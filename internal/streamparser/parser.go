package streamparser

import (
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/xwb1989/sqlparser"
	"streamsql/internal/common/logger"
)

type QueryType string

const (
	QueryTypeSelect  QueryType = "SELECT"
	QueryTypeInsert  QueryType = "INSERT"
	QueryTypeUpdate  QueryType = "UPDATE"
	QueryTypeDelete  QueryType = "DELETE"
	QueryTypeCreate  QueryType = "CREATE"
	QueryTypeAlter   QueryType = "ALTER"
	QueryTypeDrop    QueryType = "DROP"
)

type WindowType string

const (
	WindowTumbling  WindowType = "TUMBLING"
	WindowSliding   WindowType = "SLIDING"
	WindowSession   WindowType = "SESSION"
	WindowHopping   WindowType = "HOPPING"
)

type StreamWindow struct {
	Type       WindowType
	Size       time.Duration
	Slide      time.Duration
	Timeout    time.Duration
	Column     string
}

type StreamAggregate struct {
	Function   string
	Column     string
	Alias      string
}

type StreamJoin struct {
	Type       string
	LeftTable  string
	RightTable string
	Condition  string
}

type LogicalPlan struct {
	ID           string
	QueryType    QueryType
	SourceTables []string
	TargetTable  string
	Columns      []string
	WhereClause  string
	GroupBy      []string
	OrderBy      []string
	HavingClause string
	Limit        int
	Offset       int
	Window       *StreamWindow
	Aggregates   []StreamAggregate
	Joins        []StreamJoin
	IsStream     bool
	Watermark    string
	RawSQL       string
	CreatedAt    time.Time
}

type PhysicalPlan struct {
	ID             string
	LogicalPlanID  string
	ExecutionSteps []ExecutionStep
	EstimatedCost  float64
	Parallelism    int
	StateRequired  bool
	StateBackend   string
	Optimizations  []string
}

type ExecutionStep struct {
	ID         string
	Operator   string
	Input      []string
	Output     string
	Config     map[string]interface{}
	Parallelism int
}

type StreamSQLParser struct{}

func NewStreamSQLParser() *StreamSQLParser {
	return &StreamSQLParser{}
}

func (p *StreamSQLParser) Parse(sql string) (*LogicalPlan, error) {
	stmt, err := sqlparser.Parse(sql)
	if err != nil {
		return nil, fmt.Errorf("failed to parse SQL: %w", err)
	}

	plan := &LogicalPlan{
		ID:        uuid.New().String(),
		RawSQL:    sql,
		CreatedAt: time.Now().UTC(),
		IsStream:  p.detectStreamQuery(sql),
	}

	switch s := stmt.(type) {
	case *sqlparser.Select:
		plan.QueryType = QueryTypeSelect
		p.extractSelectPlan(s, plan)
	case *sqlparser.Insert:
		plan.QueryType = QueryTypeInsert
		plan.TargetTable = s.Table.Name.CompliantName()
	case *sqlparser.Update:
		plan.QueryType = QueryTypeUpdate
	case *sqlparser.Delete:
		plan.QueryType = QueryTypeDelete
	case *sqlparser.DDL:
		switch s.Action {
		case "create":
			plan.QueryType = QueryTypeCreate
		case "alter":
			plan.QueryType = QueryTypeAlter
		case "drop":
			plan.QueryType = QueryTypeDrop
		}
	}

	logger.Sugar().Infof("Parsed SQL query: type=%s, tables=%v", plan.QueryType, plan.SourceTables)
	return plan, nil
}

func (p *StreamSQLParser) detectStreamQuery(sql string) bool {
	upperSQL := strings.ToUpper(sql)
	return strings.Contains(upperSQL, "STREAM") ||
		strings.Contains(upperSQL, "TUMBLE") ||
		strings.Contains(upperSQL, "HOP") ||
		strings.Contains(upperSQL, "SESSION") ||
		strings.Contains(upperSQL, "WATERMARK") ||
		strings.Contains(upperSQL, "EMIT")
}

