package approval

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type BatchOperationType string

const (
	BatchTypeApprove          BatchOperationType = "approve"
	BatchTypeReject           BatchOperationType = "reject"
	BatchTypeEvaluate         BatchOperationType = "evaluate"
	BatchTypeCreateTasks      BatchOperationType = "create_tasks"
	BatchTypeCheckStatus      BatchOperationType = "check_status"
)

type BatchItem struct {
	ID           string                 `json:"id"`
	TaskID       string                 `json:"task_id,omitempty"`
	ApproverID   string                 `json:"approver_id,omitempty"`
	Comment      string                 `json:"comment,omitempty"`
	RuleID       string                 `json:"rule_id,omitempty"`
	Payload      map[string]interface{} `json:"payload,omitempty"`
	WorkflowID   string                 `json:"workflow_id,omitempty"`
	InstanceID   string                 `json:"instance_id,omitempty"`
}

type BatchResult struct {
	ID         string      `json:"id"`
	Success    bool        `json:"success"`
	Data       interface{} `json:"data,omitempty"`
	Error      string      `json:"error,omitempty"`
	ApprovedAt *time.Time  `json:"approved_at,omitempty"`
	RejectedAt *time.Time  `json:"rejected_at,omitempty"`
	Status     string      `json:"status,omitempty"`
}

type BatchRequest struct {
	Operation   BatchOperationType `json:"operation"`
	Items       []BatchItem        `json:"items"`
	Concurrency int                `json:"concurrency,omitempty"`
	TimeoutMs   int                `json:"timeout_ms,omitempty"`
}

type BatchResponse struct {
	BatchID      string        `json:"batch_id"`
	Operation    BatchOperationType `json:"operation"`
	Total        int           `json:"total"`
	SuccessCount int           `json:"success_count"`
	FailCount    int           `json:"fail_count"`
	DurationMs   int64         `json:"duration_ms"`
	Results      []BatchResult `json:"results"`
}

type BatchConfig struct {
	MaxBatchSize    int
	DefaultTimeout  time.Duration
	DefaultConcurrency int
}

func DefaultBatchConfig() *BatchConfig {
	return &BatchConfig{
		MaxBatchSize:       500,
		DefaultTimeout:    30 * time.Second,
		DefaultConcurrency: 10,
	}
}

type BatchProcessor struct {
	engine *RuleEngine
	db     *gorm.DB
	config *BatchConfig
}

func NewBatchProcessor(engine *RuleEngine, db *gorm.DB, config *BatchConfig) *BatchProcessor {
	if config == nil {
		config = DefaultBatchConfig()
	}
	return &BatchProcessor{
		engine: engine,
		db:     db,
		config: config,
	}
}

