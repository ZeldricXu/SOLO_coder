package alerting

import (
	"container/list"
	"context"
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/robfig/cron/v3"
	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/pkg/common"
)

type Notifier interface {
	Notify(ctx context.Context, alert *common.Alert) error
	Name() string
}

type ConsoleNotifier struct{}

func NewConsoleNotifier() *ConsoleNotifier {
	return &ConsoleNotifier{}
}

func (n *ConsoleNotifier) Name() string {
	return "console"
}

func (n *ConsoleNotifier) Notify(ctx context.Context, alert *common.Alert) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}

	logger.Warn("ALERT TRIGGERED", map[string]interface{}{
		"alert_id":   alert.ID,
		"rule_id":    alert.RuleID,
		"rule_name":  alert.RuleName,
		"severity":   alert.Severity,
		"message":    alert.Message,
		"status":     alert.Status,
		"starts_at":  alert.StartsAt,
		"labels":     alert.Labels,
	})
	return nil
}

type WebhookNotifier struct {
	URL string
}

func NewWebhookNotifier(url string) *WebhookNotifier {
	return &WebhookNotifier{URL: url}
}

func (n *WebhookNotifier) Name() string {
	return "webhook"
}

func (n *WebhookNotifier) Notify(ctx context.Context, alert *common.Alert) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}

	logger.Info("Webhook notification", map[string]interface{}{
		"url":      n.URL,
		"alert_id": alert.ID,
		"message":  alert.Message,
	})
	return nil
}

type EmailNotifier struct {
	To       []string
	From     string
	SMTPHost string
	SMTPPort int
}

func NewEmailNotifier(to []string, from, host string, port int) *EmailNotifier {
	return &EmailNotifier{
		To:       to,
		From:     from,
		SMTPHost: host,
		SMTPPort: port,
	}
}

func (n *EmailNotifier) Name() string {
	return "email"
}

func (n *EmailNotifier) Notify(ctx context.Context, alert *common.Alert) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}

	logger.Info("Email notification", map[string]interface{}{
		"to":       n.To,
		"alert_id": alert.ID,
		"severity": alert.Severity,
	})
	return nil
}

type evalCacheEntry struct {
	key       string
	result    bool
	expiresAt time.Time
	element   *list.Element
}

type EvalCache struct {
	capacity int
	ttl      time.Duration
	items    map[string]*evalCacheEntry
	order    *list.List
	mu       sync.RWMutex
	hits     int64
	misses   int64
}

func NewEvalCache(capacity int, ttl time.Duration) *EvalCache {
	return &EvalCache{
		capacity: capacity,
		ttl:      ttl,
		items:    make(map[string]*evalCacheEntry),
		order:    list.New(),
	}
}

func (c *EvalCache) Get(key string) (bool, bool) {
	c.mu.RLock()
	entry, exists := c.items[key]
	c.mu.RUnlock()

	if !exists {
		c.mu.Lock()
		c.misses++
		c.mu.Unlock()
		return false, false
	}

	if time.Now().After(entry.expiresAt) {
		c.mu.Lock()
		c.order.Remove(entry.element)
		delete(c.items, key)
		c.misses++
		c.mu.Unlock()
		return false, false
	}

	c.mu.Lock()
	c.order.MoveToFront(entry.element)
	c.hits++
	c.mu.Unlock()

	return entry.result, true
}

func (c *EvalCache) Set(key string, result bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if existing, ok := c.items[key]; ok {
		existing.result = result
		existing.expiresAt = time.Now().Add(c.ttl)
		c.order.MoveToFront(existing.element)
		return
	}

	if c.order.Len() >= c.capacity {
		oldest := c.order.Back()
		if oldest != nil {
			if entry, ok := oldest.Value.(*evalCacheEntry); ok {
				delete(c.items, entry.key)
			}
			c.order.Remove(oldest)
		}
	}

	entry := &evalCacheEntry{
		key:       key,
		result:    result,
		expiresAt: time.Now().Add(c.ttl),
	}
	entry.element = c.order.PushFront(entry)
	c.items[key] = entry
}

