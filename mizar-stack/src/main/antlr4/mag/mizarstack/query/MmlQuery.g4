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
    | constructorExpression
    | articleExpression
    ;

theoremInfixExpression
    : LIST OF THEOREM (IN listSource)? WHERE propositionInfixPredicate AND propositionInfixPredicate
    ;

propositionInfixPredicate
    : PROPOSITION HAS INFIX_TERM (LBRACK ABSOLUTEPATTERNMMLID_ATTR EQ stringLiteral RBRACK)?
    ;

listExpression
    : LIST OF listType (IN listSource)?
    ;

listType
    : CONSTRUCTOR
    | THEOREM
    | DEFINITION
    | STATEMENT
    | REGISTRATION
    | ALL
    ;

listSource
    : ARTICLE_NAME
    | STAR
    ;

constructorExpression
    : ARTICLE_NAME COLON itemKind NUMBER
    ;

articleExpression
    : ARTICLE ARTICLE_NAME
    ;

itemKind
    : FUNC
    | PRED
    | ATTR
    | MODE
    | SEL
    | AGGR
    | STRUCT
    | TH
    | DEF
    | DFS
    | SCH
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
    | REVERSE                                     # opReverse
    | INVERT                                      # opInvert
    | cardinalityOperation                        # opCardinality
    ;

cardinalityOperation
    : WHEREEQ LPAREN operationName COMMA NUMBER RPAREN   # opWhereEq
    | WHEREGE LPAREN operationName COMMA NUMBER RPAREN   # opWhereGe
    | WHERELE LPAREN operationName COMMA NUMBER RPAREN   # opWhereLe
    | WHEREGT LPAREN operationName COMMA NUMBER RPAREN   # opWhereGt
    | WHERELT LPAREN operationName COMMA NUMBER RPAREN   # opWhereLt
    ;

operationName
    : REF
    | OCCUR
    | DEFINITION
    | NOTATION
    | TERMTYPE REF
    | DEFTYPE REF
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
HAS: H A S;
INFIX_TERM: I N F I X '-' T E R M;
ABSOLUTEPATTERNMMLID_ATTR: A B S O L U T E P A T T E R N M M L I D;
AND: A N D;
OR: O R;
BUTNOT: B U T N O T;
NOT: N O T;
PIPE: '|';
STAR: '*';
COMMA: ',';
COLON: ':';
LPAREN: '(';
RPAREN: ')';
LBRACK: '[';
RBRACK: ']';
EQ: '=';

CONSTRUCTOR: C O N S T R U C T O R S?;
THEOREM: T H E O R E M S?;
DEFINITION: D E F I N I T I O N S?;
STATEMENT: S T A T E M E N T S?;
REGISTRATION: R E G I S T R A T I O N S?;
ALL: A L L;

REF: R E F;
OCCUR: O C C U R S?;
NOTATION: N O T A T I O N;
REDEF: R E D E F | R E D E F I N I T I O N;
ORIGIN: O R I G I N | O R I G I N A L;
COPY: C O P Y | C O P I E D;
TERMTYPE: T E R M T Y P E;
DEFTYPE: D E F T Y P E;
MAIN: M A I N;
FUNCTOR: F U N C T O R;
FILTER: F I L T E R;
GREP: G R E P;
REVERSE: R E V E R S E;
INVERT: I N V E R T;
WHEREEQ: W H E R E E Q;
WHEREGE: W H E R E G E;
WHERELE: W H E R E L E;
WHEREGT: W H E R E G T;
WHERELT: W H E R E L T;

FUNC: F U N C;
PRED: P R E D;
ATTR: A T T R;
MODE: M O D E;
SEL: S E L;
AGGR: A G G R;
STRUCT: S T R U C T;
TH: T H;
DEF: D E F;
DFS: D F S;
SCH: S C H;

ARTICLE_NAME: [A-Z] [A-Z0-9_]*;
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

