package core

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"
	"unicode"

	"github.com/datatransform/platform/pkg/logger"
	"github.com/datatransform/platform/pkg/models"
	"github.com/datatransform/platform/pkg/service"
	"github.com/datatransform/platform/pkg/utils"
	"go.uber.org/zap"
)

const (
	ServiceName = "core_processor"

	defaultMaxConcurrency = 100
	defaultEventChanSize  = 1000

	defaultL1Capacity     = 10000
	defaultL1TTL          = 5 * time.Minute
	defaultL2TTL          = 30 * time.Minute
	defaultCacheKeyPrefix = "core:cache"

	CacheHit  = "hit"
	CacheMiss = "miss"
)

var (
	ErrConfigNotFound       = service.NewErrorDetail("CORE_001", "configuration not found", nil)
	ErrResourceAcquireTimeout = service.NewErrorDetail("CORE_002", "resource acquisition timeout", nil)
	ErrProcessingFailed     = service.NewErrorDetail("CORE_003", "processing failed", nil)
	ErrValidationFailed     = service.NewErrorDetail("CORE_004", "validation failed", nil)
	ErrResourceAcquire      = service.NewErrorDetail("CORE_005", "failed to acquire resource", nil)
	ErrCacheOperation       = service.NewErrorDetail("CORE_006", "cache operation failed", nil)
)

type Processor interface {
	Process(payload map[string]interface{}, rules map[string]interface{}) (map[string]interface{}, error)
}

type TransformRule struct {
	Type       string
	Parameters map[string]interface{}
}

type HandlerRequest struct {
	TraceID   string
	Params    map[string]interface{}
	Namespace string
	Payload   map[string]interface{}
	UseCache  bool
}

type HandlerResult struct {
	Success bool
	Data    map[string]interface{}
	Error   string
	Source  string
}

type ConfigStore interface {
	Load(namespace string) (*models.Config, error)
	Save(config *models.Config)
}

type ResourcePool interface {
	Acquire(timeout time.Duration) error
	Release()
}

type CacheEntry struct {
	Value      map[string]interface{}
	ExpireAt   time.Time
	HitCount   int64
	LastAccess time.Time
}

type CacheStats struct {
	L1Hits        int64
	L1Misses      int64
	L2Hits        int64
	L2Misses      int64
	Evictions     int64
	TotalRequests int64
}

type L1Cache struct {
	items    map[string]*CacheEntry
	capacity int
	ttl      time.Duration
	mu       sync.RWMutex
	stats    CacheStats
}

type L2Cache interface {
	Get(key string) (*CacheEntry, bool)
	Set(key string, entry *CacheEntry) error
	Delete(key string) error
	Invalidate(pattern string) error
}

type NoOpL2Cache struct{}

func (c *NoOpL2Cache) Get(key string) (*CacheEntry, bool) { return nil, false }
func (c *NoOpL2Cache) Set(key string, entry *CacheEntry) error { return nil }
func (c *NoOpL2Cache) Delete(key string) error { return nil }
func (c *NoOpL2Cache) Invalidate(pattern string) error { return nil }

type CacheConfig struct {
	L1Enabled  bool
	L1Capacity int
	L1TTL      time.Duration
	L2Enabled  bool
	L2TTL      time.Duration
}

type CoreProcessor struct {
	*service.BaseService

	maxConcurrency int
	resourcePool   chan struct{}
	configStore    map[string]*models.Config
	eventChan      chan models.Entity
	mu             sync.RWMutex

	cacheEnabled bool
	l1Cache      *L1Cache
	l2Cache      L2Cache
	cacheConfig  CacheConfig
}

func NewCoreProcessor(maxConcurrency int) *CoreProcessor {
	return NewCoreProcessorWithCache(maxConcurrency, CacheConfig{
		L1Enabled:  true,
		L1Capacity: defaultL1Capacity,
		L1TTL:      defaultL1TTL,
		L2Enabled:  false,
	})
}

