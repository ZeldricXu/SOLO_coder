package lineage

import (
	"regexp"
	"strings"
	"sync"

	"github.com/datatransform/platform/pkg/logger"
	"go.uber.org/zap"
)

type TableNode struct {
	Name        string
	Columns     []ColumnNode
	InEdges     []*TableNode
	OutEdges    []*TableNode
	Metadata    map[string]interface{}
}

type ColumnNode struct {
	Name     string
	Table    *TableNode
	InEdges  []*ColumnNode
	OutEdges []*ColumnNode
}

type DAGGraph struct {
	Tables    map[string]*TableNode
	Edges     []*LineageEdge
	ColumnMap map[string]map[string]*ColumnNode
	mu        sync.RWMutex
}

type LineageEdge struct {
	Source      string
	SourceColumn string
	Target      string
	TargetColumn string
	Transform   string
}

type LineageResult struct {
	SourceTables []string
	TargetTable  string
	ColumnLineage map[string][]string
	Graph        *DAGGraph
}

type LineageParser struct {
	mu sync.Mutex
}

func NewLineageParser() *LineageParser {
	return &LineageParser{}
}

func (p *LineageParser) ParseSQL(sql string) (*LineageResult, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	logger.Info("parsing SQL for data lineage", zap.String("sql_preview", sql[:min(len(sql), 100)]))

	result := &LineageResult{
		SourceTables:  make([]string, 0),
		ColumnLineage: make(map[string][]string),
		Graph: &DAGGraph{
			Tables:    make(map[string]*TableNode),
			Edges:     make([]*LineageEdge, 0),
			ColumnMap: make(map[string]map[string]*ColumnNode),
		},
	}

	sqlUpper := strings.ToUpper(sql)

	result.TargetTable = p.extractTargetTable(sqlUpper)
	result.SourceTables = p.extractSourceTables(sqlUpper)

	columnLineage := p.extractColumnLineage(sql)
	for target, sources := range columnLineage {
		result.ColumnLineage[target] = sources
	}

	p.buildGraph(result)

	return result, nil
}

func (p *LineageParser) extractTargetTable(sql string) string {
	insertMatch := regexp.MustCompile(`INSERT\s+INTO\s+([^\s(]+)`).FindStringSubmatch(sql)
	if len(insertMatch) > 1 {
		return strings.TrimSpace(insertMatch[1])
	}

	cteMatch := regexp.MustCompile(`CREATE\s+(?:OR\s+REPLACE\s+)?(?:TEMP\s+)?(?:TABLE|VIEW)\s+([^\s(]+)`).FindStringSubmatch(sql)
	if len(cteMatch) > 1 {
		return strings.TrimSpace(cteMatch[1])
	}

	updateMatch := regexp.MustCompile(`UPDATE\s+([^\s]+)`).FindStringSubmatch(sql)
	if len(updateMatch) > 1 {
		return strings.TrimSpace(updateMatch[1])
	}

	return ""
}

func (p *LineageParser) extractSourceTables(sql string) []string {
	tables := make([]string, 0)
	seen := make(map[string]bool)

	fromPattern := regexp.MustCompile(`FROM\s+([^\s,;)(]+)(?:\s+(?:AS\s+)?([^\s,;)(]+))?`)
	matches := fromPattern.FindAllStringSubmatch(sql, -1)

	for _, match := range matches {
		tableName := strings.TrimSpace(match[1])
		if !seen[tableName] && tableName != "" && !isKeyword(tableName) {
			seen[tableName] = true
			tables = append(tables, tableName)
		}
	}

	joinPattern := regexp.MustCompile(`JOIN\s+([^\s,;)(]+)(?:\s+(?:AS\s+)?([^\s,;)(]+))?`)
	joinMatches := joinPattern.FindAllStringSubmatch(sql, -1)

	for _, match := range joinMatches {
		tableName := strings.TrimSpace(match[1])
		if !seen[tableName] && tableName != "" && !isKeyword(tableName) {
			seen[tableName] = true
			tables = append(tables, tableName)
		}
	}

	return tables
}

