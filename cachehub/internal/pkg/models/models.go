package models

import (
	"encoding/json"
	"math/rand"
	"sync"
	"time"
)

type CacheInstance struct {
	CacheID         string          `json:"cache_id"`
	CacheName       string          `json:"cache_name"`
	CacheType       string          `json:"cache_type"`
	Connection      ConnectionInfo  `json:"connection"`
	MaxCapacity     int64           `json:"max_capacity"`
	EvictionPolicy  string          `json:"eviction_policy"`
	DefaultTTL      int             `json:"default_ttl"`
	Status          string          `json:"status"`
	RegisteredAt    time.Time       `json:"registered_at"`
}

type ConnectionInfo struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Database int    `json:"database"`
}

type QueryType int

const (
	QueryTypeRealtime QueryType = iota
	QueryTypeUserSession
	QueryTypeStaticData
	QueryTypeReport
	QueryTypeDefault
)

func (q QueryType) String() string {
	switch q {
	case QueryTypeRealtime:
		return "realtime"
	case QueryTypeUserSession:
		return "user_session"
	case QueryTypeStaticData:
		return "static_data"
	case QueryTypeReport:
		return "report"
	default:
		return "default"
	}
}

func ParseQueryType(s string) QueryType {
	switch s {
	case "realtime":
		return QueryTypeRealtime
	case "user_session":
		return QueryTypeUserSession
	case "static_data":
		return QueryTypeStaticData
	case "report":
		return QueryTypeReport
	default:
		return QueryTypeDefault
	}
}

type NullCacheConfig struct {
	Enabled          bool                   `json:"enabled"`
	DefaultTTL       int                    `json:"default_ttl"`
	QueryTypeTTLs    map[QueryType]int      `json:"query_type_ttls"`
	QueryTypePattern map[string]QueryType   `json:"query_type_pattern"`
}

func NewNullCacheConfig() *NullCacheConfig {
	return &NullCacheConfig{
		Enabled:    true,
		DefaultTTL: 60,
		QueryTypeTTLs: map[QueryType]int{
			QueryTypeRealtime:    5,
			QueryTypeUserSession: 180,
			QueryTypeStaticData:  3600,
			QueryTypeReport:      300,
			QueryTypeDefault:     60,
		},
		QueryTypePattern: map[string]QueryType{
			"realtime:*":    QueryTypeRealtime,
			"session:*":     QueryTypeUserSession,
			"static:*":      QueryTypeStaticData,
			"report:*":      QueryTypeReport,
			"user:*":        QueryTypeUserSession,
			"config:*":      QueryTypeStaticData,
		},
	}
}

type DynamicJitterConfig struct {
	Enabled          bool    `json:"enabled"`
	BaseJitterRange  float64 `json:"base_jitter_range"`
	MinJitterRange   float64 `json:"min_jitter_range"`
	MaxJitterRange   float64 `json:"max_jitter_range"`
	SmallCacheThreshold int64 `json:"small_cache_threshold"`
	LargeCacheThreshold int64 `json:"large_cache_threshold"`
	SmallScaleFactor  float64 `json:"small_scale_factor"`
	LargeScaleFactor  float64 `json:"large_scale_factor"`
}

func NewDynamicJitterConfig() *DynamicJitterConfig {
	return &DynamicJitterConfig{
		Enabled:              true,
		BaseJitterRange:      0.1,
		MinJitterRange:       0.02,
		MaxJitterRange:       0.4,
		SmallCacheThreshold:  1000,
		LargeCacheThreshold:  100000,
		SmallScaleFactor:     0.5,
		LargeScaleFactor:     2.0,
	}
}

type EvictionStrategyRegistry struct {
	Strategies map[string]EvictionStrategy `json:"strategies"`
	Default    string                      `json:"default"`
}

func NewEvictionStrategyRegistry() *EvictionStrategyRegistry {
	return &EvictionStrategyRegistry{
		Strategies: make(map[string]EvictionStrategy),
		Default:    "lru",
	}
}