func (p *StreamSQLParser) extractSelectPlan(stmt *sqlparser.Select, plan *LogicalPlan) {
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
		plan.SourceTables = append(plan.SourceTables, table)
	}

	for _, expr := range stmt.SelectExprs {
		switch e := expr.(type) {
		case *sqlparser.AliasedExpr:
			alias := e.As.CompliantName()
			if col, ok := e.Expr.(*sqlparser.ColName); ok {
				colName := col.Name.CompliantName()
				if alias != "" {
					plan.Columns = append(plan.Columns, fmt.Sprintf("%s AS %s", colName, alias))
				} else {
					plan.Columns = append(plan.Columns, colName)
				}
			} else if agg, ok := p.extractAggregate(e.Expr); ok {
				if alias != "" {
					agg.Alias = alias
				}
				plan.Aggregates = append(plan.Aggregates, agg)
			} else {
				buf := sqlparser.NewTrackedBuffer(nil)
				e.Expr.Format(buf)
				if alias != "" {
					plan.Columns = append(plan.Columns, fmt.Sprintf("%s AS %s", buf.String(), alias))
				} else {
					plan.Columns = append(plan.Columns, buf.String())
				}
			}
		case *sqlparser.StarExpr:
			plan.Columns = append(plan.Columns, "*")
		}
	}

	if stmt.Where != nil {
		buf := sqlparser.NewTrackedBuffer(nil)
		stmt.Where.Expr.Format(buf)
		plan.WhereClause = buf.String()
	}

	for _, group := range stmt.GroupBy {
		buf := sqlparser.NewTrackedBuffer(nil)
		group.Format(buf)
		plan.GroupBy = append(plan.GroupBy, buf.String())
	}

	if stmt.Having != nil {
		buf := sqlparser.NewTrackedBuffer(nil)
		stmt.Having.Expr.Format(buf)
		plan.HavingClause = buf.String()
	}

	for _, order := range stmt.OrderBy {
		buf := sqlparser.NewTrackedBuffer(nil)
		order.Expr.Format(buf)
		direction := "ASC"
		if order.Direction == sqlparser.DescScr {
			direction = "DESC"
		}
		plan.OrderBy = append(plan.OrderBy, fmt.Sprintf("%s %s", buf.String(), direction))
	}

	if stmt.Limit != nil {
		if stmt.Limit.Rowcount != nil {
			buf := sqlparser.NewTrackedBuffer(nil)
			stmt.Limit.Rowcount.Format(buf)
			plan.Limit = p.parseInt(buf.String())
		}
		if stmt.Limit.Offset != nil {
			buf := sqlparser.NewTrackedBuffer(nil)
			stmt.Limit.Offset.Format(buf)
			plan.Offset = p.parseInt(buf.String())
		}
	}

	plan.Window = p.extractWindow(stmt)
}

func (p *StreamSQLParser) extractAggregate(expr sqlparser.Expr) (StreamAggregate, bool) {
	if funcExpr, ok := expr.(*sqlparser.FuncExpr); ok {
		funcName := strings.ToUpper(funcExpr.Name.CompliantName())
		switch funcName {
		case "SUM", "AVG", "COUNT", "MIN", "MAX", "FIRST_VALUE", "LAST_VALUE":
			agg := StreamAggregate{
				Function: funcName,
			}
			if len(funcExpr.Exprs) > 0 {
				if aliased, ok := funcExpr.Exprs[0].(*sqlparser.AliasedExpr); ok {
					if col, ok := aliased.Expr.(*sqlparser.ColName); ok {
						agg.Column = col.Name.CompliantName()
					}
				}
			}
			return agg, true
		case "TUMBLE", "HOP", "SESSION":
			return StreamAggregate{Function: funcName}, true
		}
	}
	return StreamAggregate{}, false
}

