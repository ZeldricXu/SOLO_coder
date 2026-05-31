package alertengine

import (
	"context"
	"fmt"
	"go.uber.org/zap"
	"metricplatform/internal/models"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
)

type Notifier interface {
	Send(ctx context.Context, alert *models.Alert) error
}

type MetricsProvider interface {
	GetMetricValue(ctx context.Context, metricName string, tags map[string]string) (float64, error)
}

type EvaluationTask struct {
	RuleID    string
	Timestamp time.Time
	Context   context.Context
}

type EvaluationResult struct {
	RuleID     string
	Value      float64
	Triggered  bool
	Alert      *models.Alert
	Error      error
	EvaluatedAt time.Time
}

type EvaluationCallback func(result *EvaluationResult)

type RuleEvaluator struct {
	rules            map[string]*models.AlertRule
	activeAlerts     map[string]*models.Alert
	alertStates      map[string]*alertState
	metricsProvider  MetricsProvider
	notifiers        []Notifier
	cron             *cron.Cron
	logger           *zap.Logger
	mu               sync.RWMutex

	taskQueue        chan *EvaluationTask
	resultQueue      chan *EvaluationResult
	workers          int
	workerWg         sync.WaitGroup
	callbacks        map[string]EvaluationCallback
	pendingTasks     map[string]int
	maxQueueSize     int
	nonBlocking      bool
	ctx              context.Context
	cancel           context.CancelFunc
}

type alertState struct {
	triggeredAt   time.Time
	lastValue     float64
	lastEvaluated time.Time
	firing        bool
}

type ParsedExpression struct {
	MetricName string
	Operator   string
	Threshold  float64
	Tags       map[string]string
}

type Option func(*RuleEvaluator)

func WithWorkers(n int) Option {
	return func(re *RuleEvaluator) {
		re.workers = n
	}
}

func WithQueueSize(size int) Option {
	return func(re *RuleEvaluator) {
		re.maxQueueSize = size
	}
}

func WithNonBlocking(nonBlocking bool) Option {
	return func(re *RuleEvaluator) {
		re.nonBlocking = nonBlocking
	}
}

func NewRuleEvaluator(mp MetricsProvider, notifiers []Notifier, logger *zap.Logger, opts ...Option) *RuleEvaluator {
	ctx, cancel := context.WithCancel(context.Background())

	re := &RuleEvaluator{
		rules:           make(map[string]*models.AlertRule),
		activeAlerts:    make(map[string]*models.Alert),
		alertStates:     make(map[string]*alertState),
		metricsProvider: mp,
		notifiers:       notifiers,
		cron:            cron.New(),
		logger:          logger,
		workers:         4,
		maxQueueSize:    10000,
		nonBlocking:     true,
		ctx:             ctx,
		cancel:          cancel,
		callbacks:       make(map[string]EvaluationCallback),
		pendingTasks:    make(map[string]int),
	}

	for _, opt := range opts {
		opt(re)
	}

	re.taskQueue = make(chan *EvaluationTask, re.maxQueueSize)
	re.resultQueue = make(chan *EvaluationResult, re.maxQueueSize)

	return re
}

func (re *RuleEvaluator) AddRule(rule *models.AlertRule) error {
	re.mu.Lock()
	defer re.mu.Unlock()

	if rule.ID == "" {
		rule.ID = uuid.New().String()
	}

	re.rules[rule.ID] = rule

	_, err := re.cron.AddFunc(fmt.Sprintf("@every %s", rule.ForDuration), func() {
		re.scheduleEvaluation(rule.ID)
	})
	if err != nil {
		return fmt.Errorf("failed to schedule rule evaluation: %w", err)
	}

	re.logger.Info("Alert rule added", zap.String("rule_id", rule.ID), zap.String("name", rule.Name))
	return nil
}

func (re *RuleEvaluator) RemoveRule(ruleID string) {
	re.mu.Lock()
	defer re.mu.Unlock()
	delete(re.rules, ruleID)
	re.logger.Info("Alert rule removed", zap.String("rule_id", ruleID))
}

