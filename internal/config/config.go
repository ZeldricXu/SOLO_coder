package config

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/enterprise/config-platform/pkg/types"
	"github.com/enterprise/config-platform/pkg/utils"
	"github.com/go-redis/redis/v8"
	"go.uber.org/zap"
)

type ValidationRule struct {
	Required    bool
	Type        string
	Validator   func(interface{}) error
	Default     interface{}
}

type Schema struct {
	rules map[string]ValidationRule
}

func NewSchema() *Schema {
	return &Schema{rules: make(map[string]ValidationRule)}
}

func (s *Schema) AddField(name string, rule ValidationRule) *Schema {
	s.rules[name] = rule
	return s
}

type CacheEntry struct {
	Config    *types.ConfigDefinition
	ExpiresAt time.Time
	CachedAt  time.Time
	Hits      int64
}

type CacheStats struct {
	L1Hits      int64 `json:"l1_hits"`
	L1Misses    int64 `json:"l1_misses"`
	L2Hits      int64 `json:"l2_hits"`
	L2Misses    int64 `json:"l2_misses"`
	Evictions   int64 `json:"evictions"`
	TotalItems  int   `json:"total_items"`
	CacheSize   int   `json:"cache_size"`
}

type CacheStrategy string

const (
	CacheStrategyLRU        CacheStrategy = "lru"
	CacheStrategyLFU        CacheStrategy = "lfu"
	CacheStrategyTimeBased  CacheStrategy = "time_based"
)

type CacheConfig struct {
	L1TTL         time.Duration
	L2TTL         time.Duration
	MaxItems      int
	Strategy      CacheStrategy
	EnableL2      bool
	WarmupOnStart bool
}

type L1Cache struct {
	items     map[string]*CacheEntry
	mu        sync.RWMutex
	maxItems  int
	strategy  CacheStrategy
	accessLog map[string][]time.Time
}

func NewL1Cache(maxItems int, strategy CacheStrategy) *L1Cache {
	return &L1Cache{
		items:     make(map[string]*CacheEntry),
		maxItems:  maxItems,
		strategy:  strategy,
		accessLog: make(map[string][]time.Time),
	}
}

func (c *L1Cache) Get(key string) (*CacheEntry, bool) {
	c.mu.RLock()
	entry, exists := c.items[key]
	if !exists {
		c.mu.RUnlock()
		return nil, false
	}

	if time.Now().After(entry.ExpiresAt) {
		c.mu.RUnlock()
		c.mu.Lock()
		delete(c.items, key)
		delete(c.accessLog, key)
		c.mu.Unlock()
		return nil, false
	}

	c.mu.RUnlock()

	c.mu.Lock()
	entry.Hits++
	c.accessLog[key] = append(c.accessLog[key], time.Now())
	if len(c.accessLog[key]) > 100 {
		c.accessLog[key] = c.accessLog[key][1:]
	}
	c.mu.Unlock()

	return entry, true
}

func (c *L1Cache) Set(key string, config *types.ConfigDefinition, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.items) >= c.maxItems {
		c.evict()
	}

	c.items[key] = &CacheEntry{
		Config:    config,
		ExpiresAt: time.Now().Add(ttl),
		CachedAt:  time.Now(),
		Hits:      0,
	}
	c.accessLog[key] = []time.Time{time.Now()}
}

func (c *L1Cache) Delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.items, key)
	delete(c.accessLog, key)
}

func (c *L1Cache) evict() {
	if len(c.items) == 0 {
		return
	}

	var evictKey string
	var oldestTime time.Time

	switch c.strategy {
	case CacheStrategyLRU:
		for k, v := range c.accessLog {
			if len(v) > 0 {
				lastAccess := v[len(v)-1]
				if oldestTime.IsZero() || lastAccess.Before(oldestTime) {
					oldestTime = lastAccess
					evictKey = k
				}
			}
		}
	case CacheStrategyLFU:
		minHits := int64(1<<63 - 1)
		for k, v := range c.items {
			if v.Hits < minHits {
				minHits = v.Hits
				evictKey = k
			}
		}
	default:
		for k, v := range c.items {
			if oldestTime.IsZero() || v.CachedAt.Before(oldestTime) {
				oldestTime = v.CachedAt
				evictKey = k
			}
		}
	}

	if evictKey != "" {
		delete(c.items, evictKey)
		delete(c.accessLog, evictKey)
	}
}

