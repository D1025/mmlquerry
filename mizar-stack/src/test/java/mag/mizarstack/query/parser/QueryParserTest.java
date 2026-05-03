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
    void parsesNodeSelectionPipelineQuery() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item[kind='Attribute-Definition'] where has Redefine[occurs='true'] and has AttributePattern[spelling='Noetherian']"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        assertInstanceOf(ListQueryNode.class, operationQuery.getQuery());
        assertInstanceOf(NodeSelectionOperationNode.class, operationQuery.getOperation());

        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals("item", operation.getTarget().getNodeName());
        assertEquals("kind", operation.getTarget().getAttributeName());
        assertEquals("Attribute-Definition", operation.getTarget().getAttributeValue());
        assertEquals(2, operation.getDescendantPredicates().size());
    }

    @Test
    void parsesNodeSelectionWithUnknownTargetKindAndWildcardAttributePredicate() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where has Redefine[occurs='true'] and has *[spelling='Noetherian']"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        assertInstanceOf(NodeSelectionOperationNode.class, operationQuery.getOperation());

        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals("item", operation.getTarget().getNodeName());
        assertEquals(2, operation.getDescendantPredicates().size());
        assertEquals("*", operation.getDescendantPredicates().get(1).getNodeName());
        assertEquals("spelling", operation.getDescendantPredicates().get(1).getAttributeName());
        assertEquals("Noetherian", operation.getDescendantPredicates().get(1).getAttributeValue());
    }

    @Test
    void parsesNodeSelectionWithRedefineTrueShorthand() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where redefine true and has *[spelling='Noetherian']"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        assertInstanceOf(NodeSelectionOperationNode.class, operationQuery.getOperation());

        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals(2, operation.getDescendantPredicates().size());
        assertEquals("redefine", operation.getDescendantPredicates().get(0).getNodeName());
        assertEquals("occurs", operation.getDescendantPredicates().get(0).getAttributeName());
        assertEquals("true", operation.getDescendantPredicates().get(0).getAttributeValue());
    }

    @Test
    void parsesNodeSelectionWithRedefinedAndRedefineBothShorthands() {
        QueryNode redefinedNode = parser.parseQuery("list of definition | nodes Item where redefined");
        QueryNode bothNode = parser.parseQuery("list of definition | nodes Item where redefine both");

        OperationQueryNode redefinedQuery = (OperationQueryNode) redefinedNode;
        NodeSelectionOperationNode redefinedOperation =
                (NodeSelectionOperationNode) redefinedQuery.getOperation();
        assertEquals("redefine", redefinedOperation.getDescendantPredicates().get(0).getNodeName());
        assertEquals("occurs", redefinedOperation.getDescendantPredicates().get(0).getAttributeName());
        assertEquals("true", redefinedOperation.getDescendantPredicates().get(0).getAttributeValue());

        OperationQueryNode bothQuery = (OperationQueryNode) bothNode;
        NodeSelectionOperationNode bothOperation = (NodeSelectionOperationNode) bothQuery.getOperation();
        assertEquals("redefine", bothOperation.getDescendantPredicates().get(0).getNodeName());
        assertNull(bothOperation.getDescendantPredicates().get(0).getAttributeName());
        assertNull(bothOperation.getDescendantPredicates().get(0).getAttributeValue());
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
