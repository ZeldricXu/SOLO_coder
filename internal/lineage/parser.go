package lineage

import (
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/xwb1989/sqlparser"
	"streamsql/internal/common/logger"
)

type ColumnLineage struct {
	SourceTable  string `json:"source_table"`
	SourceColumn string `json:"source_column"`
	TargetTable  string `json:"target_table"`
	TargetColumn string `json:"target_column"`
	Transform    string `json:"transform,omitempty"`
}

type TableLineage struct {
	ID            string          `json:"id"`
	SourceTables  []string        `json:"source_tables"`
	TargetTable   string          `json:"target_table"`
	Columns       []ColumnLineage `json:"columns"`
	SQL           string          `json:"sql"`
	OperationType string          `json:"operation_type"`
	CreatedAt     time.Time       `json:"created_at"`
	Metadata      map[string]interface{} `json:"metadata,omitempty"`
}

type LineageNode struct {
	ID       string                 `json:"id"`
	Name     string                 `json:"name"`
	Type     string                 `json:"type"`
	Metadata map[string]interface{} `json:"metadata,omitempty"`
}

type LineageEdge struct {
	ID         string                 `json:"id"`
	SourceID   string                 `json:"source_id"`
	TargetID   string                 `json:"target_id"`
	Relation   string                 `json:"relation"`
	Columns    []ColumnLineage        `json:"columns,omitempty"`
	Metadata   map[string]interface{} `json:"metadata,omitempty"`
}

type LineageDAG struct {
	Nodes map[string]*LineageNode `json:"nodes"`
	Edges map[string]*LineageEdge `json:"edges"`
	mu    sync.RWMutex
}

func NewLineageDAG() *LineageDAG {
	return &LineageDAG{
		Nodes: make(map[string]*LineageNode),
		Edges: make(map[string]*LineageEdge),
	}
}

func (dag *LineageDAG) AddNode(node *LineageNode) string {
	dag.mu.Lock()
	defer dag.mu.Unlock()

	if node.ID == "" {
		node.ID = uuid.New().String()
	}
	dag.Nodes[node.ID] = node
	return node.ID
}

func (dag *LineageDAG) AddEdge(edge *LineageEdge) string {
	dag.mu.Lock()
	defer dag.mu.Unlock()

	if edge.ID == "" {
		edge.ID = uuid.New().String()
	}
	dag.Edges[edge.ID] = edge
	return edge.ID
}

func (dag *LineageDAG) GetNode(id string) (*LineageNode, bool) {
	dag.mu.RLock()
	defer dag.mu.RUnlock()
	node, exists := dag.Nodes[id]
	return node, exists
}

func (dag *LineageDAG) GetEdge(id string) (*LineageEdge, bool) {
	dag.mu.RLock()
	defer dag.mu.RUnlock()
	edge, exists := dag.Edges[id]
	return edge, exists
}

func (dag *LineageDAG) FindNodeByName(name string) (*LineageNode, bool) {
	dag.mu.RLock()
	defer dag.mu.RUnlock()

	for _, node := range dag.Nodes {
		if node.Name == name {
			return node, true
		}
	}
	return nil, false
}

func (dag *LineageDAG) GetUpstream(nodeID string) []*LineageNode {
	dag.mu.RLock()
	defer dag.mu.RUnlock()

	var upstream []*LineageNode
	for _, edge := range dag.Edges {
		if edge.TargetID == nodeID {
			if node, exists := dag.Nodes[edge.SourceID]; exists {
				upstream = append(upstream, node)
			}
		}
	}
	return upstream
}

func (dag *LineageDAG) GetDownstream(nodeID string) []*LineageNode {
	dag.mu.RLock()
	defer dag.mu.RUnlock()

	var downstream []*LineageNode
	for _, edge := range dag.Edges {
		if edge.SourceID == nodeID {
			if node, exists := dag.Nodes[edge.TargetID]; exists {
				downstream = append(downstream, node)
			}
		}
	}
	return downstream
}