func (re *RuleEvaluator) RegisterCallback(ruleID string, cb EvaluationCallback) {
	re.mu.Lock()
	defer re.mu.Unlock()
	re.callbacks[ruleID] = cb
}

func (re *RuleEvaluator) scheduleEvaluation(ruleID string) {
	task := &EvaluationTask{
		RuleID:    ruleID,
		Timestamp: time.Now(),
		Context:   context.Background(),
	}

	if re.nonBlocking {
		select {
		case re.taskQueue <- task:
			re.mu.Lock()
			re.pendingTasks[ruleID]++
			re.mu.Unlock()
			re.logger.Debug("Evaluation task queued", zap.String("rule_id", ruleID))
		default:
			re.logger.Warn("Task queue full, dropping evaluation", zap.String("rule_id", ruleID))
		}
	} else {
		re.taskQueue <- task
	}
}

func (re *RuleEvaluator) EvaluateAsync(ruleID string, cb EvaluationCallback) error {
	re.mu.RLock()
	_, exists := re.rules[ruleID]
	re.mu.RUnlock()

	if !exists {
		return fmt.Errorf("rule not found: %s", ruleID)
	}

	if cb != nil {
		re.RegisterCallback(ruleID, cb)
	}

	re.scheduleEvaluation(ruleID)
	return nil
}

func (re *RuleEvaluator) EvaluateSync(ctx context.Context, ruleID string) (*EvaluationResult, error) {
	re.mu.RLock()
	rule, exists := re.rules[ruleID]
	re.mu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("rule not found: %s", ruleID)
	}

	result := re.processEvaluation(ruleID, rule)
	return result, result.Error
}

func (re *RuleEvaluator) Start() {
	re.startWorkers()
	go re.processResults()
	re.cron.Start()
	re.logger.Info("Alert rule evaluator started with async workers",
		zap.Int("workers", re.workers),
		zap.Int("queue_size", re.maxQueueSize),
		zap.Bool("non_blocking", re.nonBlocking))
}

func (re *RuleEvaluator) startWorkers() {
	re.workerWg.Add(re.workers)
	for i := 0; i < re.workers; i++ {
		go re.worker(i)
	}
	re.logger.Debug("Started evaluation workers", zap.Int("count", re.workers))
}

func (re *RuleEvaluator) worker(id int) {
	defer re.workerWg.Done()
	re.logger.Debug("Evaluation worker started", zap.Int("worker_id", id))

	for {
		select {
		case <-re.ctx.Done():
			re.logger.Debug("Evaluation worker stopping", zap.Int("worker_id", id))
			return
		case task, ok := <-re.taskQueue:
			if !ok {
				return
			}

			re.logger.Debug("Worker processing task",
				zap.Int("worker_id", id),
				zap.String("rule_id", task.RuleID))

			re.mu.RLock()
			rule, exists := re.rules[task.RuleID]
			re.mu.RUnlock()

			if !exists {
				re.logger.Debug("Rule not found, skipping evaluation", zap.String("rule_id", task.RuleID))
				continue
			}

			result := re.processEvaluation(task.RuleID, rule)

			select {
			case re.resultQueue <- result:
			default:
				re.logger.Warn("Result queue full, dropping result", zap.String("rule_id", task.RuleID))
			}

			re.mu.Lock()
			if re.pendingTasks[task.RuleID] > 0 {
				re.pendingTasks[task.RuleID]--
			}
			re.mu.Unlock()
		}
	}
}