type CachePolicy struct {
	PolicyID           string              `json:"policy_id"`
	CacheID            string              `json:"cache_id"`
	TTLPolicy          TTLPolicy           `json:"ttl_policy"`
	EvictionPolicy     EvictionPolicy      `json:"eviction_policy"`
	NullCacheConfig    NullCacheConfig     `json:"null_cache_config"`
	DynamicJitterConfig DynamicJitterConfig `json:"dynamic_jitter_config"`
	CreatedAt          time.Time           `json:"created_at"`
}

type TTLPolicy struct {
	DefaultTTL int             `json:"default_ttl"`
	MaxTTL     int             `json:"max_ttl"`
	TTLKeys    map[string]int  `json:"ttl_keys"`
}

type EvictionPolicy struct {
	Type               string  `json:"type"`
	EvictionThreshold  float64 `json:"eviction_threshold"`
}

type EvictionStrategy interface {
	Name() string
	Sort(items []*CacheData)
}

type LRUStrategy struct{}

func (s *LRUStrategy) Name() string { return "lru" }
func (s *LRUStrategy) Sort(items []*CacheData) {
	for i := 0; i < len(items)-1; i++ {
		for j := i + 1; j < len(items); j++ {
			if items[j].LastAccess.Before(items[i].LastAccess) {
				items[i], items[j] = items[j], items[i]
			}
		}
	}
}

type LFUStrategy struct{}

func (s *LFUStrategy) Name() string { return "lfu" }
func (s *LFUStrategy) Sort(items []*CacheData) {
	for i := 0; i < len(items)-1; i++ {
		for j := i + 1; j < len(items); j++ {
			if items[j].HitCount < items[i].HitCount {
				items[i], items[j] = items[j], items[i]
			}
		}
	}
}

type FIFOStrategy struct{}

func (s *FIFOStrategy) Name() string { return "fifo" }
func (s *FIFOStrategy) Sort(items []*CacheData) {
	for i := 0; i < len(items)-1; i++ {
		for j := i + 1; j < len(items); j++ {
			if items[j].CreatedAt.Before(items[i].CreatedAt) {
				items[i], items[j] = items[j], items[i]
			}
		}
	}
}

type TTLStrategy struct{}

func (s *TTLStrategy) Name() string { return "ttl" }
func (s *TTLStrategy) Sort(items []*CacheData) {
	for i := 0; i < len(items)-1; i++ {
		for j := i + 1; j < len(items); j++ {
			if items[j].ExpireAt.Before(items[i].ExpireAt) {
				items[i], items[j] = items[j], items[i]
			}
		}
	}
}

type RandomStrategy struct{}

func (s *RandomStrategy) Name() string { return "random" }
func (s *RandomStrategy) Sort(items []*CacheData) {
	rand.Shuffle(len(items), func(i, j int) {
		items[i], items[j] = items[j], items[i]
	})
}

type CacheData struct {
	Key         string          `json:"key"`
	Value       interface{}     `json:"value"`
	CacheID     string          `json:"cache_id"`
	TTL         int             `json:"ttl"`
	CreatedAt   time.Time       `json:"created_at"`
	LastAccess  time.Time       `json:"last_access"`
	HitCount    int             `json:"hit_count"`
	Size        int             `json:"size"`
	ExpireAt    time.Time       `json:"expire_at"`
}

type CacheStats struct {
	StatID         string    `json:"stat_id"`
	CacheID        string    `json:"cache_id"`
	StatTime       time.Time `json:"stat_time"`
	TotalKeys      int       `json:"total_keys"`
	HitRate        float64   `json:"hit_rate"`
	HitCount       int       `json:"hit_count"`
	MissCount      int       `json:"miss_count"`
	CapacityUsage  float64   `json:"capacity_usage"`
	CurrentUsage   int64     `json:"current_usage"`
	EvictionCount  int       `json:"eviction_count"`
}

type ExpireRecord struct {
	ExpireID    string    `json:"expire_id"`
	CacheID     string    `json:"cache_id"`
	Key         string    `json:"key"`
	ExpireTime  time.Time `json:"expire_time"`
	ExpireReason string   `json:"expire_reason"`
	Status      string    `json:"status"`
}

