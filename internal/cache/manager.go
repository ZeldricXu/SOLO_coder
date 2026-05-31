package cache

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/common/eventbus"
	"github.com/edgevision/edgevision/internal/common/logger"
	"github.com/edgevision/edgevision/internal/common/utils"
	"go.uber.org/zap"
)

type CacheEntry struct {
	ID        string      `json:"id"`
	Data      interface{} `json:"data"`
	DataType  string      `json:"data_type"`
	Timestamp time.Time   `json:"timestamp"`
	DeviceID  string      `json:"device_id"`
	TTL       int64       `json:"ttl"`
	Size      int64       `json:"size"`
}

type SyncStatus string

const (
	SyncStatusPending SyncStatus = "pending"
	SyncStatusSyncing SyncStatus = "syncing"
	SyncStatusSynced  SyncStatus = "synced"
	SyncStatusFailed  SyncStatus = "failed"
)

type CacheStats struct {
	TotalEntries   int64         `json:"total_entries"`
	PendingSync    int64         `json:"pending_sync"`
	TotalSize      int64         `json:"total_size_bytes"`
	NetworkStatus  string        `json:"network_status"`
	LastSyncTime   *time.Time    `json:"last_sync_time"`
	FailedAttempts int           `json:"failed_attempts"`
}

type Manager struct {
	storagePath  string
	cache        map[string]CacheEntry
	syncQueue    chan string
	mu           sync.RWMutex
	networkOnline bool
	maxSize      int64
	maxEntries   int
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
}

func NewManager(storagePath string, maxSize int64, maxEntries int) (*Manager, error) {
	if err := os.MkdirAll(storagePath, 0755); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	manager := &Manager{
		storagePath:  storagePath,
		cache:        make(map[string]CacheEntry),
		syncQueue:    make(chan string, 10000),
		networkOnline: true,
		maxSize:      maxSize,
		maxEntries:   maxEntries,
		ctx:          ctx,
		cancel:       cancel,
	}
	if err := manager.loadFromDisk(); err != nil {
		logger.Get().Warn("Failed to load cache from disk", zap.Error(err))
	}
	return manager, nil
}

func (m *Manager) loadFromDisk() error {
	files, err := os.ReadDir(m.storagePath)
	if err != nil {
		return err
	}
	for _, file := range files {
		if file.IsDir() || filepath.Ext(file.Name()) != ".json" {
			continue
		}
		data, err := os.ReadFile(filepath.Join(m.storagePath, file.Name()))
		if err != nil {
			continue
		}
		var entry CacheEntry
		if err := json.Unmarshal(data, &entry); err != nil {
			continue
		}
		m.cache[entry.ID] = entry
	}
	logger.Get().Info("Cache loaded from disk", zap.Int("entries", len(m.cache)))
	return nil
}

func (m *Manager) saveToDisk(entry CacheEntry) error {
	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}
	filename := filepath.Join(m.storagePath, entry.ID+".json")
	return os.WriteFile(filename, data, 0644)
}

func (m *Manager) removeFromDisk(entryID string) error {
	filename := filepath.Join(m.storagePath, entryID+".json")
	return os.Remove(filename)
}

func (m *Manager) Store(data interface{}, dataType string, deviceID string, ttlSeconds int64) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.cache) >= m.maxEntries {
		m.evictOldest()
	}
	entry := CacheEntry{
		ID:        utils.GenerateID("cache"),
		Data:      data,
		DataType:  dataType,
		Timestamp: time.Now().UTC(),
		DeviceID:  deviceID,
		TTL:       ttlSeconds,
		Size:      int64(len(data.(string))),
	}
	if err := m.saveToDisk(entry); err != nil {
		return "", err
	}
	m.cache[entry.ID] = entry
	if !m.networkOnline {
		m.syncQueue <- entry.ID
	}
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "cache.stored",
		Payload: map[string]interface{}{
			"entry_id": entry.ID,
			"device_id": deviceID,
		},
	})
	logger.Get().Debug("Data cached", zap.String("entry_id", entry.ID))
	return entry.ID, nil
}

