package mag.mizarstack.query.integration;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.query.ast.ListQueryNode;
import mag.mizarstack.query.ast.QueryNode;
import mag.mizarstack.query.eval.QueryEvaluationService;
import mag.mizarstack.query.eval.QueryResultProjectionService;
import mag.mizarstack.query.parser.QueryParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QueryExecutionService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 500;

    private final QueryParser queryParser;
    private final QueryProcessingPipeline queryProcessingPipeline;
    private final QueryResultProjectionService queryResultProjectionService;

    public QueryExecutionOutcome execute(String queryText, boolean includeItemsInResponse) {
        return execute(queryText, includeItemsInResponse, new QueryPageRequest(null, null, null, null, null));
    }

    public QueryExecutionOutcome execute(
            String queryText,
            boolean includeItemsInResponse,
            QueryPageRequest pageRequest
    ) {
        long totalStart = System.currentTimeMillis();

        int normalizedSize = normalizePageSize(pageRequest == null ? null : pageRequest.size());
        int normalizedPage = normalizePageNumber(pageRequest == null ? null : pageRequest.page());
        String normalizedSortBy = trimToNull(pageRequest == null ? null : pageRequest.sortBy());
        String normalizedSortDirection = normalizeSortDirection(pageRequest == null ? null : pageRequest.sortDirection());
        String normalizedFilter = trimToNull(pageRequest == null ? null : pageRequest.filter());

        long parseStart = System.currentTimeMillis();
        QueryNode ast = queryParser.parseQuery(queryText);
        long parseMs = System.currentTimeMillis() - parseStart;

        QueryEvaluationService.QueryResult result;
        QueryPageResult page;
        long executeMs;
        long projectionMs;

        if (ast instanceof ListQueryNode listQueryNode) {
            long executeStart = System.currentTimeMillis();
            QueryEvaluationService.PagedListQueryResult pagedListQueryResult =
                    queryProcessingPipeline.executePagedListQuery(
                            listQueryNode,
                            normalizedPage,
                            normalizedSize,
                            normalizedSortBy,
                            normalizedSortDirection,
                            normalizedFilter
                    );
            executeMs = System.currentTimeMillis() - executeStart;

            long projectionStart = System.currentTimeMillis();
            List<Map<String, Object>> projectedItems = queryResultProjectionService.projectForTable(pagedListQueryResult.data());
            projectionMs = System.currentTimeMillis() - projectionStart;

            result = new QueryEvaluationService.QueryResult(
                    pagedListQueryResult.data(),
                    pagedListQueryResult.description()
            );
            page = new QueryPageResult(
                    projectedItems,
                    pagedListQueryResult.totalCount(),
                    pagedListQueryResult.page(),
                    pagedListQueryResult.size(),
                    pagedListQueryResult.sortBy(),
                    pagedListQueryResult.sortDirection(),
                    pagedListQueryResult.filter()
            );
        } else {
            long executeStart = System.currentTimeMillis();
            result = queryProcessingPipeline.executeQuery(ast);
            executeMs = System.currentTimeMillis() - executeStart;

            long projectionStart = System.currentTimeMillis();
            List<Map<String, Object>> projectedItems = queryResultProjectionService.projectForTable(result.getData());
            page = applyPagingAndSorting(
                    projectedItems,
                    new QueryPageRequest(
                            normalizedPage,
                            normalizedSize,
                            normalizedSortBy,
                            normalizedSortDirection,
                            normalizedFilter
                    )
            );
            projectionMs = System.currentTimeMillis() - projectionStart;
        }

        long totalMs = System.currentTimeMillis() - totalStart;
        QueryExecutionMetrics metrics = new QueryExecutionMetrics(parseMs, executeMs, projectionMs, totalMs);

        List<Map<String, Object>> responseItems = includeItemsInResponse
                ? page.items()
                : Collections.emptyList();

        return new QueryExecutionOutcome(
                ast,
                result,
                responseItems,
                page.totalCount(),
                page.page(),
                page.size(),
                page.sortBy(),
                page.sortDirection(),
                page.filter(),
                metrics
        );
    }

    private QueryPageResult applyPagingAndSorting(List<Map<String, Object>> rows, QueryPageRequest request) {
        List<Map<String, Object>> source = rows == null ? List.of() : rows;

        String filter = request == null ? null : trimToNull(request.filter());
        String sortBy = request == null ? null : trimToNull(request.sortBy());
        String sortDirection = normalizeSortDirection(request == null ? null : request.sortDirection());
        int size = normalizePageSize(request == null ? null : request.size());
        int requestedPage = normalizePageNumber(request == null ? null : request.page());

        List<Map<String, Object>> filtered = source;
        if (filter != null) {
            String needle = filter.toLowerCase(Locale.ROOT);
            filtered = source.stream()
                    .filter(row -> row != null && row.values().stream()
                            .map(this::safeString)
                            .map(value -> value.toLowerCase(Locale.ROOT))
                            .anyMatch(value -> value.contains(needle)))
                    .toList();
        }

        List<Map<String, Object>> sorted = new ArrayList<>(filtered);
        if (sortBy != null) {
            sorted.sort((left, right) -> compareRows(left, right, sortBy, sortDirection));
        }

        int totalCount = sorted.size();
        int maxPage = totalCount == 0 ? 0 : (totalCount - 1) / size;
        int page = Math.min(requestedPage, maxPage);
        int from = Math.min(page * size, totalCount);
        int to = Math.min(from + size, totalCount);
        List<Map<String, Object>> items = (from >= to) ? List.of() : new ArrayList<>(sorted.subList(from, to));

        return new QueryPageResult(items, totalCount, page, size, sortBy, sortDirection, filter);
    }

    private int compareRows(
            Map<String, Object> left,
            Map<String, Object> right,
            String sortBy,
            String sortDirection
    ) {
        Object leftValue = left == null ? null : left.get(sortBy);
        Object rightValue = right == null ? null : right.get(sortBy);
        int compared = compareValues(leftValue, rightValue);
        return "desc".equals(sortDirection) ? -compared : compared;
    }

    private int compareValues(Object leftValue, Object rightValue) {
        if (leftValue == null && rightValue == null) {
            return 0;
        }
        if (leftValue == null) {
            return 1;
        }
        if (rightValue == null) {
            return -1;
        }

        Double leftNumber = parseNumber(leftValue);
        Double rightNumber = parseNumber(rightValue);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber);
        }

        return String.CASE_INSENSITIVE_ORDER.compare(safeString(leftValue), safeString(rightValue));
    }

    private Double parseNumber(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private int normalizePageSize(Integer rawSize) {
        if (rawSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        int clamped = Math.max(1, rawSize);
        return Math.min(clamped, MAX_PAGE_SIZE);
    }

    private int normalizePageNumber(Integer rawPage) {
        if (rawPage == null || rawPage < 0) {
            return 0;
        }
        return rawPage;
    }

    private String normalizeSortDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return "asc";
        }
        return "desc".equalsIgnoreCase(raw.trim()) ? "desc" : "asc";
    }

    private String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    public record QueryExecutionOutcome(
            QueryNode ast,
            QueryEvaluationService.QueryResult rawResult,
            List<Map<String, Object>> responseItems,
            int totalCount,
            int page,
            int size,
            String sortBy,
            String sortDirection,
            String filter,
            QueryExecutionMetrics metrics
    ) {
    }

    public record QueryExecutionMetrics(
            long parseMs,
            long executeMs,
            long projectionMs,
            long totalMs
    ) {
    }

    public record QueryPageRequest(
            Integer page,
            Integer size,
            String sortBy,
            String sortDirection,
            String filter
    ) {
    }

    private record QueryPageResult(
            List<Map<String, Object>> items,
            int totalCount,
            int page,
            int size,
            String sortBy,
            String sortDirection,
            String filter
    ) {
    }
}
