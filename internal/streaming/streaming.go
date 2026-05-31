package streaming

import (
	"errors"
	"strings"
	"sync"

	"github.com/datatransform/platform/pkg/logger"
	"go.uber.org/zap"
)

type TokenType int

const (
	TokenKeyword TokenType = iota
	TokenIdentifier
	TokenOperator
	TokenLiteral
	TokenPunctuation
	TokenFunction
	TokenEOF
)

type Token struct {
	Type  TokenType
	Value string
	Pos   int
}

type ASTNode struct {
	NodeType string
	Value    string
	Children []*ASTNode
	Metadata map[string]interface{}
}

type LogicalPlan struct {
	Operator     string
	SourceTables []string
	TargetTable  string
	Conditions   []string
	Projections  []string
	Aggregations []string
	WindowSpec   *WindowSpec
}

type WindowSpec struct {
	PartitionBy []string
	OrderBy     []string
	FrameClause string
}

type PhysicalPlan struct {
	Stages       []*ExecutionStage
	Optimizations []string
	EstimatedCost float64
}

type ExecutionStage struct {
	ID          string
	Operation   string
	Parallelism int
	Resources   map[string]int64
}

type SQLParser struct {
	tokens []Token
	pos    int
	mu     sync.Mutex
}

func NewSQLParser() *SQLParser {
	return &SQLParser{
		tokens: make([]Token, 0),
		pos:    0,
	}
}

func (p *SQLParser) Parse(sql string) (*ASTNode, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	logger.Info("parsing streaming SQL", zap.String("sql", sql))

	tokens, err := p.tokenize(sql)
	if err != nil {
		return nil, err
	}

	p.tokens = tokens
	p.pos = 0

	ast, err := p.parseStatement()
	if err != nil {
		return nil, err
	}

	return ast, nil
}

func (p *SQLParser) tokenize(sql string) ([]Token, error) {
	tokens := make([]Token, 0)
	keywords := map[string]bool{
		"SELECT": true, "FROM": true, "WHERE": true, "GROUP": true,
		"BY": true, "HAVING": true, "ORDER": true, "LIMIT": true,
		"INSERT": true, "INTO": true, "VALUES": true, "UPDATE": true,
		"SET": true, "DELETE": true, "JOIN": true, "INNER": true,
		"LEFT": true, "RIGHT": true, "FULL": true, "OUTER": true,
		"ON": true, "AS": true, "AND": true, "OR": true, "NOT": true,
		"IN": true, "LIKE": true, "BETWEEN": true, "NULL": true,
		"IS": true, "DISTINCT": true, "COUNT": true, "SUM": true,
		"AVG": true, "MIN": true, "MAX": true, "WINDOW": true,
		"PARTITION": true, "RANGE": true, "ROWS": true, "BETWEEN": true,
		"UNBOUNDED": true, "PRECEDING": true, "FOLLOWING": true,
		"CURRENT": true, "ROW": true, "STREAM": true, "TUMBLE": true,
		"HOP": true, "SESSION": true,
	}

	functions := map[string]bool{
		"COUNT": true, "SUM": true, "AVG": true, "MIN": true, "MAX": true,
		"TUMBLE": true, "HOP": true, "SESSION": true,
	}

	sqlUpper := strings.ToUpper(sql)
	var currentToken strings.Builder
	pos := 0

	for pos < len(sqlUpper) {
		char := sqlUpper[pos]

		switch {
		case char == ' ' || char == '\t' || char == '\n' || char == '\r':
			if currentToken.Len() > 0 {
				tokenValue := currentToken.String()
				tokenType := TokenIdentifier
				if functions[tokenValue] {
					tokenType = TokenFunction
				} else if keywords[tokenValue] {
					tokenType = TokenKeyword
				}
				tokens = append(tokens, Token{Type: tokenType, Value: tokenValue, Pos: pos - currentToken.Len()})
				currentToken.Reset()
			}
			pos++

		case char >= 'A' && char <= 'Z' || char >= 'a' && char <= 'z' || char == '_':
			currentToken.WriteByte(char)
			pos++

		case char >= '0' && char <= '9':
			currentToken.WriteByte(char)
			pos++

		case char == '\'' || char == '"':
			quote := char
			pos++
			for pos < len(sqlUpper) && sqlUpper[pos] != quote {
				currentToken.WriteByte(sqlUpper[pos])
				pos++
			}
			tokens = append(tokens, Token{Type: TokenLiteral, Value: currentToken.String(), Pos: pos - currentToken.Len()})
			currentToken.Reset()
			pos++

		case strings.ContainsRune("=<>+-*/%", rune(char)):
			if currentToken.Len() > 0 {
				tokens = append(tokens, Token{Type: TokenIdentifier, Value: currentToken.String(), Pos: pos - currentToken.Len()})
				currentToken.Reset()
			}
			operator := string(char)
			if pos+1 < len(sqlUpper) && (sqlUpper[pos+1] == '=' || (char == '<' && sqlUpper[pos+1] == '>')) {
				operator += string(sqlUpper[pos+1])
				pos++
			}
			tokens = append(tokens, Token{Type: TokenOperator, Value: operator, Pos: pos})
			pos++

		case strings.ContainsRune("(),;.[]", rune(char)):
			if currentToken.Len() > 0 {
				tokens = append(tokens, Token{Type: TokenIdentifier, Value: currentToken.String(), Pos: pos - currentToken.Len()})
				currentToken.Reset()
			}
			tokens = append(tokens, Token{Type: TokenPunctuation, Value: string(char), Pos: pos})
			pos++

		default:
			pos++
		}
	}

	if currentToken.Len() > 0 {
		tokenValue := currentToken.String()
		tokenType := TokenIdentifier
		if functions[tokenValue] {
			tokenType = TokenFunction
		} else if keywords[tokenValue] {
			tokenType = TokenKeyword
		}
		tokens = append(tokens, Token{Type: tokenType, Value: tokenValue, Pos: pos - currentToken.Len()})
	}

	tokens = append(tokens, Token{Type: TokenEOF, Value: "", Pos: pos})

	return tokens, nil
}

