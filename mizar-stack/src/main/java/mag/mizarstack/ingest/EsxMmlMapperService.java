package mag.mizarstack.ingest;

import lombok.extern.slf4j.Slf4j;
import org.dom4j.Element;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EsxMmlMapperService {

    private static final String TEXT_PROPER_XPATH =
            "//*[translate(local-name(), 'abcdefghijklmnopqrstuvwxyz', 'ABCDEFGHIJKLMNOPQRSTUVWXYZ')='TEXT-PROPER']";

    // Prefer exact-case match used in ESX files (<Item ...>)
    private static final String ITEM_XPATH = "//Item";
    private static final Set<String> DEFINITION_ITEM_KINDS = Set.of(
            "Attribute-Definition",
            "Functor-Definition",
            "Predicate-Definition",
            "Structure-Definition",
            "Mode-Definition"
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

    private final EsxMmlJpaDao mmlDao;

    public EsxMmlMapperService(EsxMmlJpaDao mmlDao) {
        this.mmlDao = mmlDao;
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

    private Map<String, Object> processArticleXmlInternal(
            byte[] xmlBytes,
            String s3Key,
            List<String> messages,
            java.util.function.Consumer<String> progress
    ) {
        var parsedItems = new AtomicInteger(0);

        // libId -> mml_item.id
        Map<String, UUID> libIdToItem = new ConcurrentHashMap<>();
        List<PendingRelation> pendingRelations = Collections.synchronizedList(new ArrayList<>());

        final List<String> finalMessages = (messages == null) ? new ArrayList<>() : messages;

        java.util.function.Consumer<String> m = msg -> {
            finalMessages.add(msg);
            if (progress != null) {
                try { progress.accept(msg); } catch (Exception e) { log.warn("progress consumer failed", e); }
            }
            log.info("ESX-Mapper: {}", msg);
        };

        try {
            m.accept("Starting mapping for " + (s3Key == null ? "raw-bytes" : s3Key));

            String fallbackArticle = deriveArticleNameFromS3Key(s3Key);
            UUID fallbackArticleId = upsertArticle(
                    fallbackArticle,
                    s3Key,
                    new String(xmlBytes, StandardCharsets.UTF_8)
            );

            AtomicReference<UUID> currentArticleId = new AtomicReference<>(fallbackArticleId);
            AtomicReference<String> currentArticleName = new AtomicReference<>(fallbackArticle);

            m.accept("Article context (fallback): " + fallbackArticle + " (id=" + fallbackArticleId + ")");

            // Streaming SAX ingestion that is namespace/case safe.
            // We extract each <Item> subtree as XML and map it to DB.
            ingestItemsSax(xmlBytes, s3Key, currentArticleId, currentArticleName,
                    parsedItems, libIdToItem, pendingRelations, m);

            if (parsedItems.get() == 0) {
                detectLikelyItemElements(xmlBytes, m);
            }

            m.accept("Parsing complete: totalItems=" + parsedItems.get() + " pendingRelations=" + pendingRelations.size());

            AtomicInteger resolvedRefs = new AtomicInteger(0);
            int total = pendingRelations.size();
            m.accept("Resolving pending relations: " + total);

            for (PendingRelation pending : pendingRelations) {
                UUID constructorItemId = libIdToItem.get(pending.constructorLibId);
                if (constructorItemId == null) {
                    constructorItemId = findConstructorItemIdByLibId(pending.constructorLibId);
                }
                if (constructorItemId == null) continue;

                switch (pending.type) {
                    case ITEM_CONSTRUCTOR_REF ->
                            insertItemConstructorRef(pending.sourceItemId, constructorItemId, pending.role, pending.isPositive, pending.occurrences, pending.details);
                    case NOTATION_CONSTRUCTOR ->
                            insertNotationConstructor(pending.sourceItemId, constructorItemId, pending.role);
                    case CONSTRUCTOR_DEFINITION ->
                            insertConstructorDefinition(constructorItemId, pending.sourceItemId);
                    case CONSTRUCTOR_DEFINIENS ->
                            insertConstructorDefiniens(pending.sourceItemId, constructorItemId);
                    case REGISTRATION_RELATION ->
                            insertRegistrationRelation(pending.sourceItemId, constructorItemId, pending.role, pending.isPositive);
                }
                int r = resolvedRefs.incrementAndGet();
                if (r % 500 == 0) m.accept("Resolved relations: " + r + " / " + total);
            }

            m.accept("Resolving complete: resolved=" + resolvedRefs.get()
                    + " unresolved=" + (pendingRelations.size() - resolvedRefs.get()));

        } catch (Exception e) {
            log.warn("Error while mapping ESX XML", e);
            finalMessages.add("fatal: " + e.getMessage());
            if (progress != null) {
                try { progress.accept("fatal: " + e.getMessage()); } catch (Exception ex) { log.warn("progress consumer failed", ex); }
            }
        }

        return Map.of(
                "processedItems", parsedItems.get(),
                "pendingRefs", pendingRelations.size(),
                "messages", finalMessages
        );
    }

    private void ingestItemsSax(
            byte[] xmlBytes,
            String s3Key,
            AtomicReference<UUID> currentArticleId,
            AtomicReference<String> currentArticleName,
            AtomicInteger parsedItems,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations,
            java.util.function.Consumer<String> m
    ) throws Exception {
        javax.xml.parsers.SAXParserFactory f = javax.xml.parsers.SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignore) {}
        try { f.setFeature("http://xml.org/sax/features/validation", false); } catch (Exception ignore) {}

        var parser = f.newSAXParser();

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
                            UUID aid = upsertArticle(article, s3Key, new String(xmlBytes, StandardCharsets.UTF_8));
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
                        // finalize item
                        try {
                            Element itemEl = org.dom4j.DocumentHelper.parseText(itemXml.toString()).getRootElement();

                            UUID articleId = currentArticleId.get();
                            String articleName = currentArticleName.get();
                            if (articleId == null) {
                                articleId = upsertArticle(deriveArticleNameFromS3Key(s3Key), s3Key, new String(xmlBytes, StandardCharsets.UTF_8));
                                currentArticleId.set(articleId);
                            }

                            MappedMmlItem statementItem = extractItem(itemEl, articleName);
                            UUID statementItemId = upsertMmlItem(articleId, statementItem);

                            if (statementItem.libId != null && !statementItem.libId.isBlank()) {
                                libIdToItem.put(statementItem.libId, statementItemId);
                            }

                            try {
                                insertStatement(statementItemId, normalizeStatementKind(statementItem.subKind), statementItem.textContent);
                            } catch (Exception ignore) {}

                            List<String> references = findConstructorLibIds(itemEl);
                            for (String rlib : references) {
                                pendingRelations.add(PendingRelation.itemConstructorRef(statementItemId, rlib, "ref", true, 1, null));
                            }

                            String itemKind = Optional.ofNullable(itemEl.attributeValue("kind")).orElse("");
                            if (isDefinitionItemKind(itemKind)) {
                                processDefinitionItem(itemEl, itemKind, articleId, articleName, statementItemId, libIdToItem, pendingRelations);
                            }
                            if (isClusterItemKind(itemKind)) {
                                processRegistrationItem(itemEl, articleId, articleName, libIdToItem, pendingRelations);
                            }

                            int count = parsedItems.incrementAndGet();
                            if (count == 1) {
                                m.accept("First item seen: elementName=Item kind=" + statementItem.kind + " subkind=" + statementItem.subKind + " number=" + statementItem.number + " libId=" + statementItem.libId);
                            }
                            if (count % 200 == 0) m.accept("Processed items: " + count);

                        } catch (Exception ex) {
                            log.warn("Failed to process Item subtree", ex);
                            m.accept("Failed to process Item subtree: " + ex.getMessage());
                        } finally {
                            itemXml = null;
                        }
                    }
                }
            }
        });
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

    private static boolean equalsAnyIgnoreCase(String s, String... options) {
        if (s == null) return false;
        for (String o : options) {
            if (o != null && s.equalsIgnoreCase(o)) return true;
        }
        return false;
    }

    // -------------------- DB: Article --------------------

    private UUID upsertArticle(String articleName, String s3Key, String xmlContent) {
        return mmlDao.upsertArticle(articleName, s3Key, xmlContent);
    }

    // -------------------- DB: Items (UPSERT) --------------------

    private UUID upsertMmlItem(UUID articleId, MappedMmlItem it) {
        return mmlDao.upsertMmlItem(articleId, it);
    }

    private void insertConstructor(UUID itemId, String constructorKind, String shortName) {
        mmlDao.insertConstructor(itemId, constructorKind, shortName);
    }

    private void insertNotation(UUID itemId, UUID articleId, String notationKind) {
        mmlDao.insertNotation(itemId, notationKind);
    }

    private void insertStatement(UUID itemId, String statementKind, String text) {
        mmlDao.insertStatement(itemId, statementKind, text);
    }

    private void insertRegistration(UUID itemId, String regKind) {
        mmlDao.insertRegistration(itemId, regKind);
    }

    private void insertItemConstructorRef(UUID itemId, UUID constructorItemId, String role, boolean isPositive, int occurrences, Map<String, Object> details) {
        mmlDao.insertItemConstructorRef(itemId, constructorItemId, role, isPositive, occurrences, details);
    }

    private void insertNotationFormat(UUID notationItemId, UUID formatId) {
        mmlDao.insertNotationFormat(notationItemId, formatId);
    }

    private void insertFormatSymbol(UUID formatId, UUID symbolId, int pos) {
        mmlDao.insertFormatSymbol(formatId, symbolId, pos);
    }

    private void insertConstructorDefinition(UUID constructorItemId, UUID definitionStatementItemId) {
        mmlDao.insertConstructorDefinition(constructorItemId, definitionStatementItemId);
    }

    private void insertConstructorDefiniens(UUID definiensStatementItemId, UUID constructorItemId) {
        mmlDao.insertConstructorDefiniens(definiensStatementItemId, constructorItemId);
    }

    private void insertRegistrationRelation(UUID registrationItemId, UUID constructorItemId, String role, boolean isPositive) {
        mmlDao.insertRegistrationRelation(registrationItemId, constructorItemId, role, isPositive);
    }

    private boolean isDefinitionItemKind(String itemKind) {
        return itemKind != null && DEFINITION_ITEM_KINDS.contains(itemKind);
    }

    private boolean isClusterItemKind(String itemKind) {
        return "Cluster".equals(itemKind);
    }

    private void processDefinitionItem(
            Element itemEl,
            String itemKind,
            UUID articleId,
            String articleName,
            UUID statementItemId,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations
    ) {
        Element definitionEl = firstDirectChildByName(itemEl, itemKind);
        if (definitionEl == null) return;

        String constructorKind = constructorKindFromDefinitionName(itemKind);
        String constructorLibId = extractDefinitionConstructorLibId(definitionEl, articleName);

        if (constructorKind != null && constructorLibId != null && !constructorLibId.isBlank()) {
            MappedMmlItem constructorItem = buildSpecializedItem(definitionEl, "constructor", constructorKind, constructorLibId, articleName);
            UUID constructorItemId = upsertMmlItem(articleId, constructorItem);
            insertConstructor(constructorItemId, constructorKind, constructorItem.shortName);
            libIdToItem.put(constructorLibId, constructorItemId);

            if (definitionEl.selectSingleNode("./Definiens") != null || definitionEl.selectSingleNode(".//Definiens") != null) {
                pendingRelations.add(PendingRelation.constructorDefinition(statementItemId, constructorLibId));
                pendingRelations.add(PendingRelation.constructorDefiniens(statementItemId, constructorLibId));
            }
        }

        List<Element> patternElements = findNotationPatternElements(definitionEl);
        for (Element patternEl : patternElements) {
            processNotationPattern(patternEl, articleId, articleName, constructorLibId, libIdToItem, pendingRelations);
        }
    }

    private void processRegistrationItem(
            Element itemEl,
            UUID articleId,
            String articleName,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations
    ) {
        Element clusterEl = firstDirectChildByName(itemEl, "Cluster");
        if (clusterEl == null) return;

        Element registrationEl = null;
        for (Iterator<?> it = clusterEl.elementIterator(); it.hasNext(); ) {
            Object o = it.next();
            if (!(o instanceof Element e)) continue;
            String n = e.getName();
            if (equalsAnyIgnoreCase(n, "Conditional-Registration", "Existential-Registration", "Functorial-Registration")) {
                registrationEl = e;
                break;
            }
        }
        if (registrationEl == null) return;

        String regKind = registrationKindFromElementName(registrationEl.getName());
        String regLibId = buildRegistrationLibId(articleName, regKind, registrationEl.attributeValue("xmlid"));

        MappedMmlItem regItem = buildSpecializedItem(registrationEl, "registration", regKind, regLibId, articleName);
        UUID registrationItemId = upsertMmlItem(articleId, regItem);
        insertRegistration(registrationItemId, normalizeRegistrationKind(regKind));
        if (regItem.libId != null && !regItem.libId.isBlank()) {
            libIdToItem.put(regItem.libId, registrationItemId);
        }

        List<org.dom4j.Node> refNodes = registrationEl.selectNodes(".//*[@absoluteconstrMMLId]");
        for (org.dom4j.Node n : refNodes) {
            if (!(n instanceof Element refEl)) continue;
            String constructorLibId = refEl.attributeValue("absoluteconstrMMLId");
            if (constructorLibId == null || constructorLibId.isBlank()) continue;
            String role = deriveRegistrationRole(refEl);
            boolean isPositive = !isNegativeReference(refEl);
            pendingRelations.add(PendingRelation.registrationRelation(registrationItemId, constructorLibId, role, isPositive));
        }
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
            List<PendingRelation> pendingRelations
    ) {
        String patternLibId = firstNonBlank(patternEl.attributeValue("absolutepatternMMLId"), patternEl.attributeValue("MMLId"));
        if (patternLibId == null || patternLibId.isBlank()) return;

        String notationKind = notationKindFromPatternName(patternEl.getName());
        String notationLibId = buildNotationLibId(patternLibId, patternEl.getName(), patternEl.attributeValue("xmlid"));
        MappedMmlItem notationItem = buildSpecializedItem(patternEl, "notation", notationKind, notationLibId, articleName);

        UUID notationItemId = upsertMmlItem(articleId, notationItem);
        insertNotation(notationItemId, articleId, normalizeNotationKind(notationKind, patternEl));
        if (notationItem.libId != null && !notationItem.libId.isBlank()) {
            libIdToItem.put(notationItem.libId, notationItemId);
        }

        processNotationChildren(patternEl, notationItemId, articleId, libIdToItem, pendingRelations, fallbackConstructorLibId);
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

    private MappedMmlItem extractItem(Element itemEl, String articleName) {
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
        it.number = findNumber(itemEl);
        return it;
    }

    /**
     * Must produce stable number for UNIQUE(article_id, subkind, number).
     * - Try nr/number attributes
     * - Try nested Label[@labelnr]
     * - Fallback: parse xmlid like "x123" -> 123 (very stable within file)
     */
    private int findNumber(Element itemEl) {
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

        // Jeżeli w środku są wzorce, możemy też zgadnąć po nazwach elementów
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

    private UUID findConstructorItemIdByLibId(String libId) {
        return mmlDao.findConstructorItemIdByLibId(libId).orElse(null);
    }

    private boolean constructorExists(UUID constructorItemId) {
        return mmlDao.constructorExists(constructorItemId);
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

    private UUID insertSymbolIfAbsent(UUID articleId, String text) {
        return mmlDao.insertSymbolIfAbsent(articleId, text);
    }

    private void insertNotationSymbol(UUID notationItemId, UUID symbolId, int pos) {
        mmlDao.insertNotationSymbol(notationItemId, symbolId, pos);
    }

    private void insertNotationConstructor(UUID notationItemId, UUID constructorItemId, String role) {
        mmlDao.insertNotationConstructor(notationItemId, constructorItemId, role);
    }

    private UUID upsertFormat(UUID articleId, String name, String representation) {
        return mmlDao.upsertFormat(articleId, name, representation);
    }

    private void processNotationChildren(
            Element patternEl,
            UUID notationItemId,
            UUID articleId,
            Map<String, UUID> libIdToItem,
            List<PendingRelation> pendingRelations,
            String fallbackConstructorLibId
    ) {
        if (patternEl == null || notationItemId == null) return;

        int pos = 1;
        List<UUID> symbolIds = new ArrayList<>();
        for (String symbolText : extractNotationSymbols(patternEl)) {
            UUID symbolId = insertSymbolIfAbsent(articleId, symbolText);
            if (symbolId == null) continue;
            insertNotationSymbol(notationItemId, symbolId, pos++);
            symbolIds.add(symbolId);
        }

        List<org.dom4j.Node> formatNodes = patternEl.selectNodes(".//*[@formatnr or @formatdes]");
        for (org.dom4j.Node fn : formatNodes) {
            if (!(fn instanceof Element el)) continue;
            String name = el.attributeValue("formatnr");
            String repr = el.attributeValue("formatdes");
            if (name == null || name.isBlank()) continue;
            UUID formatId = upsertFormat(articleId, name, repr);
            if (formatId == null) continue;
            insertNotationFormat(notationItemId, formatId);
            for (int i = 0; i < symbolIds.size(); i++) {
                insertFormatSymbol(formatId, symbolIds.get(i), i + 1);
            }
        }

        Set<String> constructorLibIds = new LinkedHashSet<>();
        if (looksLikeLibId(fallbackConstructorLibId)) {
            constructorLibIds.add(fallbackConstructorLibId);
        }
        String ownConstructorLib = patternEl.attributeValue("absoluteconstrMMLId");
        if (looksLikeLibId(ownConstructorLib)) {
            constructorLibIds.add(ownConstructorLib);
        }
        List<org.dom4j.Node> constrNodes = patternEl.selectNodes(".//*[@absoluteconstrMMLId]");
        for (org.dom4j.Node n : constrNodes) {
            if (!(n instanceof Element e)) continue;
            String lib = e.attributeValue("absoluteconstrMMLId");
            if (looksLikeLibId(lib)) constructorLibIds.add(lib);
        }

        for (String constructorLibId : constructorLibIds) {
            pendingRelations.add(PendingRelation.notationConstructor(notationItemId, constructorLibId, "denotes"));
            UUID known = libIdToItem.get(constructorLibId);
            if (known != null && constructorExists(known)) {
                insertNotationConstructor(notationItemId, known, "denotes");
            }
        }
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

    // -------------------- Utilities --------------------

    private static String deriveArticleNameFromS3Key(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) return "UNKNOWN";
        String file = s3Key.substring(Math.max(s3Key.lastIndexOf('/') + 1, 0));
        String base = file.replaceAll("\\.esx$", "");
        return base.toUpperCase(Locale.ROOT);
    }

}