func (dag *LineageDAG) GetAllUpstream(nodeID string) []*LineageNode {
	visited := make(map[string]bool)
	var result []*LineageNode

	var dfs func(id string)
	dfs = func(id string) {
		if visited[id] {
			return
		}
		visited[id] = true

		upstream := dag.GetUpstream(id)
		for _, node := range upstream {
			if !visited[node.ID] {
				result = append(result, node)
				dfs(node.ID)
			}
		}
	}

	dfs(nodeID)
	return result
}

func (dag *LineageDAG) GetAllDownstream(nodeID string) []*LineageNode {
	visited := make(map[string]bool)
	var result []*LineageNode

	var dfs func(id string)
	dfs = func(id string) {
		if visited[id] {
			return
		}
		visited[id] = true

		downstream := dag.GetDownstream(id)
		for _, node := range downstream {
			if !visited[node.ID] {
				result = append(result, node)
				dfs(node.ID)
			}
		}
	}

	dfs(nodeID)
	return result
}

func (dag *LineageDAG) GetEdgesBetween(sourceID, targetID string) []*LineageEdge {
	dag.mu.RLock()
	defer dag.mu.RUnlock()

	var edges []*LineageEdge
	for _, edge := range dag.Edges {
		if edge.SourceID == sourceID && edge.TargetID == targetID {
			edges = append(edges, edge)
		}
	}
	return edges
}

func (dag *LineageDAG) ListNodes() []*LineageNode {
	dag.mu.RLock()
	defer dag.mu.RUnlock()

	nodes := make([]*LineageNode, 0, len(dag.Nodes))
	for _, node := range dag.Nodes {
		nodes = append(nodes, node)
	}
	return nodes
}

func (dag *LineageDAG) ListEdges() []*LineageEdge {
	dag.mu.RLock()
	defer dag.mu.RUnlock()

	edges := make([]*LineageEdge, 0, len(dag.Edges))
	for _, edge := range dag.Edges {
		edges = append(edges, edge)
	}
	return edges
}

type SQLLineageParser struct{}

func NewSQLLineageParser() *SQLLineageParser {
	return &SQLLineageParser{}
}

func (p *SQLLineageParser) Parse(sql string) (*TableLineage, error) {
	stmt, err := sqlparser.Parse(sql)
	if err != nil {
		return nil, fmt.Errorf("failed to parse SQL: %w", err)
	}

	lineage := &TableLineage{
		ID:        uuid.New().String(),
		SQL:       sql,
		CreatedAt: time.Now().UTC(),
		Columns:   make([]ColumnLineage, 0),
	}

	switch s := stmt.(type) {
	case *sqlparser.Select:
		lineage.OperationType = "SELECT"
		p.extractSelectLineage(s, lineage)
	case *sqlparser.Insert:
		lineage.OperationType = "INSERT"
		p.extractInsertLineage(s, lineage)
	case *sqlparser.Update:
		lineage.OperationType = "UPDATE"
		p.extractUpdateLineage(s, lineage)
	case *sqlparser.CreateTable:
		lineage.OperationType = "CREATE_TABLE_AS"
		p.extractCreateTableLineage(s, lineage)
	default:
		lineage.OperationType = fmt.Sprintf("%T", stmt)
	}

	return lineage, nil
}