func NewCoreProcessorWithCache(maxConcurrency int, cacheConfig CacheConfig) *CoreProcessor {
	if maxConcurrency <= 0 {
		maxConcurrency = defaultMaxConcurrency
	}

	if cacheConfig.L1Capacity <= 0 {
		cacheConfig.L1Capacity = defaultL1Capacity
	}
	if cacheConfig.L1TTL <= 0 {
		cacheConfig.L1TTL = defaultL1TTL
	}

	processor := &CoreProcessor{
		BaseService:    service.NewBaseService(ServiceName),
		maxConcurrency: maxConcurrency,
		resourcePool:   make(chan struct{}, maxConcurrency),
		configStore:    make(map[string]*models.Config),
		eventChan:      make(chan models.Entity, defaultEventChanSize),
		cacheEnabled:   cacheConfig.L1Enabled || cacheConfig.L2Enabled,
		cacheConfig:    cacheConfig,
	}

	if cacheConfig.L1Enabled {
		processor.l1Cache = &L1Cache{
			items:    make(map[string]*CacheEntry),
			capacity: cacheConfig.L1Capacity,
			ttl:      cacheConfig.L1TTL,
			stats:    CacheStats{},
		}
	}

	if cacheConfig.L2Enabled {
		processor.l2Cache = &NoOpL2Cache{}
	}

	return processor
}

func (p *CoreProcessor) SetL2Cache(l2Cache L2Cache) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.l2Cache = l2Cache
	p.cacheConfig.L2Enabled = l2Cache != nil
	p.cacheEnabled = p.cacheConfig.L1Enabled || p.cacheConfig.L2Enabled
}

func (p *CoreProcessor) Start() error {
	if err := p.ValidateStart(); err != nil {
		return err
	}

	logger.Info("starting core processor",
		zap.String("service", ServiceName),
		zap.Int("max_concurrency", p.maxConcurrency),
		zap.Bool("cache_enabled", p.cacheEnabled),
		zap.Bool("l1_enabled", p.cacheConfig.L1Enabled),
		zap.Bool("l2_enabled", p.cacheConfig.L2Enabled),
	)

	if p.cacheEnabled {
		go p.startCacheCleanup()
	}

	p.SetRunning(true)
	return nil
}

func (p *CoreProcessor) Stop() error {
	if err := p.ValidateStop(); err != nil {
		return err
	}

	logger.Info("stopping core processor",
		zap.String("service", ServiceName),
	)

	close(p.eventChan)
	p.SetRunning(false)
	return nil
}

func (p *CoreProcessor) startCacheCleanup() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		if !p.IsRunning() {
			return
		}
		p.cleanupExpiredCache()
	}
}

func (p *CoreProcessor) cleanupExpiredCache() {
	if p.l1Cache == nil {
		return
	}

	p.l1Cache.mu.Lock()
	defer p.l1Cache.mu.Unlock()

	now := time.Now()
	expired := 0

	for key, entry := range p.l1Cache.items {
		if now.After(entry.ExpireAt) {
			delete(p.l1Cache.items, key)
			expired++
		}
	}

	if expired > 0 {
		logger.Debug("cache cleanup completed",
			zap.Int("expired_entries", expired),
			zap.Int("remaining_entries", len(p.l1Cache.items)),
		)
	}
}

func (p *CoreProcessor) LoadConfig(namespace string) (*models.Config, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	config, exists := p.configStore[namespace]
	if !exists {
		return nil, wrapError(ErrConfigNotFound, "namespace: "+namespace)
	}

	return config, nil
}

func (p *CoreProcessor) SaveConfig(config *models.Config) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if config != nil && config.Namespace != "" {
		p.configStore[config.Namespace] = config
		logger.Debug("config saved",
			zap.String("namespace", config.Namespace),
			zap.Int("version", config.Version),
		)
	}
}

func (p *CoreProcessor) ValidateParams(params map[string]interface{}) error {
	if params == nil {
		return wrapError(ErrValidationFailed, "params cannot be nil")
	}

	if _, exists := params["type"]; !exists {
		return wrapError(ErrValidationFailed, "missing required field: type")
	}

	return nil
}

func (p *CoreProcessor) AcquireResource(timeout time.Duration) error {
	if timeout <= 0 {
		timeout = 30 * time.Second
	}

	select {
	case p.resourcePool <- struct{}{}:
		return nil
	case <-time.After(timeout):
		return ErrResourceAcquireTimeout
	}
}

func (p *CoreProcessor) ReleaseResource() {
	select {
	case <-p.resourcePool:
	default:
	}
}

