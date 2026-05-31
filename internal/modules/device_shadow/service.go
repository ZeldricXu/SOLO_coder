package device_shadow

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"edgescheduler/internal/common/database"
	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/common/logger"
	"edgescheduler/pkg/utils"
)

type DeviceShadowService interface {
	GetOrCreateShadow(ctx context.Context, deviceID string) (*DeviceShadow, error)
	GetShadow(ctx context.Context, deviceID string) (*DeviceShadow, error)
	UpdateDesiredState(ctx context.Context, req *ShadowUpdateRequest) (*DeviceShadow, error)
	UpdateReportedState(ctx context.Context, req *ShadowUpdateRequest) (*DeviceShadow, error)
	DeleteShadow(ctx context.Context, deviceID string) error
	ListShadows(ctx context.Context, status ShadowSyncStatus, offset, limit int) ([]DeviceShadow, int64, error)

	SyncShadow(ctx context.Context, deviceID string) (*DeviceShadow, error)
	ResolveConflict(ctx context.Context, deviceID string, action ShadowVersionConflictAction, resolution map[string]interface{}) (*DeviceShadow, error)

	GetOperationLogs(ctx context.Context, deviceID string, offset, limit int) ([]ShadowOperationLog, int64, error)
	GetVersionHistory(ctx context.Context, deviceID string, offset, limit int) ([]ShadowVersionHistory, int64, error)
	RollbackToVersion(ctx context.Context, deviceID string, version int) (*DeviceShadow, error)

	StartShadowSync(ctx context.Context, syncInterval time.Duration)
}

type deviceShadowServiceImpl struct {
	db            *gorm.DB
	eventBus      eventbus.EventBus
	shadows       map[string]*DeviceShadow
	shadowsMu     sync.RWMutex
	syncQueue     chan string
}

func NewDeviceShadowService() DeviceShadowService {
	return &deviceShadowServiceImpl{
		db:        database.GetDB(),
		eventBus:  eventbus.GetEventBus(),
		shadows:   make(map[string]*DeviceShadow),
		syncQueue: make(chan string, 1000),
	}
}

func (s *deviceShadowServiceImpl) GetOrCreateShadow(ctx context.Context, deviceID string) (*DeviceShadow, error) {
	s.shadowsMu.RLock()
	if shadow, exists := s.shadows[deviceID]; exists {
		s.shadowsMu.RUnlock()
		return shadow, nil
	}
	s.shadowsMu.RUnlock()

	var shadow DeviceShadow
	err := s.db.Where("device_id = ?", deviceID).First(&shadow).Error
	if err == nil {
		s.shadowsMu.Lock()
		s.shadows[deviceID] = &shadow
		s.shadowsMu.Unlock()
		return &shadow, nil
	}

	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, err
	}

	shadow = DeviceShadow{
		ShadowID:       utils.GenerateID("shd"),
		DeviceID:       deviceID,
		Version:        1,
		Desired:        make(map[string]interface{}),
		Reported:       make(map[string]interface{}),
		Delta:          make(map[string]interface{}),
		Metadata:       make(map[string]interface{}),
		SyncStatus:     ShadowSyncStatusSynced,
		ConflictAction: ConflictActionMerge,
	}

	if err := s.db.Create(&shadow).Error; err != nil {
		return nil, fmt.Errorf("failed to create device shadow: %w", err)
	}

	s.shadowsMu.Lock()
	s.shadows[deviceID] = &shadow
	s.shadowsMu.Unlock()

	logger.Info("Device shadow created",
		zap.String("device_id", deviceID),
		zap.String("shadow_id", shadow.ShadowID),
	)

	s.eventBus.Publish(ctx, eventbus.EventShadowCreated, map[string]interface{}{
		"device_id": deviceID,
		"shadow_id": shadow.ShadowID,
	}, "device_shadow")

	return &shadow, nil
}

func (s *deviceShadowServiceImpl) GetShadow(ctx context.Context, deviceID string) (*DeviceShadow, error) {
	s.shadowsMu.RLock()
	if shadow, exists := s.shadows[deviceID]; exists {
		s.shadowsMu.RUnlock()
		return shadow, nil
	}
	s.shadowsMu.RUnlock()

	var shadow DeviceShadow
	if err := s.db.Where("device_id = ?", deviceID).First(&shadow).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("device shadow not found")
		}
		return nil, err
	}

	s.shadowsMu.Lock()
	s.shadows[deviceID] = &shadow
	s.shadowsMu.Unlock()

	return &shadow, nil
}

