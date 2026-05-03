package mag.mizarstack.query.eval;

import lombok.RequiredArgsConstructor;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Node;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class QueryResultProjectionService {

    private static final int MAX_SQL_IN_IDS = 30000;

    private final JdbcClient jdbcClient;

    public List<Map<String, Object>> projectForTable(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, RootNodeContext> rootContextByItemId = loadRootNodeContext(rows);
        Map<UUID, String> rawXmlByItemId = loadItemRawXmlForNodeRows(rows);
        Map<UUID, Optional<Document>> documentByItemId = new HashMap<>();
        List<Map<String, Object>> projected = new ArrayList<>(rows.size());

        for (Map<String, Object> row : rows) {
            UUID itemId = asUuid(row.get("item_id"));
            String rawText = resolveRawText(row, itemId, rootContextByItemId, rawXmlByItemId, documentByItemId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_id", itemId == null ? "" : itemId.toString());
            item.put("node_id", safeToString(row.get("node_id")));
            item.put("node_path", safeToString(row.get("node_path")));
            item.put("lib_id", safeToString(row.get("lib_id")));
            item.put("article_name", safeToString(row.get("article_name")));
            item.put("node_type", safeToString(row.get("node_type")));
            item.put("text_position", resolveTextPosition(row, itemId, rootContextByItemId));
            item.put("raw", rawText);
            projected.add(item);
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
