package storage

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"container/list"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type LocalStorageManager struct {
	backupDir string
	indexFile string
	mu        sync.RWMutex
	logger    domain.Logger
	clock     domain.Clock
	cache     *LRUCache
	cacheCfg  CacheConfig
}

type CacheConfig struct {
	MaxEntries      int
	MaxSizeBytes    int64
	TTL             time.Duration
	WarmupOnStartup bool
	WarmupCount     int
}

type CacheEntry struct {
	Backup     *domain.BackupInfo
	Data       []byte
	Size       int64
	AccessedAt time.Time
	CreatedAt  time.Time
}

type LRUCache struct {
	maxEntries   int
	maxSizeBytes int64
	ttl          time.Duration
	entries      map[string]*list.Element
	lruList      *list.List
	currentSize  int64
	mu           sync.RWMutex
	hits         int64
	misses       int64
	evictions    int64
}

func NewLRUCache(maxEntries int, maxSizeBytes int64, ttl time.Duration) *LRUCache {
	return &LRUCache{
		maxEntries:   maxEntries,
		maxSizeBytes: maxSizeBytes,
		ttl:          ttl,
		entries:      make(map[string]*list.Element),
		lruList:      list.New(),
	}
}

func (c *LRUCache) Get(key string) (*CacheEntry, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	elem, exists := c.entries[key]
	if !exists {
		c.misses++
		return nil, false
	}

	entry := elem.Value.(*CacheEntry)

	if c.ttl > 0 && time.Since(entry.AccessedAt) > c.ttl {
		c.removeElement(elem)
		c.misses++
		return nil, false
	}

	c.lruList.MoveToFront(elem)
	entry.AccessedAt = time.Now().UTC()
	c.hits++

	return entry, true
}

func (c *LRUCache) Put(key string, entry *CacheEntry) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, exists := c.entries[key]; exists {
		c.lruList.Remove(elem)
		oldEntry := elem.Value.(*CacheEntry)
		c.currentSize -= oldEntry.Size
		delete(c.entries, key)
	}

	for c.maxEntries > 0 && c.lruList.Len() >= c.maxEntries {
		c.evictOldest()
	}

	for c.maxSizeBytes > 0 && c.currentSize+entry.Size > c.maxSizeBytes {
		c.evictOldest()
	}

	entry.AccessedAt = time.Now().UTC()
	entry.CreatedAt = time.Now().UTC()
	elem := c.lruList.PushFront(entry)
	c.entries[key] = elem
	c.currentSize += entry.Size
}

func (c *LRUCache) Delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, exists := c.entries[key]; exists {
		c.removeElement(elem)
	}
}

func (c *LRUCache) removeElement(elem *list.Element) {
	entry := elem.Value.(*CacheEntry)
	c.currentSize -= entry.Size
	c.lruList.Remove(elem)
	delete(c.entries, entry.Backup.ID)
	c.evictions++
}

func (c *LRUCache) evictOldest() {
	elem := c.lruList.Back()
	if elem == nil {
		return
	}
	c.removeElement(elem)
}

func (c *LRUCache) Stats() map[string]interface{} {
	c.mu.RLock()
	defer c.mu.RUnlock()

	total := c.hits + c.misses
	hitRate := 0.0
	if total > 0 {
		hitRate = float64(c.hits) / float64(total)
	}

	return map[string]interface{}{
		"hits":         c.hits,
		"misses":       c.misses,
		"evictions":    c.evictions,
		"hit_rate":     hitRate,
		"size_bytes":   c.currentSize,
		"entry_count":  c.lruList.Len(),
		"max_entries":  c.maxEntries,
		"max_size":     c.maxSizeBytes,
	}
}

func (c *LRUCache) Keys() []string {
	c.mu.RLock()
	defer c.mu.RUnlock()

	keys := make([]string, 0, len(c.entries))
	for k := range c.entries {
		keys = append(keys, k)
	}
	return keys
}

func (c *LRUCache) Purge() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.lruList.Init()
	c.entries = make(map[string]*list.Element)
	c.currentSize = 0
}

type StorageConfig struct {
	BackupDir   string
	Logger      domain.Logger
	CacheConfig CacheConfig
}

func DefaultCacheConfig() CacheConfig {
	return CacheConfig{
		MaxEntries:      100,
		MaxSizeBytes:    512 * 1024 * 1024,
		TTL:             1 * time.Hour,
		WarmupOnStartup: true,
		WarmupCount:     10,
	}
}

