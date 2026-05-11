package mag.mizarstack.query.integration;

import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.query.ast.ListQueryNode;
import mag.mizarstack.query.ast.QueryNode;
import mag.mizarstack.query.eval.QueryEvaluationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Query processing pipeline for executing queries and tracking performance.
 */
@Slf4j
@Service
public class QueryProcessingPipeline {

    private final QueryEvaluationService evaluationService;
    private final JdbcClient jdbcClient;

    public QueryProcessingPipeline(QueryEvaluationService evaluationService, JdbcClient jdbcClient) {
        this.evaluationService = evaluationService;
        this.jdbcClient = jdbcClient;
    }

    public QueryEvaluationService.QueryResult executeQuery(QueryNode query) {
        long startTime = System.currentTimeMillis();
        try {
            QueryEvaluationService.QueryResult result = evaluationService.evaluate(query);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Query executed successfully in {} ms", duration);
            return result;
        } catch (Exception e) {
            log.error("Query execution failed", e);
            throw new RuntimeException("Query execution failed", e);
        }
    }

    public QueryEvaluationService.PagedListQueryResult executePagedListQuery(
            ListQueryNode query,
            int page,
            int size,
            String sortBy,
            String sortDirection,
            String filter
    ) {
        long startTime = System.currentTimeMillis();
        try {
            QueryEvaluationService.PagedListQueryResult result = evaluationService.evaluatePagedListQuery(
                    query,
                    page,
                    size,
                    sortBy,
                    sortDirection,
                    filter
            );
            long duration = System.currentTimeMillis() - startTime;
            log.info("Paged list query executed successfully in {} ms", duration);
            return result;
        } catch (Exception e) {
            log.error("Paged list query execution failed", e);
            throw new RuntimeException("Paged list query execution failed", e);
        }
    }
}