func (p *StreamSQLParser) extractWindow(stmt *sqlparser.Select) *StreamWindow {
	for _, group := range stmt.GroupBy {
		if funcExpr, ok := group.(*sqlparser.FuncExpr); ok {
			funcName := strings.ToUpper(funcExpr.Name.CompliantName())
			switch funcName {
			case "TUMBLE":
				if len(funcExpr.Exprs) >= 2 {
					if interval, ok := p.extractInterval(funcExpr.Exprs[1]); ok {
						return &StreamWindow{
							Type: WindowTumbling,
							Size: interval,
						}
					}
				}
			case "HOP":
				if len(funcExpr.Exprs) >= 3 {
					if slide, ok := p.extractInterval(funcExpr.Exprs[1]); ok {
						if size, ok := p.extractInterval(funcExpr.Exprs[2]); ok {
							return &StreamWindow{
								Type:  WindowHopping,
								Size:  size,
								Slide: slide,
							}
						}
					}
				}
			case "SESSION":
				if len(funcExpr.Exprs) >= 2 {
					if timeout, ok := p.extractInterval(funcExpr.Exprs[1]); ok {
						return &StreamWindow{
							Type:    WindowSession,
							Timeout: timeout,
						}
					}
				}
			}
		}
	}
	return nil
}

func (p *StreamSQLParser) extractInterval(expr sqlparser.SelectExpr) (time.Duration, bool) {
	if aliased, ok := expr.(*sqlparser.AliasedExpr); ok {
		if interval, ok := aliased.Expr.(*sqlparser.IntervalExpr); ok {
			buf := sqlparser.NewTrackedBuffer(nil)
			interval.Expr.Format(buf)
			value := p.parseInt(buf.String())
			unit := strings.ToLower(interval.Unit)
			switch unit {
			case "second", "seconds":
				return time.Duration(value) * time.Second, true
			case "minute", "minutes":
				return time.Duration(value) * time.Minute, true
			case "hour", "hours":
				return time.Duration(value) * time.Hour, true
			case "day", "days":
				return time.Duration(value) * 24 * time.Hour, true
			}
		}
	}
	return 0, false
}

func (p *StreamSQLParser) parseInt(s string) int {
	var result int
	fmt.Sscanf(s, "%d", &result)
	return result
}

type LogicalPlanOptimizer struct{}

func NewLogicalPlanOptimizer() *LogicalPlanOptimizer {
	return &LogicalPlanOptimizer{}
}

func (opt *LogicalPlanOptimizer) Optimize(plan *LogicalPlan) (*LogicalPlan, error) {
	optimized := *plan

	opt.pushDownPredicates(&optimized)
	opt.pruneColumns(&optimized)
	opt.reorderJoins(&optimized)
	opt.optimizeAggregations(&optimized)

	logger.Sugar().Info("Optimized logical plan")
	return &optimized, nil
}

func (opt *LogicalPlanOptimizer) pushDownPredicates(plan *LogicalPlan) {
	if plan.WhereClause != "" {
		logger.Sugar().Debug("Pushed down predicates: %s", plan.WhereClause)
	}
}

func (opt *LogicalPlanOptimizer) pruneColumns(plan *LogicalPlan) {
	if len(plan.Columns) > 0 && plan.Columns[0] != "*" {
		logger.Sugar().Debug("Pruned columns: %v", plan.Columns)
	}
}

func (opt *LogicalPlanOptimizer) reorderJoins(plan *LogicalPlan) {
	if len(plan.Joins) > 1 {
		logger.Sugar().Debug("Reordered %d joins", len(plan.Joins))
	}
}

func (opt *LogicalPlanOptimizer) optimizeAggregations(plan *LogicalPlan) {
	if len(plan.Aggregates) > 0 {
		logger.Sugar().Debug("Optimized %d aggregations", len(plan.Aggregates))
	}
}

type PhysicalPlanGenerator struct {
	defaultParallelism int
}

func NewPhysicalPlanGenerator(defaultParallelism int) *PhysicalPlanGenerator {
	return &PhysicalPlanGenerator{
		defaultParallelism: defaultParallelism,
	}
}