type AlertConfig struct {
	AlertID         string   `json:"alert_id"`
	CacheID         string   `json:"cache_id"`
	AlertType       string   `json:"alert_type"`
	Threshold       float64  `json:"threshold"`
	NotifyChannels  []string `json:"notify_channels"`
	Enabled         bool     `json:"enabled"`
	LastTriggered   *time.Time `json:"last_triggered,omitempty"`
}

type CacheOperationRequest struct {
	CacheID   string      `json:"cache_id"`
	Operation string      `json:"operation"`
	Key       string      `json:"key"`
	Value     interface{} `json:"value,omitempty"`
	TTL       int         `json:"ttl,omitempty"`
}

type CacheOperationResponse struct {
	Code  int         `json:"code"`
	Data  interface{} `json:"data"`
	Error string      `json:"error,omitempty"`
}

type InMemoryCache struct {
	data      map[string]*CacheData
	mu        sync.RWMutex
	capacity  int64
	usage     int64
	hitCount  int
	missCount int
	evictCount int
	cacheID   string
}

func NewInMemoryCache(cacheID string, capacity int64) *InMemoryCache {
	return &InMemoryCache{
		data:     make(map[string]*CacheData),
		capacity: capacity,
		cacheID:  cacheID,
	}
}

func (c *CacheData) MarshalBinary() ([]byte, error) {
	return json.Marshal(c)
}

func (c *CacheData) UnmarshalBinary(data []byte) error {
	return json.Unmarshal(data, c)
}

func (c *InMemoryCache) Get(key string) (*CacheData, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	item, exists := c.data[key]
	if !exists {
		c.missCount++
		return nil, false
	}

	if time.Now().After(item.ExpireAt) {
		c.missCount++
		return nil, false
	}

	item.HitCount++
	item.LastAccess = time.Now()
	c.hitCount++
	return item, true
}

func (c *InMemoryCache) Set(key string, value interface{}, ttl int) *CacheData {
	c.mu.Lock()
	defer c.mu.Unlock()

	valueBytes, _ := json.Marshal(value)
	size := len(valueBytes)

	item := &CacheData{
		Key:        key,
		Value:      value,
		CacheID:    c.cacheID,
		TTL:        ttl,
		CreatedAt:  time.Now(),
		LastAccess: time.Now(),
		HitCount:   0,
		Size:       size,
		ExpireAt:   time.Now().Add(time.Duration(ttl) * time.Second),
	}

	if existing, exists := c.data[key]; exists {
		c.usage -= int64(existing.Size)
	}

	c.data[key] = item
	c.usage += int64(size)
	return item
}

func (c *InMemoryCache) Delete(key string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	if item, exists := c.data[key]; exists {
		c.usage -= int64(item.Size)
		delete(c.data, key)
		return true
	}
	return false
}

func (c *InMemoryCache) GetAll() map[string]*CacheData {
	c.mu.RLock()
	defer c.mu.RUnlock()

	result := make(map[string]*CacheData)
	for k, v := range c.data {
		result[k] = v
	}
	return result
}

func (c *InMemoryCache) GetKeys() []string {
	c.mu.RLock()
	defer c.mu.RUnlock()

	keys := make([]string, 0, len(c.data))
	for k := range c.data {
		keys = append(keys, k)
	}
	return keys
}

func (c *InMemoryCache) GetUsage() int64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.usage
}

func (c *InMemoryCache) GetCapacity() int64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.capacity
}

func (c *InMemoryCache) UpdateCapacity(capacity int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.capacity = capacity
}

func (c *InMemoryCache) GetCount() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.data)
}

func (c *InMemoryCache) GetHitCount() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.hitCount
}

func (c *InMemoryCache) GetMissCount() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.missCount
}

func (c *InMemoryCache) GetEvictCount() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.evictCount
}

func (c *InMemoryCache) IncEvictCount() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.evictCount++
}

func (c *InMemoryCache) IsExpired(key string) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()

	item, exists := c.data[key]
	if !exists {
		return true
	}
	return time.Now().After(item.ExpireAt)
}

func (c *InMemoryCache) GetExpiredKeys() []string {
	c.mu.RLock()
	defer c.mu.RUnlock()

	now := time.Now()
	expired := make([]string, 0)
	for key, item := range c.data {
		if now.After(item.ExpireAt) {
			expired = append(expired, key)
		}
	}
	return expired
}