func (p *SQLParser) parseStatement() (*ASTNode, error) {
	if p.pos >= len(p.tokens) {
		return nil, errors.New("unexpected end of input")
	}

	token := p.tokens[p.pos]

	switch token.Value {
	case "SELECT":
		return p.parseSelect()
	case "INSERT":
		return p.parseInsert()
	default:
		return nil, errors.New("unsupported statement type: " + token.Value)
	}
}

func (p *SQLParser) parseSelect() (*ASTNode, error) {
	node := &ASTNode{
		NodeType: "SELECT_STATEMENT",
		Children: make([]*ASTNode, 0),
		Metadata: make(map[string]interface{}),
	}

	p.pos++

	projections := make([]*ASTNode, 0)
	for p.pos < len(p.tokens) && p.tokens[p.pos].Value != "FROM" && p.tokens[p.pos].Type != TokenEOF {
		if p.tokens[p.pos].Type == TokenPunctuation {
			p.pos++
			continue
		}
		projections = append(projections, &ASTNode{
			NodeType: "PROJECTION",
			Value:    p.tokens[p.pos].Value,
		})
		p.pos++
	}

	node.Children = append(node.Children, &ASTNode{
		NodeType: "PROJECTIONS",
		Children: projections,
	})

	if p.pos < len(p.tokens) && p.tokens[p.pos].Value == "FROM" {
		p.pos++
		sources := make([]*ASTNode, 0)
		for p.pos < len(p.tokens) && p.tokens[p.pos].Value != "WHERE" &&
			p.tokens[p.pos].Value != "GROUP" && p.tokens[p.pos].Value != "WINDOW" &&
			p.tokens[p.pos].Type != TokenEOF {
			if p.tokens[p.pos].Type == TokenPunctuation {
				p.pos++
				continue
			}
			sources = append(sources, &ASTNode{
				NodeType: "TABLE",
				Value:    p.tokens[p.pos].Value,
			})
			p.pos++
		}
		node.Children = append(node.Children, &ASTNode{
			NodeType: "FROM_CLAUSE",
			Children: sources,
		})
	}

	if p.pos < len(p.tokens) && p.tokens[p.pos].Value == "WHERE" {
		p.pos++
		conditions := make([]*ASTNode, 0)
		for p.pos < len(p.tokens) && p.tokens[p.pos].Value != "GROUP" &&
			p.tokens[p.pos].Value != "WINDOW" && p.tokens[p.pos].Type != TokenEOF {
			conditions = append(conditions, &ASTNode{
				NodeType: "CONDITION",
				Value:    p.tokens[p.pos].Value,
			})
			p.pos++
		}
		node.Children = append(node.Children, &ASTNode{
			NodeType: "WHERE_CLAUSE",
			Children: conditions,
		})
	}

	if p.pos < len(p.tokens) && p.tokens[p.pos].Value == "GROUP" {
		p.pos += 2
		groupings := make([]*ASTNode, 0)
		for p.pos < len(p.tokens) && p.tokens[p.pos].Value != "WINDOW" && p.tokens[p.pos].Type != TokenEOF {
			if p.tokens[p.pos].Type == TokenPunctuation {
				p.pos++
				continue
			}
			groupings = append(groupings, &ASTNode{
				NodeType: "GROUPING",
				Value:    p.tokens[p.pos].Value,
			})
			p.pos++
		}
		node.Children = append(node.Children, &ASTNode{
			NodeType: "GROUP_BY",
			Children: groupings,
		})
	}

	return node, nil
}