func NewLocalStorageManager(cfg StorageConfig) (*LocalStorageManager, error) {
	if cfg.BackupDir == "" {
		cfg.BackupDir = "./backups"
	}

	if cfg.CacheConfig.MaxEntries == 0 && cfg.CacheConfig.MaxSizeBytes == 0 && cfg.CacheConfig.TTL == 0 {
		cfg.CacheConfig = DefaultCacheConfig()
	}

	if err := os.MkdirAll(cfg.BackupDir, 0755); err != nil {
		return nil, fmt.Errorf("create backup dir: %w", err)
	}

	indexFile := filepath.Join(cfg.BackupDir, "index.json")
	if _, err := os.Stat(indexFile); os.IsNotExist(err) {
		initial := []domain.BackupInfo{}
		data, _ := json.Marshal(initial)
		os.WriteFile(indexFile, data, 0644)
	}

	mgr := &LocalStorageManager{
		backupDir: cfg.BackupDir,
		indexFile: indexFile,
		logger:    cfg.Logger,
		clock:     utils.NewRealClock(),
		cache:     NewLRUCache(cfg.CacheConfig.MaxEntries, cfg.CacheConfig.MaxSizeBytes, cfg.CacheConfig.TTL),
		cacheCfg:  cfg.CacheConfig,
	}

	if cfg.CacheConfig.WarmupOnStartup {
		go mgr.warmupCache()
	}

	go mgr.startCacheMaintenance()

	return mgr, nil
}

func (m *LocalStorageManager) warmupCache() {
	m.logger.Info("starting cache warmup")

	backups, err := m.ListBackups(context.Background())
	if err != nil {
		m.logger.Error("cache warmup failed", "error", err)
		return
	}

	sortBackupsByRecency(backups)

	count := min(len(backups), m.cacheCfg.WarmupCount)
	warmed := 0

	for i := 0; i < count; i++ {
		backup := backups[i]
		data, err := os.ReadFile(backup.Path)
		if err != nil {
			m.logger.Warn("skipping backup during warmup", "backup_id", backup.ID, "error", err)
			continue
		}

		entry := &CacheEntry{
			Backup: &backup,
			Data:   data,
			Size:   int64(len(data)),
		}
		m.cache.Put(backup.ID, entry)
		warmed++
	}

	m.logger.Info("cache warmup complete", "warmed_count", warmed)
}

func (m *LocalStorageManager) startCacheMaintenance() {
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		m.cleanupExpiredEntries()
	}
}

func (m *LocalStorageManager) cleanupExpiredEntries() {
	if m.cache.ttl <= 0 {
		return
	}

	now := time.Now()
	keys := m.cache.Keys()

	for _, key := range keys {
		if entry, exists := m.cache.Get(key); exists {
			if now.Sub(entry.AccessedAt) > m.cache.ttl {
				m.cache.Delete(key)
			}
		}
	}
}

func (m *LocalStorageManager) Backup(ctx context.Context, source string) (*domain.BackupInfo, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, err := os.Stat(source); os.IsNotExist(err) {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("source not found: %s", source))
	}

	backupID := utils.NewBackupID()
	backupPath := filepath.Join(m.backupDir, backupID+".tar.gz")

	if err := m.createTarGz(source, backupPath); err != nil {
		return nil, apperr.NewInternalError(fmt.Sprintf("backup failed: %v", err))
	}

	checksum, err := m.calculateChecksum(backupPath)
	if err != nil {
		return nil, err
	}

	info, err := os.Stat(backupPath)
	if err != nil {
		return nil, err
	}

	backup := &domain.BackupInfo{
		ID:        backupID,
		Source:    source,
		Path:      backupPath,
		Size:      info.Size(),
		CreatedAt: m.clock.Now(),
		Encrypted: false,
		Checksum:  checksum,
	}

	if err := m.addToIndex(backup); err != nil {
		return nil, err
	}

	data, _ := os.ReadFile(backupPath)
	m.cache.Put(backupID, &CacheEntry{
		Backup: backup,
		Data:   data,
		Size:   int64(len(data)),
	})

	m.logger.Info("backup completed", "backup_id", backupID, "source", source, "size", info.Size())
	return backup, nil
}

