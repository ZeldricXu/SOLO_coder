package query

import (
	"fmt"
	"strconv"
)

type Parser struct {
	tokens []Token
	pos    int
}

func NewParser() *Parser {
	return &Parser{}
}

func (p *Parser) Parse(input string) (*SelectStmt, error) {
	lex := NewLexer(input)
	p.tokens = lex.Tokenize()
	p.pos = 0
	return p.parseSelect()
}

func (p *Parser) peek() Token {
	if p.pos < len(p.tokens) {
		return p.tokens[p.pos]
	}
	return Token{TOK_EOF, ""}
}

func (p *Parser) advance() Token {
	tok := p.peek()
	p.pos++
	return tok
}

func (p *Parser) expect(t TokenType) (Token, error) {
	tok := p.advance()
	if tok.Type != t {
		return tok, fmt.Errorf("expected token type %d, got %d (%q)", t, tok.Type, tok.Value)
	}
	return tok, nil
}

func (p *Parser) parseSelect() (*SelectStmt, error) {
	stmt := &SelectStmt{OrderAsc: true, Limit: -1}
	if _, err := p.expect(TOK_SELECT); err != nil {
		return nil, err
	}
	cols, err := p.parseColumnList()
	if err != nil {
		return nil, err
	}
	stmt.Columns = cols
	if _, err := p.expect(TOK_FROM); err != nil {
		return nil, err
	}
	fromTok, err := p.expect(TOK_IDENT)
	if err != nil {
		return nil, err
	}
	stmt.From = fromTok.Value
	for p.peek().Type == TOK_JOIN || p.peek().Type == TOK_INNER || p.peek().Type == TOK_LEFT {
		join, err := p.parseJoin()
		if err != nil {
			return nil, err
		}
		stmt.Joins = append(stmt.Joins, join)
	}
	if p.peek().Type == TOK_WHERE {
		p.advance()
		expr, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		stmt.Where = expr
	}
	if p.peek().Type == TOK_GROUP {
		p.advance()
		if _, err := p.expect(TOK_BY); err != nil {
			return nil, err
		}
		groupCols, err := p.parseColumnList()
		if err != nil {
			return nil, err
		}
		stmt.GroupBy = groupCols
		if p.peek().Type == TOK_SUM || p.peek().Type == TOK_COUNT || p.peek().Type == TOK_AVG ||
			p.peek().Type == TOK_MIN || p.peek().Type == TOK_MAX || p.peek().Type == TOK_STDDEV {
			aggTok := p.advance()
			stmt.AggFunc = aggTok.Value
			if _, err := p.expect(TOK_LPAREN); err != nil {
				return nil, err
			}
			colTok, err := p.expect(TOK_IDENT)
			if err != nil {
				return nil, err
			}
			stmt.AggCol = colTok.Value
			if _, err := p.expect(TOK_RPAREN); err != nil {
				return nil, err
			}
		}
	}
	if p.peek().Type == TOK_ORDER {
		p.advance()
		if _, err := p.expect(TOK_BY); err != nil {
			return nil, err
		}
		colTok, err := p.expect(TOK_IDENT)
		if err != nil {
			return nil, err
		}
		stmt.OrderBy = colTok.Value
		if p.peek().Type == TOK_ASC {
			p.advance()
			stmt.OrderAsc = true
		} else if p.peek().Type == TOK_DESC {
			p.advance()
			stmt.OrderAsc = false
		}
	}
	if p.peek().Type == TOK_LIMIT {
		p.advance()
		numTok, err := p.expect(TOK_NUMBER)
		if err != nil {
			return nil, err
		}
		n, err := strconv.Atoi(numTok.Value)
		if err != nil {
			return nil, fmt.Errorf("invalid LIMIT value: %s", numTok.Value)
		}
		stmt.Limit = n
	}
	return stmt, nil
}

func (p *Parser) parseJoin() (*JoinClause, error) {
	joinType := JoinInner
	if p.peek().Type == TOK_LEFT {
		p.advance()
		joinType = JoinLeft
	} else if p.peek().Type == TOK_INNER {
		p.advance()
		joinType = JoinInner
	}
	if _, err := p.expect(TOK_JOIN); err != nil {
		return nil, err
	}
	tableTok, err := p.expect(TOK_IDENT)
	if err != nil {
		return nil, err
	}
	if _, err := p.expect(TOK_ON); err != nil {
		return nil, err
	}
	leftColTok, err := p.expect(TOK_IDENT)
	if err != nil {
		return nil, err
	}
	if _, err := p.expect(TOK_EQ); err != nil {
		return nil, err
	}
	rightColTok, err := p.expect(TOK_IDENT)
	if err != nil {
		return nil, err
	}
	return &JoinClause{
		JoinType: joinType,
		Table:    tableTok.Value,
		LeftCol:  leftColTok.Value,
		RightCol: rightColTok.Value,
	}, nil
}

func (p *Parser) parseColumnList() ([]string, error) {
	if p.peek().Type == TOK_STAR {
		p.advance()
		return []string{"*"}, nil
	}
	tok, err := p.expect(TOK_IDENT)
	if err != nil {
		return nil, err
	}
	cols := []string{tok.Value}
	for p.peek().Type == TOK_COMMA {
		p.advance()
		tok, err := p.expect(TOK_IDENT)
		if err != nil {
			return nil, err
		}
		cols = append(cols, tok.Value)
	}
	return cols, nil
}