func (c *EvalCache) Invalidate(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if entry, ok := c.items[key]; ok {
		c.order.Remove(entry.element)
		delete(c.items, key)
	}
}

func (c *EvalCache) InvalidatePattern(pattern string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	for key, entry := range c.items {
		if strings.Contains(key, pattern) {
			c.order.Remove(entry.element)
			delete(c.items, key)
		}
	}
}

func (c *EvalCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.items = make(map[string]*evalCacheEntry)
	c.order.Init()
}

func (c *EvalCache) Stats() (hits, misses int64, size int) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.hits, c.misses, len(c.items)
}

type MetricPreloader interface {
	Preload(metrics []common.Metric) error
	Name() string
}

type HistoryPreloader struct {
	evaluator *ExpressionEvaluator
}

func NewHistoryPreloader(evaluator *ExpressionEvaluator) *HistoryPreloader {
	return &HistoryPreloader{evaluator: evaluator}
}

func (p *HistoryPreloader) Name() string {
	return "history_preloader"
}

func (p *HistoryPreloader) Preload(metrics []common.Metric) error {
	for _, metric := range metrics {
		p.evaluator.AddMetric(metric)
	}
	logger.Info("Preloaded metrics", map[string]interface{}{
		"count": len(metrics),
	})
	return nil
}

type RuleEvaluator interface {
	Evaluate(expr string, data map[string]float64) (bool, error)
}

type ExpressionEvaluator struct {
	metricStore map[string][]common.Metric
	mu          sync.RWMutex
}

func NewExpressionEvaluator() *ExpressionEvaluator {
	return &ExpressionEvaluator{
		metricStore: make(map[string][]common.Metric),
	}
}

func (e *ExpressionEvaluator) AddMetric(metric common.Metric) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.metricStore[metric.Name] = append(e.metricStore[metric.Name], metric)
	if len(e.metricStore[metric.Name]) > 1000 {
		e.metricStore[metric.Name] = e.metricStore[metric.Name][1:]
	}
}

func (e *ExpressionEvaluator) GetMetricValue(name string) (float64, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()

	metrics, exists := e.metricStore[name]
	if !exists || len(metrics) == 0 {
		return 0, false
	}
	return metrics[len(metrics)-1].Value, true
}

func (e *ExpressionEvaluator) GetMetricHistory(name string, limit int) []common.Metric {
	e.mu.RLock()
	defer e.mu.RUnlock()

	metrics, exists := e.metricStore[name]
	if !exists {
		return nil
	}

	if limit > 0 && len(metrics) > limit {
		return metrics[len(metrics)-limit:]
	}
	return metrics
}

func (e *ExpressionEvaluator) Evaluate(expr string, data map[string]float64) (bool, error) {
	expr = strings.TrimSpace(expr)

	tokens, err := parseExpression(expr)
	if err != nil {
		return false, fmt.Errorf("parse error: %w", err)
	}

	return evaluateTokens(tokens, data, e)
}

