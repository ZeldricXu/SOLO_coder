package streaming

import (
	"errors"
	"fmt"
	"regexp"
	"session154/internal/logger"
	"strings"
	"time"

	"go.uber.org/zap"
)

type TokenType int

const (
	TokenKeyword TokenType = iota
	TokenIdentifier
	TokenNumber
	TokenString
	TokenOperator
	TokenPunctuation
	TokenFunction
	TokenEOF
)

type Token struct {
	Type    TokenType
	Literal string
	Line    int
	Pos     int
}

type ASTNode interface {
	String() string
}

type SelectStmt struct {
	SelectItems []SelectItem
	From        Relation
	Where       Expression
	GroupBy     []Expression
	Having      Expression
	OrderBy     []OrderByItem
	Limit       int
	Offset      int
	Window      *WindowSpec
	IsStream    bool
}

func (s *SelectStmt) String() string { return "SELECT" }

type SelectItem struct {
	Expr  Expression
	Alias string
}

type Expression interface {
	ASTNode
	expr()
}

type Identifier struct {
	Name string
}

func (i *Identifier) String() string { return i.Name }
func (i *Identifier) expr()         {}

type Literal struct {
	Value interface{}
	Type  string
}

func (l *Literal) String() string { return fmt.Sprintf("%v", l.Value) }
func (l *Literal) expr()         {}

type BinaryExpr struct {
	Left     Expression
	Operator string
	Right    Expression
}

func (b *BinaryExpr) String() string { return fmt.Sprintf("%s %s %s", b.Left, b.Operator, b.Right) }
func (b *BinaryExpr) expr()         {}

type FunctionCall struct {
	Name     string
	Args     []Expression
	Distinct bool
}

func (f *FunctionCall) String() string { return fmt.Sprintf("%s(...)", f.Name) }
func (f *FunctionCall) expr()         {}

type Relation interface {
	ASTNode
	relation()
}

type TableRef struct {
	Name  string
	Alias string
}

func (t *TableRef) String() string { return t.Name }
func (t *TableRef) relation()      {}

type Join struct {
	Left     Relation
	Right    Relation
	JoinType string
	On       Expression
}

func (j *Join) String() string { return fmt.Sprintf("%s JOIN %s", j.JoinType, j.Right) }
func (j *Join) relation()      {}

type OrderByItem struct {
	Expr Expression
	Desc bool
}

type WindowSpec struct {
	PartitionBy []Expression
	OrderBy     []OrderByItem
	WindowFrame *WindowFrame
}

type WindowFrame struct {
	Unit      string
	Start     int
	End       int
	StartType string
	EndType   string
}

type LogicalPlan interface {
	plan()
	String() string
}

type LogicalScan struct {
	Table    string
	Filter   Expression
	Columns  []string
	IsStream bool
}

func (p *LogicalScan) plan()   {}
func (p *LogicalScan) String() string { return fmt.Sprintf("Scan[%s]", p.Table) }

type LogicalFilter struct {
	Input  LogicalPlan
	Filter Expression
}

func (p *LogicalFilter) plan()   {}
func (p *LogicalFilter) String() string { return "Filter" }

type LogicalProject struct {
	Input   LogicalPlan
	Columns []SelectItem
}

func (p *LogicalProject) plan()   {}
func (p *LogicalProject) String() string { return "Project" }

type LogicalAggregate struct {
	Input       LogicalPlan
	GroupKeys   []Expression
	Aggregations []Aggregation
}

type Aggregation struct {
	Function   string
	Args       []Expression
	Distinct   bool
	Alias      string
}

func (p *LogicalAggregate) plan()   {}
func (p *LogicalAggregate) String() string { return "Aggregate" }

type LogicalSort struct {
	Input LogicalPlan
	Items []OrderByItem
	Limit int
}

func (p *LogicalSort) plan()   {}
func (p *LogicalSort) String() string { return "Sort" }

type LogicalJoin struct {
	Left  LogicalPlan
	Right LogicalPlan
	Type  string
	Cond  Expression
}

