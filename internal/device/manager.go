package device

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/edgeplatform/session306/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type DeviceManager struct {
	da               *data.DataAccess
	eventBus         events.EventBus
	logger           *zap.Logger
	heartbeatTimeout time.Duration
	mu               sync.RWMutex
	onlineDevices    map[string]time.Time
}

func NewDeviceManager(da *data.DataAccess, eb events.EventBus, log *zap.Logger) *DeviceManager {
	return &DeviceManager{
		da:               da,
		eventBus:         eb,
		logger:           log,
		heartbeatTimeout: 5 * time.Minute,
		onlineDevices:    make(map[string]time.Time),
	}
}

func (m *DeviceManager) Start(ctx context.Context) error {
	go m.heartbeatMonitor(ctx)
	m.logger.Info("Device manager started")
	return nil
}

func (m *DeviceManager) Register(ctx context.Context, req *model.DeviceRegisterRequest) (*model.Device, error) {
	now := utils.NowUTC()
	authToken := m.generateAuthToken()

	device := &model.Device{
		DeviceID:        utils.GenerateID("dev"),
		Name:            req.Name,
		Type:            req.Type,
		Status:          model.DeviceStatusPending,
		FirmwareVersion: req.FirmwareVersion,
		HardwareVersion: req.HardwareVersion,
		PublicKey:       req.PublicKey,
		AuthToken:       authToken,
		Metadata:        req.Metadata,
		Tags:            make(map[string]string),
		RegisteredAt:    now,
		CreatedAt:       now,
		UpdatedAt:       now,
	}

	if device.Metadata == nil {
		device.Metadata = make(map[string]interface{})
	}

	if err := m.da.DB().WithContext(ctx).Create(device).Error; err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to register device")
	}

	event := events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      events.EventDeviceRegistered,
		Source:    "device_manager",
		Timestamp: now,
		TraceID:   ctx.Value("trace_id").(string),
		Payload: map[string]interface{}{
			"device_id": device.DeviceID,
			"name":      device.Name,
			"type":      device.Type,
		},
	}
	_ = m.eventBus.Publish(ctx, event)

	m.logger.Info("Device registered",
		zap.String("device_id", device.DeviceID),
		zap.String("name", device.Name),
		zap.String("type", device.Type),
	)
	return device, nil
}

func (m *DeviceManager) Activate(ctx context.Context, req *model.DeviceActivateRequest) (*model.Device, error) {
	device, err := m.GetDevice(ctx, req.DeviceID)
	if err != nil {
		return nil, err
	}

	if device.Status == model.DeviceStatusActive || device.Status == model.DeviceStatusOnline {
		return device, nil
	}

	if device.Status != model.DeviceStatusPending {
		return nil, errors.NewValidationError("device is not in pending state")
	}

	now := utils.NowUTC()
	device.Status = model.DeviceStatusActive
	device.ActivatedAt = &now
	device.UpdatedAt = now

	if req.FirmwareVersion != "" {
		device.FirmwareVersion = req.FirmwareVersion
	}
	if req.IPAddress != "" {
		device.IPAddress = req.IPAddress
	}
	if req.Location != "" {
		device.Location = req.Location
	}
	if req.Metadata != nil {
		for k, v := range req.Metadata {
			device.Metadata[k] = v
		}
	}

	if err := m.da.DB().WithContext(ctx).Save(device).Error; err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to activate device")
	}

	m.eventBus.Publish(ctx, events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      events.EventDeviceRegistered,
		Source:    "device_manager",
		Timestamp: now,
		Payload: map[string]interface{}{
			"device_id": device.DeviceID,
		},
	})

	m.logger.Info("Device activated", zap.String("device_id", req.DeviceID))
	return device, nil
}

func (m *DeviceManager) Authenticate(ctx context.Context, req *model.DeviceAuthRequest) (string, error) {
	device, err := m.GetDevice(ctx, req.DeviceID)
	if err != nil {
		return "", err
	}

	if device.Status == model.DeviceStatusDeleted || device.Status == model.DeviceStatusSuspended {
		return "", errors.NewUnauthorizedError("device is not active")
	}

	expectedSignature := m.generateSignature(device.AuthToken, req.Timestamp)
	if req.Signature != expectedSignature {
		return "", errors.NewUnauthorizedError("invalid signature")
	}

	timeDiff := time.Since(time.Unix(req.Timestamp, 0)).Abs()
	if timeDiff > 5*time.Minute {
		return "", errors.NewUnauthorizedError("timestamp expired")
	}

	if device.Status == model.DeviceStatusPending {
		_, _ = m.Activate(ctx, &model.DeviceActivateRequest{
			DeviceID: device.DeviceID,
		})
	}

	sessionToken := utils.GenerateID("sess")

	cacheKey := fmt.Sprintf("session:%s", sessionToken)
	_ = m.da.CacheSet(ctx, cacheKey, device.DeviceID, 24*time.Hour)

	m.logger.Debug("Device authenticated",
		zap.String("device_id", device.DeviceID),
		zap.String("session_token", sessionToken),
	)
	return sessionToken, nil
}