func (p *SQLParser) parseInsert() (*ASTNode, error) {
	node := &ASTNode{
		NodeType: "INSERT_STATEMENT",
		Children: make([]*ASTNode, 0),
	}

	p.pos += 2

	targetTable := p.tokens[p.pos].Value
	node.Children = append(node.Children, &ASTNode{
		NodeType: "TARGET_TABLE",
		Value:    targetTable,
	})

	p.pos++

	if p.tokens[p.pos].Type == TokenPunctuation && p.tokens[p.pos].Value == "(" {
		p.pos++
		columns := make([]*ASTNode, 0)
		for p.tokens[p.pos].Value != ")" {
			if p.tokens[p.pos].Type == TokenPunctuation {
				p.pos++
				continue
			}
			columns = append(columns, &ASTNode{
				NodeType: "COLUMN",
				Value:    p.tokens[p.pos].Value,
			})
			p.pos++
		}
		node.Children = append(node.Children, &ASTNode{
			NodeType: "COLUMNS",
			Children: columns,
		})
		p.pos++
	}

	return node, nil
}

type LogicalPlanBuilder struct{}

func NewLogicalPlanBuilder() *LogicalPlanBuilder {
	return &LogicalPlanBuilder{}
}

func (b *LogicalPlanBuilder) Build(ast *ASTNode) (*LogicalPlan, error) {
	logger.Info("building logical plan from AST")

	plan := &LogicalPlan{
		SourceTables: make([]string, 0),
		Conditions:   make([]string, 0),
		Projections:  make([]string, 0),
		Aggregations: make([]string, 0),
	}

	b.extractFromAST(ast, plan)

	plan = b.optimize(plan)

	return plan, nil
}

func (b *LogicalPlanBuilder) extractFromAST(node *ASTNode, plan *LogicalPlan) {
	if node == nil {
		return
	}

	switch node.NodeType {
	case "SELECT_STATEMENT":
		plan.Operator = "SELECT"
	case "INSERT_STATEMENT":
		plan.Operator = "INSERT"
	case "PROJECTION":
		plan.Projections = append(plan.Projections, node.Value)
	case "TABLE":
		plan.SourceTables = append(plan.SourceTables, node.Value)
	case "TARGET_TABLE":
		plan.TargetTable = node.Value
	case "CONDITION":
		plan.Conditions = append(plan.Conditions, node.Value)
	case "GROUPING":
		plan.Aggregations = append(plan.Aggregations, node.Value)
	}

	for _, child := range node.Children {
		b.extractFromAST(child, plan)
	}
}