func parseExpression(expr string) ([]string, error) {
	var tokens []string
	var current strings.Builder
	inMetric := false

	for i := 0; i < len(expr); i++ {
		ch := expr[i]

		if ch == '{' {
			if inMetric {
				return nil, fmt.Errorf("nested braces not allowed")
			}
			inMetric = true
			current.WriteByte(ch)
		} else if ch == '}' {
			if !inMetric {
				return nil, fmt.Errorf("unmatched closing brace")
			}
			inMetric = false
			current.WriteByte(ch)
			tokens = append(tokens, current.String())
			current.Reset()
		} else if !inMetric && (ch == ' ' || ch == '\t' || ch == '\n') {
			if current.Len() > 0 {
				tokens = append(tokens, current.String())
				current.Reset()
			}
		} else if !inMetric && (ch == '(' || ch == ')' || ch == '!' || ch == '&' || ch == '|' || ch == '<' || ch == '>' || ch == '=') {
			if current.Len() > 0 {
				tokens = append(tokens, current.String())
				current.Reset()
			}
			if ch == '&' || ch == '|' || ch == '<' || ch == '>' || ch == '=' {
				if i+1 < len(expr) && expr[i+1] == ch {
					tokens = append(tokens, string(ch)+string(expr[i+1]))
					i++
				} else if ch == '<' || ch == '>' {
					if i+1 < len(expr) && expr[i+1] == '=' {
						tokens = append(tokens, string(ch)+string(expr[i+1]))
						i++
					} else {
						tokens = append(tokens, string(ch))
					}
				} else {
					tokens = append(tokens, string(ch))
				}
			} else {
				tokens = append(tokens, string(ch))
			}
		} else if !inMetric && (ch == '+' || ch == '-' || ch == '*' || ch == '/') {
			if current.Len() > 0 {
				tokens = append(tokens, current.String())
				current.Reset()
			}
			tokens = append(tokens, string(ch))
		} else {
			current.WriteByte(ch)
		}
	}

	if current.Len() > 0 {
		tokens = append(tokens, current.String())
	}

	if inMetric {
		return nil, fmt.Errorf("unmatched opening brace")
	}

	return tokens, nil
}

func evaluateTokens(tokens []string, data map[string]float64, eval *ExpressionEvaluator) (bool, error) {
	if len(tokens) == 0 {
		return false, fmt.Errorf("empty expression")
	}

	values := make(map[string]float64)
	for k, v := range data {
		values[k] = v
	}

	metricRegex := regexp.MustCompile(`^{([^}]+)}$`)
	for i, token := range tokens {
		if matches := metricRegex.FindStringSubmatch(token); matches != nil {
			metricName := matches[1]
			if val, ok := values[metricName]; ok {
				tokens[i] = fmt.Sprintf("%f", val)
			} else if val, ok := eval.GetMetricValue(metricName); ok {
				tokens[i] = fmt.Sprintf("%f", val)
			} else {
				tokens[i] = "NaN"
			}
		}
	}

	return evaluateLogical(tokens)
}

func evaluateLogical(tokens []string) (bool, error) {
	andParts := splitTokens(tokens, "&&")

	var results []bool
	for _, part := range andParts {
		orParts := splitTokens(part, "||")
		var orResults []bool
		for _, orPart := range orParts {
			result, err := evaluateComparison(orPart)
			if err != nil {
				return false, err
			}
			orResults = append(orResults, result)
		}

		andResult := orResults[0]
		for _, r := range orResults[1:] {
			andResult = andResult || r
		}
		results = append(results, andResult)
	}

	if len(results) == 0 {
		return false, fmt.Errorf("no logical expressions found")
	}

	result := results[0]
	for _, r := range results[1:] {
		result = result && r
	}

	return result, nil
}

func splitTokens(tokens []string, sep string) [][]string {
	var result [][]string
	var current []string

	for _, token := range tokens {
		if token == sep {
			if len(current) > 0 {
				result = append(result, current)
				current = nil
			}
		} else {
			current = append(current, token)
		}
	}

	if len(current) > 0 {
		result = append(result, current)
	}

	return result
}

func evaluateComparison(tokens []string) (bool, error) {
	if len(tokens) < 3 {
		if len(tokens) == 1 {
			val, err := strconv.ParseFloat(tokens[0], 64)
			if err != nil {
				return false, nil
			}
			return val != 0, nil
		}
		return false, fmt.Errorf("invalid comparison: %v", tokens)
	}

	var opIndex int
	var op string
	found := false

	for i, token := range tokens {
		if token == ">" || token == "<" || token == "==" || token == "!=" || token == ">=" || token == "<=" {
			op = token
			opIndex = i
			found = true
			break
		}
	}

	if !found {
		val, err := evaluateArithmetic(tokens)
		if err != nil {
			return false, err
		}
		return val != 0, nil
	}

	left, err := evaluateArithmetic(tokens[:opIndex])
	if err != nil {
		return false, err
	}

	right, err := evaluateArithmetic(tokens[opIndex+1:])
	if err != nil {
		return false, err
	}

	if math.IsNaN(left) || math.IsNaN(right) {
		return false, nil
	}

	switch op {
	case ">":
		return left > right, nil
	case "<":
		return left < right, nil
	case "==":
		return math.Abs(left-right) < 0.0001, nil
	case "!=":
		return math.Abs(left-right) >= 0.0001, nil
	case ">=":
		return left >= right, nil
	case "<=":
		return left <= right, nil
	default:
		return false, fmt.Errorf("unknown operator: %s", op)
	}
}