func (m *DeviceManager) VerifySession(ctx context.Context, sessionToken string) (string, error) {
	cacheKey := fmt.Sprintf("session:%s", sessionToken)
	deviceID, err := m.da.CacheGet(ctx, cacheKey)
	if err != nil {
		return "", errors.NewUnauthorizedError("invalid or expired session")
	}
	return deviceID, nil
}

func (m *DeviceManager) ValidateSession(ctx context.Context, deviceID string, sessionToken string) (bool, error) {
	cachedDeviceID, err := m.VerifySession(ctx, sessionToken)
	if err != nil {
		return false, err
	}
	return cachedDeviceID == deviceID, nil
}

func (m *DeviceManager) Heartbeat(ctx context.Context, deviceID string, req *model.DeviceHeartbeatRequest) error {
	device, err := m.GetDevice(ctx, deviceID)
	if err != nil {
		return err
	}

	if device.Status == model.DeviceStatusDeleted || device.Status == model.DeviceStatusSuspended {
		return errors.NewUnauthorizedError("device is not active")
	}

	now := utils.NowUTC()
	updates := map[string]interface{}{
		"last_heartbeat": now,
		"last_seen_at":   now,
		"updated_at":     now,
	}

	if req.Status != "" {
		updates["status"] = req.Status
	}
	if req.FirmwareVersion != "" {
		updates["firmware_version"] = req.FirmwareVersion
	}
	if req.IPAddress != "" {
		updates["ip_address"] = req.IPAddress
	}
	if req.Metrics != nil {
		metadata := make(map[string]interface{})
		for k, v := range device.Metadata {
			metadata[k] = v
		}
		for k, v := range req.Metrics {
			metadata["metric_"+k] = v
		}
		updates["metadata"] = metadata
	}

	if err := m.da.DB().WithContext(ctx).Model(device).Updates(updates).Error; err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal, "failed to update heartbeat")
	}

	m.mu.Lock()
	wasOffline := true
	if lastSeen, ok := m.onlineDevices[deviceID]; ok {
		if time.Since(lastSeen) < m.heartbeatTimeout {
			wasOffline = false
		}
	}
	m.onlineDevices[deviceID] = now
	m.mu.Unlock()

	if wasOffline && (device.Status == model.DeviceStatusActive || device.Status == model.DeviceStatusOffline) {
		event := events.Event{
			ID:        utils.GenerateID("evt"),
			Type:      events.EventDeviceOnline,
			Source:    "device_manager",
			Timestamp: now,
			TraceID:   ctx.Value("trace_id").(string),
			Payload: map[string]interface{}{
				"device_id": deviceID,
				"data":      req.Metrics,
			},
		}
		_ = m.eventBus.Publish(ctx, event)

		m.da.DB().WithContext(ctx).Model(device).Update("status", model.DeviceStatusOnline)
	}

	return nil
}

func (m *DeviceManager) GetDevice(ctx context.Context, deviceID string) (*model.Device, error) {
	var device model.Device
	err := m.da.DB().WithContext(ctx).Where("device_id = ?", deviceID).First(&device).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("device not found")
	}
	return &device, err
}

func (m *DeviceManager) ListDevices(ctx context.Context, deviceType string, status model.DeviceStatus, offset, limit int) ([]model.Device, int64, error) {
	var devices []model.Device
	var total int64

	query := m.da.DB().WithContext(ctx).Model(&model.Device{}).
		Where("status != ?", model.DeviceStatusDeleted)

	if deviceType != "" {
		query = query.Where("type = ?", deviceType)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("created_at DESC").Find(&devices).Error
	return devices, total, err
}

func (m *DeviceManager) UpdateDevice(ctx context.Context, deviceID string, updates map[string]interface{}) (*model.Device, error) {
	device, err := m.GetDevice(ctx, deviceID)
	if err != nil {
		return nil, err
	}

	updates["updated_at"] = utils.NowUTC()

	if err := m.da.DB().WithContext(ctx).Model(device).Updates(updates).Error; err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to update device")
	}

	return m.GetDevice(ctx, deviceID)
}