func isKeyword(word string) bool {
	keywords := map[string]bool{
		"SELECT": true, "FROM": true, "WHERE": true, "JOIN": true,
		"INNER": true, "LEFT": true, "RIGHT": true, "OUTER": true,
		"ON": true, "AS": true, "AND": true, "OR": true, "NOT": true,
		"GROUP": true, "BY": true, "HAVING": true, "ORDER": true,
		"LIMIT": true, "INSERT": true, "INTO": true, "VALUES": true,
		"UPDATE": true, "SET": true, "DELETE": true, "CREATE": true,
		"TABLE": true, "VIEW": true, "WITH": true, "UNION": true,
		"ALL": true, "DISTINCT": true, "NULL": true, "IS": true,
	}
	return keywords[strings.ToUpper(word)]
}

func (p *LineageParser) extractColumnLineage(sql string) map[string][]string {
	lineage := make(map[string][]string)

	selectPattern := regexp.MustCompile(`SELECT\s+(.+?)\s+FROM`)
	matches := selectPattern.FindStringSubmatch(strings.ToUpper(sql))

	if len(matches) > 1 {
		projections := splitProjections(matches[1])

		for _, proj := range projections {
			proj = strings.TrimSpace(proj)

			aliasParts := strings.Split(proj, " AS ")
			var columnName, expression string

			if len(aliasParts) > 1 {
				columnName = strings.TrimSpace(aliasParts[len(aliasParts)-1])
				expression = strings.TrimSpace(strings.Join(aliasParts[:len(aliasParts)-1], " AS "))
			} else {
				parts := strings.Fields(proj)
				if len(parts) > 1 {
					columnName = parts[len(parts)-1]
					expression = strings.Join(parts[:len(parts)-1], " ")
				} else {
					columnName = proj
					expression = proj
				}
			}

			sourceColumns := p.extractColumnReferences(expression)
			if columnName != "" {
				lineage[columnName] = sourceColumns
			}
		}
	}

	return lineage
}

func (p *LineageParser) extractColumnReferences(expression string) []string {
	references := make([]string, 0)
	seen := make(map[string]bool)

	columnPattern := regexp.MustCompile(`([a-zA-Z_][a-zA-Z0-9_]*)\.([a-zA-Z_][a-zA-Z0-9_]*)`)
	matches := columnPattern.FindAllStringSubmatch(expression, -1)

	for _, match := range matches {
		if len(match) > 2 {
			colRef := match[1] + "." + match[2]
			if !seen[colRef] {
				seen[colRef] = true
				references = append(references, colRef)
			}
		}
	}

	if len(references) == 0 {
		simplePattern := regexp.MustCompile(`\b([a-zA-Z_][a-zA-Z0-9_]*)\b`)
		simpleMatches := simplePattern.FindAllStringSubmatch(expression, -1)

		for _, match := range simpleMatches {
			word := match[1]
			if !isKeyword(word) && !isFunction(word) && !seen[word] {
				seen[word] = true
				references = append(references, word)
			}
		}
	}

	return references
}

func isFunction(word string) bool {
	functions := map[string]bool{
		"COUNT": true, "SUM": true, "AVG": true, "MIN": true, "MAX": true,
		"COALESCE": true, "IFNULL": true, "NULLIF": true, "CAST": true,
		"CONCAT": true, "SUBSTRING": true, "TRIM": true, "UPPER": true,
		"LOWER": true, "ROUND": true, "FLOOR": true, "CEIL": true,
		"NOW": true, "CURRENT_DATE": true, "CURRENT_TIME": true,
	}
	return functions[strings.ToUpper(word)]
}

func splitProjections(projections string) []string {
	result := make([]string, 0)
	var current strings.Builder
	parenLevel := 0

	for _, char := range projections {
		switch char {
		case '(':
			parenLevel++
			current.WriteRune(char)
		case ')':
			parenLevel--
			current.WriteRune(char)
		case ',':
			if parenLevel == 0 {
				result = append(result, current.String())
				current.Reset()
			} else {
				current.WriteRune(char)
			}
		default:
			current.WriteRune(char)
		}
	}

	if current.Len() > 0 {
		result = append(result, current.String())
	}

	return result
}

