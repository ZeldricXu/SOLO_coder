package alerting

import (
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"session130/internal/logger"
	"session130/internal/metrics"
	"session130/pkg/models"
)

type Notifier interface {
	Notify(alert *models.Alert) error
}

type LogNotifier struct{}

func (n *LogNotifier) Notify(alert *models.Alert) error {
	logger.Info("", "alert triggered", map[string]interface{}{
		"alert_id":   alert.AlertID,
		"rule_id":    alert.RuleID,
		"rule_name":  alert.RuleName,
		"metric":     alert.Metric,
		"value":      alert.Value,
		"threshold":  alert.Threshold,
		"status":     alert.Status,
	})
	return nil
}

type CacheEntry struct {
	Value      float64
	ExpiresAt  time.Time
	AccessedAt time.Time
}

type Cache interface {
	Get(key string) (float64, bool)
	Set(key string, value float64)
	Invalidate(key string)
	Stop()
}

type L1Cache struct {
	mu    sync.RWMutex
	items map[string]CacheEntry
	max   int
	ttl   time.Duration
}

func NewL1Cache(max int, ttl time.Duration) *L1Cache {
	if max <= 0 {
		max = 1000
	}
	if ttl <= 0 {
		ttl = 5 * time.Second
	}
	return &L1Cache{
		items: make(map[string]CacheEntry),
		max:   max,
		ttl:   ttl,
	}
}

func (c *L1Cache) Get(key string) (float64, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	entry, exists := c.items[key]
	if !exists {
		return 0, false
	}
	if time.Now().After(entry.ExpiresAt) {
		return 0, false
	}
	entry.AccessedAt = time.Now()
	c.items[key] = entry
	return entry.Value, true
}

func (c *L1Cache) Set(key string, value float64) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.items) >= c.max {
		c.evictLRU()
	}

	c.items[key] = CacheEntry{
		Value:      value,
		ExpiresAt:  time.Now().Add(c.ttl),
		AccessedAt: time.Now(),
	}
}

func (c *L1Cache) evictLRU() {
	var oldestKey string
	var oldestTime time.Time
	now := time.Now()
	for k, v := range c.items {
		if oldestTime.IsZero() || v.AccessedAt.Before(oldestTime) {
			oldestKey = k
			oldestTime = v.AccessedAt
			if oldestTime.Before(now.Add(-c.ttl)) {
				break
			}
		}
	}
	if oldestKey != "" {
		delete(c.items, oldestKey)
	}
}

func (c *L1Cache) Invalidate(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.items, key)
}

func (c *L1Cache) InvalidateAll() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items = make(map[string]CacheEntry)
}

func (c *L1Cache) Stop() {}

type L2Cache struct {
	mu       sync.RWMutex
	items    map[string]CacheEntry
	ttl      time.Duration
	stopChan chan struct{}
}

func NewL2Cache(ttl time.Duration) *L2Cache {
	c := &L2Cache{
		items:    make(map[string]CacheEntry),
		ttl:      ttl,
		stopChan: make(chan struct{}),
	}
	go c.cleanupLoop()
	return c
}

func (c *L2Cache) cleanupLoop() {
	ticker := time.NewTicker(c.ttl / 2)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			c.cleanupExpired()
		case <-c.stopChan:
			return
		}
	}
}

func (c *L2Cache) cleanupExpired() {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for k, v := range c.items {
		if now.After(v.ExpiresAt) {
			delete(c.items, k)
		}
	}
}

func (c *L2Cache) Get(key string) (float64, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	entry, exists := c.items[key]
	if !exists {
		return 0, false
	}
	if time.Now().After(entry.ExpiresAt) {
		return 0, false
	}
	return entry.Value, true
}

func (c *L2Cache) Set(key string, value float64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items[key] = CacheEntry{
		Value:      value,
		ExpiresAt:  time.Now().Add(c.ttl),
		AccessedAt: time.Now(),
	}
}

func (c *L2Cache) Invalidate(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.items, key)
}

func (c *L2Cache) Stop() {
	close(c.stopChan)
}

type MultiLevelCache struct {
	l1 *L1Cache
	l2 *L2Cache
}

func NewMultiLevelCache(l1Max int, l2TTL time.Duration) *MultiLevelCache {
	return &MultiLevelCache{
		l1: NewL1Cache(l1Max, 5*time.Second),
		l2: NewL2Cache(l2TTL),
	}
}

