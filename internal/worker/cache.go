package worker

import (
	"container/list"
	"context"
	"encoding/gob"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/cespare/xxhash/v2"
	"github.com/df1-96/experiment/pkg/util"
	"go.uber.org/zap"
)

type CacheStats struct {
	Hits        int64
	Misses      int64
	Evictions   int64
	TotalWrites int64
	TotalReads  int64
	StartTime   time.Time
}

func (s CacheStats) HitRate() float64 {
	total := s.Hits + s.Misses
	if total == 0 {
		return 0
	}
	return float64(s.Hits) / float64(total)
}

type LocalCache struct {
	config    CacheConfig
	mu        sync.RWMutex
	lruList   *list.List
	cacheMap  map[string]*list.Element
	stats     CacheStats
	running   bool
	ctx       context.Context
	cancel    context.CancelFunc
	wg        sync.WaitGroup
}

func NewLocalCache(config CacheConfig) *LocalCache {
	if config.MaxSize <= 0 {
		config.MaxSize = 10000
	}
	if config.TTL <= 0 {
		config.TTL = 24 * time.Hour
	}
	if config.PersistInterval <= 0 {
		config.PersistInterval = 5 * time.Minute
	}

	return &LocalCache{
		config:   config,
		lruList:  list.New(),
		cacheMap: make(map[string]*list.Element),
		stats: CacheStats{
			StartTime: time.Now(),
		},
	}
}

func (lc *LocalCache) Start(ctx context.Context) error {
	lc.mu.Lock()
	defer lc.mu.Unlock()

	if lc.running {
		return nil
	}

	lc.ctx, lc.cancel = context.WithCancel(ctx)
	lc.running = true

	if err := lc.loadFromDisk(); err != nil {
		util.Warn("failed to load cache from disk", zap.Error(err))
	}

	if lc.config.PersistPath != "" {
		lc.wg.Add(1)
		go lc.persistLoop()
	}

	util.Info("local cache started",
		zap.Int("max_size", lc.config.MaxSize),
		zap.Duration("ttl", lc.config.TTL),
		zap.String("persist_path", lc.config.PersistPath))

	return nil
}

func (lc *LocalCache) Stop() error {
	lc.mu.Lock()
	defer lc.mu.Unlock()

	if !lc.running {
		return nil
	}

	lc.running = false
	lc.cancel()
	lc.wg.Wait()

	if lc.config.PersistPath != "" {
		if err := lc.saveToDisk(); err != nil {
			util.Warn("failed to save cache to disk", zap.Error(err))
		}
	}

	util.Info("local cache stopped",
		zap.Int64("hits", lc.stats.Hits),
		zap.Int64("misses", lc.stats.Misses),
		zap.Float64("hit_rate", lc.stats.HitRate()))

	return nil
}

func (lc *LocalCache) persistLoop() {
	defer lc.wg.Done()

	ticker := time.NewTicker(lc.config.PersistInterval)
	defer ticker.Stop()

	for {
		select {
		case <-lc.ctx.Done():
			return
		case <-ticker.C:
			lc.mu.Lock()
			if err := lc.saveToDisk(); err != nil {
				util.Warn("periodic cache persist failed", zap.Error(err))
			}
			lc.mu.Unlock()
		}
	}
}

func (lc *LocalCache) Get(key string) (interface{}, bool) {
	lc.mu.Lock()
	defer lc.mu.Unlock()

	lc.stats.TotalReads++

	elem, exists := lc.cacheMap[key]
	if !exists {
		lc.stats.Misses++
		return nil, false
	}

	entry := elem.Value.(*cacheEntry)

	if !entry.expiresAt.IsZero() && time.Now().After(entry.expiresAt) {
		lc.removeElement(elem)
		lc.stats.Evictions++
		lc.stats.Misses++
		return nil, false
	}

	lc.lruList.MoveToFront(elem)
	entry.accessTime = time.Now()
	lc.stats.Hits++

	return entry.value, true
}

func (lc *LocalCache) Set(key string, value interface{}) error {
	return lc.SetWithTTL(key, value, lc.config.TTL)
}

func (lc *LocalCache) SetWithTTL(key string, value interface{}, ttl time.Duration) error {
	lc.mu.Lock()
	defer lc.mu.Unlock()

	lc.stats.TotalWrites++

	if elem, exists := lc.cacheMap[key]; exists {
		lc.lruList.MoveToFront(elem)
		entry := elem.Value.(*cacheEntry)
		entry.value = value
		entry.accessTime = time.Now()
		if ttl > 0 {
			entry.expiresAt = time.Now().Add(ttl)
		}
		return nil
	}

	entry := &cacheEntry{
		key:        key,
		value:      value,
		accessTime: time.Now(),
	}
	if ttl > 0 {
		entry.expiresAt = time.Now().Add(ttl)
	}

	elem := lc.lruList.PushFront(entry)
	lc.cacheMap[key] = elem

	for lc.lruList.Len() > lc.config.MaxSize {
		lc.evictOldest()
	}

	return nil
}

func (lc *LocalCache) Delete(key string) bool {
	lc.mu.Lock()
	defer lc.mu.Unlock()

	elem, exists := lc.cacheMap[key]
	if !exists {
		return false
	}

	lc.removeElement(elem)
	return true
}

