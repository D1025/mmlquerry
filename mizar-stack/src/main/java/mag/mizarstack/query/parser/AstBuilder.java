package mag.mizarstack.query.parser;

import mag.mizarstack.query.MmlQueryBaseVisitor;
import mag.mizarstack.query.MmlQueryParser;
import mag.mizarstack.query.ast.*;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public class AstBuilder extends MmlQueryBaseVisitor<Object> {

    public QueryNode buildQuery(MmlQueryParser.QueryContext queryContext) {
        return (QueryNode) visit(queryContext.expression());
    }

    @Override
    public Object visitExpression(MmlQueryParser.ExpressionContext ctx) {
        return visit(ctx.orExpression());
    }

    @Override
    public Object visitOrExpression(MmlQueryParser.OrExpressionContext ctx) {
        QueryNode current = (QueryNode) visit(ctx.andExpression(0));
        for (int i = 1; i < ctx.andExpression().size(); i++) {
            QueryNode right = (QueryNode) visit(ctx.andExpression(i));
            Token opToken = ctx.getChild(2 * i - 1).getPayload() instanceof Token t ? t : null;
            String opText = (opToken == null) ? ctx.getChild(2 * i - 1).getText() : opToken.getText();
            CompoundOperator operator = "butnot".equalsIgnoreCase(opText)
                    ? CompoundOperator.BUTNOT
                    : CompoundOperator.OR;
            current = new CompoundQueryNode(current, operator, right);
        }
        return current;
    }

    @Override
    public Object visitAndExpression(MmlQueryParser.AndExpressionContext ctx) {
        QueryNode current = (QueryNode) visit(ctx.unaryExpression(0));
        for (int i = 1; i < ctx.unaryExpression().size(); i++) {
            QueryNode right = (QueryNode) visit(ctx.unaryExpression(i));
            current = new CompoundQueryNode(current, CompoundOperator.AND, right);
        }
        return current;
    }

    @Override
    public Object visitUnaryExpression(MmlQueryParser.UnaryExpressionContext ctx) {
        if (ctx.NOT() != null) {
            QueryNode inner = (QueryNode) visit(ctx.unaryExpression());
            return new GroupQueryNode(GroupQuantifier.NONE, inner);
        }
        return visit(ctx.pipelineExpression());
    }

    @Override
    public Object visitPipelineExpression(MmlQueryParser.PipelineExpressionContext ctx) {
        QueryNode current = (QueryNode) visit(ctx.atomExpression());
        for (MmlQueryParser.OperationExpressionContext opCtx : ctx.operationExpression()) {
            OperationNode operation = (OperationNode) visit(opCtx);
            current = new OperationQueryNode(current, operation);
        }
        return current;
    }

    @Override
    public Object visitAtomExpression(MmlQueryParser.AtomExpressionContext ctx) {
        if (ctx.expression() != null) {
            return visit(ctx.expression());
        }
        if (ctx.theoremInfixExpression() != null) {
            return visit(ctx.theoremInfixExpression());
        }
        if (ctx.listExpression() != null) {
            return visit(ctx.listExpression());
        }
        if (ctx.articleExpression() != null) {
            return visit(ctx.articleExpression());
        }
        throw new IllegalArgumentException("Unsupported atom expression: " + ctx.getText());
    }

    @Override
    public Object visitTheoremInfixExpression(MmlQueryParser.TheoremInfixExpressionContext ctx) {
        ListType listType = parseListType(ctx.listType().getText());
        QuerySource source = new QuerySource(ctx.listSource() == null ? "*" : ctx.listSource().getText());
        QueryNode base = new ListQueryNode(listType, source);

        if (ctx.scopedPredicate().isEmpty()) {
            throw new IllegalArgumentException("List query with where requires at least one scoped predicate.");
        }

        List<ScopedPredicate> predicates = new ArrayList<>();
        for (MmlQueryParser.ScopedPredicateContext predicateContext : ctx.scopedPredicate()) {
            predicates.add(extractScopedPredicate(predicateContext));
        }

        String criterion = buildScopedCriterion(predicates);
        return new SelectiveQueryNode(base, criterion);
    }

    @Override
    public Object visitListExpression(MmlQueryParser.ListExpressionContext ctx) {
        ListType listType;
        if (ctx.listType() != null) {
            listType = parseListType(ctx.listType().getText());
        } else if (ctx.OCCURRENCES() != null) {
            listType = ListType.SYMBOL_OCCURRENCES;
        } else {
            throw new IllegalArgumentException("Unsupported list expression: " + ctx.getText());
        }
        QuerySource source = new QuerySource(ctx.listSource() == null ? "*" : ctx.listSource().getText());

        if (ctx.symbolWhereClause() != null) {
            if (listType != ListType.SYMBOLS) {
                throw new IllegalArgumentException(
                        "WHERE spelling is supported only for \"list of symbols\"."
                );
            }
            PredicateAttributeData spellingData = extractSpellingClause(ctx.symbolWhereClause().spellingClause());
            return new ListQueryNode(listType, source, spellingData.attributeValue());
        }

        return new ListQueryNode(listType, source);
    }

    @Override
    public Object visitArticleExpression(MmlQueryParser.ArticleExpressionContext ctx) {
        return new ArticleQueryNode(ctx.ARTICLE_NAME().getText());
    }

    @Override
    public Object visitOpRef(MmlQueryParser.OpRefContext ctx) {
        return new BasicOperationNode(BasicOperationType.REF, null);
    }

    @Override
    public Object visitOpOccur(MmlQueryParser.OpOccurContext ctx) {
        return new BasicOperationNode(BasicOperationType.OCCUR, null);
    }

    @Override
    public Object visitOpDefinition(MmlQueryParser.OpDefinitionContext ctx) {
        return new BasicOperationNode(BasicOperationType.DEFINITION, null);
    }

    @Override
    public Object visitOpNotation(MmlQueryParser.OpNotationContext ctx) {
        return new BasicOperationNode(BasicOperationType.NOTATION, null);
    }

    @Override
    public Object visitOpRedef(MmlQueryParser.OpRedefContext ctx) {
        return new BasicOperationNode(BasicOperationType.REDEF, null);
    }

    @Override
    public Object visitOpOrigin(MmlQueryParser.OpOriginContext ctx) {
        return new BasicOperationNode(BasicOperationType.ORIGIN, null);
    }

    @Override
    public Object visitOpCopy(MmlQueryParser.OpCopyContext ctx) {
        return new BasicOperationNode(BasicOperationType.COPY, null);
    }

    @Override
    public Object visitOpTermTypeRef(MmlQueryParser.OpTermTypeRefContext ctx) {
        return new BasicOperationNode(BasicOperationType.TERMTYPE_REF, null);
    }

    @Override
    public Object visitOpDefTypeRef(MmlQueryParser.OpDefTypeRefContext ctx) {
        return new BasicOperationNode(BasicOperationType.DEFTYPE_REF, null);
    }

    @Override
    public Object visitOpMainMode(MmlQueryParser.OpMainModeContext ctx) {
        return new BasicOperationNode(BasicOperationType.MAIN_MODE, null);
    }

    @Override
    public Object visitOpMainFunctor(MmlQueryParser.OpMainFunctorContext ctx) {
        return new BasicOperationNode(BasicOperationType.MAIN_FUNCTOR, null);
    }

    @Override
    public Object visitOpFilter(MmlQueryParser.OpFilterContext ctx) {
        return new FilterOperationNode(parseStringLiteral(ctx.stringLiteral().getText()));
    }

    @Override
    public Object visitOpGrep(MmlQueryParser.OpGrepContext ctx) {
        return new GrepOperationNode(parseStringLiteral(ctx.stringLiteral().getText()));
    }

    @Override
    public Object visitOpNodes(MmlQueryParser.OpNodesContext ctx) {
        NodePredicate target = extractNodeSelector(ctx.nodeSelector());
        List<NodePredicate> descendantPredicates = new ArrayList<>();
        for (MmlQueryParser.NodeWherePredicateContext predicateContext : ctx.nodeWherePredicate()) {
            descendantPredicates.add(extractNodeWherePredicate(predicateContext));
        }
        return new NodeSelectionOperationNode(target, descendantPredicates);
    }

    @Override
    public Object visitOpReverse(MmlQueryParser.OpReverseContext ctx) {
        return new ReverseOperationNode(ReverseOperationType.REVERSE);
    }

    @Override
    public Object visitOpInvert(MmlQueryParser.OpInvertContext ctx) {
        return new ReverseOperationNode(ReverseOperationType.INVERT);
    }

    @Override
    public Object visitOpCardinality(MmlQueryParser.OpCardinalityContext ctx) {
        return visit(ctx.cardinalityOperation());
    }

    @Override
    public Object visitOpWhereEq(MmlQueryParser.OpWhereEqContext ctx) {
        return buildCardinalityOperation(ctx.operationName(), ctx.NUMBER().getText(), CardinalityComparator.EQ);
    }

    @Override
    public Object visitOpWhereGe(MmlQueryParser.OpWhereGeContext ctx) {
        return buildCardinalityOperation(ctx.operationName(), ctx.NUMBER().getText(), CardinalityComparator.GE);
    }

    @Override
    public Object visitOpWhereLe(MmlQueryParser.OpWhereLeContext ctx) {
        return buildCardinalityOperation(ctx.operationName(), ctx.NUMBER().getText(), CardinalityComparator.LE);
    }

    @Override
    public Object visitOpWhereGt(MmlQueryParser.OpWhereGtContext ctx) {
        return buildCardinalityOperation(ctx.operationName(), ctx.NUMBER().getText(), CardinalityComparator.GT);
    }

    @Override
    public Object visitOpWhereLt(MmlQueryParser.OpWhereLtContext ctx) {
        return buildCardinalityOperation(ctx.operationName(), ctx.NUMBER().getText(), CardinalityComparator.LT);
    }

    private CardinalityFilterOperationNode buildCardinalityOperation(
            MmlQueryParser.OperationNameContext operationNameContext,
            String thresholdRaw,
            CardinalityComparator comparator
    ) {
        BasicOperationType operationType = parseOperationName(operationNameContext.getText());
        int threshold = Integer.parseInt(thresholdRaw);
        return new CardinalityFilterOperationNode(comparator, operationType, threshold);
    }

    private ListType parseListType(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "theorem", "theorems" -> ListType.THEOREMS;
            case "definition", "definitions" -> ListType.DEFINITIONS;
            case "statement", "statements" -> ListType.STATEMENTS;
            case "registration", "registrations" -> ListType.REGISTRATIONS;
            case "symbol", "symbols" -> ListType.SYMBOLS;
            case "all" -> ListType.ALL;
            default -> throw new IllegalArgumentException("Unsupported list type: " + text);
        };
    }

    private BasicOperationType parseOperationName(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return switch (normalized) {
            case "ref" -> BasicOperationType.REF;
            case "occur", "occurs" -> BasicOperationType.OCCUR;
            case "definition", "definitions" -> BasicOperationType.DEFINITION;
            case "notation" -> BasicOperationType.NOTATION;
            case "termtyperef" -> BasicOperationType.TERMTYPE_REF;
            case "deftyperef" -> BasicOperationType.DEFTYPE_REF;
            default -> throw new IllegalArgumentException("Unsupported operation in where* filter: " + text);
        };
    }

    private ScopedPredicate extractScopedPredicate(MmlQueryParser.ScopedPredicateContext predicateContext) {
        String scope = normalizeScopeName(predicateContext.scopeName().getText());
        if (scope.isBlank()) {
            throw new IllegalArgumentException("Scope in predicate cannot be blank.");
        }

        String nodeName = normalizeNodeName(readIdentifierText(predicateContext.nodeName().getText()));
        if (nodeName.isBlank()) {
            throw new IllegalArgumentException("Node name in scoped predicate cannot be blank.");
        }

        boolean predicateNegated = hasNegationAroundHas(predicateContext);
        PredicateAttributeData attributeData = extractPredicateAttribute(predicateContext.predicateAttribute());
        boolean negated = predicateNegated;
        if (attributeData != null && attributeData.negated()) {
            negated = !negated;
        }
        if (attributeData == null) {
            return new ScopedPredicate(scope, nodeName, null, null, negated);
        }
        return new ScopedPredicate(scope, nodeName, attributeData.attributeName(), attributeData.attributeValue(), negated);
    }

    private NodePredicate extractNodeSelector(MmlQueryParser.NodeSelectorContext selectorContext) {
        String nodeName = normalizeNodeName(readIdentifierText(selectorContext.nodeName().getText()));
        if (nodeName.isBlank()) {
            throw new IllegalArgumentException("Node selector cannot be blank.");
        }

        if (selectorContext.stringLiteral() == null) {
            return new NodePredicate(nodeName, null, null);
        }

        String attributeName = normalizeAttributeName(readIdentifierText(selectorContext.attributeName().getText()));
        if (attributeName.isBlank()) {
            throw new IllegalArgumentException("Node selector attribute name cannot be blank.");
        }
        String attributeValue = parseStringLiteral(selectorContext.stringLiteral().getText());
        return new NodePredicate(nodeName, attributeName, attributeValue);
    }

    private NodePredicate extractNodePredicate(MmlQueryParser.NodePredicateContext predicateContext) {
        String nodeName = normalizeNodeName(readIdentifierText(predicateContext.nodeName().getText()));
        if (nodeName.isBlank()) {
            throw new IllegalArgumentException("Node predicate cannot be blank.");
        }

        boolean predicateNegated = hasNegationAroundHas(predicateContext);
        PredicateAttributeData attributeData = extractPredicateAttribute(predicateContext.predicateAttribute());
        boolean negated = predicateNegated;
        if (attributeData != null && attributeData.negated()) {
            negated = !negated;
        }
        if (attributeData == null) {
            return new NodePredicate(nodeName, null, null, negated);
        }
        return new NodePredicate(nodeName, attributeData.attributeName(), attributeData.attributeValue(), negated);
    }

    private NodePredicate extractNodeWherePredicate(MmlQueryParser.NodeWherePredicateContext predicateContext) {
        boolean prefixedNegation = predicateContext.getChildCount() > 0
                && "not".equalsIgnoreCase(predicateContext.getChild(0).getText());
        if (predicateContext.nodePredicate() != null) {
            return withNegation(extractNodePredicate(predicateContext.nodePredicate()), prefixedNegation);
        }
        if (predicateContext.spellingPredicate() != null) {
            return withNegation(extractSpellingPredicate(predicateContext.spellingPredicate()), prefixedNegation);
        }
        return withNegation(extractRedefinePredicate(predicateContext.redefinePredicate()), prefixedNegation);
    }

    private NodePredicate extractSpellingPredicate(MmlQueryParser.SpellingPredicateContext predicateContext) {
        if (predicateContext == null || predicateContext.spellingClause() == null) {
            throw new IllegalArgumentException("Spelling predicate cannot be blank.");
        }
        PredicateAttributeData attributeData = extractSpellingClause(predicateContext.spellingClause());
        return new NodePredicate("*", attributeData.attributeName(), attributeData.attributeValue());
    }

    private NodePredicate withNegation(NodePredicate predicate, boolean negated) {
        if (predicate == null || !negated) {
            return predicate;
        }
        predicate.setNegated(!predicate.isNegated());
        return predicate;
    }

    private NodePredicate extractRedefinePredicate(MmlQueryParser.RedefinePredicateContext predicateContext) {
        if (predicateContext == null || predicateContext.nodeName().isEmpty()) {
            throw new IllegalArgumentException("Redefine predicate cannot be blank.");
        }

        String shorthand = normalizeAttributeName(readIdentifierText(predicateContext.nodeName(0).getText()));
        if (!"redefine".equals(shorthand) && !"redefined".equals(shorthand)) {
            throw new IllegalArgumentException(
                    "Unsupported node predicate shorthand: " + predicateContext.nodeName(0).getText()
            );
        }

        String state = "true";
        if (predicateContext.nodeName().size() > 1) {
            state = normalizeAttributeName(readIdentifierText(predicateContext.nodeName(1).getText()));
        }

        return switch (state) {
            case "true" -> new NodePredicate("redefine", "occurs", "true");
            case "false" -> new NodePredicate("redefine", "occurs", "false");
            case "both" -> new NodePredicate("redefine", null, null);
            default -> throw new IllegalArgumentException(
                    "Unsupported redefine option: " + predicateContext.nodeName(1).getText()
                            + ". Use true, false, or both."
            );
        };
    }

    private PredicateAttributeData extractPredicateAttribute(MmlQueryParser.PredicateAttributeContext context) {
        if (context == null) {
            return null;
        }
        if (context.spellingClause() != null) {
            PredicateAttributeData spellingData = extractSpellingClause(context.spellingClause());
            boolean negated = context.NOT() != null;
            return new PredicateAttributeData(spellingData.attributeName(), spellingData.attributeValue(), negated);
        }

        String attributeName = normalizeAttributeName(readIdentifierText(context.attributeName().getText()));
        if (attributeName.isBlank()) {
            throw new IllegalArgumentException("Predicate attribute name cannot be blank.");
        }
        String attributeValue = parseStringLiteral(context.stringLiteral().getText());
        return new PredicateAttributeData(attributeName, attributeValue, false);
    }

    private PredicateAttributeData extractSpellingClause(MmlQueryParser.SpellingClauseContext context) {
        if (context == null || context.predicateValue() == null) {
            throw new IllegalArgumentException("Spelling predicate value cannot be blank.");
        }
        String value = parsePredicateValue(context.predicateValue());
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Spelling predicate value cannot be blank.");
        }
        return new PredicateAttributeData("spelling", value, false);
    }

    private boolean hasNegationAroundHas(ParseTree context) {
        if (context == null) {
            return false;
        }
        for (int i = 0; i < context.getChildCount() - 1; i++) {
            String left = context.getChild(i).getText();
            String right = context.getChild(i + 1).getText();
            if (("has".equalsIgnoreCase(left) && "not".equalsIgnoreCase(right))
                    || ("not".equalsIgnoreCase(left) && "has".equalsIgnoreCase(right))) {
                return true;
            }
        }
        return false;
    }

    private String parsePredicateValue(MmlQueryParser.PredicateValueContext context) {
        if (context == null) {
            return "";
        }
        if (context.stringLiteral() != null) {
            return parseStringLiteral(context.stringLiteral().getText());
        }
        if (context.nodeName() != null) {
            return readIdentifierText(context.nodeName().getText());
        }
        return "";
    }

    private String buildScopedCriterion(List<ScopedPredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            throw new IllegalArgumentException("At least one scoped predicate is required.");
        }

        String scope = predicates.get(0).scope();
        for (ScopedPredicate predicate : predicates) {
            if (!scope.equals(predicate.scope())) {
                throw new IllegalArgumentException("All predicates in a single where clause must use the same scope.");
            }
        }

        StringBuilder sb = new StringBuilder("NODE_HAS|scope=").append(scope).append("|count=").append(predicates.size());
        for (int i = 0; i < predicates.size(); i++) {
            ScopedPredicate predicate = predicates.get(i);
            sb.append("|n").append(i).append('=').append(encodeCriterionValue(predicate.nodeName()));
            if (predicate.negated()) {
                sb.append("|neg").append(i).append("=1");
            }
            if (predicate.attributeName() != null && !predicate.attributeName().isBlank()) {
                sb.append("|a").append(i).append('=').append(encodeCriterionValue(predicate.attributeName()));
                sb.append("|v").append(i).append('=').append(encodeCriterionValue(
                        predicate.attributeValue() == null ? "" : predicate.attributeValue()
                ));
            }
        }
        return sb.toString();
    }

    private String normalizeNodeName(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        boolean containsWildcard = normalized.contains("*") || normalized.contains("_");
        if (!containsWildcard) {
            normalized = normalized.replace('_', '-');
        }
        if (!containsWildcard && !normalized.contains("-")) {
            normalized = normalized
                    .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                    .replaceAll("([a-z0-9])([A-Z])", "$1-$2");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeAttributeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeScopeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String encodeCriterionValue(String raw) {
        String value = raw == null ? "" : raw;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String readIdentifierText(String rawText) {
        if (rawText == null) {
            return "";
        }
        String trimmed = rawText.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return parseStringLiteral(trimmed);
            }
        }
        return trimmed;
    }

    private String parseStringLiteral(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            String body = text.substring(1, text.length() - 1);
            return body
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
                    .replace("\\\\", "\\");
        }
        return text;
    }

    private record ScopedPredicate(
            String scope,
            String nodeName,
            String attributeName,
            String attributeValue,
            boolean negated
    ) {
    }

    private record PredicateAttributeData(String attributeName, String attributeValue, boolean negated) {
    }
}