func (m *LocalStorageManager) Restore(ctx context.Context, backupID string, dest string) error {
	m.mu.RLock()

	if entry, exists := m.cache.Get(backupID); exists {
		m.mu.RUnlock()

		if err := m.extractTarGzFromBytes(entry.Data, dest); err != nil {
			return apperr.NewInternalError(fmt.Sprintf("restore from cache failed: %v", err))
		}
		m.logger.Info("restore from cache completed", "backup_id", backupID, "dest", dest)
		return nil
	}

	m.mu.RUnlock()

	m.mu.RLock()
	defer m.mu.RUnlock()

	backups, err := m.readIndex()
	if err != nil {
		return err
	}

	var backup *domain.BackupInfo
	for i := range backups {
		if backups[i].ID == backupID {
			backup = &backups[i]
			break
		}
	}

	if backup == nil {
		return apperr.NewNotFoundError(fmt.Sprintf("backup not found: %s", backupID))
	}

	if _, err := os.Stat(backup.Path); os.IsNotExist(err) {
		return apperr.NewNotFoundError(fmt.Sprintf("backup file missing: %s", backup.Path))
	}

	actualChecksum, err := m.calculateChecksum(backup.Path)
	if err != nil {
		return err
	}
	if actualChecksum != backup.Checksum {
		return apperr.NewInternalError(fmt.Sprintf("backup corrupted, checksum mismatch"))
	}

	data, err := os.ReadFile(backup.Path)
	if err == nil {
		go func() {
			m.cache.Put(backupID, &CacheEntry{
				Backup: backup,
				Data:   data,
				Size:   int64(len(data)),
			})
		}()
	}

	if err := m.extractTarGz(backup.Path, dest); err != nil {
		return apperr.NewInternalError(fmt.Sprintf("restore failed: %v", err))
	}

	m.logger.Info("restore completed", "backup_id", backupID, "dest", dest)
	return nil
}

func (m *LocalStorageManager) extractTarGzFromBytes(data []byte, dest string) error {
	if err := os.MkdirAll(dest, 0755); err != nil {
		return err
	}

	reader := bytes.NewReader(data)
	gzReader, err := gzip.NewReader(reader)
	if err != nil {
		return err
	}
	defer gzReader.Close()

	tarReader := tar.NewReader(gzReader)

	for {
		header, err := tarReader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}

		target := filepath.Join(dest, header.Name)

		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0755); err != nil {
				return err
			}
		case tar.TypeReg:
			outFile, err := os.Create(target)
			if err != nil {
				return err
			}
			if _, err := io.Copy(outFile, tarReader); err != nil {
				outFile.Close()
				return err
			}
			outFile.Close()
		}
	}
	return nil
}

func (m *LocalStorageManager) ListBackups(ctx context.Context) ([]domain.BackupInfo, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.readIndex()
}

func (m *LocalStorageManager) DeleteBackup(ctx context.Context, backupID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	backups, err := m.readIndex()
	if err != nil {
		return err
	}

	found := -1
	for i := range backups {
		if backups[i].ID == backupID {
			found = i
			break
		}
	}

	if found == -1 {
		return apperr.NewNotFoundError(fmt.Sprintf("backup not found: %s", backupID))
	}

	path := backups[found].Path
	backups = append(backups[:found], backups[found+1:]...)

	if err := m.writeIndex(backups); err != nil {
		return err
	}

	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}

	m.cache.Delete(backupID)

	m.logger.Info("backup deleted", "backup_id", backupID)
	return nil
}

func (m *LocalStorageManager) createTarGz(source, target string) error {
	file, err := os.Create(target)
	if err != nil {
		return err
	}
	defer file.Close()

	gzWriter := gzip.NewWriter(file)
	defer gzWriter.Close()

	tarWriter := tar.NewWriter(gzWriter)
	defer tarWriter.Close()

	sourceInfo, err := os.Stat(source)
	if err != nil {
		return err
	}

	var baseDir string
	if sourceInfo.IsDir() {
		baseDir = filepath.Base(source)
	}

	return filepath.Walk(source, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		header, err := tar.FileInfoHeader(info, info.Name())
		if err != nil {
			return err
		}

		if baseDir != "" {
			header.Name = filepath.Join(baseDir, path[len(source):])
		}

		if err := tarWriter.WriteHeader(header); err != nil {
			return err
		}

		if !info.IsDir() {
			f, err := os.Open(path)
			if err != nil {
				return err
			}
			defer f.Close()
			_, err = io.Copy(tarWriter, f)
			return err
		}
		return nil
	})
}

