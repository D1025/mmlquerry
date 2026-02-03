package mag.mizarstack.query.eval;

import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.query.ast.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Extended query evaluation service with special operations like occur, definition, etc.
 */
@Slf4j
@Service
public class ExtendedQueryEvaluationService {

    private final JdbcClient jdbcClient;

    public ExtendedQueryEvaluationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public QueryEvaluationService.QueryResult evaluateOccur(ConstructorQueryNode constructor) {
        return new QueryEvaluationService.QueryResult();
    }

    public QueryEvaluationService.QueryResult evaluateDefinition(ConstructorQueryNode constructor) {
        return new QueryEvaluationService.QueryResult();
    }

    public QueryEvaluationService.QueryResult evaluateNotation(ConstructorQueryNode constructor) {
        return new QueryEvaluationService.QueryResult();
    }

    public QueryEvaluationService.QueryResult evaluateRedef(ConstructorQueryNode constructor) {
        return new QueryEvaluationService.QueryResult();
    }

    public QueryEvaluationService.QueryResult evaluateOrigin(ConstructorQueryNode constructor) {
        return new QueryEvaluationService.QueryResult();
    }

    public QueryEvaluationService.QueryResult evaluateCopy(ConstructorQueryNode constructor) {
        return new QueryEvaluationService.QueryResult();
    }

    public QueryEvaluationService.QueryResult evaluateTermTypeRef(ConstructorQueryNode constructor) {
        return new QueryEvaluationService.QueryResult();
    }

    public QueryEvaluationService.QueryResult evaluateDefTypeRef(ConstructorQueryNode constructor) {
        return new QueryEvaluationService.QueryResult();
    }

    public QueryEvaluationService.QueryResult evaluateMainMode(ConstructorQueryNode registration) {
        return new QueryEvaluationService.QueryResult();
    }

    public QueryEvaluationService.QueryResult evaluateMainFunctor(ConstructorQueryNode registration) {
        return new QueryEvaluationService.QueryResult();
    }
}