func (p *LineageParser) buildGraph(result *LineageResult) {
	graph := result.Graph
	graph.mu.Lock()
	defer graph.mu.Unlock()

	for _, tableName := range result.SourceTables {
		if _, exists := graph.Tables[tableName]; !exists {
			graph.Tables[tableName] = &TableNode{
				Name:     tableName,
				Columns:  make([]ColumnNode, 0),
				InEdges:  make([]*TableNode, 0),
				OutEdges: make([]*TableNode, 0),
				Metadata: make(map[string]interface{}),
			}
			graph.ColumnMap[tableName] = make(map[string]*ColumnNode)
		}
	}

	if result.TargetTable != "" {
		if _, exists := graph.Tables[result.TargetTable]; !exists {
			graph.Tables[result.TargetTable] = &TableNode{
				Name:     result.TargetTable,
				Columns:  make([]ColumnNode, 0),
				InEdges:  make([]*TableNode, 0),
				OutEdges: make([]*TableNode, 0),
				Metadata: make(map[string]interface{}),
			}
			graph.ColumnMap[result.TargetTable] = make(map[string]*ColumnNode)
		}

		targetNode := graph.Tables[result.TargetTable]
		for _, sourceName := range result.SourceTables {
			sourceNode := graph.Tables[sourceName]
			if sourceNode != nil {
				sourceNode.OutEdges = append(sourceNode.OutEdges, targetNode)
				targetNode.InEdges = append(targetNode.InEdges, sourceNode)

				graph.Edges = append(graph.Edges, &LineageEdge{
					Source: sourceName,
					Target: result.TargetTable,
				})
			}
		}
	}

	for targetCol, sourceCols := range result.ColumnLineage {
		for _, sourceCol := range sourceCols {
			graph.Edges = append(graph.Edges, &LineageEdge{
				SourceColumn: sourceCol,
				TargetColumn: targetCol,
			})
		}
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (g *DAGGraph) GetTableNode(name string) (*TableNode, bool) {
	g.mu.RLock()
	defer g.mu.RUnlock()
	node, exists := g.Tables[name]
	return node, exists
}

func (g *DAGGraph) GetAllTables() []string {
	g.mu.RLock()
	defer g.mu.RUnlock()

	tables := make([]string, 0, len(g.Tables))
	for name := range g.Tables {
		tables = append(tables, name)
	}
	return tables
}

func (g *DAGGraph) GetUpstreamTables(tableName string) []string {
	g.mu.RLock()
	defer g.mu.RUnlock()

	node, exists := g.Tables[tableName]
	if !exists {
		return []string{}
	}

	upstream := make([]string, 0)
	for _, edge := range node.InEdges {
		upstream = append(upstream, edge.Name)
	}
	return upstream
}

func (g *DAGGraph) GetDownstreamTables(tableName string) []string {
	g.mu.RLock()
	defer g.mu.RUnlock()

	node, exists := g.Tables[tableName]
	if !exists {
		return []string{}
	}

	downstream := make([]string, 0)
	for _, edge := range node.OutEdges {
		downstream = append(downstream, edge.Name)
	}
	return downstream
}

func (g *DAGGraph) TopologicalSort() []string {
	g.mu.RLock()
	defer g.mu.RUnlock()

	inDegree := make(map[string]int)
	for name := range g.Tables {
		inDegree[name] = 0
	}

	for _, node := range g.Tables {
		for _, outEdge := range node.OutEdges {
			inDegree[outEdge.Name]++
		}
	}

	queue := make([]string, 0)
	for name, degree := range inDegree {
		if degree == 0 {
			queue = append(queue, name)
		}
	}

	result := make([]string, 0)
	for len(queue) > 0 {
		node := queue[0]
		queue = queue[1:]
		result = append(result, node)

		if tableNode, exists := g.Tables[node]; exists {
			for _, outEdge := range tableNode.OutEdges {
				inDegree[outEdge.Name]--
				if inDegree[outEdge.Name] == 0 {
					queue = append(queue, outEdge.Name)
				}
			}
		}
	}

	return result
}

func (g *DAGGraph) HasCycle() bool {
	g.mu.RLock()
	defer g.mu.RUnlock()

	visited := make(map[string]bool)
	recursionStack := make(map[string]bool)

	for name := range g.Tables {
		if g.detectCycle(name, visited, recursionStack) {
			return true
		}
	}
	return false
}

func (g *DAGGraph) detectCycle(node string, visited map[string]bool, recursionStack map[string]bool) bool {
	if recursionStack[node] {
		return true
	}
	if visited[node] {
		return false
	}

	visited[node] = true
	recursionStack[node] = true

	if tableNode, exists := g.Tables[node]; exists {
		for _, outEdge := range tableNode.OutEdges {
			if g.detectCycle(outEdge.Name, visited, recursionStack) {
				return true
			}
		}
	}

	recursionStack[node] = false
	return false
}