func (c *L1Cache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items = make(map[string]*CacheEntry)
	c.accessLog = make(map[string][]time.Time)
}

func (c *L1Cache) Len() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.items)
}

func (c *L1Cache) GetAll() []*CacheEntry {
	c.mu.RLock()
	defer c.mu.RUnlock()
	result := make([]*CacheEntry, 0, len(c.items))
	for _, v := range c.items {
		result = append(result, v)
	}
	return result
}

type L2Cache struct {
	client    *redis.Client
	keyPrefix string
	ctx       context.Context
}

func NewL2Cache(redisURL string) (*L2Cache, error) {
	opt, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, err
	}

	client := redis.NewClient(opt)
	ctx := context.Background()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, err
	}

	return &L2Cache{
		client:    client,
		keyPrefix: "config:",
		ctx:       ctx,
	}, nil
}

func (c *L2Cache) Get(key string) (*types.ConfigDefinition, error) {
	data, err := c.client.Get(c.ctx, c.keyPrefix+key).Bytes()
	if err != nil {
		return nil, err
	}

	var config types.ConfigDefinition
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, err
	}
	return &config, nil
}

func (c *L2Cache) Set(key string, config *types.ConfigDefinition, ttl time.Duration) error {
	data, err := json.Marshal(config)
	if err != nil {
		return err
	}
	return c.client.Set(c.ctx, c.keyPrefix+key, data, ttl).Err()
}

func (c *L2Cache) Delete(key string) error {
	return c.client.Del(c.ctx, c.keyPrefix+key).Err()
}

func (c *L2Cache) Clear() error {
	iter := c.client.Scan(c.ctx, 0, c.keyPrefix+"*", 0).Iterator()
	for iter.Next(c.ctx) {
		c.client.Del(c.ctx, iter.Val())
	}
	return iter.Err()
}

func (c *L2Cache) Close() error {
	return c.client.Close()
}

type Manager struct {
	configs     map[string]*types.ConfigDefinition
	mu          sync.RWMutex
	schemas     map[string]*Schema
	l1Cache     *L1Cache
	l2Cache     *L2Cache
	cacheConfig CacheConfig
	cacheStats  CacheStats
	logger      *zap.Logger
	eventBus    chan CacheEvent
}

type CacheEvent struct {
	Type      string
	Key       string
	Timestamp time.Time
	Level     string
}

type CacheInvalidationRequest struct {
	Keys      []string `json:"keys"`
	InvalidateAll bool   `json:"invalidate_all"`
}

var (
	instance *Manager
	once     sync.Once
)

func DefaultCacheConfig() CacheConfig {
	return CacheConfig{
		L1TTL:         5 * time.Minute,
		L2TTL:         30 * time.Minute,
		MaxItems:      1000,
		Strategy:      CacheStrategyLRU,
		EnableL2:      false,
		WarmupOnStart: false,
	}
}

func GetManager() *Manager {
	once.Do(func() {
		cfg := DefaultCacheConfig()
		instance = &Manager{
			configs:     make(map[string]*types.ConfigDefinition),
			schemas:     make(map[string]*Schema),
			l1Cache:     NewL1Cache(cfg.MaxItems, cfg.Strategy),
			cacheConfig: cfg,
			cacheStats:  CacheStats{},
			logger:      zap.NewNop(),
			eventBus:    make(chan CacheEvent, 1000),
		}
		instance.registerDefaultSchemas()

		if cfg.WarmupOnStart {
			go instance.warmupCache()
		}

		go instance.startCacheCleaner()
	})
	return instance
}

func (m *Manager) InitL2Cache(redisURL string) error {
	l2, err := NewL2Cache(redisURL)
	if err != nil {
		return err
	}
	m.l2Cache = l2
	m.cacheConfig.EnableL2 = true
	return nil
}