func (c *MultiLevelCache) Get(key string) (float64, bool) {
	if val, ok := c.l1.Get(key); ok {
		return val, true
	}
	if val, ok := c.l2.Get(key); ok {
		c.l1.Set(key, val)
		return val, true
	}
	return 0, false
}

func (c *MultiLevelCache) Set(key string, value float64) {
	c.l1.Set(key, value)
	c.l2.Set(key, value)
}

func (c *MultiLevelCache) Invalidate(key string) {
	c.l1.Invalidate(key)
	c.l2.Invalidate(key)
}

func (c *MultiLevelCache) Stop() {
	c.l2.Stop()
}

type ConditionEvaluator interface {
	Evaluate(rule *models.AlertRule, value float64) bool
}

type conditionEvaluator struct{}

func newConditionEvaluator() ConditionEvaluator {
	return &conditionEvaluator{}
}

func (e *conditionEvaluator) Evaluate(rule *models.AlertRule, value float64) bool {
	switch rule.Condition {
	case "gt", ">":
		return value > rule.Threshold
	case "gte", ">=":
		return value >= rule.Threshold
	case "lt", "<":
		return value < rule.Threshold
	case "lte", "<=":
		return value <= rule.Threshold
	case "eq", "==":
		return value == rule.Threshold
	case "ne", "!=":
		return value != rule.Threshold
	default:
		return value > rule.Threshold
	}
}

type AlertManager interface {
	AddRule(rule *models.AlertRule) error
	RemoveRule(ruleID string)
	GetRule(ruleID string) (*models.AlertRule, error)
	ListRules() []*models.AlertRule
	RegisterNotifier(name string, notifier Notifier)
	SetMetricSource(source func(metric string, labels map[string]string) (float64, error))
	PreWarmCache()
	Evaluate()
	GetAlerts(status string) []*models.Alert
	GetCacheStats() map[string]interface{}
	Start()
	Stop()
}

type Evaluator struct {
	mu                sync.RWMutex
	rules             map[string]*models.AlertRule
	alerts            map[string]*models.Alert
	notifiers         map[string]Notifier
	metricSource      func(metric string, labels map[string]string) (float64, error)
	stopChan          chan struct{}
	interval          time.Duration
	cache             Cache
	conditionEval     ConditionEvaluator
	batchSize         int
	evalWorkers       int
	taskQueue         chan *models.AlertRule
	wg                sync.WaitGroup
	sortedLabelsCache sync.Map
}

var (
	instance *Evaluator
	once     sync.Once
)

func NewEvaluator(interval time.Duration) *Evaluator {
	return NewEvaluatorWithConfig(interval, 10000, 30*time.Second, 4)
}

func NewEvaluatorWithConfig(interval time.Duration, l1Max int, l2TTL time.Duration, evalWorkers int) *Evaluator {
	if evalWorkers <= 0 {
		evalWorkers = 4
	}
	e := &Evaluator{
		rules:         make(map[string]*models.AlertRule),
		alerts:        make(map[string]*models.Alert),
		notifiers:     make(map[string]Notifier),
		stopChan:      make(chan struct{}),
		interval:      interval,
		cache:         NewMultiLevelCache(l1Max, l2TTL),
		conditionEval: newConditionEvaluator(),
		batchSize:     100,
		evalWorkers:   evalWorkers,
		taskQueue:     make(chan *models.AlertRule, 10000),
	}
	e.notifiers["log"] = &LogNotifier{}
	e.startEvalWorkers()
	return e
}

func (e *Evaluator) startEvalWorkers() {
	for i := 0; i < e.evalWorkers; i++ {
		e.wg.Add(1)
		go e.evalWorker()
	}
}

func (e *Evaluator) evalWorker() {
	defer e.wg.Done()
	for {
		select {
		case rule := <-e.taskQueue:
			e.evaluateRule(rule)
		case <-e.stopChan:
			return
		}
	}
}

func GetEvaluator() *Evaluator {
	once.Do(func() {
		instance = NewEvaluator(30 * time.Second)
	})
	return instance
}

func (e *Evaluator) SetMetricSource(source func(metric string, labels map[string]string) (float64, error)) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.metricSource = source
}