func (re *RuleEvaluator) processEvaluation(ruleID string, rule *models.AlertRule) *EvaluationResult {
	result := &EvaluationResult{
		RuleID:      ruleID,
		EvaluatedAt: time.Now(),
	}

	if !rule.Enabled {
		return result
	}

	ctx := context.Background()
	parsed, err := ParseExpression(rule.Expression)
	if err != nil {
		result.Error = fmt.Errorf("failed to parse expression: %w", err)
		re.logger.Error("Failed to parse expression", zap.Error(err), zap.String("rule_id", ruleID))
		return result
	}

	value, err := re.metricsProvider.GetMetricValue(ctx, parsed.MetricName, parsed.Tags)
	if err != nil {
		result.Error = fmt.Errorf("failed to get metric value: %w", err)
		re.logger.Error("Failed to get metric value", zap.Error(err), zap.String("metric", parsed.MetricName))
		return result
	}

	result.Value = value
	stateKey := fmt.Sprintf("%s:%s", ruleID, hashTags(parsed.Tags))
	triggered := evaluateCondition(value, parsed.Operator, parsed.Threshold)

	re.mu.Lock()
	state, exists := re.alertStates[stateKey]
	if !exists {
		state = &alertState{}
		re.alertStates[stateKey] = state
	}
	state.lastValue = value
	state.lastEvaluated = time.Now()
	re.mu.Unlock()

	now := time.Now()
	result.Triggered = triggered

	if triggered {
		if !state.firing {
			state.triggeredAt = now
		}

		duration := now.Sub(state.triggeredAt)
		if duration >= rule.ForDuration && !state.firing {
			alert := re.triggerAlert(ctx, rule, parsed, value, stateKey)
			result.Alert = alert
		}
	} else {
		if state.firing {
			re.resolveAlert(ctx, rule, stateKey)
		}
		state.triggeredAt = time.Time{}
	}

	re.logger.Debug("Rule evaluation completed",
		zap.String("rule_id", ruleID),
		zap.Float64("value", value),
		zap.Bool("triggered", triggered))

	return result
}

func (re *RuleEvaluator) processResults() {
	for {
		select {
		case <-re.ctx.Done():
			return
		case result, ok := <-re.resultQueue:
			if !ok {
				return
			}

			re.mu.RLock()
			cb, hasCallback := re.callbacks[result.RuleID]
			re.mu.RUnlock()

			if hasCallback {
				go func(r *EvaluationResult) {
					defer func() {
						if err := recover(); err != nil {
							re.logger.Error("Callback panicked", zap.Any("error", err))
						}
					}()
					cb(r)
				}(result)
			}
		}
	}
}

func (re *RuleEvaluator) triggerAlert(ctx context.Context, rule *models.AlertRule, parsed *ParsedExpression, value float64, stateKey string) *models.Alert {
	re.mu.Lock()
	defer re.mu.Unlock()

	alert := &models.Alert{
		ID:       uuid.New().String(),
		RuleID:   rule.ID,
		Labels:   mergeLabels(rule.Labels, parsed.Tags),
		State:    "firing",
		Severity: rule.Severity,
		ActiveAt: time.Now(),
		Value:    value,
		Message:  fmt.Sprintf("Alert %s fired: value %.2f %s %.2f", rule.Name, value, parsed.Operator, parsed.Threshold),
	}

	re.activeAlerts[stateKey] = alert
	re.alertStates[stateKey].firing = true

	for _, notifier := range re.notifiers {
		go func(n Notifier, a *models.Alert) {
			if err := n.Send(ctx, a); err != nil {
				re.logger.Error("Failed to send notification", zap.Error(err))
			}
		}(notifier, alert)
	}

	re.logger.Info("Alert triggered",
		zap.String("alert_id", alert.ID),
		zap.String("rule_id", rule.ID),
		zap.Float64("value", value),
		zap.String("severity", rule.Severity))

	return alert
}