func (s *deviceShadowServiceImpl) UpdateDesiredState(ctx context.Context, req *ShadowUpdateRequest) (*DeviceShadow, error) {
	shadow, err := s.GetOrCreateShadow(ctx, req.DeviceID)
	if err != nil {
		return nil, err
	}

	if req.Version > 0 && req.Version != shadow.Version {
		return s.handleVersionConflict(ctx, shadow, req, ConflictActionLatest)
	}

	oldDesired := make(map[string]interface{})
	for k, v := range shadow.Desired {
		oldDesired[k] = v
	}

	oldVersion := shadow.Version
	newDesired := s.mergeMaps(shadow.Desired, req.Desired)

	delta := s.calculateDelta(newDesired, shadow.Reported)

	now := time.Now().UTC()
	updates := map[string]interface{}{
		"desired":              newDesired,
		"delta":                delta,
		"version":              shadow.Version + 1,
		"sync_status":          ShadowSyncStatusPending,
		"last_desired_update":  now,
	}

	if err := s.db.Model(shadow).Updates(updates).Error; err != nil {
		return nil, err
	}

	shadow.Desired = newDesired
	shadow.Delta = delta
	shadow.Version++
	shadow.SyncStatus = ShadowSyncStatusPending
	shadow.LastDesiredUpdate = &now

	s.logOperation(ctx, shadow, "update_desired", oldVersion, req.Source, req.Desired, oldDesired, req.Desired, nil, nil)
	s.saveVersionHistory(ctx, shadow, req.Source)

	select {
	case s.syncQueue <- shadow.DeviceID:
	default:
	}

	logger.Info("Desired state updated",
		zap.String("device_id", req.DeviceID),
		zap.Int("version", shadow.Version),
	)

	s.eventBus.Publish(ctx, eventbus.EventShadowDesiredUpdated, map[string]interface{}{
		"device_id": req.DeviceID,
		"version":   shadow.Version,
		"delta":     delta,
	}, "device_shadow")

	return shadow, nil
}

func (s *deviceShadowServiceImpl) UpdateReportedState(ctx context.Context, req *ShadowUpdateRequest) (*DeviceShadow, error) {
	shadow, err := s.GetOrCreateShadow(ctx, req.DeviceID)
	if err != nil {
		return nil, err
	}

	if req.Version > 0 && req.Version != shadow.Version {
		return s.handleVersionConflict(ctx, shadow, req, ConflictActionMerge)
	}

	oldReported := make(map[string]interface{})
	for k, v := range shadow.Reported {
		oldReported[k] = v
	}

	oldVersion := shadow.Version
	newReported := s.mergeMaps(shadow.Reported, req.Reported)

	delta := s.calculateDelta(shadow.Desired, newReported)

	now := time.Now().UTC()
	updates := map[string]interface{}{
		"reported":             newReported,
		"delta":                delta,
		"version":              shadow.Version + 1,
		"sync_status":          ShadowSyncStatusSynced,
		"last_reported_update": now,
		"last_synced_at":       now,
	}

	if err := s.db.Model(shadow).Updates(updates).Error; err != nil {
		return nil, err
	}

	shadow.Reported = newReported
	shadow.Delta = delta
	shadow.Version++
	shadow.SyncStatus = ShadowSyncStatusSynced
	shadow.LastReportedUpdate = &now
	shadow.LastSyncedAt = &now

	s.logOperation(ctx, shadow, "update_reported", oldVersion, req.Source, req.Reported, nil, nil, oldReported, newReported)
	s.saveVersionHistory(ctx, shadow, req.Source)

	logger.Info("Reported state updated",
		zap.String("device_id", req.DeviceID),
		zap.Int("version", shadow.Version),
	)

	s.eventBus.Publish(ctx, eventbus.EventShadowReportedUpdated, map[string]interface{}{
		"device_id": req.DeviceID,
		"version":   shadow.Version,
	}, "device_shadow")

	return shadow, nil
}

