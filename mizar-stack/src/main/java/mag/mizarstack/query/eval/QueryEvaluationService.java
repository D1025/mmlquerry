package mag.mizarstack.query.eval;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import mag.mizarstack.query.ast.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Service for evaluating query AST nodes against the database.
 */
@Slf4j
@Service
public class QueryEvaluationService {

    private final JdbcClient jdbcClient;

    public QueryEvaluationService() {
        this.jdbcClient = null;
    }

    public QueryEvaluationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public QueryResult evaluate(QueryNode query) {
        log.info("Evaluating query: {}", query.getClass().getSimpleName());
        if (query instanceof ListQueryNode) {
            return visitListQuery((ListQueryNode) query);
        } else if (query instanceof ConstructorQueryNode) {
            return visitConstructorQuery((ConstructorQueryNode) query);
        } else if (query instanceof ArticleQueryNode) {
            return visitArticleQuery((ArticleQueryNode) query);
        } else if (query instanceof GroupQueryNode) {
            return visitGroupQuery((GroupQueryNode) query);
        } else if (query instanceof CompoundQueryNode) {
            return visitCompoundQuery((CompoundQueryNode) query);
        } else if (query instanceof ContextQueryNode) {
            return visitContextQuery((ContextQueryNode) query);
        } else if (query instanceof OperationQueryNode) {
            return visitOperationQuery((OperationQueryNode) query);
        } else if (query instanceof SelectiveQueryNode) {
            return visitSelectiveQuery((SelectiveQueryNode) query);
        }
        return new QueryResult();
    }

    public QueryResult evaluateOperation(OperationNode operation) {
        if (operation instanceof BasicOperationNode) {
            return visitBasicOperation((BasicOperationNode) operation);
        } else if (operation instanceof FilterOperationNode) {
            return visitFilterOperation((FilterOperationNode) operation);
        } else if (operation instanceof GrepOperationNode) {
            return visitGrepOperation((GrepOperationNode) operation);
        } else if (operation instanceof ReverseOperationNode) {
            return visitReverseOperation((ReverseOperationNode) operation);
        } else if (operation instanceof CompoundOperationNode) {
            return visitCompoundOperation((CompoundOperationNode) operation);
        }
        return new QueryResult();
    }

    private QueryResult visitListQuery(ListQueryNode node) {
        log.debug("Evaluating list query: {}", node.getListType());
        return new QueryResult();
    }

    private QueryResult visitConstructorQuery(ConstructorQueryNode node) {
        log.debug("Evaluating constructor query: {}:{} {}",
            node.getArticleName(), node.getKind(), node.getNumber());
        return new QueryResult();
    }

    private QueryResult visitArticleQuery(ArticleQueryNode node) {
        log.debug("Evaluating article query: {}", node.getArticleName());
        return new QueryResult();
    }

    private QueryResult visitGroupQuery(GroupQueryNode node) {
        log.debug("Evaluating group query with quantifier: {}", node.getQuantifier());
        return new QueryResult();
    }

    private QueryResult visitCompoundQuery(CompoundQueryNode node) {
        log.debug("Evaluating compound query with operator: {}", node.getOperator());
        QueryResult left = evaluate(node.getLeft());
        QueryResult right = evaluate(node.getRight());

        List<Map<String, Object>> result = switch(node.getOperator()) {
            case AND -> intersect(left.getData(), right.getData());
            case OR -> union(left.getData(), right.getData());
            case NOT -> negate(left.getData());
        };

        return new QueryResult();
    }

    private QueryResult visitContextQuery(ContextQueryNode node) {
        return evaluate(node.getQuery());
    }

    private QueryResult visitOperationQuery(OperationQueryNode node) {
        QueryResult queryResult = evaluate(node.getQuery());
        return evaluateOperation(node.getOperation());
    }

    private QueryResult visitSelectiveQuery(SelectiveQueryNode node) {
        return evaluate(node.getQuery());
    }

    private QueryResult visitEnumeratedList(EnumeratedListNode node) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ConstructorItem item : node.getItems()) {
            items.add(Map.of(
                "article", item.getArticleName(),
                "kind", item.getKind(),
                "number", item.getNumber()
            ));
        }
        return new QueryResult();
    }

    private QueryResult visitBasicOperation(BasicOperationNode node) {
        return new QueryResult();
    }

    private QueryResult visitFilterOperation(FilterOperationNode node) {
        return new QueryResult();
    }

    private QueryResult visitGrepOperation(GrepOperationNode node) {
        return new QueryResult();
    }

    private QueryResult visitReverseOperation(ReverseOperationNode node) {
        return new QueryResult();
    }

    private QueryResult visitCompoundOperation(CompoundOperationNode node) {
        return new QueryResult();
    }

    private List<Map<String, Object>> intersect(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        return new ArrayList<>();
    }

    private List<Map<String, Object>> union(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        List<Map<String, Object>> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

    private List<Map<String, Object>> negate(List<Map<String, Object>> data) {
        return new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryResult {
        private List<Map<String, Object>> data = new ArrayList<>();
        private String description;
    }
}

