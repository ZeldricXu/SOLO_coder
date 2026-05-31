package service

import (
	"context"
	"fmt"
	"reflect"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/database"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/pkg/utils"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type DeviceShadowService struct {
	db *gorm.DB
}

func NewDeviceShadowService() *DeviceShadowService {
	return &DeviceShadowService{
		db: database.GetDB(),
	}
}

func (s *DeviceShadowService) GetOrCreateShadow(ctx context.Context, deviceID string) (*model.DeviceShadow, error) {
	var shadow model.DeviceShadow
	err := s.db.Where("device_id = ?", deviceID).First(&shadow).Error
	if err == nil {
		return &shadow, nil
	}

	if err != gorm.ErrRecordNotFound {
		return nil, err
	}

	shadow = model.DeviceShadow{
		ID:        utils.GenerateID("sh"),
		DeviceID:  deviceID,
		Version:   1,
		Desired:   make(map[string]interface{}),
		Reported:  make(map[string]interface{}),
		Delta:     make(map[string]interface{}),
		Metadata:  make(map[string]interface{}),
		Timestamp: utils.Now(),
		CreatedAt: utils.Now(),
		UpdatedAt: utils.Now(),
	}

	if err := s.db.Create(&shadow).Error; err != nil {
		return nil, err
	}

	return &shadow, nil
}

type UpdateShadowRequest struct {
	DeviceID    string                 `json:"device_id"`
	State       string                 `json:"state"`
	Payload     map[string]interface{} `json:"payload"`
	ClientToken string                 `json:"client_token"`
}

func (s *DeviceShadowService) UpdateShadow(ctx context.Context, req *UpdateShadowRequest) (*model.DeviceShadow, error) {
	shadow, err := s.GetOrCreateShadow(ctx, req.DeviceID)
	if err != nil {
		return nil, err
	}

	operation := &model.ShadowOperation{
		ID:          utils.GenerateID("op"),
		DeviceID:    req.DeviceID,
		Operation:   model.OpUpdate,
		State:       req.State,
		Payload:     req.Payload,
		ClientToken: req.ClientToken,
		Status:      model.ShadowOpStatusAccepted,
		Version:     shadow.Version + 1,
		Timestamp:   utils.Now(),
		CreatedAt:   utils.Now(),
	}

	if err := s.db.Create(operation).Error; err != nil {
		return nil, err
	}

	shadow.Version++
	shadow.Timestamp = utils.Now()
	shadow.UpdatedAt = utils.Now()

	switch req.State {
	case model.StateDesired:
		shadow.Desired = s.mergeStates(shadow.Desired, req.Payload)
	case model.StateReported:
		shadow.Reported = s.mergeStates(shadow.Reported, req.Payload)
	}

	shadow.Delta = s.calculateDelta(shadow.Desired, shadow.Reported)

	if err := s.db.Save(shadow).Error; err != nil {
		return nil, err
	}

	s.createHistory(ctx, shadow, req.State, "api")

	cacheKey := fmt.Sprintf("shadow:%s", req.DeviceID)
	_ = cache.Set(ctx, cacheKey, utils.ToJSON(shadow), 1*time.Hour)

	channel := fmt.Sprintf("shadow:%s:updates", req.DeviceID)
	_ = cache.Publish(ctx, channel, utils.ToJSON(shadow))

	logger.Get().Info("shadow updated",
		zap.String("device_id", req.DeviceID),
		zap.Int64("version", shadow.Version))

	return shadow, nil
}

func (s *DeviceShadowService) mergeStates(old, new map[string]interface{}) map[string]interface{} {
	if old == nil {
		old = make(map[string]interface{})
	}
	for k, v := range new {
		old[k] = v
	}
	return old
}

func (s *DeviceShadowService) calculateDelta(desired, reported map[string]interface{}) map[string]interface{} {
	delta := make(map[string]interface{})
	for k, v := range desired {
		if rv, ok := reported[k]; !ok || !reflect.DeepEqual(v, rv) {
			delta[k] = v
		}
	}
	return delta
}

func (s *DeviceShadowService) GetShadow(ctx context.Context, deviceID string) (*model.DeviceShadow, error) {
	cacheKey := fmt.Sprintf("shadow:%s", deviceID)
	if cached, err := cache.Get(ctx, cacheKey); err == nil {
		var shadow model.DeviceShadow
		if err := utils.FromJSON(cached, &shadow); err == nil {
			return &shadow, nil
		}
	}

	shadow, err := s.GetOrCreateShadow(ctx, deviceID)
	if err != nil {
		return nil, err
	}

	_ = cache.Set(ctx, cacheKey, utils.ToJSON(shadow), 1*time.Hour)

	return shadow, nil
}

func (s *DeviceShadowService) GetDesiredState(ctx context.Context, deviceID string) (map[string]interface{}, error) {
	shadow, err := s.GetShadow(ctx, deviceID)
	if err != nil {
		return nil, err
	}
	return shadow.Desired, nil
}

func (s *DeviceShadowService) GetReportedState(ctx context.Context, deviceID string) (map[string]interface{}, error) {
	shadow, err := s.GetShadow(ctx, deviceID)
	if err != nil {
		return nil, err
	}
	return shadow.Reported, nil
}

func (s *DeviceShadowService) GetDelta(ctx context.Context, deviceID string) (map[string]interface{}, error) {
	shadow, err := s.GetShadow(ctx, deviceID)
	if err != nil {
		return nil, err
	}
	return shadow.Delta, nil
}

type ReportStateRequest struct {
	DeviceID string                 `json:"device_id"`
	Payload  map[string]interface{} `json:"payload"`
}

func (s *DeviceShadowService) ReportState(ctx context.Context, req *ReportStateRequest) (*model.DeviceShadow, error) {
	shadow, err := s.GetOrCreateShadow(ctx, req.DeviceID)
	if err != nil {
		return nil, err
	}

	shadow.Version++
	shadow.Reported = s.mergeStates(shadow.Reported, req.Payload)
	shadow.Delta = s.calculateDelta(shadow.Desired, shadow.Reported)
	shadow.Timestamp = utils.Now()
	shadow.UpdatedAt = utils.Now()

	if err := s.db.Save(shadow).Error; err != nil {
		return nil, err
	}

	s.createHistory(ctx, shadow, model.StateReported, "device")

	cacheKey := fmt.Sprintf("shadow:%s", req.DeviceID)
	_ = cache.Set(ctx, cacheKey, utils.ToJSON(shadow), 1*time.Hour)

	channel := fmt.Sprintf("shadow:%s:reported", req.DeviceID)
	_ = cache.Publish(ctx, channel, utils.ToJSON(req.Payload))

	return shadow, nil
}

func (s *DeviceShadowService) createHistory(ctx context.Context, shadow *model.DeviceShadow, changeType, changedBy string) {
	history := &model.ShadowHistory{
		ID:         utils.GenerateID("shh"),
		DeviceID:   shadow.DeviceID,
		Version:    shadow.Version,
		Desired:    shadow.Desired,
		Reported:   shadow.Reported,
		ChangeType: changeType,
		ChangedBy:  changedBy,
		Timestamp:  utils.Now(),
		CreatedAt:  utils.Now(),
	}
	_ = s.db.Create(history).Error
}

func (s *DeviceShadowService) ListShadowHistory(ctx context.Context, deviceID string, page, pageSize int) ([]model.ShadowHistory, int64, error) {
	var history []model.ShadowHistory
	var total int64

	query := s.db.Model(&model.ShadowHistory{}).Where("device_id = ?", deviceID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("timestamp DESC").Find(&history).Error; err != nil {
		return nil, 0, err
	}

	return history, total, nil
}

func (s *DeviceShadowService) GetOperation(ctx context.Context, operationID string) (*model.ShadowOperation, error) {
	var operation model.ShadowOperation
	if err := s.db.First(&operation, "id = ?", operationID).Error; err != nil {
		return nil, err
	}
	return &operation, nil
}

func (s *DeviceShadowService) UpdateOperationStatus(ctx context.Context, operationID, status string, errorCode, errorMessage *string) (*model.ShadowOperation, error) {
	var operation model.ShadowOperation
	if err := s.db.First(&operation, "id = ?", operationID).Error; err != nil {
		return nil, err
	}

	operation.Status = status
	operation.ErrorCode = errorCode
	operation.ErrorMessage = errorMessage

	if err := s.db.Save(&operation).Error; err != nil {
		return nil, err
	}

	return &operation, nil
}

func (s *DeviceShadowService) DeleteShadow(ctx context.Context, deviceID string) error {
	if err := s.db.Where("device_id = ?", deviceID).Delete(&model.DeviceShadow{}).Error; err != nil {
		return err
	}

	cacheKey := fmt.Sprintf("shadow:%s", deviceID)
	_ = cache.Del(ctx, cacheKey)

	return nil
}

func (s *DeviceShadowService) SyncDesiredToDevice(ctx context.Context, deviceID string) (*model.DeviceShadow, error) {
	shadow, err := s.GetShadow(ctx, deviceID)
	if err != nil {
		return nil, err
	}

	if len(shadow.Delta) > 0 {
		channel := fmt.Sprintf("shadow:%s:desired", deviceID)
		_ = cache.Publish(ctx, channel, utils.ToJSON(shadow.Desired))
		logger.Get().Info("sync desired state to device",
			zap.String("device_id", deviceID),
			zap.Int("delta_count", len(shadow.Delta)))
	}

	return shadow, nil
}

func (s *DeviceShadowService) SubscribeToUpdates(ctx context.Context, deviceID string, handler func(shadow *model.DeviceShadow)) {
	channel := fmt.Sprintf("shadow:%s:updates", deviceID)
	pubsub := cache.Subscribe(ctx, channel)
	defer pubsub.Close()

	ch := pubsub.Channel()
	for msg := range ch {
		var shadow model.DeviceShadow
		if err := utils.FromJSON(msg.Payload, &shadow); err == nil {
			handler(&shadow)
		}
	}
}
