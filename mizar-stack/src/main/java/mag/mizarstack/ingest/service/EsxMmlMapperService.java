package mag.mizarstack.ingest.service;

import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.ingest.dto.MappedMmlItem;
import mag.mizarstack.ingest.persistence.EsxMmlJpaDao;
import mag.mizarstack.ingest.persistence.UpsertResult;
import mag.mizarstack.ingest.stats.FileInsertStats;
import org.dom4j.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EsxMmlMapperService {

    private static final String TEXT_PROPER_XPATH =
            "//*[translate(local-name(), 'abcdefghijklmnopqrstuvwxyz', 'ABCDEFGHIJKLMNOPQRSTUVWXYZ')='TEXT-PROPER']";

    private static final String ITEM_XPATH = "//Item";
    private static final Set<String> DEFINITION_ITEM_KINDS = Set.of(
            "definition-item",
            "attribute-definition",
            "functor-definition",
            "predicate-definition",
            "structure-definition",
            "mode-definition",
            "selector-definition",
            "aggregate-definition"
    );
    private static final Set<String> CONSTRUCTOR_DEFINITION_ELEMENT_NAMES = Set.of(
            "Attribute-Definition",
            "Functor-Definition",
            "Predicate-Definition",
            "Structure-Definition",
            "Mode-Definition",
            "Selector-Definition",
            "Aggregate-Definition"
    );
    private static final Set<String> NOTATION_PATTERN_NAMES = Set.of(
            "Attribute-Pattern",
            "Mode-Pattern",
            "Predicate-Pattern",
            "Structure-Pattern",
            "SelectorFunctor-Pattern",
            "InfixFunctor-Pattern",
            "ForgetfulFunctor-Pattern",
            "AggregateFunctor-Pattern",
            "Strict-Pattern"
    );
    private static final int MAX_DEFERRED_RELATIONS = 500_000;
    private static final int ITEM_NODE_RAW_MAX = 512;
    private static final int ITEM_NODE_TEXT_MAX = 256;
    private static final int DEFERRED_RESOLVE_INTERVAL_FILES = 25;
    private static final int DEFERRED_RESOLVE_BATCH_SIZE = 20_000;
    private static final String NODE_TYPE_THEOREMS = "theorems";
    private static final String NODE_TYPE_DEFINITIONS = "definitions";
    private static final String NODE_TYPE_SCHEMES = "schemes";
    private static final String NODE_TYPE_REGISTRATIONS = "registrations";
    private static final String NODE_TYPE_SYMBOLS = "symbols";
    private static final String NODE_TYPE_NO_NODES = "no_nodes";

    private final EsxMmlJpaDao mmlDao;
    private final TransactionTemplate txTemplate;
    private final int mapperWorkerThreads;
    private final Map<String, PendingRelation> deferredPendingRelations = new ConcurrentHashMap<>();
    private final AtomicInteger mappedFileCounter = new AtomicInteger(0);

    public EsxMmlMapperService(
            EsxMmlJpaDao mmlDao,
            PlatformTransactionManager txManager,
            @Value("${app.ingest.mapper.threads:0}") int mapperWorkerThreads
    ) {
        this.mmlDao = mmlDao;
        this.txTemplate = new TransactionTemplate(txManager);
        if (mapperWorkerThreads > 0) {
            this.mapperWorkerThreads = mapperWorkerThreads;
        } else {
            int auto = Runtime.getRuntime().availableProcessors();
            this.mapperWorkerThreads = Math.max(1, Math.min(8, auto));
        }
    }

    public Map<String, Object> processArticleXml(byte[] xmlBytes, String s3Key) {
        List<String> messages = new ArrayList<>();
        return processArticleXmlInternal(xmlBytes, s3Key, messages, null);
    }

    public Map<String, Object> processArticleXml(byte[] xmlBytes, String s3Key, List<String> messages) {
        if (messages == null) messages = new ArrayList<>();
        return processArticleXmlInternal(xmlBytes, s3Key, messages, null);
    }

    public Map<String, Object> processArticleXml(byte[] xmlBytes, String s3Key, java.util.function.Consumer<String> progress) {
        List<String> messages = new ArrayList<>();
        return processArticleXmlInternal(xmlBytes, s3Key, messages, progress);
    }

    public Map<String, Integer> flushDeferredRelations(java.util.function.Consumer<String> progress) {
        int passes = 0;
        int resolvedTotal = 0;
        int noProgressPasses = 0;
        FileInsertStats flushStats = new FileInsertStats();

        while (!deferredPendingRelations.isEmpty() && passes < 1_000) {
            int before = deferredPendingRelations.size();
            int resolved = resolveDeferredRelations(flushStats, msg -> {
                if (progress != null && msg != null) {
                    progress.accept(msg);
                }
            }, DEFERRED_RESOLVE_BATCH_SIZE * 2);

            resolvedTotal += resolved;
            passes++;

            int after = deferredPendingRelations.size();
            if (resolved <= 0 || after >= before) {
                noProgressPasses++;
            } else {
                noProgressPasses = 0;
            }

            if (noProgressPasses >= 3) {
                break;
            }
        }

        if (progress != null) {
            progress.accept("Deferred flush complete: resolved=" + resolvedTotal
                    + " remaining=" + deferredPendingRelations.size()
                    + " passes=" + passes);
        }

        return Map.of(
                "resolved", resolvedTotal,
                "remaining", deferredPendingRelations.size(),
                "passes", passes
        );
    }

    private Map<String, Object> processArticleXmlInternal(
            byte[] xmlBytes,
            String s3Key,
            List<String> messages,
            java.util.function.Consumer<String> progress
    ) {
        int mappedFileOrdinal = mappedFileCounter.incrementAndGet();
        var parsedItems = new AtomicInteger(0);
        var failedItems = new AtomicInteger(0);
        var itemSequence = new AtomicInteger(0);
        FileInsertStats insertStats = new FileInsertStats();

        // libId -> mml_item.id
        Map<String, UUID> libIdToItem = new ConcurrentHashMap<>();
        List<PendingRelation> pendingRelations = Collections.synchronizedList(new ArrayList<>());
        // Shared caches are safe in single-thread mode.
        // With parallel workers they can leak uncommitted IDs across transactions
        // and cause FK violations (e.g. notation_symbol -> symbol).
        Map<String, UUID> symbolCache = (mapperWorkerThreads <= 1) ? new ConcurrentHashMap<>() : null;
        Map<String, UUID> formatCache = (mapperWorkerThreads <= 1) ? new ConcurrentHashMap<>() : null;

        final List<String> finalMessages = Collections.synchronizedList((messages == null) ? new ArrayList<>() : messages);

        java.util.function.Consumer<String> m = msg -> {
            finalMessages.add(msg);
            if (progress != null) {
                try { progress.accept(msg); } catch (Exception e) { log.warn("progress consumer failed", e); }
            }
            log.debug("ESX-Mapper: {}", msg);
        };

        try {
            m.accept("Starting mapping for " + (s3Key == null ? "raw-bytes" : s3Key));

            String fallbackArticle = deriveArticleNameFromS3Key(s3Key);
            UUID fallbackArticleId = upsertArticle(
                    fallbackArticle,
                    s3Key,
                    new String(xmlBytes, StandardCharsets.UTF_8),
                    insertStats
            );

            AtomicReference<UUID> currentArticleId = new AtomicReference<>(fallbackArticleId);
            AtomicReference<String> currentArticleName = new AtomicReference<>(fallbackArticle);

            m.accept("Article context (fallback): " + fallbackArticle + " (id=" + fallbackArticleId + ")");
            m.accept("Mapper workers: " + mapperWorkerThreads);
            if (mapperWorkerThreads > 1) {
                m.accept("Shared symbol/format cache disabled in parallel mode to avoid cross-transaction stale IDs.");
            }

            // Streaming SAX ingestion that is namespace/case safe.
            // We extract each <Item> subtree as XML and map it to DB.
            ingestItemsSax(xmlBytes, s3Key, currentArticleId, currentArticleName,
                    parsedItems, failedItems, itemSequence, libIdToItem, pendingRelations, symbolCache, formatCache, insertStats, m);

            if (parsedItems.get() == 0) {
                detectLikelyItemElements(xmlBytes, m);
            }

            m.accept("Parsing complete: totalItems=" + parsedItems.get() + " pendingRelations=" + pendingRelations.size());

            AtomicInteger resolvedRefs = new AtomicInteger(0);
            int total = pendingRelations.size();
            List<PendingRelation> unresolvedRelations = new ArrayList<>();
            Set<String> unresolvedConstructorLibIds = pendingRelations.stream()
                    .map(p -> p.constructorLibId)
                    .filter(Objects::nonNull)
                    .filter(s -> !s.isBlank())
                    .filter(s -> !libIdToItem.containsKey(s))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, UUID> dbResolvedConstructors = mmlDao.findConstructorItemIdsByLibIds(unresolvedConstructorLibIds);

            m.accept("Resolving pending relations: " + total);
            m.accept("Prefetched constructor mappings: " + dbResolvedConstructors.size() + " / " + unresolvedConstructorLibIds.size());

            txTemplate.executeWithoutResult(status -> {
                for (PendingRelation pending : pendingRelations) {
                    UUID constructorItemId = dbResolvedConstructors.get(pending.constructorLibId);
                    if (constructorItemId == null) {
                        constructorItemId = libIdToItem.get(pending.constructorLibId);
                    }
                    if (constructorItemId == null) {
                        unresolvedRelations.add(pending);
                        continue;
                    }
                    resolvePendingRelation(pending, constructorItemId, insertStats);
                    int r = resolvedRefs.incrementAndGet();
                    if (r % 500 == 0) m.accept("Resolved relations: " + r + " / " + total);
                }
            });

            if (!unresolvedRelations.isEmpty()) {
                enqueueDeferredRelations(unresolvedRelations);
                m.accept("Deferred unresolved relations for cross-file resolution: "
                        + unresolvedRelations.size() + " (queue=" + deferredPendingRelations.size() + ")");
            }
            boolean shouldResolveDeferred = (mappedFileOrdinal % DEFERRED_RESOLVE_INTERVAL_FILES == 0)
                    || deferredPendingRelations.size() >= (MAX_DEFERRED_RELATIONS / 2);
            if (shouldResolveDeferred) {
                int deferredResolved = resolveDeferredRelations(insertStats, m, DEFERRED_RESOLVE_BATCH_SIZE);
                if (deferredResolved > 0) {
                    m.accept("Resolved deferred relations after file commit: " + deferredResolved
                            + " (queue=" + deferredPendingRelations.size() + ")");
                }
            }

            m.accept("Resolving complete: resolved=" + resolvedRefs.get()
                    + " unresolved=" + (pendingRelations.size() - resolvedRefs.get()));
            m.accept("Inserted rows by type: " + formatInsertStats(insertStats.snapshotOrdered()));

        } catch (Exception e) {
            log.warn("Error while mapping ESX XML", e);
            finalMessages.add("fatal: " + e.getMessage());
            if (progress != null) {
                try { progress.accept("fatal: " + e.getMessage()); } catch (Exception ex) { log.warn("progress consumer failed", ex); }
            }
        }

        Map<String, Long> insertedCounts = insertStats.snapshotOrdered();
        return Map.of(
                "processedItems", parsedItems.get(),
                "failedItems", failedItems.get(),
                "pendingRefs", pendingRelations.size(),
                "insertedCounts", insertedCounts,
                "messages", finalMessages
        );
    }

    private void ingestItemsSax(
            byte[] xmlBytes,
            String s3Key,
            AtomicReference<UUID> currentArticleId,
            AtomicReference<String> currentArticleName,
            AtomicInteger parsedItems,
            AtomicInteger failedItems,
            AtomicInteger itemSequence,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations,
            Map<String, UUID> symbolCache,
            Map<String, UUID> formatCache,
            FileInsertStats insertStats,
            java.util.function.Consumer<String> m
    ) throws Exception {
        javax.xml.parsers.SAXParserFactory f = javax.xml.parsers.SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignore) {}
        try { f.setFeature("http://xml.org/sax/features/validation", false); } catch (Exception ignore) {}

        final ExecutorService itemExecutor = createItemExecutor();
        var parser = f.newSAXParser();

        try {
            parser.parse(new ByteArrayInputStream(xmlBytes), new org.xml.sax.helpers.DefaultHandler() {
                private int itemDepth = 0;
                private StringBuilder itemXml;

                private String elementName(String localName, String qName) {
                    return (qName != null && !qName.isBlank()) ? qName : ((localName != null && !localName.isBlank()) ? localName : "");
                }

                private String elementLocal(String localName, String qName) {
                    // used for matching by local-name ignoring prefix
                    if (localName != null && !localName.isBlank()) return localName;
                    if (qName == null) return "";
                    int idx = qName.indexOf(':');
                    return idx >= 0 ? qName.substring(idx + 1) : qName;
                }

                @Override
                public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes) {
                    String tagName = elementName(localName, qName);
                    String local = elementLocal(localName, qName);

                    // Update article context (Text-Proper/@articleid)
                    if (equalsAnyIgnoreCase(local, "Text-Proper")) {
                        String article = attrIgnoreCase(attributes, "articleid");
                        if (article != null && !article.isBlank()) {
                            try {
                                UUID aid = upsertArticle(article, s3Key, new String(xmlBytes, StandardCharsets.UTF_8), insertStats);
                                currentArticleId.set(aid);
                                currentArticleName.set(article);
                                m.accept("Article detected from Text-Proper: " + article + " (id=" + aid + ")");
                            } catch (Exception ex) {
                                log.warn("Text-Proper detection failed", ex);
                            }
                        }
                    }

                    // Capture <Item> subtree (match by local name, ignore namespace prefix)
                    if (equalsAnyIgnoreCase(local, "Item")) {
                        if (itemDepth == 0) {
                            itemXml = new StringBuilder(4096);
                        }
                        itemDepth++;
                    }

                    if (itemDepth > 0) {
                        appendStartTag(itemXml, tagName, attributes);
                    }
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    if (itemDepth > 0 && itemXml != null) {
                        // escape basic XML chars
                        for (int i = start; i < start + length; i++) {
                            char c = ch[i];
                            switch (c) {
                                case '&' -> itemXml.append("&amp;");
                                case '<' -> itemXml.append("&lt;");
                                case '>' -> itemXml.append("&gt;");
                                case '"' -> itemXml.append("&quot;");
                                case '\'' -> itemXml.append("&apos;");
                                default -> itemXml.append(c);
                            }
                        }
                    }
                }

                @Override
                public void endElement(String uri, String localName, String qName) {
                    String tagName = elementName(localName, qName);
                    String local = elementLocal(localName, qName);

                    if (itemDepth > 0 && itemXml != null) {
                        itemXml.append("</").append(tagName).append('>');
                    }

                    if (equalsAnyIgnoreCase(local, "Item")) {
                        itemDepth--;
                        if (itemDepth == 0) {
                            final String itemXmlSnapshot = itemXml.toString();
                            final UUID articleIdSnapshot = currentArticleId.get();
                            final String articleNameSnapshot = currentArticleName.get();
                            final int itemSequenceSnapshot = itemSequence.incrementAndGet();

                            Runnable task = () -> processItemSubtree(
                                    itemXmlSnapshot,
                                    xmlBytes,
                                    s3Key,
                                    itemSequenceSnapshot,
                                    articleIdSnapshot,
                                    articleNameSnapshot,
                                    currentArticleId,
                                    currentArticleName,
                                    parsedItems,
                                    failedItems,
                                    libIdToItem,
                                    pendingRelations,
                                    symbolCache,
                                    formatCache,
                                    insertStats,
                                    m
                            );

                            try {
                                if (itemExecutor != null) {
                                    itemExecutor.execute(task);
                                } else {
                                    task.run();
                                }
                            } catch (Exception ex) {
                                failedItems.incrementAndGet();
                                log.warn("Failed to submit Item subtree task", ex);
                                m.accept("Failed to submit Item subtree task: " + ex.getMessage());
                            } finally {
                                itemXml = null;
                            }
                        }
                    }
                }
            });
        } finally {
            awaitItemExecutor(itemExecutor, m);
        }
    }

    private ExecutorService createItemExecutor() {
        if (mapperWorkerThreads <= 1) {
            return null;
        }
        int queueCapacity = Math.max(32, mapperWorkerThreads * 8);
        return new ThreadPoolExecutor(
                mapperWorkerThreads,
                mapperWorkerThreads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private void awaitItemExecutor(ExecutorService itemExecutor, java.util.function.Consumer<String> m) throws InterruptedException {
        if (itemExecutor == null) return;

        itemExecutor.shutdown();
        if (!itemExecutor.awaitTermination(1, TimeUnit.HOURS)) {
            itemExecutor.shutdownNow();
            m.accept("Mapper workers timeout: forced shutdown");
        }
    }

    private void processItemSubtree(
            String itemXml,
            byte[] xmlBytes,
            String s3Key,
            int itemSequence,
            UUID articleIdHint,
            String articleNameHint,
            AtomicReference<UUID> currentArticleId,
            AtomicReference<String> currentArticleName,
            AtomicInteger parsedItems,
            AtomicInteger failedItems,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations,
            Map<String, UUID> symbolCache,
            Map<String, UUID> formatCache,
            FileInsertStats insertStats,
            java.util.function.Consumer<String> m
    ) {
        try {
            txTemplate.executeWithoutResult(status -> {
                try {
                    Element itemEl = org.dom4j.DocumentHelper.parseText(itemXml).getRootElement();

                    UUID articleId = articleIdHint != null ? articleIdHint : currentArticleId.get();
                    String articleName = (articleNameHint != null && !articleNameHint.isBlank())
                            ? articleNameHint
                            : currentArticleName.get();

                    if (articleId == null) {
                        articleId = upsertArticle(
                                deriveArticleNameFromS3Key(s3Key),
                                s3Key,
                                new String(xmlBytes, StandardCharsets.UTF_8),
                                insertStats
                        );
                        currentArticleId.compareAndSet(null, articleId);
                    }
                    if (articleName == null || articleName.isBlank()) {
                        articleName = deriveArticleNameFromS3Key(s3Key);
                        currentArticleName.compareAndSet(null, articleName);
                    }

                    MappedMmlItem statementItem = extractItem(itemEl, articleName, itemSequence);
                    UUID statementItemId = upsertMmlItem(articleId, statementItem, insertStats);

                    if (statementItem.libId != null && !statementItem.libId.isBlank()) {
                        libIdToItem.put(statementItem.libId, statementItemId);
                    }

                    String statementKind = normalizeStatementKind(statementItem.subKind);
                    String rootNodeType = classifyRootItemNodeType(itemEl, statementKind);

                    try {
                        insertStatement(statementItemId, statementKind, statementItem.textContent, insertStats);
                    } catch (Exception ignore) {
                    }

                    // Persist every XML tag from this Item subtree into item_node for future sequence/context queries.
                    mapAllItemNodes(
                            itemEl,
                            statementItemId,
                            articleId,
                            rootNodeType,
                            pendingRelations,
                            symbolCache,
                            formatCache,
                            insertStats
                    );

                    String itemKind = Optional.ofNullable(itemEl.attributeValue("kind")).orElse("");
                    if (isDefinitionItemKind(itemKind)) {
                        processDefinitionItem(itemEl, itemKind, articleId, articleName, statementItemId, libIdToItem, pendingRelations, symbolCache, formatCache, insertStats);
                    }
                    if (isRegistrationItemKind(itemKind) || isDefinitionContainerItemKind(itemKind)) {
                        processRegistrationItem(
                                itemEl,
                                articleId,
                                articleName,
                                libIdToItem,
                                pendingRelations,
                                symbolCache,
                                formatCache,
                                insertStats
                        );
                    }

                    int count = parsedItems.incrementAndGet();
                    if (count == 1) {
                        m.accept("First item seen: elementName=Item kind=" + statementItem.kind + " subkind=" + statementItem.subKind + " number=" + statementItem.number + " libId=" + statementItem.libId);
                    }
                    if (count % 200 == 0) m.accept("Processed items: " + count);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });

        } catch (Exception ex) {
            Throwable root = ex.getCause() != null ? ex.getCause() : ex;
            failedItems.incrementAndGet();
            log.warn("Failed to process Item subtree", root);
            m.accept("Failed to process Item subtree: " + root.getMessage());
        }
    }

    private static void appendStartTag(StringBuilder sb, String name, org.xml.sax.Attributes attributes) {
        sb.append('<').append(name);
        for (int i = 0; i < attributes.getLength(); i++) {
            String an = attributes.getQName(i);
            if (an == null || an.isBlank()) an = attributes.getLocalName(i);
            String av = attributes.getValue(i);
            if (an == null || an.isBlank() || av == null) continue;
            sb.append(' ').append(an).append("=\"");
            // minimal escape
            sb.append(av.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"));
            sb.append('"');
        }
        sb.append('>');
    }

    private static String attrIgnoreCase(org.xml.sax.Attributes atts, String name) {
        for (int i = 0; i < atts.getLength(); i++) {
            String an = atts.getLocalName(i);
            if (an == null || an.isBlank()) an = atts.getQName(i);
            if (an != null && an.equalsIgnoreCase(name)) {
                return atts.getValue(i);
            }
        }
        return null;
    }

    private static final int DETECT_MAX_ELEMENTS = 50_000;
    private static final int DETECT_TOP_K = 25;

    private void detectLikelyItemElements(byte[] xmlBytes, java.util.function.Consumer<String> m) {
        final Map<String, Integer> nameCounts = new HashMap<>();
        final Map<String, Integer> interestingCounts = new HashMap<>();
        final AtomicInteger total = new AtomicInteger();

        try {
            m.accept("No <Item> matched by ITEM_XPATH. Running ESX element auto-detection (first " + DETECT_MAX_ELEMENTS + " start-tags)...");

            javax.xml.parsers.SAXParserFactory f = javax.xml.parsers.SAXParserFactory.newInstance();
            f.setNamespaceAware(true);
            // Bez walidacji/DTD: szybciej i bez zewnętrznych fetchy
            try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignore) {}
            try { f.setFeature("http://xml.org/sax/features/validation", false); } catch (Exception ignore) {}

            var parser = f.newSAXParser();

            parser.parse(new ByteArrayInputStream(xmlBytes), new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes) {
                    if (total.incrementAndGet() > DETECT_MAX_ELEMENTS) {
                        throw new StopDetect();
                    }

                    String name = (localName != null && !localName.isBlank()) ? localName : qName;
                    if (name == null) name = "";

                    nameCounts.merge(name, 1, Integer::sum);

                    boolean interesting = false;
                    for (int i = 0; i < attributes.getLength(); i++) {
                        String an = attributes.getLocalName(i);
                        if (an == null || an.isBlank()) an = attributes.getQName(i);
                        if (an == null) continue;

                        if (equalsAnyIgnoreCase(an,
                                "MMLId", "absoluteconstrMMLId", "absolutepatternMMLId",
                                "articleid", "xmlid", "idnr", "kind", "nr", "number")) {
                            interesting = true;
                            break;
                        }
                    }
                    if (interesting) {
                        interestingCounts.merge(name, 1, Integer::sum);
                    }
                }
            });


        } catch (StopDetect stop) {
            // expected
        } catch (Exception ex) {
            m.accept("Auto-detection failed: " + ex.getMessage());
            return;
        }

        m.accept("Auto-detection summary: scannedStartTags=" + total.get()
                + " uniqueElements=" + nameCounts.size()
                + " interestingElements=" + interestingCounts.size());

        m.accept("Top elements (by frequency): " + topK(nameCounts, DETECT_TOP_K));
        m.accept("Top elements with interesting attrs (MMLId/xmlid/kind/nr/...): " + topK(interestingCounts, DETECT_TOP_K));

        m.accept("Next step: set ITEM_XPATH to match the real item element name from the list above (case/namespace safe). ");
    }

    private static String topK(Map<String, Integer> counts, int k) {
        if (counts == null || counts.isEmpty()) return "<none>";
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(k)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String formatInsertStats(Map<String, Long> stats) {
        if (stats == null || stats.isEmpty()) {
            return "<none>";
        }
        return stats.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static boolean equalsAnyIgnoreCase(String s, String... options) {
        if (s == null) return false;
        for (String o : options) {
            if (o != null && s.equalsIgnoreCase(o)) return true;
        }
        return false;
    }

    // -------------------- DB: Article --------------------

    private UUID upsertArticle(String articleName, String s3Key, String xmlContent, FileInsertStats stats) {
        UpsertResult result = mmlDao.upsertArticle(articleName, s3Key, xmlContent);
        if (result.inserted()) {
            stats.add(FileInsertStats.ARTICLE, 1);
        }
        return result.id();
    }

    // -------------------- DB: Items (UPSERT) --------------------

    private UUID upsertMmlItem(UUID articleId, MappedMmlItem it, FileInsertStats stats) {
        UpsertResult result = mmlDao.upsertMmlItem(articleId, it);
        if (result.inserted()) {
            stats.add(FileInsertStats.MML_ITEM, 1);
        }
        return result.id();
    }

    private void insertConstructor(UUID itemId, String constructorKind, String shortName, FileInsertStats stats) {
        stats.add(FileInsertStats.CONSTRUCTOR, mmlDao.insertConstructor(itemId, constructorKind, shortName));
    }

    private void insertNotation(UUID itemId, UUID articleId, String notationKind, FileInsertStats stats) {
        stats.add(FileInsertStats.NOTATION, mmlDao.insertNotation(itemId, notationKind));
    }

    private void insertStatement(UUID itemId, String statementKind, String text, FileInsertStats stats) {
        stats.add(FileInsertStats.STATEMENT, mmlDao.insertStatement(itemId, statementKind, text));
    }

    private void insertRegistration(UUID itemId, String regKind, FileInsertStats stats) {
        stats.add(FileInsertStats.REGISTRATION, mmlDao.insertRegistration(itemId, regKind));
    }

    private void insertItemConstructorRef(UUID itemId, UUID constructorItemId, String role, boolean isPositive, int occurrences, Map<String, Object> details, FileInsertStats stats) {
        stats.add(FileInsertStats.ITEM_CONSTRUCTOR_REF, mmlDao.insertItemConstructorRef(itemId, constructorItemId, role, isPositive, occurrences, details));
    }

    private void insertNotationFormat(UUID notationItemId, UUID formatId, FileInsertStats stats) {
        stats.add(FileInsertStats.NOTATION_FORMAT, mmlDao.insertNotationFormat(notationItemId, formatId));
    }

    private void insertFormatSymbol(UUID formatId, UUID symbolId, int pos, FileInsertStats stats) {
        stats.add(FileInsertStats.FORMAT_SYMBOL, mmlDao.insertFormatSymbol(formatId, symbolId, pos));
    }

    private void insertConstructorDefinition(UUID constructorItemId, UUID definitionStatementItemId, FileInsertStats stats) {
        stats.add(FileInsertStats.CONSTRUCTOR_DEFINITION, mmlDao.insertConstructorDefinition(constructorItemId, definitionStatementItemId));
    }

    private void insertConstructorDefiniens(UUID definiensStatementItemId, UUID constructorItemId, FileInsertStats stats) {
        stats.add(FileInsertStats.CONSTRUCTOR_DEFINIENS, mmlDao.insertConstructorDefiniens(definiensStatementItemId, constructorItemId));
    }

    private void insertRegistrationRelation(UUID registrationItemId, UUID constructorItemId, String role, boolean isPositive, FileInsertStats stats) {
        stats.add(FileInsertStats.REGISTRATION_RELATION, mmlDao.insertRegistrationRelation(registrationItemId, constructorItemId, role, isPositive));
    }

    private void insertItemNode(
            UUID nodeId,
            UUID itemId,
            UUID parentNodeId,
            String nodePath,
            String nodeType,
            UUID constructorItemId,
            UUID symbolId,
            UUID formatId,
            int pos,
            int depth,
            String raw,
            Map<String, Object> details,
            FileInsertStats stats
    ) {
        int inserted = mmlDao.insertItemNode(
                nodeId,
                itemId,
                parentNodeId,
                nodePath,
                nodeType,
                constructorItemId,
                symbolId,
                formatId,
                pos,
                depth,
                raw,
                details
        );
        stats.add(FileInsertStats.ITEM_NODE, inserted);
        if (inserted > 0) {
            if (constructorItemId != null) {
                stats.add(FileInsertStats.ITEM_NODE_WITH_CONSTRUCTOR, inserted);
            }
            if (symbolId != null) {
                stats.add(FileInsertStats.ITEM_NODE_WITH_SYMBOL, inserted);
            }
            if (formatId != null) {
                stats.add(FileInsertStats.ITEM_NODE_WITH_FORMAT, inserted);
            }
        }
    }

    private void updateItemNodeConstructor(UUID nodeId, UUID constructorItemId, FileInsertStats stats) {
        int updated = mmlDao.updateItemNodeConstructor(nodeId, constructorItemId);
        stats.add(FileInsertStats.ITEM_NODE_WITH_CONSTRUCTOR, updated);
    }

    private void resolvePendingRelation(PendingRelation pending, UUID constructorItemId, FileInsertStats insertStats) {
        if (pending == null || constructorItemId == null) {
            return;
        }
        switch (pending.type) {
            case ITEM_CONSTRUCTOR_REF ->
                    insertItemConstructorRef(pending.sourceItemId, constructorItemId, pending.role, pending.isPositive, pending.occurrences, pending.details, insertStats);
            case NOTATION_CONSTRUCTOR ->
                    insertNotationConstructor(pending.sourceItemId, constructorItemId, pending.role, insertStats);
            case CONSTRUCTOR_DEFINITION ->
                    insertConstructorDefinition(constructorItemId, pending.sourceItemId, insertStats);
            case CONSTRUCTOR_DEFINIENS ->
                    insertConstructorDefiniens(pending.sourceItemId, constructorItemId, insertStats);
            case REGISTRATION_RELATION ->
                    insertRegistrationRelation(pending.sourceItemId, constructorItemId, pending.role, pending.isPositive, insertStats);
            case ITEM_NODE_CONSTRUCTOR ->
                    updateItemNodeConstructor(pending.sourceItemId, constructorItemId, insertStats);
        }
    }

    private void enqueueDeferredRelations(List<PendingRelation> unresolvedRelations) {
        if (unresolvedRelations == null || unresolvedRelations.isEmpty()) {
            return;
        }
        if (deferredPendingRelations.size() >= MAX_DEFERRED_RELATIONS) {
            return;
        }
        for (PendingRelation pending : unresolvedRelations) {
            if (pending == null) continue;
            if (deferredPendingRelations.size() >= MAX_DEFERRED_RELATIONS) break;
            deferredPendingRelations.putIfAbsent(deferredRelationKey(pending), pending);
        }
    }

    private int resolveDeferredRelations(
            FileInsertStats insertStats,
            java.util.function.Consumer<String> m,
            int maxBatchSize
    ) {
        if (deferredPendingRelations.isEmpty()) {
            return 0;
        }
        int batchLimit = Math.max(1_000, maxBatchSize);
        List<Map.Entry<String, PendingRelation>> snapshot = new ArrayList<>(Math.min(batchLimit, deferredPendingRelations.size()));
        for (Map.Entry<String, PendingRelation> entry : deferredPendingRelations.entrySet()) {
            snapshot.add(entry);
            if (snapshot.size() >= batchLimit) {
                break;
            }
        }
        if (snapshot.isEmpty()) {
            return 0;
        }

        Set<String> unresolvedConstructorLibIds = snapshot.stream()
                .map(Map.Entry::getValue)
                .map(p -> p.constructorLibId)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (unresolvedConstructorLibIds.isEmpty()) {
            return 0;
        }

        Map<String, UUID> dbResolvedConstructors = mmlDao.findConstructorItemIdsByLibIds(unresolvedConstructorLibIds);
        if (dbResolvedConstructors.isEmpty()) {
            return 0;
        }

        AtomicInteger resolved = new AtomicInteger(0);
        txTemplate.executeWithoutResult(status -> {
            for (Map.Entry<String, PendingRelation> entry : snapshot) {
                PendingRelation pending = entry.getValue();
                if (pending == null) continue;
                UUID constructorItemId = dbResolvedConstructors.get(pending.constructorLibId);
                if (constructorItemId == null) continue;
                resolvePendingRelation(pending, constructorItemId, insertStats);
                deferredPendingRelations.remove(entry.getKey(), pending);
                resolved.incrementAndGet();
            }
        });
        if (resolved.get() > 0) {
            m.accept("Deferred relations queue size: " + deferredPendingRelations.size());
        }
        return resolved.get();
    }

    private static String deferredRelationKey(PendingRelation pending) {
        String constructorLibId = safeDeferredValue(pending.constructorLibId);
        String role = safeDeferredValue(pending.role);
        int detailsHash = (pending.details == null) ? 0 : pending.details.hashCode();
        return pending.type + "|"
                + pending.sourceItemId + "|"
                + constructorLibId + "|"
                + role + "|"
                + pending.isPositive + "|"
                + pending.occurrences + "|"
                + detailsHash;
    }

    private static String safeDeferredValue(String value) {
        return (value == null || value.isBlank()) ? "_" : value;
    }

    private boolean isDefinitionItemKind(String itemKind) {
        return itemKind != null && DEFINITION_ITEM_KINDS.contains(itemKind.toLowerCase(Locale.ROOT));
    }

    private boolean isClusterItemKind(String itemKind) {
        return itemKind != null && "cluster".equalsIgnoreCase(itemKind);
    }

    private boolean isRegistrationItemKind(String itemKind) {
        if (itemKind == null) {
            return false;
        }
        String lowerKind = itemKind.toLowerCase(Locale.ROOT);
        return "cluster".equals(lowerKind) || lowerKind.contains("registration");
    }

    private boolean isDefinitionContainerItemKind(String itemKind) {
        return itemKind != null && "definition-item".equalsIgnoreCase(itemKind);
    }

    private void processDefinitionItem(
            Element itemEl,
            String itemKind,
            UUID articleId,
            String articleName,
            UUID statementItemId,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations,
            Map<String, UUID> symbolCache,
            Map<String, UUID> formatCache,
            FileInsertStats insertStats
    ) {
        Element definitionRoot = firstDirectChildByName(itemEl, itemKind);
        if (definitionRoot == null) return;

        List<Element> concreteDefinitions = findConstructorDefinitionElements(definitionRoot);
        if (concreteDefinitions.isEmpty()) {
            concreteDefinitions = List.of(definitionRoot);
        }

        for (Element definitionEl : concreteDefinitions) {
            List<Element> patternElements = findNotationPatternElements(definitionEl);
            for (Element patternEl : patternElements) {
                processNotationPattern(patternEl, articleId, articleName, null, libIdToItem, pendingRelations, symbolCache, formatCache, insertStats);
            }
        }
    }

    private void processRegistrationItem(
            Element itemEl,
            UUID articleId,
            String articleName,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations,
            Map<String, UUID> symbolCache,
            Map<String, UUID> formatCache,
            FileInsertStats insertStats
    ) {
        List<Element> registrationElements = findRegistrationElements(itemEl);
        if (registrationElements.isEmpty()) {
            return;
        }

        for (Element registrationEl : registrationElements) {
            String regKind = registrationKindFromElementName(registrationEl.getName());
            String regLibId = buildRegistrationLibId(articleName, regKind, registrationEl.attributeValue("xmlid"));

            MappedMmlItem regItem = buildSpecializedItem(registrationEl, "registration", regKind, regLibId, articleName);
            UUID registrationItemId = upsertMmlItem(articleId, regItem, insertStats);
            insertRegistration(registrationItemId, normalizeRegistrationKind(regKind), insertStats);
            if (regItem.libId != null && !regItem.libId.isBlank()) {
                libIdToItem.put(regItem.libId, registrationItemId);
            }

            // Persist registration subtree under registration item id so registration views can classify roots correctly.
            mapAllItemNodes(
                    registrationEl,
                    registrationItemId,
                    articleId,
                    NODE_TYPE_REGISTRATIONS,
                    pendingRelations,
                    symbolCache,
                    formatCache,
                    insertStats
            );

        }
    }

    private List<Element> findConstructorDefinitionElements(Element root) {
        if (root == null) {
            return List.of();
        }
        List<Element> out = new ArrayList<>();
        if (CONSTRUCTOR_DEFINITION_ELEMENT_NAMES.contains(root.getName())) {
            out.add(root);
        }
        List<org.dom4j.Node> descendants = root.selectNodes(".//*");
        for (org.dom4j.Node n : descendants) {
            if (!(n instanceof Element e)) continue;
            if (CONSTRUCTOR_DEFINITION_ELEMENT_NAMES.contains(e.getName())) {
                out.add(e);
            }
        }
        return out;
    }

    private List<Element> findRegistrationElements(Element root) {
        if (root == null) {
            return List.of();
        }

        List<Element> out = new ArrayList<>();
        if (isRegistrationElementName(root.getName())) {
            out.add(root);
        }

        List<org.dom4j.Node> descendants = root.selectNodes(
                ".//Conditional-Registration | .//Existential-Registration | .//Functorial-Registration"
        );
        for (org.dom4j.Node n : descendants) {
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    private MappedMmlItem buildSpecializedItem(Element sourceEl, String kind, String subKind, String libId, String articleName) {
        MappedMmlItem it = new MappedMmlItem();
        it.kind = kind;
        it.subKind = subKind;
        it.libId = libId;
        it.shortName = sourceEl.attributeValue("idnr");
        it.title = firstNonBlank(sourceEl.attributeValue("spelling"), sourceEl.attributeValue("idnr"), sourceEl.getName());
        it.textContent = extractTextContent(sourceEl);
        it.rawXml = sourceEl.asXML();

        int fromLib = parseTrailingNumberFromLibId(libId);
        int fromNode = findNumber(sourceEl);
        it.number = (fromLib > 0) ? fromLib : fromNode;

        if ((it.libId == null || it.libId.isBlank()) && articleName != null && !articleName.isBlank()) {
            String xmlid = sourceEl.attributeValue("xmlid");
            if (xmlid != null && !xmlid.isBlank()) {
                it.libId = articleName + ":" + subKind + ":" + xmlid;
            }
        }
        return it;
    }

    private String extractDefinitionConstructorLibId(Element definitionEl, String articleName) {
        String mmlId = definitionEl.attributeValue("MMLId");
        if (mmlId != null && !mmlId.isBlank()) return mmlId;

        List<org.dom4j.Node> nodes = definitionEl.selectNodes(".//*[@absoluteconstrMMLId]");
        for (org.dom4j.Node n : nodes) {
            if (!(n instanceof Element e)) continue;
            String lib = e.attributeValue("absoluteconstrMMLId");
            if (lib == null || lib.isBlank()) continue;
            if (isLocalLibId(lib, articleName)) return lib;
        }
        return null;
    }

    private List<Element> findNotationPatternElements(Element root) {
        List<Element> out = new ArrayList<>();
        List<org.dom4j.Node> all = root.selectNodes(".//*");
        for (org.dom4j.Node n : all) {
            if (n instanceof Element e && NOTATION_PATTERN_NAMES.contains(e.getName())) {
                out.add(e);
            }
        }
        return out;
    }

    private void processNotationPattern(
            Element patternEl,
            UUID articleId,
            String articleName,
            String fallbackConstructorLibId,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations,
            Map<String, UUID> symbolCache,
            Map<String, UUID> formatCache,
            FileInsertStats insertStats
    ) {
        String patternLibId = firstNonBlank(patternEl.attributeValue("absolutepatternMMLId"), patternEl.attributeValue("MMLId"));
        if (patternLibId == null || patternLibId.isBlank()) return;

        String notationKind = notationKindFromPatternName(patternEl.getName());
        String notationLibId = buildNotationLibId(patternLibId, patternEl.getName(), patternEl.attributeValue("xmlid"));
        MappedMmlItem notationItem = buildSpecializedItem(patternEl, "notation", notationKind, notationLibId, articleName);

        UUID notationItemId = upsertMmlItem(articleId, notationItem, insertStats);
        insertNotation(notationItemId, articleId, normalizeNotationKind(notationKind, patternEl), insertStats);
        if (notationItem.libId != null && !notationItem.libId.isBlank()) {
            libIdToItem.put(notationItem.libId, notationItemId);
        }

        processNotationChildren(patternEl, notationItemId, articleId, libIdToItem, pendingRelations, fallbackConstructorLibId, symbolCache, formatCache, insertStats);
    }

    private String constructorKindFromDefinitionName(String definitionName) {
        if (definitionName == null) return null;
        String lower = definitionName.toLowerCase(Locale.ROOT);
        if (lower.contains("attribute")) return "attr";
        if (lower.contains("functor")) return "func";
        if (lower.contains("predicate")) return "pred";
        if (lower.contains("mode")) return "mode";
        if (lower.contains("structure")) return "struct";
        if (lower.contains("selector")) return "sel";
        if (lower.contains("aggregate")) return "aggr";
        return null;
    }

    private String notationKindFromPatternName(String patternName) {
        if (patternName == null) return "funcnot";
        String lower = patternName.toLowerCase(Locale.ROOT);
        if (lower.contains("attribute") || lower.contains("strict")) return "attrnot";
        if (lower.contains("mode")) return "modenot";
        if (lower.contains("predicate")) return "prednot";
        if (lower.contains("selector")) return "selnot";
        if (lower.contains("structure")) return "structnot";
        if (lower.contains("aggregate")) return "aggrnot";
        return "funcnot";
    }

    private String registrationKindFromElementName(String elementName) {
        if (elementName == null) return "condreg";
        return switch (elementName.toLowerCase(Locale.ROOT)) {
            case "existential-registration" -> "exreg";
            case "functorial-registration" -> "funcreg";
            default -> "condreg";
        };
    }

    private boolean isRegistrationElementName(String elementName) {
        return equalsAnyIgnoreCase(elementName, "Conditional-Registration", "Existential-Registration", "Functorial-Registration");
    }

    private String deriveRegistrationRole(Element refEl) {
        Element adjectiveCluster = nearestAncestor(refEl, "Adjective-Cluster");
        if (adjectiveCluster != null) {
            String role = adjectiveCluster.attributeValue("role");
            if (role != null && !role.isBlank()) return role.toLowerCase(Locale.ROOT);
            return "cluster";
        }
        if (hasAncestor(refEl, "Clustered-Type")) return "basetype";
        return "cluster";
    }

    private boolean isNegativeReference(Element el) {
        return isTrue(el.attributeValue("nonocc")) || isTrue(el.attributeValue("antonymic"));
    }

    private static boolean isTrue(String v) {
        return v != null && ("true".equalsIgnoreCase(v) || "1".equals(v));
    }

    private Element nearestAncestor(Element start, String ancestorName) {
        Element parent = (start == null) ? null : start.getParent();
        while (parent != null) {
            if (parent.getName().equalsIgnoreCase(ancestorName)) return parent;
            parent = parent.getParent();
        }
        return null;
    }

    private boolean hasAncestor(Element start, String ancestorName) {
        return nearestAncestor(start, ancestorName) != null;
    }

    private static String buildNotationLibId(String patternLibId, String patternName, String xmlid) {
        String suffix = (xmlid == null || xmlid.isBlank()) ? (patternName == null ? "pattern" : patternName) : xmlid;
        return "pattern:" + patternLibId + ":" + suffix;
    }

    private static String buildRegistrationLibId(String articleName, String regKind, String xmlid) {
        String suffix = (xmlid == null || xmlid.isBlank()) ? "reg" : xmlid;
        return articleName + ":" + regKind + ":" + suffix;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static int parseTrailingNumberFromLibId(String libId) {
        if (libId == null || libId.isBlank()) return 0;
        String tail = libId;
        int idx = libId.lastIndexOf(':');
        if (idx >= 0 && idx + 1 < libId.length()) {
            tail = libId.substring(idx + 1);
        }
        String digits = tail.replaceAll("\\D+", "");
        if (digits.isBlank()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static boolean isLocalLibId(String libId, String articleName) {
        if (libId == null || articleName == null) return false;
        return libId.toUpperCase(Locale.ROOT).startsWith(articleName.toUpperCase(Locale.ROOT) + ":");
    }

    private Element firstDirectChildByName(Element parent, String childName) {
        if (parent == null || childName == null || childName.isBlank()) return null;
        for (Iterator<?> it = parent.elementIterator(); it.hasNext(); ) {
            Object o = it.next();
            if (o instanceof Element e && e.getName().equalsIgnoreCase(childName)) {
                return e;
            }
        }
        return null;
    }

    // -------------------- Extraction --------------------

    private MappedMmlItem extractItem(Element itemEl, String articleName, int itemSequence) {
        MappedMmlItem it = new MappedMmlItem();
        it.kind = guessItemKind(itemEl);

        if ("notation".equals(it.kind)) {
            it.subKind = guessNotationKind(itemEl);
        } else {
            it.subKind = guessSubKind(itemEl);
        }

        it.shortName = itemEl.attributeValue("idnr");
        it.libId = findBestLibId(itemEl, articleName, it.kind, it.subKind);
        it.title = findTitle(itemEl);
        it.textContent = extractTextContent(itemEl);
        it.rawXml = itemEl.asXML();
        it.number = findNumber(itemEl, itemSequence);
        return it;
    }

    /**
     * Must produce stable number for UNIQUE(article_id, subkind, number).
     * - Try nr/number attributes
     * - Try nested Label[@labelnr]
     * - Fallback: parse xmlid like "x123" -> 123 (very stable within file)
     */
    private int findNumber(Element itemEl) {
        return findNumber(itemEl, 0);
    }

    private int findNumber(Element itemEl, int itemSequence) {
        String nr = itemEl.attributeValue("nr");
        if (nr == null) nr = itemEl.attributeValue("number");
        if (nr != null) {
            try {
                int v = Integer.parseInt(nr);
                if (v > 0) return v;
            } catch (Exception ignore) {}
        }

        String ownLibId = findOwnMmlId(itemEl);
        int fromLib = parseTrailingNumberFromLibId(ownLibId);
        if (fromLib > 0) return fromLib;

        boolean hasOwnLibId = ownLibId != null && !ownLibId.isBlank();
        if (hasOwnLibId) {
            Element label = (Element) itemEl.selectSingleNode(".//Label[@labelnr]");
            if (label != null) {
                String labelnr = label.attributeValue("labelnr");
                if (labelnr != null) {
                    try {
                        int v = Integer.parseInt(labelnr);
                        if (v > 0) return v;
                    } catch (Exception ignore) {}
                }
            }
        }

        String xmlid = itemEl.attributeValue("xmlid");
        if (xmlid != null) {
            String digits = xmlid.replaceAll("\\D+", "");
            if (!digits.isBlank()) {
                try {
                    int v = Integer.parseInt(digits);
                    if (hasOwnLibId) return v;
                    return 1_000_000 + v;
                } catch (Exception ignore) {}
            }
        }

        if (itemSequence > 0) {
            return 2_000_000 + itemSequence;
        }
        return 0;
    }

    private String guessItemKind(Element itemEl) {
        // In ESX, <Item kind="Regular-Statement"> etc. Only statements are wrapped in <Item>.
        // Constructors/notations/registrations exist as nested elements.
        return "statement";
    }

    private String guessSubKind(Element itemEl) {
        // Determine statement kind from immediate child element name.
        // Examples: <Regular-Statement>, <Definitional-Block>, etc.
        Element firstChild = null;
        for (Iterator<?> it = itemEl.elementIterator(); it.hasNext(); ) {
            Object o = it.next();
            if (o instanceof Element e) { firstChild = e; break; }
        }
        if (firstChild != null) {
            String n = firstChild.getName();
            if (n != null) {
                String nn = n.toLowerCase(Locale.ROOT);
                if (nn.contains("definitional") || nn.contains("definition")) return "def";
                if (nn.contains("scheme")) return "sch";
                if (nn.contains("theorem")) return "th";
                if (nn.contains("registration")) return "condreg";
                if (nn.contains("regular-statement") || nn.contains("statement")) return "th";
            }
        }

        // Fallback: if proposition exists, treat as theorem-like.
        if (itemEl.selectSingleNode(".//Proposition") != null) return "th";

        return "th";
    }

    private String findTitle(Element itemEl) {
        // With ESX statements, Label is nested under Proposition
        Element label = (Element) itemEl.selectSingleNode(".//Proposition/Label");
        if (label != null) {
            String sp = label.attributeValue("spelling");
            if (sp != null && !sp.isBlank()) return sp;
        }
        return null;
    }

    private String extractTextContent(Element itemEl) {
        String txt = itemEl.getStringValue();
        if (txt == null) return null;
        txt = txt.trim().replaceAll("\\s+", " ");
        return txt.isEmpty() ? null : txt;
    }

    /**
     * Heurystyka dla notacji. W wielu ESX notacje mogą nie być opakowane w <Item>,
     * ale metoda jest używana też jako fallback/normalizacja.
     */
    private String guessNotationKind(Element itemEl) {
        if (itemEl == null) return "funcnot";

        String raw = Optional.ofNullable(itemEl.attributeValue("kind"))
                .orElse("")
                .toLowerCase(Locale.ROOT);

        if (raw.contains("attr")) return "attrnot";
        if (raw.contains("functor") || raw.contains("func")) return "funcnot";
        if (raw.contains("mode")) return "modenot";
        if (raw.contains("pred")) return "prednot";
        if (raw.contains("sel")) return "selnot";
        if (raw.contains("struct")) return "structnot";
        if (raw.contains("aggr") || raw.contains("aggregate")) return "aggrnot";

        if (itemEl.selectSingleNode(".//Attribute-Pattern") != null) return "attrnot";
        if (itemEl.selectSingleNode(".//Functor-Pattern") != null) return "funcnot";
        if (itemEl.selectSingleNode(".//Mode-Pattern") != null) return "modenot";
        if (itemEl.selectSingleNode(".//Predicate-Pattern") != null) return "prednot";
        if (itemEl.selectSingleNode(".//Selector-Pattern") != null) return "selnot";
        if (itemEl.selectSingleNode(".//Structure-Pattern") != null) return "structnot";
        if (itemEl.selectSingleNode(".//Aggregate-Pattern") != null) return "aggrnot";

        return "funcnot";
    }


    private String normalizeNotationKind(String subKind, Element itemEl) {
        if (subKind == null || subKind.isBlank()) return guessNotationKind(itemEl);
        if (subKind.endsWith("not")) return subKind;

        return switch (subKind) {
            case "attr" -> "attrnot";
            case "func" -> "funcnot";
            case "mode" -> "modenot";
            case "pred" -> "prednot";
            case "sel" -> "selnot";
            case "struct" -> "structnot";
            case "aggr" -> "aggrnot";
            default -> guessNotationKind(itemEl);
        };
    }

    private String normalizeStatementKind(String subKind) {
        if (subKind == null || subKind.isBlank()) return "th";
        return switch (subKind) {
            case "th", "def", "dfs", "sch" -> subKind;
            default -> "th";
        };
    }

    private String normalizeRegistrationKind(String subKind) {
        if (subKind == null || subKind.isBlank()) return "condreg";
        return switch (subKind) {
            case "exreg", "condreg", "funcreg" -> subKind;
            default -> "condreg";
        };
    }

    private List<String> findConstructorLibIds(Element itemEl) {
        List<String> lst = new ArrayList<>();
        List<org.dom4j.Node> nodes = itemEl.selectNodes(".//*[@absoluteconstrMMLId or @constr]");
        for (org.dom4j.Node n : nodes) {
            if (n instanceof Element el) {
                String lib = el.attributeValue("absoluteconstrMMLId");
                if ((lib == null || lib.isBlank()) && looksLikeLibId(el.attributeValue("constr"))) {
                    lib = el.attributeValue("constr");
                }
                if (lib != null && !lib.isBlank()) lst.add(lib);
            }
        }
        return lst.stream().distinct().collect(Collectors.toList());
    }

    private String findBestLibId(Element itemEl, String articleName, String kind, String subKind) {
        String own = findOwnMmlId(itemEl);
        if (own != null && !own.isBlank()) {
            String itemKind = itemEl.attributeValue("kind");
            if (isDefinitionItemKind(itemKind)) {
                Element child = firstElementChild(itemEl);
                String xmlid = firstNonBlank(itemEl.attributeValue("xmlid"), child == null ? null : child.attributeValue("xmlid"));
                return "statement:" + own + ":" + firstNonBlank(xmlid, "def");
            }
            return own;
        }

        // fallback
        String lab = itemEl.attributeValue("xmlid");
        if (lab == null) lab = itemEl.attributeValue("idnr");
        if (lab != null && !lab.isBlank()) {
            String kindShort = (subKind != null) ? subKind : (kind != null ? kind.replaceAll("[^A-Za-z]", "") : "item");
            return articleName + ":" + kindShort + " " + lab;
        }
        return null;
    }

    private String findOwnMmlId(Element itemEl) {
        if (itemEl == null) return null;

        String own = itemEl.attributeValue("MMLId");
        if (own != null && !own.isBlank()) return own;

        Element firstChild = firstElementChild(itemEl);
        if (firstChild != null) {
            own = firstChild.attributeValue("MMLId");
            if (own != null && !own.isBlank()) return own;
        }
        return null;
    }

    private Element firstElementChild(Element parent) {
        if (parent == null) return null;
        for (Iterator<?> it = parent.elementIterator(); it.hasNext(); ) {
            Object o = it.next();
            if (o instanceof Element e) return e;
        }
        return null;
    }

    private boolean looksLikeLibId(String value) {
        return value != null && value.contains(":");
    }

    // -------------------- Notation children: symbols / constructors / formats --------------------

    private UUID insertSymbolIfAbsent(UUID articleId, String text, FileInsertStats stats) {
        UpsertResult result = mmlDao.insertSymbolIfAbsent(articleId, text);
        if (result.inserted()) {
            stats.add(FileInsertStats.SYMBOL, 1);
        }
        return result.id();
    }

    private UUID insertSymbolIfAbsentCached(UUID articleId, String text, Map<String, UUID> symbolCache, FileInsertStats stats) {
        if (articleId == null || text == null || text.isBlank()) return null;
        if (symbolCache == null) {
            return insertSymbolIfAbsent(articleId, text, stats);
        }
        String key = articleId + "|" + text.trim();
        return symbolCache.computeIfAbsent(key, ignored -> insertSymbolIfAbsent(articleId, text, stats));
    }

    private void insertNotationSymbol(UUID notationItemId, UUID symbolId, int pos, FileInsertStats stats) {
        stats.add(FileInsertStats.NOTATION_SYMBOL, mmlDao.insertNotationSymbol(notationItemId, symbolId, pos));
    }

    private void insertNotationConstructor(UUID notationItemId, UUID constructorItemId, String role, FileInsertStats stats) {
        stats.add(FileInsertStats.NOTATION_CONSTRUCTOR, mmlDao.insertNotationConstructor(notationItemId, constructorItemId, role));
    }

    private UUID upsertFormat(UUID articleId, String name, String representation, FileInsertStats stats) {
        UpsertResult result = mmlDao.upsertFormat(articleId, name, representation);
        if (result.inserted()) {
            stats.add(FileInsertStats.FORMAT, 1);
        }
        return result.id();
    }

    private UUID upsertFormatCached(UUID articleId, String name, String representation, Map<String, UUID> formatCache, FileInsertStats stats) {
        if (articleId == null || name == null || name.isBlank()) return null;
        if (formatCache == null) {
            return upsertFormat(articleId, name, representation, stats);
        }
        String key = articleId + "|" + name.trim();
        return formatCache.computeIfAbsent(key, ignored -> upsertFormat(articleId, name, representation, stats));
    }

    private void processNotationChildren(
            Element patternEl,
            UUID notationItemId,
            UUID articleId,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations,
            String fallbackConstructorLibId,
            Map<String, UUID> symbolCache,
            Map<String, UUID> formatCache,
            FileInsertStats insertStats
    ) {
        if (patternEl == null || notationItemId == null) return;

        int pos = 1;
        List<UUID> symbolIds = new ArrayList<>();
        for (String symbolText : extractNotationSymbols(patternEl)) {
            UUID symbolId = insertSymbolIfAbsentCached(articleId, symbolText, symbolCache, insertStats);
            if (symbolId == null) continue;
            insertNotationSymbol(notationItemId, symbolId, pos++, insertStats);
            symbolIds.add(symbolId);
        }

        List<org.dom4j.Node> formatNodes = patternEl.selectNodes(".//*[@formatnr or @formatdes]");
        for (org.dom4j.Node fn : formatNodes) {
            if (!(fn instanceof Element el)) continue;
            String name = el.attributeValue("formatnr");
            String repr = el.attributeValue("formatdes");
            if (name == null || name.isBlank()) continue;
            UUID formatId = upsertFormatCached(articleId, name, repr, formatCache, insertStats);
            if (formatId == null) continue;
            insertNotationFormat(notationItemId, formatId, insertStats);
            for (int i = 0; i < symbolIds.size(); i++) {
                insertFormatSymbol(formatId, symbolIds.get(i), i + 1, insertStats);
            }
        }

        // Constructor-level links are intentionally disabled: query language and storage no longer expose constructor view/data.
    }

    private List<String> extractNotationSymbols(Element patternEl) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();

        String rootSpelling = patternEl.attributeValue("spelling");
        if (rootSpelling != null && !rootSpelling.isBlank()) {
            symbols.add(rootSpelling);
        }

        List<org.dom4j.Node> nodes = patternEl.selectNodes(".//*[@spelling]");
        for (org.dom4j.Node n : nodes) {
            if (!(n instanceof Element e)) continue;
            String name = e.getName();
            if (name == null || !name.toLowerCase(Locale.ROOT).contains("symbol")) continue;
            String spelling = e.attributeValue("spelling");
            if (spelling == null || spelling.isBlank()) continue;
            symbols.add(spelling);
        }

        return new ArrayList<>(symbols);
    }

    // -------------------- Generic XML tag -> item_node mapping --------------------

    private void mapAllItemNodes(
            Element itemEl,
            UUID itemId,
            UUID articleId,
            String rootNodeType,
            List<PendingRelation> pendingRelations,
            Map<String, UUID> symbolCache,
            Map<String, UUID> formatCache,
            FileInsertStats insertStats
    ) {
        if (itemEl == null || itemId == null) {
            return;
        }
        Map<String, UUID> symbols = (symbolCache == null) ? new ConcurrentHashMap<>() : symbolCache;
        Map<String, UUID> formats = (formatCache == null) ? new ConcurrentHashMap<>() : formatCache;

        String rootPath = "/" + itemEl.getName() + "[1]";
        persistItemNodeRecursive(
                itemEl,
                itemId,
                articleId,
                null,
                rootPath,
                0,
                1,
                rootNodeType,
                pendingRelations,
                symbols,
                formats,
                insertStats
        );
    }

    private void persistItemNodeRecursive(
            Element nodeEl,
            UUID itemId,
            UUID articleId,
            UUID parentNodeId,
            String nodePath,
            int depth,
            int pos,
            String rootNodeType,
            List<PendingRelation> pendingRelations,
            Map<String, UUID> symbolCache,
            Map<String, UUID> formatCache,
            FileInsertStats insertStats
    ) {
        UUID nodeId = UUID.randomUUID();
        UUID constructorItemId = null;
        UUID symbolId = resolveSymbolIdForNode(nodeEl, articleId, symbolCache, insertStats);
        UUID formatId = resolveFormatIdForNode(nodeEl, articleId, formatCache, insertStats);

        insertItemNode(
                nodeId,
                itemId,
                parentNodeId,
                nodePath,
                classifyItemNodeType(nodeEl, symbolId, rootNodeType, depth),
                constructorItemId,
                symbolId,
                formatId,
                pos,
                depth,
                abbreviateRawXml(nodeEl),
                collectNodeDetails(nodeEl),
                insertStats
        );

        int childPos = 1;
        for (Iterator<?> it = nodeEl.elementIterator(); it.hasNext(); ) {
            Object o = it.next();
            if (!(o instanceof Element child)) continue;
            String childPath = nodePath + "/" + child.getName() + "[" + childPos + "]";
            persistItemNodeRecursive(
                    child,
                    itemId,
                    articleId,
                    nodeId,
                    childPath,
                    depth + 1,
                    childPos,
                    rootNodeType,
                    pendingRelations,
                    symbolCache,
                    formatCache,
                    insertStats
            );
            childPos++;
        }
    }

    private UUID resolveSymbolIdForNode(
            Element nodeEl,
            UUID articleId,
            Map<String, UUID> symbolCache,
            FileInsertStats insertStats
    ) {
        if (nodeEl == null || articleId == null) {
            return null;
        }
        String nodeName = nodeEl.getName();
        if (nodeName == null) {
            return null;
        }
        String normalizedNodeName = nodeName.toLowerCase(Locale.ROOT);
        boolean symbolNode = normalizedNodeName.contains("symbol");
        boolean patternNode = normalizedNodeName.endsWith("-pattern");

        if (!symbolNode && !patternNode) {
            return null;
        }

        String symbolText = firstNonBlank(
                nodeEl.attributeValue("spelling"),
                symbolNode ? nodeEl.attributeValue("name") : null,
                symbolNode ? nodeEl.getTextTrim() : null
        );
        if (symbolText == null || symbolText.isBlank()) {
            return null;
        }
        return insertSymbolIfAbsentCached(articleId, symbolText, symbolCache, insertStats);
    }

    private UUID resolveFormatIdForNode(
            Element nodeEl,
            UUID articleId,
            Map<String, UUID> formatCache,
            FileInsertStats insertStats
    ) {
        if (nodeEl == null || articleId == null) {
            return null;
        }
        String formatName = nodeEl.attributeValue("formatnr");
        if (formatName == null || formatName.isBlank()) {
            return null;
        }
        String representation = nodeEl.attributeValue("formatdes");
        return upsertFormatCached(articleId, formatName, representation, formatCache, insertStats);
    }

    private String classifyItemNodeType(Element nodeEl, UUID symbolId, String rootNodeType, int depth) {
        if (isSymbolNodeType(nodeEl, symbolId)) {
            return NODE_TYPE_SYMBOLS;
        }
        if (depth == 0 && rootNodeType != null && !rootNodeType.isBlank()) {
            return rootNodeType;
        }
        return NODE_TYPE_NO_NODES;
    }

    private String classifyRootItemNodeType(Element itemEl, String statementKind) {
        if (itemEl == null) {
            return NODE_TYPE_NO_NODES;
        }

        String itemKind = Optional.ofNullable(itemEl.attributeValue("kind")).orElse("");
        String lowerKind = itemKind.toLowerCase(Locale.ROOT);

        if (isClusterItemKind(itemKind) || lowerKind.contains("registration")) {
            return NODE_TYPE_REGISTRATIONS;
        }
        if (lowerKind.contains("scheme")) {
            return NODE_TYPE_SCHEMES;
        }
        if (lowerKind.contains("definition")) {
            return NODE_TYPE_DEFINITIONS;
        }
        if ("theorem-item".equals(lowerKind) || "regular-statement".equals(lowerKind) || lowerKind.contains("theorem")) {
            return NODE_TYPE_THEOREMS;
        }

        if ("sch".equals(statementKind)) {
            return NODE_TYPE_SCHEMES;
        }
        if ("def".equals(statementKind) || "dfs".equals(statementKind)) {
            return NODE_TYPE_DEFINITIONS;
        }
        return NODE_TYPE_NO_NODES;
    }

    private boolean isSymbolNodeType(Element nodeEl, UUID symbolId) {
        if (symbolId != null) {
            return true;
        }
        if (nodeEl == null || nodeEl.getName() == null) {
            return false;
        }
        return nodeEl.getName().toLowerCase(Locale.ROOT).contains("symbol");
    }

    private Map<String, Object> collectNodeDetails(Element nodeEl) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tag", nodeEl.getName());

        Map<String, String> attrs = new LinkedHashMap<>();
        for (Iterator<?> it = nodeEl.attributeIterator(); it.hasNext(); ) {
            Object o = it.next();
            if (!(o instanceof org.dom4j.Attribute attr)) continue;
            attrs.put(attr.getName(), attr.getValue());
        }
        if (!attrs.isEmpty()) {
            details.put("attrs", attrs);
        }
        return details;
    }

    private String abbreviateRawXml(Element nodeEl) {
        if (nodeEl == null) {
            return null;
        }

        String tag = nodeEl.getName();
        if (tag == null || tag.isBlank()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(ITEM_NODE_RAW_MAX);
        sb.append('<').append(tag);

        for (Iterator<?> it = nodeEl.attributeIterator(); it.hasNext(); ) {
            Object o = it.next();
            if (!(o instanceof org.dom4j.Attribute attr)) continue;
            String an = attr.getName();
            if (an == null || an.isBlank()) continue;
            String av = attr.getValue();
            if (av == null) av = "";
            sb.append(' ')
                    .append(an)
                    .append("=\"")
                    .append(av
                            .replace("&", "&amp;")
                            .replace("\"", "&quot;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;"))
                    .append('"');
            if (sb.length() >= ITEM_NODE_RAW_MAX) {
                return sb.substring(0, ITEM_NODE_RAW_MAX);
            }
        }

        boolean hasChildren = nodeEl.elementIterator().hasNext();
        String text = nodeEl.getTextTrim();
        boolean hasText = text != null && !text.isBlank();

        if (!hasChildren && hasText) {
            if (text.length() > ITEM_NODE_TEXT_MAX) {
                text = text.substring(0, ITEM_NODE_TEXT_MAX);
            }
            sb.append('>')
                    .append(text
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;"))
                    .append("</")
                    .append(tag)
                    .append('>');
        } else if (hasChildren) {
            sb.append(">...</").append(tag).append('>');
        } else {
            sb.append("/>");
        }

        if (sb.length() <= ITEM_NODE_RAW_MAX) {
            return sb.toString();
        }
        return sb.substring(0, ITEM_NODE_RAW_MAX);
    }

    // -------------------- Utilities --------------------

    private static String deriveArticleNameFromS3Key(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) return "UNKNOWN";
        String file = s3Key.substring(Math.max(s3Key.lastIndexOf('/') + 1, 0));
        String base = file.replaceAll("\\.esx$", "");
        return base.toUpperCase(Locale.ROOT);
    }

}