func (p *CoreProcessor) Process(payload map[string]interface{}, rules map[string]interface{}) (map[string]interface{}, error) {
	result := make(map[string]interface{}, len(payload))

	for key, value := range payload {
		if rule, exists := rules[key]; exists {
			transformed, err := p.transformValue(value, rule)
			if err != nil {
				return nil, wrapError(ErrProcessingFailed, err.Error())
			}
			result[key] = transformed
		} else {
			result[key] = value
		}
	}

	result["processed_at"] = utils.CurrentTime().Format(time.RFC3339)
	result["transformed"] = true

	return result, nil
}

func (p *CoreProcessor) transformValue(value interface{}, rule interface{}) (interface{}, error) {
	ruleMap, ok := rule.(map[string]interface{})
	if !ok {
		return value, nil
	}

	ruleType, _ := ruleMap["type"].(string)
	switch strings.ToLower(ruleType) {
	case "string":
		return fmt.Sprintf("%v", value), nil

	case "int":
		return toInt(value)

	case "uppercase":
		return toUpper(toString(value)), nil

	case "lowercase":
		return toLower(toString(value)), nil

	case "trim":
		return strings.TrimSpace(toString(value)), nil

	default:
		return value, nil
	}
}

func (p *CoreProcessor) GenerateCacheKey(request *HandlerRequest) string {
	data := map[string]interface{}{
		"namespace": request.Namespace,
		"params":    request.Params,
		"payload":   request.Payload,
	}

	jsonData, err := json.Marshal(data)
	if err != nil {
		return ""
	}

	hash := sha256.Sum256(jsonData)
	return fmt.Sprintf("%s:%s", defaultCacheKeyPrefix, hex.EncodeToString(hash[:]))
}

func (p *CoreProcessor) GetFromCache(key string) (*CacheEntry, string) {
	if !p.cacheEnabled || key == "" {
		return nil, ""
	}

	if p.l1Cache != nil {
		p.l1Cache.mu.RLock()
		if entry, exists := p.l1Cache.items[key]; exists && time.Now().Before(entry.ExpireAt) {
			entry.HitCount++
			entry.LastAccess = time.Now()
			p.l1Cache.stats.L1Hits++
			p.l1Cache.stats.TotalRequests++
			p.l1Cache.mu.RUnlock()
			return entry, CacheHit
		}
		p.l1Cache.stats.L1Misses++
		p.l1Cache.mu.RUnlock()
	}

	if p.l2Cache != nil {
		if entry, exists := p.l2Cache.Get(key); exists && time.Now().Before(entry.ExpireAt) {
			if p.l1Cache != nil {
				p.l1Cache.mu.Lock()
				p.l1Cache.items[key] = entry
				p.evictL1IfNeeded()
				p.l1Cache.mu.Unlock()
			}
			p.l1Cache.stats.L2Hits++
			return entry, CacheHit
		}
		p.l1Cache.stats.L2Misses++
	}

	if p.l1Cache != nil {
		p.l1Cache.stats.TotalRequests++
	}

	return nil, CacheMiss
}

func (p *CoreProcessor) SetInCache(key string, value map[string]interface{}) error {
	if !p.cacheEnabled || key == "" {
		return nil
	}

	entry := &CacheEntry{
		Value:      value,
		ExpireAt:   time.Now().Add(p.cacheConfig.L1TTL),
		HitCount:   0,
		LastAccess: time.Now(),
	}

	if p.l1Cache != nil {
		p.l1Cache.mu.Lock()
		p.l1Cache.items[key] = entry
		p.evictL1IfNeeded()
		p.l1Cache.mu.Unlock()
	}

	if p.l2Cache != nil {
		l2Entry := &CacheEntry{
			Value:      value,
			ExpireAt:   time.Now().Add(p.cacheConfig.L2TTL),
			HitCount:   0,
			LastAccess: time.Now(),
		}
		if err := p.l2Cache.Set(key, l2Entry); err != nil {
			logger.Warn("failed to set L2 cache", zap.Error(err))
		}
	}

	return nil
}

func (p *CoreProcessor) evictL1IfNeeded() {
	if len(p.l1Cache.items) <= p.l1Cache.capacity {
		return
	}

	var oldestKey string
	var oldestTime time.Time

	for key, entry := range p.l1Cache.items {
		if oldestKey == "" || entry.LastAccess.Before(oldestTime) {
			oldestKey = key
			oldestTime = entry.LastAccess
		}
	}

	if oldestKey != "" {
		delete(p.l1Cache.items, oldestKey)
		p.l1Cache.stats.Evictions++
		logger.Debug("L1 cache evicted", zap.String("key", oldestKey))
	}
}

