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
        } else if (query instanceof EnumeratedListNode) {
            return visitEnumeratedList((EnumeratedListNode) query);
        }
        return root;
    }

    public ObjectNode serializeOperation(OperationNode node) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (node instanceof BasicOperationNode) {
            return visitBasicOperation((BasicOperationNode) node);
        } else if (node instanceof FilterOperationNode) {
            return visitFilterOperation((FilterOperationNode) node);
        } else if (node instanceof GrepOperationNode) {
            return visitGrepOperation((GrepOperationNode) node);
        } else if (node instanceof NumericValueFilterOperationNode) {
            return visitNumericValueOperation((NumericValueFilterOperationNode) node);
        } else if (node instanceof NodeCardinalityFilterOperationNode) {
            return visitNodeCardinalityOperation((NodeCardinalityFilterOperationNode) node);
        } else if (node instanceof NodeSelectionOperationNode) {
            return visitNodeSelectionOperation((NodeSelectionOperationNode) node);
        } else if (node instanceof ReverseOperationNode) {
            return visitReverseOperation((ReverseOperationNode) node);
        } else if (node instanceof CardinalityFilterOperationNode) {
            return visitCardinalityOperation((CardinalityFilterOperationNode) node);
        } else if (node instanceof CompoundOperationNode) {
            return visitCompoundOperation((CompoundOperationNode) node);
        }
        return root;
    }

    private ObjectNode visitListQuery(ListQueryNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "ListQuery");
        obj.put("listType", node.getListType().toString());
        if (node.getSource() != null) {
            obj.set("source", serializeQuerySource(node.getSource()));
        }
        if (node.getSymbolSpellingFilter() != null && !node.getSymbolSpellingFilter().isBlank()) {
            obj.put("symbolSpellingFilter", node.getSymbolSpellingFilter());
        }
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
        obj.put("size", node.getItems() == null ? 0 : node.getItems().size());
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

    private ObjectNode visitNodeSelectionOperation(NodeSelectionOperationNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "NodeSelectionOperation");
        obj.set("target", serializeNodePredicate(node.getTarget()));
        var predicates = JsonNodeFactory.instance.arrayNode();
        if (node.getDescendantPredicates() != null) {
            for (NodePredicate predicate : node.getDescendantPredicates()) {
                predicates.add(serializeNodePredicate(predicate));
            }
        }
        obj.set("descendantPredicates", predicates);
        return obj;
    }

    private ObjectNode serializeNodePredicate(NodePredicate predicate) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        if (predicate == null) {
            return obj;
        }
        obj.put("nodeName", predicate.getNodeName());
        if (predicate.getAttributeName() != null) {
            obj.put("attributeName", predicate.getAttributeName());
        }
        if (predicate.getAttributeValue() != null) {
            obj.put("attributeValue", predicate.getAttributeValue());
        }
        if (predicate.isNegated()) {
            obj.put("negated", true);
        }
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

    private ObjectNode visitCardinalityOperation(CardinalityFilterOperationNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "CardinalityFilterOperation");
        obj.put("comparator", node.getComparator().toString());
        obj.put("operation", node.getOperationType().toString());
        obj.put("threshold", node.getThreshold());
        return obj;
    }

    private ObjectNode visitNumericValueOperation(NumericValueFilterOperationNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "NumericValueFilterOperation");
        obj.put("comparator", node.getComparator().toString());
        obj.put("threshold", node.getThreshold());
        return obj;
    }

    private ObjectNode visitNodeCardinalityOperation(NodeCardinalityFilterOperationNode node) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("type", "NodeCardinalityFilterOperation");
        obj.put("comparator", node.getComparator().toString());
        obj.put("scope", node.getScopeName());
        obj.put("nodeName", node.getNodeName());
        obj.put("threshold", node.getThreshold());
        return obj;
    }

    private ObjectNode serializeQuerySource(QuerySource source) {
        ObjectNode obj = JsonNodeFactory.instance.objectNode();
        obj.put("source", source.getSource());
        return obj;
    }
}

