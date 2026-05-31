package service

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/database"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/internal/infrastructure/wal"
	"github.com/edgevision/edgevision/pkg/utils"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type DeviceService struct {
	db  *gorm.DB
	wal *wal.WAL
}

func NewDeviceService(walInstance *wal.WAL) *DeviceService {
	return &DeviceService{
		db:  database.GetDB(),
		wal: walInstance,
	}
}

type RegisterDeviceRequest struct {
	Name           string                 `json:"name"`
	Type           string                 `json:"type"`
	Model          string                 `json:"model"`
	SerialNumber   string                 `json:"serial_number"`
	IPAddress      string                 `json:"ip_address"`
	Location       string                 `json:"location"`
	HardwareVersion string               `json:"hardware_version"`
	FirmwareVersion string               `json:"firmware_version"`
	Protocol       string                 `json:"protocol"`
	Tags           map[string]string      `json:"tags"`
	Metadata       map[string]interface{} `json:"metadata"`
}

func (s *DeviceService) Register(ctx context.Context, req *RegisterDeviceRequest) (*model.Device, error) {
	var existing model.Device
	if err := s.db.Where("serial_number = ?", req.SerialNumber).First(&existing).Error; err == nil {
		return nil, errors.New("device with this serial number already exists")
	}

	device := &model.Device{
		ID:              utils.GenerateID("dev"),
		Name:            req.Name,
		Type:            req.Type,
		Model:           req.Model,
		SerialNumber:    req.SerialNumber,
		Status:          model.DeviceStatusInactive,
		IPAddress:       req.IPAddress,
		Location:        req.Location,
		HardwareVersion: req.HardwareVersion,
		FirmwareVersion: req.FirmwareVersion,
		Protocol:        req.Protocol,
		AuthToken:       utils.HashSHA256(utils.RandomString(32)),
		SecretKey:       utils.RandomString(64),
		Tags:            req.Tags,
		Metadata:        req.Metadata,
		RegisteredAt:    utils.Now(),
		CreatedAt:       utils.Now(),
		UpdatedAt:       utils.Now(),
	}

	if err := s.db.Create(device).Error; err != nil {
		logger.Get().Error("failed to create device", zap.Error(err))
		return nil, err
	}

	if s.wal != nil {
		_, _ = s.wal.Write("device_registered", device)
	}

	cacheKey := fmt.Sprintf("device:%s", device.ID)
	_ = cache.Set(ctx, cacheKey, utils.ToJSON(device), 24*time.Hour)

	return device, nil
}

func (s *DeviceService) Activate(ctx context.Context, deviceID string) (*model.Device, error) {
	var device model.Device
	if err := s.db.First(&device, "id = ?", deviceID).Error; err != nil {
		return nil, err
	}

	device.Status = model.DeviceStatusActive
	now := utils.Now()
	device.ActivatedAt = &now
	device.UpdatedAt = now

	if err := s.db.Save(&device).Error; err != nil {
		return nil, err
	}

	s.createDeviceEvent(ctx, device.ID, model.DeviceEventTypeActivated, "device activated", nil)

	cacheKey := fmt.Sprintf("device:%s", device.ID)
	_ = cache.Set(ctx, cacheKey, utils.ToJSON(device), 24*time.Hour)

	return &device, nil
}

func (s *DeviceService) Deactivate(ctx context.Context, deviceID string) (*model.Device, error) {
	var device model.Device
	if err := s.db.First(&device, "id = ?", deviceID).Error; err != nil {
		return nil, err
	}

	device.Status = model.DeviceStatusOffline
	device.UpdatedAt = utils.Now()

	if err := s.db.Save(&device).Error; err != nil {
		return nil, err
	}

	s.createDeviceEvent(ctx, device.ID, model.DeviceEventTypeOffline, "device deactivated", nil)

	cacheKey := fmt.Sprintf("device:%s", device.ID)
	_ = cache.Del(ctx, cacheKey)

	return &device, nil
}

