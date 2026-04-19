package mag.mizarstack.query.parser;

import mag.mizarstack.query.ast.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryParserTest {

    private final QueryParser parser = new QueryParser();

    @Test
    void parsesTheoremInfixPredicateQuery() {
        QueryNode node = parser.parseQuery(
                "list of theorem where proposition has infix-term[absolutepatternmmlid='RELAT_1:3'] and proposition has infix-term"
        );

        assertInstanceOf(SelectiveQueryNode.class, node);
        SelectiveQueryNode selective = (SelectiveQueryNode) node;
        assertInstanceOf(ListQueryNode.class, selective.getQuery());
        assertTrue(selective.getCriterion().startsWith("PROP_INFIX|first=RELAT_1:3|second=*"));
    }

    @Test
    void parsesPipelineWithCardinalityFilter() {
        QueryNode node = parser.parseQuery("ABCMIZ_0:func 1 | ref | wherege(ref,2)");
        assertInstanceOf(OperationQueryNode.class, node);

        OperationQueryNode outer = (OperationQueryNode) node;
        assertInstanceOf(CardinalityFilterOperationNode.class, outer.getOperation());
        CardinalityFilterOperationNode cardinality = (CardinalityFilterOperationNode) outer.getOperation();
        assertEquals(CardinalityComparator.GE, cardinality.getComparator());
        assertEquals(BasicOperationType.REF, cardinality.getOperationType());
        assertEquals(2, cardinality.getThreshold());
    }

    @Test
    void parsesCompoundBooleanOperators() {
        QueryNode node = parser.parseQuery("list of theorem and not list of definition");
        assertInstanceOf(CompoundQueryNode.class, node);
    }
}

