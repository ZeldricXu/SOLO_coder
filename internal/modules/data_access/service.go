package data_access

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"loglevelplatform/internal/common/database"
	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"

	"github.com/go-redis/redis/v8"
	gocache "github.com/patrickmn/go-cache"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type CacheStrategy string

const (
	CacheStrategyWriteThrough CacheStrategy = "write_through"
	CacheStrategyWriteBehind  CacheStrategy = "write_behind"
	CacheStrategyCacheAside   CacheStrategy = "cache_aside"
)

type CacheConfig struct {
	DefaultTTL      time.Duration
	MaxEntries      int
	Strategy        CacheStrategy
	EnableRedis     bool
	RedisPrefix     string
	StatsEnabled    bool
}

type CacheStats struct {
	Hits        int64     `json:"hits"`
	Misses      int64     `json:"misses"`
	Evictions   int64     `json:"evictions"`
	Sets        int64     `json:"sets"`
	Deletes     int64     `json:"deletes"`
	HitRate     float64   `json:"hit_rate"`
	LastReset   time.Time `json:"last_reset"`
}

type CacheEntry struct {
	Key        string      `json:"key"`
	Value      interface{} `json:"value"`
	ExpiresAt  time.Time   `json:"expires_at"`
	CreatedAt  time.Time   `json:"created_at"`
	AccessedAt time.Time   `json:"accessed_at"`
	HitCount   int64       `json:"hit_count"`
}

type Service struct {
	db          *gorm.DB
	redisClient *redis.Client
	memoryCache *gocache.Cache
	config      CacheConfig
	stats       CacheStats
	statsMu     sync.RWMutex
	mu          sync.RWMutex
	pendingWrites map[string]*CacheEntry
	writeBehindTicker *time.Ticker
}

var (
	instance *Service
	once     sync.Once
)

func NewService(config ...CacheConfig) *Service {
	once.Do(func() {
		cfg := CacheConfig{
			DefaultTTL:   5 * time.Minute,
			MaxEntries:   10000,
			Strategy:     CacheStrategyCacheAside,
			EnableRedis:  false,
			RedisPrefix:  "loglevelplatform:",
			StatsEnabled: true,
		}
		if len(config) > 0 {
			cfg = config[0]
		}

		instance = &Service{
			db:              database.GetDB(),
			memoryCache:     gocache.New(cfg.DefaultTTL, 10*time.Minute),
			config:          cfg,
			stats:           CacheStats{LastReset: time.Now()},
			pendingWrites:   make(map[string]*CacheEntry),
			writeBehindTicker: time.NewTicker(5 * time.Second),
		}

		if cfg.EnableRedis {
			instance.redisClient = database.GetRedis()
		}

		go instance.processWriteBehind()
	})
	return instance
}

func (s *Service) processWriteBehind() {
	for range s.writeBehindTicker.C {
		s.flushWriteBehind()
	}
}

func (s *Service) flushWriteBehind() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if len(s.pendingWrites) == 0 {
		return
	}

	for key, entry := range s.pendingWrites {
		s.persistToDB(entry)
		delete(s.pendingWrites, key)
	}
}

func (s *Service) persistToDB(entry *CacheEntry) {
	ctx := context.Background()
	log := logger.FromContext(ctx)

	valueBytes, err := json.Marshal(entry.Value)
	if err != nil {
		log.Error("failed to marshal cache value", zap.String("key", entry.Key), zap.Error(err))
		return
	}

	dbEntry := &models.CacheEntry{
		Key:        entry.Key,
		Value:      string(valueBytes),
		ExpiresAt:  entry.ExpiresAt,
		HitCount:   entry.HitCount,
		CreatedAt:  entry.CreatedAt,
		AccessedAt: entry.AccessedAt,
	}

	var existing models.CacheEntry
	err = s.db.Where("key = ?", entry.Key).First(&existing).Error
	if err == nil {
		if err := s.db.Save(dbEntry).Error; err != nil {
			log.Error("failed to update cache entry in db", zap.String("key", entry.Key), zap.Error(err))
		}
	} else {
		if err := s.db.Create(dbEntry).Error; err != nil {
			log.Error("failed to create cache entry in db", zap.String("key", entry.Key), zap.Error(err))
		}
	}
}