func (s *DeviceService) GetByID(ctx context.Context, deviceID string) (*model.Device, error) {
	cacheKey := fmt.Sprintf("device:%s", deviceID)
	if cached, err := cache.Get(ctx, cacheKey); err == nil {
		var device model.Device
		if err := utils.FromJSON(cached, &device); err == nil {
			return &device, nil
		}
	}

	var device model.Device
	if err := s.db.First(&device, "id = ?", deviceID).Error; err != nil {
		return nil, err
	}

	_ = cache.Set(ctx, cacheKey, utils.ToJSON(device), 24*time.Hour)

	return &device, nil
}

func (s *DeviceService) List(ctx context.Context, page, pageSize int, status, deviceType string) ([]model.Device, int64, error) {
	var devices []model.Device
	var total int64

	query := s.db.Model(&model.Device{})
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if deviceType != "" {
		query = query.Where("type = ?", deviceType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&devices).Error; err != nil {
		return nil, 0, err
	}

	return devices, total, nil
}

func (s *DeviceService) Update(ctx context.Context, deviceID string, updates map[string]interface{}) (*model.Device, error) {
	var device model.Device
	if err := s.db.First(&device, "id = ?", deviceID).Error; err != nil {
		return nil, err
	}

	if err := s.db.Model(&device).Updates(updates).Error; err != nil {
		return nil, err
	}

	if err := s.db.First(&device, "id = ?", deviceID).Error; err != nil {
		return nil, err
	}

	device.UpdatedAt = utils.Now()
	_ = s.db.Save(&device)

	cacheKey := fmt.Sprintf("device:%s", device.ID)
	_ = cache.Set(ctx, cacheKey, utils.ToJSON(device), 24*time.Hour)

	return &device, nil
}

func (s *DeviceService) Delete(ctx context.Context, deviceID string) error {
	var device model.Device
	if err := s.db.First(&device, "id = ?", deviceID).Error; err != nil {
		return err
	}

	device.Status = model.DeviceStatusDeprecated
	device.UpdatedAt = utils.Now()

	if err := s.db.Save(&device).Error; err != nil {
		return err
	}

	cacheKey := fmt.Sprintf("device:%s", deviceID)
	_ = cache.Del(ctx, cacheKey)

	return nil
}

func (s *DeviceService) Heartbeat(ctx context.Context, deviceID string) error {
	var device model.Device
	if err := s.db.First(&device, "id = ?", deviceID).Error; err != nil {
		return err
	}

	now := utils.Now()
	device.LastSeenAt = &now
	if device.Status == model.DeviceStatusOffline {
		device.Status = model.DeviceStatusActive
		s.createDeviceEvent(ctx, deviceID, model.DeviceEventTypeOnline, "device came online", nil)
	}

	return s.db.Save(&device).Error
}

func (s *DeviceService) Authenticate(ctx context.Context, deviceID, token string) (bool, error) {
	var device model.Device
	if err := s.db.Select("auth_token").First(&device, "id = ?", deviceID).Error; err != nil {
		return false, err
	}

	return device.AuthToken == token, nil
}

func (s *DeviceService) createDeviceEvent(ctx context.Context, deviceID, eventType, message string, payload map[string]interface{}) {
	event := &model.DeviceEvent{
		ID:        utils.GenerateID("evt"),
		DeviceID:  deviceID,
		EventType: eventType,
		Severity:  "info",
		Message:   message,
		Payload:   payload,
		Timestamp: utils.Now(),
		CreatedAt: utils.Now(),
	}

	if eventType == model.DeviceEventTypeError {
		event.Severity = "error"
	}

	_ = s.db.Create(event).Error

	channel := fmt.Sprintf("device:%s:events", deviceID)
	_ = cache.Publish(ctx, channel, utils.ToJSON(event))
}

func (s *DeviceService) GetEvents(ctx context.Context, deviceID string, page, pageSize int) ([]model.DeviceEvent, int64, error) {
	var events []model.DeviceEvent
	var total int64

	query := s.db.Model(&model.DeviceEvent{}).Where("device_id = ?", deviceID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("timestamp DESC").Find(&events).Error; err != nil {
		return nil, 0, err
	}

	return events, total, nil
}