func (e *Evaluator) AddRule(rule *models.AlertRule) error {
	if rule.RuleID == "" {
		return errors.New("rule_id is required")
	}
	if rule.Metric == "" {
		return errors.New("metric is required")
	}

	e.mu.Lock()
	defer e.mu.Unlock()

	e.rules[rule.RuleID] = rule
	e.cache.Invalidate(buildCacheKey(rule.Metric, rule.Labels))
	logger.Info("", "alert rule added", map[string]interface{}{
		"rule_id": rule.RuleID,
		"name":    rule.Name,
		"metric":  rule.Metric,
	})
	return nil
}

func (e *Evaluator) RemoveRule(ruleID string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if rule, exists := e.rules[ruleID]; exists {
		e.cache.Invalidate(buildCacheKey(rule.Metric, rule.Labels))
	}
	delete(e.rules, ruleID)
	logger.Info("", "alert rule removed", map[string]interface{}{
		"rule_id": ruleID,
	})
}

func (e *Evaluator) GetRule(ruleID string) (*models.AlertRule, error) {
	e.mu.RLock()
	defer e.mu.RUnlock()

	rule, exists := e.rules[ruleID]
	if !exists {
		return nil, fmt.Errorf("rule %s not found", ruleID)
	}
	return rule, nil
}

func (e *Evaluator) ListRules() []*models.AlertRule {
	e.mu.RLock()
	defer e.mu.RUnlock()

	rules := make([]*models.AlertRule, 0, len(e.rules))
	for _, rule := range e.rules {
		rules = append(rules, rule)
	}
	return rules
}

func (e *Evaluator) RegisterNotifier(name string, notifier Notifier) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.notifiers[name] = notifier
}

func (e *Evaluator) getEnabledRules() []*models.AlertRule {
	e.mu.RLock()
	defer e.mu.RUnlock()

	rules := make([]*models.AlertRule, 0, len(e.rules))
	for _, rule := range e.rules {
		if rule.Enabled {
			rules = append(rules, rule)
		}
	}
	return rules
}

func (e *Evaluator) PreWarmCache() {
	rules := e.getEnabledRules()

	for _, rule := range rules {
		key := buildCacheKey(rule.Metric, rule.Labels)
		if _, ok := e.cache.Get(key); !ok {
			if value, err := e.fetchMetricValue(rule); err == nil {
				e.cache.Set(key, value)
			}
		}
	}
	logger.Info("", "alert cache pre-warmed", map[string]interface{}{
		"rules_count": len(rules),
	})
}

func (e *Evaluator) Evaluate() {
	rules := e.getEnabledRules()

	for _, rule := range rules {
		select {
		case e.taskQueue <- rule:
		default:
			e.evaluateRule(rule)
		}
	}
}

func (e *Evaluator) evaluateRule(rule *models.AlertRule) {
	cacheKey := buildCacheKey(rule.Metric, rule.Labels)

	value, err := e.getOrFetchMetric(rule, cacheKey)
	if err != nil {
		logger.Warn("", "failed to get metric value for alert evaluation", map[string]interface{}{
			"rule_id": rule.RuleID,
			"metric":  rule.Metric,
			"error":   err.Error(),
		})
		return
	}

	triggered := e.conditionEval.Evaluate(rule, value)
	alertKey := buildAlertKey(rule)

	e.updateAlertState(rule, value, triggered, alertKey)
}

func (e *Evaluator) getOrFetchMetric(rule *models.AlertRule, cacheKey string) (float64, error) {
	if value, ok := e.cache.Get(cacheKey); ok {
		return value, nil
	}

	value, err := e.fetchMetricValue(rule)
	if err != nil {
		return 0, err
	}

	e.cache.Set(cacheKey, value)
	return value, nil
}

func (e *Evaluator) fetchMetricValue(rule *models.AlertRule) (float64, error) {
	if e.metricSource != nil {
		return e.metricSource(rule.Metric, rule.Labels)
	}
	return 0, errors.New("no metric source configured")
}

func (e *Evaluator) updateAlertState(rule *models.AlertRule, value float64, triggered bool, alertKey string) {
	e.mu.Lock()
	defer e.mu.Unlock()

	existingAlert, exists := e.alerts[alertKey]

	if triggered {
		e.handleTriggeredAlert(rule, value, existingAlert, exists, alertKey)
	} else {
		e.handleResolvedAlert(existingAlert, exists, rule.Labels)
	}
}

