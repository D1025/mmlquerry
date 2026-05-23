grammar MmlQuery;

query
    : expression EOF
    ;

expression
    : orExpression
    ;

orExpression
    : andExpression ((OR | BUTNOT) andExpression)*
    ;

andExpression
    : unaryExpression (AND unaryExpression)*
    ;

unaryExpression
    : NOT unaryExpression
    | pipelineExpression
    ;

pipelineExpression
    : atomExpression (PIPE operationExpression)*
    ;

atomExpression
    : LPAREN expression RPAREN
    | theoremInfixExpression
    | listExpression
    | articleExpression
    ;

theoremInfixExpression
    : LIST OF listType (IN listSource)? WHERE scopedPredicate (AND scopedPredicate)*
    ;

scopedPredicate
    : scopeName HAS NOT? nodeName predicateAttribute?
    | scopeName NOT HAS nodeName predicateAttribute?
    | scopeName HAS NOT? negatedAdjectiveClause
    | scopeName NOT HAS negatedAdjectiveClause
    ;

scopeName
    : PROPOSITION
    | ITEM
    ;

nodeName
    : simpleNodeName (pathConnector simpleNodeName)+
    | simpleNodeName
    | stringLiteral
    ;

simpleNodeName
    : NODE_NAME
    | ARTICLE_NAME
    | ITEM
    | PROPOSITION
    | SPELLING
    | STAR
    ;

pathConnector
    : SLASH
    | DOUBLE_SLASH
    | SLASH NUMBER SLASH
    ;

attributeName
    : NODE_NAME
    | ARTICLE_NAME
    | SPELLING
    | OCCUR
    | stringLiteral
    ;

predicateAttribute
    : LBRACK attributeName EQ stringLiteral RBRACK
    | spellingClause
    | NOT spellingClause
    ;

spellingClause
    : SPELLING EQ? predicateValue
    ;

predicateValue
    : stringLiteral
    | nodeName
    ;

listExpression
    : LIST OF listType (IN listSource)? symbolWhereClause?
    | OCCURRENCES OF SYMBOL (IN listSource)?
    ;

symbolWhereClause
    : WHERE spellingClause
    ;

listType
    : THEOREM
    | DEFINITION
    | STATEMENT
    | REGISTRATION
    | SYMBOL
    | ALL
    ;

listSource
    : ARTICLE_NAME
    | STAR
    ;

articleExpression
    : ARTICLE ARTICLE_NAME
    ;

operationExpression
    : REF                                         # opRef
    | OCCUR                                       # opOccur
    | DEFINITION                                  # opDefinition
    | NOTATION                                    # opNotation
    | REDEF                                       # opRedef
    | ORIGIN                                      # opOrigin
    | COPY                                        # opCopy
    | TERMTYPE REF                                # opTermTypeRef
    | DEFTYPE REF                                 # opDefTypeRef
    | MAIN MODE                                   # opMainMode
    | MAIN FUNCTOR                                # opMainFunctor
    | FILTER LPAREN stringLiteral RPAREN          # opFilter
    | GREP LPAREN stringLiteral RPAREN            # opGrep
    | NODES nodeSelector (WHERE nodeWherePredicate (AND nodeWherePredicate)*)? # opNodes
    | REVERSE                                     # opReverse
    | INVERT                                      # opInvert
    | numericValueOperation                       # opNumericValue
    | cardinalityOperation                        # opCardinality
    ;

nodeSelector
    : nodeName (LBRACK attributeName EQ stringLiteral RBRACK)?
    ;

nodePredicate
    : HAS NOT? nodeName predicateAttribute?
    | NOT HAS nodeName predicateAttribute?
    | HAS NOT? negatedAdjectiveClause
    | NOT HAS negatedAdjectiveClause
    ;

negatedAdjectiveClause
    : NEGATED ADJECTIVE spellingClause
    ;

nodeWherePredicate
    : NOT nodePredicate
    | NOT spellingPredicate
    | NOT redefinePredicate
    | nodePredicate
    | spellingPredicate
    | redefinePredicate
    ;

redefinePredicate
    : nodeName nodeName?
    ;

spellingPredicate
    : spellingClause
    ;

numericValueOperation
    : NUMEQ LPAREN NUMBER RPAREN   # opNumEq
    | NUMGE LPAREN NUMBER RPAREN   # opNumGe
    | NUMLE LPAREN NUMBER RPAREN   # opNumLe
    | NUMGT LPAREN NUMBER RPAREN   # opNumGt
    | NUMLT LPAREN NUMBER RPAREN   # opNumLt
    | NUMBER_KW numericComparator NUMBER #opNumberComparator
    ;

numericComparator
    : EQ
    | GTE
    | LTE
    | GT
    | LT
    ;

