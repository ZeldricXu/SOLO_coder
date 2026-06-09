grammar LogQuery;

query
    : expression (timeRange)? EOF
    ;

expression
    : expression AND expression       # andExpression
    | expression OR expression        # orExpression
    | NOT expression                  # notExpression
    | LPAREN expression RPAREN        # parenExpression
    | fieldComparison                 # fieldExpression
    | stringLiteral                   # fulltextExpression
    ;

fieldComparison
    : fieldName COLON value           # exactMatch
    | fieldName COLON TILDE value     # regexMatch
    | fieldName COLON LBRACE value TO value RBRACE  # rangeMatch
    | fieldName OP value              # numericCompare
    ;

fieldName
    : IDENTIFIER
    ;

value
    : stringLiteral
    | NUMBER
    | booleanLiteral
    ;

stringLiteral
    : QUOTED_STRING
    | IDENTIFIER
    ;

booleanLiteral
    : TRUE | FALSE
    ;

timeRange
    : SINCE duration AGO
    | BETWEEN timeInstant AND timeInstant
    | timeInstant TO timeInstant
    ;

duration
    : NUMBER UNIT
    ;

timeInstant
    : ISO_TIMESTAMP
    | NATURAL_DATE
    ;

AND: 'AND' | 'and' | '&&';
OR: 'OR' | 'or' | '||';
NOT: 'NOT' | 'not' | '!';
SINCE: 'SINCE' | 'since';
AGO: 'AGO' | 'ago';
BETWEEN: 'BETWEEN' | 'between';
TO: 'TO' | 'to';
TRUE: 'true' | 'TRUE';
FALSE: 'false' | 'FALSE';

COLON: ':';
TILDE: '~';
LPAREN: '(';
RPAREN: ')';
LBRACE: '[';
RBRACE: ']';

OP: '>' | '<' | '>=' | '<=' | '!=' | '=';

UNIT: 's' | 'm' | 'h' | 'd' | 'w';

NUMBER: '-'? DIGIT+ ('.' DIGIT+)?;

QUOTED_STRING: '"' ( ~["\\] | '\\' . )* '"'
             | '\'' ( ~['\\] | '\\' . )* '\'';

ISO_TIMESTAMP: DIGIT{4} '-' DIGIT{2} '-' DIGIT{2}
             ( ('T' | ' ') DIGIT{2} ':' DIGIT{2} (':' DIGIT{2} ('.' DIGIT+)?)?
               ( 'Z' | (('+' | '-') DIGIT{2} ':' DIGIT{2}) )? )?;

NATURAL_DATE: 'today' | 'yesterday' | 'now' | 'last_week' | 'last_month'
            | 'this_week' | 'this_month' | 'this_year';

IDENTIFIER: LETTER (LETTER | DIGIT | '_' | '-' | '.')*;

WS: [ \t\r\n]+ -> skip;

fragment DIGIT: [0-9];
fragment LETTER: [a-zA-Z];