func (s *Service) Get(ctx context.Context, key string) (interface{}, bool, error) {
	log := logger.FromContext(ctx)

	if entry, found := s.memoryCache.Get(key); found {
		s.recordHit()
		s.updateAccessTime(key)
		log.Debug("cache hit (memory)", zap.String("key", key))
		return entry, true, nil
	}

	if s.config.EnableRedis && s.redisClient != nil {
		redisKey := s.config.RedisPrefix + key
		val, err := s.redisClient.Get(ctx, redisKey).Result()
		if err == nil {
			var entry CacheEntry
			if err := json.Unmarshal([]byte(val), &entry); err == nil {
				s.memoryCache.Set(key, entry.Value, time.Until(entry.ExpiresAt))
				s.recordHit()
				s.updateAccessTime(key)
				log.Debug("cache hit (redis)", zap.String("key", key))
				return entry.Value, true, nil
			}
		} else if err != redis.Nil {
			log.Warn("redis get error", zap.String("key", key), zap.Error(err))
		}
	}

	var dbEntry models.CacheEntry
	err := s.db.Where("key = ? AND expires_at > ?", key, time.Now()).First(&dbEntry).Error
	if err == nil {
		var value interface{}
		if err := json.Unmarshal([]byte(dbEntry.Value), &value); err == nil {
			ttl := time.Until(dbEntry.ExpiresAt)
			s.memoryCache.Set(key, value, ttl)
			if s.config.EnableRedis && s.redisClient != nil {
				redisKey := s.config.RedisPrefix + key
				entry := CacheEntry{
					Key:        key,
					Value:      value,
					ExpiresAt:  dbEntry.ExpiresAt,
					CreatedAt:  dbEntry.CreatedAt,
					AccessedAt: time.Now(),
					HitCount:   dbEntry.HitCount + 1,
				}
				entryBytes, _ := json.Marshal(entry)
				s.redisClient.SetEX(ctx, redisKey, entryBytes, ttl)
			}
			s.db.Model(&dbEntry).Updates(map[string]interface{}{
				"hit_count":   dbEntry.HitCount + 1,
				"accessed_at": time.Now(),
			})
			s.recordHit()
			log.Debug("cache hit (db)", zap.String("key", key))
			return value, true, nil
		}
	}

	s.recordMiss()
	log.Debug("cache miss", zap.String("key", key))
	return nil, false, nil
}

func (s *Service) Set(ctx context.Context, key string, value interface{}, ttl ...time.Duration) error {
	log := logger.FromContext(ctx)

	expiration := s.config.DefaultTTL
	if len(ttl) > 0 {
		expiration = ttl[0]
	}

	expiresAt := time.Now().Add(expiration)

	entry := &CacheEntry{
		Key:        key,
		Value:      value,
		ExpiresAt:  expiresAt,
		CreatedAt:  time.Now(),
		AccessedAt: time.Now(),
		HitCount:   0,
	}

	s.memoryCache.Set(key, value, expiration)

	if s.config.EnableRedis && s.redisClient != nil {
		redisKey := s.config.RedisPrefix + key
		entryBytes, err := json.Marshal(entry)
		if err != nil {
			log.Error("failed to marshal cache entry for redis", zap.String("key", key), zap.Error(err))
		} else {
			s.redisClient.SetEX(ctx, redisKey, entryBytes, expiration)
		}
	}

	switch s.config.Strategy {
	case CacheStrategyWriteThrough:
		s.persistToDB(entry)
	case CacheStrategyWriteBehind:
		s.mu.Lock()
		s.pendingWrites[key] = entry
		s.mu.Unlock()
	case CacheStrategyCacheAside:
	}

	s.recordSet()
	log.Debug("cache set", zap.String("key", key), zap.Duration("ttl", expiration))
	return nil
}