func (p *BatchProcessor) Execute(ctx context.Context, req *BatchRequest) (*BatchResponse, error) {
	start := time.Now()

	if len(req.Items) == 0 {
		return nil, errors.New("batch is empty")
	}
	if len(req.Items) > p.config.MaxBatchSize {
		return nil, fmt.Errorf("batch size exceeds limit: %d > %d", len(req.Items), p.config.MaxBatchSize)
	}

	timeout := p.config.DefaultTimeout
	if req.TimeoutMs > 0 {
		timeout = time.Duration(req.TimeoutMs) * time.Millisecond
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	concurrency := p.config.DefaultConcurrency
	if req.Concurrency > 0 {
		concurrency = req.Concurrency
	}

	batchID := fmt.Sprintf("batch_%s", uuid.New().String()[:8])
	logger.Info("batch operation started",
		zap.String("batch_id", batchID),
		zap.String("operation", string(req.Operation)),
		zap.Int("count", len(req.Items)),
		zap.Int("concurrency", concurrency),
	)

	results := make([]BatchResult, len(req.Items))

	switch req.Operation {
	case BatchTypeApprove:
		results = p.processApprove(ctx, req.Items, concurrency)
	case BatchTypeReject:
		results = p.processReject(ctx, req.Items, concurrency)
	case BatchTypeEvaluate:
		results = p.processEvaluate(ctx, req.Items)
	case BatchTypeCreateTasks:
		results = p.processCreateTasks(ctx, req.Items, concurrency)
	case BatchTypeCheckStatus:
		results = p.processCheckStatus(ctx, req.Items)
	default:
		return nil, fmt.Errorf("unknown batch operation: %s", req.Operation)
	}

	successCount, failCount := countResults(results)
	durationMs := time.Since(start).Milliseconds()

	logger.Info("batch operation completed",
		zap.String("batch_id", batchID),
		zap.Int("success", successCount),
		zap.Int("fail", failCount),
		zap.Int64("duration_ms", durationMs),
	)

	return &BatchResponse{
		BatchID:      batchID,
		Operation:    req.Operation,
		Total:        len(req.Items),
		SuccessCount: successCount,
		FailCount:    failCount,
		DurationMs:   durationMs,
		Results:      results,
	}, nil
}

func (p *BatchProcessor) processApprove(ctx context.Context, items []BatchItem, concurrency int) []BatchResult {
	return p.processParallel(ctx, items, concurrency, func(ctx context.Context, item BatchItem) (interface{}, error) {
		if item.TaskID == "" || item.ApproverID == "" {
			return nil, errors.New("task_id and approver_id required")
		}
		err := p.engine.Approve(ctx, item.TaskID, item.ApproverID, item.Comment)
		if err != nil {
			return nil, err
		}
		now := time.Now()
		return map[string]interface{}{
			"task_id": item.TaskID,
			"approved_at": now,
		}, nil
	})
}

func (p *BatchProcessor) processReject(ctx context.Context, items []BatchItem, concurrency int) []BatchResult {
	return p.processParallel(ctx, items, concurrency, func(ctx context.Context, item BatchItem) (interface{}, error) {
		if item.TaskID == "" || item.ApproverID == "" {
			return nil, errors.New("task_id and approver_id required")
		}
		err := p.engine.Reject(ctx, item.TaskID, item.ApproverID, item.Comment)
		if err != nil {
			return nil, err
		}
		now := time.Now()
		return map[string]interface{}{
			"task_id": item.TaskID,
			"rejected_at": now,
		}, nil
	})
}

func (p *BatchProcessor) processEvaluate(ctx context.Context, items []BatchItem) []BatchResult {
	results := make([]BatchResult, len(items))

	for i, item := range items {
		results[i].ID = item.ID
		if item.RuleID == "" {
			results[i].Success = false
			results[i].Error = "rule_id required"
			continue
		}
		if item.Payload == nil {
			results[i].Success = false
			results[i].Error = "payload required"
			continue
		}

		var rule models.ApprovalRule
		if err := p.db.WithContext(ctx).Where("id = ?", item.RuleID).First(&rule).Error; err != nil {
			results[i].Success = false
			results[i].Error = fmt.Sprintf("rule not found: %v", err)
			continue
		}

		matched, err := p.engine.Evaluate(ctx, &rule, item.Payload)
		if err != nil {
			results[i].Success = false
			results[i].Error = err.Error()
			continue
		}

		results[i].Success = true
		results[i].Data = map[string]interface{}{
			"rule_id": item.RuleID,
			"matched": matched,
		}
	}

	return results
}

func (p *BatchProcessor) processCreateTasks(ctx context.Context, items []BatchItem, concurrency int) []BatchResult {
	return p.processParallel(ctx, items, concurrency, func(ctx context.Context, item BatchItem) (interface{}, error) {
		if item.WorkflowID == "" || item.InstanceID == "" {
			return nil, errors.New("workflow_id and instance_id required")
		}

		tenantID := ""
		if t, ok := item.Payload["tenant_id"]; ok {
			if id, ok := t.(string); ok {
				tenantID = id
			}
		}
		if tenantID == "" {
			return nil, errors.New("tenant_id required in payload")
		}

		tasks, err := p.engine.CreateApprovalTasks(ctx, tenantID, item.WorkflowID, item.InstanceID, item.Payload)
		if err != nil {
			return nil, err
		}

		taskIDs := make([]string, len(tasks))
		for i, t := range tasks {
			taskIDs[i] = t.ID
		}

		return map[string]interface{}{
			"workflow_id": item.WorkflowID,
			"instance_id": item.InstanceID,
			"task_count":   len(tasks),
			"task_ids":     taskIDs,
		}, nil
	})
}

func (p *BatchProcessor) processCheckStatus(ctx context.Context, items []BatchItem) []BatchResult {
	results := make([]BatchResult, len(items))

	for i, item := range items {
		results[i].ID = item.ID
		if item.InstanceID == "" {
			results[i].Success = false
			results[i].Error = "instance_id required"
			continue
		}

		status, err := p.engine.CheckApprovalStatus(ctx, item.InstanceID)
		if err != nil {
			results[i].Success = false
			results[i].Error = err.Error()
			continue
		}

		results[i].Success = true
		results[i].Status = status
		results[i].Data = map[string]interface{}{
			"instance_id": item.InstanceID,
			"status":      status,
		}
	}

	return results
}

type processorFunc func(context.Context, BatchItem) (interface{}, error)

func (p *BatchProcessor) processParallel(ctx context.Context, items []BatchItem, concurrency int, fn processorFunc) []BatchResult {
	results := make([]BatchResult, len(items))

	if concurrency <= 0 {
		concurrency = 1
	}
	if concurrency > len(items) {
		concurrency = len(items)
	}

	sem := make(chan struct{}, concurrency)
	var wg sync.WaitGroup
	var mu sync.Mutex

	for i := range items {
		wg.Add(1)
		sem <- struct{}{}

		go func(idx int, item BatchItem) {
			defer wg.Done()
			defer func() { <-sem }()

			result := BatchResult{ID: item.ID}

			select {
			case <-ctx.Done():
				result.Success = false
				result.Error = "context cancelled or timeout"
			default:
				data, err := fn(ctx, item)
				if err != nil {
					result.Success = false
					result.Error = err.Error()
				} else {
					result.Success = true
					result.Data = data
				}
			}

			mu.Lock()
			results[idx] = result
			mu.Unlock()
		}(i, items[i])
	}

	wg.Wait()
	close(sem)

	return results
}

func countResults(results []BatchResult) (success, fail int) {
	for _, r := range results {
		if r.Success {
			success++
		} else {
			fail++
		}
	}
	return
}

type RequestBatcher struct {
	requestChan chan *BatchedRequest
	processChan chan []*BatchedRequest

	maxWait     time.Duration
	maxBatchSize int

	processFn  func(ctx context.Context, requests []*BatchedRequest) ([]*BatchResult, error)
	stopChan   chan struct{}
	wg         sync.WaitGroup

	mu         sync.Mutex
	running    bool
}

type BatchedRequest struct {
	Operation BatchOperationType
	Item      BatchItem
	RespChan  chan *BatchResult
}

func NewRequestBatcher(
	maxWait time.Duration,
	maxBatchSize int,
	processFn func(ctx context.Context, requests []*BatchedRequest) ([]*BatchResult, error),
) *RequestBatcher {
	return &RequestBatcher{
		requestChan:  make(chan *BatchedRequest, 10000),
		processChan:  make(chan []*BatchedRequest, 100),
		maxWait:      maxWait,
		maxBatchSize: maxBatchSize,
		processFn:    processFn,
		stopChan:     make(chan struct{}),
	}
}

func (b *RequestBatcher) Start() error {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.running {
		return errors.New("batcher already running")
	}

	b.wg.Add(2)

	go b.accumulate()
	go b.process()

	b.running = true
	return nil
}

func (b *RequestBatcher) Stop() {
	b.mu.Lock()
	if !b.running {
		b.mu.Unlock()
		return
	}
	b.running = false
	b.mu.Unlock()

	close(b.stopChan)
	b.wg.Wait()
}

func (b *RequestBatcher) Submit(ctx context.Context, req *BatchedRequest) error {
	select {
	case b.requestChan <- req:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (b *RequestBatcher) accumulate() {
	defer b.wg.Done()

	ticker := time.NewTicker(b.maxWait)
	defer ticker.Stop()

	var batch []*BatchedRequest

	for {
		select {
		case <-b.stopChan:
			if len(batch) > 0 {
				b.processChan <- batch
			}
			return
		case req := <-b.requestChan:
			batch = append(batch, req)
			if len(batch) >= b.maxBatchSize {
				b.processChan <- batch
				batch = nil
			}
		case <-ticker.C:
			if len(batch) > 0 {
				b.processChan <- batch
				batch = nil
			}
		}
	}
}

func (b *RequestBatcher) process() {
	defer b.wg.Done()

	for {
		select {
		case <-b.stopChan:
			return
		case batch := <-b.processChan:
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)

			results, err := b.processFn(ctx, batch)
			if err != nil {
				for _, req := range batch {
					select {
					case req.RespChan <- &BatchResult{
						Success: false,
						Error:   err.Error(),
					}:
					default:
					}
				}
			} else {
				for i, req := range batch {
					if i < len(results) {
						select {
						case req.RespChan <- results[i]:
						default:
						}
					}
				}
			}

			cancel()
		}
	}
}

type BatchStats struct {
	TotalBatches     int64
	TotalItems       int64
	SuccessItems     int64
	FailedItems      int64
	AvgLatencyMs     float64
	MaxLatencyMs     int64
	ActiveRequests   int64
	BatchSizes       map[int]int64
}

var batchStats = BatchStats{
	BatchSizes: make(map[int]int64),
}

func GetBatchStats() *BatchStats {
	return &batchStats
}

func ResetBatchStats() {
	batchStats = BatchStats{
		BatchSizes: make(map[int]int64),
	}
}

func createBatchID() string {
	return fmt.Sprintf("batch_%s", uuid.New().String()[:8])
}

func (p *BatchProcessor) BatchCreateRules(ctx context.Context, tenantID string, rules []struct {
	Name        string
	WorkflowID  string
	Condition   *ApprovalCondition
	Strategy    string
	Approvers   *ApproverSpec
	Priority    int
}) ([]*models.ApprovalRule, error) {
	if len(rules) == 0 {
		return nil, errors.New("no rules to create")
	}

	createdRules := make([]*models.ApprovalRule, 0, len(rules))

	for _, r := range rules {
		rule, err := p.engine.CreateRule(ctx, tenantID, r.Name, r.WorkflowID, r.Condition, r.Strategy, r.Approvers, r.Priority)
		if err != nil {
			return createdRules, fmt.Errorf("failed to create rule %q: %w", r.Name, err)
		}
		createdRules = append(createdRules, rule)
	}

	return createdRules, nil
}

func (p *BatchProcessor) BatchGetRules(ctx context.Context, tenantID string, ruleIDs []string) ([]*models.ApprovalRule, error) {
	if len(ruleIDs) == 0 {
		return []*models.ApprovalRule{}, nil
	}

	var rules []*models.ApprovalRule
	if err := p.db.WithContext(ctx).
		Where("tenant_id = ? AND id IN ?", tenantID, ruleIDs).
		Order("priority DESC").
		Find(&rules).Error; err != nil {
		return nil, err
	}

	return rules, nil
}

func (p *BatchProcessor) BatchEnableRules(ctx context.Context, tenantID string, ruleIDs []string, enabled bool) error {
	if len(ruleIDs) == 0 {
		return nil
	}

	now := time.Now()
	return p.db.WithContext(ctx).
		Model(&models.ApprovalRule{}).
		Where("tenant_id = ? AND id IN ?", tenantID, ruleIDs).
		Updates(map[string]interface{}{
			"enabled":    enabled,
			"updated_at": now,
		}).Error
}

func (p *BatchProcessor) BatchDeleteRules(ctx context.Context, tenantID string, ruleIDs []string) error {
	if len(ruleIDs) == 0 {
		return nil
	}

	return p.db.WithContext(ctx).
		Where("tenant_id = ? AND id IN ?", tenantID, ruleIDs).
		Delete(&models.ApprovalRule{}).Error
}

func (p *BatchProcessor) BatchGetPendingTasks(ctx context.Context, approverIDs []string) (map[string][]*models.ApprovalTask, error) {
	if len(approverIDs) == 0 {
		return map[string][]*models.ApprovalTask{}, nil
	}

	var tasks []*models.ApprovalTask
	if err := p.db.WithContext(ctx).
		Where("approver_id IN ? AND status = ?", approverIDs, StatusPending).
		Order("created_at DESC").
		Find(&tasks).Error; err != nil {
		return nil, err
	}

	result := make(map[string][]*models.ApprovalTask)
	for _, task := range tasks {
		result[task.ApproverID] = append(result[task.ApproverID], task)
	}

	return result, nil
}

func (p *BatchProcessor) BatchResolveApprovers(ctx context.Context, items []struct {
	RuleID  string
	Payload map[string]interface{}
}) (map[string][]string, error) {
	if len(items) == 0 {
		return map[string][]string{}, nil
	}

	ruleIDs := make([]string, len(items))
	for i, item := range items {
		ruleIDs[i] = item.RuleID
	}

	var rules []*models.ApprovalRule
	if err := p.db.WithContext(ctx).Where("id IN ?", ruleIDs).Find(&rules).Error; err != nil {
		return nil, err
	}

	ruleMap := make(map[string]*models.ApprovalRule)
	for _, rule := range rules {
		ruleMap[rule.ID] = rule
	}

	result := make(map[string][]string)
	for _, item := range items {
		if rule, ok := ruleMap[item.RuleID]; ok {
			approvers, err := p.engine.ResolveApprovers(ctx, rule, item.Payload)
			if err != nil {
				return result, err
			}
			result[item.RuleID] = approvers
		}
	}

	return result, nil
}

func (p *BatchProcessor) BatchCheckApprovalStatus(ctx context.Context, instanceIDs []string) (map[string]string, error) {
	if len(instanceIDs) == 0 {
		return map[string]string{}, nil
	}

	result := make(map[string]string)

	for _, instanceID := range instanceIDs {
		status, err := p.engine.CheckApprovalStatus(ctx, instanceID)
		if err != nil {
			return result, err
		}
		result[instanceID] = status
	}

	return result, nil
}

func (p *BatchProcessor) ExecuteTransaction(ctx context.Context, fn func(tx *gorm.DB) error) error {
	return p.db.WithContext(ctx).Transaction(fn)
}