func (p *Parser) parseExpr() (WhereExpr, error) {
	return p.parseOrExpr()
}

func (p *Parser) parseOrExpr() (WhereExpr, error) {
	left, err := p.parseAndExpr()
	if err != nil {
		return nil, err
	}
	for p.peek().Type == TOK_OR {
		p.advance()
		right, err := p.parseAndExpr()
		if err != nil {
			return nil, err
		}
		left = &BinaryExpr{Left: left, Right: right, Op: "OR"}
	}
	return left, nil
}

func (p *Parser) parseAndExpr() (WhereExpr, error) {
	left, err := p.parseUnaryExpr()
	if err != nil {
		return nil, err
	}
	for p.peek().Type == TOK_AND {
		p.advance()
		right, err := p.parseUnaryExpr()
		if err != nil {
			return nil, err
		}
		left = &BinaryExpr{Left: left, Right: right, Op: "AND"}
	}
	return left, nil
}

func (p *Parser) parseUnaryExpr() (WhereExpr, error) {
	if p.peek().Type == TOK_NOT {
		p.advance()
		expr, err := p.parseUnaryExpr()
		if err != nil {
			return nil, err
		}
		return &BinaryExpr{Left: &BoolExpr{Value: false}, Right: expr, Op: "NOT"}, nil
	}
	return p.parsePrimaryExpr()
}

func (p *Parser) parsePrimaryExpr() (WhereExpr, error) {
	if p.peek().Type == TOK_LPAREN {
		p.advance()
		expr, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		if _, err := p.expect(TOK_RPAREN); err != nil {
			return nil, err
		}
		return expr, nil
	}
	if p.peek().Type != TOK_IDENT {
		return nil, fmt.Errorf("expected identifier, got %q", p.peek().Value)
	}
	ident := p.advance()
	switch p.peek().Type {
	case TOK_EQ, TOK_NEQ, TOK_LT, TOK_LTE, TOK_GT, TOK_GTE:
		op := p.advance()
		val, err := p.parseValue()
		if err != nil {
			return nil, err
		}
		return &CompareExpr{Col: ident.Value, Op: op.Value, Value: val}, nil
	case TOK_IN:
		p.advance()
		vals, err := p.parseInList()
		if err != nil {
			return nil, err
		}
		return &InExpr{Col: ident.Value, Values: vals, Negated: false}, nil
	case TOK_NOT:
		p.advance()
		if _, err := p.expect(TOK_IN); err != nil {
			return nil, err
		}
		vals, err := p.parseInList()
		if err != nil {
			return nil, err
		}
		return &InExpr{Col: ident.Value, Values: vals, Negated: true}, nil
	case TOK_BETWEEN:
		p.advance()
		low, err := p.parseValue()
		if err != nil {
			return nil, err
		}
		if _, err := p.expect(TOK_AND); err != nil {
			return nil, err
		}
		high, err := p.parseValue()
		if err != nil {
			return nil, err
		}
		return &BetweenExpr{Col: ident.Value, Low: low, High: high}, nil
	case TOK_IS:
		p.advance()
		negated := false
		if p.peek().Type == TOK_NOT {
			p.advance()
			negated = true
		}
		if _, err := p.expect(TOK_NULL); err != nil {
			return nil, err
		}
		return &IsNullExpr{Col: ident.Value, Negated: negated}, nil
	case TOK_LIKE:
		p.advance()
		patternTok, err := p.expect(TOK_STRING)
		if err != nil {
			return nil, err
		}
		return &LikeExpr{Col: ident.Value, Pattern: patternTok.Value}, nil
	default:
		return nil, fmt.Errorf("unexpected token after identifier %q: %q", ident.Value, p.peek().Value)
	}
}

func (p *Parser) parseInList() ([]interface{}, error) {
	if _, err := p.expect(TOK_LPAREN); err != nil {
		return nil, err
	}
	vals := make([]interface{}, 0)
	val, err := p.parseValue()
	if err != nil {
		return nil, err
	}
	vals = append(vals, val)
	for p.peek().Type == TOK_COMMA {
		p.advance()
		val, err := p.parseValue()
		if err != nil {
			return nil, err
		}
		vals = append(vals, val)
	}
	if _, err := p.expect(TOK_RPAREN); err != nil {
		return nil, err
	}
	return vals, nil
}

func (p *Parser) parseValue() (interface{}, error) {
	tok := p.advance()
	switch tok.Type {
	case TOK_NUMBER:
		if stringsContainsDot(tok.Value) {
			f, err := strconv.ParseFloat(tok.Value, 64)
			if err != nil {
				return nil, fmt.Errorf("invalid float: %s", tok.Value)
			}
			return f, nil
		}
		i, err := strconv.ParseInt(tok.Value, 10, 64)
		if err != nil {
			return nil, fmt.Errorf("invalid integer: %s", tok.Value)
		}
		return i, nil
	case TOK_STRING:
		return tok.Value, nil
	case TOK_IDENT:
		return tok.Value, nil
	default:
		return nil, fmt.Errorf("expected value, got %q", tok.Value)
	}
}

func stringsContainsDot(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] == '.' {
			return true
		}
	}
	return false
}
