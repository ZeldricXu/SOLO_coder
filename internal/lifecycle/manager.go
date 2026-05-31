package lifecycle

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/common/eventbus"
	"github.com/edgevision/edgevision/internal/common/logger"
	"github.com/edgevision/edgevision/internal/common/utils"
	"go.uber.org/zap"
)

type DeviceStatus string

const (
	DeviceStatusPending    DeviceStatus = "pending"
	DeviceStatusActive     DeviceStatus = "active"
	DeviceStatusInactive   DeviceStatus = "inactive"
	DeviceStatusOffline    DeviceStatus = "offline"
	DeviceStatusSuspended  DeviceStatus = "suspended"
	DeviceStatusDecommissioned DeviceStatus = "decommissioned"
)

type DeviceInfo struct {
	ID              string                 `json:"id"`
	Name            string                 `json:"name"`
	Type            string                 `json:"type"`
	Model           string                 `json:"model"`
	SerialNumber    string                 `json:"serial_number"`
	Status          DeviceStatus           `json:"status"`
	Secret          string                 `json:"secret,omitempty"`
	IPAddress       string                 `json:"ip_address"`
	FirmwareVersion string                 `json:"firmware_version"`
	Location        string                 `json:"location"`
	Tags            map[string]string      `json:"tags"`
	Attributes      map[string]interface{} `json:"attributes"`
	RegisteredAt    time.Time              `json:"registered_at"`
	ActivatedAt     *time.Time             `json:"activated_at"`
	LastSeen        *time.Time             `json:"last_seen"`
	LastHeartbeat   *time.Time             `json:"last_heartbeat"`
	HeartbeatInterval int                  `json:"heartbeat_interval_seconds"`
}

type AuthResult struct {
	Success  bool   `json:"success"`
	Token    string `json:"token,omitempty"`
	DeviceID string `json:"device_id,omitempty"`
	Expires  int64  `json:"expires,omitempty"`
	Error    string `json:"error,omitempty"`
}

type Manager struct {
	devices     map[string]*DeviceInfo
	secretKey   string
	heartbeatTimeout int
	mu          sync.RWMutex
	ctx         context.Context
	cancel      context.CancelFunc
	wg          sync.WaitGroup
}

func NewManager(secretKey string, heartbeatTimeout int) *Manager {
	ctx, cancel := context.WithCancel(context.Background())
	return &Manager{
		devices:     make(map[string]*DeviceInfo),
		secretKey:   secretKey,
		heartbeatTimeout: heartbeatTimeout,
		ctx:         ctx,
		cancel:      cancel,
	}
}

func (m *Manager) Register(device *DeviceInfo) (string, string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, existing := range m.devices {
		if existing.SerialNumber == device.SerialNumber {
			return "", "", errors.New("device with this serial number already registered")
		}
	}
	device.ID = utils.GenerateID("dev")
	device.Secret = m.generateSecret(device.ID)
	device.Status = DeviceStatusPending
	device.RegisteredAt = time.Now().UTC()
	device.HeartbeatInterval = 60
	device.Tags = make(map[string]string)
	device.Attributes = make(map[string]interface{})
	m.devices[device.ID] = device
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "device.registered",
		Payload: map[string]interface{}{
			"device_id": device.ID,
			"name":      device.Name,
		},
	})
	logger.Get().Info("Device registered",
		zap.String("device_id", device.ID),
		zap.String("name", device.Name),
		zap.String("serial", device.SerialNumber))
	return device.ID, device.Secret, nil
}

func (m *Manager) generateSecret(deviceID string) string {
	h := hmac.New(sha256.New, []byte(m.secretKey))
	h.Write([]byte(deviceID + time.Now().String()))
	return base64.URLEncoding.EncodeToString(h.Sum(nil))
}

func (m *Manager) Activate(deviceID, secret string) (*AuthResult, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	device, exists := m.devices[deviceID]
	if !exists {
		return nil, errors.New("device not found")
	}
	if device.Secret != secret {
		return nil, errors.New("invalid secret")
	}
	if device.Status == DeviceStatusDecommissioned {
		return nil, errors.New("device is decommissioned")
	}
	now := time.Now().UTC()
	device.Status = DeviceStatusActive
	device.ActivatedAt = &now
	device.LastSeen = &now
	device.LastHeartbeat = &now
	token := m.generateToken(deviceID)
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "device.activated",
		Payload: map[string]interface{}{"device_id": deviceID},
	})
	logger.Get().Info("Device activated", zap.String("device_id", deviceID))
	return &AuthResult{
		Success:  true,
		Token:    token,
		DeviceID: deviceID,
		Expires:  time.Now().Add(24 * time.Hour).Unix(),
	}, nil
}

func (m *Manager) generateToken(deviceID string) string {
	h := hmac.New(sha256.New, []byte(m.secretKey))
	h.Write([]byte(deviceID + time.Now().String()))
	return base64.URLEncoding.EncodeToString(h.Sum(nil))
}