func (p *LogicalJoin) plan()   {}
func (p *LogicalJoin) String() string { return fmt.Sprintf("%sJoin", p.Type) }

type LogicalWindow struct {
	Input        LogicalPlan
	WindowFunc   FunctionCall
	WindowSpec   *WindowSpec
	Alias        string
}

func (p *LogicalWindow) plan()   {}
func (p *LogicalWindow) String() string { return "Window" }

type PhysicalPlan interface {
	physical()
	String() string
}

type PhysicalScan struct {
	Table    string
	Filter   Expression
	Columns  []string
	IsStream bool
}

func (p *PhysicalScan) physical() {}
func (p *PhysicalScan) String() string { return fmt.Sprintf("PhysicalScan[%s]", p.Table) }

type PhysicalFilter struct {
	Input  PhysicalPlan
	Filter Expression
}

func (p *PhysicalFilter) physical() {}
func (p *PhysicalFilter) String() string { return "PhysicalFilter" }

type PhysicalProject struct {
	Input   PhysicalPlan
	Columns []SelectItem
}

func (p *PhysicalProject) physical() {}
func (p *PhysicalProject) String() string { return "PhysicalProject" }

type PhysicalHashAggregate struct {
	Input       PhysicalPlan
	GroupKeys   []Expression
	Aggregations []Aggregation
}

func (p *PhysicalHashAggregate) physical() {}
func (p *PhysicalHashAggregate) String() string { return "HashAggregate" }

type PhysicalSortAggregate struct {
	Input       PhysicalPlan
	GroupKeys   []Expression
	Aggregations []Aggregation
}

func (p *PhysicalSortAggregate) physical() {}
func (p *PhysicalSortAggregate) String() string { return "SortAggregate" }

type PhysicalSort struct {
	Input PhysicalPlan
	Items []OrderByItem
	Limit int
}

func (p *PhysicalSort) physical() {}
func (p *PhysicalSort) String() string { return "PhysicalSort" }

type PhysicalHashJoin struct {
	Left  PhysicalPlan
	Right PhysicalPlan
	Type  string
	Cond  Expression
}

func (p *PhysicalHashJoin) physical() {}
func (p *PhysicalHashJoin) String() string { return fmt.Sprintf("Hash%sJoin", p.Type) }

type PhysicalNestedLoopJoin struct {
	Left  PhysicalPlan
	Right PhysicalPlan
	Type  string
	Cond  Expression
}

func (p *PhysicalNestedLoopJoin) physical() {}
func (p *PhysicalNestedLoopJoin) String() string { return fmt.Sprintf("NestedLoop%sJoin", p.Type) }

type PhysicalWatermark struct {
	Input      PhysicalPlan
	EventTime  Expression
	Delay      time.Duration
}

func (p *PhysicalWatermark) physical() {}
func (p *PhysicalWatermark) String() string { return "Watermark" }

type PhysicalWindowAggregate struct {
	Input        PhysicalPlan
	WindowFunc   FunctionCall
	WindowSpec   *WindowSpec
	Alias        string
}

func (p *PhysicalWindowAggregate) physical() {}
func (p *PhysicalWindowAggregate) String() string { return "WindowAggregate" }

type Lexer struct {
	input   string
	pos     int
	readPos int
	ch      byte
	line    int
	linePos int
}

func NewLexer(input string) *Lexer {
	l := &Lexer{input: input, line: 1}
	l.readChar()
	return l
}

func (l *Lexer) readChar() {
	if l.readPos >= len(l.input) {
		l.ch = 0
	} else {
		l.ch = l.input[l.readPos]
	}
	l.pos = l.readPos
	l.readPos++
	l.linePos++
	if l.ch == '\n' {
		l.line++
		l.linePos = 0
	}
}

func (l *Lexer) peekChar() byte {
	if l.readPos >= len(l.input) {
		return 0
	}
	return l.input[l.readPos]
}

