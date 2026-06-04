package query

import (
	"strings"
	"unicode"
)

type TokenType int

const (
	TOK_IDENT  TokenType = iota
	TOK_NUMBER
	TOK_STRING
	TOK_SELECT
	TOK_FROM
	TOK_WHERE
	TOK_AND
	TOK_OR
	TOK_NOT
	TOK_IN
	TOK_BETWEEN
	TOK_IS
	TOK_NULL
	TOK_LIKE
	TOK_GROUP
	TOK_BY
	TOK_ORDER
	TOK_ASC
	TOK_DESC
	TOK_LIMIT
	TOK_SUM
	TOK_COUNT
	TOK_AVG
	TOK_MIN
	TOK_MAX
	TOK_STDDEV
	TOK_AS
	TOK_LPAREN
	TOK_RPAREN
	TOK_COMMA
	TOK_EQ
	TOK_NEQ
	TOK_LT
	TOK_LTE
	TOK_GT
	TOK_GTE
	TOK_STAR
	TOK_EOF
	TOK_JOIN
	TOK_INNER
	TOK_LEFT
	TOK_ON
	TOK_DISTINCT
	TOK_PERCENTILE
	TOK_P50
	TOK_P90
	TOK_P95
	TOK_P99
)

type Token struct {
	Type  TokenType
	Value string
}

var keywords = map[string]TokenType{
	"SELECT":  TOK_SELECT,
	"FROM":    TOK_FROM,
	"WHERE":   TOK_WHERE,
	"AND":     TOK_AND,
	"OR":      TOK_OR,
	"NOT":     TOK_NOT,
	"IN":      TOK_IN,
	"BETWEEN": TOK_BETWEEN,
	"IS":      TOK_IS,
	"NULL":    TOK_NULL,
	"LIKE":    TOK_LIKE,
	"GROUP":   TOK_GROUP,
	"BY":      TOK_BY,
	"ORDER":   TOK_ORDER,
	"ASC":     TOK_ASC,
	"DESC":    TOK_DESC,
	"LIMIT":   TOK_LIMIT,
	"SUM":     TOK_SUM,
	"COUNT":   TOK_COUNT,
	"AVG":     TOK_AVG,
	"MIN":     TOK_MIN,
	"MAX":     TOK_MAX,
	"STDDEV":     TOK_STDDEV,
	"AS":         TOK_AS,
	"JOIN":       TOK_JOIN,
	"INNER":      TOK_INNER,
	"LEFT":       TOK_LEFT,
	"ON":         TOK_ON,
	"DISTINCT":   TOK_DISTINCT,
	"PERCENTILE": TOK_PERCENTILE,
	"P50":        TOK_P50,
	"P90":        TOK_P90,
	"P95":        TOK_P95,
	"P99":        TOK_P99,
}

type Lexer struct {
	input  string
	pos    int
	tokens []Token
}

func NewLexer(input string) *Lexer {
	return &Lexer{input: input}
}

func (l *Lexer) Tokenize() []Token {
	l.tokens = nil
	l.pos = 0
	for l.pos < len(l.input) {
		l.skipWhitespace()
		if l.pos >= len(l.input) {
			break
		}
		ch := l.input[l.pos]
		switch {
		case ch == '*' :
			l.tokens = append(l.tokens, Token{TOK_STAR, "*"})
			l.pos++
		case ch == '(':
			l.tokens = append(l.tokens, Token{TOK_LPAREN, "("})
			l.pos++
		case ch == ')':
			l.tokens = append(l.tokens, Token{TOK_RPAREN, ")"})
			l.pos++
		case ch == ',':
			l.tokens = append(l.tokens, Token{TOK_COMMA, ","})
			l.pos++
		case ch == '=':
			l.tokens = append(l.tokens, Token{TOK_EQ, "="})
			l.pos++
		case ch == '!' && l.pos+1 < len(l.input) && l.input[l.pos+1] == '=':
			l.tokens = append(l.tokens, Token{TOK_NEQ, "!="})
			l.pos += 2
		case ch == '<':
			if l.pos+1 < len(l.input) && l.input[l.pos+1] == '=' {
				l.tokens = append(l.tokens, Token{TOK_LTE, "<="})
				l.pos += 2
			} else {
				l.tokens = append(l.tokens, Token{TOK_LT, "<"})
				l.pos++
			}
		case ch == '>':
			if l.pos+1 < len(l.input) && l.input[l.pos+1] == '=' {
				l.tokens = append(l.tokens, Token{TOK_GTE, ">="})
				l.pos += 2
			} else {
				l.tokens = append(l.tokens, Token{TOK_GT, ">"})
				l.pos++
			}
		case ch == '\'' || ch == '"':
			l.readString(ch)
		case unicode.IsDigit(rune(ch)):
			l.readNumber()
		case unicode.IsLetter(rune(ch)) || ch == '_':
			l.readIdent()
		default:
			l.pos++
		}
	}
	l.tokens = append(l.tokens, Token{TOK_EOF, ""})
	return l.tokens
}

func (l *Lexer) skipWhitespace() {
	for l.pos < len(l.input) && unicode.IsSpace(rune(l.input[l.pos])) {
		l.pos++
	}
}

func (l *Lexer) readNumber() {
	start := l.pos
	hasDot := false
	for l.pos < len(l.input) {
		ch := l.input[l.pos]
		if ch == '.' && !hasDot {
			hasDot = true
			l.pos++
		} else if unicode.IsDigit(rune(ch)) {
			l.pos++
		} else {
			break
		}
	}
	l.tokens = append(l.tokens, Token{TOK_NUMBER, l.input[start:l.pos]})
}

func (l *Lexer) readString(quote byte) {
	l.pos++
	start := l.pos
	for l.pos < len(l.input) && l.input[l.pos] != quote {
		if l.input[l.pos] == '\\' {
			l.pos++
		}
		l.pos++
	}
	val := l.input[start:l.pos]
	l.tokens = append(l.tokens, Token{TOK_STRING, val})
	if l.pos < len(l.input) {
		l.pos++
	}
}

func (l *Lexer) readIdent() {
	start := l.pos
	for l.pos < len(l.input) {
		ch := l.input[l.pos]
		if unicode.IsLetter(rune(ch)) || unicode.IsDigit(rune(ch)) || ch == '_' || ch == '.' {
			l.pos++
		} else {
			break
		}
	}
	word := l.input[start:l.pos]
	if tokType, ok := keywords[strings.ToUpper(word)]; ok {
		l.tokens = append(l.tokens, Token{tokType, strings.ToUpper(word)})
	} else {
		l.tokens = append(l.tokens, Token{TOK_IDENT, word})
	}
}
