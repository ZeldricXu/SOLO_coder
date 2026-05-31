package device_lifecycle

import (
	"context"
	"errors"
	"fmt"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"edgescheduler/internal/common/database"
	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/common/logger"
	"edgescheduler/pkg/utils"
)

type DeviceService interface {
	RegisterDevice(ctx context.Context, req *DeviceRegistrationRequest) (*Device, error)
	ActivateDevice(ctx context.Context, req *DeviceActivationRequest) (*Device, error)
	ProcessHeartbeat(ctx context.Context, req *DeviceHeartbeatRequest) (*DeviceStatusResponse, error)
	GetDevice(ctx context.Context, deviceID string) (*Device, error)
	ListDevices(ctx context.Context, filters map[string]interface{}, offset, limit int) ([]Device, int64, error)
	UpdateDeviceStatus(ctx context.Context, deviceID string, status DeviceStatus) error
	DeactivateDevice(ctx context.Context, deviceID string, reason string) error
	DeleteDevice(ctx context.Context, deviceID string) error
	StartHeartbeatMonitor(ctx context.Context, checkInterval time.Duration, timeout time.Duration)
}

type deviceServiceImpl struct {
	db        *gorm.DB
	eventBus  eventbus.EventBus
	heartbeatCh chan *DeviceHeartbeatRequest
}

func NewDeviceService() DeviceService {
	return &deviceServiceImpl{
		db:         database.GetDB(),
		eventBus:   eventbus.GetEventBus(),
		heartbeatCh: make(chan *DeviceHeartbeatRequest, 1000),
	}
}

func NewDeviceServiceWithDeps(db *gorm.DB, eb eventbus.EventBus) DeviceService {
	return &deviceServiceImpl{
		db:         db,
		eventBus:   eb,
		heartbeatCh: make(chan *DeviceHeartbeatRequest, 1000),
	}
}

func (s *deviceServiceImpl) RegisterDevice(ctx context.Context, req *DeviceRegistrationRequest) (*Device, error) {
	logger.Info("Registering device", zap.String("device_id", req.DeviceID))

	var existing Device
	result := s.db.Where("device_id = ?", req.DeviceID).First(&existing)
	if result.Error == nil {
		return nil, errors.New("device already registered")
	}
	if !errors.Is(result.Error, gorm.ErrRecordNotFound) {
		return nil, result.Error
	}

	device := &Device{
		DeviceID:     req.DeviceID,
		Name:         req.Name,
		Type:         req.Type,
		Status:       DeviceStatusRegistered,
		Model:        req.Model,
		Manufacturer: req.Manufacturer,
		IPAddress:    req.IPAddress,
		Location:     req.Location,
		AuthToken:    utils.HashString(req.DeviceID + time.Now().String()),
		Metadata:     req.Metadata,
		Labels:       req.Labels,
	}

	if err := s.db.Create(device).Error; err != nil {
		return nil, fmt.Errorf("failed to create device: %w", err)
	}

	s.eventBus.Publish(ctx, eventbus.EventDeviceRegistered, map[string]interface{}{
		"device_id": device.DeviceID,
		"name":      device.Name,
		"type":      device.Type,
	}, "device_lifecycle")

	logger.Info("Device registered successfully",
		zap.String("device_id", device.DeviceID),
		zap.String("id", device.ID),
	)

	return device, nil
}

func (s *deviceServiceImpl) ActivateDevice(ctx context.Context, req *DeviceActivationRequest) (*Device, error) {
	logger.Info("Activating device", zap.String("device_id", req.DeviceID))

	var device Device
	if err := s.db.Where("device_id = ?", req.DeviceID).First(&device).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("device not found")
		}
		return nil, err
	}

	if device.Status == DeviceStatusActivated || device.Status == DeviceStatusOnline {
		return nil, errors.New("device already activated")
	}

	if device.Status == DeviceStatusDeactivated {
		return nil, errors.New("device has been deactivated")
	}

	now := time.Now().UTC()
	device.Status = DeviceStatusActivated
	device.ActivatedAt = &now
	device.LastHeartbeatAt = &now

	if err := s.db.Save(&device).Error; err != nil {
		return nil, fmt.Errorf("failed to activate device: %w", err)
	}

	s.eventBus.Publish(ctx, eventbus.EventDeviceActivated, map[string]interface{}{
		"device_id":    device.DeviceID,
		"activated_at": device.ActivatedAt,
	}, "device_lifecycle")

	logger.Info("Device activated successfully", zap.String("device_id", device.DeviceID))

	return &device, nil
}

func (s *deviceServiceImpl) ProcessHeartbeat(ctx context.Context, req *DeviceHeartbeatRequest) (*DeviceStatusResponse, error) {
	logger.Debug("Processing heartbeat", zap.String("device_id", req.DeviceID))

	var device Device
	if err := s.db.Where("device_id = ?", req.DeviceID).First(&device).Error; err != nil {
		return nil, errors.New("device not found")
	}

	if device.Status == DeviceStatusDeactivated {
		return nil, errors.New("device deactivated")
	}

	now := time.Now().UTC()
	oldStatus := device.Status
	device.LastHeartbeatAt = &now

	if req.FirmwareVersion != "" {
		device.FirmwareVersion = req.FirmwareVersion
	}

	if device.Status == DeviceStatusRegistered {
		device.Status = DeviceStatusActivated
		device.ActivatedAt = &now
	} else if device.Status == DeviceStatusOffline {
		device.Status = DeviceStatusOnline
	} else if device.Status == DeviceStatusActivated {
		device.Status = DeviceStatusOnline
	}

	if oldStatus != device.Status {
		s.eventBus.Publish(ctx, eventbus.EventDeviceStatusChanged, map[string]interface{}{
			"device_id":  device.DeviceID,
			"old_status": oldStatus,
			"new_status": device.Status,
		}, "device_lifecycle")
	}

	if err := s.db.Save(&device).Error; err != nil {
		return nil, fmt.Errorf("failed to update heartbeat: %w", err)
	}

	return &DeviceStatusResponse{
		DeviceID:        device.DeviceID,
		Status:          device.Status,
		LastHeartbeatAt: device.LastHeartbeatAt,
		FirmwareVersion: device.FirmwareVersion,
	}, nil
}

