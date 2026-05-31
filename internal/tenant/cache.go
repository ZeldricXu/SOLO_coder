package tenant

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"go.uber.org/zap"
)

type CacheLevel string

const (
	CacheLevelL1 CacheLevel = "l1"
	CacheLevelL2 CacheLevel = "l2"
	CacheLevelDB CacheLevel = "db"
)

type CacheConfig struct {
	L1Enabled     bool
	L1TTL       time.Duration
	L1MaxSize   int
	L2Enabled   bool
	L2Prefix    string
	L2TTL       time.Duration
}

func DefaultCacheConfig() *CacheConfig {
	return &CacheConfig{
		L1Enabled:   true,
		L1TTL:       5 * time.Minute,
		L1MaxSize:   1000,
		L2Enabled:   false,
		L2Prefix:    "tenant:",
		L2TTL:       15 * time.Minute,
	}
}

type L1CacheEntry struct {
	Tenant    *models.Tenant
	ExpiresAt time.Time
}

type L2Client interface {
	Get(ctx context.Context, key string) (string, error)
	Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error
	Del(ctx context.Context, key string) error
	Publish(ctx context.Context, channel string, message interface{}) error
	Subscribe(ctx context.Context, channel string) (<-chan string, error)
	DelPattern(ctx context.Context, pattern string) error
}

type MultiLevelCache struct {
	l1Cache   map[string]*L1CacheEntry
	l1Mu      sync.RWMutex
	l1Stats   CacheStats

	l2Client  L2Client
	l2Stats   CacheStats

	config     *CacheConfig

	evictionMu sync.Mutex
}

type CacheStats struct {
	Hits      int64
	Misses    int64
	Evictions int64
	Errors    int64
}

type CacheStatsSnapshot struct {
	L1Hits      int64
	L1Misses    int64
	L1Evictions int64
	L2Hits      int64
	L2Misses    int64
	L2Errors    int64
	L1HitRate    float64
	L2HitRate    float64
}

func NewMultiLevelCache(config *CacheConfig, l2Client L2Client) *MultiLevelCache {
	if config == nil {
		config = DefaultCacheConfig()
	}
	return &MultiLevelCache{
		l1Cache:  make(map[string]*L1CacheEntry),
		l2Client:  l2Client,
		config:     config,
	}
}

func (c *MultiLevelCache) Get(ctx context.Context, tenantID string) (*models.Tenant, CacheLevel, error) {
	if c.config.L1Enabled {
		if tenant, hit := c.getFromL1(tenantID); hit {
			c.incrementL1Hit()
			return tenant, CacheLevelL1, nil
		}
		c.incrementL1Miss()
	}

	if c.config.L2Enabled && c.l2Client != nil {
		if tenant, err := c.getFromL2(ctx, tenantID); err == nil && tenant != nil {
			c.incrementL2Hit()
			if c.config.L1Enabled {
				c.putToL1(tenantID, tenant)
			}
			return tenant, CacheLevelL2, nil
		}
		c.incrementL2Miss()
	}

	return nil, CacheLevelDB, nil
}

func (c *MultiLevelCache) Put(ctx context.Context, tenant *models.Tenant) {
	if c.config.L1Enabled {
		c.putToL1(tenant.ID, tenant)
	}

	if c.config.L2Enabled && c.l2Client != nil {
		if err := c.putToL2(ctx, tenant); err != nil {
			logger.Warn("failed to write to l2 cache", zap.Error(err), zap.String("tenant_id", tenant.ID))
			c.incrementL2Error()
		}
	}
}

func (c *MultiLevelCache) Invalidate(ctx context.Context, tenantID string) {
	if c.config.L1Enabled {
		c.deleteFromL1(tenantID)
	}

	if c.config.L2Enabled && c.l2Client != nil {
		key := c.l2Key(tenantID)
		if err := c.l2Client.Del(ctx, key); err != nil {
			logger.Warn("failed to invalidate l2 cache", zap.Error(err), zap.String("tenant_id", tenantID))
		}
	}

	if c.config.L2Enabled && c.l2Client != nil {
		if err := c.l2Client.Publish(ctx, "tenant:invalidate", tenantID); err != nil {
			logger.Warn("failed to publish invalidation", zap.Error(err))
		}
	}
}

func (c *MultiLevelCache) InvalidateAll(ctx context.Context) {
	if c.config.L1Enabled {
		c.l1Mu.Lock()
		c.l1Cache = make(map[string]*L1CacheEntry)
		c.l1Mu.Unlock()
	}

	if c.config.L2Enabled && c.l2Client != nil {
		if err := c.l2Client.DelPattern(ctx, c.l2Key("*")); err != nil {
			logger.Warn("failed to invalidate all l2 cache", zap.Error(err))
		}
	}
}

func (c *MultiLevelCache) Warmup(ctx context.Context, tenants []*models.Tenant) {
	for _, tenant := range tenants {
		c.Put(ctx, tenant)
	}
	logger.Info("cache warmed up", zap.Int("count", len(tenants)))
}

