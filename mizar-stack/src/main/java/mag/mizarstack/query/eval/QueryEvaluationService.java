package mag.mizarstack.query.eval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.query.ast.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryEvaluationService {

    private static final String SQL_LIKE_ESCAPE_CLAUSE = " escape '\\'";

    private static final Map<String, List<String>> ATTRIBUTE_KEY_CANDIDATES = Map.ofEntries(
            Map.entry("absoluteconstrmmlid", List.of("absoluteconstrMMLId", "absoluteconstrmmlid")),
            Map.entry("absolutenr", List.of("absolutenr")),
            Map.entry("absoluteorigconstrmmlid", List.of("absoluteorigconstrMMLId", "absoluteorigconstrmmlid")),
            Map.entry("absoluteorigpatternmmlid", List.of("absoluteorigpatternMMLId", "absoluteorigpatternmmlid")),
            Map.entry("absolutepatternmmlid", List.of("absolutepatternMMLId", "absolutepatternmmlid")),
            Map.entry("arity", List.of("arity")),
            Map.entry("constr", List.of("constr")),
            Map.entry("formatdes", List.of("formatdes")),
            Map.entry("formatnr", List.of("formatnr")),
            Map.entry("kind", List.of("kind")),
            Map.entry("mmlid", List.of("MMLId", "mmlid")),
            Map.entry("occurs", List.of("occurs")),
            Map.entry("origconstrnr", List.of("origconstrnr")),
            Map.entry("origpatternnr", List.of("origpatternnr")),
            Map.entry("patternnr", List.of("patternnr")),
            Map.entry("position", List.of("position")),
            Map.entry("spelling", List.of("spelling")),
            Map.entry("xmlid", List.of("xmlid"))
    );

    private final JdbcClient jdbcClient;
    private final ExtendedQueryEvaluationService extendedEvaluationService;

    public QueryResult evaluate(QueryNode query) {
        log.info("Evaluating query: {}", query.getClass().getSimpleName());
        if (query instanceof ListQueryNode node) {
            return visitListQuery(node);
        }
        if (query instanceof ArticleQueryNode node) {
            return visitArticleQuery(node);
        }
        if (query instanceof GroupQueryNode node) {
            return visitGroupQuery(node);
        }
        if (query instanceof CompoundQueryNode node) {
            return visitCompoundQuery(node);
        }
        if (query instanceof ContextQueryNode node) {
            return visitContextQuery(node);
        }
        if (query instanceof OperationQueryNode node) {
            return visitOperationQuery(node);
        }
        if (query instanceof SelectiveQueryNode node) {
            return visitSelectiveQuery(node);
        }
        if (query instanceof EnumeratedListNode node) {
            return visitEnumeratedList(node);
        }
        throw new IllegalArgumentException("Unsupported query node: " + query.getClass().getName());
    }

    public PagedListQueryResult evaluatePagedListQuery(
            ListQueryNode node,
            int page,
            int size,
            String sortBy,
            String sortDirection,
            String filter
    ) {
        if (node == null) {
            throw new IllegalArgumentException("List query node is required for paged evaluation.");
        }

        String source = normalizeSource(node.getSource());
        PagedListPlan plan = buildPagedListPlan(node, source);

        String normalizedFilter = normalizeLookupValue(filter);
        String filterPattern = normalizedFilter.isBlank() ? null : toSqlLikeContainsPattern(normalizedFilter);
        String filterClause = buildFilterClause(plan.listType(), filterPattern != null);

        SortResolution sortResolution = resolveSort(plan.listType(), sortBy, sortDirection);
        int safeSize = Math.max(1, size);
        int safePage = Math.max(0, page);

        Map<String, Object> params = new LinkedHashMap<>(plan.params());
        if (filterPattern != null) {
            params.put("filterPattern", filterPattern);
        }

        String countSql = """
                with base as (
                %s
                )
                select count(*)
                from base q
                where 1=1
                %s
                """.formatted(plan.baseSql(), filterClause);
        int totalCount = queryCount(countSql, params);

        int maxPage = totalCount == 0 ? 0 : (totalCount - 1) / safeSize;
        int effectivePage = Math.min(safePage, maxPage);
        int offset = effectivePage * safeSize;
        params.put("limit", safeSize);
        params.put("offset", offset);

        String pageSql = """
                with base as (
                %s
                )
                select *
                from base q
                where 1=1
                %s
                order by %s
                limit :limit
                offset :offset
                """.formatted(plan.baseSql(), filterClause, sortResolution.orderClause());

        List<Map<String, Object>> data = plan.genericRows()
                ? queryGenericRows(pageSql, params)
                : queryRows(pageSql, params);

        return new PagedListQueryResult(
                data,
                totalCount,
                "List query: " + node.getListType(),
                effectivePage,
                safeSize,
                sortResolution.sortByKey(),
                sortResolution.sortDirection(),
                filterPattern == null ? null : filter
        );
    }

    private QueryResult visitListQuery(ListQueryNode node) {
        String source = normalizeSource(node.getSource());
        if (node.getListType() == ListType.SYMBOLS) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("source", source);
            String sql = buildSymbolNodesBaseSql(params, node.getSymbolSpellingFilter())
                    + "\norder by a.name asc, mi.lib_id asc, n.item_id asc, n.pos asc, n.id asc";
            return new QueryResult(queryRows(sql, params), "List query: " + node.getListType());
        }
        if (node.getListType() == ListType.SYMBOL_OCCURRENCES) {
            String sql = """
                    select trim(coalesce(n.details -> 'attrs' ->> 'spelling', '')) as spelling,
                           count(*) as occurrences
                    from item_node n
                    join mml_item mi on mi.id = n.item_id
                    join article a on a.id = mi.article_id
                    where (:source = '*' or a.name = :source)
                      and right(lower(coalesce(n.details ->> 'tag', '')), 8) = '-pattern'
                      and nullif(trim(coalesce(n.details -> 'attrs' ->> 'spelling', '')), '') is not null
                    group by trim(coalesce(n.details -> 'attrs' ->> 'spelling', ''))
                    order by occurrences desc, spelling asc
                    """;
            return new QueryResult(queryGenericRows(sql, Map.of("source", source)), "List query: " + node.getListType());
        }

        String sql = switch (node.getListType()) {
            case THEOREMS -> """
                    select vt.item_id, vt.lib_id, vt.article_name, vt.kind,
                           vt.subkind, vt.text_content, vt.node_type
                    from view_theorems vt
                    where :source = '*'
                       or vt.article_name = :source
                    """;
            case DEFINITIONS -> """
                    select vd.item_id, vd.lib_id, vd.article_name, vd.kind,
                           vd.subkind, vd.text_content, vd.node_type
                    from view_definitions vd
                    where :source = '*'
                       or vd.article_name = :source
                    """;
            case STATEMENTS -> """
                    select vs.item_id, vs.lib_id, vs.article_name, vs.kind,
                           vs.subkind, vs.text_content, vs.node_type
                    from view_statements vs
                    where :source = '*'
                       or vs.article_name = :source
                    """;
            case REGISTRATIONS -> """
                    select vr.item_id, vr.lib_id, vr.article_name, vr.kind,
                           vr.subkind, vr.text_content, vr.node_type
                    from view_registrations vr
                    where :source = '*'
                       or vr.article_name = :source
                    """;
            case SYMBOLS -> throw new IllegalStateException("Unexpected symbol list dispatch.");
            case SYMBOL_OCCURRENCES -> throw new IllegalStateException("Unexpected symbol-occurrence list dispatch.");
            case ALL -> """
                    select vi.id as item_id,
                           vi.lib_id,
                           vi.article_name,
                           vi.kind,
                           vi.subkind,
                           vi.text_content,
                           case
                               when vi.kind = 'statement' and vi.subkind = 'th' then 'theorems'
                               when vi.kind = 'statement' and vi.subkind in ('def', 'dfs') then 'definitions'
                               when vi.kind = 'statement' and vi.subkind = 'sch' then 'schemes'
                               when vi.kind = 'registration' then 'registrations'
                               else vi.kind
                            end as node_type
                    from view_items vi
                    where :source = '*'
                       or vi.article_name = :source
                    """;
        };
        return new QueryResult(queryRows(sql, Map.of("source", source)), "List query: " + node.getListType());
    }

    private PagedListPlan buildPagedListPlan(ListQueryNode queryNode, String source) {
        ListType listType = queryNode.getListType();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", source);

        if (listType == ListType.SYMBOLS) {
            String baseSql = buildSymbolNodesBaseSql(params, queryNode.getSymbolSpellingFilter());
            return new PagedListPlan(listType, baseSql, params, false);
        }
        if (listType == ListType.SYMBOL_OCCURRENCES) {
            String baseSql = """
                    select trim(coalesce(n.details -> 'attrs' ->> 'spelling', '')) as spelling,
                           count(*) as occurrences
                    from item_node n
                    join mml_item mi on mi.id = n.item_id
                    join article a on a.id = mi.article_id
                    where (:source = '*' or a.name = :source)
                      and right(lower(coalesce(n.details ->> 'tag', '')), 8) = '-pattern'
                      and nullif(trim(coalesce(n.details -> 'attrs' ->> 'spelling', '')), '') is not null
                    group by trim(coalesce(n.details -> 'attrs' ->> 'spelling', ''))
                    """;
            return new PagedListPlan(listType, baseSql, params, true);
        }

        String baseSql = switch (listType) {
            case THEOREMS -> """
                    select vt.item_id, vt.lib_id, vt.article_name, vt.kind,
                           vt.subkind, vt.text_content, vt.node_type
                    from view_theorems vt
                    where :source = '*'
                       or vt.article_name = :source
                    """;
            case DEFINITIONS -> """
                    select vd.item_id, vd.lib_id, vd.article_name, vd.kind,
                           vd.subkind, vd.text_content, vd.node_type
                    from view_definitions vd
                    where :source = '*'
                       or vd.article_name = :source
                    """;
            case STATEMENTS -> """
                    select vs.item_id, vs.lib_id, vs.article_name, vs.kind,
                           vs.subkind, vs.text_content, vs.node_type
                    from view_statements vs
                    where :source = '*'
                       or vs.article_name = :source
                    """;
            case REGISTRATIONS -> """
                    select vr.item_id, vr.lib_id, vr.article_name, vr.kind,
                           vr.subkind, vr.text_content, vr.node_type
                    from view_registrations vr
                    where :source = '*'
                       or vr.article_name = :source
                    """;
            case ALL -> """
                    select vi.id as item_id,
                           vi.lib_id,
                           vi.article_name,
                           vi.kind,
                           vi.subkind,
                           vi.text_content,
                           case
                               when vi.kind = 'statement' and vi.subkind = 'th' then 'theorems'
                               when vi.kind = 'statement' and vi.subkind in ('def', 'dfs') then 'definitions'
                               when vi.kind = 'statement' and vi.subkind = 'sch' then 'schemes'
                               when vi.kind = 'registration' then 'registrations'
                               else vi.kind
                            end as node_type
                    from view_items vi
                    where :source = '*'
                       or vi.article_name = :source
                    """;
            case SYMBOLS, SYMBOL_OCCURRENCES -> throw new IllegalStateException(
                    "Unexpected symbol list dispatch in entity plan."
            );
        };
        return new PagedListPlan(listType, baseSql, params, false);
    }

    private String buildSymbolNodesBaseSql(Map<String, Object> params, String spellingFilterRaw) {
        StringBuilder sql = new StringBuilder("""
                select n.item_id,
                       mi.lib_id,
                       a.name as article_name,
                       mi.kind,
                       mi.subkind,
                       mi.text_content,
                       'symbols' as node_type,
                       n.id as node_id,
                       coalesce(n.details -> 'attrs' ->> 'position', cast(n.pos as text)) as text_position,
                       n.node_path,
                       n.details ->> 'tag' as node_tag,
                       n.details -> 'attrs' ->> 'xmlid' as node_xmlid,
                       trim(coalesce(n.details -> 'attrs' ->> 'spelling', '')) as spelling
                from item_node n
                join mml_item mi on mi.id = n.item_id
                join article a on a.id = mi.article_id
                where (:source = '*' or a.name = :source)
                  and right(lower(coalesce(n.details ->> 'tag', '')), 8) = '-pattern'
                  and nullif(trim(coalesce(n.details -> 'attrs' ->> 'spelling', '')), '') is not null
                """);

        String normalizedSpellingFilter = normalizeLookupValue(spellingFilterRaw);
        if (!normalizedSpellingFilter.isBlank()) {
            boolean patternSpelling = isPatternLikeValue(normalizedSpellingFilter);
            sql.append("\n  and lower(coalesce(n.details -> 'attrs' ->> 'spelling', ''))");
            if (patternSpelling) {
                sql.append(" like :symbolSpellingPattern").append(SQL_LIKE_ESCAPE_CLAUSE);
                params.put("symbolSpellingPattern", toSqlLikePattern(normalizedSpellingFilter));
            } else {
                sql.append(" = :symbolSpelling");
                params.put("symbolSpelling", unescapePatternLiterals(normalizedSpellingFilter));
            }
        }
        return sql.toString();
    }

    private String buildFilterClause(ListType listType, boolean includeFilter) {
        if (!includeFilter) {
            return "";
        }

        if (listType == ListType.SYMBOLS) {
            return """
                      and (
                          lower(coalesce(q.spelling, '')) like :filterPattern escape '\\'
                          or lower(coalesce(q.article_name, '')) like :filterPattern escape '\\'
                          or lower(coalesce(q.lib_id, '')) like :filterPattern escape '\\'
                          or lower(coalesce(q.kind, '')) like :filterPattern escape '\\'
                          or lower(coalesce(q.subkind, '')) like :filterPattern escape '\\'
                          or lower(coalesce(q.node_type, '')) like :filterPattern escape '\\'
                          or lower(coalesce(q.text_content, '')) like :filterPattern escape '\\'
                          or lower(coalesce(cast(q.item_id as text), '')) like :filterPattern escape '\\'
                      )
                    """;
        }
        if (listType == ListType.SYMBOL_OCCURRENCES) {
            return """
                      and (
                          lower(coalesce(q.spelling, '')) like :filterPattern escape '\\'
                          or lower(coalesce(cast(q.occurrences as text), '')) like :filterPattern escape '\\'
                      )
                    """;
        }

        return """
                  and (
                      lower(coalesce(q.article_name, '')) like :filterPattern escape '\\'
                      or lower(coalesce(q.lib_id, '')) like :filterPattern escape '\\'
                      or lower(coalesce(q.kind, '')) like :filterPattern escape '\\'
                      or lower(coalesce(q.subkind, '')) like :filterPattern escape '\\'
                      or lower(coalesce(q.node_type, '')) like :filterPattern escape '\\'
                      or lower(coalesce(q.text_content, '')) like :filterPattern escape '\\'
                      or lower(coalesce(cast(q.item_id as text), '')) like :filterPattern escape '\\'
                  )
                """;
    }

    private SortResolution resolveSort(ListType listType, String requestedSortBy, String requestedSortDirection) {
        String direction = "desc".equalsIgnoreCase(safeToString(requestedSortDirection).trim()) ? "desc" : "asc";
        String normalizedSortBy = safeToString(requestedSortBy).trim().toLowerCase(Locale.ROOT);

        if (listType == ListType.SYMBOL_OCCURRENCES) {
            if ("spelling".equals(normalizedSortBy)) {
                return new SortResolution("q.spelling " + direction + " nulls last", "spelling", direction);
            }
            if ("occurrences".equals(normalizedSortBy) || "count".equals(normalizedSortBy)) {
                return new SortResolution("q.occurrences " + direction + " nulls last", "occurrences", direction);
            }
            return new SortResolution("q.occurrences desc, q.spelling asc", "occurrences", "desc");
        }

        if (listType == ListType.SYMBOLS) {
            Map<String, String> symbolSortColumns = Map.ofEntries(
                    Map.entry("spelling", "q.spelling"),
                    Map.entry("item_id", "q.item_id"),
                    Map.entry("lib_id", "q.lib_id"),
                    Map.entry("article_name", "q.article_name"),
                    Map.entry("kind", "q.kind"),
                    Map.entry("subkind", "q.subkind"),
                    Map.entry("node_type", "q.node_type"),
                    Map.entry("text_position", "q.text_position"),
                    Map.entry("raw", "q.text_content"),
                    Map.entry("raw_text", "q.text_content"),
                    Map.entry("text_content", "q.text_content")
            );
            String expression = symbolSortColumns.get(normalizedSortBy);
            if (expression != null) {
                return new SortResolution(expression + " " + direction + " nulls last", normalizedSortBy, direction);
            }
            if ("spelling".equals(normalizedSortBy)) {
                return new SortResolution("q.spelling " + direction + " nulls last", "spelling", direction);
            }
            return new SortResolution("q.spelling asc, q.article_name asc, q.lib_id asc, q.item_id asc", "spelling", "asc");
        }

        Map<String, String> entitySortColumns = Map.ofEntries(
                Map.entry("item_id", "q.item_id"),
                Map.entry("lib_id", "q.lib_id"),
                Map.entry("article_name", "q.article_name"),
                Map.entry("kind", "q.kind"),
                Map.entry("subkind", "q.subkind"),
                Map.entry("node_type", "q.node_type"),
                Map.entry("raw", "q.text_content"),
                Map.entry("raw_text", "q.text_content"),
                Map.entry("text_content", "q.text_content"),
                Map.entry("text_position", "q.text_content")
        );
        String expression = entitySortColumns.get(normalizedSortBy);
        if (expression != null) {
            return new SortResolution(expression + " " + direction + " nulls last", normalizedSortBy, direction);
        }

        return new SortResolution("q.article_name asc, q.lib_id asc, q.item_id asc", "article_name", "asc");
    }

    private QueryResult visitArticleQuery(ArticleQueryNode node) {
        String sql = """
                select vi.id as item_id,
                       vi.lib_id,
                       vi.article_name,
                       vi.kind,
                       vi.subkind,
                       vi.text_content,
                       case
                           when vi.kind = 'statement' and vi.subkind = 'th' then 'theorems'
                           when vi.kind = 'statement' and vi.subkind in ('def', 'dfs') then 'definitions'
                           when vi.kind = 'statement' and vi.subkind = 'sch' then 'schemes'
                           when vi.kind = 'registration' then 'registrations'
                           else vi.kind
                       end as node_type
                from view_items vi
                where vi.article_name = :articleName
                """;
        return new QueryResult(queryRows(sql, Map.of("articleName", node.getArticleName())), "Article query");
    }

    private QueryResult visitGroupQuery(GroupQueryNode node) {
        QueryResult inner = evaluate(node.getInner());
        if (node.getQuantifier() == GroupQuantifier.NONE) {
            return negate(inner);
        }
        return inner;
    }

    private QueryResult visitCompoundQuery(CompoundQueryNode node) {
        QueryResult left = evaluate(node.getLeft());
        QueryResult right = evaluate(node.getRight());

        List<Map<String, Object>> data = switch (node.getOperator()) {
            case AND -> intersect(left.getData(), right.getData());
            case OR -> union(left.getData(), right.getData());
            case BUTNOT, NOT -> difference(left.getData(), right.getData());
        };

        return new QueryResult(data, "Compound query: " + node.getOperator());
    }

    private QueryResult visitContextQuery(ContextQueryNode node) {
        QueryResult base = evaluate(node.getQuery());
        if (node.getContext() == null || node.getContext().getContextData() == null) {
            return base;
        }
        return applyCriterion(base, node.getContext().getContextData());
    }

    private QueryResult visitOperationQuery(OperationQueryNode node) {
        QueryResult base = evaluate(node.getQuery());
        return evaluateOperation(base, node.getOperation());
    }

    private QueryResult visitSelectiveQuery(SelectiveQueryNode node) {
        QueryResult base = evaluate(node.getQuery());
        return applyCriterion(base, node.getCriterion());
    }

    private QueryResult visitEnumeratedList(EnumeratedListNode node) {
        return new QueryResult(List.of(), "Enumerated constructor lists are no longer supported.");
    }

    private QueryResult evaluateOperation(QueryResult base, OperationNode operation) {
        if (operation instanceof BasicOperationNode node) {
            return extendedEvaluationService.applyBasicOperation(base, node);
        }
        if (operation instanceof CardinalityFilterOperationNode node) {
            return extendedEvaluationService.applyCardinalityFilter(base, node);
        }
        if (operation instanceof FilterOperationNode node) {
            return applyFilter(base, node.getFilterCriteria());
        }
        if (operation instanceof GrepOperationNode node) {
            return applyGrep(base, node.getPattern());
        }
        if (operation instanceof NodeSelectionOperationNode node) {
            return applyNodeSelection(base, node);
        }
        if (operation instanceof ReverseOperationNode node) {
            return applyReverse(base, node.getOperationType());
        }
        if (operation instanceof CompoundOperationNode node) {
            QueryResult left = evaluateOperation(base, node.getLeft());
            QueryResult right = evaluateOperation(base, node.getRight());
            return switch (node.getCombinator()) {
                case PIPE -> evaluateOperation(left, node.getRight());
                case AND -> new QueryResult(intersect(left.getData(), right.getData()), "Operation AND");
                case OR -> new QueryResult(union(left.getData(), right.getData()), "Operation OR");
            };
        }
        throw new IllegalArgumentException("Unsupported operation node: " + operation.getClass().getName());
    }

    private QueryResult applyFilter(QueryResult base, String criteria) {
        if (criteria == null || criteria.isBlank()) {
            return base;
        }
        String trimmed = criteria.trim();
        int eq = trimmed.indexOf('=');
        if (eq > 0) {
            String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String expected = trimmed.substring(eq + 1).trim();
            List<Map<String, Object>> filtered = base.getData().stream()
                    .filter(row -> valueMatches(row, key, expected))
                    .toList();
            return new QueryResult(filtered, "Filter applied: " + trimmed);
        }

        String needle = trimmed.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> filtered = base.getData().stream()
                .filter(row -> rowText(row).contains(needle))
                .toList();
        return new QueryResult(filtered, "Filter contains: " + trimmed);
    }

    private QueryResult applyGrep(QueryResult base, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return base;
        }
        final Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException ex) {
            throw new IllegalArgumentException("Invalid grep regex: " + ex.getMessage());
        }

        List<Map<String, Object>> filtered = base.getData().stream()
                .filter(row -> compiled.matcher(extractRawText(row)).find()
                        || compiled.matcher(safeToString(row.get("lib_id"))).find()
                        || compiled.matcher(safeToString(row.get("article_name"))).find())
                .toList();
        return new QueryResult(filtered, "Grep applied");
    }

    private QueryResult applyNodeSelection(QueryResult base, NodeSelectionOperationNode operation) {
        if (operation == null || operation.getTarget() == null) {
            return base;
        }

        List<UUID> itemIds = extractItemIds(base.getData());
        if (itemIds.isEmpty()) {
            return new QueryResult(List.of(), "No input items for node selection");
        }

        NodePredicate target = operation.getTarget();
        List<NodePredicate> descendantPredicates = operation.getDescendantPredicates() == null
                ? List.of()
                : operation.getDescendantPredicates();

        StringBuilder sql = new StringBuilder("""
                with base_items as (
                    select cast(unnest(string_to_array(:itemIdsCsv, ',')) as uuid) as item_id
                )
                select distinct
                       n0.id as node_id,
                       n0.item_id,
                       mi.lib_id,
                       a.name as article_name,
                       mi.kind,
                       coalesce(n0.details -> 'attrs' ->> 'kind', mi.subkind) as subkind,
                       n0.raw as raw_text,
                       coalesce(n0.details -> 'attrs' ->> 'kind', lower(coalesce(n0.details ->> 'tag', ''))) as node_type,
                       coalesce(n0.details -> 'attrs' ->> 'position', cast(n0.pos as text)) as text_position,
                       n0.node_path,
                       lower(coalesce(n0.details ->> 'tag', '')) as node_tag,
                       n0.details -> 'attrs' ->> 'xmlid' as node_xmlid
                from base_items bi
                join item_node n0 on n0.item_id = bi.item_id
                join mml_item mi on mi.id = n0.item_id
                join article a on a.id = mi.article_id
                where 1=1
                """);

        appendNodePredicateCondition(sql, target, "n0", 0);

        for (int i = 0; i < descendantPredicates.size(); i++) {
            int predicateIndex = i + 1;
            NodePredicate predicate = descendantPredicates.get(i);
            sql.append("\n  and ");
            if (predicate.isNegated()) {
                sql.append("not ");
            }
            sql.append("exists (")
                    .append("\n      select 1")
                    .append("\n      from item_node n").append(predicateIndex)
                    .append("\n      where n").append(predicateIndex).append(".item_id = n0.item_id")
                    .append("\n        and n").append(predicateIndex).append(".node_path like n0.node_path || '/%'");
            appendNodePredicateCondition(sql, predicate, "n" + predicateIndex, predicateIndex);
            sql.append("\n  )");
        }

        sql.append("\norder by article_name, text_position, n0.node_path");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemIdsCsv", toUuidCsv(itemIds));
        putNodePredicateParams(params, target, 0);
        for (int i = 0; i < descendantPredicates.size(); i++) {
            putNodePredicateParams(params, descendantPredicates.get(i), i + 1);
        }

        List<Map<String, Object>> rows = queryRows(sql.toString(), params);
        return new QueryResult(
                deduplicate(removeAncestorNodeRows(rows)),
                "Selected nodes: " + target.getNodeName()
        );
    }

    private QueryResult applyReverse(QueryResult base, ReverseOperationType type) {
        List<Map<String, Object>> copy = new ArrayList<>(base.getData());
        Collections.reverse(copy);
        String description = (type == ReverseOperationType.INVERT) ? "Inverted order" : "Reversed order";
        return new QueryResult(copy, description);
    }

    private QueryResult applyCriterion(QueryResult base, String criterion) {
        if (criterion == null || criterion.isBlank()) {
            return base;
        }
        if (criterion.startsWith("NODE_HAS|")) {
            return applyNodeHasCriterion(base, criterion);
        }
        if (criterion.startsWith("PROP_HAS|")) {
            return applyNodeHasCriterion(base, criterion);
        }
        if (criterion.startsWith("PROP_INFIX|")) {
            return applyPropositionInfixCriterion(base, criterion);
        }
        return applyFilter(base, criterion);
    }

    private QueryResult applyPropositionInfixCriterion(QueryResult base, String criterion) {
        Map<String, String> parts = parseCriterionParts(criterion);
        String first = parts.get("first");
        String second = parts.get("second");
        if (first == null || first.isBlank()) {
            throw new IllegalArgumentException("PROP_INFIX criterion requires first=<absolutepatternmmlid>");
        }
        List<PropositionPredicate> predicates = new ArrayList<>();
        predicates.add(new PropositionPredicate(
                "infix-term",
                "absolutepatternmmlid",
                first,
                false
        ));
        if (second == null || second.isBlank() || "*".equals(second)) {
            predicates.add(new PropositionPredicate("infix-term", null, null, false));
        } else {
            predicates.add(new PropositionPredicate("infix-term", "absolutepatternmmlid", second, false));
        }
        return applyNodePredicates(base, "proposition", predicates, true, "Filtered by proposition infix pattern");
    }

    private QueryResult applyNodeHasCriterion(QueryResult base, String criterion) {
        Map<String, String> parts = parseCriterionParts(criterion);
        String scope = normalizeScopeName(parts.get("scope"));
        // Backward-compatibility for historical PROP_HAS criteria without explicit scope.
        if (scope.isBlank()) {
            scope = "proposition";
        }
        int predicateCount = parsePositiveInt(parts.get("count"), "NODE_HAS criterion requires count=<positive integer>");

        List<PropositionPredicate> predicates = new ArrayList<>();
        for (int i = 0; i < predicateCount; i++) {
            String node = decodeCriterionPart(parts.get("n" + i));
            if (node == null || node.isBlank()) {
                throw new IllegalArgumentException("NODE_HAS criterion is missing node for predicate index " + i);
            }
            String attr = decodeCriterionPart(parts.get("a" + i));
            String value = decodeCriterionPart(parts.get("v" + i));
            if (attr == null || attr.isBlank()) {
                attr = null;
                value = null;
            }
            boolean negated = "1".equals(parts.get("neg" + i));
            predicates.add(new PropositionPredicate(node, attr, value, negated));
        }

        boolean requireDistinctNodes = "proposition".equals(scope);
        return applyNodePredicates(base, scope, predicates, requireDistinctNodes, "Filtered by scoped predicates");
    }

    private QueryResult applyNodePredicates(
            QueryResult base,
            String scope,
            List<PropositionPredicate> predicates,
            boolean requireDistinctNodes,
            String description
    ) {
        if (predicates == null || predicates.isEmpty()) {
            return base;
        }
        if (!"proposition".equals(scope) && !"item".equals(scope)) {
            throw new IllegalArgumentException("Unsupported scope for NODE_HAS criterion: " + scope);
        }

        List<UUID> itemIds = extractItemIds(base.getData());
        if (itemIds.isEmpty()) {
            return new QueryResult(List.of(), "No items to filter by scoped predicates");
        }

        StringBuilder sql = new StringBuilder("""
                with base_items as (
                    select cast(unnest(string_to_array(:itemIdsCsv, ',')) as uuid) as item_id
                )
                select distinct n0.item_id
                from base_items bi
                join item_node n0 on n0.item_id = bi.item_id
                where 1=1
                """);

        if ("proposition".equals(scope)) {
            sql.append("\n  and substring(n0.node_path from '^(.*/Proposition\\[[0-9]+\\])') is not null");
        }

        int anchorIndex = -1;
        for (int i = 0; i < predicates.size(); i++) {
            if (!predicates.get(i).negated()) {
                anchorIndex = i;
                break;
            }
        }
        boolean syntheticAnchor = anchorIndex < 0;
        if (syntheticAnchor) {
            appendNodePredicateCondition(
                    sql,
                    new PropositionPredicate("*", null, null, false),
                    "n0",
                    0
            );
        } else {
            appendNodePredicateCondition(sql, predicates.get(anchorIndex), "n0", 0);
        }

        int clauseIndex = 1;
        for (int i = 0; i < predicates.size(); i++) {
            if (!syntheticAnchor && i == anchorIndex) {
                continue;
            }
            PropositionPredicate predicate = predicates.get(i);
            sql.append("\n  and ");
            if (predicate.negated()) {
                sql.append("not ");
            }
            sql.append("exists (")
                    .append("\n      select 1")
                    .append("\n      from item_node n")
                    .append(clauseIndex)
                    .append("\n      where n").append(clauseIndex).append(".item_id = n0.item_id");
            if ("proposition".equals(scope)) {
                sql.append("\n        and substring(n").append(clauseIndex)
                        .append(".node_path from '^(.*/Proposition\\[[0-9]+\\])') = ")
                        .append("substring(n0.node_path from '^(.*/Proposition\\[[0-9]+\\])')");
            }
            if (requireDistinctNodes && !predicate.negated()) {
                sql.append("\n        and n").append(clauseIndex).append(".id <> n0.id");
            }

            appendNodePredicateCondition(sql, predicate, "n" + clauseIndex, clauseIndex);
            sql.append("\n  )");
            clauseIndex++;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemIdsCsv", toUuidCsv(itemIds));

        if (!syntheticAnchor) {
            PropositionPredicate anchor = predicates.get(anchorIndex);
            putNodePredicateParams(
                    params,
                    new NodePredicate(anchor.nodeName(), anchor.attributeName(), anchor.attributeValue(), anchor.negated()),
                    0
            );
        }

        int paramIndex = 1;
        for (int i = 0; i < predicates.size(); i++) {
            if (!syntheticAnchor && i == anchorIndex) {
                continue;
            }
            PropositionPredicate predicate = predicates.get(i);
            String normalizedNodeName = normalizeLookupValue(predicate.nodeName());
            if (!isWildcardNode(normalizedNodeName)) {
                if (isPatternLikeValue(normalizedNodeName)) {
                    params.put("nodePattern" + paramIndex, toSqlLikePattern(normalizedNodeName));
                } else {
                    params.put("node" + paramIndex, unescapePatternLiterals(normalizedNodeName));
                }
            }
            if (predicate.attributeName() != null && !predicate.attributeName().isBlank()) {
                String normalizedAttrName = normalizeLookupValue(predicate.attributeName());
                if (isPatternLikeValue(normalizedAttrName)) {
                    params.put("attrPattern" + paramIndex, toSqlLikePattern(normalizedAttrName));
                } else {
                    params.put("attr" + paramIndex, unescapePatternLiterals(normalizedAttrName));
                }
                if (predicate.attributeValue() != null) {
                    String normalizedAttrValue = normalizeLookupValue(predicate.attributeValue());
                    if (isPatternLikeValue(normalizedAttrValue)) {
                        params.put("valuePattern" + paramIndex, toSqlLikePattern(normalizedAttrValue));
                    } else {
                        params.put("value" + paramIndex, unescapePatternLiterals(normalizedAttrValue));
                    }
                }
            }
            paramIndex++;
        }

        List<UUID> matchedIds = queryItemIds(sql.toString(), params);
        Set<UUID> matched = new HashSet<>(matchedIds);
        List<Map<String, Object>> rows = base.getData().stream()
                .filter(row -> {
                    UUID id = asUuid(row.get("item_id"));
                    return id != null && matched.contains(id);
                })
                .toList();

        return new QueryResult(deduplicate(rows), description);
    }

    private QueryResult negate(QueryResult input) {
        String sql = """
                select vi.id as item_id,
                       vi.lib_id,
                       vi.article_name,
                       vi.kind,
                       vi.subkind,
                       vi.text_content,
                       case
                           when vi.kind = 'statement' and vi.subkind = 'th' then 'theorems'
                           when vi.kind = 'statement' and vi.subkind in ('def', 'dfs') then 'definitions'
                           when vi.kind = 'statement' and vi.subkind = 'sch' then 'schemes'
                           when vi.kind = 'registration' then 'registrations'
                           else vi.kind
                       end as node_type
                from view_items vi
                """;
        List<Map<String, Object>> universe = queryRows(sql, Map.of());
        return new QueryResult(difference(universe, input.getData()), "Negated query");
    }

    private List<Map<String, Object>> intersect(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        Set<String> rightKeys = new HashSet<>(keys(right));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : left) {
            if (rightKeys.contains(rowKey(row))) {
                out.add(row);
            }
        }
        return deduplicate(out);
    }

    private List<Map<String, Object>> union(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        List<Map<String, Object>> all = new ArrayList<>(left);
        all.addAll(right);
        return deduplicate(all);
    }

    private List<Map<String, Object>> difference(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        Set<String> rightKeys = new HashSet<>(keys(right));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : left) {
            if (!rightKeys.contains(rowKey(row))) {
                out.add(row);
            }
        }
        return deduplicate(out);
    }

    private List<String> keys(List<Map<String, Object>> rows) {
        return rows.stream().map(this::rowKey).toList();
    }

    private String rowKey(Map<String, Object> row) {
        Object nodeXmlId = row.get("node_xmlid");
        Object articleName = row.get("article_name");
        if (nodeXmlId != null && articleName != null) {
            return "xmlid:" + articleName + ":" + nodeXmlId;
        }
        Object nodeId = row.get("node_id");
        if (nodeId != null) {
            return "node:" + nodeId;
        }
        String spelling = safeToString(row.get("spelling"));
        if (!spelling.isBlank()) {
            return "spelling:" + normalizeLookupValue(spelling);
        }
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

    private List<Map<String, Object>> deduplicate(List<Map<String, Object>> rows) {
        LinkedHashMap<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            indexed.putIfAbsent(rowKey(row), row);
        }
        return new ArrayList<>(indexed.values());
    }

    private List<Map<String, Object>> removeAncestorNodeRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < 2) {
            return rows == null ? List.of() : rows;
        }

        Map<String, TreeSet<String>> pathsByItemId = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String itemId = safeToString(row.get("item_id"));
            String path = safeToString(row.get("node_path"));
            if (itemId.isBlank() || path.isBlank()) {
                continue;
            }
            pathsByItemId.computeIfAbsent(itemId, ignored -> new TreeSet<>()).add(path);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> candidate : rows) {
            String candidateItemId = safeToString(candidate.get("item_id"));
            String candidatePath = safeToString(candidate.get("node_path"));
            if (candidateItemId.isBlank() || candidatePath.isBlank()) {
                out.add(candidate);
                continue;
            }

            TreeSet<String> itemPaths = pathsByItemId.get(candidateItemId);
            String descendantPrefix = candidatePath + "/";
            String nearestDescendant = itemPaths == null ? null : itemPaths.ceiling(descendantPrefix);
            boolean hasMatchingDescendant = nearestDescendant != null && nearestDescendant.startsWith(descendantPrefix);

            if (!hasMatchingDescendant) {
                out.add(candidate);
            }
        }
        return out;
    }

    private List<UUID> extractItemIds(List<Map<String, Object>> rows) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            UUID id = asUuid(row.get("item_id"));
            if (id != null) {
                result.add(id);
            }
        }
        return new ArrayList<>(result);
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

    private boolean valueMatches(Map<String, Object> row, String key, String expected) {
        String actual = switch (key) {
            case "article", "article_name" -> safeToString(row.get("article_name"));
            case "lib", "lib_id" -> safeToString(row.get("lib_id"));
            case "kind" -> safeToString(row.get("kind"));
            case "subkind" -> safeToString(row.get("subkind"));
            case "node_type", "node" -> safeToString(row.get("node_type"));
            case "text", "raw_text", "text_content" -> extractRawText(row);
            case "position", "text_position" -> safeToString(row.get("text_position"));
            case "spelling" -> safeToString(row.get("spelling"));
            case "occurrences", "count" -> safeToString(row.get("occurrences"));
            default -> "";
        };
        String normalizedExpected = normalizeLookupValue(expected);
        if (isPatternLikeValue(normalizedExpected)) {
            return matchesPattern(actual, normalizedExpected);
        }
        return actual.equalsIgnoreCase(unescapePatternLiterals(normalizedExpected));
    }

    private String rowText(Map<String, Object> row) {
        return (safeToString(row.get("article_name")) + " "
                + safeToString(row.get("lib_id")) + " "
                + safeToString(row.get("kind")) + " "
                + safeToString(row.get("subkind")) + " "
                + safeToString(row.get("node_type")) + " "
                + safeToString(row.get("text_position")) + " "
                + safeToString(row.get("spelling")) + " "
                + safeToString(row.get("occurrences")) + " "
                + extractRawText(row)).toLowerCase(Locale.ROOT);
    }

    private String extractRawText(Map<String, Object> row) {
        String rawText = safeToString(row.get("raw_text"));
        if (!rawText.isBlank()) {
            return rawText;
        }
        String spelling = safeToString(row.get("spelling"));
        if (!spelling.isBlank()) {
            return spelling;
        }
        return safeToString(row.get("text_content"));
    }

    private String safeToString(Object value) {
        return value == null ? "" : value.toString();
    }

    private Map<String, String> parseCriterionParts(String criterion) {
        Map<String, String> out = new HashMap<>();
        String[] chunks = criterion.split("\\|");
        for (String chunk : chunks) {
            int idx = chunk.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = chunk.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = chunk.substring(idx + 1).trim();
            out.put(key, value);
        }
        return out;
    }

    private void appendNodePredicateCondition(
            StringBuilder sql,
            NodePredicate predicate,
            String alias,
            int predicateIndex
    ) {
        String normalizedNodeName = normalizeLookupValue(predicate.getNodeName());
        if (!isWildcardNode(normalizedNodeName)) {
            sql.append("\n    and lower(coalesce(").append(alias).append(".details ->> 'tag', ''))");
            if (isPatternLikeValue(normalizedNodeName)) {
                sql.append(" like :nodePattern").append(predicateIndex).append(SQL_LIKE_ESCAPE_CLAUSE);
            } else {
                sql.append(" = :node").append(predicateIndex);
            }
        }

        if (predicate.getAttributeName() != null && !predicate.getAttributeName().isBlank()) {
            appendNodeAttributeCondition(sql, predicate, alias, predicateIndex);
        }
    }

    private void appendNodePredicateCondition(
            StringBuilder sql,
            PropositionPredicate predicate,
            String alias,
            int predicateIndex
    ) {
        appendNodePredicateCondition(
                sql,
                new NodePredicate(
                        predicate.nodeName(),
                        predicate.attributeName(),
                        predicate.attributeValue(),
                        predicate.negated()
                ),
                alias,
                predicateIndex
        );
    }

    private void putNodePredicateParams(Map<String, Object> params, NodePredicate predicate, int predicateIndex) {
        String normalizedNodeName = normalizeLookupValue(predicate.getNodeName());
        if (!isWildcardNode(normalizedNodeName)) {
            if (isPatternLikeValue(normalizedNodeName)) {
                params.put("nodePattern" + predicateIndex, toSqlLikePattern(normalizedNodeName));
            } else {
                params.put("node" + predicateIndex, unescapePatternLiterals(normalizedNodeName));
            }
        }
        if (predicate.getAttributeName() != null && !predicate.getAttributeName().isBlank()) {
            String normalizedAttrName = normalizeLookupValue(predicate.getAttributeName());
            if (isPatternLikeValue(normalizedAttrName)) {
                params.put("attrPattern" + predicateIndex, toSqlLikePattern(normalizedAttrName));
            } else {
                params.put("attr" + predicateIndex, unescapePatternLiterals(normalizedAttrName));
            }
            if (predicate.getAttributeValue() != null) {
                String normalizedAttrValue = normalizeLookupValue(predicate.getAttributeValue());
                if (isPatternLikeValue(normalizedAttrValue)) {
                    params.put("valuePattern" + predicateIndex, toSqlLikePattern(normalizedAttrValue));
                } else {
                    params.put("value" + predicateIndex, unescapePatternLiterals(normalizedAttrValue));
                }
            }
        }
    }

    private boolean isWildcardNode(String nodeName) {
        return "*".equals(safeToString(nodeName).trim());
    }

    private void appendNodeAttributeCondition(
            StringBuilder sql,
            NodePredicate predicate,
            String alias,
            int predicateIndex
    ) {
        List<String> keyCandidates = attributeKeyCandidates(predicate.getAttributeName());
        if (!keyCandidates.isEmpty()) {
            appendKnownAttributeExists(sql, alias, keyCandidates);
            if (predicate.getAttributeValue() == null) {
                return;
            }

            String normalizedValue = normalizeLookupValue(predicate.getAttributeValue());
            boolean patternValue = isPatternLikeValue(normalizedValue);
            sql.append("\n    and lower(coalesce(");
            for (int i = 0; i < keyCandidates.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(alias)
                        .append(".details -> 'attrs' ->> '")
                        .append(escapeSqlLiteral(keyCandidates.get(i)))
                        .append("'");
            }
            sql.append(", ''))");
            if (patternValue) {
                sql.append(" like :valuePattern").append(predicateIndex).append(SQL_LIKE_ESCAPE_CLAUSE);
            } else {
                sql.append(" = :value").append(predicateIndex);
            }
            return;
        }

        String normalizedAttrName = normalizeLookupValue(predicate.getAttributeName());
        boolean patternAttrName = isPatternLikeValue(normalizedAttrName);
        sql.append("\n    and exists (")
                .append("\n        select 1")
                .append("\n        from jsonb_each_text(coalesce(").append(alias)
                .append(".details -> 'attrs', '{}'::jsonb)) a").append(predicateIndex)
                .append("(attr_key, attr_value)")
                .append("\n        where lower(a").append(predicateIndex).append(".attr_key)");
        if (patternAttrName) {
            sql.append(" like :attrPattern").append(predicateIndex).append(SQL_LIKE_ESCAPE_CLAUSE);
        } else {
            sql.append(" = :attr").append(predicateIndex);
        }

        if (predicate.getAttributeValue() != null) {
            String normalizedAttrValue = normalizeLookupValue(predicate.getAttributeValue());
            boolean patternAttrValue = isPatternLikeValue(normalizedAttrValue);
            sql.append("\n          and lower(coalesce(a").append(predicateIndex)
                    .append(".attr_value, ''))");
            if (patternAttrValue) {
                sql.append(" like :valuePattern").append(predicateIndex).append(SQL_LIKE_ESCAPE_CLAUSE);
            } else {
                sql.append(" = :value").append(predicateIndex);
            }
        }
        sql.append("\n    )");
    }

    private void appendKnownAttributeExists(StringBuilder sql, String alias, List<String> keyCandidates) {
        sql.append("\n    and (");
        for (int i = 0; i < keyCandidates.size(); i++) {
            if (i > 0) {
                sql.append(" or ");
            }
            sql.append("jsonb_exists(coalesce(").append(alias)
                    .append(".details -> 'attrs', '{}'::jsonb), '")
                    .append(escapeSqlLiteral(keyCandidates.get(i)))
                    .append("')");
        }
        sql.append(")");
    }

    private List<String> attributeKeyCandidates(String attributeName) {
        String normalized = normalizeLookupValue(attributeName);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> known = ATTRIBUTE_KEY_CANDIDATES.get(normalized);
        if (known != null) {
            return known;
        }
        if (normalized.matches("[a-z0-9_-]+")) {
            return List.of(normalized);
        }
        return List.of();
    }

    private String escapeSqlLiteral(String value) {
        return safeToString(value).replace("'", "''");
    }

    private int parsePositiveInt(String raw, String messageOnFailure) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(messageOnFailure);
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(messageOnFailure);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(messageOnFailure);
        }
    }

    private boolean isPatternLikeValue(String raw) {
        String normalized = normalizeLookupValue(raw);
        if (normalized.isBlank()) {
            return false;
        }
        boolean escaped = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '*' || ch == '_') {
                return true;
            }
        }
        return false;
    }

    private String toSqlLikeContainsPattern(String raw) {
        return "%" + toSqlLikePattern(raw) + "%";
    }

    private String toSqlLikePattern(String raw) {
        String normalized = normalizeLookupValue(raw);
        StringBuilder sb = new StringBuilder(normalized.length() + 8);
        boolean escaped = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (escaped) {
                appendSqlLikeLiteral(sb, ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '*') {
                sb.append('%');
            } else if (ch == '_') {
                sb.append('_');
            } else {
                appendSqlLikeLiteral(sb, ch);
            }
        }
        if (escaped) {
            appendSqlLikeLiteral(sb, '\\');
        }
        return sb.toString();
    }

    private String unescapePatternLiterals(String raw) {
        String normalized = normalizeLookupValue(raw);
        if (normalized.isBlank()) {
            return normalized;
        }
        StringBuilder sb = new StringBuilder(normalized.length());
        boolean escaped = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (escaped) {
                if (ch == '*' || ch == '_' || ch == '\\' || ch == '%') {
                    sb.append(ch);
                } else {
                    sb.append('\\').append(ch);
                }
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            sb.append(ch);
        }
        if (escaped) {
            sb.append('\\');
        }
        return sb.toString();
    }

    private void appendSqlLikeLiteral(StringBuilder sb, char ch) {
        if (ch == '%' || ch == '_' || ch == '\\') {
            sb.append('\\');
        }
        sb.append(ch);
    }

    private boolean matchesPattern(String actualRaw, String patternRaw) {
        String actual = normalizeLookupValue(actualRaw);
        String pattern = normalizeLookupValue(patternRaw);
        StringBuilder regex = new StringBuilder(pattern.length() * 2);
        regex.append('^');
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                appendRegexLiteral(regex, ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '*') {
                regex.append(".*");
            } else if (ch == '_') {
                regex.append('.');
            } else {
                appendRegexLiteral(regex, ch);
            }
        }
        if (escaped) {
            appendRegexLiteral(regex, '\\');
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(actual)
                .matches();
    }

    private void appendRegexLiteral(StringBuilder regex, char ch) {
        if ("\\.^$|?*+()[]{}".indexOf(ch) >= 0) {
            regex.append('\\');
        }
        regex.append(ch);
    }

    private String decodeCriterionPart(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            return "";
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(raw);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    private String normalizeLookupValue(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeScopeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSource(QuerySource source) {
        if (source == null || source.getSource() == null || source.getSource().isBlank()) {
            return "*";
        }
        return source.getSource().toUpperCase(Locale.ROOT);
    }

    private String toUuidCsv(List<UUID> ids) {
        StringJoiner joiner = new StringJoiner(",");
        for (UUID id : ids) {
            if (id != null) {
                joiner.add(id.toString());
            }
        }
        return joiner.toString();
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
            Object nodeId = safeGet(rs, "node_id");
            if (nodeId != null) {
                row.put("node_id", nodeId);
            }
            Object nodePath = safeGet(rs, "node_path");
            if (nodePath != null) {
                row.put("node_path", nodePath);
            }
            Object nodeTag = safeGet(rs, "node_tag");
            if (nodeTag != null) {
                row.put("node_tag", nodeTag);
            }
            Object nodeXmlId = safeGet(rs, "node_xmlid");
            if (nodeXmlId != null) {
                row.put("node_xmlid", nodeXmlId);
            }
            Object spelling = safeGet(rs, "spelling");
            if (spelling != null) {
                row.put("spelling", spelling);
            }
            Object occurrences = safeGet(rs, "occurrences");
            if (occurrences != null) {
                row.put("occurrences", occurrences);
            }
            return row;
        }).list();
    }

    private List<Map<String, Object>> queryGenericRows(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query((rs, rowNum) -> {
            java.sql.ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String key = metaData.getColumnLabel(i);
                if (key == null || key.isBlank()) {
                    key = metaData.getColumnName(i);
                }
                row.put(key, rs.getObject(i));
            }
            return row;
        }).list();
    }

    private int queryCount(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query((rs, rowNum) -> rs.getInt(1))
                .optional()
                .orElse(0);
    }

    private List<UUID> queryItemIds(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        List<UUID> rows = spec.query((rs, rowNum) -> asUuid(rs.getObject("item_id"))).list();
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID row : rows) {
            if (row != null) {
                unique.add(row);
            }
        }
        return new ArrayList<>(unique);
    }

    private Object safeGet(java.sql.ResultSet rs, String column) {
        try {
            return rs.getObject(column);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record PropositionPredicate(
            String nodeName,
            String attributeName,
            String attributeValue,
            boolean negated
    ) {
    }

    private record PagedListPlan(
            ListType listType,
            String baseSql,
            Map<String, Object> params,
            boolean genericRows
    ) {
    }

    private record SortResolution(
            String orderClause,
            String sortByKey,
            String sortDirection
    ) {
    }

    public record PagedListQueryResult(
            List<Map<String, Object>> data,
            int totalCount,
            String description,
            int page,
            int size,
            String sortBy,
            String sortDirection,
            String filter
    ) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryResult {
        private List<Map<String, Object>> data = new ArrayList<>();
        private String description;
    }
}