func (s *deviceShadowServiceImpl) DeleteShadow(ctx context.Context, deviceID string) error {
	result := s.db.Where("device_id = ?", deviceID).Delete(&DeviceShadow{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("device shadow not found")
	}

	s.shadowsMu.Lock()
	delete(s.shadows, deviceID)
	s.shadowsMu.Unlock()

	s.db.Where("device_id = ?", deviceID).Delete(&ShadowOperationLog{})
	s.db.Where("device_id = ?", deviceID).Delete(&ShadowVersionHistory{})

	logger.Info("Device shadow deleted",
		zap.String("device_id", deviceID),
	)

	return nil
}

func (s *deviceShadowServiceImpl) ListShadows(ctx context.Context, status ShadowSyncStatus, offset, limit int) ([]DeviceShadow, int64, error) {
	var shadows []DeviceShadow
	var total int64

	query := s.db.Model(&DeviceShadow{})
	if status != "" {
		query = query.Where("sync_status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("updated_at DESC").Offset(offset).Limit(limit).Find(&shadows).Error; err != nil {
		return nil, 0, err
	}

	return shadows, total, nil
}

func (s *deviceShadowServiceImpl) SyncShadow(ctx context.Context, deviceID string) (*DeviceShadow, error) {
	shadow, err := s.GetShadow(ctx, deviceID)
	if err != nil {
		return nil, err
	}

	shadow.SyncStatus = ShadowSyncStatusSyncing
	s.db.Save(shadow)

	logger.Info("Syncing device shadow",
		zap.String("device_id", deviceID),
	)

	go func() {
		time.Sleep(100 * time.Millisecond)

		now := time.Now().UTC()
		shadow.SyncStatus = ShadowSyncStatusSynced
		shadow.LastSyncedAt = &now
		s.db.Save(shadow)

		s.eventBus.Publish(ctx, eventbus.EventShadowSynced, map[string]interface{}{
			"device_id": deviceID,
			"version":   shadow.Version,
		}, "device_shadow")
	}()

	return shadow, nil
}

func (s *deviceShadowServiceImpl) ResolveConflict(ctx context.Context, deviceID string, action ShadowVersionConflictAction, resolution map[string]interface{}) (*DeviceShadow, error) {
	shadow, err := s.GetShadow(ctx, deviceID)
	if err != nil {
		return nil, err
	}

	if shadow.SyncStatus != ShadowSyncStatusConflicted {
		return nil, errors.New("no conflict to resolve")
	}

	switch action {
	case ConflictActionMerge:
		if resolution["desired"] != nil {
			if d, ok := resolution["desired"].(map[string]interface{}); ok {
				shadow.Desired = s.mergeMaps(shadow.Desired, d)
			}
		}
		if resolution["reported"] != nil {
			if r, ok := resolution["reported"].(map[string]interface{}); ok {
				shadow.Reported = s.mergeMaps(shadow.Reported, r)
			}
		}
	case ConflictActionLatest:
		if resolution["desired"] != nil {
			if d, ok := resolution["desired"].(map[string]interface{}); ok {
				shadow.Desired = d
			}
		}
		if resolution["reported"] != nil {
			if r, ok := resolution["reported"].(map[string]interface{}); ok {
				shadow.Reported = r
			}
		}
	}

	shadow.Delta = s.calculateDelta(shadow.Desired, shadow.Reported)
	shadow.Version++
	shadow.SyncStatus = ShadowSyncStatusSynced
	shadow.LastSyncedAt = &time.Time{}
	*shadow.LastSyncedAt = time.Now().UTC()

	s.db.Save(shadow)
	s.saveVersionHistory(ctx, shadow, "conflict_resolution")

	logger.Info("Shadow conflict resolved",
		zap.String("device_id", deviceID),
		zap.String("action", string(action)),
	)

	s.eventBus.Publish(ctx, eventbus.EventShadowConflictResolved, map[string]interface{}{
		"device_id": deviceID,
		"action":    action,
	}, "device_shadow")

	return shadow, nil
}

func (s *deviceShadowServiceImpl) GetOperationLogs(ctx context.Context, deviceID string, offset, limit int) ([]ShadowOperationLog, int64, error) {
	var logs []ShadowOperationLog
	var total int64

	query := s.db.Model(&ShadowOperationLog{}).Where("device_id = ?", deviceID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&logs).Error; err != nil {
		return nil, 0, err
	}

	return logs, total, nil
}

func (s *deviceShadowServiceImpl) GetVersionHistory(ctx context.Context, deviceID string, offset, limit int) ([]ShadowVersionHistory, int64, error) {
	var history []ShadowVersionHistory
	var total int64

	query := s.db.Model(&ShadowVersionHistory{}).Where("device_id = ?", deviceID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("version DESC").Offset(offset).Limit(limit).Find(&history).Error; err != nil {
		return nil, 0, err
	}

	return history, total, nil
}

func (s *deviceShadowServiceImpl) RollbackToVersion(ctx context.Context, deviceID string, version int) (*DeviceShadow, error) {
	var history ShadowVersionHistory
	if err := s.db.Where("device_id = ? AND version = ?", deviceID, version).First(&history).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("version not found")
		}
		return nil, err
	}

	shadow, err := s.GetShadow(ctx, deviceID)
	if err != nil {
		return nil, err
	}

	oldDesired := make(map[string]interface{})
	oldReported := make(map[string]interface{})
	for k, v := range shadow.Desired {
		oldDesired[k] = v
	}
	for k, v := range shadow.Reported {
		oldReported[k] = v
	}

	oldVersion := shadow.Version
	shadow.Desired = history.Desired
	shadow.Reported = history.Reported
	shadow.Delta = s.calculateDelta(shadow.Desired, shadow.Reported)
	shadow.Version++
	shadow.LastSyncedAt = &history.CreatedAt

	s.db.Save(shadow)

	s.logOperation(ctx, shadow, "rollback", oldVersion, "user", map[string]interface{}{
		"rollback_to": version,
	}, oldDesired, shadow.Desired, oldReported, shadow.Reported)

	logger.Info("Shadow rolled back to version",
		zap.String("device_id", deviceID),
		zap.Int("target_version", version),
		zap.Int("new_version", shadow.Version),
	)

	s.eventBus.Publish(ctx, eventbus.EventShadowRollback, map[string]interface{}{
		"device_id":      deviceID,
		"target_version": version,
		"new_version":    shadow.Version,
	}, "device_shadow")

	return shadow, nil
}

func (s *deviceShadowServiceImpl) StartShadowSync(ctx context.Context, syncInterval time.Duration) {
	logger.Info("Starting shadow sync service",
		zap.Duration("interval", syncInterval),
	)

	ticker := time.NewTicker(syncInterval)
	go func() {
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case deviceID := <-s.syncQueue:
				s.SyncShadow(ctx, deviceID)
			case <-ticker.C:
				var pendingShadows []DeviceShadow
				s.db.Where("sync_status IN ?", []ShadowSyncStatus{
					ShadowSyncStatusPending,
					ShadowSyncStatusOutOfSync,
				}).Find(&pendingShadows)

				for _, shadow := range pendingShadows {
					select {
					case s.syncQueue <- shadow.DeviceID:
					default:
					}
				}
			}
		}
	}()
}