func (l *Lexer) NextToken() Token {
	l.skipWhitespace()

	switch l.ch {
	case 0:
		return Token{Type: TokenEOF, Literal: "", Line: l.line, Pos: l.linePos}
	case '=':
		if l.peekChar() == '=' {
			ch := l.ch
			l.readChar()
			return Token{Type: TokenOperator, Literal: string(ch) + string(l.ch), Line: l.line, Pos: l.linePos}
		}
		return Token{Type: TokenOperator, Literal: string(l.ch), Line: l.line, Pos: l.linePos}
	case '!':
		if l.peekChar() == '=' {
			l.readChar()
			return Token{Type: TokenOperator, Literal: "!=", Line: l.line, Pos: l.linePos}
		}
	case '<':
		if l.peekChar() == '=' {
			l.readChar()
			return Token{Type: TokenOperator, Literal: "<=", Line: l.line, Pos: l.linePos}
		}
		return Token{Type: TokenOperator, Literal: "<", Line: l.line, Pos: l.linePos}
	case '>':
		if l.peekChar() == '=' {
			l.readChar()
			return Token{Type: TokenOperator, Literal: ">=", Line: l.line, Pos: l.linePos}
		}
		return Token{Type: TokenOperator, Literal: ">", Line: l.line, Pos: l.linePos}
	case '+', '-', '*', '/', '%':
		return Token{Type: TokenOperator, Literal: string(l.ch), Line: l.line, Pos: l.linePos}
	case ',', '(', ')', ';':
		tok := Token{Type: TokenPunctuation, Literal: string(l.ch), Line: l.line, Pos: l.linePos}
		l.readChar()
		return tok
	case '\'', '"':
		return l.readString()
	default:
		if isLetter(l.ch) {
			literal := l.readIdentifier()
			if isKeyword(literal) {
				return Token{Type: TokenKeyword, Literal: strings.ToUpper(literal), Line: l.line, Pos: l.linePos}
			}
			if l.peekChar() == '(' {
				return Token{Type: TokenFunction, Literal: literal, Line: l.line, Pos: l.linePos}
			}
			return Token{Type: TokenIdentifier, Literal: literal, Line: l.line, Pos: l.linePos}
		}
		if isDigit(l.ch) {
			return l.readNumber()
		}
	}

	l.readChar()
	return Token{Type: TokenEOF, Literal: "", Line: l.line, Pos: l.linePos}
}

func (l *Lexer) readIdentifier() string {
	start := l.pos
	for isLetter(l.ch) || isDigit(l.ch) || l.ch == '_' {
		l.readChar()
	}
	return l.input[start:l.pos]
}

func (l *Lexer) readNumber() Token {
	start := l.pos
	for isDigit(l.ch) {
		l.readChar()
	}
	if l.ch == '.' && isDigit(l.peekChar()) {
		l.readChar()
		for isDigit(l.ch) {
			l.readChar()
		}
	}
	return Token{Type: TokenNumber, Literal: l.input[start:l.pos], Line: l.line, Pos: l.linePos}
}

func (l *Lexer) readString() Token {
	quote := l.ch
	l.readChar()
	start := l.pos
	for l.ch != quote && l.ch != 0 {
		l.readChar()
	}
	literal := l.input[start:l.pos]
	l.readChar()
	return Token{Type: TokenString, Literal: literal, Line: l.line, Pos: l.linePos}
}

func (l *Lexer) skipWhitespace() {
	for l.ch == ' ' || l.ch == '\t' || l.ch == '\n' || l.ch == '\r' {
		l.readChar()
	}
}

func isLetter(ch byte) bool {
	return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
}

func isDigit(ch byte) bool {
	return ch >= '0' && ch <= '9'
}

