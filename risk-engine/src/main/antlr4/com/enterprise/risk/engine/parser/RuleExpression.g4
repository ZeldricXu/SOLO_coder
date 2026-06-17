grammar RuleExpression;

expression
    : orExpression EOF
    ;

orExpression
    : andExpression (OR andExpression)*
    ;

andExpression
    : notExpression (AND notExpression)*
    ;

notExpression
    : NOT notExpression
    | comparisonExpression
    ;

comparisonExpression
    : primaryExpression comparisonOperator primaryExpression
    | primaryExpression IN LPAREN valueList RPAREN
    | primaryExpression NOT IN LPAREN valueList RPAREN
    | primaryExpression BETWEEN primaryExpression AND primaryExpression
    | primaryExpression LIKE STRING
    | primaryExpression IS NULL
    | primaryExpression IS NOT NULL
    | primaryExpression
    ;

comparisonOperator
    : GT | GTE | LT | LTE | EQ | NEQ
    ;

valueList
    : primaryExpression (COMMA primaryExpression)*
    ;

primaryExpression
    : LPAREN orExpression RPAREN
    | functionCall
    | fieldAccess
    | literal
    ;

functionCall
    : IDENTIFIER LPAREN argumentList? RPAREN
    ;

argumentList
    : primaryExpression (COMMA primaryExpression)*
    ;

fieldAccess
    : IDENTIFIER (DOT IDENTIFIER)*
    ;

literal
    : NUMBER
    | STRING
    | BOOLEAN
    | NULL
    ;

AND: 'AND' | '&&' ;
OR: 'OR' | '||' ;
NOT: 'NOT' | '!' ;
IN: 'IN' ;
BETWEEN: 'BETWEEN' ;
LIKE: 'LIKE' ;
IS: 'IS' ;
NULL: 'NULL' | 'null' ;

GT: '>' ;
GTE: '>=' ;
LT: '<' ;
LTE: '<=' ;
EQ: '==' | '=' ;
NEQ: '!=' | '<>' ;

LPAREN: '(' ;
RPAREN: ')' ;
COMMA: ',' ;
DOT: '.' ;

BOOLEAN: 'true' | 'TRUE' | 'false' | 'FALSE' ;

IDENTIFIER: [a-zA-Z_][a-zA-Z0-9_]* ;

NUMBER: '-'? DIGIT+ ('.' DIGIT+)? ;
fragment DIGIT: [0-9] ;

STRING: '"' (ESC | ~["\\])* '"' | '\'' (ESC | ~['\\])* '\'' ;
fragment ESC: '\\' (["\\/bfnrt] | UNICODE) ;
fragment UNICODE: 'u' HEX HEX HEX HEX ;
fragment HEX: [0-9a-fA-F] ;

WS: [ \t\r\n]+ -> skip ;
