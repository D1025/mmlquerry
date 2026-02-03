grammar MmlQuery;

// Top-level query rule
query : list
      | itemQuery
      | compoundQuery
      | contextQuery
      | operationQuery
      | selectiveQuery
      ;

// List queries (all items of a type)
list : globalList
     | qualifiedList
     ;

globalList : LIST '<' listItemKind '>'
           ;

qualifiedList : LIST '<' itemKind '>' 'in' listSource
              ;

listItemKind : 'constructors'
             | 'theorems'
             | 'definitions'
             | 'statements'
             | 'registrations'
             | 'all'
             ;

itemKind : 'func' | 'pred' | 'attr' | 'mode' | 'sel' | 'aggr' | 'struct'
         | 'th' | 'def' | 'dfs' | 'sch'
         ;

listSource : articleName
           | '*'
           ;

// Article-specific queries
articleList : ARTICLE articleName
            ;

symbolList : SYMBOL TEXT
           ;

formatList : FORMAT TEXT
           ;

keywordList : KEYWORD TEXT
            ;

nonQualifiedList : TEXT
                 ;

// Enumerated list (explicit constructor references)
enumeratedList : '{' constructorItem (',' constructorItem)* '}'
               ;

// Item queries (specific items)
itemQuery : constructor
          | constructorAbbreviation
          | constructorRelatives
          | articleQuery
          | groupQuery
          ;

constructor : articleName ':' itemKind NUMBER
            ;

constructorAbbreviation : articleName ':' constructorRelatives
                        ;

constructorRelatives : itemKind NUMBER
                     ;

articleQuery : ARTICLE articleName
             ;

groupQuery : FORALL query
           | EXISTS query
           | NOT query
           ;

constructorItem : articleName ':' itemKind NUMBER
                ;

// Compound queries (combining queries)
compoundQuery : query AND query
              | query OR query
              | NOT query
              ;

// Context queries (with constraints)
contextQuery : query WHERE TEXT
             ;

// Operation queries
operationQuery : query operation
               ;

operation : basicOperation
          | filterOperation
          | grepOperation
          | reverseOperation
          | compoundOperation
          ;

basicOperation : OCCUR
               | DEFINITION
               | NOTATION
               | REDEF
               | ORIGIN
               | COPY
               | 'termtype' 'ref'
               | 'deftype' 'ref'
               | 'main' 'mode'
               | 'main' 'functor'
               ;

filterOperation : FILTER TEXT
                ;

grepOperation : GREP TEXT
              ;

reverseOperation : REVERSE
                 | INVERT
                 ;

compoundOperation : operation ('|' | '&' | '|') operation
                  ;

// Selective queries
selectiveQuery : query WHERE TEXT
               ;

// Identifiers and literals
articleName : ARTICLE_NAME
            ;

ARTICLE : 'article' | 'Article' | 'ARTICLE' ;
LIST : 'list' | 'List' | 'LIST' ;
FORALL : 'forall' | 'all' ;
EXISTS : 'exists' | 'some' ;
NOT : 'not' | '~' | '!' ;
AND : 'and' | '&' | '&&' ;
OR : 'or' | '|' | '||' ;
WHERE : 'where' | 'with' ;
SYMBOL : 'symbol' | 'Symbol' ;
FORMAT : 'format' | 'Format' ;
KEYWORD : 'keyword' | 'Keyword' ;
FILTER : 'filter' | 'Filter' ;
GREP : 'grep' | 'Grep' ;
OCCUR : 'occur' | 'occurs' | 'occurrence' ;
DEFINITION : 'definition' | 'def' ;
NOTATION : 'notation' | 'not' ;
REDEF : 'redef' | 'redefinition' ;
ORIGIN : 'origin' | 'original' ;
COPY : 'copy' | 'copied' ;
REVERSE : 'reverse' ;
INVERT : 'invert' ;
ARTICLE_NAME : [A-Z][A-Z0-9_]* ;
NUMBER : [0-9]+ ;
TEXT : '"' (~["])* '"'
     | '\'' (~['\''])* '\''
     | [a-zA-Z_][a-zA-Z0-9_]*
     ;

WS : [ \t\r\n]+ -> skip ;