cardinalityOperation
    : WHEREEQ LPAREN operationName COMMA NUMBER RPAREN   # opWhereEq
    | WHEREGE LPAREN operationName COMMA NUMBER RPAREN   # opWhereGe
    | WHERELE LPAREN operationName COMMA NUMBER RPAREN   # opWhereLe
    | WHEREGT LPAREN operationName COMMA NUMBER RPAREN   # opWhereGt
    | WHERELT LPAREN operationName COMMA NUMBER RPAREN   # opWhereLt
    | WHEREEQ LPAREN nodeCardinalityTarget COMMA NUMBER RPAREN # opWhereNodeEq
    | WHEREGE LPAREN nodeCardinalityTarget COMMA NUMBER RPAREN # opWhereNodeGe
    | WHERELE LPAREN nodeCardinalityTarget COMMA NUMBER RPAREN # opWhereNodeLe
    | WHEREGT LPAREN nodeCardinalityTarget COMMA NUMBER RPAREN # opWhereNodeGt
    | WHERELT LPAREN nodeCardinalityTarget COMMA NUMBER RPAREN # opWhereNodeLt
    ;

operationName
    : REF
    | OCCUR
    | DEFINITION
    | NOTATION
    | TERMTYPE REF
    | DEFTYPE REF
    ;

nodeCardinalityTarget
    : (scopeName COLON)? nodeName
    ;

stringLiteral
    : STRING
    ;

LIST: L I S T;
OF: O F;
IN: I N;
WHERE: W H E R E;
ARTICLE: A R T I C L E;
PROPOSITION: P R O P O S I T I O N;
ITEM: I T E M;
HAS: H A S;
AND: A N D;
OR: O R;
BUTNOT: B U T N O T;
NOT: N O T;
PIPE: '|';
DOUBLE_SLASH: '//';
SLASH: '/';
STAR: '*';
COMMA: ',';
COLON: ':';
LPAREN: '(';
RPAREN: ')';
LBRACK: '[';
RBRACK: ']';
EQ: '=';
GTE: '>=';
LTE: '<=';
GT: '>';
LT: '<';

THEOREM: T H E O R E M S?;
DEFINITION: D E F I N I T I O N S?;
STATEMENT: S T A T E M E N T S?;
REGISTRATION: R E G I S T R A T I O N S?;
SYMBOL: S Y M B O L S?;
ALL: A L L;

REF: R E F;
OCCUR: O C C U R S?;
NOTATION: N O T A T I O N;
REDEF: R E D E F | R E D E F I N I T I O N;
ORIGIN: O R I G I N | O R I G I N A L;
COPY: C O P Y | C O P I E D;
OCCURRENCES: O C C U R R E N C E S;
TERMTYPE: T E R M T Y P E;
DEFTYPE: D E F T Y P E;
MAIN: M A I N;
MODE: M O D E;
FUNCTOR: F U N C T O R;
FILTER: F I L T E R;
GREP: G R E P;
NODES: N O D E S?;
SPELLING: S P E L L I N G;
NEGATED: N E G A T E D;
ADJECTIVE: A D J E C T I V E;
REVERSE: R E V E R S E;
INVERT: I N V E R T;
NUMEQ: N U M E Q;
NUMGE: N U M G E;
NUMLE: N U M L E;
NUMGT: N U M G T;
NUMLT: N U M L T;
WHEREEQ: W H E R E E Q;
WHEREGE: W H E R E G E;
WHERELE: W H E R E L E;
WHEREGT: W H E R E G T;
WHERELT: W H E R E L T;
NUMBER_KW: N U M B E R;

ARTICLE_NAME: [A-Z] [A-Z0-9_]*;
NODE_NAME: [a-zA-Z] [a-zA-Z0-9_]* ('-' [a-zA-Z0-9_]+)*;
NUMBER: [0-9]+;
STRING
    : '\'' (~['\\] | '\\' .)* '\''
    | '"' (~["\\] | '\\' .)* '"'
    ;

WS: [ \t\r\n]+ -> skip;

fragment A: [aA];
fragment B: [bB];
fragment C: [cC];
fragment D: [dD];
fragment E: [eE];
fragment F: [fF];
fragment G: [gG];
fragment H: [hH];
fragment I: [iI];
fragment J: [jJ];
fragment K: [kK];
fragment L: [lL];
fragment M: [mM];
fragment N: [nN];
fragment O: [oO];
fragment P: [pP];
fragment Q: [qQ];
fragment R: [rR];
fragment S: [sS];
fragment T: [tT];
fragment U: [uU];
fragment V: [vV];
fragment W: [wW];
fragment X: [xX];
fragment Y: [yY];
fragment Z: [zZ];