func (m *Manager) SetCacheConfig(cfg CacheConfig) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.cacheConfig = cfg
	m.l1Cache = NewL1Cache(cfg.MaxItems, cfg.Strategy)
}

func (m *Manager) GetCacheConfig() CacheConfig {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.cacheConfig
}

func (m *Manager) warmupCache() {
	m.mu.RLock()
	configs := make([]*types.ConfigDefinition, 0, len(m.configs))
	for _, cfg := range m.configs {
		configs = append(configs, cfg)
	}
	m.mu.RUnlock()

	for _, cfg := range configs {
		m.l1Cache.Set(cfg.Namespace, cfg, m.cacheConfig.L1TTL)
		if m.l2Cache != nil {
			m.l2Cache.Set(cfg.Namespace, cfg, m.cacheConfig.L2TTL)
		}
	}
	m.logger.Info("Cache warmup completed", zap.Int("count", len(configs)))
}

func (m *Manager) startCacheCleaner() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		m.cleanExpiredCache()
	}
}

func (m *Manager) cleanExpiredCache() {
	now := time.Now()
	m.l1Cache.mu.Lock()
	for k, v := range m.l1Cache.items {
		if now.After(v.ExpiresAt) {
			delete(m.l1Cache.items, k)
			delete(m.l1Cache.accessLog, k)
			m.cacheStats.Evictions++
		}
	}
	m.l1Cache.mu.Unlock()
}

func (m *Manager) registerDefaultSchemas() {
	gatewaySchema := NewSchema().
		AddField("timeout", ValidationRule{Required: false, Type: "int", Default: 30}).
		AddField("retries", ValidationRule{Required: false, Type: "int", Default: 3}).
		AddField("rate_limit", ValidationRule{Required: false, Type: "int", Default: 100})
	m.schemas["gateway"] = gatewaySchema

	sidecarSchema := NewSchema().
		AddField("cpu_limit", ValidationRule{Required: false, Type: "string", Default: "500m"}).
		AddField("memory_limit", ValidationRule{Required: false, Type: "string", Default: "256Mi"}).
		AddField("auto_inject", ValidationRule{Required: false, Type: "bool", Default: true})
	m.schemas["sidecar"] = sidecarSchema
}

func (m *Manager) Validate(namespace string, params map[string]interface{}) (map[string]interface{}, error) {
	schema, exists := m.schemas[namespace]
	if !exists {
		return params, nil
	}

	result := make(map[string]interface{})
	for field, rule := range schema.rules {
		val, ok := params[field]
		if !ok {
			if rule.Required {
				return nil, fmt.Errorf("required field missing: %s", field)
			}
			result[field] = rule.Default
			continue
		}
		if rule.Validator != nil {
			if err := rule.Validator(val); err != nil {
				return nil, fmt.Errorf("validation failed for %s: %w", field, err)
			}
		}
		result[field] = val
	}
	for k, v := range params {
		if _, ok := schema.rules[k]; !ok {
			result[k] = v
		}
	}
	return result, nil
}

func (m *Manager) LoadConfig(namespace string) (*types.ConfigDefinition, error) {
	if entry, ok := m.l1Cache.Get(namespace); ok {
		m.mu.Lock()
		m.cacheStats.L1Hits++
		m.mu.Unlock()
		m.emitEvent("hit", namespace, "L1")
		return entry.Config, nil
	}

	m.mu.Lock()
	m.cacheStats.L1Misses++
	m.mu.Unlock()

	if m.l2Cache != nil {
		if cfg, err := m.l2Cache.Get(namespace); err == nil {
			m.l1Cache.Set(namespace, cfg, m.cacheConfig.L1TTL)
			m.mu.Lock()
			m.cacheStats.L2Hits++
			m.mu.Unlock()
			m.emitEvent("hit", namespace, "L2")
			return cfg, nil
		}
		m.mu.Lock()
		m.cacheStats.L2Misses++
		m.mu.Unlock()
	}

	m.mu.RLock()
	cfg, exists := m.configs[namespace]
	m.mu.RUnlock()

	if !exists {
		return nil, errors.New("config not found")
	}

	m.l1Cache.Set(namespace, cfg, m.cacheConfig.L1TTL)
	if m.l2Cache != nil {
		m.l2Cache.Set(namespace, cfg, m.cacheConfig.L2TTL)
	}

	m.emitEvent("load", namespace, "origin")
	return cfg, nil
}