func evaluateArithmetic(tokens []string) (float64, error) {
	if len(tokens) == 0 {
		return 0, fmt.Errorf("empty arithmetic expression")
	}

	if len(tokens) == 1 {
		val, err := strconv.ParseFloat(tokens[0], 64)
		if err != nil {
			return math.NaN(), fmt.Errorf("invalid number: %s", tokens[0])
		}
		return val, nil
	}

	for precedence := 0; precedence < 2; precedence++ {
		for i := 1; i < len(tokens)-1; i += 2 {
			op := tokens[i]
			isHigher := (precedence == 0 && (op == "*" || op == "/")) ||
				(precedence == 1 && (op == "+" || op == "-"))

			if isHigher {
				left, err := evaluateArithmetic(tokens[:i])
				if err != nil {
					return 0, err
				}
				right, err := evaluateArithmetic(tokens[i+1:])
				if err != nil {
					return 0, err
				}

				switch op {
				case "+":
					return left + right, nil
				case "-":
					return left - right, nil
				case "*":
					return left * right, nil
				case "/":
					if right == 0 {
						return math.NaN(), nil
					}
					return left / right, nil
				}
			}
		}
	}

	return math.NaN(), fmt.Errorf("invalid arithmetic expression: %v", tokens)
}

type AlertEngine struct {
	rules        map[string]*common.AlertRule
	alerts       map[string]*common.Alert
	notifiers    []Notifier
	evaluator    *ExpressionEvaluator
	cron         *cron.Cron
	cronEntries  map[string]cron.EntryID
	mu           sync.RWMutex
	ctx          context.Context
	cancel       context.CancelFunc
	running      bool
	cache        *EvalCache
	preloader    *HistoryPreloader
	preloaded    bool
}

func NewAlertEngine() *AlertEngine {
	ctx, cancel := context.WithCancel(context.Background())
	evaluator := NewExpressionEvaluator()
	return &AlertEngine{
		rules:       make(map[string]*common.AlertRule),
		alerts:      make(map[string]*common.Alert),
		notifiers:   []Notifier{NewConsoleNotifier()},
		evaluator:   evaluator,
		cron:        cron.New(),
		cronEntries: make(map[string]cron.EntryID),
		ctx:         ctx,
		cancel:      cancel,
		cache:       NewEvalCache(10000, 30*time.Second),
		preloader:   NewHistoryPreloader(evaluator),
		preloaded:   false,
	}
}

func NewAlertEngineWithCache(cacheCapacity int, cacheTTL time.Duration) *AlertEngine {
	ctx, cancel := context.WithCancel(context.Background())
	evaluator := NewExpressionEvaluator()
	return &AlertEngine{
		rules:       make(map[string]*common.AlertRule),
		alerts:      make(map[string]*common.Alert),
		notifiers:   []Notifier{NewConsoleNotifier()},
		evaluator:   evaluator,
		cron:        cron.New(),
		cronEntries: make(map[string]cron.EntryID),
		ctx:         ctx,
		cancel:      cancel,
		cache:       NewEvalCache(cacheCapacity, cacheTTL),
		preloader:   NewHistoryPreloader(evaluator),
		preloaded:   false,
	}
}

func (e *AlertEngine) AddNotifier(notifier Notifier) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.notifiers = append(e.notifiers, notifier)
	logger.Info("Added notifier", map[string]interface{}{"name": notifier.Name()})
}

