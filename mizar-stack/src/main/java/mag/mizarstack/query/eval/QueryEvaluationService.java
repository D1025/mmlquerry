package mag.mizarstack.query.eval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.query.ast.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryEvaluationService {

    private static final String SQL_LIKE_ESCAPE_CLAUSE = " escape '\\'";
    private static final String PROPOSITION_PATH_REGEX = "^(.*/proposition\\[[0-9]+\\])";
    private static final Pattern XML_OPEN_TAG_PATTERN = Pattern.compile(
            "<\\s*([A-Za-z][A-Za-z0-9\\-]*)\\b([^<>]*)>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NUMBER_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\bnumber\\s*=\\s*\"([0-9]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPELLING_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\bspelling\\s*=\\s*\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern XML_ENTITY_NUMERIC_HEX_PATTERN = Pattern.compile("&#x([0-9a-fA-F]+);");
    private static final Pattern XML_ENTITY_NUMERIC_DEC_PATTERN = Pattern.compile("&#([0-9]+);");
    private static final String SYNTHETIC_NEGATED_ADJECTIVE_NODE = "__negated_adjective__";
    private static final String ATTRIBUTE_TAG = "attribute";
    private static final String ATTRIBUTE_KEY_NONOCC = "nonocc";
    private static final String ATTRIBUTE_KEY_SPELLING = "spelling";

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
        if (operation instanceof NodeCardinalityFilterOperationNode node) {
            return applyNodeCardinalityFilter(base, node);
        }
        if (operation instanceof FilterOperationNode node) {
            return applyFilter(base, node.getFilterCriteria());
        }
        if (operation instanceof GrepOperationNode node) {
            return applyGrep(base, node.getPattern());
        }
        if (operation instanceof NumericValueFilterOperationNode node) {
            return applyNumericValueFilter(base, node);
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

    private QueryResult applyNumericValueFilter(QueryResult base, NumericValueFilterOperationNode operation) {
        if (operation == null) {
            return base;
        }

        List<UUID> itemIds = extractItemIds(base.getData());
        if (!itemIds.isEmpty()) {
            BigInteger threshold = BigInteger.valueOf(operation.getThreshold());
            BigInteger cap = threshold.add(BigInteger.ONE);
            Set<UUID> matched = new HashSet<>(
                    queryNumericLiteralItemIds(itemIds, operation.getComparator(), operation.getThreshold())
            );
            List<Map<String, Object>> filtered = base.getData().stream()
                    .filter(row -> {
                        UUID itemId = asUuid(row.get("item_id"));
                        if (itemId != null) {
                            return matched.contains(itemId);
                        }
                        return matchesNumericThreshold(
                                extractRawText(row),
                                operation.getComparator(),
                                threshold,
                                cap
                        );
                    })
                    .toList();
            return new QueryResult(
                    filtered,
                    "Numeric value filter " + operation.getComparator() + "(" + operation.getThreshold() + ")"
            );
        }

        BigInteger threshold = BigInteger.valueOf(operation.getThreshold());
        BigInteger cap = threshold.add(BigInteger.ONE);

        List<UUID> missingTextItemIds = base.getData().stream()
                .filter(Objects::nonNull)
                .filter(row -> extractRawText(row).isBlank())
                .map(row -> asUuid(row.get("item_id")))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, String> rawXmlByItemId = loadItemRawXmlByIds(missingTextItemIds);

        List<Map<String, Object>> filtered = base.getData().stream()
                .filter(row -> {
                    String text = extractRawText(row);
                    if (text.isBlank()) {
                        UUID itemId = asUuid(row.get("item_id"));
                        if (itemId != null) {
                            text = safeToString(rawXmlByItemId.get(itemId));
                        }
                    }
                    return matchesNumericThreshold(text, operation.getComparator(), threshold, cap);
                })
                .toList();
        return new QueryResult(
                filtered,
                "Numeric value filter " + operation.getComparator() + "(" + operation.getThreshold() + ")"
        );
    }

    private List<UUID> queryNumericLiteralItemIds(
            List<UUID> itemIds,
            CardinalityComparator comparator,
            long threshold
    ) {
        if (itemIds == null || itemIds.isEmpty() || comparator == null) {
            return List.of();
        }

        String sqlComparator = switch (comparator) {
            case EQ -> "=";
            case GE -> ">=";
            case LE -> "<=";
            case GT -> ">";
            case LT -> "<";
        };

        String sql = """
                with base_items as (
                    select cast(unnest(string_to_array(:itemIdsCsv, ',')) as uuid) as item_id
                )
                select distinct n.item_id
                from base_items bi
                join item_node n on n.item_id = bi.item_id
                where lower(coalesce(n.details ->> 'tag', '')) in ('numeral-term', 'natural-term', 'numeralterm', 'naturalterm')
                  and jsonb_exists(coalesce(n.details -> 'attrs', '{}'::jsonb), 'number')
                  and coalesce(n.details -> 'attrs' ->> 'number', '') ~ '^[0-9]+$'
                  and (coalesce(n.details -> 'attrs' ->> 'number', '0'))::numeric
                """ + " " + sqlComparator + " cast(:numericThreshold as numeric)";

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemIdsCsv", toUuidCsv(itemIds));
        params.put("numericThreshold", Long.toString(threshold));
        return queryItemIds(sql, params);
    }

    private QueryResult applyNodeCardinalityFilter(QueryResult base, NodeCardinalityFilterOperationNode operation) {
        if (operation == null) {
            return base;
        }
        List<UUID> itemIds = extractItemIds(base.getData());
        if (itemIds.isEmpty()) {
            return new QueryResult(List.of(), "No input items for node cardinality filter");
        }

        String scope = normalizeScopeName(operation.getScopeName());
        if (scope.isBlank()) {
            scope = "item";
        }
        if (!"item".equals(scope) && !"proposition".equals(scope)) {
            throw new IllegalArgumentException("Unsupported scope in node cardinality filter: " + scope);
        }

        NodePredicate predicate = new NodePredicate(operation.getNodeName(), null, null, false);

        StringBuilder sql = new StringBuilder("""
                with base_items as (
                    select cast(unnest(string_to_array(:itemIdsCsv, ',')) as uuid) as item_id
                )
                """);

        if ("proposition".equals(scope)) {
            String propositionPath = propositionPathExpression("n");
            sql.append("""
                    , proposition_counts as (
                        select n.item_id,
                    """);
            sql.append("               ").append(propositionPath).append(" as proposition_path,\n");
            sql.append("""
                               count(*) as cnt
                        from base_items bi
                        join item_node n on n.item_id = bi.item_id
                        where
                    """);
            sql.append("                        ")
                    .append(propositionPath)
                    .append(" is not null\n");
            appendNodePredicateCondition(sql, predicate, "n", 0);
            sql.append("\n                        group by n.item_id,\n");
            sql.append("                                 ").append(propositionPath).append("\n");
            sql.append("""
                    )
                    select item_id, max(cnt) as cnt
                    from proposition_counts
                    group by item_id
                    """);
        } else {
            sql.append("""
                    select n.item_id, count(*) as cnt
                    from base_items bi
                    join item_node n on n.item_id = bi.item_id
                    where 1=1
                    """);
            appendNodePredicateCondition(sql, predicate, "n", 0);
            sql.append("\ngroup by n.item_id");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemIdsCsv", toUuidCsv(itemIds));
        putNodePredicateParams(params, predicate, 0);

        List<Map<String, Object>> countRows = queryGenericRows(sql.toString(), params);
        Map<UUID, Integer> countsByItem = new HashMap<>();
        for (Map<String, Object> row : countRows) {
            UUID itemId = asUuid(row.get("item_id"));
            if (itemId == null) {
                continue;
            }
            int count = ((Number) row.getOrDefault("cnt", 0)).intValue();
            countsByItem.put(itemId, count);
        }

        List<Map<String, Object>> filtered = base.getData().stream()
                .filter(row -> {
                    UUID itemId = asUuid(row.get("item_id"));
                    int value = itemId == null ? 0 : countsByItem.getOrDefault(itemId, 0);
                    return compareCardinalityValue(value, operation.getThreshold(), operation.getComparator());
                })
                .toList();

        return new QueryResult(
                filtered,
                "Node cardinality filter " + operation.getComparator()
                        + "(" + scope + ":" + operation.getNodeName() + ", " + operation.getThreshold() + ")"
        );
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
                       trim(coalesce(n0.details -> 'attrs' ->> 'spelling', '')) as spelling,
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

        QueryResult optimizedResult = tryApplyOptimizedScopedPredicate(base, scope, predicates, description, itemIds);
        if (optimizedResult != null) {
            return optimizedResult;
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
            sql.append("\n  and ").append(propositionPathExpression("n0")).append(" is not null");
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
                String clauseAlias = "n" + clauseIndex;
                sql.append("\n        and ")
                        .append(propositionPathExpression(clauseAlias))
                        .append(" = ")
                        .append(propositionPathExpression("n0"));
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

    private QueryResult tryApplyOptimizedScopedPredicate(
            QueryResult base,
            String scope,
            List<PropositionPredicate> predicates,
            String description,
            List<UUID> itemIds
    ) {
        if (!"proposition".equals(scope) || predicates == null || predicates.size() != 1) {
            return null;
        }

        PropositionPredicate predicate = predicates.get(0);
        if (predicate == null || predicate.negated()) {
            return null;
        }

        String normalizedNodeName = normalizeLookupValue(predicate.nodeName());
        if (!SYNTHETIC_NEGATED_ADJECTIVE_NODE.equals(normalizedNodeName)) {
            return null;
        }

        String normalizedSpelling = normalizeLookupValue(predicate.attributeValue());
        if (normalizedSpelling.isBlank()) {
            return null;
        }

        StringBuilder sql = new StringBuilder("""
                with base_items as (
                    select cast(unnest(string_to_array(:itemIdsCsv, ',')) as uuid) as item_id
                )
                select distinct n.item_id
                from item_node n
                join base_items bi on bi.item_id = n.item_id
                where lower(coalesce(n.details ->> 'tag', '')) = 'attribute'
                  and jsonb_exists(coalesce(n.details -> 'attrs', '{}'::jsonb), 'spelling')
                  and lower(coalesce(n.details -> 'attrs' ->> 'nonocc', '')) = 'true'
                  and strpos(lower(n.node_path), '/proposition[') > 0
                """);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("itemIdsCsv", toUuidCsv(itemIds));
        if (isPatternLikeValue(normalizedSpelling)) {
            sql.append("\n  and lower(coalesce(n.details -> 'attrs' ->> 'spelling', ''))")
                    .append(" like :negAdjFastSpellingPattern")
                    .append(SQL_LIKE_ESCAPE_CLAUSE);
            params.put("negAdjFastSpellingPattern", toSqlLikePattern(normalizedSpelling));
        } else {
            sql.append("\n  and lower(coalesce(n.details -> 'attrs' ->> 'spelling', '')) = :negAdjFastSpelling");
            params.put("negAdjFastSpelling", unescapePatternLiterals(normalizedSpelling));
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
        if (SYNTHETIC_NEGATED_ADJECTIVE_NODE.equals(normalizedNodeName)) {
            appendNegatedAdjectiveCondition(sql, predicate, alias, predicateIndex);
            return;
        }

        ParsedNodePath nodePath = parseNodePathExpression(normalizedNodeName);
        if (nodePath != null) {
            appendNodePathPredicateCondition(sql, predicate, alias, predicateIndex, nodePath);
            return;
        }

        if (!isWildcardNode(normalizedNodeName)) {
            appendNodeTagMatchCondition(sql, alias, normalizedNodeName, "node" + predicateIndex, "nodePattern" + predicateIndex);
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

    private void appendNegatedAdjectiveCondition(
            StringBuilder sql,
            NodePredicate predicate,
            String alias,
            int predicateIndex
    ) {
        sql.append("\n    and lower(coalesce(").append(alias).append(".details ->> 'tag', '')) = '")
                .append(ATTRIBUTE_TAG)
                .append("'");

        appendKnownAttributeExists(sql, alias, List.of(ATTRIBUTE_KEY_NONOCC, ATTRIBUTE_KEY_SPELLING));
        sql.append("\n    and lower(coalesce(").append(alias)
                .append(".details -> 'attrs' ->> '").append(ATTRIBUTE_KEY_NONOCC)
                .append("', '')) = 'true'");

        String normalizedSpelling = normalizeLookupValue(predicate.getAttributeValue());
        sql.append("\n    and lower(coalesce(").append(alias)
                .append(".details -> 'attrs' ->> '").append(ATTRIBUTE_KEY_SPELLING)
                .append("', ''))");
        if (isPatternLikeValue(normalizedSpelling)) {
            sql.append(" like :negAdjSpellingPattern").append(predicateIndex).append(SQL_LIKE_ESCAPE_CLAUSE);
        } else {
            sql.append(" = :negAdjSpelling").append(predicateIndex);
        }
    }

    private void putNodePredicateParams(Map<String, Object> params, NodePredicate predicate, int predicateIndex) {
        String normalizedNodeName = normalizeLookupValue(predicate.getNodeName());
        if (SYNTHETIC_NEGATED_ADJECTIVE_NODE.equals(normalizedNodeName)) {
            String normalizedSpelling = normalizeLookupValue(predicate.getAttributeValue());
            if (isPatternLikeValue(normalizedSpelling)) {
                params.put("negAdjSpellingPattern" + predicateIndex, toSqlLikePattern(normalizedSpelling));
            } else {
                params.put("negAdjSpelling" + predicateIndex, unescapePatternLiterals(normalizedSpelling));
            }
            return;
        }

        ParsedNodePath nodePath = parseNodePathExpression(normalizedNodeName);
        if (nodePath != null) {
            for (int i = 0; i < nodePath.segments().size(); i++) {
                String segment = nodePath.segments().get(i);
                putNodeTagMatchParams(
                        params,
                        segment,
                        "pathNode" + predicateIndex + "_" + i,
                        "pathNodePattern" + predicateIndex + "_" + i
                );
            }
        } else if (!isWildcardNode(normalizedNodeName)) {
            putNodeTagMatchParams(params, normalizedNodeName, "node" + predicateIndex, "nodePattern" + predicateIndex);
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

    private void appendNodePathPredicateCondition(
            StringBuilder sql,
            NodePredicate predicate,
            String alias,
            int predicateIndex,
            ParsedNodePath nodePath
    ) {
        if (nodePath == null || nodePath.segments().isEmpty()) {
            return;
        }

        String firstSegment = normalizeLookupValue(nodePath.segments().get(0));
        if (!isWildcardNode(firstSegment)) {
            appendNodeTagMatchCondition(
                    sql,
                    alias,
                    firstSegment,
                    "pathNode" + predicateIndex + "_0",
                    "pathNodePattern" + predicateIndex + "_0"
            );
        }

        String currentAlias = alias;
        for (int i = 0; i < nodePath.steps().size(); i++) {
            NodePathStep step = nodePath.steps().get(i);
            String nextAlias = alias + "_path" + predicateIndex + "_" + (i + 1);
            String nextSegment = normalizeLookupValue(nodePath.segments().get(i + 1));

            sql.append("\n    and exists (")
                    .append("\n        select 1")
                    .append("\n        from item_node ").append(nextAlias)
                    .append("\n        where ").append(nextAlias).append(".item_id = ").append(currentAlias).append(".item_id")
                    .append("\n          and ").append(nextAlias).append(".node_path like ").append(currentAlias).append(".node_path || '/%'");

            if (step.kind() == NodePathStepKind.DIRECT_CHILD) {
                sql.append("\n          and ")
                        .append(nodeDepthExpression(nextAlias))
                        .append(" - ")
                        .append(nodeDepthExpression(currentAlias))
                        .append(" = 1");
            } else if (step.kind() == NodePathStepKind.EXACT_DEPTH) {
                sql.append("\n          and ")
                        .append(nodeDepthExpression(nextAlias))
                        .append(" - ")
                        .append(nodeDepthExpression(currentAlias))
                        .append(" = ")
                        .append(step.depth());
            }

            if (!isWildcardNode(nextSegment)) {
                appendNodeTagMatchCondition(
                        sql,
                        nextAlias,
                        nextSegment,
                        "pathNode" + predicateIndex + "_" + (i + 1),
                        "pathNodePattern" + predicateIndex + "_" + (i + 1)
                );
            }

            if (i == nodePath.steps().size() - 1
                    && predicate.getAttributeName() != null
                    && !predicate.getAttributeName().isBlank()) {
                appendNodeAttributeCondition(sql, predicate, nextAlias, predicateIndex);
            }

            currentAlias = nextAlias;
        }

        for (int i = 0; i < nodePath.steps().size(); i++) {
            sql.append("\n    )");
        }
    }

    private String nodeDepthExpression(String alias) {
        return "(length(" + alias + ".node_path) - length(replace(" + alias + ".node_path, '/', '')))";
    }

    private void appendNodeTagMatchCondition(
            StringBuilder sql,
            String alias,
            String normalizedNodeName,
            String exactParamName,
            String patternParamName
    ) {
        sql.append("\n    and lower(coalesce(").append(alias).append(".details ->> 'tag', ''))");
        if (isPatternLikeValue(normalizedNodeName)) {
            sql.append(" like :").append(patternParamName).append(SQL_LIKE_ESCAPE_CLAUSE);
        } else {
            sql.append(" = :").append(exactParamName);
        }
    }

    private void putNodeTagMatchParams(
            Map<String, Object> params,
            String normalizedNodeName,
            String exactParamName,
            String patternParamName
    ) {
        if (isWildcardNode(normalizedNodeName)) {
            return;
        }
        if (isPatternLikeValue(normalizedNodeName)) {
            params.put(patternParamName, toSqlLikePattern(normalizedNodeName));
        } else {
            params.put(exactParamName, unescapePatternLiterals(normalizedNodeName));
        }
    }

    private ParsedNodePath parseNodePathExpression(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty() || !text.contains("/")) {
            return null;
        }

        List<String> segments = new ArrayList<>();
        List<NodePathStep> steps = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int slashIndex = text.indexOf('/', index);
            if (slashIndex < 0) {
                String tail = text.substring(index).trim();
                if (tail.isEmpty()) {
                    return null;
                }
                segments.add(tail);
                break;
            }

            String segment = text.substring(index, slashIndex).trim();
            if (segment.isEmpty()) {
                return null;
            }
            segments.add(segment);

            if (slashIndex + 1 < text.length() && text.charAt(slashIndex + 1) == '/') {
                steps.add(NodePathStep.anyDepth());
                index = slashIndex + 2;
                continue;
            }

            int cursor = slashIndex + 1;
            int digitsStart = cursor;
            while (cursor < text.length() && Character.isDigit(text.charAt(cursor))) {
                cursor++;
            }

            if (cursor > digitsStart && cursor < text.length() && text.charAt(cursor) == '/') {
                int depth = Integer.parseInt(text.substring(digitsStart, cursor));
                if (depth <= 0) {
                    return null;
                }
                steps.add(NodePathStep.exactDepth(depth));
                index = cursor + 1;
                continue;
            }

            steps.add(NodePathStep.directChild());
            index = slashIndex + 1;
        }

        if (segments.size() < 2 || steps.size() != segments.size() - 1) {
            return null;
        }
        return new ParsedNodePath(List.copyOf(segments), List.copyOf(steps));
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

    private boolean matchesNumericThreshold(
            String rawText,
            CardinalityComparator comparator,
            BigInteger threshold,
            BigInteger cap
    ) {
        if (rawText == null || rawText.isBlank() || comparator == null || threshold == null || cap == null) {
            return false;
        }

        if (looksLikeXml(rawText)) {
            List<NumericToken> xmlTokens = tokenizeNumericFromXmlAttributes(rawText);
            if (xmlTokens.isEmpty()) {
                return false;
            }
            return matchesNumericThresholdStructured(xmlTokens, comparator, threshold, cap);
        }

        List<NumericToken> tokens = tokenizeNumeric(rawText);
        if (tokens.isEmpty()) {
            return false;
        }

        for (int i = 0; i < tokens.size(); i++) {
            NumericToken token = tokens.get(i);
            if (token.type() != NumericTokenType.NUMBER && token.type() != NumericTokenType.LPAREN) {
                continue;
            }
            NumericParseResult parsed = parseNumericExpression(tokens, i, cap);
            if (!parsed.success()) {
                continue;
            }
            if (compareNumericValue(parsed.value(), threshold, comparator)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesNumericThresholdStructured(
            List<NumericToken> tokens,
            CardinalityComparator comparator,
            BigInteger threshold,
            BigInteger cap
    ) {
        if (tokens == null || tokens.isEmpty()) {
            return false;
        }
        for (int i = 0; i < tokens.size(); i++) {
            NumericToken token = tokens.get(i);
            if (token.type() != NumericTokenType.NUMBER
                    && token.type() != NumericTokenType.PLUS
                    && token.type() != NumericTokenType.MUL
                    && token.type() != NumericTokenType.POW) {
                continue;
            }
            NumericParseResult parsed = parsePrefixNumericExpression(tokens, i, cap);
            if (!parsed.success()) {
                continue;
            }
            if (compareNumericValue(parsed.value(), threshold, comparator)) {
                return true;
            }
        }
        return false;
    }

    private NumericParseResult parseNumericExpression(List<NumericToken> tokens, int startIndex, BigInteger cap) {
        NumericExpressionParser parser = new NumericExpressionParser(tokens, startIndex, cap);
        return parser.parseExpression();
    }

    private List<NumericToken> tokenizeNumeric(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        List<NumericToken> tokens = new ArrayList<>();
        int i = 0;
        while (i < rawText.length()) {
            char ch = rawText.charAt(i);
            if (Character.isDigit(ch)) {
                int start = i;
                while (i < rawText.length() && Character.isDigit(rawText.charAt(i))) {
                    i++;
                }
                tokens.add(new NumericToken(NumericTokenType.NUMBER, rawText.substring(start, i)));
                continue;
            }
            if (ch == '|' && i + 1 < rawText.length() && rawText.charAt(i + 1) == '^') {
                tokens.add(new NumericToken(NumericTokenType.POW, "|^"));
                i += 2;
                continue;
            }
            switch (ch) {
                case '+' -> tokens.add(new NumericToken(NumericTokenType.PLUS, "+"));
                case '*' -> tokens.add(new NumericToken(NumericTokenType.MUL, "*"));
                case '(' -> tokens.add(new NumericToken(NumericTokenType.LPAREN, "("));
                case ')' -> tokens.add(new NumericToken(NumericTokenType.RPAREN, ")"));
                case '=' -> tokens.add(new NumericToken(NumericTokenType.EQ, "="));
                case '<' -> tokens.add(new NumericToken(NumericTokenType.LT, "<"));
                case '>' -> tokens.add(new NumericToken(NumericTokenType.GT, ">"));
                default -> {
                    i++;
                    continue;
                }
            }
            i++;
        }
        return tokens;
    }

    private List<NumericToken> tokenizeNumericFromXmlAttributes(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        List<NumericToken> tokens = new ArrayList<>();
        Matcher tagMatcher = XML_OPEN_TAG_PATTERN.matcher(rawText);
        while (tagMatcher.find()) {
            String attrs = safeToString(tagMatcher.group(2));
            Matcher numberMatcher = NUMBER_ATTRIBUTE_PATTERN.matcher(attrs);
            while (numberMatcher.find()) {
                tokens.add(new NumericToken(NumericTokenType.NUMBER, numberMatcher.group(1)));
            }

            Matcher spellingMatcher = SPELLING_ATTRIBUTE_PATTERN.matcher(attrs);
            while (spellingMatcher.find()) {
                String spelling = decodeXmlAttributeValue(spellingMatcher.group(1)).trim();
                NumericTokenType operatorType = numericOperatorTypeFromSpelling(spelling);
                if (operatorType != null) {
                    tokens.add(new NumericToken(operatorType, spelling));
                }
            }
        }
        return tokens;
    }

    private NumericTokenType numericOperatorTypeFromSpelling(String spellingRaw) {
        String spelling = safeToString(spellingRaw).trim();
        return switch (spelling) {
            case "+" -> NumericTokenType.PLUS;
            case "*" -> NumericTokenType.MUL;
            case "|^" -> NumericTokenType.POW;
            case "=" -> NumericTokenType.EQ;
            case "<" -> NumericTokenType.LT;
            case ">" -> NumericTokenType.GT;
            default -> null;
        };
    }

    private String decodeXmlAttributeValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String out = raw
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");

        Matcher hexMatcher = XML_ENTITY_NUMERIC_HEX_PATTERN.matcher(out);
        StringBuffer hexBuffer = new StringBuffer();
        while (hexMatcher.find()) {
            String replacement = numericEntityToChar(hexMatcher.group(1), 16);
            hexMatcher.appendReplacement(hexBuffer, Matcher.quoteReplacement(replacement));
        }
        hexMatcher.appendTail(hexBuffer);
        out = hexBuffer.toString();

        Matcher decMatcher = XML_ENTITY_NUMERIC_DEC_PATTERN.matcher(out);
        StringBuffer decBuffer = new StringBuffer();
        while (decMatcher.find()) {
            String replacement = numericEntityToChar(decMatcher.group(1), 10);
            decMatcher.appendReplacement(decBuffer, Matcher.quoteReplacement(replacement));
        }
        decMatcher.appendTail(decBuffer);
        return decBuffer.toString();
    }

    private String numericEntityToChar(String codeRaw, int radix) {
        if (codeRaw == null || codeRaw.isBlank()) {
            return "";
        }
        try {
            int codePoint = Integer.parseInt(codeRaw, radix);
            if (!Character.isValidCodePoint(codePoint)) {
                return "";
            }
            return new String(Character.toChars(codePoint));
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    private boolean looksLikeXml(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        int start = rawText.indexOf('<');
        int end = rawText.indexOf('>');
        return start >= 0 && end > start;
    }

    private NumericParseResult parsePrefixNumericExpression(
            List<NumericToken> tokens,
            int startIndex,
            BigInteger cap
    ) {
        if (tokens == null || startIndex < 0 || startIndex >= tokens.size()) {
            return NumericParseResult.failure(startIndex);
        }
        NumericToken token = tokens.get(startIndex);
        if (token.type() == NumericTokenType.NUMBER) {
            try {
                BigInteger value = new BigInteger(token.text());
                return new NumericParseResult(true, clampToCap(value, cap), startIndex + 1, 0);
            } catch (NumberFormatException ex) {
                return NumericParseResult.failure(startIndex);
            }
        }
        if (token.type() != NumericTokenType.PLUS
                && token.type() != NumericTokenType.MUL
                && token.type() != NumericTokenType.POW) {
            return NumericParseResult.failure(startIndex);
        }

        NumericParseResult left = parsePrefixNumericExpression(tokens, startIndex + 1, cap);
        if (!left.success()) {
            return NumericParseResult.failure(startIndex);
        }
        NumericParseResult right = parsePrefixNumericExpression(tokens, left.nextIndex(), cap);
        if (!right.success()) {
            return NumericParseResult.failure(startIndex);
        }

        BigInteger value = switch (token.type()) {
            case PLUS -> addWithCap(left.value(), right.value(), cap);
            case MUL -> multiplyWithCap(left.value(), right.value(), cap);
            case POW -> powWithCap(left.value(), right.value(), cap);
            default -> BigInteger.ZERO;
        };
        return new NumericParseResult(
                true,
                value,
                right.nextIndex(),
                left.operatorCount() + right.operatorCount() + 1
        );
    }

    private BigInteger clampToCap(BigInteger value, BigInteger cap) {
        if (value.compareTo(cap) > 0) {
            return cap;
        }
        return value;
    }

    private BigInteger addWithCap(BigInteger left, BigInteger right, BigInteger cap) {
        if (left.compareTo(cap) >= 0 || right.compareTo(cap) >= 0) {
            return cap;
        }
        BigInteger result = left.add(right);
        return clampToCap(result, cap);
    }

    private BigInteger multiplyWithCap(BigInteger left, BigInteger right, BigInteger cap) {
        if (left.signum() == 0 || right.signum() == 0) {
            return BigInteger.ZERO;
        }
        if (left.compareTo(cap) >= 0 || right.compareTo(cap) >= 0) {
            return cap;
        }
        BigInteger result = left.multiply(right);
        return clampToCap(result, cap);
    }

    private BigInteger powWithCap(BigInteger base, BigInteger exponent, BigInteger cap) {
        if (exponent.signum() < 0) {
            return BigInteger.ZERO;
        }
        if (exponent.signum() == 0) {
            return BigInteger.ONE;
        }
        if (base.signum() == 0) {
            return BigInteger.ZERO;
        }
        if (base.equals(BigInteger.ONE)) {
            return BigInteger.ONE;
        }
        if (base.compareTo(cap) >= 0) {
            return cap;
        }

        BigInteger result = BigInteger.ONE;
        BigInteger remaining = exponent;
        while (remaining.signum() > 0) {
            result = multiplyWithCap(result, base, cap);
            if (result.compareTo(cap) >= 0) {
                return cap;
            }
            remaining = remaining.subtract(BigInteger.ONE);
        }
        return result;
    }

    private boolean compareNumericValue(BigInteger value, BigInteger threshold, CardinalityComparator comparator) {
        int cmp = value.compareTo(threshold);
        return switch (comparator) {
            case EQ -> cmp == 0;
            case GE -> cmp >= 0;
            case LE -> cmp <= 0;
            case GT -> cmp > 0;
            case LT -> cmp < 0;
        };
    }

    private boolean compareCardinalityValue(int value, int threshold, CardinalityComparator comparator) {
        return switch (comparator) {
            case EQ -> value == threshold;
            case GE -> value >= threshold;
            case LE -> value <= threshold;
            case GT -> value > threshold;
            case LT -> value < threshold;
        };
    }

    private enum NumericTokenType {
        NUMBER,
        PLUS,
        MUL,
        POW,
        LPAREN,
        RPAREN,
        EQ,
        LT,
        GT
    }

    private record NumericToken(NumericTokenType type, String text) {
    }

    private record NumericParseResult(boolean success, BigInteger value, int nextIndex, int operatorCount) {
        static NumericParseResult failure(int index) {
            return new NumericParseResult(false, BigInteger.ZERO, index, 0);
        }
    }

    private final class NumericExpressionParser {
        private final List<NumericToken> tokens;
        private final BigInteger cap;
        private int index;

        private NumericExpressionParser(List<NumericToken> tokens, int startIndex, BigInteger cap) {
            this.tokens = tokens;
            this.cap = cap;
            this.index = startIndex;
        }

        private NumericParseResult parseExpression() {
            return parseAdditive();
        }

        private NumericParseResult parseAdditive() {
            int start = index;
            NumericParseResult left = parseMultiplicative();
            if (!left.success()) {
                index = start;
                return NumericParseResult.failure(start);
            }
            BigInteger value = left.value();
            int operators = left.operatorCount();

            while (index < tokens.size() && tokens.get(index).type() == NumericTokenType.PLUS) {
                index++;
                NumericParseResult right = parseMultiplicative();
                if (!right.success()) {
                    index = start;
                    return NumericParseResult.failure(start);
                }
                value = addWithCap(value, right.value(), cap);
                operators += right.operatorCount() + 1;
            }

            return new NumericParseResult(true, value, index, operators);
        }

        private NumericParseResult parseMultiplicative() {
            int start = index;
            NumericParseResult left = parsePower();
            if (!left.success()) {
                index = start;
                return NumericParseResult.failure(start);
            }
            BigInteger value = left.value();
            int operators = left.operatorCount();

            while (index < tokens.size() && tokens.get(index).type() == NumericTokenType.MUL) {
                index++;
                NumericParseResult right = parsePower();
                if (!right.success()) {
                    index = start;
                    return NumericParseResult.failure(start);
                }
                value = multiplyWithCap(value, right.value(), cap);
                operators += right.operatorCount() + 1;
            }

            return new NumericParseResult(true, value, index, operators);
        }

        private NumericParseResult parsePower() {
            int start = index;
            NumericParseResult left = parseFactor();
            if (!left.success()) {
                index = start;
                return NumericParseResult.failure(start);
            }
            BigInteger value = left.value();
            int operators = left.operatorCount();

            while (index < tokens.size() && tokens.get(index).type() == NumericTokenType.POW) {
                index++;
                NumericParseResult right = parseFactor();
                if (!right.success()) {
                    index = start;
                    return NumericParseResult.failure(start);
                }
                value = powWithCap(value, right.value(), cap);
                operators += right.operatorCount() + 1;
            }

            return new NumericParseResult(true, value, index, operators);
        }

        private NumericParseResult parseFactor() {
            if (index >= tokens.size()) {
                return NumericParseResult.failure(index);
            }
            NumericToken token = tokens.get(index);
            if (token.type() == NumericTokenType.NUMBER) {
                index++;
                try {
                    BigInteger value = new BigInteger(token.text());
                    return new NumericParseResult(true, clampToCap(value, cap), index, 0);
                } catch (NumberFormatException ex) {
                    return NumericParseResult.failure(index);
                }
            }
            if (token.type() == NumericTokenType.LPAREN) {
                int start = index;
                index++;
                NumericParseResult inner = parseAdditive();
                if (!inner.success()) {
                    index = start;
                    return NumericParseResult.failure(start);
                }
                if (index >= tokens.size() || tokens.get(index).type() != NumericTokenType.RPAREN) {
                    index = start;
                    return NumericParseResult.failure(start);
                }
                index++;
                return new NumericParseResult(true, inner.value(), index, inner.operatorCount());
            }
            return NumericParseResult.failure(index);
        }
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

    private String propositionPathExpression(String alias) {
        return "substring(lower(" + alias + ".node_path) from '" + PROPOSITION_PATH_REGEX + "')";
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

    private Map<UUID, String> loadItemRawXmlByIds(List<UUID> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        String itemIdsCsv = toUuidCsv(itemIds);
        if (itemIdsCsv.isBlank()) {
            return Map.of();
        }

        String sql = """
                with ids as (
                    select cast(unnest(string_to_array(:itemIdsCsv, ',')) as uuid) as item_id
                )
                select mi.id as item_id,
                       mi.raw_xml
                from ids
                join mml_item mi on mi.id = ids.item_id
                """;
        List<Map<String, Object>> rows = queryGenericRows(sql, Map.of("itemIdsCsv", itemIdsCsv));
        Map<UUID, String> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID itemId = asUuid(row.get("item_id"));
            String rawXml = safeToString(row.get("raw_xml"));
            if (itemId != null && !rawXml.isBlank()) {
                out.put(itemId, rawXml);
            }
        }
        return out;
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

    private record ParsedNodePath(List<String> segments, List<NodePathStep> steps) {
    }

    private record NodePathStep(NodePathStepKind kind, int depth) {
        private static NodePathStep directChild() {
            return new NodePathStep(NodePathStepKind.DIRECT_CHILD, 1);
        }

        private static NodePathStep anyDepth() {
            return new NodePathStep(NodePathStepKind.ANY_DEPTH, -1);
        }

        private static NodePathStep exactDepth(int depth) {
            return new NodePathStep(NodePathStepKind.EXACT_DEPTH, depth);
        }
    }

    private enum NodePathStepKind {
        DIRECT_CHILD,
        ANY_DEPTH,
        EXACT_DEPTH
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