func (lc *LocalCache) Has(key string) bool {
	lc.mu.RLock()
	defer lc.mu.RUnlock()

	elem, exists := lc.cacheMap[key]
	if !exists {
		return false
	}

	entry := elem.Value.(*cacheEntry)
	if !entry.expiresAt.IsZero() && time.Now().After(entry.expiresAt) {
		return false
	}

	return true
}

func (lc *LocalCache) GetOrCompute(key string, compute func() (interface{}, error)) (interface{}, error) {
	if value, ok := lc.Get(key); ok {
		return value, nil
	}

	value, err := compute()
	if err != nil {
		return nil, err
	}

	if err := lc.Set(key, value); err != nil {
		return value, err
	}

	return value, nil
}

func (lc *LocalCache) evictOldest() {
	elem := lc.lruList.Back()
	if elem != nil {
		lc.removeElement(elem)
		lc.stats.Evictions++
	}
}

func (lc *LocalCache) removeElement(elem *list.Element) {
	entry := elem.Value.(*cacheEntry)
	delete(lc.cacheMap, entry.key)
	lc.lruList.Remove(elem)
}

func (lc *LocalCache) Clear() {
	lc.mu.Lock()
	defer lc.mu.Unlock()

	lc.lruList.Init()
	lc.cacheMap = make(map[string]*list.Element)
}

func (lc *LocalCache) Len() int {
	lc.mu.RLock()
	defer lc.mu.RUnlock()
	return lc.lruList.Len()
}

func (lc *LocalCache) GetStats() CacheStats {
	lc.mu.RLock()
	defer lc.mu.RUnlock()
	return lc.stats
}

func (lc *LocalCache) ResetStats() {
	lc.mu.Lock()
	defer lc.mu.Unlock()
	lc.stats = CacheStats{
		StartTime: time.Now(),
	}
}

func (lc *LocalCache) saveToDisk() error {
	if lc.config.PersistPath == "" {
		return nil
	}

	dir := filepath.Dir(lc.config.PersistPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("failed to create cache directory: %w", err)
	}

	tmpPath := lc.config.PersistPath + ".tmp"
	file, err := os.Create(tmpPath)
	if err != nil {
		return fmt.Errorf("failed to create cache file: %w", err)
	}
	defer file.Close()

	encoder := gob.NewEncoder(file)

	entries := make([]*cacheEntry, 0, lc.lruList.Len())
	for elem := lc.lruList.Front(); elem != nil; elem = elem.Next() {
		entry := elem.Value.(*cacheEntry)
		entries = append(entries, entry)
	}

	saveData := struct {
		Entries []*cacheEntry
		Stats   CacheStats
	}{
		Entries: entries,
		Stats:   lc.stats,
	}

	if err := encoder.Encode(saveData); err != nil {
		os.Remove(tmpPath)
		return fmt.Errorf("failed to encode cache data: %w", err)
	}

	if err := file.Sync(); err != nil {
		os.Remove(tmpPath)
		return fmt.Errorf("failed to sync cache file: %w", err)
	}

	if err := os.Rename(tmpPath, lc.config.PersistPath); err != nil {
		os.Remove(tmpPath)
		return fmt.Errorf("failed to rename cache file: %w", err)
	}

	util.Debug("cache persisted to disk",
		zap.Int("entries", len(entries)),
		zap.String("path", lc.config.PersistPath))

	return nil
}

func (lc *LocalCache) loadFromDisk() error {
	if lc.config.PersistPath == "" {
		return nil
	}

	if _, err := os.Stat(lc.config.PersistPath); os.IsNotExist(err) {
		return nil
	}

	file, err := os.Open(lc.config.PersistPath)
	if err != nil {
		return fmt.Errorf("failed to open cache file: %w", err)
	}
	defer file.Close()

	decoder := gob.NewDecoder(file)

	var saveData struct {
		Entries []*cacheEntry
		Stats   CacheStats
	}

	if err := decoder.Decode(&saveData); err != nil {
		return fmt.Errorf("failed to decode cache data: %w", err)
	}

	now := time.Now()
	loadedCount := 0

	for _, entry := range saveData.Entries {
		if !entry.expiresAt.IsZero() && now.After(entry.expiresAt) {
			continue
		}

		elem := lc.lruList.PushFront(entry)
		lc.cacheMap[entry.key] = elem
		loadedCount++
	}

	lc.stats = saveData.Stats
	lc.stats.StartTime = time.Now()

	util.Info("cache loaded from disk",
		zap.Int("loaded", loadedCount),
		zap.Int("expired", len(saveData.Entries)-loadedCount))

	return nil
}

func (lc *LocalCache) GenerateCacheKey(parts ...interface{}) string {
	h := xxhash.New()
	for _, part := range parts {
		fmt.Fprintf(h, "%v|", part)
	}
	return fmt.Sprintf("%x", h.Sum64())
}

func (lc *LocalCache) Keys() []string {
	lc.mu.RLock()
	defer lc.mu.RUnlock()

	keys := make([]string, 0, lc.lruList.Len())
	for elem := lc.lruList.Front(); elem != nil; elem = elem.Next() {
		entry := elem.Value.(*cacheEntry)
		keys = append(keys, entry.key)
	}
	return keys
}

func (lc *LocalCache) IsRunning() bool {
	lc.mu.RLock()
	defer lc.mu.RUnlock()
	return lc.running
}

func init() {
	gob.Register(map[string]float64{})
	gob.Register(map[string]interface{}{})
	gob.Register([]float64{})
	gob.Register([]interface{}{})
	gob.Register(&TaskResult{})
}