var keywords = map[string]bool{
	"SELECT": true, "FROM": true, "WHERE": true, "GROUP": true, "BY": true,
	"HAVING": true, "ORDER": true, "LIMIT": true, "OFFSET": true, "JOIN": true,
	"INNER": true, "LEFT": true, "RIGHT": true, "OUTER": true, "ON": true,
	"AS": true, "AND": true, "OR": true, "NOT": true, "IN": true,
	"LIKE": true, "BETWEEN": true, "IS": true, "NULL": true, "TRUE": true,
	"FALSE": true, "ASC": true, "DESC": true, "DISTINCT": true, "COUNT": true,
	"SUM": true, "AVG": true, "MIN": true, "MAX": true, "OVER": true,
	"PARTITION": true, "ROWS": true, "RANGE": true, "BETWEEN": true,
	"UNBOUNDED": true, "PRECEDING": true, "FOLLOWING": true, "CURRENT": true,
	"ROW": true, "STREAM": true, "TUMBLE": true, "HOP": true, "SESSION": true,
	"WATERMARK": true,
}

func isKeyword(word string) bool {
	return keywords[strings.ToUpper(word)]
}

type Parser struct {
	lexer     *Lexer
	curToken  Token
	peekToken Token
	errors    []error
}

func NewParser(input string) *Parser {
	p := &Parser{lexer: NewLexer(input)}
	p.nextToken()
	p.nextToken()
	return p
}

func (p *Parser) nextToken() {
	p.curToken = p.peekToken
	p.peekToken = p.lexer.NextToken()
}

func (p *Parser) Parse() (*SelectStmt, error) {
	stmt := &SelectStmt{}

	if p.curToken.Literal == "STREAM" {
		stmt.IsStream = true
		p.nextToken()
	}

	if !p.expectPeek(TokenKeyword, "SELECT") {
		return nil, p.errors[0]
	}
	p.nextToken()

	stmt.SelectItems = p.parseSelectItems()

	if p.curToken.Literal == "FROM" {
		p.nextToken()
		stmt.From = p.parseRelation()
	}

	if p.curToken.Literal == "WHERE" {
		p.nextToken()
		stmt.Where = p.parseExpression()
	}

	if p.curToken.Literal == "GROUP" {
		p.nextToken()
		if p.curToken.Literal == "BY" {
			p.nextToken()
			stmt.GroupBy = p.parseExpressionList()
		}
	}

	if p.curToken.Literal == "HAVING" {
		p.nextToken()
		stmt.Having = p.parseExpression()
	}

	if p.curToken.Literal == "ORDER" {
		p.nextToken()
		if p.curToken.Literal == "BY" {
			p.nextToken()
			stmt.OrderBy = p.parseOrderBy()
		}
	}

	if p.curToken.Literal == "LIMIT" {
		p.nextToken()
		stmt.Limit = p.parseInt()
	}

	return stmt, nil
}

func (p *Parser) parseSelectItems() []SelectItem {
	items := []SelectItem{}

	for {
		item := SelectItem{}
		item.Expr = p.parseExpression()

		if p.curToken.Literal == "AS" {
			p.nextToken()
			item.Alias = p.curToken.Literal
			p.nextToken()
		}

		items = append(items, item)

		if p.curToken.Literal != "," {
			break
		}
		p.nextToken()
	}

	return items
}

func (p *Parser) parseRelation() Relation {
	rel := &TableRef{Name: p.curToken.Literal}
	p.nextToken()

	if p.curToken.Literal == "AS" {
		p.nextToken()
		rel.Alias = p.curToken.Literal
		p.nextToken()
	}

	for p.curToken.Literal == "JOIN" || p.curToken.Literal == "INNER" ||
		p.curToken.Literal == "LEFT" || p.curToken.Literal == "RIGHT" {
		joinType := "INNER"
		if p.curToken.Literal == "LEFT" {
			joinType = "LEFT"
			p.nextToken()
		} else if p.curToken.Literal == "RIGHT" {
			joinType = "RIGHT"
			p.nextToken()
		}
		if p.curToken.Literal == "OUTER" {
			p.nextToken()
		}
		p.nextToken()

		right := &TableRef{Name: p.curToken.Literal}
		p.nextToken()

		if p.curToken.Literal == "ON" {
			p.nextToken()
			on := p.parseExpression()
			rel = &Join{Left: rel, Right: right, JoinType: joinType, On: on}
		}
	}

	return rel
}

