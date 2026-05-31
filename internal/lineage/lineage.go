package lineage

import (
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/common"
	"github.com/datatrace/datatrace/internal/models"
)

type TableNode struct {
	Name      string
	Database  string
	Fields    []string
	Timestamp time.Time
}

type FieldLineage struct {
	SourceTable string
	SourceField string
	TargetTable string
	TargetField string
	Transform   string
}

type DAGNode struct {
	ID       string
	Table    string
	Database string
	Fields   []string
	InEdges  []string
	OutEdges []string
}

type DAGGraph struct {
	nodes map[string]*DAGNode
	mu    sync.RWMutex
}

type SQLParser interface {
	Parse(sql string) ([]TableNode, []FieldLineage, error)
}

type RegexSQLParser struct {
	regexCache map[string]*regexp.Regexp
	cacheMu    sync.RWMutex
}

func NewRegexSQLParser() *RegexSQLParser {
	return &RegexSQLParser{
		regexCache: make(map[string]*regexp.Regexp),
	}
}

func (p *RegexSQLParser) getCompiledRegex(pattern string) *regexp.Regexp {
	p.cacheMu.RLock()
	if re, ok := p.regexCache[pattern]; ok {
		p.cacheMu.RUnlock()
		return re
	}
	p.cacheMu.RUnlock()

	p.cacheMu.Lock()
	defer p.cacheMu.Unlock()

	if re, ok := p.regexCache[pattern]; ok {
		return re
	}
	re := regexp.MustCompile(pattern)
	p.regexCache[pattern] = re
	return re
}

func (p *RegexSQLParser) Parse(sql string) ([]TableNode, []FieldLineage, error) {
	sql = strings.TrimSpace(sql)
	if sql == "" {
		return nil, nil, common.WrapError(common.CodeInvalidInput, "sql cannot be empty", nil)
	}

	tables := make([]TableNode, 0)
	lineages := make([]FieldLineage, 0)

	sourceTables := p.extractSourceTables(sql)
	targetTable := p.extractTargetTable(sql)

	for _, t := range sourceTables {
		db, tbl := parseTableName(t)
		tables = append(tables, TableNode{
			Name:      tbl,
			Database:  db,
			Fields:    p.extractFields(sql, t),
			Timestamp: time.Now(),
		})
	}

	if targetTable != "" {
		db, tbl := parseTableName(targetTable)
		targetNode := TableNode{
			Name:      tbl,
			Database:  db,
			Fields:    p.extractFields(sql, targetTable),
			Timestamp: time.Now(),
		}
		tables = append(tables, targetNode)

		for _, src := range sourceTables {
			_, srcTbl := parseTableName(src)
			lineages = append(lineages, FieldLineage{
				SourceTable: srcTbl,
				TargetTable: tbl,
				Transform:   "ETL",
			})
		}
	}

	return tables, lineages, nil
}

func (p *RegexSQLParser) extractSourceTables(sql string) []string {
	tables := make([]string, 0)
	fromRegex := p.getCompiledRegex(`(?i)FROM\s+([a-zA-Z0-9_\.]+)`)
	joinRegex := p.getCompiledRegex(`(?i)JOIN\s+([a-zA-Z0-9_\.]+)`)

	matches := fromRegex.FindAllStringSubmatch(sql, -1)
	for _, m := range matches {
		tables = append(tables, m[1])
	}

	matches = joinRegex.FindAllStringSubmatch(sql, -1)
	for _, m := range matches {
		tables = append(tables, m[1])
	}

	return tables
}

func (p *RegexSQLParser) extractTargetTable(sql string) string {
	patterns := []string{
		`(?i)INSERT\s+INTO\s+([a-zA-Z0-9_\.]+)`,
		`(?i)UPDATE\s+([a-zA-Z0-9_\.]+)`,
		`(?i)CREATE\s+TABLE\s+([a-zA-Z0-9_\.]+)`,
	}

	for _, pattern := range patterns {
		re := p.getCompiledRegex(pattern)
		if matches := re.FindStringSubmatch(sql); len(matches) > 1 {
			return matches[1]
		}
	}
	return ""
}

func (p *RegexSQLParser) extractFields(sql, tableName string) []string {
	fields := make([]string, 0)
	selectRegex := p.getCompiledRegex(`(?i)SELECT\s+(.*?)\s+FROM`)
	matches := selectRegex.FindStringSubmatch(sql)
	if len(matches) <= 1 {
		return fields
	}

	fieldStr := matches[1]
	fieldParts := strings.Split(fieldStr, ",")
	for _, f := range fieldParts {
		f = strings.TrimSpace(f)
		if f == "*" {
			continue
		}
		aliasParts := strings.Split(f, " ")
		if len(aliasParts) > 0 {
			fields = append(fields, strings.TrimSpace(aliasParts[0]))
		}
	}
	return fields
}

