package testfixtures

import (
	"time"

	"github.com/cachehub/internal/pkg/models"
)

type TestDataBuilder struct {
}

func NewTestDataBuilder() *TestDataBuilder {
	return &TestDataBuilder{}
}

func (b *TestDataBuilder) BuildDefaultCacheInstance() *models.CacheInstance {
	return &models.CacheInstance{
		CacheID:        "test_cache_01",
		CacheName:      "测试缓存",
		CacheType:      "memory",
		Connection:     models.ConnectionInfo{Host: "localhost", Port: 6379, Database: 0},
		MaxCapacity:    104857600,
		EvictionPolicy: "lru",
		DefaultTTL:     3600,
		Status:         "online",
		RegisteredAt:   time.Now(),
	}
}

func (b *TestDataBuilder) BuildCacheInstanceWithID(cacheID string) *models.CacheInstance {
	instance := b.BuildDefaultCacheInstance()
	instance.CacheID = cacheID
	instance.CacheName = "缓存实例_" + cacheID
	return instance
}

func (b *TestDataBuilder) BuildSmallCapacityInstance() *models.CacheInstance {
	instance := b.BuildDefaultCacheInstance()
	instance.CacheID = "small_cache_01"
	instance.CacheName = "小容量测试缓存"
	instance.MaxCapacity = 10240
	return instance
}

func (b *TestDataBuilder) BuildOfflineInstance() *models.CacheInstance {
	instance := b.BuildDefaultCacheInstance()
	instance.CacheID = "offline_cache_01"
	instance.Status = "offline"
	return instance
}

func (b *TestDataBuilder) BuildDefaultPolicy() *models.CachePolicy {
	return &models.CachePolicy{
		PolicyID: "policy_default",
		CacheID:  "test_cache_01",
		TTLPolicy: models.TTLPolicy{
			DefaultTTL: 3600,
			MaxTTL:     86400,
			TTLKeys: map[string]int{
				"session": 1800,
				"token":   300,
			},
		},
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lru",
			EvictionThreshold: 80,
		},
		CreatedAt: time.Now(),
	}
}

func (b *TestDataBuilder) BuildPolicyWithCacheID(cacheID, evictionType string) *models.CachePolicy {
	policy := b.BuildDefaultPolicy()
	policy.PolicyID = "policy_" + cacheID
	policy.CacheID = cacheID
	policy.EvictionPolicy.Type = evictionType
	return policy
}

func (b *TestDataBuilder) BuildLFUPolicy() *models.CachePolicy {
	policy := b.BuildDefaultPolicy()
	policy.PolicyID = "policy_lfu"
	policy.EvictionPolicy.Type = "lfu"
	return policy
}

func (b *TestDataBuilder) BuildFIFOPolicy() *models.CachePolicy {
	policy := b.BuildDefaultPolicy()
	policy.PolicyID = "policy_fifo"
	policy.EvictionPolicy.Type = "fifo"
	return policy
}

func (b *TestDataBuilder) BuildLowThresholdPolicy() *models.CachePolicy {
	policy := b.BuildDefaultPolicy()
	policy.PolicyID = "policy_low_threshold"
	policy.EvictionPolicy.EvictionThreshold = 50
	return policy
}

func (b *TestDataBuilder) BuildCacheData(key string, value interface{}, ttl int) *models.CacheData {
	now := time.Now()
	return &models.CacheData{
		Key:        key,
		Value:      value,
		CacheID:    "test_cache_01",
		TTL:        ttl,
		CreatedAt:  now,
		LastAccess: now,
		HitCount:   0,
		Size:       estimateSize(value),
		ExpireAt:   now.Add(time.Duration(ttl) * time.Second),
	}
}

func (b *TestDataBuilder) BuildExpiredCacheData(key string) *models.CacheData {
	data := b.BuildCacheData(key, "expired_value", 1)
	data.ExpireAt = time.Now().Add(-1 * time.Hour)
	return data
}

func (b *TestDataBuilder) BuildMultipleCacheData(count int) []*models.CacheData {
	items := make([]*models.CacheData, 0, count)
	for i := 0; i < count; i++ {
		key := "key_" + string(rune('a'+i%26)) + "_" + string(rune('a'+(i+1)%26))
		value := map[string]interface{}{
			"id":    i,
			"name":  "test_name_" + string(rune('a'+i%26)),
			"value": i * 100,
		}
		items = append(items, b.BuildCacheData(key, value, 3600))
	}
	return items
}

func (b *TestDataBuilder) BuildDefaultAlertConfig() *models.AlertConfig {
	return &models.AlertConfig{
		AlertID:        "alert_capacity",
		CacheID:        "test_cache_01",
		AlertType:      "capacity_warning",
		Threshold:      80,
		NotifyChannels: []string{"email", "slack"},
		Enabled:        true,
	}
}

func (b *TestDataBuilder) BuildHitRateAlertConfig() *models.AlertConfig {
	config := b.BuildDefaultAlertConfig()
	config.AlertID = "alert_hitrate"
	config.AlertType = "hit_rate_warning"
	config.Threshold = 50
	return config
}

func (b *TestDataBuilder) BuildCacheOperationRequest(operation, cacheID, key string) *models.CacheOperationRequest {
	return &models.CacheOperationRequest{
		CacheID:   cacheID,
		Operation: operation,
		Key:       key,
	}
}

func (b *TestDataBuilder) BuildSetOperationRequest(cacheID, key string, value interface{}, ttl int) *models.CacheOperationRequest {
	return &models.CacheOperationRequest{
		CacheID:   cacheID,
		Operation: "set",
		Key:       key,
		Value:     value,
		TTL:       ttl,
	}
}

func (b *TestDataBuilder) BuildUserValue(userID int) map[string]interface{} {
	return map[string]interface{}{
		"user_id": userID,
		"name":    "用户" + string(rune('A'+userID%26)),
		"role":    "admin",
		"active":  true,
		"created": time.Now().Format(time.RFC3339),
	}
}

func (b *TestDataBuilder) BuildSyncConfig(sourceID string, targetIDs []string) *struct {
	SourceCacheID  string
	TargetCacheIDs []string
	SyncMode       string
	Enabled        bool
} {
	return &struct {
		SourceCacheID  string
		TargetCacheIDs []string
		SyncMode       string
		Enabled        bool
	}{
		SourceCacheID:  sourceID,
		TargetCacheIDs: targetIDs,
		SyncMode:       "async",
		Enabled:        true,
	}
}

func (b *TestDataBuilder) BuildRealtimeSyncConfig(sourceID string, targetIDs []string) *struct {
	SourceCacheID  string
	TargetCacheIDs []string
	SyncMode       string
	Enabled        bool
} {
	config := b.BuildSyncConfig(sourceID, targetIDs)
	config.SyncMode = "realtime"
	return config
}

func estimateSize(value interface{}) int {
	switch v := value.(type) {
	case string:
		return len(v)
	case []byte:
		return len(v)
	case map[string]interface{}:
		size := 0
		for k, val := range v {
			size += len(k) + estimateSize(val)
		}
		return size
	default:
		return 100
	}
}