func (re *RuleEvaluator) resolveAlert(ctx context.Context, rule *models.AlertRule, stateKey string) {
	re.mu.Lock()
	defer re.mu.Unlock()

	alert, exists := re.activeAlerts[stateKey]
	if !exists {
		return
	}

	now := time.Now()
	alert.State = "resolved"
	alert.ResolvedAt = &now

	delete(re.activeAlerts, stateKey)
	re.alertStates[stateKey].firing = false

	for _, notifier := range re.notifiers {
		go func(n Notifier, a *models.Alert) {
			if err := n.Send(ctx, a); err != nil {
				re.logger.Error("Failed to send resolution notification", zap.Error(err))
			}
		}(notifier, alert)
	}

	re.logger.Info("Alert resolved",
		zap.String("alert_id", alert.ID),
		zap.String("rule_id", rule.ID))
}

func ParseExpression(expr string) (*ParsedExpression, error) {
	re := regexp.MustCompile(`^([a-zA-Z_][a-zA-Z0-9_]*)(\{[^}]*\})?\s*(>=|<=|>|<|==|!=)\s*([-+]?\d*\.?\d+)$`)
	matches := re.FindStringSubmatch(strings.TrimSpace(expr))

	if matches == nil {
		return nil, fmt.Errorf("invalid expression format: %s", expr)
	}

	parsed := &ParsedExpression{
		MetricName: matches[1],
		Operator:   matches[3],
		Tags:       make(map[string]string),
	}

	threshold, err := strconv.ParseFloat(matches[4], 64)
	if err != nil {
		return nil, fmt.Errorf("invalid threshold: %s", matches[4])
	}
	parsed.Threshold = threshold

	if matches[2] != "" {
		tagStr := strings.Trim(matches[2], "{}")
		for _, pair := range strings.Split(tagStr, ",") {
			kv := strings.SplitN(strings.TrimSpace(pair), "=", 2)
			if len(kv) == 2 {
				parsed.Tags[strings.TrimSpace(kv[0])] = strings.Trim(strings.TrimSpace(kv[1]), "\"")
			}
		}
	}

	return parsed, nil
}

func evaluateCondition(value float64, operator string, threshold float64) bool {
	switch operator {
	case ">":
		return value > threshold
	case ">=":
		return value >= threshold
	case "<":
		return value < threshold
	case "<=":
		return value <= threshold
	case "==":
		return value == threshold
	case "!=":
		return value != threshold
	default:
		return false
	}
}

func hashTags(tags map[string]string) string {
	var parts []string
	for k, v := range tags {
		parts = append(parts, fmt.Sprintf("%s=%s", k, v))
	}
	return strings.Join(parts, ",")
}

func mergeLabels(a, b map[string]string) map[string]string {
	result := make(map[string]string)
	for k, v := range a {
		result[k] = v
	}
	for k, v := range b {
		result[k] = v
	}
	return result
}

func (re *RuleEvaluator) GetActiveAlerts() []*models.Alert {
	re.mu.RLock()
	defer re.mu.RUnlock()

	alerts := make([]*models.Alert, 0, len(re.activeAlerts))
	for _, alert := range re.activeAlerts {
		alerts = append(alerts, alert)
	}
	return alerts
}

func (re *RuleEvaluator) GetRules() []*models.AlertRule {
	re.mu.RLock()
	defer re.mu.RUnlock()

	rules := make([]*models.AlertRule, 0, len(re.rules))
	for _, rule := range re.rules {
		rules = append(rules, rule)
	}
	return rules
}

func (re *RuleEvaluator) GetStats() map[string]interface{} {
	re.mu.RLock()
	defer re.mu.RUnlock()

	stats := make(map[string]interface{})
	stats["rules_count"] = len(re.rules)
	stats["active_alerts"] = len(re.activeAlerts)
	stats["queue_size"] = len(re.taskQueue)
	stats["result_queue_size"] = len(re.resultQueue)
	stats["pending_tasks"] = re.pendingTasks
	stats["workers"] = re.workers

	return stats
}

func (re *RuleEvaluator) Stop() {
	re.cancel()
	re.cron.Stop()
	close(re.taskQueue)
	re.workerWg.Wait()
	close(re.resultQueue)
	re.logger.Info("Alert rule evaluator stopped")
}