func (b *LogicalPlanBuilder) optimize(plan *LogicalPlan) *LogicalPlan {
	logger.Info("applying logical plan optimizations")

	optimized := &LogicalPlan{
		Operator:     plan.Operator,
		SourceTables: b.deduplicate(plan.SourceTables),
		TargetTable:  plan.TargetTable,
		Conditions:   b.deduplicate(plan.Conditions),
		Projections:  b.deduplicate(plan.Projections),
		Aggregations: b.deduplicate(plan.Aggregations),
		WindowSpec:   plan.WindowSpec,
	}

	return optimized
}

func (b *LogicalPlanBuilder) deduplicate(items []string) []string {
	seen := make(map[string]bool)
	result := make([]string, 0)

	for _, item := range items {
		if !seen[item] {
			seen[item] = true
			result = append(result, item)
		}
	}

	return result
}

type PhysicalPlanTranslator struct{}

func NewPhysicalPlanTranslator() *PhysicalPlanTranslator {
	return &PhysicalPlanTranslator{}
}

func (t *PhysicalPlanTranslator) Translate(logicalPlan *LogicalPlan) (*PhysicalPlan, error) {
	logger.Info("translating logical plan to physical plan")

	physicalPlan := &PhysicalPlan{
		Stages:        make([]*ExecutionStage, 0),
		Optimizations: make([]string, 0),
	}

	stageID := 0

	if len(logicalPlan.Conditions) > 0 {
		physicalPlan.Stages = append(physicalPlan.Stages, &ExecutionStage{
			ID:          t.generateStageID(stageID),
			Operation:   "FILTER",
			Parallelism: 4,
			Resources:   map[string]int64{"cpu": 2, "memory": 1024},
		})
		stageID++
	}

	if len(logicalPlan.Projections) > 0 {
		physicalPlan.Stages = append(physicalPlan.Stages, &ExecutionStage{
			ID:          t.generateStageID(stageID),
			Operation:   "PROJECT",
			Parallelism: 8,
			Resources:   map[string]int64{"cpu": 1, "memory": 512},
		})
		stageID++
	}

	if len(logicalPlan.Aggregations) > 0 {
		physicalPlan.Stages = append(physicalPlan.Stages, &ExecutionStage{
			ID:          t.generateStageID(stageID),
			Operation:   "AGGREGATE",
			Parallelism: 4,
			Resources:   map[string]int64{"cpu": 4, "memory": 2048},
		})
		stageID++
	}

	physicalPlan.Optimizations = []string{
		"PredicatePushdown",
		"ColumnPruning",
		"PartitionAwareExecution",
	}

	physicalPlan.EstimatedCost = float64(len(physicalPlan.Stages) * 100)

	return physicalPlan, nil
}

func (t *PhysicalPlanTranslator) generateStageID(id int) string {
	return "stage_" + string(rune('0'+id))
}

type StreamingQueryEngine struct {
	parser     *SQLParser
	planBuilder *LogicalPlanBuilder
	translator  *PhysicalPlanTranslator
}

func NewStreamingQueryEngine() *StreamingQueryEngine {
	return &StreamingQueryEngine{
		parser:      NewSQLParser(),
		planBuilder: NewLogicalPlanBuilder(),
		translator:  NewPhysicalPlanTranslator(),
	}
}

func (e *StreamingQueryEngine) Execute(sql string) (*PhysicalPlan, error) {
	logger.Info("executing streaming query", zap.String("sql", sql))

	ast, err := e.parser.Parse(sql)
	if err != nil {
		return nil, err
	}

	logicalPlan, err := e.planBuilder.Build(ast)
	if err != nil {
		return nil, err
	}

	physicalPlan, err := e.translator.Translate(logicalPlan)
	if err != nil {
		return nil, err
	}

	return physicalPlan, nil
}