func (m *Manager) Authenticate(deviceID, token string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	device, exists := m.devices[deviceID]
	if !exists {
		return false
	}
	if device.Status != DeviceStatusActive {
		return false
	}
	return token != ""
}

func (m *Manager) Heartbeat(deviceID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	device, exists := m.devices[deviceID]
	if !exists {
		return errors.New("device not found")
	}
	now := time.Now().UTC()
	device.LastHeartbeat = &now
	device.LastSeen = &now
	if device.Status == DeviceStatusOffline {
		device.Status = DeviceStatusActive
		eventbus.GetBus().Publish(eventbus.Event{
			Type: "device.online",
			Payload: map[string]interface{}{"device_id": deviceID},
		})
	}
	return nil
}

func (m *Manager) Get(deviceID string) (*DeviceInfo, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	device, exists := m.devices[deviceID]
	return device, exists
}

func (m *Manager) List() []*DeviceInfo {
	m.mu.RLock()
	defer m.mu.RUnlock()
	devices := make([]*DeviceInfo, 0, len(m.devices))
	for _, d := range m.devices {
		devices = append(devices, d)
	}
	return devices
}

func (m *Manager) Update(deviceID string, updates map[string]interface{}) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	device, exists := m.devices[deviceID]
	if !exists {
		return false
	}
	if name, ok := updates["name"].(string); ok {
		device.Name = name
	}
	if ip, ok := updates["ip_address"].(string); ok {
		device.IPAddress = ip
	}
	if firmware, ok := updates["firmware_version"].(string); ok {
		device.FirmwareVersion = firmware
	}
	if location, ok := updates["location"].(string); ok {
		device.Location = location
	}
	if tags, ok := updates["tags"].(map[string]string); ok {
		for k, v := range tags {
			device.Tags[k] = v
		}
	}
	if attrs, ok := updates["attributes"].(map[string]interface{}); ok {
		for k, v := range attrs {
			device.Attributes[k] = v
		}
	}
	return true
}

func (m *Manager) SetStatus(deviceID string, status DeviceStatus) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	device, exists := m.devices[deviceID]
	if !exists {
		return false
	}
	oldStatus := device.Status
	device.Status = status
	if status != oldStatus {
		eventbus.GetBus().Publish(eventbus.Event{
			Type: "device.status.changed",
			Payload: map[string]interface{}{
				"device_id":  deviceID,
				"old_status": oldStatus,
				"new_status": status,
			},
		})
	}
	return true
}

func (m *Manager) Decommission(deviceID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	device, exists := m.devices[deviceID]
	if !exists {
		return false
	}
	device.Status = DeviceStatusDecommissioned
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "device.decommissioned",
		Payload: map[string]interface{}{"device_id": deviceID},
	})
	logger.Get().Info("Device decommissioned", zap.String("device_id", deviceID))
	return true
}

func (m *Manager) Start() {
	m.wg.Add(1)
	go m.monitorHeartbeats()
	logger.Get().Info("Device lifecycle manager started")
}

func (m *Manager) monitorHeartbeats() {
	defer m.wg.Done()
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			m.checkOfflineDevices()
		case <-m.ctx.Done():
			return
		}
	}
}

func (m *Manager) checkOfflineDevices() {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now().UTC()
	timeout := time.Duration(m.heartbeatTimeout) * time.Second
	for _, device := range m.devices {
		if device.Status == DeviceStatusActive && device.LastHeartbeat != nil {
			if now.Sub(*device.LastHeartbeat) > timeout {
				device.Status = DeviceStatusOffline
				eventbus.GetBus().Publish(eventbus.Event{
					Type: "device.offline",
					Payload: map[string]interface{}{"device_id": device.ID},
				})
				logger.Get().Warn("Device marked offline",
					zap.String("device_id", device.ID),
					zap.Time("last_heartbeat", *device.LastHeartbeat))
			}
		}
	}
}

func (m *Manager) GetStats() map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()
	stats := map[string]int{
		"total":          0,
		"pending":        0,
		"active":         0,
		"inactive":       0,
		"offline":        0,
		"suspended":      0,
		"decommissioned": 0,
	}
	for _, device := range m.devices {
		stats["total"]++
		switch device.Status {
		case DeviceStatusPending:
			stats["pending"]++
		case DeviceStatusActive:
			stats["active"]++
		case DeviceStatusInactive:
			stats["inactive"]++
		case DeviceStatusOffline:
			stats["offline"]++
		case DeviceStatusSuspended:
			stats["suspended"]++
		case DeviceStatusDecommissioned:
			stats["decommissioned"]++
		}
	}
	return map[string]interface{}{"devices": stats}
}

func (m *Manager) Stop() {
	m.cancel()
	m.wg.Wait()
	logger.Get().Info("Device lifecycle manager stopped")
}