func parseTableName(name string) (string, string) {
	parts := strings.Split(name, ".")
	if len(parts) == 2 {
		return parts[0], parts[1]
	}
	return "default", name
}

type LineageParser struct {
	parser SQLParser
	graph  *DAGGraph
	mu     sync.RWMutex
}

func NewLineageParser() *LineageParser {
	return &LineageParser{
		parser: NewRegexSQLParser(),
		graph: &DAGGraph{
			nodes: make(map[string]*DAGNode),
		},
	}
}

func NewLineageParserWithParser(parser SQLParser) *LineageParser {
	return &LineageParser{
		parser: parser,
		graph: &DAGGraph{
			nodes: make(map[string]*DAGNode),
		},
	}
}

func (lp *LineageParser) ParseSQL(sql string) ([]TableNode, []FieldLineage, error) {
	return lp.parser.Parse(sql)
}

func (lp *LineageParser) BuildDAG(tables []TableNode, lineages []FieldLineage) (*DAGGraph, error) {
	lp.graph.mu.Lock()
	defer lp.graph.mu.Unlock()

	for _, table := range tables {
		nodeID := fmt.Sprintf("%s.%s", table.Database, table.Name)
		if _, exists := lp.graph.nodes[nodeID]; !exists {
			lp.graph.nodes[nodeID] = &DAGNode{
				ID:       nodeID,
				Table:    table.Name,
				Database: table.Database,
				Fields:   table.Fields,
			}
		}
	}

	for _, lineage := range lineages {
		srcID := fmt.Sprintf("default.%s", lineage.SourceTable)
		tgtID := fmt.Sprintf("default.%s", lineage.TargetTable)

		if srcNode, ok := lp.graph.nodes[srcID]; ok {
			srcNode.OutEdges = append(srcNode.OutEdges, tgtID)
		}
		if tgtNode, ok := lp.graph.nodes[tgtID]; ok {
			tgtNode.InEdges = append(tgtNode.InEdges, srcID)
		}
	}

	return lp.graph, nil
}

func (lp *LineageParser) GetLineage(tableName string) (node *DAGNode, upstream []*DAGNode, downstream []*DAGNode) {
	lp.graph.mu.RLock()
	defer lp.graph.mu.RUnlock()

	nodeID := fmt.Sprintf("default.%s", tableName)
	node, ok := lp.graph.nodes[nodeID]
	if !ok {
		return nil, nil, nil
	}

	upstream = make([]*DAGNode, 0, len(node.InEdges))
	for _, inID := range node.InEdges {
		if n, exists := lp.graph.nodes[inID]; exists {
			upstream = append(upstream, n)
		}
	}

	downstream = make([]*DAGNode, 0, len(node.OutEdges))
	for _, outID := range node.OutEdges {
		if n, exists := lp.graph.nodes[outID]; exists {
			downstream = append(downstream, n)
		}
	}

	return node, upstream, downstream
}

func (lp *LineageParser) ToEntity() *models.Entity {
	return common.NewEntity("lineage_dag")
}

func (g *DAGGraph) GetNodes() []*DAGNode {
	g.mu.RLock()
	defer g.mu.RUnlock()

	nodes := make([]*DAGNode, 0, len(g.nodes))
	for _, n := range g.nodes {
		nodes = append(nodes, n)
	}
	return nodes
}

func (g *DAGGraph) TopologicalSort() ([]string, error) {
	g.mu.RLock()
	defer g.mu.RUnlock()

	inDegree := make(map[string]int, len(g.nodes))
	for id := range g.nodes {
		inDegree[id] = 0
	}
	for _, node := range g.nodes {
		for _, outID := range node.OutEdges {
			inDegree[outID]++
		}
	}

	queue := make([]string, 0)
	for id, deg := range inDegree {
		if deg == 0 {
			queue = append(queue, id)
		}
	}

	result := make([]string, 0, len(g.nodes))
	for len(queue) > 0 {
		curr := queue[0]
		queue = queue[1:]
		result = append(result, curr)

		node, ok := g.nodes[curr]
		if !ok {
			continue
		}
		for _, outID := range node.OutEdges {
			inDegree[outID]--
			if inDegree[outID] == 0 {
				queue = append(queue, outID)
			}
		}
	}

	if len(result) != len(g.nodes) {
		return nil, fmt.Errorf("cycle detected in DAG")
	}

	return result, nil
}

func (g *DAGGraph) NodeCount() int {
	g.mu.RLock()
	defer g.mu.RUnlock()
	return len(g.nodes)
}

func (g *DAGGraph) EdgeCount() int {
	g.mu.RLock()
	defer g.mu.RUnlock()

	count := 0
	for _, node := range g.nodes {
		count += len(node.OutEdges)
	}
	return count
}