func (p *Parser) parseExpression() Expression {
	return p.parseOr()
}

func (p *Parser) parseOr() Expression {
	left := p.parseAnd()
	for p.curToken.Literal == "OR" {
		p.nextToken()
		right := p.parseAnd()
		left = &BinaryExpr{Left: left, Operator: "OR", Right: right}
	}
	return left
}

func (p *Parser) parseAnd() Expression {
	left := p.parseEquality()
	for p.curToken.Literal == "AND" {
		p.nextToken()
		right := p.parseEquality()
		left = &BinaryExpr{Left: left, Operator: "AND", Right: right}
	}
	return left
}

func (p *Parser) parseEquality() Expression {
	left := p.parseComparison()
	for p.curToken.Literal == "=" || p.curToken.Literal == "!=" {
		op := p.curToken.Literal
		p.nextToken()
		right := p.parseComparison()
		left = &BinaryExpr{Left: left, Operator: op, Right: right}
	}
	return left
}

func (p *Parser) parseComparison() Expression {
	left := p.parseAdditive()
	for p.curToken.Literal == "<" || p.curToken.Literal == ">" ||
		p.curToken.Literal == "<=" || p.curToken.Literal == ">=" {
		op := p.curToken.Literal
		p.nextToken()
		right := p.parseAdditive()
		left = &BinaryExpr{Left: left, Operator: op, Right: right}
	}
	return left
}

func (p *Parser) parseAdditive() Expression {
	left := p.parseMultiplicative()
	for p.curToken.Literal == "+" || p.curToken.Literal == "-" {
		op := p.curToken.Literal
		p.nextToken()
		right := p.parseMultiplicative()
		left = &BinaryExpr{Left: left, Operator: op, Right: right}
	}
	return left
}

func (p *Parser) parseMultiplicative() Expression {
	left := p.parsePrimary()
	for p.curToken.Literal == "*" || p.curToken.Literal == "/" || p.curToken.Literal == "%" {
		op := p.curToken.Literal
		p.nextToken()
		right := p.parsePrimary()
		left = &BinaryExpr{Left: left, Operator: op, Right: right}
	}
	return left
}

func (p *Parser) parsePrimary() Expression {
	switch p.curToken.Type {
	case TokenIdentifier:
		ident := &Identifier{Name: p.curToken.Literal}
		p.nextToken()
		return ident
	case TokenNumber:
		num := p.parseNumber()
		p.nextToken()
		return num
	case TokenString:
		str := &Literal{Value: p.curToken.Literal, Type: "string"}
		p.nextToken()
		return str
	case TokenFunction:
		funcName := p.curToken.Literal
		p.nextToken()
		p.nextToken()
		args := []Expression{}
		if p.curToken.Literal != ")" {
			args = p.parseExpressionList()
		}
		p.nextToken()
		return &FunctionCall{Name: funcName, Args: args}
	case TokenPunctuation:
		if p.curToken.Literal == "(" {
			p.nextToken()
			expr := p.parseExpression()
			p.nextToken()
			return expr
		}
	}
	return &Identifier{Name: p.curToken.Literal}
}

func (p *Parser) parseExpressionList() []Expression {
	exprs := []Expression{}
	for {
		exprs = append(exprs, p.parseExpression())
		if p.curToken.Literal != "," {
			break
		}
		p.nextToken()
	}
	return exprs
}

func (p *Parser) parseOrderBy() []OrderByItem {
	items := []OrderByItem{}
	for {
		item := OrderByItem{Expr: p.parseExpression()}
		if p.curToken.Literal == "DESC" {
			item.Desc = true
			p.nextToken()
		} else if p.curToken.Literal == "ASC" {
			p.nextToken()
		}
		items = append(items, item)
		if p.curToken.Literal != "," {
			break
		}
		p.nextToken()
	}
	return items
}