func (s *deviceShadowServiceImpl) mergeMaps(base, override map[string]interface{}) map[string]interface{} {
	result := make(map[string]interface{})
	for k, v := range base {
		result[k] = v
	}
	for k, v := range override {
		if v == nil {
			delete(result, k)
		} else {
			result[k] = v
		}
	}
	return result
}

func (s *deviceShadowServiceImpl) calculateDelta(desired, reported map[string]interface{}) map[string]interface{} {
	delta := make(map[string]interface{})

	for k, v := range desired {
		if !reflect.DeepEqual(reported[k], v) {
			delta[k] = map[string]interface{}{
				"desired":  v,
				"reported": reported[k],
			}
		}
	}

	return delta
}

func (s *deviceShadowServiceImpl) handleVersionConflict(ctx context.Context, shadow *DeviceShadow, req *ShadowUpdateRequest, defaultAction ShadowVersionConflictAction) (*DeviceShadow, error) {
	logger.Warn("Version conflict detected",
		zap.String("device_id", req.DeviceID),
		zap.Int("expected_version", shadow.Version),
		zap.Int("received_version", req.Version),
	)

	action := shadow.ConflictAction
	if action == "" {
		action = defaultAction
	}

	switch action {
	case ConflictActionLatest:
		shadow.SyncStatus = ShadowSyncStatusConflicted
		s.db.Save(shadow)

		s.eventBus.Publish(ctx, eventbus.EventShadowConflict, map[string]interface{}{
			"device_id":        req.DeviceID,
			"expected_version": shadow.Version,
			"received_version": req.Version,
		}, "device_shadow")

		return nil, fmt.Errorf("version conflict: expected %d, received %d", shadow.Version, req.Version)
	case ConflictActionMerge:
		fallthrough
	default:
		return nil, fmt.Errorf("version conflict: expected %d, received %d", shadow.Version, req.Version)
	}
}

func (s *deviceShadowServiceImpl) logOperation(ctx context.Context, shadow *DeviceShadow, op string, oldVersion int, source string,
	changes map[string]interface{}, oldDesired, newDesired, oldReported, newReported map[string]interface{}) {
	log := &ShadowOperationLog{
		LogID:       utils.GenerateID("log"),
		DeviceID:    shadow.DeviceID,
		Operation:   op,
		OldVersion:  oldVersion,
		NewVersion:  shadow.Version,
		Source:      source,
		Changes:     changes,
		OldDesired:  oldDesired,
		NewDesired:  newDesired,
		OldReported: oldReported,
		NewReported: newReported,
	}
	s.db.Create(log)
}

func (s *deviceShadowServiceImpl) saveVersionHistory(ctx context.Context, shadow *DeviceShadow, source string) {
	history := &ShadowVersionHistory{
		HistoryID: utils.GenerateID("hist"),
		DeviceID:  shadow.DeviceID,
		Version:   shadow.Version,
		Desired:   make(map[string]interface{}),
		Reported:  make(map[string]interface{}),
		CreatedAt: time.Now().UTC(),
		Source:    source,
	}
	for k, v := range shadow.Desired {
		history.Desired[k] = v
	}
	for k, v := range shadow.Reported {
		history.Reported[k] = v
	}
	s.db.Create(history)
}