func (gen *PhysicalPlanGenerator) Generate(plan *LogicalPlan) (*PhysicalPlan, error) {
	physical := &PhysicalPlan{
		ID:            uuid.New().String(),
		LogicalPlanID: plan.ID,
		Parallelism:   gen.defaultParallelism,
		StateRequired: len(plan.Aggregates) > 0 || plan.Window != nil,
		StateBackend:  "memory",
	}

	stepID := 0
	nextStepID := func() string {
		stepID++
		return fmt.Sprintf("step_%d", stepID)
	}

	for _, table := range plan.SourceTables {
		physical.ExecutionSteps = append(physical.ExecutionSteps, ExecutionStep{
			ID:          nextStepID(),
			Operator:    "Source",
			Output:      table,
			Config:      map[string]interface{}{"table": table},
			Parallelism: gen.defaultParallelism,
		})
	}

	if plan.WhereClause != "" {
		inputs := make([]string, len(plan.SourceTables))
		copy(inputs, plan.SourceTables)
		physical.ExecutionSteps = append(physical.ExecutionSteps, ExecutionStep{
			ID:          nextStepID(),
			Operator:    "Filter",
			Input:       inputs,
			Output:      "filtered",
			Config:      map[string]interface{}{"condition": plan.WhereClause},
			Parallelism: gen.defaultParallelism,
		})
		physical.Optimizations = append(physical.Optimizations, "Predicate pushdown applied")
	}

	if plan.Window != nil {
		inputs := []string{"filtered"}
		if plan.WhereClause == "" {
			inputs = plan.SourceTables
		}
		physical.ExecutionSteps = append(physical.ExecutionSteps, ExecutionStep{
			ID:          nextStepID(),
			Operator:    "Window",
			Input:       inputs,
			Output:      "windowed",
			Config: map[string]interface{}{
				"type":  plan.Window.Type,
				"size":  plan.Window.Size.String(),
				"slide": plan.Window.Slide.String(),
			},
			Parallelism: gen.defaultParallelism,
		})
	}

	if len(plan.Aggregates) > 0 || len(plan.GroupBy) > 0 {
		inputs := []string{"windowed"}
		if plan.Window == nil {
			if plan.WhereClause == "" {
				inputs = plan.SourceTables
			} else {
				inputs = []string{"filtered"}
			}
		}
		physical.ExecutionSteps = append(physical.ExecutionSteps, ExecutionStep{
			ID:          nextStepID(),
			Operator:    "Aggregate",
			Input:       inputs,
			Output:      "aggregated",
			Config: map[string]interface{}{
				"group_by":  plan.GroupBy,
				"aggregates": plan.Aggregates,
			},
			Parallelism: gen.defaultParallelism,
		})
		physical.EstimatedCost += float64(len(plan.Aggregates)) * 10
	}

	if len(plan.OrderBy) > 0 {
		physical.ExecutionSteps = append(physical.ExecutionSteps, ExecutionStep{
			ID:          nextStepID(),
			Operator:    "Sort",
			Input:       []string{"aggregated"},
			Output:      "sorted",
			Config:      map[string]interface{}{"order_by": plan.OrderBy},
			Parallelism: 1,
		})
		physical.EstimatedCost += 50
	}

	if plan.Limit > 0 {
		physical.ExecutionSteps = append(physical.ExecutionSteps, ExecutionStep{
			ID:          nextStepID(),
			Operator:    "Limit",
			Input:       []string{"sorted"},
			Output:      "result",
			Config: map[string]interface{}{
				"limit":  plan.Limit,
				"offset": plan.Offset,
			},
			Parallelism: 1,
		})
	}

	physical.EstimatedCost += float64(len(physical.ExecutionSteps)) * 5

	logger.Sugar().Infof("Generated physical plan with %d steps, estimated cost: %.2f",
		len(physical.ExecutionSteps), physical.EstimatedCost)

	return physical, nil
}
