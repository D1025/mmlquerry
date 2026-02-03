package mag.mizarstack.ingest;

import lombok.extern.slf4j.Slf4j;
import org.dom4j.Element;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private final JdbcClient db;

    public EsxMmlMapperService(JdbcClient db) {
        this.db = db;
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
        List<PendingRef> pendingRefs = Collections.synchronizedList(new ArrayList<>());

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
                    parsedItems, libIdToItem, pendingRefs, m);

            if (parsedItems.get() == 0) {
                detectLikelyItemElements(xmlBytes, m);
            }

            m.accept("Parsing complete: totalItems=" + parsedItems.get() + " pendingRefs=" + pendingRefs.size());

            AtomicInteger resolvedRefs = new AtomicInteger(0);
            int total = pendingRefs.size();
            m.accept("Resolving pending references: " + total);

            for (PendingRef pr : pendingRefs) {
                UUID citem = libIdToItem.get(pr.refLibId);
                if (citem == null) {
                    citem = findConstructorItemIdByLibId(pr.refLibId);
                }
                if (citem != null) {
                    insertItemConstructorRef(pr.itemId, citem, pr.role, true, 1, null);
                    int r = resolvedRefs.incrementAndGet();
                    if (r % 500 == 0) m.accept("Resolved refs: " + r + " / " + total);
                }
            }

            m.accept("Resolving complete: resolved=" + resolvedRefs.get()
                    + " unresolved=" + (pendingRefs.size() - resolvedRefs.get()));

        } catch (Exception e) {
            log.warn("Error while mapping ESX XML", e);
            finalMessages.add("fatal: " + e.getMessage());
            if (progress != null) {
                try { progress.accept("fatal: " + e.getMessage()); } catch (Exception ex) { log.warn("progress consumer failed", ex); }
            }
        }

        return Map.of(
                "processedItems", parsedItems.get(),
                "pendingRefs", pendingRefs.size(),
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
            List<PendingRef> pendingRefs,
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

                            MmlItem it = extractItem(itemEl, articleName);
                            UUID itemId = upsertMmlItem(articleId, it);

                            if (it.libId != null && !it.libId.isBlank()) {
                                libIdToItem.put(it.libId, itemId);
                            }

                            try {
                                insertStatement(itemId, normalizeStatementKind(it.subKind), it.textContent);
                            } catch (Exception ignore) {}

                            List<String> references = findConstructorLibIds(itemEl);
                            for (String rlib : references) {
                                pendingRefs.add(new PendingRef(itemId, rlib, "ref"));
                            }

                            int count = parsedItems.incrementAndGet();
                            if (count == 1) {
                                m.accept("First item seen: elementName=Item kind=" + it.kind + " subkind=" + it.subKind + " number=" + it.number + " libId=" + it.libId);
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

    private static class StopDetect extends RuntimeException {
    }

    // -------------------- DB: Article --------------------

    private UUID upsertArticle(String articleName, String s3Key, String xmlContent) {
        var params = Map.of("name", articleName, "file_path", s3Key, "xml", xmlContent);
        try {
            return db.sql("""
                    insert into article(id, name, file_path, xml_content)
                    values (gen_random_uuid(), :name, :file_path, :xml)
                    on conflict (name) do update set
                        file_path = excluded.file_path,
                        xml_content = excluded.xml_content
                    returning id
                """)
                    .params(params)
                    .query(UUID.class)
                    .single();
        } catch (Exception ex) {
            log.warn("Article upsert failed, falling back to select: {}", ex.getMessage());
            String idStr = db.sql("select id from article where name=:name")
                    .param("name", articleName)
                    .query(String.class)
                    .single();
            return UUID.fromString(idStr);
        }
    }

    // -------------------- DB: Items (UPSERT) --------------------

    /**
     * Upsert by UNIQUE(article_id, subkind, number) to make ingestion idempotent.
     * If subkind is null, Postgres allows duplicates (NULL != NULL), so you may still get duplicates for such items.
     * The important ones (th/def/dfs/sch, constructors, notations) usually have non-null subkind + stable number.
     */
    private UUID upsertMmlItem(UUID articleId, MmlItem it) {
        var params = new HashMap<String, Object>();
        params.put("articleId", articleId);
        params.put("kind", it.kind);
        params.put("subkind", it.subKind);
        params.put("number", it.number);
        params.put("lib_id", it.libId);
        params.put("title", it.title);
        params.put("text_content", it.textContent);
        params.put("raw_xml", it.rawXml);

        try {
            return db.sql("""
                insert into mml_item (id, article_id, kind, subkind, number, lib_id, title, text_content, raw_xml)
                values (gen_random_uuid(), :articleId, :kind, :subkind, :number, :lib_id, :title, :text_content, :raw_xml)
                on conflict (article_id, subkind, number)
                do update set
                    kind = excluded.kind,
                    lib_id = excluded.lib_id,
                    title = excluded.title,
                    text_content = excluded.text_content,
                    raw_xml = excluded.raw_xml
                returning id
                """)
                .params(params)
                .query(UUID.class)
                .single();
        } catch (org.springframework.dao.DataIntegrityViolationException dive) {
            // Most common: UNIQUE(lib_id) violation (idx_mml_item_lib_id_unique) when our (article_id,subkind,number)
            // doesn't match, but lib_id already exists.
            if (it.libId == null || it.libId.isBlank()) throw dive;

            log.debug("mml_item upsert: conflict on lib_id={}, falling back to update-by-lib_id", it.libId);
            List<UUID> existing = db.sql("select id from mml_item where lib_id = :lib")
                    .param("lib", it.libId)
                    .query(UUID.class)
                    .list();
            if (existing.isEmpty()) throw dive;

            UUID id = existing.get(0);

            // Important: keep (article_id, subkind, number) stable to avoid violating UNIQUE(article_id, subkind, number)
            // when the same lib_id appeared under a different key.
            db.sql("""
                    update mml_item
                    set kind = :kind,
                        title = :title,
                        text_content = :text_content,
                        raw_xml = :raw_xml
                    where id = :id
                    """)
                    .params(new HashMap<>() {{
                        putAll(params);
                        put("id", id);
                    }})
                    .update();

            // If additionally there is already a canonical row for (articleId, subkind, number), prefer it.
            // This prevents scattering duplicates when upstream numbering is noisy.
            if (it.subKind != null && !it.subKind.isBlank() && it.number > 0) {
                List<UUID> canonical = db.sql("""
                        select id from mml_item
                        where article_id = :aid and subkind = :sk and number = :nr
                        """)
                        .params(Map.of("aid", articleId, "sk", it.subKind, "nr", it.number))
                        .query(UUID.class)
                        .list();
                if (!canonical.isEmpty()) {
                    return canonical.get(0);
                }
            }

            return id;
        }
    }

    private void insertConstructor(UUID itemId, String constructorKind, String shortName) {
        if (constructorKind == null || constructorKind.isBlank()) return;
        db.sql("""
                insert into constructor(item_id, constructor_kind, short_name)
                values(:id, :ck, :sn)
                on conflict (item_id) do nothing
                """)
                .params(Map.of("id", itemId, "ck", constructorKind, "sn", shortName))
                .update();
    }

    private void insertNotation(UUID itemId, UUID articleId, String notationKind) {
        if (notationKind == null || notationKind.isBlank()) return;
        db.sql("""
                insert into notation(item_id, notation_kind)
                values(:id, :nk)
                on conflict (item_id) do nothing
                """)
                .params(Map.of("id", itemId, "nk", notationKind))
                .update();
    }

    private void insertStatement(UUID itemId, String statementKind, String text) {
        if (statementKind == null || statementKind.isBlank()) statementKind = "th";
        db.sql("""
                insert into statement(item_id, statement_kind, statement_text)
                values(:id, :sk, :text)
                on conflict (item_id) do nothing
                """)
                .params(Map.of("id", itemId, "sk", statementKind, "text", text))
                .update();
    }

    private void insertRegistration(UUID itemId, String regKind) {
        if (regKind == null || regKind.isBlank()) regKind = "condreg";
        db.sql("""
                insert into registration(item_id, registration_kind)
                values(:id, :rk)
                on conflict (item_id) do nothing
                """)
                .params(Map.of("id", itemId, "rk", regKind))
                .update();
    }

    private void insertItemConstructorRef(UUID itemId, UUID constructorItemId, String role, boolean isPositive, int occurrences, Map<String, Object> details) {
        if (!constructorExists(constructorItemId)) {
            // Avoid FK violation; unresolved will stay unresolved until constructors are ingested.
            return;
        }
        Map<String, Object> p = new HashMap<>();
        p.put("id", UUID.randomUUID());
        p.put("item_id", itemId);
        p.put("constructor_item_id", constructorItemId);
        p.put("role", role);
        p.put("is_positive", isPositive);
        p.put("occurrences", occurrences);
        p.put("details", details);

        db.sql("""
                insert into item_constructor_ref (id, item_id, constructor_item_id, role, is_positive, occurrences, details)
                values(:id, :item_id, :constructor_item_id, :role, :is_positive, :occurrences, :details)
                on conflict (id) do nothing
                """)
                .params(p)
                .update();
    }

    // -------------------- Extraction --------------------

    private MmlItem extractItem(Element itemEl, String articleName) {
        MmlItem it = new MmlItem();
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

        Element label = (Element) itemEl.selectSingleNode(".//Label[@labelnr]");
        if (label != null) {
            String labelnr = label.attributeValue("labelnr");
            if (labelnr != null) {
                try {
                    int v = Integer.parseInt(labelnr);
                    // labelnr=0 występuje masowo; nie jest stabilnym numerem bibliotecznym
                    if (v > 0) return v;
                } catch (Exception ignore) {}
            }
        }

        String xmlid = itemEl.attributeValue("xmlid");
        if (xmlid != null) {
            String digits = xmlid.replaceAll("\\D+", "");
            if (!digits.isBlank()) {
                try { return Integer.parseInt(digits); } catch (Exception ignore) {}
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
        if (libId == null) return null;
        // FK in item_constructor_ref points to constructor(item_id), not just any mml_item.
        List<UUID> row = db.sql("""
                select c.item_id
                from constructor c
                join mml_item mi on mi.id = c.item_id
                where mi.lib_id = :lib
                """)
                .param("lib", libId)
                .query(UUID.class)
                .list();
        if (row.isEmpty()) return null;
        return row.get(0);
    }

    private boolean constructorExists(UUID constructorItemId) {
        if (constructorItemId == null) return false;
        List<Integer> ok = db.sql("select 1 from constructor where item_id = :id")
                .param("id", constructorItemId)
                .query(Integer.class)
                .list();
        return !ok.isEmpty();
    }

    private List<String> findConstructorLibIds(Element itemEl) {
        List<String> lst = new ArrayList<>();
        List<org.dom4j.Node> nodes = itemEl.selectNodes(".//*[@absoluteconstrMMLId or @absolutepatternMMLId or @MMLId or @constr]");
        for (org.dom4j.Node n : nodes) {
            if (n instanceof Element el) {
                String lib = el.attributeValue("absoluteconstrMMLId");
                if (lib == null) lib = el.attributeValue("absolutepatternMMLId");
                if (lib == null) lib = el.attributeValue("MMLId");
                if (lib == null) lib = el.attributeValue("constr");
                if (lib != null && !lib.isBlank()) lst.add(lib);
            }
        }
        return lst.stream().distinct().collect(Collectors.toList());
    }

    private String findBestLibId(Element itemEl, String articleName, String kind, String subKind) {
        List<org.dom4j.Node> found = itemEl.selectNodes(".//*[@MMLId or @absoluteconstrMMLId or @absolutepatternMMLId or @constr]");
        for (org.dom4j.Node n : found) {
            if (n instanceof Element el) {
                String libId = el.attributeValue("MMLId");
                if (libId != null && !libId.isBlank()) return libId;

                libId = el.attributeValue("absoluteconstrMMLId");
                if (libId != null && !libId.isBlank()) return libId;

                libId = el.attributeValue("absolutepatternMMLId");
                if (libId != null && !libId.isBlank()) return libId;

                libId = el.attributeValue("constr");
                if (libId != null && !libId.isBlank()) return libId;
            }
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

    // -------------------- Notation children: symbols / constructors / formats --------------------

    private UUID insertSymbolIfAbsent(UUID articleId, String text) {
        if (text == null || text.isBlank()) return null;
        String normalized = text.trim();

        List<String> row = db.sql("select id from symbol where text = :t and article_id = :aid")
                .params(Map.of("t", text, "aid", articleId))
                .query(String.class)
                .list();

        if (!row.isEmpty()) return UUID.fromString(row.get(0));

        return db.sql("insert into symbol(id, text, normalized, article_id) values(gen_random_uuid(), :t, :n, :aid) returning id")
                .params(Map.of("t", text, "n", normalized, "aid", articleId))
                .query(UUID.class)
                .single();
    }

    private void insertNotationSymbol(UUID notationItemId, UUID symbolId, int pos) {
        if (notationItemId == null || symbolId == null) return;
        db.sql("""
                insert into notation_symbol(notation_item_id, symbol_id, pos)
                values(:nid, :sid, :pos)
                on conflict (notation_item_id, pos) do update set symbol_id=excluded.symbol_id
                """)
                .params(Map.of("nid", notationItemId, "sid", symbolId, "pos", pos))
                .update();
    }

    private void insertNotationConstructor(UUID notationItemId, UUID constructorItemId, String role) {
        if (notationItemId == null || constructorItemId == null) return;
        db.sql("""
                insert into notation_constructor(notation_item_id, constructor_item_id, role)
                values(:nid, :cid, :r)
                on conflict (notation_item_id, constructor_item_id) do nothing
                """)
                .params(Map.of("nid", notationItemId, "cid", constructorItemId, "r", role))
                .update();
    }

    private UUID upsertFormat(UUID articleId, String name, String representation) {
        if (name == null || name.isBlank()) return null;

        List<String> row = db.sql("select id from format where name = :n and article_id = :aid")
                .params(Map.of("n", name, "aid", articleId))
                .query(String.class)
                .list();

        if (!row.isEmpty()) return UUID.fromString(row.get(0));

        return db.sql("insert into format(id, name, representation, article_id) values(gen_random_uuid(), :n, :r, :aid) returning id")
                .params(Map.of("n", name, "r", representation, "aid", articleId))
                .query(UUID.class)
                .single();
    }

    private void processNotationChildren(Element itemEl, UUID notationItemId, UUID articleId) {
        if (itemEl == null || notationItemId == null) return;

        int pos = 1;

        // symbols: nodes with spelling
        List<org.dom4j.Node> nodes = itemEl.selectNodes(".//*[@spelling]");
        for (org.dom4j.Node n : nodes) {
            if (n instanceof Element el) {
                String spelling = el.attributeValue("spelling");
                if (spelling == null || spelling.isBlank()) continue;
                UUID symId = insertSymbolIfAbsent(articleId, spelling);
                if (symId != null) insertNotationSymbol(notationItemId, symId, pos++);
            }
        }

        // notation -> constructor(s)
        List<org.dom4j.Node> patternNodes = itemEl.selectNodes(".//*[@absoluteconstrMMLId or @constr or @MMLId]");
        for (org.dom4j.Node n : patternNodes) {
            if (n instanceof Element pn) {
                String constrLib = pn.attributeValue("absoluteconstrMMLId");
                if (constrLib == null || constrLib.isBlank()) constrLib = pn.attributeValue("constr");
                if (constrLib == null || constrLib.isBlank()) constrLib = pn.attributeValue("MMLId");
                if (constrLib == null || constrLib.isBlank()) continue;

                UUID constructorItem = findConstructorItemIdByLibId(constrLib);
                if (constructorItem != null) insertNotationConstructor(notationItemId, constructorItem, null);
            }
        }

        // formats
        List<org.dom4j.Node> formatNodes = itemEl.selectNodes(".//*[@formatnr or @formatdes]");
        for (org.dom4j.Node fn : formatNodes) {
            if (fn instanceof Element el) {
                String name = el.attributeValue("formatnr");
                String repr = el.attributeValue("formatdes");
                if (name == null || name.isBlank()) continue;
                upsertFormat(articleId, name, repr);
            }
        }
    }

    // -------------------- Utilities --------------------

    private static String deriveArticleNameFromS3Key(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) return "UNKNOWN";
        String file = s3Key.substring(Math.max(s3Key.lastIndexOf('/') + 1, 0));
        String base = file.replaceAll("\\.esx$", "");
        return base.toUpperCase(Locale.ROOT);
    }

    // -------------------- DTOs --------------------

    private static class MmlItem {
        String kind;
        String subKind;
        int number;
        String libId;
        String title;
        String textContent;
        String rawXml;
        String shortName;
    }

    private static class PendingRef {
        UUID itemId;
        String refLibId;
        String role;
        PendingRef(UUID itemId, String refLibId, String role) {
            this.itemId = itemId;
            this.refLibId = refLibId;
            this.role = role;
        }
    }
}