func (s *deviceServiceImpl) GetDevice(ctx context.Context, deviceID string) (*Device, error) {
	var device Device
	if err := s.db.Where("device_id = ?", deviceID).First(&device).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("device not found")
		}
		return nil, err
	}
	return &device, nil
}

func (s *deviceServiceImpl) ListDevices(ctx context.Context, filters map[string]interface{}, offset, limit int) ([]Device, int64, error) {
	var devices []Device
	var total int64

	query := s.db.Model(&Device{})

	if status, ok := filters["status"].(string); ok && status != "" {
		query = query.Where("status = ?", status)
	}
	if deviceType, ok := filters["type"].(string); ok && deviceType != "" {
		query = query.Where("type = ?", deviceType)
	}
	if location, ok := filters["location"].(string); ok && location != "" {
		query = query.Where("location LIKE ?", "%"+location+"%")
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Offset(offset).Limit(limit).Find(&devices).Error; err != nil {
		return nil, 0, err
	}

	return devices, total, nil
}

func (s *deviceServiceImpl) UpdateDeviceStatus(ctx context.Context, deviceID string, status DeviceStatus) error {
	var device Device
	if err := s.db.Where("device_id = ?", deviceID).First(&device).Error; err != nil {
		return err
	}

	oldStatus := device.Status
	device.Status = status

	if err := s.db.Save(&device).Error; err != nil {
		return err
	}

	if oldStatus != status {
		s.eventBus.Publish(ctx, eventbus.EventDeviceStatusChanged, map[string]interface{}{
			"device_id":  device.DeviceID,
			"old_status": oldStatus,
			"new_status": status,
		}, "device_lifecycle")
	}

	return nil
}

func (s *deviceServiceImpl) DeactivateDevice(ctx context.Context, deviceID string, reason string) error {
	logger.Info("Deactivating device",
		zap.String("device_id", deviceID),
		zap.String("reason", reason),
	)

	var device Device
	if err := s.db.Where("device_id = ?", deviceID).First(&device).Error; err != nil {
		return err
	}

	now := time.Now().UTC()
	device.Status = DeviceStatusDeactivated
	device.DeactivatedAt = &now

	if device.Metadata == nil {
		device.Metadata = make(map[string]interface{})
	}
	device.Metadata["deactivation_reason"] = reason

	if err := s.db.Save(&device).Error; err != nil {
		return fmt.Errorf("failed to deactivate device: %w", err)
	}

	s.eventBus.Publish(ctx, eventbus.EventDeviceDeactivated, map[string]interface{}{
		"device_id":          device.DeviceID,
		"deactivation_reason": reason,
		"deactivated_at":     device.DeactivatedAt,
	}, "device_lifecycle")

	logger.Info("Device deactivated successfully", zap.String("device_id", deviceID))

	return nil
}

func (s *deviceServiceImpl) DeleteDevice(ctx context.Context, deviceID string) error {
	result := s.db.Where("device_id = ?", deviceID).Delete(&Device{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("device not found")
	}
	return nil
}

func (s *deviceServiceImpl) StartHeartbeatMonitor(ctx context.Context, checkInterval time.Duration, timeout time.Duration) {
	logger.Info("Starting heartbeat monitor",
		zap.Duration("check_interval", checkInterval),
		zap.Duration("timeout", timeout),
	)

	ticker := time.NewTicker(checkInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			logger.Info("Heartbeat monitor stopped")
			return
		case <-ticker.C:
			cutoffTime := time.Now().UTC().Add(-timeout)
			var devices []Device

			err := s.db.Where("status IN (?) AND (last_heartbeat_at IS NULL OR last_heartbeat_at < ?)",
				[]DeviceStatus{DeviceStatusOnline, DeviceStatusActivated}, cutoffTime).Find(&devices).Error

			if err != nil {
				logger.Error("Failed to query devices for heartbeat check", zap.Error(err))
				continue
			}

			for _, device := range devices {
				logger.Warn("Device heartbeat timeout, marking as offline",
					zap.String("device_id", device.DeviceID),
					zap.Time("last_heartbeat", *device.LastHeartbeatAt),
				)

				device.Status = DeviceStatusOffline
				s.db.Save(&device)

				s.eventBus.Publish(ctx, eventbus.EventDeviceStatusChanged, map[string]interface{}{
					"device_id":  device.DeviceID,
					"old_status": DeviceStatusOnline,
					"new_status": DeviceStatusOffline,
					"reason":     "heartbeat_timeout",
				}, "heartbeat_monitor")
			}
		}
	}
}
