package mag.mizarstack.query.eval;

import lombok.RequiredArgsConstructor;
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Node;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class QueryResultProjectionService {

    private static final int MAX_SQL_IN_IDS = 30000;
    private static final Pattern MML_ID_ATTRIBUTE_REGEX = Pattern.compile(
            "(?i)(?:^|\\s)MMLId\\s*=\\s*\"([^\"]+)\""
    );

    private final JdbcClient jdbcClient;

    public List<Map<String, Object>> projectForTable(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> entityRows = rows.stream()
                .filter(this::isEntityRow)
                .toList();

        Map<UUID, RootNodeContext> rootContextByItemId = loadRootNodeContext(entityRows);
        Map<UUID, String> rawXmlByItemId = loadItemRawXmlForNodeRows(entityRows);
        Map<UUID, Optional<Document>> documentByItemId = new HashMap<>();
        List<Map<String, Object>> projected = new ArrayList<>(rows.size());

        for (Map<String, Object> row : rows) {
            if (!isEntityRow(row)) {
                projected.add(projectGenericRow(row));
                continue;
            }

            UUID itemId = asUuid(row.get("item_id"));
            String rawText = resolveRawText(row, itemId, rootContextByItemId, rawXmlByItemId, documentByItemId);
            String resolvedMmlIds = resolveMmlIds(
                    row,
                    itemId,
                    rawText,
                    rootContextByItemId,
                    rawXmlByItemId,
                    documentByItemId
            );

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_id", itemId == null ? "" : itemId.toString());
            item.put("node_id", safeToString(row.get("node_id")));
            item.put("node_path", safeToString(row.get("node_path")));
            item.put("lib_id", safeToString(row.get("lib_id")));
            item.put("article_name", safeToString(row.get("article_name")));
            item.put("node_type", safeToString(row.get("node_type")));
            item.put("text_position", resolveTextPosition(row, itemId, rootContextByItemId));
            item.put("MMLId", resolvedMmlIds);
            item.put("mml_ids", resolvedMmlIds);
            if (row.containsKey("spelling")) {
                item.put("spelling", safeToString(row.get("spelling")));
            }
            if (row.containsKey("occurrences")) {
                item.put("occurrences", row.get("occurrences"));
            }
            item.put("raw", rawText);
            projected.add(item);
        }

        return projected;
    }

    private boolean isEntityRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        return row.containsKey("item_id")
                || row.containsKey("node_id")
                || row.containsKey("node_path")
                || row.containsKey("node_xmlid")
                || row.containsKey("lib_id")
                || row.containsKey("article_name")
                || row.containsKey("node_type")
                || row.containsKey("raw_text")
                || row.containsKey("text_content");
    }

    private Map<String, Object> projectGenericRow(Map<String, Object> row) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof UUID uuid) {
                projected.put(entry.getKey(), uuid.toString());
            } else {
                projected.put(entry.getKey(), value);
            }
        }
        return projected;
    }

    private Map<UUID, RootNodeContext> loadRootNodeContext(List<Map<String, Object>> rows) {
        LinkedHashSet<UUID> itemIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (isNodeRow(row)
                    && !safeToString(row.get("text_position")).isBlank()
                    && !safeToString(row.get("raw_text")).isBlank()) {
                continue;
            }
            UUID itemId = asUuid(row.get("item_id"));
            if (itemId != null) {
                itemIds.add(itemId);
            }
        }
        if (itemIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, RootNodeContext> contextByItemId = new HashMap<>();
        String sql = """
                select rn.item_id,
                       coalesce(rn.details -> 'attrs' ->> 'position', cast(rn.pos as text)) as text_position
                       , rn.raw as raw_text
                       , rn.details::text as details_json
                from view_item_root_nodes rn
                where rn.item_id in (:itemIds)
                """;

        for (List<UUID> batch : partitionIds(new ArrayList<>(itemIds), MAX_SQL_IN_IDS)) {
            List<Map<String, Object>> rowsWithPosition = jdbcClient.sql(sql)
                    .param("itemIds", batch)
                    .query((rs, rowNum) -> {
                        Map<String, Object> row = new HashMap<>();
                        row.put("item_id", rs.getObject("item_id"));
                        row.put("text_position", rs.getObject("text_position"));
                        row.put("raw_text", rs.getObject("raw_text"));
                        row.put("details_json", rs.getObject("details_json"));
                        return row;
                    })
                    .list();

            for (Map<String, Object> positionRow : rowsWithPosition) {
                UUID itemId = asUuid(positionRow.get("item_id"));
                if (itemId != null) {
                    String textPosition = safeToString(positionRow.get("text_position"));
                    String rawText = safeToString(positionRow.get("raw_text"));
                    String detailsJson = safeToString(positionRow.get("details_json"));
                    contextByItemId.put(itemId, new RootNodeContext(textPosition, rawText, detailsJson));
                }
            }
        }
        return contextByItemId;
    }

    private Map<UUID, String> loadItemRawXmlForNodeRows(List<Map<String, Object>> rows) {
        LinkedHashSet<UUID> itemIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (!isNodeRow(row) || safeToString(row.get("node_xmlid")).isBlank()) {
                continue;
            }
            UUID itemId = asUuid(row.get("item_id"));
            if (itemId != null) {
                itemIds.add(itemId);
            }
        }
        if (itemIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, String> out = new HashMap<>();
        String sql = "select id as item_id, raw_xml from mml_item where id in (:itemIds)";
        for (List<UUID> batch : partitionIds(new ArrayList<>(itemIds), MAX_SQL_IN_IDS)) {
            List<Map<String, Object>> rawRows = jdbcClient.sql(sql)
                    .param("itemIds", batch)
                    .query((rs, rowNum) -> {
                        Map<String, Object> row = new HashMap<>();
                        row.put("item_id", rs.getObject("item_id"));
                        row.put("raw_xml", rs.getObject("raw_xml"));
                        return row;
                    })
                    .list();

            for (Map<String, Object> rawRow : rawRows) {
                UUID itemId = asUuid(rawRow.get("item_id"));
                String rawXml = safeToString(rawRow.get("raw_xml"));
                if (itemId != null && !rawXml.isBlank()) {
                    out.put(itemId, rawXml);
                }
            }
        }
        return out;
    }

    private String resolveRawText(
            Map<String, Object> row,
            UUID itemId,
            Map<UUID, RootNodeContext> contextByItemId,
            Map<UUID, String> rawXmlByItemId,
            Map<UUID, Optional<Document>> documentByItemId
    ) {
        if (isNodeRow(row)) {
            String nodeXml = resolveNodeRawXml(row, itemId, rawXmlByItemId, documentByItemId);
            if (!nodeXml.isBlank()) {
                return nodeXml;
            }

            String rawText = safeToString(row.get("raw_text"));
            if (!rawText.isBlank()) {
                return rawText;
            }
        }

        if (itemId != null) {
            RootNodeContext rootNodeContext = contextByItemId.get(itemId);
            if (rootNodeContext != null) {
                if (!rootNodeContext.rawText().isBlank()) {
                    return rootNodeContext.rawText();
                }
                if (!rootNodeContext.detailsJson().isBlank()) {
                    return rootNodeContext.detailsJson();
                }
            }
        }

        String rawText = safeToString(row.get("raw_text"));
        if (!rawText.isBlank()) {
            return rawText;
        }
        return safeToString(row.get("text_content"));
    }

    private boolean isNodeRow(Map<String, Object> row) {
        return row != null && (
                !safeToString(row.get("node_id")).isBlank()
                        || !safeToString(row.get("node_xmlid")).isBlank()
                        || !safeToString(row.get("node_path")).isBlank()
        );
    }

    private String resolveNodeRawXml(
            Map<String, Object> row,
            UUID itemId,
            Map<UUID, String> rawXmlByItemId,
            Map<UUID, Optional<Document>> documentByItemId
    ) {
        if (itemId == null || rawXmlByItemId == null || rawXmlByItemId.isEmpty()) {
            return "";
        }

        String nodeXmlId = safeToString(row.get("node_xmlid"));
        if (nodeXmlId.isBlank()) {
            return "";
        }

        Optional<Document> document = documentByItemId.computeIfAbsent(
                itemId,
                id -> parseDocument(rawXmlByItemId.get(id))
        );
        if (document.isEmpty()) {
            return "";
        }

        try {
            Node node = document.get().selectSingleNode("//*[@xmlid='" + nodeXmlId.replace("'", "") + "']");
            return node == null ? "" : node.asXML();
        } catch (Exception ignored) {
            return "";
        }
    }

    private Optional<Document> parseDocument(String rawXml) {
        if (rawXml == null || rawXml.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DocumentHelper.parseText(rawXml));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String resolveTextPosition(Map<String, Object> row, UUID itemId, Map<UUID, RootNodeContext> contextByItemId) {
        if (isNodeRow(row)) {
            String nodePosition = safeToString(row.get("text_position"));
            if (!nodePosition.isBlank()) {
                return nodePosition;
            }
        }

        if (itemId != null) {
            RootNodeContext rootNodeContext = contextByItemId.get(itemId);
            if (rootNodeContext != null && !rootNodeContext.textPosition().isBlank()) {
                return rootNodeContext.textPosition();
            }
        }
        return safeToString(row.get("text_position"));
    }

    private String resolveMmlIds(
            Map<String, Object> row,
            UUID itemId,
            String rawText,
            Map<UUID, RootNodeContext> contextByItemId,
            Map<UUID, String> rawXmlByItemId,
            Map<UUID, Optional<Document>> documentByItemId
    ) {
        LinkedHashSet<String> fragmentMmlIds = extractMmlIdsFromXml(rawText);
        if (!fragmentMmlIds.isEmpty()) {
            return String.join(", ", fragmentMmlIds);
        }

        if (isNodeRow(row)) {
            LinkedHashSet<String> nearestMmlIds = resolveNearestAncestorMmlIds(
                    row,
                    itemId,
                    rawXmlByItemId,
                    documentByItemId
            );
            if (!nearestMmlIds.isEmpty()) {
                return String.join(", ", nearestMmlIds);
            }
        }

        if (itemId != null) {
            RootNodeContext rootNodeContext = contextByItemId.get(itemId);
            if (rootNodeContext != null) {
                LinkedHashSet<String> rootMmlIds = extractMmlIdsFromXml(rootNodeContext.rawText());
                if (!rootMmlIds.isEmpty()) {
                    return String.join(", ", rootMmlIds);
                }
            }
        }

        String libIdFallback = safeToString(row.get("lib_id"));
        return libIdFallback;
    }

    private LinkedHashSet<String> resolveNearestAncestorMmlIds(
            Map<String, Object> row,
            UUID itemId,
            Map<UUID, String> rawXmlByItemId,
            Map<UUID, Optional<Document>> documentByItemId
    ) {
        if (itemId == null || rawXmlByItemId == null || rawXmlByItemId.isEmpty()) {
            return new LinkedHashSet<>();
        }
        String nodeXmlId = safeToString(row.get("node_xmlid"));
        if (nodeXmlId.isBlank()) {
            return new LinkedHashSet<>();
        }

        Optional<Document> document = documentByItemId.computeIfAbsent(
                itemId,
                id -> parseDocument(rawXmlByItemId.get(id))
        );
        if (document.isEmpty()) {
            return new LinkedHashSet<>();
        }

        try {
            Node node = document.get().selectSingleNode("//*[@xmlid='" + nodeXmlId.replace("'", "") + "']");
            Element current = node instanceof Element element ? element : (node == null ? null : node.getParent());
            while (current != null) {
                LinkedHashSet<String> elementMmlIds = new LinkedHashSet<>();
                collectMmlIdsFromElementAttributes(current, elementMmlIds);
                if (!elementMmlIds.isEmpty()) {
                    return elementMmlIds;
                }
                current = current.getParent();
            }
        } catch (Exception ignored) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>();
    }

    private LinkedHashSet<String> extractMmlIdsFromXml(String rawXml) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (rawXml == null || rawXml.isBlank()) {
            return out;
        }

        Optional<Document> parsed = parseDocument(rawXml);
        if (parsed.isPresent()) {
            Element root = parsed.get().getRootElement();
            if (root != null) {
                collectMmlIdsFromElementTree(root, out);
            }
            if (!out.isEmpty()) {
                return out;
            }
        }

        Matcher matcher = MML_ID_ATTRIBUTE_REGEX.matcher(rawXml);
        while (matcher.find()) {
            String rawValue = matcher.group(1);
            if (rawValue == null) {
                continue;
            }
            String normalizedValue = rawValue.trim();
            if (!normalizedValue.isBlank()) {
                out.add(normalizedValue);
            }
        }
        return out;
    }

    private void collectMmlIdsFromElementTree(Element element, LinkedHashSet<String> out) {
        if (element == null || out == null) {
            return;
        }
        collectMmlIdsFromElementAttributes(element, out);
        for (Iterator<?> iterator = element.elementIterator(); iterator.hasNext(); ) {
            Object child = iterator.next();
            if (child instanceof Element childElement) {
                collectMmlIdsFromElementTree(childElement, out);
            }
        }
    }

    private void collectMmlIdsFromElementAttributes(Element element, LinkedHashSet<String> out) {
        if (element == null || out == null) {
            return;
        }
        for (Object rawAttribute : element.attributes()) {
            if (!(rawAttribute instanceof Attribute attribute)) {
                continue;
            }
            String name = attribute.getName();
            if (name == null || !name.equalsIgnoreCase("MMLId")) {
                continue;
            }
            String value = attribute.getValue();
            if (value == null) {
                continue;
            }
            String normalizedValue = value.trim();
            if (!normalizedValue.isBlank()) {
                out.add(normalizedValue);
            }
        }
    }

    private String safeToString(Object value) {
        return value == null ? "" : value.toString();
    }

    private UUID asUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<List<UUID>> partitionIds(List<UUID> ids, int chunkSize) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() <= chunkSize) {
            return List.of(ids);
        }
        List<List<UUID>> chunks = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += chunkSize) {
            int to = Math.min(ids.size(), from + chunkSize);
            chunks.add(ids.subList(from, to));
        }
        return chunks;
    }

    private record RootNodeContext(String textPosition, String rawText, String detailsJson) {
    }
}