func (c *MultiLevelCache) GetStats() *CacheStatsSnapshot {
	c.l1Mu.RLock()
	defer c.l1Mu.RUnlock()

	l1Total := c.l1Stats.Hits + c.l1Stats.Misses
	l2Total := c.l2Stats.Hits + c.l2Stats.Misses

	l1HitRate := 0.0
	if l1Total > 0 {
		l1HitRate = float64(c.l1Stats.Hits) / float64(l1Total)
	}

	l2HitRate := 0.0
	if l2Total > 0 {
		l2HitRate = float64(c.l2Stats.Hits) / float64(l2Total)
	}

	return &CacheStatsSnapshot{
		L1Hits:      c.l1Stats.Hits,
		L1Misses:    c.l1Stats.Misses,
		L1Evictions: c.l1Stats.Evictions,
		L2Hits:      c.l2Stats.Hits,
		L2Misses:    c.l2Stats.Misses,
		L2Errors:    c.l2Stats.Errors,
		L1HitRate:    l1HitRate,
		L2HitRate:    l2HitRate,
	}
}

func (c *MultiLevelCache) ResetStats() {
	c.l1Mu.Lock()
	defer c.l1Mu.Unlock()

	c.l1Stats = CacheStats{}
	c.l2Stats = CacheStats{}
}

func (c *MultiLevelCache) StartEviction(interval time.Duration, stopChan <-chan struct{}) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			c.evictExpired()
		case <-stopChan:
			return
		}
	}
}

func (c *MultiLevelCache) getFromL1(tenantID string) (*models.Tenant, bool) {
	c.l1Mu.RLock()
	defer c.l1Mu.RUnlock()

	entry, exists := c.l1Cache[tenantID]
	if !exists {
		return nil, false
	}

	if time.Now().After(entry.ExpiresAt) {
		return nil, false
	}

	return entry.Tenant, true
}

func (c *MultiLevelCache) putToL1(tenantID string, tenant *models.Tenant) {
	c.l1Mu.Lock()
	defer c.l1Mu.Unlock()

	if len(c.l1Cache) >= c.config.L1MaxSize {
		c.evictOldest()
	}

	c.l1Cache[tenantID] = &L1CacheEntry{
		Tenant:    tenant,
		ExpiresAt: time.Now().Add(c.config.L1TTL),
	}
}

func (c *MultiLevelCache) deleteFromL1(tenantID string) {
	c.l1Mu.Lock()
	defer c.l1Mu.Unlock()
	delete(c.l1Cache, tenantID)
}

func (c *MultiLevelCache) evictExpired() {
	c.l1Mu.Lock()
	defer c.l1Mu.Unlock()

	now := time.Now()
	for id, entry := range c.l1Cache {
		if now.After(entry.ExpiresAt) {
			delete(c.l1Cache, id)
		}
	}
}

func (c *MultiLevelCache) evictOldest() {
	var oldestID string
	var oldestTime time.Time

	for id, entry := range c.l1Cache {
		if oldestID == "" || entry.ExpiresAt.Before(oldestTime) {
			oldestID = id
			oldestTime = entry.ExpiresAt
		}
	}

	if oldestID != "" {
		delete(c.l1Cache, oldestID)
		c.l1Stats.Evictions++
	}
}

func (c *MultiLevelCache) getFromL2(ctx context.Context, tenantID string) (*models.Tenant, error) {
	key := c.l2Key(tenantID)
	data, err := c.l2Client.Get(ctx, key)
	if err != nil {
		return nil, err
	}

	var tenant models.Tenant
	if err := json.Unmarshal([]byte(data), &tenant); err != nil {
		return nil, err
	}

	return &tenant, nil
}

func (c *MultiLevelCache) putToL2(ctx context.Context, tenant *models.Tenant) error {
	key := c.l2Key(tenant.ID)
	data, err := json.Marshal(tenant)
	if err != nil {
		return err
	}
	return c.l2Client.Set(ctx, key, string(data), c.config.L2TTL)
}

func (c *MultiLevelCache) l2Key(tenantID string) string {
	return fmt.Sprintf("%s%s", c.config.L2Prefix, tenantID)
}

func (c *MultiLevelCache) incrementL1Hit() {
	c.l1Mu.Lock()
	defer c.l1Mu.Unlock()
	c.l1Stats.Hits++
}

func (c *MultiLevelCache) incrementL1Miss() {
	c.l1Mu.Lock()
	defer c.l1Mu.Unlock()
	c.l1Stats.Misses++
}

func (c *MultiLevelCache) incrementL2Hit() {
	c.l1Mu.Lock()
	defer c.l1Mu.Unlock()
	c.l2Stats.Hits++
}

func (c *MultiLevelCache) incrementL2Miss() {
	c.l1Mu.Lock()
	defer c.l1Mu.Unlock()
	c.l2Stats.Misses++
}

func (c *MultiLevelCache) incrementL2Error() {
	c.l1Mu.Lock()
	defer c.l1Mu.Unlock()
	c.l2Stats.Errors++
}

type InvalidationMessage struct {
	Action   string `json:"action"`
	TenantID string `json:"tenant_id"`
	Source   string `json:"source"`
}

func (c *MultiLevelCache) SubscribeInvalidations(ctx context.Context, source string) error {
	if !c.config.L2Enabled || c.l2Client == nil {
		return nil
	}

	ch, err := c.l2Client.Subscribe(ctx, "tenant:invalidate")
	if err != nil {
		return err
	}

	go func() {
		for msg := range ch {
			var invMsg InvalidationMessage
			if err := json.Unmarshal([]byte(msg), &invMsg); err != nil {
				c.deleteFromL1(msg)
			} else if invMsg.Source != source {
				c.deleteFromL1(invMsg.TenantID)
			}
		}
	}()

	return nil
}