func (m *LocalStorageManager) extractTarGz(source, dest string) error {
	if err := os.MkdirAll(dest, 0755); err != nil {
		return err
	}

	file, err := os.Open(source)
	if err != nil {
		return err
	}
	defer file.Close()

	gzReader, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer gzReader.Close()

	tarReader := tar.NewReader(gzReader)

	for {
		header, err := tarReader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}

		target := filepath.Join(dest, header.Name)

		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0755); err != nil {
				return err
			}
		case tar.TypeReg:
			outFile, err := os.Create(target)
			if err != nil {
				return err
			}
			if _, err := io.Copy(outFile, tarReader); err != nil {
				outFile.Close()
				return err
			}
			outFile.Close()
		}
	}
	return nil
}

func (m *LocalStorageManager) calculateChecksum(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()

	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func (m *LocalStorageManager) readIndex() ([]domain.BackupInfo, error) {
	data, err := os.ReadFile(m.indexFile)
	if err != nil {
		return nil, err
	}

	var backups []domain.BackupInfo
	if err := json.Unmarshal(data, &backups); err != nil {
		return nil, err
	}
	return backups, nil
}

func (m *LocalStorageManager) writeIndex(backups []domain.BackupInfo) error {
	data, err := json.MarshalIndent(backups, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(m.indexFile, data, 0644)
}

func (m *LocalStorageManager) addToIndex(backup *domain.BackupInfo) error {
	backups, err := m.readIndex()
	if err != nil {
		return err
	}
	backups = append(backups, *backup)
	return m.writeIndex(backups)
}

type SnapshotPolicy struct {
	Interval time.Duration
	MaxCount int
}

func (m *LocalStorageManager) AutoSnapshot(ctx context.Context, source string, policy SnapshotPolicy) {
	ticker := time.NewTicker(policy.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if _, err := m.Backup(ctx, source); err != nil {
				m.logger.Error("auto snapshot failed", "error", err)
			}

			backups, _ := m.ListBackups(ctx)
			if len(backups) > policy.MaxCount && policy.MaxCount > 0 {
				sortBackupsByRecency(backups)
				for i := policy.MaxCount; i < len(backups); i++ {
					m.DeleteBackup(ctx, backups[i].ID)
				}
			}
		}
	}
}

func sortBackupsByRecency(backups []domain.BackupInfo) {
	sort.Slice(backups, func(i, j int) bool {
		return backups[i].CreatedAt.After(backups[j].CreatedAt)
	})
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (m *LocalStorageManager) GetCacheStats() map[string]interface{} {
	return m.cache.Stats()
}

func (m *LocalStorageManager) PurgeCache() {
	m.cache.Purge()
	m.logger.Info("cache purged")
}

func (m *LocalStorageManager) WarmupCache(count int) error {
	m.logger.Info("manual cache warmup started", "count", count)

	backups, err := m.ListBackups(context.Background())
	if err != nil {
		return err
	}

	sortBackupsByRecency(backups)

	warmed := 0
	for i := 0; i < min(len(backups), count); i++ {
		backup := backups[i]
		data, err := os.ReadFile(backup.Path)
		if err != nil {
			continue
		}
		m.cache.Put(backup.ID, &CacheEntry{
			Backup: &backup,
			Data:   data,
			Size:   int64(len(data)),
		})
		warmed++
	}

	m.logger.Info("manual cache warmup complete", "warmed_count", warmed)
	return nil
}

func (m *LocalStorageManager) PreloadBackup(backupID string) error {
	m.mu.RLock()
	backups, err := m.readIndex()
	m.mu.RUnlock()

	if err != nil {
		return err
	}

	var backup *domain.BackupInfo
	for i := range backups {
		if backups[i].ID == backupID {
			backup = &backups[i]
			break
		}
	}

	if backup == nil {
		return apperr.NewNotFoundError(fmt.Sprintf("backup not found: %s", backupID))
	}

	data, err := os.ReadFile(backup.Path)
	if err != nil {
		return err
	}

	m.cache.Put(backupID, &CacheEntry{
		Backup: backup,
		Data:   data,
		Size:   int64(len(data)),
	})

	m.logger.Info("backup preloaded to cache", "backup_id", backupID, "size", len(data))
	return nil
}

type PrefetchStrategy string

const (
	PrefetchOnAccess PrefetchStrategy = "on_access"
	PrefetchRecent   PrefetchStrategy = "recent"
	PrefetchNone     PrefetchStrategy = "none"
)

func (m *LocalStorageManager) SetPrefetchStrategy(strategy PrefetchStrategy) {
	m.logger.Info("prefetch strategy set", "strategy", strategy)
}
