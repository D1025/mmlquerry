package mag.mizarstack.query.eval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.query.ast.*;
import org.springframework.stereotype.Service;

/**
 * Service for serializing query AST to JSON.
 */
@Slf4j
@Service
public class AstJsonSerializer {

    public ObjectNode serializeQuery(QueryNode query) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (query instanceof ListQueryNode) {
            return visitListQuery((ListQueryNode) query);
        } else if (query instanceof ConstructorQueryNode) {
            return visitConstructorQuery((ConstructorQueryNode) query);
        }
        return root;
    }

    public ObjectNode serializeOperation(OperationNode node) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (node instanceof BasicOperationNode) {
            return visitBasicOperation((BasicOperationNode) node);
        }
        return root;
    }

    private ObjectNode visitListQuery(ListQueryNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "ListQuery");
        obj.put("listType", node.getListType().toString());
        return obj;
    }

    private ObjectNode visitConstructorQuery(ConstructorQueryNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "ConstructorQuery");
        obj.put("article", node.getArticleName());
        obj.put("kind", node.getKind());
        obj.put("number", node.getNumber());
        return obj;
    }

    private ObjectNode visitArticleQuery(ArticleQueryNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "ArticleQuery");
        obj.put("article", node.getArticleName());
        return obj;
    }

    private ObjectNode visitGroupQuery(GroupQueryNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "GroupQuery");
        obj.put("quantifier", node.getQuantifier().toString());
        return obj;
    }

    private ObjectNode visitCompoundQuery(CompoundQueryNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "CompoundQuery");
        obj.put("operator", node.getOperator().toString());
        obj.set("left", serializeQuery(node.getLeft()));
        obj.set("right", serializeQuery(node.getRight()));
        return obj;
    }

    private ObjectNode visitContextQuery(ContextQueryNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "ContextQuery");
        obj.set("query", serializeQuery(node.getQuery()));
        return obj;
    }

    private ObjectNode visitOperationQuery(OperationQueryNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "OperationQuery");
        obj.set("query", serializeQuery(node.getQuery()));
        obj.set("operation", serializeOperation(node.getOperation()));
        return obj;
    }

    private ObjectNode visitSelectiveQuery(SelectiveQueryNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "SelectiveQuery");
        obj.put("criterion", node.getCriterion());
        obj.set("query", serializeQuery(node.getQuery()));
        return obj;
    }

    private ObjectNode visitEnumeratedList(EnumeratedListNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "EnumeratedList");
        return obj;
    }

    private ObjectNode visitBasicOperation(BasicOperationNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "BasicOperation");
        obj.put("operation", node.getOperationType().toString());
        if (node.getParameter() != null) {
            obj.put("parameter", node.getParameter());
        }
        return obj;
    }

    private ObjectNode visitFilterOperation(FilterOperationNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "FilterOperation");
        obj.put("criteria", node.getFilterCriteria());
        return obj;
    }

    private ObjectNode visitGrepOperation(GrepOperationNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "GrepOperation");
        obj.put("pattern", node.getPattern());
        return obj;
    }

    private ObjectNode visitReverseOperation(ReverseOperationNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "ReverseOperation");
        obj.put("operationType", node.getOperationType().toString());
        return obj;
    }

    private ObjectNode visitCompoundOperation(CompoundOperationNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "CompoundOperation");
        obj.put("combinator", node.getCombinator().toString());
        obj.set("left", serializeOperation(node.getLeft()));
        obj.set("right", serializeOperation(node.getRight()));
        return obj;
    }

    private ObjectNode serializeQuerySource(QuerySource source) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("source", source.getSource());
        return obj;
    }
}