func (p *CoreProcessor) InvalidateCache(key string) error {
	if p.l1Cache != nil {
		p.l1Cache.mu.Lock()
		delete(p.l1Cache.items, key)
		p.l1Cache.mu.Unlock()
	}

	if p.l2Cache != nil {
		if err := p.l2Cache.Delete(key); err != nil {
			return wrapError(ErrCacheOperation, err.Error())
		}
	}

	return nil
}

func (p *CoreProcessor) InvalidateCacheByPattern(pattern string) error {
	if p.l1Cache != nil {
		p.l1Cache.mu.Lock()
		for key := range p.l1Cache.items {
			if strings.Contains(key, pattern) {
				delete(p.l1Cache.items, key)
			}
		}
		p.l1Cache.mu.Unlock()
	}

	if p.l2Cache != nil {
		if err := p.l2Cache.Invalidate(pattern); err != nil {
			return wrapError(ErrCacheOperation, err.Error())
		}
	}

	return nil
}

func (p *CoreProcessor) WarmupCache(requests []*HandlerRequest) int {
	successCount := 0

	for _, req := range requests {
		result := p.ExecuteHandler(req)
		if result.Success {
			successCount++
		}
	}

	logger.Info("cache warmup completed",
		zap.Int("total_requests", len(requests)),
		zap.Int("success_count", successCount),
	)

	return successCount
}

func (p *CoreProcessor) GetCacheStats() CacheStats {
	if p.l1Cache == nil {
		return CacheStats{}
	}

	p.l1Cache.mu.RLock()
	defer p.l1Cache.mu.RUnlock()

	return CacheStats{
		L1Hits:        p.l1Cache.stats.L1Hits,
		L1Misses:      p.l1Cache.stats.L1Misses,
		L2Hits:        p.l1Cache.stats.L2Hits,
		L2Misses:      p.l1Cache.stats.L2Misses,
		Evictions:     p.l1Cache.stats.Evictions,
		TotalRequests: p.l1Cache.stats.TotalRequests,
	}
}

func (p *CoreProcessor) Emit(eventType string, entity models.Entity) {
	select {
	case p.eventChan <- entity:
		logger.Info("event emitted",
			zap.String("event_type", eventType),
			zap.String("entity_id", entity.ID),
		)
	default:
		logger.Warn("event channel full, dropping event",
			zap.String("entity_id", entity.ID),
		)
	}
}

func (p *CoreProcessor) EventChannel() <-chan models.Entity {
	return p.eventChan
}

func (p *CoreProcessor) RecordMetrics(ctx *models.ExecutionContext) {
	duration := time.Since(ctx.StartAt).Milliseconds()
	logger.Info("execution metrics",
		zap.String("trace_id", ctx.TraceID),
		zap.Int64("duration_ms", duration),
	)
}

func (p *CoreProcessor) ExecuteHandler(request *HandlerRequest) *HandlerResult {
	if request == nil {
		return createErrorResult("invalid request: nil request")
	}

	execCtx := models.NewExecutionContext(request.TraceID)
	defer execCtx.Cleanup()

	logger.Info("handler execution started",
		zap.String("trace_id", request.TraceID),
		zap.String("namespace", request.Namespace),
	)

	if err := p.ValidateParams(request.Params); err != nil {
		logger.Warn("validation failed",
			zap.String("trace_id", request.TraceID),
			zap.Error(err),
		)
		return createErrorResult(err.Error())
	}

	if p.cacheEnabled && request.UseCache {
		cacheKey := p.GenerateCacheKey(request)
		if entry, source := p.GetFromCache(cacheKey); entry != nil {
			logger.Info("cache hit",
				zap.String("trace_id", request.TraceID),
				zap.String("source", source),
			)
			return &HandlerResult{
				Success: true,
				Data:    entry.Value,
				Source:  source,
			}
		}
	}

	config, err := p.LoadConfig(request.Namespace)
	if err != nil {
		logger.Error("config load failed",
			zap.String("trace_id", request.TraceID),
			zap.Error(err),
		)
		return createErrorResult("configuration error")
	}

	timeout := extractTimeout(config)
	if err := p.AcquireResource(timeout); err != nil {
		if err == ErrResourceAcquireTimeout {
			logger.Error("resource acquisition timeout",
				zap.String("trace_id", request.TraceID),
			)
			return createErrorResult("上游服务响应超时")
		}
		return createErrorResult("resource acquisition failed")
	}
	defer p.ReleaseResource()

	rules := extractRules(config)
	result, err := p.Process(request.Payload, rules)
	if err != nil {
		logger.Error("core processing failed",
			zap.String("trace_id", request.TraceID),
			zap.Error(err),
		)
		return createErrorResult("internal processing error")
	}

	if p.cacheEnabled && request.UseCache {
		cacheKey := p.GenerateCacheKey(request)
		p.SetInCache(cacheKey, result)
	}

	entity := createEntity(result)
	p.Emit("task.completed", entity)
	p.RecordMetrics(execCtx)

	logger.Info("handler execution completed",
		zap.String("trace_id", request.TraceID),
	)

	return &HandlerResult{
		Success: true,
		Data:    result,
		Source:  "compute",
	}
}

