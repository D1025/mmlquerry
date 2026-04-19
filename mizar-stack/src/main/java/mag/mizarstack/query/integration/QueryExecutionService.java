package mag.mizarstack.query.integration;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.query.ast.QueryNode;
import mag.mizarstack.query.eval.QueryEvaluationService;
import mag.mizarstack.query.eval.QueryResultProjectionService;
import mag.mizarstack.query.parser.QueryParser;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QueryExecutionService {

    private final QueryParser queryParser;
    private final QueryProcessingPipeline queryProcessingPipeline;
    private final QueryResultProjectionService queryResultProjectionService;

    public QueryExecutionOutcome execute(String queryText, boolean includeItemsInResponse) {
        long totalStart = System.currentTimeMillis();

        long parseStart = System.currentTimeMillis();
        QueryNode ast = queryParser.parseQuery(queryText);
        long parseMs = System.currentTimeMillis() - parseStart;

        long executeStart = System.currentTimeMillis();
        QueryEvaluationService.QueryResult result = queryProcessingPipeline.executeQuery(ast);
        long executeMs = System.currentTimeMillis() - executeStart;

        long projectionStart = System.currentTimeMillis();
        List<Map<String, Object>> projectedItems = queryResultProjectionService.projectForTable(result.getData());
        long projectionMs = System.currentTimeMillis() - projectionStart;

        long totalMs = System.currentTimeMillis() - totalStart;
        QueryExecutionMetrics metrics = new QueryExecutionMetrics(parseMs, executeMs, projectionMs, totalMs);

        List<Map<String, Object>> responseItems = includeItemsInResponse
                ? projectedItems
                : Collections.emptyList();

        return new QueryExecutionOutcome(ast, result, responseItems, projectedItems.size(), metrics);
    }

    public record QueryExecutionOutcome(
            QueryNode ast,
            QueryEvaluationService.QueryResult rawResult,
            List<Map<String, Object>> responseItems,
            int projectedCount,
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
}