func (e *AlertEngine) AddRule(rule *common.AlertRule) error {
	if rule.ID == "" {
		rule.ID = common.NewID()
	}

	e.mu.Lock()
	defer e.mu.Unlock()

	if _, exists := e.rules[rule.ID]; exists {
		return fmt.Errorf("%w: rule %s", common.ErrAlreadyExists, rule.ID)
	}

	e.rules[rule.ID] = rule
	logger.Info("Added alert rule", map[string]interface{}{
		"rule_id":   rule.ID,
		"rule_name": rule.Name,
		"enabled":   rule.Enabled,
	})

	if rule.Enabled && rule.CronExpr != "" {
		entryID, err := e.cron.AddFunc(rule.CronExpr, func() {
			e.evaluateRule(rule.ID)
		})
		if err != nil {
			logger.Warn("Failed to schedule rule", map[string]interface{}{
				"rule_id":   rule.ID,
				"cron_expr": rule.CronExpr,
				"error":     err.Error(),
			})
		} else {
			e.cronEntries[rule.ID] = entryID
			logger.Info("Scheduled alert rule", map[string]interface{}{
				"rule_id":   rule.ID,
				"cron_expr": rule.CronExpr,
			})
		}
	}

	e.cache.InvalidatePattern(rule.Expression)

	return nil
}

func (e *AlertEngine) RemoveRule(ruleID string) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if _, exists := e.rules[ruleID]; !exists {
		return fmt.Errorf("%w: rule %s", common.ErrNotFound, ruleID)
	}

	rule := e.rules[ruleID]

	if entryID, exists := e.cronEntries[ruleID]; exists {
		e.cron.Remove(entryID)
		delete(e.cronEntries, ruleID)
	}

	e.cache.InvalidatePattern(rule.Expression)

	delete(e.rules, ruleID)
	logger.Info("Removed alert rule", map[string]interface{}{"rule_id": ruleID})
	return nil
}

func (e *AlertEngine) buildCacheKey(ruleID string, expr string) string {
	metricNames := extractMetricNames(expr)
	e.evaluator.mu.RLock()
	var values []string
	for _, name := range metricNames {
		if metrics, ok := e.evaluator.metricStore[name]; ok && len(metrics) > 0 {
			values = append(values, fmt.Sprintf("%s:%.4f", name, metrics[len(metrics)-1].Value))
		}
	}
	e.evaluator.mu.RUnlock()
	return fmt.Sprintf("%s|%s", ruleID, strings.Join(values, ","))
}

func extractMetricNames(expr string) []string {
	re := regexp.MustCompile(`\{([^}]+)\}`)
	matches := re.FindAllStringSubmatch(expr, -1)
	var names []string
	for _, m := range matches {
		if len(m) > 1 {
			names = append(names, m[1])
		}
	}
	return names
}

func (e *AlertEngine) evaluateRule(ruleID string) {
	e.mu.RLock()
	rule, exists := e.rules[ruleID]
	e.mu.RUnlock()

	if !exists || !rule.Enabled {
		return
	}

	cacheKey := e.buildCacheKey(ruleID, rule.Expression)
	if cachedResult, found := e.cache.Get(cacheKey); found {
		e.processEvaluationResult(ruleID, rule, cachedResult)
		return
	}

	result, err := e.evaluator.Evaluate(rule.Expression, nil)
	if err != nil {
		logger.Error("Failed to evaluate rule", map[string]interface{}{
			"rule_id": ruleID,
			"error":   err.Error(),
		})
		return
	}

	e.cache.Set(cacheKey, result)

	e.processEvaluationResult(ruleID, rule, result)
}