func (p *SQLLineageParser) extractSelectLineage(stmt *sqlparser.Select, lineage *TableLineage) {
	sourceTables := make(map[string]bool)

	for _, from := range stmt.From {
		if aliased, ok := from.(*sqlparser.AliasedTableExpr); ok {
			if tableName, ok := aliased.Expr.(sqlparser.TableName); ok {
				table := tableName.Name.CompliantName()
				sourceTables[table] = true
			}
		}
	}

	for table := range sourceTables {
		lineage.SourceTables = append(lineage.SourceTables, table)
	}

	for _, expr := range stmt.SelectExprs {
		if aliased, ok := expr.(*sqlparser.AliasedExpr) {
			targetCol := aliased.As.CompliantName()
			if targetCol == "" {
				if col, ok := aliased.Expr.(*sqlparser.ColName); ok {
					targetCol = col.Name.CompliantName()
				}
			}

			if col, ok := aliased.Expr.(*sqlparser.ColName); ok {
				sourceCol := col.Name.CompliantName()
				sourceTable := ""
				if !col.Qualifier.IsEmpty() {
					sourceTable = col.Qualifier.Name.CompliantName()
				}

				if sourceTable == "" && len(lineage.SourceTables) == 1 {
					sourceTable = lineage.SourceTables[0]
				}

				lineage.Columns = append(lineage.Columns, ColumnLineage{
					SourceTable:  sourceTable,
					SourceColumn: sourceCol,
					TargetColumn: targetCol,
				})
			} else {
				buf := sqlparser.NewTrackedBuffer(nil)
				aliased.Expr.Format(buf)
				lineage.Columns = append(lineage.Columns, ColumnLineage{
					TargetColumn: targetCol,
					Transform:    buf.String(),
				})
			}
		}
	}
}

func (p *SQLLineageParser) extractInsertLineage(stmt *sqlparser.Insert, lineage *TableLineage) {
	lineage.TargetTable = stmt.Table.Name.CompliantName()

	if selectStmt, ok := stmt.Rows.(sqlparser.SelectStatement); ok {
		if sel, ok := selectStmt.(*sqlparser.Select); ok {
			p.extractSelectLineage(sel, lineage)
		}
	}
}

func (p *SQLLineageParser) extractUpdateLineage(stmt *sqlparser.Update, lineage *TableLineage) {
	lineage.TargetTable = stmt.TableExprs[0].(*sqlparser.AliasedTableExpr).Expr.(sqlparser.TableName).Name.CompliantName()
	lineage.SourceTables = append(lineage.SourceTables, lineage.TargetTable)

	for _, expr := range stmt.Exprs {
		colName := expr.Name.Name.CompliantName()
		buf := sqlparser.NewTrackedBuffer(nil)
		expr.Expr.Format(buf)

		lineage.Columns = append(lineage.Columns, ColumnLineage{
			SourceTable:  lineage.TargetTable,
			SourceColumn: colName,
			TargetTable:  lineage.TargetTable,
			TargetColumn: colName,
			Transform:    buf.String(),
		})
	}
}

func (p *SQLLineageParser) extractCreateTableLineage(stmt *sqlparser.CreateTable, lineage *TableLineage) {
	lineage.TargetTable = stmt.Table.Name.CompliantName()

	if stmt.Select != nil {
		if sel, ok := stmt.Select.(*sqlparser.Select); ok {
			p.extractSelectLineage(sel, lineage)
		}
	}
}

type LineageExtractor interface {
	Extract(sql string) (*TableLineage, error)
	Supports(sqlType string) bool
}

type SQLExtractor struct {
	parser *SQLLineageParser
}

func NewSQLExtractor() *SQLExtractor {
	return &SQLExtractor{
		parser: NewSQLLineageParser(),
	}
}

func (e *SQLExtractor) Extract(sql string) (*TableLineage, error) {
	return e.parser.Parse(sql)
}

func (e *SQLExtractor) Supports(sqlType string) bool {
	return strings.ToLower(sqlType) == "sql" || strings.ToLower(sqlType) == "mysql" || strings.ToLower(sqlType) == "postgresql"
}

type SparkSQLExtractor struct {
	parser *SQLLineageParser
}

func NewSparkSQLExtractor() *SparkSQLExtractor {
	return &SparkSQLExtractor{
		parser: NewSQLLineageParser(),
	}
}

func (e *SparkSQLExtractor) Extract(sql string) (*TableLineage, error) {
	sql = strings.ReplaceAll(sql, "LATERAL VIEW", "-- LATERAL VIEW")
	sql = strings.ReplaceAll(sql, "CLUSTER BY", "-- CLUSTER BY")
	return e.parser.Parse(sql)
}

func (e *SparkSQLExtractor) Supports(sqlType string) bool {
	return strings.ToLower(sqlType) == "sparksql" || strings.ToLower(sqlType) == "hive"
}
