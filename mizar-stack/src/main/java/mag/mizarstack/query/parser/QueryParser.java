package mag.mizarstack.query.parser;

import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.query.MmlQueryLexer;
import mag.mizarstack.query.MmlQueryParser;
import mag.mizarstack.query.ast.QueryNode;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.springframework.stereotype.Service;

/**
 * Parser service for parsing query strings into AST.
 */
@Slf4j
@Service
public class QueryParser {

    private final AstBuilder astBuilder = new AstBuilder();

    public QueryNode parseQuery(String queryString) {
        try {
            log.debug("Parsing query: {}", queryString);

            if (queryString == null || queryString.isBlank()) {
                throw new RuntimeException("Query string is empty");
            }

            CharStream input = CharStreams.fromString(queryString);
            MmlQueryLexer lexer = new MmlQueryLexer(input);
            MmlQueryParser parser = new MmlQueryParser(new CommonTokenStream(lexer));

            BaseErrorListener syntaxErrorListener = new BaseErrorListener() {
                @Override
                public void syntaxError(
                        Recognizer<?, ?> recognizer,
                        Object offendingSymbol,
                        int line,
                        int charPositionInLine,
                        String msg,
                        RecognitionException e
                ) {
                    throw new IllegalArgumentException(
                            "Query syntax error at line " + line + ", column " + charPositionInLine + ": " + msg
                    );
                }
            };

            lexer.removeErrorListeners();
            parser.removeErrorListeners();
            lexer.addErrorListener(syntaxErrorListener);
            parser.addErrorListener(syntaxErrorListener);

            MmlQueryParser.QueryContext queryContext = parser.query();
            QueryNode ast = astBuilder.buildQuery(queryContext);
            log.debug("Successfully parsed query to AST: {}", ast.getClass().getSimpleName());

            return ast;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Unexpected error during parsing", e);
            throw new RuntimeException("Unexpected error during parsing", e);
        }
    }
}