func (e *AlertEngine) processEvaluationResult(ruleID string, rule *common.AlertRule, result bool) {
	e.mu.Lock()
	activeAlert, alertExists := e.alerts[ruleID]
	e.mu.Unlock()

	if result && !alertExists {
		alert := &common.Alert{
			ID:          common.NewID(),
			RuleID:      rule.ID,
			RuleName:    rule.Name,
			Severity:    rule.Severity,
			Message:     rule.Annotations["description"],
			Labels:      rule.Labels,
			Annotations: rule.Annotations,
			StartsAt:    time.Now(),
			Status:      "firing",
		}

		e.mu.Lock()
		e.alerts[ruleID] = alert
		e.mu.Unlock()

		go e.notifyAll(alert)
		logger.Warn("Alert fired", map[string]interface{}{
			"rule_id":    ruleID,
			"alert_id":   alert.ID,
			"severity":   rule.Severity,
			"expression": rule.Expression,
		})
	} else if !result && alertExists && activeAlert.Status == "firing" {
		activeAlert.Status = "resolved"
		activeAlert.EndsAt = time.Now()

		go e.notifyAll(activeAlert)
		logger.Info("Alert resolved", map[string]interface{}{
			"rule_id":  ruleID,
			"alert_id": activeAlert.ID,
			"duration": common.FormatDuration(activeAlert.EndsAt.Sub(activeAlert.StartsAt)),
		})
	}
}

func (e *AlertEngine) notifyAll(alert *common.Alert) {
	e.mu.RLock()
	notifiers := make([]Notifier, len(e.notifiers))
	copy(notifiers, e.notifiers)
	e.mu.RUnlock()

	var wg sync.WaitGroup
	for _, n := range notifiers {
		wg.Add(1)
		go func(notifier Notifier) {
			defer wg.Done()
			if err := notifier.Notify(e.ctx, alert); err != nil {
				logger.Error("Notification failed", map[string]interface{}{
					"notifier": notifier.Name(),
					"alert_id": alert.ID,
					"error":    err.Error(),
				})
			}
		}(n)
	}
	wg.Wait()
}

func (e *AlertEngine) EvaluateAll() {
	e.mu.RLock()
	rules := make([]string, 0, len(e.rules))
	for id := range e.rules {
		rules = append(rules, id)
	}
	e.mu.RUnlock()

	for _, id := range rules {
		e.evaluateRule(id)
	}
}

func (e *AlertEngine) ReportMetric(metric common.Metric) {
	e.evaluator.AddMetric(metric)

	e.cache.InvalidatePattern(metric.Name)
}

func (e *AlertEngine) PreloadMetrics(metrics []common.Metric) error {
	if err := e.preloader.Preload(metrics); err != nil {
		return err
	}

	e.preloaded = true

	e.EvaluateAll()

	logger.Info("Alert engine preloaded and initial evaluation completed", map[string]interface{}{
		"metric_count": len(metrics),
	})
	return nil
}

func (e *AlertEngine) IsPreloaded() bool {
	return e.preloaded
}

func (e *AlertEngine) GetCacheStats() (hits, misses int64, size int) {
	return e.cache.Stats()
}

func (e *AlertEngine) InvalidateCache() {
	e.cache.Clear()
	logger.Info("Alert engine cache invalidated")
}

func (e *AlertEngine) Start() {
	e.mu.Lock()
	defer e.mu.Unlock()

	if e.running {
		return
	}

	e.cron.Start()
	e.running = true
	logger.Info("Alert engine started")
}

func (e *AlertEngine) Stop() {
	e.mu.Lock()
	defer e.mu.Unlock()

	if !e.running {
		return
	}

	e.cancel()
	e.cron.Stop()
	e.running = false
	logger.Info("Alert engine stopped")
}

func (e *AlertEngine) GetActiveAlerts() []*common.Alert {
	e.mu.RLock()
	defer e.mu.RUnlock()

	alerts := make([]*common.Alert, 0)
	for _, alert := range e.alerts {
		if alert.Status == "firing" {
			alerts = append(alerts, alert)
		}
	}
	return alerts
}

func (e *AlertEngine) GetRules() []*common.AlertRule {
	e.mu.RLock()
	defer e.mu.RUnlock()

	rules := make([]*common.AlertRule, 0, len(e.rules))
	for _, rule := range e.rules {
		rules = append(rules, rule)
	}
	return rules
}