func (m *Manager) SaveConfig(namespace string, params map[string]interface{}) (*types.ConfigDefinition, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	validated, err := m.Validate(namespace, params)
	if err != nil {
		return nil, err
	}

	existing, exists := m.configs[namespace]
	version := 1
	if exists {
		version = existing.Version + 1
	}

	cfg := &types.ConfigDefinition{
		ConfigID:   utils.GenerateID("cfg"),
		Namespace:  namespace,
		Version:    version,
		Parameters: validated,
		Enabled:    true,
		AppliedAt:  time.Now().UTC(),
	}

	m.configs[namespace] = cfg

	m.l1Cache.Set(namespace, cfg, m.cacheConfig.L1TTL)
	if m.l2Cache != nil {
		m.l2Cache.Set(namespace, cfg, m.cacheConfig.L2TTL)
	}

	m.emitEvent("save", namespace, "origin")
	return cfg, nil
}

func (m *Manager) ListConfigs() []*types.ConfigDefinition {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*types.ConfigDefinition, 0, len(m.configs))
	for _, cfg := range m.configs {
		result = append(result, cfg)
	}
	return result
}

func (m *Manager) DeleteConfig(namespace string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.configs[namespace]; !exists {
		return errors.New("config not found")
	}
	delete(m.configs, namespace)

	m.l1Cache.Delete(namespace)
	if m.l2Cache != nil {
		m.l2Cache.Delete(namespace)
	}

	m.emitEvent("delete", namespace, "origin")
	return nil
}

func (m *Manager) InvalidateCache(req CacheInvalidationRequest) {
	if req.InvalidateAll {
		m.l1Cache.Clear()
		if m.l2Cache != nil {
			m.l2Cache.Clear()
		}
		m.emitEvent("invalidate_all", "", "both")
		return
	}

	for _, key := range req.Keys {
		m.l1Cache.Delete(key)
		if m.l2Cache != nil {
			m.l2Cache.Delete(key)
		}
		m.emitEvent("invalidate", key, "both")
	}
}

func (m *Manager) GetCacheStats() CacheStats {
	m.mu.RLock()
	defer m.mu.RUnlock()

	stats := m.cacheStats
	stats.TotalItems = m.l1Cache.Len()
	stats.CacheSize = m.cacheConfig.MaxItems
	return stats
}

func (m *Manager) GetCachedItems() []*CacheEntry {
	return m.l1Cache.GetAll()
}

func (m *Manager) Warmup(configs []*types.ConfigDefinition) {
	for _, cfg := range configs {
		m.l1Cache.Set(cfg.Namespace, cfg, m.cacheConfig.L1TTL)
		if m.l2Cache != nil {
			m.l2Cache.Set(cfg.Namespace, cfg, m.cacheConfig.L2TTL)
		}
	}
	m.logger.Info("Cache warmup completed", zap.Int("count", len(configs)))
}

func (m *Manager) RegisterSchema(namespace string, schema *Schema) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.schemas[namespace] = schema
}

func (m *Manager) emitEvent(typ, key, level string) {
	select {
	case m.eventBus <- CacheEvent{
		Type:      typ,
		Key:       key,
		Timestamp: time.Now(),
		Level:     level,
	}:
	default:
	}
}

func (m *Manager) Events() <-chan CacheEvent {
	return m.eventBus
}

func (m *Manager) SetLogger(logger *zap.Logger) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logger = logger
}

func (m *Manager) Close() {
	if m.l2Cache != nil {
		m.l2Cache.Close()
	}
	close(m.eventBus)
}