func (e *Evaluator) handleTriggeredAlert(rule *models.AlertRule, value float64, existingAlert *models.Alert, exists bool, alertKey string) {
	if !exists {
		alert := &models.Alert{
			AlertID:   fmt.Sprintf("alert_%d", time.Now().UnixNano()),
			RuleID:    rule.RuleID,
			RuleName:  rule.Name,
			Metric:    rule.Metric,
			Value:     value,
			Threshold: rule.Threshold,
			Status:    "firing",
			Labels:    rule.Labels,
			StartedAt: time.Now(),
		}
		e.alerts[alertKey] = alert
		e.notifyAll(alert)
		metrics.Inc("alerts_fired", rule.Labels)
	} else if existingAlert.Status == "firing" {
		existingAlert.Value = value
	}
}

func (e *Evaluator) handleResolvedAlert(existingAlert *models.Alert, exists bool, labels map[string]string) {
	if exists && existingAlert.Status == "firing" {
		now := time.Now()
		existingAlert.Status = "resolved"
		existingAlert.ResolvedAt = &now
		e.notifyAll(existingAlert)
		metrics.Inc("alerts_resolved", labels)
	}
}

func (e *Evaluator) notifyAll(alert *models.Alert) {
	if ch, exists := alert.Labels["notify_channels"]; exists {
		if notifier, ok := e.notifiers[ch]; ok {
			go func(n Notifier) {
				if err := n.Notify(alert); err != nil {
					logger.Error("", "failed to send notification", map[string]interface{}{
						"alert_id": alert.AlertID,
						"error":    err.Error(),
					})
				}
			}(notifier)
		}
	}

	if notifier, exists := e.notifiers["log"]; exists {
		go notifier.Notify(alert)
	}
}

func (e *Evaluator) GetAlerts(status string) []*models.Alert {
	e.mu.RLock()
	defer e.mu.RUnlock()

	alerts := make([]*models.Alert, 0, len(e.alerts))
	for _, alert := range e.alerts {
		if status == "" || alert.Status == status {
			alerts = append(alerts, alert)
		}
	}
	return alerts
}

func (e *Evaluator) GetCacheStats() map[string]interface{} {
	return map[string]interface{}{
		"eval_workers": e.evalWorkers,
		"queue_size":   len(e.taskQueue),
		"batch_size":   e.batchSize,
	}
}

func (e *Evaluator) Start() {
	ticker := time.NewTicker(e.interval)
	go func() {
		for {
			select {
			case <-ticker.C:
				e.Evaluate()
			case <-e.stopChan:
				ticker.Stop()
				return
			}
		}
	}()
	logger.Info("", "alert evaluator started", map[string]interface{}{
		"interval_seconds": e.interval.Seconds(),
		"eval_workers":     e.evalWorkers,
	})
}

func (e *Evaluator) Stop() {
	close(e.stopChan)
	e.cache.Stop()
	e.wg.Wait()
	logger.Info("", "alert evaluator stopped", nil)
}

func getSortedKeys(labels map[string]string) []string {
	keys := make([]string, 0, len(labels))
	for k := range labels {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func buildCacheKey(metric string, labels map[string]string) string {
	if len(labels) == 0 {
		return metric
	}

	keys := getSortedKeys(labels)
	var sb strings.Builder
	sb.WriteString(metric)
	for _, k := range keys {
		sb.WriteByte('|')
		sb.WriteString(k)
		sb.WriteByte('=')
		sb.WriteString(labels[k])
	}
	return sb.String()
}

func buildAlertKey(rule *models.AlertRule) string {
	if len(rule.Labels) == 0 {
		return rule.RuleID
	}

	keys := getSortedKeys(rule.Labels)
	var sb strings.Builder
	sb.WriteString(rule.RuleID)
	for _, k := range keys {
		sb.WriteByte('_')
		sb.WriteString(k)
		sb.WriteByte('=')
		sb.WriteString(rule.Labels[k])
	}
	return sb.String()
}

func EvaluateCondition(rule *models.AlertRule, value float64) bool {
	return GetEvaluator().conditionEval.Evaluate(rule, value)
}