func (s *Service) Delete(ctx context.Context, key string) error {
	log := logger.FromContext(ctx)

	s.memoryCache.Delete(key)

	if s.config.EnableRedis && s.redisClient != nil {
		redisKey := s.config.RedisPrefix + key
		s.redisClient.Del(ctx, redisKey)
	}

	if err := s.db.Where("key = ?", key).Delete(&models.CacheEntry{}).Error; err != nil {
		log.Error("failed to delete cache entry from db", zap.String("key", key), zap.Error(err))
		return err
	}

	s.mu.Lock()
	delete(s.pendingWrites, key)
	s.mu.Unlock()

	s.recordDelete()
	log.Debug("cache deleted", zap.String("key", key))
	return nil
}

func (s *Service) InvalidateByPattern(ctx context.Context, pattern string) (int, error) {
	log := logger.FromContext(ctx)

	count := 0

	keys := s.memoryCache.Items()
	for key := range keys {
		if matchPattern(key, pattern) {
			s.memoryCache.Delete(key)
			count++
		}
	}

	if s.config.EnableRedis && s.redisClient != nil {
		redisPattern := s.config.RedisPrefix + pattern
		iter := s.redisClient.Scan(ctx, 0, redisPattern, 0).Iterator()
		for iter.Next(ctx) {
			s.redisClient.Del(ctx, iter.Val())
			count++
		}
		if err := iter.Err(); err != nil {
			log.Warn("redis scan error", zap.String("pattern", pattern), zap.Error(err))
		}
	}

	dbPattern := patternToSQL(pattern)
	result := s.db.Where("key LIKE ?", dbPattern).Delete(&models.CacheEntry{})
	if result.Error != nil {
		log.Error("failed to delete cache entries from db", zap.String("pattern", pattern), zap.Error(result.Error))
	} else {
		count += int(result.RowsAffected)
	}

	log.Info("cache invalidation by pattern", zap.String("pattern", pattern), zap.Int("count", count))
	return count, nil
}

func (s *Service) InvalidateByTag(ctx context.Context, tags []string) (int, error) {
	log := logger.FromContext(ctx)
	count := 0

	for _, tag := range tags {
		pattern := fmt.Sprintf("*:%s:*", tag)
		c, _ := s.InvalidateByPattern(ctx, pattern)
		count += c
	}

	log.Info("cache invalidation by tags", zap.Strings("tags", tags), zap.Int("count", count))
	return count, nil
}

func (s *Service) GetStats(ctx context.Context) CacheStats {
	s.statsMu.RLock()
	defer s.statsMu.RUnlock()

	stats := s.stats
	total := stats.Hits + stats.Misses
	if total > 0 {
		stats.HitRate = float64(stats.Hits) / float64(total)
	}
	return stats
}

func (s *Service) ResetStats(ctx context.Context) {
	s.statsMu.Lock()
	defer s.statsMu.Unlock()

	s.stats = CacheStats{LastReset: time.Now()}
	logger.FromContext(ctx).Info("cache stats reset")
}

func (s *Service) GetEntries(ctx context.Context, prefix string, limit int) ([]CacheEntry, error) {
	var result []CacheEntry

	items := s.memoryCache.Items()
	count := 0
	for key, item := range items {
		if prefix == "" || matchPattern(key, prefix+"*") {
			result = append(result, CacheEntry{
				Key:        key,
				Value:      item.Object,
				ExpiresAt:  time.Now().Add(item.Expiration),
				CreatedAt:  time.Now().Add(-item.Expiration),
				AccessedAt: time.Now(),
			})
			count++
			if limit > 0 && count >= limit {
				break
			}
		}
	}

	return result, nil
}

