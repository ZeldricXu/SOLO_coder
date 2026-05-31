package shadow

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/common/eventbus"
	"github.com/edgevision/edgevision/internal/common/logger"
	"go.uber.org/zap"
)

type DeviceState struct {
	Reported   map[string]interface{} `json:"reported"`
	Desired    map[string]interface{} `json:"desired"`
	Delta      map[string]interface{} `json:"delta,omitempty"`
	Metadata   map[string]StateMeta   `json:"metadata"`
	Version    int64                  `json:"version"`
	LastUpdate time.Time              `json:"last_update"`
}

type StateMeta struct {
	Timestamp time.Time `json:"timestamp"`
	Version   int64     `json:"version"`
}

type ShadowDocument struct {
	DeviceID  string      `json:"device_id"`
	State     DeviceState `json:"state"`
	Timestamp time.Time   `json:"timestamp"`
}

type SyncDirection string

const (
	SyncToCloud   SyncDirection = "to_cloud"
	SyncToDevice  SyncDirection = "to_device"
	SyncBidirect  SyncDirection = "bidirectional"
)

type Manager struct {
	shadows   map[string]*ShadowDocument
	mu        sync.RWMutex
	syncQueue chan string
	direction SyncDirection
	ctx       context.Context
	cancel    context.CancelFunc
	wg        sync.WaitGroup
}

func NewManager() *Manager {
	ctx, cancel := context.WithCancel(context.Background())
	return &Manager{
		shadows:   make(map[string]*ShadowDocument),
		syncQueue: make(chan string, 1000),
		direction: SyncBidirect,
		ctx:       ctx,
		cancel:    cancel,
	}
}

func (m *Manager) GetOrCreate(deviceID string) *ShadowDocument {
	m.mu.Lock()
	defer m.mu.Unlock()
	shadow, exists := m.shadows[deviceID]
	if !exists {
		shadow = &ShadowDocument{
			DeviceID: deviceID,
			State: DeviceState{
				Reported: make(map[string]interface{}),
				Desired:  make(map[string]interface{}),
				Metadata: make(map[string]StateMeta),
				Version:  1,
			},
			Timestamp: time.Now().UTC(),
		}
		m.shadows[deviceID] = shadow
	}
	return shadow
}

func (m *Manager) Get(deviceID string) (*ShadowDocument, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	shadow, exists := m.shadows[deviceID]
	return shadow, exists
}

func (m *Manager) UpdateReported(deviceID string, reported map[string]interface{}) error {
	shadow := m.GetOrCreate(deviceID)
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now().UTC()
	for key, value := range reported {
		shadow.State.Reported[key] = value
		shadow.State.Metadata[key] = StateMeta{
			Timestamp: now,
			Version:   shadow.State.Version,
		}
	}
	shadow.State.Version++
	shadow.Timestamp = now
	m.computeDelta(shadow)
	select {
	case m.syncQueue <- deviceID:
	default:
	}
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "shadow.reported.updated",
		Payload: map[string]interface{}{
			"device_id": deviceID,
			"version":   shadow.State.Version,
		},
	})
	logger.Get().Debug("Reported state updated",
		zap.String("device_id", deviceID),
		zap.Int64("version", shadow.State.Version))
	return nil
}

func (m *Manager) UpdateDesired(deviceID string, desired map[string]interface{}) error {
	shadow := m.GetOrCreate(deviceID)
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now().UTC()
	for key, value := range desired {
		shadow.State.Desired[key] = value
	}
	shadow.State.Version++
	shadow.Timestamp = now
	m.computeDelta(shadow)
	select {
	case m.syncQueue <- deviceID:
	default:
	}
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "shadow.desired.updated",
		Payload: map[string]interface{}{
			"device_id": deviceID,
			"version":   shadow.State.Version,
		},
	})
	logger.Get().Debug("Desired state updated",
		zap.String("device_id", deviceID),
		zap.Int64("version", shadow.State.Version))
	return nil
}

func (m *Manager) computeDelta(shadow *ShadowDocument) {
	delta := make(map[string]interface{})
	for key, desiredValue := range shadow.State.Desired {
		reportedValue, exists := shadow.State.Reported[key]
		if !exists {
			delta[key] = desiredValue
			continue
		}
		desiredJSON, _ := json.Marshal(desiredValue)
		reportedJSON, _ := json.Marshal(reportedValue)
		if string(desiredJSON) != string(reportedJSON) {
			delta[key] = desiredValue
		}
	}
	if len(delta) > 0 {
		shadow.State.Delta = delta
	} else {
		shadow.State.Delta = nil
	}
}

func (m *Manager) GetDelta(deviceID string) (map[string]interface{}, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	shadow, exists := m.shadows[deviceID]
	if !exists {
		return nil, false
	}
	return shadow.State.Delta, shadow.State.Delta != nil
}

func (m *Manager) Delete(deviceID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, exists := m.shadows[deviceID]; !exists {
		return false
	}
	delete(m.shadows, deviceID)
	return true
}

func (m *Manager) ListDevices() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	devices := make([]string, 0, len(m.shadows))
	for id := range m.shadows {
		devices = append(devices, id)
	}
	return devices
}

func (m *Manager) Start() {
	m.wg.Add(1)
	go m.syncWorker()
	logger.Get().Info("Device shadow manager started")
}

func (m *Manager) syncWorker() {
	defer m.wg.Done()
	for {
		select {
		case deviceID := <-m.syncQueue:
			m.performSync(deviceID)
		case <-m.ctx.Done():
			return
		}
	}
}

func (m *Manager) performSync(deviceID string) {
	m.mu.RLock()
	shadow, exists := m.shadows[deviceID]
	m.mu.RUnlock()
	if !exists {
		return
	}
	logger.Get().Debug("Syncing device shadow",
		zap.String("device_id", deviceID),
		zap.Int64("version", shadow.State.Version))
	time.Sleep(10 * time.Millisecond)
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "shadow.synced",
		Payload: map[string]interface{}{
			"device_id": deviceID,
			"version":   shadow.State.Version,
			"direction": m.direction,
		},
	})
}

func (m *Manager) SetSyncDirection(direction SyncDirection) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.direction = direction
	logger.Get().Info("Sync direction updated", zap.String("direction", string(direction)))
}

func (m *Manager) Merge(deviceID string, patch map[string]interface{}) error {
	shadow := m.GetOrCreate(deviceID)
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now().UTC()
	if reported, ok := patch["reported"].(map[string]interface{}); ok {
		for k, v := range reported {
			shadow.State.Reported[k] = v
			shadow.State.Metadata[k] = StateMeta{Timestamp: now, Version: shadow.State.Version}
		}
	}
	if desired, ok := patch["desired"].(map[string]interface{}); ok {
		for k, v := range desired {
			shadow.State.Desired[k] = v
		}
	}
	shadow.State.Version++
	shadow.Timestamp = now
	m.computeDelta(shadow)
	return nil
}

func (m *Manager) Stop() {
	m.cancel()
	close(m.syncQueue)
	m.wg.Wait()
	logger.Get().Info("Device shadow manager stopped")
}