func (m *DeviceManager) Suspend(ctx context.Context, deviceID string) error {
	device, err := m.GetDevice(ctx, deviceID)
	if err != nil {
		return err
	}

	if device.Status == model.DeviceStatusDeleted {
		return errors.NewValidationError("device is already deleted")
	}

	now := utils.NowUTC()
	device.Status = model.DeviceStatusSuspended
	device.UpdatedAt = now

	if err := m.da.DB().WithContext(ctx).Save(device).Error; err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal, "failed to suspend device")
	}

	m.mu.Lock()
	delete(m.onlineDevices, deviceID)
	m.mu.Unlock()

	event := events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      events.EventDeviceOffline,
		Source:    "device_manager",
		Timestamp: now,
		TraceID:   ctx.Value("trace_id").(string),
		Payload: map[string]interface{}{
			"device_id": deviceID,
			"reason":    "suspended",
		},
	}
	_ = m.eventBus.Publish(ctx, event)

	m.logger.Info("Device suspended", zap.String("device_id", deviceID))
	return nil
}

func (m *DeviceManager) Delete(ctx context.Context, deviceID string) error {
	now := utils.NowUTC()
	err := m.da.DB().WithContext(ctx).Model(&model.Device{}).
		Where("device_id = ?", deviceID).
		Updates(map[string]interface{}{
			"status":     model.DeviceStatusDeleted,
			"auth_token": "",
			"updated_at": now,
		}).Error
	if err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal, "failed to delete device")
	}

	m.mu.Lock()
	delete(m.onlineDevices, deviceID)
	m.mu.Unlock()

	m.logger.Info("Device deleted", zap.String("device_id", deviceID))
	return nil
}

func (m *DeviceManager) heartbeatMonitor(ctx context.Context) {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			m.checkOfflineDevices(ctx)
		}
	}
}

func (m *DeviceManager) checkOfflineDevices(ctx context.Context) {
	now := utils.NowUTC()
	timeoutThreshold := now.Add(-m.heartbeatTimeout)

	m.mu.Lock()
	offlineDevices := make([]string, 0)
	for deviceID, lastSeen := range m.onlineDevices {
		if lastSeen.Before(timeoutThreshold) {
			offlineDevices = append(offlineDevices, deviceID)
		}
	}
	for _, deviceID := range offlineDevices {
		delete(m.onlineDevices, deviceID)
	}
	m.mu.Unlock()

	for _, deviceID := range offlineDevices {
		m.da.DB().WithContext(ctx).Model(&model.Device{}).
			Where("device_id = ? AND status = ?", deviceID, model.DeviceStatusOnline).
			Update("status", model.DeviceStatusOffline)

		event := events.Event{
			ID:        utils.GenerateID("evt"),
			Type:      events.EventDeviceOffline,
			Source:    "device_manager",
			Timestamp: now,
			TraceID:   ctx.Value("trace_id").(string),
			Payload: map[string]interface{}{
				"device_id": deviceID,
				"reason":    "heartbeat_timeout",
			},
		}
		_ = m.eventBus.Publish(ctx, event)

		m.logger.Info("Device marked offline",
			zap.String("device_id", deviceID),
			zap.Duration("timeout", m.heartbeatTimeout),
		)
	}
}

func (m *DeviceManager) GetOnlineCount() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.onlineDevices)
}

func (m *DeviceManager) generateAuthToken() string {
	hash := sha256.Sum256([]byte(utils.GenerateID("token") + time.Now().String()))
	return hex.EncodeToString(hash[:])
}

func (m *DeviceManager) generateSignature(token string, timestamp int64) string {
	data := fmt.Sprintf("%s:%d", token, timestamp)
	hash := sha256.Sum256([]byte(data))
	return hex.EncodeToString(hash[:])
}

func (m *DeviceManager) GetMetrics(ctx context.Context) (map[string]interface{}, error) {
	var totalDevices int64
	var onlineDevices int64
	var offlineDevices int64
	var pendingDevices int64

	m.da.DB().WithContext(ctx).Model(&model.Device{}).
		Where("status != ?", model.DeviceStatusDeleted).
		Count(&totalDevices)

	m.da.DB().WithContext(ctx).Model(&model.Device{}).
		Where("status = ?", model.DeviceStatusOnline).
		Count(&onlineDevices)

	m.da.DB().WithContext(ctx).Model(&model.Device{}).
		Where("status = ?", model.DeviceStatusOffline).
		Count(&offlineDevices)

	m.da.DB().WithContext(ctx).Model(&model.Device{}).
		Where("status = ?", model.DeviceStatusPending).
		Count(&pendingDevices)

	return map[string]interface{}{
		"total_devices":   totalDevices,
		"online_devices":  onlineDevices,
		"offline_devices": offlineDevices,
		"pending_devices": pendingDevices,
		"online_realtime": m.GetOnlineCount(),
	}, nil
}

func (m *DeviceManager) List(ctx context.Context, offset, limit int) ([]model.Device, int64, error) {
	return m.ListDevices(ctx, "", "", offset, limit)
}

func (m *DeviceManager) Get(ctx context.Context, deviceID string) (*model.Device, error) {
	return m.GetDevice(ctx, deviceID)
}

func (m *DeviceManager) Deactivate(ctx context.Context, deviceID string) error {
	return m.Delete(ctx, deviceID)
}