func (p *Parser) parseInt() int {
	var result int
	fmt.Sscanf(p.curToken.Literal, "%d", &result)
	p.nextToken()
	return result
}

func (p *Parser) parseNumber() *Literal {
	if strings.Contains(p.curToken.Literal, ".") {
		var f float64
		fmt.Sscanf(p.curToken.Literal, "%f", &f)
		return &Literal{Value: f, Type: "float"}
	}
	var i int64
	fmt.Sscanf(p.curToken.Literal, "%d", &i)
	return &Literal{Value: i, Type: "int"}
}

func (p *Parser) expectPeek(typ TokenType, literal string) bool {
	if p.peekToken.Type == typ && p.peekToken.Literal == literal {
		p.nextToken()
		return true
	}
	p.errors = append(p.errors, fmt.Errorf("expected %s, got %s", literal, p.peekToken.Literal))
	return false
}

type LogicalPlanner struct{}

func NewLogicalPlanner() *LogicalPlanner {
	return &LogicalPlanner{}
}

func (lp *LogicalPlanner) Plan(stmt *SelectStmt) (LogicalPlan, error) {
	tableRef, ok := stmt.From.(*TableRef)
	if !ok {
		return nil, errors.New("only table references supported")
	}

	var plan LogicalPlan = &LogicalScan{
		Table:    tableRef.Name,
		IsStream: stmt.IsStream,
	}

	if stmt.Where != nil {
		plan = &LogicalFilter{Input: plan, Filter: stmt.Where}
	}

	plan = &LogicalProject{Input: plan, Columns: stmt.SelectItems}

	if len(stmt.GroupBy) > 0 {
		aggs := []Aggregation{}
		for _, item := range stmt.SelectItems {
			if fc, ok := item.Expr.(*FunctionCall); ok {
				aggs = append(aggs, Aggregation{
					Function: fc.Name,
					Args:     fc.Args,
					Distinct: fc.Distinct,
					Alias:    item.Alias,
				})
			}
		}
		plan = &LogicalAggregate{
			Input:        plan,
			GroupKeys:    stmt.GroupBy,
			Aggregations: aggs,
		}
	}

	if len(stmt.OrderBy) > 0 {
		plan = &LogicalSort{Input: plan, Items: stmt.OrderBy, Limit: stmt.Limit}
	}

	return plan, nil
}

type Optimizer struct{}

func NewOptimizer() *Optimizer {
	return &Optimizer{}
}

func (o *Optimizer) Optimize(plan LogicalPlan) LogicalPlan {
	o.pushDownFilters(plan)
	o.combineProjects(plan)
	o.removeRedundantProjections(plan)
	return plan
}

func (o *Optimizer) pushDownFilters(plan LogicalPlan) {
	switch p := plan.(type) {
	case *LogicalFilter:
		if scan, ok := p.Input.(*LogicalScan); ok {
			if scan.Filter == nil {
				scan.Filter = p.Filter
				*p = *p.Input.(*LogicalScan)
			}
		} else {
			o.pushDownFilters(p.Input)
		}
	case *LogicalProject:
		o.pushDownFilters(p.Input)
	case *LogicalAggregate:
		o.pushDownFilters(p.Input)
	case *LogicalSort:
		o.pushDownFilters(p.Input)
	}
}

func (o *Optimizer) combineProjects(plan LogicalPlan) {
	if proj, ok := plan.(*LogicalProject); ok {
		if child, ok := proj.Input.(*LogicalProject); ok {
			proj.Input = child.Input
		}
	}
}

func (o *Optimizer) removeRedundantProjections(plan LogicalPlan) {
	if proj, ok := plan.(*LogicalProject); ok {
		if len(proj.Columns) == 1 {
			if ident, ok := proj.Columns[0].Expr.(*Identifier); ok && ident.Name == "*" {
				*proj = *proj.Input.(*LogicalProject)
			}
		}
	}
}

type PhysicalPlanner struct{}

