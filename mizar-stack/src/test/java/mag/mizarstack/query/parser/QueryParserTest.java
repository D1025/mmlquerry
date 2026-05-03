package mag.mizarstack.query.parser;

import mag.mizarstack.query.ast.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryParserTest {

    private final QueryParser parser = new QueryParser();

    @Test
    void parsesTheoremInfixPredicateQuery() {
        QueryNode node = parser.parseQuery(
                "list of theorem where proposition has InfixTerm[absolutepatternmmlid='RELAT_1:3'] and proposition has InfixTerm"
        );

        assertInstanceOf(SelectiveQueryNode.class, node);
        SelectiveQueryNode selective = (SelectiveQueryNode) node;
        assertInstanceOf(ListQueryNode.class, selective.getQuery());
        assertTrue(selective.getCriterion().startsWith("NODE_HAS|scope=proposition|count=2|"));
    }

    @Test
    void parsesTheoremPredicateWithAnyNodeAndSpelling() {
        QueryNode node = parser.parseQuery(
                "list of theorem where proposition has InfixTerm[spelling='Element']"
        );

        assertInstanceOf(SelectiveQueryNode.class, node);
        SelectiveQueryNode selective = (SelectiveQueryNode) node;
        assertInstanceOf(ListQueryNode.class, selective.getQuery());
        assertTrue(selective.getCriterion().startsWith("NODE_HAS|scope=proposition|count=1|"));
    }

    @Test
    void parsesThesisNodePredicate() {
        QueryNode node = parser.parseQuery(
                "list of theorem where proposition has Thesis"
        );

        assertInstanceOf(SelectiveQueryNode.class, node);
        SelectiveQueryNode selective = (SelectiveQueryNode) node;
        assertInstanceOf(ListQueryNode.class, selective.getQuery());
        assertTrue(selective.getCriterion().startsWith("NODE_HAS|scope=proposition|count=1|"));
    }

    @Test
    void parsesQuotedNodeAndAttributeNames() {
        QueryNode node = parser.parseQuery(
                "list of theorem where proposition has \"Definition\"[\"spelling\"='Element']"
        );

        assertInstanceOf(SelectiveQueryNode.class, node);
        SelectiveQueryNode selective = (SelectiveQueryNode) node;
        assertTrue(selective.getCriterion().startsWith("NODE_HAS|scope=proposition|count=1|"));
    }

    @Test
    void parsesDefinitionRedefineAndPatternSpellingQuery() {
        QueryNode node = parser.parseQuery(
                "list of definition where item has Redefine[occurs='true'] and item has AttributePattern[spelling='Noetherian']"
        );

        assertInstanceOf(SelectiveQueryNode.class, node);
        SelectiveQueryNode selective = (SelectiveQueryNode) node;
        assertInstanceOf(ListQueryNode.class, selective.getQuery());
        assertTrue(selective.getCriterion().startsWith("NODE_HAS|scope=item|count=2|"));
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