func (p *CoreProcessor) ProcessWithContext(ctx context.Context, request *HandlerRequest) (*HandlerResult, error) {
	resultChan := make(chan *HandlerResult, 1)

	go func() {
		resultChan <- p.ExecuteHandler(request)
	}()

	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case result := <-resultChan:
		return result, nil
	}
}

func (p *CoreProcessor) Stats() map[string]interface{} {
	p.mu.RLock()
	defer p.mu.RUnlock()

	stats := map[string]interface{}{
		"max_concurrency":  p.maxConcurrency,
		"active_resources": len(p.resourcePool),
		"config_count":     len(p.configStore),
		"event_queue_size": len(p.eventChan),
		"running":          p.IsRunning(),
		"cache_enabled":    p.cacheEnabled,
	}

	if p.cacheEnabled {
		cacheStats := p.GetCacheStats()
		stats["cache"] = map[string]interface{}{
			"l1_enabled":     p.cacheConfig.L1Enabled,
			"l2_enabled":     p.cacheConfig.L2Enabled,
			"l1_capacity":    p.cacheConfig.L1Capacity,
			"l1_size":        len(p.l1Cache.items),
			"l1_hits":        cacheStats.L1Hits,
			"l1_misses":      cacheStats.L1Misses,
			"l2_hits":        cacheStats.L2Hits,
			"l2_misses":      cacheStats.L2Misses,
			"evictions":      cacheStats.Evictions,
			"total_requests": cacheStats.TotalRequests,
		}

		if cacheStats.TotalRequests > 0 {
			hitRate := float64(cacheStats.L1Hits+cacheStats.L2Hits) / float64(cacheStats.TotalRequests) * 100
			stats["cache"].(map[string]interface{})["hit_rate_percent"] = hitRate
		}
	}

	return stats
}

func wrapError(base *service.ErrorDetail, detail string) *service.ErrorDetail {
	return service.NewErrorDetail(base.Code, base.Message+": "+detail, base.Cause)
}

func createErrorResult(message string) *HandlerResult {
	return &HandlerResult{
		Success: false,
		Error:   message,
	}
}

func extractTimeout(config *models.Config) time.Duration {
	if t, ok := config.Parameters["timeout"].(float64); ok {
		return time.Duration(t) * time.Second
	}
	return 30 * time.Second
}

func extractRules(config *models.Config) map[string]interface{} {
	if rules, ok := config.Parameters["rules"].(map[string]interface{}); ok {
		return rules
	}
	return make(map[string]interface{})
}

func createEntity(result map[string]interface{}) models.Entity {
	return models.Entity{
		ID:     utils.GenerateID("ent"),
		Type:   "event",
		Status: "active",
		Attributes: map[string]interface{}{
			"result": result,
		},
		CreatedAt: utils.CurrentTime(),
		UpdatedAt: utils.CurrentTime(),
	}
}

func toInt(value interface{}) (int, error) {
	switch v := value.(type) {
	case float64:
		return int(v), nil
	case int:
		return v, nil
	case int64:
		return int(v), nil
	default:
		return 0, fmt.Errorf("cannot convert %T to int", value)
	}
}

func toString(value interface{}) string {
	if value == nil {
		return ""
	}
	return fmt.Sprintf("%v", value)
}

func toUpper(s string) string {
	runes := []rune(s)
	for i, r := range runes {
		runes[i] = unicode.ToUpper(r)
	}
	return string(runes)
}

func toLower(s string) string {
	runes := []rune(s)
	for i, r := range runes {
		runes[i] = unicode.ToLower(r)
	}
	return string(runes)
}
