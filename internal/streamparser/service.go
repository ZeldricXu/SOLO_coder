package streamparser

import (
	"fmt"
	"sync"
	"time"

	"streamsql/internal/common/logger"
)

type QueryExecution struct {
	ID            string
	SQL           string
	LogicalPlan   *LogicalPlan
	PhysicalPlan  *PhysicalPlan
	Status        string
	Result        interface{}
	Error         string
	SubmittedAt   time.Time
	StartedAt     *time.Time
	CompletedAt   *time.Time
	DurationMs    int64
}

type StreamParserService struct {
	parser        *StreamSQLParser
	optimizer     *LogicalPlanOptimizer
	planGenerator *PhysicalPlanGenerator
	queries       map[string]*QueryExecution
	mu            sync.RWMutex
}

func NewStreamParserService(defaultParallelism int) *StreamParserService {
	return &StreamParserService{
		parser:        NewStreamSQLParser(),
		optimizer:     NewLogicalPlanOptimizer(),
		planGenerator: NewPhysicalPlanGenerator(defaultParallelism),
		queries:       make(map[string]*QueryExecution),
	}
}

func (s *StreamParserService) Parse(sql string) (*LogicalPlan, error) {
	start := time.Now()
	plan, err := s.parser.Parse(sql)
	if err != nil {
		return nil, err
	}

	logger.Sugar().Infof("Parsed SQL in %v", time.Since(start))
	return plan, nil
}

func (s *StreamParserService) Optimize(plan *LogicalPlan) (*LogicalPlan, error) {
	start := time.Now()
	optimized, err := s.optimizer.Optimize(plan)
	if err != nil {
		return nil, err
	}

	logger.Sugar().Infof("Optimized logical plan in %v", time.Since(start))
	return optimized, nil
}

func (s *StreamParserService) GeneratePhysicalPlan(plan *LogicalPlan) (*PhysicalPlan, error) {
	start := time.Now()
	physical, err := s.planGenerator.Generate(plan)
	if err != nil {
		return nil, err
	}

	logger.Sugar().Infof("Generated physical plan in %v", time.Since(start))
	return physical, nil
}

func (s *StreamParserService) Compile(sql string) (*QueryExecution, error) {
	execution := &QueryExecution{
		SQL:         sql,
		Status:      "COMPILING",
		SubmittedAt: time.Now().UTC(),
	}

	logical, err := s.Parse(sql)
	if err != nil {
		execution.Status = "FAILED"
		execution.Error = err.Error()
		now := time.Now().UTC()
		execution.CompletedAt = &now
		return execution, err
	}
	execution.LogicalPlan = logical

	optimized, err := s.Optimize(logical)
	if err != nil {
		execution.Status = "FAILED"
		execution.Error = err.Error()
		now := time.Now().UTC()
		execution.CompletedAt = &now
		return execution, err
	}
	execution.LogicalPlan = optimized

	physical, err := s.GeneratePhysicalPlan(optimized)
	if err != nil {
		execution.Status = "FAILED"
		execution.Error = err.Error()
		now := time.Now().UTC()
		execution.CompletedAt = &now
		return execution, err
	}
	execution.PhysicalPlan = physical

	execution.Status = "COMPILED"
	execution.ID = logical.ID
	now := time.Now().UTC()
	execution.CompletedAt = &now
	execution.DurationMs = time.Since(execution.SubmittedAt).Milliseconds()

	s.mu.Lock()
	s.queries[execution.ID] = execution
	s.mu.Unlock()

	logger.Sugar().Infof("Compiled query %s in %dms", execution.ID, execution.DurationMs)
	return execution, nil
}

func (s *StreamParserService) GetQuery(id string) (*QueryExecution, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	query, exists := s.queries[id]
	if !exists {
		return nil, fmt.Errorf("query not found: %s", id)
	}
	return query, nil
}

func (s *StreamParserService) ListQueries() []*QueryExecution {
	s.mu.RLock()
	defer s.mu.RUnlock()

	queries := make([]*QueryExecution, 0, len(s.queries))
	for _, q := range s.queries {
		queries = append(queries, q)
	}
	return queries
}

func (s *StreamParserService) DeleteQuery(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.queries[id]; !exists {
		return fmt.Errorf("query not found: %s", id)
	}

	delete(s.queries, id)
	return nil
}

func (s *StreamParserService) Execute(sql string) (*QueryExecution, error) {
	execution, err := s.Compile(sql)
	if err != nil {
		return execution, err
	}

	execution.Status = "RUNNING"
	now := time.Now().UTC()
	execution.StartedAt = &now

	mockResult := s.executeMock(execution.PhysicalPlan)
	execution.Result = mockResult

	execution.Status = "COMPLETED"
	now = time.Now().UTC()
	execution.CompletedAt = &now
	execution.DurationMs = time.Since(execution.SubmittedAt).Milliseconds()

	logger.Sugar().Infof("Executed query %s in %dms", execution.ID, execution.DurationMs)
	return execution, nil
}

func (s *StreamParserService) executeMock(plan *PhysicalPlan) map[string]interface{} {
	result := make(map[string]interface{})
	result["execution_steps"] = len(plan.ExecutionSteps)
	result["estimated_cost"] = plan.EstimatedCost
	result["parallelism"] = plan.Parallelism
	result["state_required"] = plan.StateRequired
	result["optimizations"] = plan.Optimizations

	stepDetails := make([]map[string]interface{}, 0)
	for _, step := range plan.ExecutionSteps {
		stepDetails = append(stepDetails, map[string]interface{}{
			"id":          step.ID,
			"operator":    step.Operator,
			"input":       step.Input,
			"output":      step.Output,
			"parallelism": step.Parallelism,
		})
	}
	result["steps"] = stepDetails

	rows := make([]map[string]interface{}, 0)
	for i := 0; i < 5; i++ {
		row := make(map[string]interface{})
		row["id"] = i + 1
		row["value"] = float64(i) * 10.5
		row["timestamp"] = time.Now().UTC().Add(time.Duration(-i) * time.Second)
		rows = append(rows, row)
	}
	result["sample_data"] = rows
	result["total_rows"] = 1000

	return result
}

func (s *StreamParserService) GetStats() map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()

	totalQueries := len(s.queries)
	completed := 0
	failed := 0
	running := 0

	for _, q := range s.queries {
		switch q.Status {
		case "COMPLETED":
			completed++
		case "FAILED":
			failed++
		case "RUNNING":
			running++
		}
	}

	return map[string]interface{}{
		"total_queries":     totalQueries,
		"completed_queries": completed,
		"failed_queries":    failed,
		"running_queries":   running,
	}
}