func NewPhysicalPlanner() *PhysicalPlanner {
	return &PhysicalPlanner{}
}

func (pp *PhysicalPlanner) Plan(plan LogicalPlan) (PhysicalPlan, error) {
	switch lp := plan.(type) {
	case *LogicalScan:
		return &PhysicalScan{
			Table:    lp.Table,
			Filter:   lp.Filter,
			Columns:  lp.Columns,
			IsStream: lp.IsStream,
		}, nil
	case *LogicalFilter:
		input, err := pp.Plan(lp.Input)
		if err != nil {
			return nil, err
		}
		return &PhysicalFilter{Input: input, Filter: lp.Filter}, nil
	case *LogicalProject:
		input, err := pp.Plan(lp.Input)
		if err != nil {
			return nil, err
		}
		return &PhysicalProject{Input: input, Columns: lp.Columns}, nil
	case *LogicalAggregate:
		input, err := pp.Plan(lp.Input)
		if err != nil {
			return nil, err
		}
		if len(lp.GroupKeys) == 0 {
			return &PhysicalHashAggregate{
				Input:        input,
				GroupKeys:    lp.GroupKeys,
				Aggregations: lp.Aggregations,
			}, nil
		}
		return &PhysicalSortAggregate{
			Input:        input,
			GroupKeys:    lp.GroupKeys,
			Aggregations: lp.Aggregations,
		}, nil
	case *LogicalSort:
		input, err := pp.Plan(lp.Input)
		if err != nil {
			return nil, err
		}
		return &PhysicalSort{Input: input, Items: lp.Items, Limit: lp.Limit}, nil
	case *LogicalJoin:
		left, err := pp.Plan(lp.Left)
		if err != nil {
			return nil, err
		}
		right, err := pp.Plan(lp.Right)
		if err != nil {
			return nil, err
		}
		return &PhysicalHashJoin{Left: left, Right: right, Type: lp.Type, Cond: lp.Cond}, nil
	}

	return nil, fmt.Errorf("unsupported logical plan type: %T", plan)
}

type StreamQueryProcessor struct {
	parser   *Parser
	logical  *LogicalPlanner
	optim    *Optimizer
	physical *PhysicalPlanner
}

func NewStreamQueryProcessor() *StreamQueryProcessor {
	return &StreamQueryProcessor{
		logical:  NewLogicalPlanner(),
		optim:    NewOptimizer(),
		physical: NewPhysicalPlanner(),
	}
}

func (qp *StreamQueryProcessor) Process(query string) (PhysicalPlan, error) {
	parser := NewParser(query)
	stmt, err := parser.Parse()
	if err != nil {
		logger.Error("query parse failed", zap.Error(err))
		return nil, err
	}

	logicalPlan, err := qp.logical.Plan(stmt)
	if err != nil {
		logger.Error("logical planning failed", zap.Error(err))
		return nil, err
	}

	optimizedPlan := qp.optim.Optimize(logicalPlan)

	physicalPlan, err := qp.physical.Plan(optimizedPlan)
	if err != nil {
		logger.Error("physical planning failed", zap.Error(err))
		return nil, err
	}

	logger.Info("query processed", zap.String("query", query))
	return physicalPlan, nil
}

func IsStreamQuery(query string) bool {
	re := regexp.MustCompile(`(?i)^\s*STREAM\s+SELECT`)
	return re.MatchString(query)
}

type ExecutionEngine struct {
	plans map[string]PhysicalPlan
}

func NewExecutionEngine() *ExecutionEngine {
	return &ExecutionEngine{
		plans: make(map[string]PhysicalPlan),
	}
}

func (e *ExecutionEngine) Execute(queryID string, plan PhysicalPlan) error {
	e.plans[queryID] = plan
	logger.Info("executing physical plan", zap.String("query_id", queryID), zap.String("plan", plan.String()))
	return nil
}

func (e *ExecutionEngine) Stop(queryID string) {
	delete(e.plans, queryID)
}
