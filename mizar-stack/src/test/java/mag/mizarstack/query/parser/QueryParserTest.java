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
    void parsesTheoremPredicateWithSpellingShorthand() {
        QueryNode bracketNode = parser.parseQuery(
                "list of theorem where proposition has InfixTerm[spelling='Element']"
        );
        QueryNode shorthandNode = parser.parseQuery(
                "list of theorem where proposition has InfixTerm spelling 'Element'"
        );

        assertInstanceOf(SelectiveQueryNode.class, bracketNode);
        assertInstanceOf(SelectiveQueryNode.class, shorthandNode);
        SelectiveQueryNode bracket = (SelectiveQueryNode) bracketNode;
        SelectiveQueryNode shorthand = (SelectiveQueryNode) shorthandNode;
        assertEquals(bracket.getCriterion(), shorthand.getCriterion());
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
    void parsesDefinitionScopedPredicateWithNotSpellingAttribute() {
        QueryNode node = parser.parseQuery(
                "list of definition where item has Redefine[occurs='true'] and item has * not spelling 'Noetherian'"
        );

        assertInstanceOf(SelectiveQueryNode.class, node);
        SelectiveQueryNode selective = (SelectiveQueryNode) node;
        assertTrue(selective.getCriterion().startsWith("NODE_HAS|scope=item|count=2|"));
        assertTrue(selective.getCriterion().contains("|neg1=1"));
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
    void parsesNodeSelectionWithSpellingShorthandOnNodePredicate() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where has * spelling 'Noetherian'"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        assertInstanceOf(NodeSelectionOperationNode.class, operationQuery.getOperation());

        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals(1, operation.getDescendantPredicates().size());
        assertEquals("*", operation.getDescendantPredicates().get(0).getNodeName());
        assertEquals("spelling", operation.getDescendantPredicates().get(0).getAttributeName());
        assertEquals("Noetherian", operation.getDescendantPredicates().get(0).getAttributeValue());
    }

    @Test
    void parsesNodeSelectionWithPathPredicateDirectAnyAndExactDepth() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where has PatternShapedExpression/AttributePattern and has PatternShapedExpression//AttributePattern and has PatternShapedExpression/2/AttributePattern"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        assertInstanceOf(NodeSelectionOperationNode.class, operationQuery.getOperation());

        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals(3, operation.getDescendantPredicates().size());
        assertEquals(
                "pattern-shaped-expression/attribute-pattern",
                operation.getDescendantPredicates().get(0).getNodeName()
        );
        assertEquals(
                "pattern-shaped-expression//attribute-pattern",
                operation.getDescendantPredicates().get(1).getNodeName()
        );
        assertEquals(
                "pattern-shaped-expression/2/attribute-pattern",
                operation.getDescendantPredicates().get(2).getNodeName()
        );
    }

    @Test
    void parsesNodeSelectionWithHasAnyNodeNotSpellingShorthand() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where has * not spelling 'Noetherian'"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        assertInstanceOf(NodeSelectionOperationNode.class, operationQuery.getOperation());

        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals(1, operation.getDescendantPredicates().size());
        NodePredicate predicate = operation.getDescendantPredicates().get(0);
        assertEquals("*", predicate.getNodeName());
        assertEquals("spelling", predicate.getAttributeName());
        assertEquals("Noetherian", predicate.getAttributeValue());
        assertTrue(predicate.isNegated());
    }

    @Test
    void parsesNodeSelectionWithStandaloneSpellingShorthand() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where spelling 'Noetherian'"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        assertInstanceOf(NodeSelectionOperationNode.class, operationQuery.getOperation());

        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals(1, operation.getDescendantPredicates().size());
        assertEquals("*", operation.getDescendantPredicates().get(0).getNodeName());
        assertEquals("spelling", operation.getDescendantPredicates().get(0).getAttributeName());
        assertEquals("Noetherian", operation.getDescendantPredicates().get(0).getAttributeValue());
    }

    @Test
    void parsesNodeSelectionWithNotSpellingShorthand() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where not spelling 'Noetherian'"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals(1, operation.getDescendantPredicates().size());
        NodePredicate predicate = operation.getDescendantPredicates().get(0);
        assertEquals("*", predicate.getNodeName());
        assertEquals("spelling", predicate.getAttributeName());
        assertEquals("Noetherian", predicate.getAttributeValue());
        assertTrue(predicate.isNegated());
    }

    @Test
    void parsesNodeSelectionWithNotHasNodePredicate() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where not has Redefine[occurs='true']"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals(1, operation.getDescendantPredicates().size());
        NodePredicate predicate = operation.getDescendantPredicates().get(0);
        assertEquals("redefine", predicate.getNodeName());
        assertEquals("occurs", predicate.getAttributeName());
        assertEquals("true", predicate.getAttributeValue());
        assertTrue(predicate.isNegated());
    }

    @Test
    void parsesNodeSelectionWithHasNotNodePredicate() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where has not Redefine[occurs='true']"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals(1, operation.getDescendantPredicates().size());
        NodePredicate predicate = operation.getDescendantPredicates().get(0);
        assertEquals("redefine", predicate.getNodeName());
        assertEquals("occurs", predicate.getAttributeName());
        assertEquals("true", predicate.getAttributeValue());
        assertTrue(predicate.isNegated());
    }

    @Test
    void parsesScopedPredicateWithHasNotNode() {
        QueryNode node = parser.parseQuery(
                "list of theorem where proposition has not InfixTerm[spelling='Element']"
        );

        assertInstanceOf(SelectiveQueryNode.class, node);
        SelectiveQueryNode selective = (SelectiveQueryNode) node;
        assertTrue(selective.getCriterion().startsWith("NODE_HAS|scope=proposition|count=1|"));
        assertTrue(selective.getCriterion().contains("|neg0=1"));
    }

    @Test
    void parsesScopedPredicateWithNegatedAdjectiveShorthand() {
        QueryNode node = parser.parseQuery(
                "list of statement where proposition has negated adjective spelling 'empty'"
        );

        assertInstanceOf(SelectiveQueryNode.class, node);
        SelectiveQueryNode selective = (SelectiveQueryNode) node;
        assertTrue(selective.getCriterion().startsWith("NODE_HAS|scope=proposition|count=1|"));
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
    void parsesNodeSelectionWithNegatedAdjectiveShorthand() {
        QueryNode node = parser.parseQuery(
                "list of statement | nodes Proposition where has negated adjective spelling 'empty'"
        );

        assertInstanceOf(OperationQueryNode.class, node);
        OperationQueryNode operationQuery = (OperationQueryNode) node;
        assertInstanceOf(NodeSelectionOperationNode.class, operationQuery.getOperation());

        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals(1, operation.getDescendantPredicates().size());
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
        QueryNode node = parser.parseQuery("list of theorem | ref | wherege(ref,2)");
        assertInstanceOf(OperationQueryNode.class, node);

        OperationQueryNode outer = (OperationQueryNode) node;
        assertInstanceOf(CardinalityFilterOperationNode.class, outer.getOperation());
        CardinalityFilterOperationNode cardinality = (CardinalityFilterOperationNode) outer.getOperation();
        assertEquals(CardinalityComparator.GE, cardinality.getComparator());
        assertEquals(BasicOperationType.REF, cardinality.getOperationType());
        assertEquals(2, cardinality.getThreshold());
    }

    @Test
    void parsesPipelineWithNodeCardinalityFilterDefaultScope() {
        QueryNode node = parser.parseQuery("list of statement | wherege(numeralterm,3)");
        assertInstanceOf(OperationQueryNode.class, node);

        OperationQueryNode outer = (OperationQueryNode) node;
        assertInstanceOf(NodeCardinalityFilterOperationNode.class, outer.getOperation());
        NodeCardinalityFilterOperationNode cardinality =
                (NodeCardinalityFilterOperationNode) outer.getOperation();
        assertEquals(CardinalityComparator.GE, cardinality.getComparator());
        assertEquals("item", cardinality.getScopeName());
        assertEquals("numeral-term", cardinality.getNodeName());
        assertEquals(3, cardinality.getThreshold());
    }

    @Test
    void parsesPipelineWithNodeCardinalityFilterPropositionScope() {
        QueryNode node = parser.parseQuery("list of statement | wherege(proposition:numeralterm,3)");
        assertInstanceOf(OperationQueryNode.class, node);

        OperationQueryNode outer = (OperationQueryNode) node;
        assertInstanceOf(NodeCardinalityFilterOperationNode.class, outer.getOperation());
        NodeCardinalityFilterOperationNode cardinality =
                (NodeCardinalityFilterOperationNode) outer.getOperation();
        assertEquals(CardinalityComparator.GE, cardinality.getComparator());
        assertEquals("proposition", cardinality.getScopeName());
        assertEquals("numeral-term", cardinality.getNodeName());
        assertEquals(3, cardinality.getThreshold());
    }

    @Test
    void parsesPipelineWithNumericValueFilter() {
        QueryNode node = parser.parseQuery("list of statement | numgt(1000000)");
        assertInstanceOf(OperationQueryNode.class, node);

        OperationQueryNode outer = (OperationQueryNode) node;
        assertInstanceOf(NumericValueFilterOperationNode.class, outer.getOperation());
        NumericValueFilterOperationNode numeric = (NumericValueFilterOperationNode) outer.getOperation();
        assertEquals(CardinalityComparator.GT, numeric.getComparator());
        assertEquals(1_000_000L, numeric.getThreshold());
    }

    @Test
    void parsesCompoundBooleanOperators() {
        QueryNode node = parser.parseQuery("list of theorem and not list of definition");
        assertInstanceOf(CompoundQueryNode.class, node);
    }

    @Test
    void parsesUnaryNotQuery() {
        QueryNode node = parser.parseQuery("not list of definition");
        assertInstanceOf(GroupQueryNode.class, node);

        GroupQueryNode group = (GroupQueryNode) node;
        assertEquals(GroupQuantifier.NONE, group.getQuantifier());
        assertInstanceOf(ListQueryNode.class, group.getInner());
    }

    @Test
    void parsesButnotQuery() {
        QueryNode node = parser.parseQuery("list of theorem butnot list of definition");
        assertInstanceOf(CompoundQueryNode.class, node);

        CompoundQueryNode compound = (CompoundQueryNode) node;
        assertEquals(CompoundOperator.BUTNOT, compound.getOperator());
        assertInstanceOf(ListQueryNode.class, compound.getLeft());
        assertInstanceOf(ListQueryNode.class, compound.getRight());
    }

    @Test
    void parsesPartialNegationInAndQuery() {
        QueryNode node = parser.parseQuery("list of theorem and not list of definition");
        assertInstanceOf(CompoundQueryNode.class, node);

        CompoundQueryNode compound = (CompoundQueryNode) node;
        assertEquals(CompoundOperator.AND, compound.getOperator());
        assertInstanceOf(ListQueryNode.class, compound.getLeft());
        assertInstanceOf(GroupQueryNode.class, compound.getRight());

        GroupQueryNode negatedRight = (GroupQueryNode) compound.getRight();
        assertEquals(GroupQuantifier.NONE, negatedRight.getQuantifier());
        assertInstanceOf(ListQueryNode.class, negatedRight.getInner());
    }

    @Test
    void parsesNegationWithParenthesizedSubquery() {
        QueryNode node = parser.parseQuery(
                "list of theorem and not (list of definition or list of registration)"
        );
        assertInstanceOf(CompoundQueryNode.class, node);

        CompoundQueryNode compound = (CompoundQueryNode) node;
        assertEquals(CompoundOperator.AND, compound.getOperator());
        assertInstanceOf(ListQueryNode.class, compound.getLeft());
        assertInstanceOf(GroupQueryNode.class, compound.getRight());

        GroupQueryNode negated = (GroupQueryNode) compound.getRight();
        assertEquals(GroupQuantifier.NONE, negated.getQuantifier());
        assertInstanceOf(CompoundQueryNode.class, negated.getInner());
        CompoundQueryNode inner = (CompoundQueryNode) negated.getInner();
        assertEquals(CompoundOperator.OR, inner.getOperator());
    }

    @Test
    void parsesListOfSymbols() {
        QueryNode node = parser.parseQuery("list of symbols");
        assertInstanceOf(ListQueryNode.class, node);
        ListQueryNode list = (ListQueryNode) node;
        assertEquals(ListType.SYMBOLS, list.getListType());
        assertNull(list.getSymbolSpellingFilter());
    }

    @Test
    void parsesListOfSymbolsWithSpellingFilter() {
        QueryNode node = parser.parseQuery("list of symbols where spelling '+'");
        assertInstanceOf(ListQueryNode.class, node);
        ListQueryNode list = (ListQueryNode) node;
        assertEquals(ListType.SYMBOLS, list.getListType());
        assertEquals("+", list.getSymbolSpellingFilter());
    }

    @Test
    void parsesOccurrencesOfSymbols() {
        QueryNode node = parser.parseQuery("occurrences of symbols");
        assertInstanceOf(ListQueryNode.class, node);
        ListQueryNode list = (ListQueryNode) node;
        assertEquals(ListType.SYMBOL_OCCURRENCES, list.getListType());
    }

    @Test
    void parsesOccurrencesOfSymbolsWithFilterPattern() {
        QueryNode node = parser.parseQuery("occurrences of symbols | filter('spelling=ali*')");
        assertInstanceOf(OperationQueryNode.class, node);
    }

    @Test
    void parsesEscapedWildcardLiteralsInFilterPattern() {
        QueryNode node = parser.parseQuery("occurrences of symbols | filter('spelling=A\\*B\\_C')");
        assertInstanceOf(OperationQueryNode.class, node);

        OperationQueryNode operationQuery = (OperationQueryNode) node;
        assertInstanceOf(FilterOperationNode.class, operationQuery.getOperation());
        FilterOperationNode filter = (FilterOperationNode) operationQuery.getOperation();
        assertEquals("spelling=A\\*B\\_C", filter.getFilterCriteria());
    }

    @Test
    void parsesNodePatternWithWildcardInNameAndAttributeValue() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where has \"*-pattern\"[spelling='Noeth*']"
        );
        assertInstanceOf(OperationQueryNode.class, node);
    }

    @Test
    void parsesNodePatternWithEscapedWildcardLiteralsInAttributeValue() {
        QueryNode node = parser.parseQuery(
                "list of definition | nodes Item where has \"*-pattern\"[spelling='A\\*B\\_C']"
        );
        assertInstanceOf(OperationQueryNode.class, node);

        OperationQueryNode operationQuery = (OperationQueryNode) node;
        NodeSelectionOperationNode operation = (NodeSelectionOperationNode) operationQuery.getOperation();
        assertEquals("A\\*B\\_C", operation.getDescendantPredicates().get(0).getAttributeValue());
    }
}