func (m *Manager) evictOldest() {
	var oldestID string
	var oldestTime time.Time
	for id, entry := range m.cache {
		if oldestID == "" || entry.Timestamp.Before(oldestTime) {
			oldestID = id
			oldestTime = entry.Timestamp
		}
	}
	if oldestID != "" {
		delete(m.cache, oldestID)
		_ = m.removeFromDisk(oldestID)
		logger.Get().Debug("Evicted oldest cache entry", zap.String("entry_id", oldestID))
	}
}

func (m *Manager) Get(id string) (*CacheEntry, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	entry, exists := m.cache[id]
	if !exists {
		return nil, false
	}
	if entry.TTL > 0 && time.Now().UTC().Unix() > entry.Timestamp.Unix()+entry.TTL {
		return nil, false
	}
	return &entry, true
}

func (m *Manager) Delete(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, exists := m.cache[id]; !exists {
		return errors.New("entry not found")
	}
	delete(m.cache, id)
	return m.removeFromDisk(id)
}

func (m *Manager) ListByDevice(deviceID string) []CacheEntry {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var entries []CacheEntry
	for _, entry := range m.cache {
		if entry.DeviceID == deviceID {
			entries = append(entries, entry)
		}
	}
	return entries
}

func (m *Manager) SetNetworkStatus(online bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.networkOnline != online {
		m.networkOnline = online
		if online {
			m.triggerSync()
			eventbus.GetBus().Publish(eventbus.Event{
				Type: "network.online",
				Payload: map[string]interface{}{"pending_sync": len(m.syncQueue)},
			})
			logger.Get().Info("Network online, triggering sync")
		} else {
			eventbus.GetBus().Publish(eventbus.Event{
				Type: "network.offline",
				Payload: nil,
			})
			logger.Get().Warn("Network offline, caching locally")
		}
	}
}

func (m *Manager) triggerSync() {
	for id := range m.cache {
		select {
		case m.syncQueue <- id:
		default:
		}
	}
}

func (m *Manager) Start() {
	m.wg.Add(1)
	go m.syncWorker()
	logger.Get().Info("Offline cache manager started")
}

func (m *Manager) syncWorker() {
	defer m.wg.Done()
	for {
		select {
		case entryID := <-m.syncQueue:
			if !m.networkOnline {
				time.Sleep(1 * time.Second)
				m.syncQueue <- entryID
				continue
			}
			m.syncEntry(entryID)
		case <-m.ctx.Done():
			return
		}
	}
}

func (m *Manager) syncEntry(entryID string) {
	m.mu.RLock()
	entry, exists := m.cache[entryID]
	m.mu.RUnlock()
	if !exists {
		return
	}
	logger.Get().Debug("Syncing entry", zap.String("entry_id", entryID))
	time.Sleep(10 * time.Millisecond)
	if err := m.Delete(entryID); err != nil {
		logger.Get().Error("Failed to delete synced entry", zap.Error(err))
	}
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "cache.synced",
		Payload: map[string]interface{}{
			"entry_id": entryID,
			"device_id": entry.DeviceID,
		},
	})
}

func (m *Manager) GetStats() CacheStats {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var totalSize int64
	var pending int64
	for _, entry := range m.cache {
		totalSize += entry.Size
		pending++
	}
	networkStatus := "online"
	if !m.networkOnline {
		networkStatus = "offline"
	}
	return CacheStats{
		TotalEntries:  int64(len(m.cache)),
		PendingSync:   pending,
		TotalSize:     totalSize,
		NetworkStatus: networkStatus,
	}
}

func (m *Manager) Stop() {
	m.cancel()
	close(m.syncQueue)
	m.wg.Wait()
	logger.Get().Info("Offline cache manager stopped")
}
