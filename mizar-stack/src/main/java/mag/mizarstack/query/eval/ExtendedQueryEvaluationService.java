package mag.mizarstack.query.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.query.ast.BasicOperationNode;
import mag.mizarstack.query.ast.BasicOperationType;
import mag.mizarstack.query.ast.CardinalityComparator;
import mag.mizarstack.query.ast.CardinalityFilterOperationNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtendedQueryEvaluationService {

    private final JdbcClient jdbcClient;

    public QueryEvaluationService.QueryResult applyBasicOperation(
            QueryEvaluationService.QueryResult input,
            BasicOperationNode operation
    ) {
        List<UUID> itemIds = extractItemIds(input);
        if (itemIds.isEmpty()) {
            return new QueryEvaluationService.QueryResult(List.of(), "No input items for " + operation.getOperationType());
        }

        List<Map<String, Object>> rows = switch (operation.getOperationType()) {
            case REF -> runRef(itemIds, "ref");
            case OCCUR -> runOccur(itemIds, "ref");
            case DEFINITION -> runDefinition(itemIds);
            case NOTATION -> runNotation(itemIds);
            case REDEF -> unionRows(runOrigin(itemIds), runCopy(itemIds));
            case ORIGIN -> runOrigin(itemIds);
            case COPY -> runCopy(itemIds);
            case TERMTYPE_REF -> runRef(itemIds, "termtype_ref");
            case DEFTYPE_REF -> runRef(itemIds, "deftype_ref");
            case MAIN_MODE -> runMainRegistrationConstructor(itemIds, true);
            case MAIN_FUNCTOR -> runMainRegistrationConstructor(itemIds, false);
        };

        return new QueryEvaluationService.QueryResult(
                deduplicateRows(rows),
                "Applied operation: " + operation.getOperationType()
        );
    }

    public QueryEvaluationService.QueryResult applyCardinalityFilter(
            QueryEvaluationService.QueryResult input,
            CardinalityFilterOperationNode operation
    ) {
        List<UUID> itemIds = extractItemIds(input);
        if (itemIds.isEmpty()) {
            return new QueryEvaluationService.QueryResult(List.of(), "No input items for cardinality filter");
        }

        Map<UUID, Integer> counts = countByOperation(itemIds, operation.getOperationType());
        List<Map<String, Object>> filtered = input.getData().stream()
                .filter(row -> {
                    UUID itemId = asUuid(row.get("item_id"));
                    int value = (itemId == null) ? 0 : counts.getOrDefault(itemId, 0);
                    return compare(value, operation.getThreshold(), operation.getComparator());
                })
                .toList();

        return new QueryEvaluationService.QueryResult(
                filtered,
                "Applied cardinality filter " + operation.getComparator()
                        + "(" + operation.getOperationType() + ", " + operation.getThreshold() + ")"
        );
    }

    private List<Map<String, Object>> runRef(List<UUID> itemIds, String role) {
        String sql = """
                select distinct
                       vc.item_id,
                       vc.lib_id,
                       vc.article_name,
                       'constructor' as kind,
                       vc.constructor_kind as subkind,
                       vc.text_content,
                       'constructors' as node_type
                from item_constructor_ref icr
                join view_constructors vc on vc.item_id = icr.constructor_item_id
                where icr.item_id in (:itemIds)
                  and lower(icr.role) = lower(cast(:role as text))
                """;
        return queryRows(sql, Map.of("itemIds", itemIds, "role", role));
    }

    private List<Map<String, Object>> runOccur(List<UUID> constructorIds, String role) {
        String sql = """
                select distinct
                       vc.item_id,
                       vc.lib_id,
                       vc.article_name,
                       'constructor' as kind,
                       vc.constructor_kind as subkind,
                       vc.text_content,
                       'constructors' as node_type
                from item_constructor_ref icr
                join view_constructors vc on vc.item_id = icr.item_id
                where icr.constructor_item_id in (:itemIds)
                  and lower(icr.role) = lower(cast(:role as text))
                """;
        return queryRows(sql, Map.of("itemIds", constructorIds, "role", role));
    }

    private List<Map<String, Object>> runDefinition(List<UUID> constructorIds) {
        String sql = """
                select distinct
                       vd.item_id,
                       vd.lib_id,
                       vd.article_name,
                       vd.kind,
                       vd.subkind,
                       vd.text_content,
                       'definitions' as node_type
                from view_definitions vd
                where vd.item_id in (
                    select cd.definition_statement_item_id
                    from constructor_definition cd
                    where cd.constructor_item_id in (:itemIds)
                )
                """;
        return queryRows(sql, Map.of("itemIds", constructorIds));
    }

    private List<Map<String, Object>> runNotation(List<UUID> constructorIds) {
        String sql = """
                select distinct
                       vn.item_id,
                       vn.lib_id,
                       vn.article_name,
                       vn.kind,
                       vn.subkind,
                       vn.text_content,
                       'notations' as node_type
                from view_notations vn
                join notation_constructor nc on nc.notation_item_id = vn.item_id
                where nc.constructor_item_id in (:itemIds)
                """;
        return queryRows(sql, Map.of("itemIds", constructorIds));
    }

    private List<Map<String, Object>> runOrigin(List<UUID> copyIds) {
        String sql = """
                select distinct
                       vc.item_id,
                       vc.lib_id,
                       vc.article_name,
                       'constructor' as kind,
                       vc.constructor_kind as subkind,
                       vc.text_content,
                       'constructors' as node_type
                from constructor_redefinition cr
                join view_constructors vc on vc.item_id = cr.origin_item_id
                where cr.copy_item_id in (:itemIds)
                """;
        return queryRows(sql, Map.of("itemIds", copyIds));
    }

    private List<Map<String, Object>> runCopy(List<UUID> originIds) {
        String sql = """
                select distinct
                       vc.item_id,
                       vc.lib_id,
                       vc.article_name,
                       'constructor' as kind,
                       vc.constructor_kind as subkind,
                       vc.text_content,
                       'constructors' as node_type
                from constructor_redefinition cr
                join view_constructors vc on vc.item_id = cr.copy_item_id
                where cr.origin_item_id in (:itemIds)
                """;
        return queryRows(sql, Map.of("itemIds", originIds));
    }

    private List<Map<String, Object>> runMainRegistrationConstructor(List<UUID> registrationIds, boolean mode) {
        String column = mode ? "main_mode_constructor_id" : "main_func_constructor_id";
        String sql = """
                select distinct
                       vc.item_id,
                       vc.lib_id,
                       vc.article_name,
                       'constructor' as kind,
                       vc.constructor_kind as subkind,
                       vc.text_content,
                       'constructors' as node_type
                from registration r
                join view_constructors vc on vc.item_id = r.%s
                where r.item_id in (:itemIds)
                  and r.%s is not null
                """.formatted(column, column);
        return queryRows(sql, Map.of("itemIds", registrationIds));
    }

    private Map<UUID, Integer> countByOperation(List<UUID> itemIds, BasicOperationType operationType) {
        String sql = switch (operationType) {
            case REF -> """
                    select icr.item_id, count(distinct icr.constructor_item_id) as cnt
                    from item_constructor_ref icr
                    where icr.item_id in (:itemIds)
                      and lower(icr.role) = 'ref'
                    group by icr.item_id
                    """;
            case OCCUR -> """
                    select icr.constructor_item_id as item_id, count(distinct icr.item_id) as cnt
                    from item_constructor_ref icr
                    where icr.constructor_item_id in (:itemIds)
                      and lower(icr.role) = 'ref'
                    group by icr.constructor_item_id
                    """;
            case DEFINITION -> """
                    select cd.constructor_item_id as item_id, count(*) as cnt
                    from constructor_definition cd
                    where cd.constructor_item_id in (:itemIds)
                    group by cd.constructor_item_id
                    """;
            case NOTATION -> """
                    select nc.constructor_item_id as item_id, count(distinct nc.notation_item_id) as cnt
                    from notation_constructor nc
                    where nc.constructor_item_id in (:itemIds)
                    group by nc.constructor_item_id
                    """;
            case TERMTYPE_REF -> """
                    select icr.item_id, count(distinct icr.constructor_item_id) as cnt
                    from item_constructor_ref icr
                    where icr.item_id in (:itemIds)
                      and lower(icr.role) = 'termtype_ref'
                    group by icr.item_id
                    """;
            case DEFTYPE_REF -> """
                    select icr.item_id, count(distinct icr.constructor_item_id) as cnt
                    from item_constructor_ref icr
                    where icr.item_id in (:itemIds)
                      and lower(icr.role) = 'deftype_ref'
                    group by icr.item_id
                    """;
            default -> null;
        };

        if (sql == null) {
            return Map.of();
        }

        List<Map<String, Object>> rows = queryRows(sql, Map.of("itemIds", itemIds));
        Map<UUID, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID itemId = asUuid(row.get("item_id"));
            if (itemId == null) {
                continue;
            }
            int cnt = ((Number) row.getOrDefault("cnt", 0)).intValue();
            result.put(itemId, cnt);
        }
        return result;
    }

    private boolean compare(int value, int threshold, CardinalityComparator comparator) {
        return switch (comparator) {
            case EQ -> value == threshold;
            case GE -> value >= threshold;
            case LE -> value <= threshold;
            case GT -> value > threshold;
            case LT -> value < threshold;
        };
    }

    private List<UUID> extractItemIds(QueryEvaluationService.QueryResult input) {
        if (input == null || input.getData() == null || input.getData().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : input.getData()) {
            UUID id = asUuid(row.get("item_id"));
            if (id != null) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
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
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> queryRows(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }

        return spec.query((rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("item_id", rs.getObject("item_id"));
            row.put("lib_id", safeGet(rs, "lib_id"));
            row.put("article_name", safeGet(rs, "article_name"));
            row.put("kind", safeGet(rs, "kind"));
            row.put("subkind", safeGet(rs, "subkind"));
            Object rawText = safeGet(rs, "raw_text");
            if (rawText == null) {
                rawText = safeGet(rs, "text_content");
            }
            row.put("raw_text", rawText);
            row.put("text_content", rawText);
            row.put("node_type", safeGet(rs, "node_type"));
            row.put("text_position", safeGet(rs, "text_position"));
            Object cnt = safeGet(rs, "cnt");
            if (cnt != null) {
                row.put("cnt", cnt);
            }
            return row;
        }).list();
    }

    private Object safeGet(java.sql.ResultSet rs, String column) {
        try {
            return rs.getObject(column);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> unionRows(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        List<Map<String, Object>> all = new ArrayList<>(left);
        all.addAll(right);
        return deduplicateRows(all);
    }

    private List<Map<String, Object>> deduplicateRows(List<Map<String, Object>> rows) {
        LinkedHashMap<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = uniqueRowKey(row);
            indexed.putIfAbsent(key, row);
        }
        return new ArrayList<>(indexed.values());
    }

    private String uniqueRowKey(Map<String, Object> row) {
        UUID itemId = asUuid(row.get("item_id"));
        if (itemId != null) {
            return "item:" + itemId;
        }
        Object libId = row.get("lib_id");
        if (libId != null) {
            return "lib:" + libId;
        }
        return "row:" + row.hashCode();
    }
}