func (s *Service) CleanupExpired(ctx context.Context) (int, error) {
	log := logger.FromContext(ctx)

	result := s.db.Where("expires_at < ?", time.Now()).Delete(&models.CacheEntry{})
	if result.Error != nil {
		log.Error("failed to cleanup expired cache entries", zap.Error(result.Error))
		return 0, result.Error
	}

	count := int(result.RowsAffected)
	log.Info("expired cache entries cleaned up", zap.Int("count", count))
	return count, nil
}

func (s *Service) Warmup(ctx context.Context, keys []string) (int, error) {
	log := logger.FromContext(ctx)
	loaded := 0

	for _, key := range keys {
		var dbEntry models.CacheEntry
		err := s.db.Where("key = ? AND expires_at > ?", key, time.Now()).First(&dbEntry).Error
		if err == nil {
			var value interface{}
			if err := json.Unmarshal([]byte(dbEntry.Value), &value); err == nil {
				ttl := time.Until(dbEntry.ExpiresAt)
				s.memoryCache.Set(key, value, ttl)
				loaded++
			}
		}
	}

	log.Info("cache warmup completed", zap.Int("loaded", loaded), zap.Int("requested", len(keys)))
	return loaded, nil
}

func (s *Service) recordHit() {
	if !s.config.StatsEnabled {
		return
	}
	s.statsMu.Lock()
	defer s.statsMu.Unlock()
	s.stats.Hits++
}

func (s *Service) recordMiss() {
	if !s.config.StatsEnabled {
		return
	}
	s.statsMu.Lock()
	defer s.statsMu.Unlock()
	s.stats.Misses++
}

func (s *Service) recordSet() {
	if !s.config.StatsEnabled {
		return
	}
	s.statsMu.Lock()
	defer s.statsMu.Unlock()
	s.stats.Sets++
}

func (s *Service) recordDelete() {
	if !s.config.StatsEnabled {
		return
	}
	s.statsMu.Lock()
	defer s.statsMu.Unlock()
	s.stats.Deletes++
}

func (s *Service) updateAccessTime(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if entry, ok := s.pendingWrites[key]; ok {
		entry.AccessedAt = time.Now()
		entry.HitCount++
	}
}

func (s *Service) GetOrSet(ctx context.Context, key string, ttl time.Duration, loader func() (interface{}, error)) (interface{}, error) {
	if val, found, _ := s.Get(ctx, key); found {
		return val, nil
	}

	val, err := loader()
	if err != nil {
		return nil, err
	}

	if err := s.Set(ctx, key, val, ttl); err != nil {
		return nil, err
	}

	return val, nil
}

func (s *Service) BatchGet(ctx context.Context, keys []string) (map[string]interface{}, error) {
	result := make(map[string]interface{})
	for _, key := range keys {
		if val, found, _ := s.Get(ctx, key); found {
			result[key] = val
		}
	}
	return result, nil
}

func (s *Service) BatchSet(ctx context.Context, items map[string]interface{}, ttl time.Duration) error {
	for key, value := range items {
		s.Set(ctx, key, value, ttl)
	}
	return nil
}

func (s *Service) BatchDelete(ctx context.Context, keys []string) error {
	for _, key := range keys {
		s.Delete(ctx, key)
	}
	return nil
}

func matchPattern(key, pattern string) bool {
	if pattern == "*" {
		return true
	}

	for i := 0; i < len(key) && i < len(pattern); i++ {
		if pattern[i] == '*' {
			return true
		}
		if pattern[i] != key[i] {
			return false
		}
	}

	return len(key) == len(pattern)
}

func patternToSQL(pattern string) string {
	result := ""
	for _, c := range pattern {
		if c == '*' {
			result += "%"
		} else if c == '?' {
			result += "_"
		} else {
			result += string(c)
		}
	}
	return result
}

func (s *Service) Shutdown() {
	s.writeBehindTicker.Stop()
	s.flushWriteBehind()
}
