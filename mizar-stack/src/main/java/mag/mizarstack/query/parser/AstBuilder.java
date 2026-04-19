package mag.mizarstack.query.parser;

import mag.mizarstack.query.MmlQueryBaseVisitor;
import mag.mizarstack.query.MmlQueryParser;
import mag.mizarstack.query.ast.*;
import org.antlr.v4.runtime.Token;
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
        if (ctx.constructorExpression() != null) {
            return visit(ctx.constructorExpression());
        }
        if (ctx.articleExpression() != null) {
            return visit(ctx.articleExpression());
        }
        throw new IllegalArgumentException("Unsupported atom expression: " + ctx.getText());
    }

    @Override
    public Object visitTheoremInfixExpression(MmlQueryParser.TheoremInfixExpressionContext ctx) {
        QuerySource source = new QuerySource(ctx.listSource() == null ? "*" : ctx.listSource().getText());
        QueryNode base = new ListQueryNode(ListType.THEOREMS, source);

        if (ctx.propositionInfixPredicate().size() != 2) {
            throw new IllegalArgumentException("Theorem infix query expects exactly two proposition predicates.");
        }

        String firstPattern = extractAbsolutePattern(ctx.propositionInfixPredicate(0));
        String secondPattern = extractAbsolutePattern(ctx.propositionInfixPredicate(1));

        if (firstPattern == null || firstPattern.isBlank()) {
            throw new IllegalArgumentException("First proposition predicate must include absolutepatternmmlid.");
        }

        String criterion = "PROP_INFIX|first=" + firstPattern + "|second=" + (secondPattern == null ? "*" : secondPattern);
        return new SelectiveQueryNode(base, criterion);
    }

    @Override
    public Object visitListExpression(MmlQueryParser.ListExpressionContext ctx) {
        ListType listType = parseListType(ctx.listType().getText());
        QuerySource source = new QuerySource(ctx.listSource() == null ? "*" : ctx.listSource().getText());
        return new ListQueryNode(listType, source);
    }

    @Override
    public Object visitConstructorExpression(MmlQueryParser.ConstructorExpressionContext ctx) {
        return new ConstructorQueryNode(
                ctx.ARTICLE_NAME().getText(),
                ctx.itemKind().getText().toLowerCase(Locale.ROOT),
                Integer.parseInt(ctx.NUMBER().getText())
        );
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
            case "constructor", "constructors" -> ListType.CONSTRUCTORS;
            case "theorem", "theorems" -> ListType.THEOREMS;
            case "definition", "definitions" -> ListType.DEFINITIONS;
            case "statement", "statements" -> ListType.STATEMENTS;
            case "registration", "registrations" -> ListType.REGISTRATIONS;
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

    private String extractAbsolutePattern(MmlQueryParser.PropositionInfixPredicateContext predicateContext) {
        if (predicateContext.stringLiteral() == null) {
            return null;
        }
        String raw = parseStringLiteral(predicateContext.stringLiteral().getText());
        return raw == null ? null : raw.toUpperCase(Locale.ROOT);
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
}
