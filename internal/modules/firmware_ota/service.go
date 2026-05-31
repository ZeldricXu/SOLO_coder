package firmware_ota

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"edgescheduler/internal/common/database"
	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/common/logger"
	"edgescheduler/pkg/utils"
)

type FirmwareOTAService interface {
	GenerateDifferentialFirmware(ctx context.Context, req *DiffGenerationRequest) (*FirmwareImage, error)
	PublishFirmware(ctx context.Context, req *FirmwarePublishRequest) (*FirmwareImage, error)
	GetFirmware(ctx context.Context, firmwareID string) (*FirmwareImage, error)
	ListFirmwares(ctx context.Context, deviceType string, status FirmwareStatus, offset, limit int) ([]FirmwareImage, int64, error)

	CreateUpgradeBatch(ctx context.Context, req *UpgradeBatchRequest) (*UpgradeBatch, error)
	GetUpgradeBatch(ctx context.Context, batchID string) (*UpgradeBatch, error)
	ListUpgradeBatches(ctx context.Context, offset, limit int) ([]UpgradeBatch, int64, error)
	StartUpgradeBatch(ctx context.Context, batchID string) error
	PauseUpgradeBatch(ctx context.Context, batchID string) error
	CancelUpgradeBatch(ctx context.Context, batchID string) error
	RollbackUpgradeBatch(ctx context.Context, batchID string, reason string) error

	GetDeviceUpgrade(ctx context.Context, upgradeID string) (*DeviceUpgrade, error)
	ListDeviceUpgrades(ctx context.Context, deviceID string, batchID string, offset, limit int) ([]DeviceUpgrade, int64, error)
	UpdateDeviceUpgradeStatus(ctx context.Context, upgradeID string, phase UpgradePhase, progress int, errDetail string) error
	RetryDeviceUpgrade(ctx context.Context, upgradeID string) error

	CreateUpgradePolicy(ctx context.Context, policy *UpgradePolicy) (*UpgradePolicy, error)
	ListUpgradePolicies(ctx context.Context, offset, limit int) ([]UpgradePolicy, int64, error)

	StartOTAManager(ctx context.Context)
}

type firmwareOTAServiceImpl struct {
	db               *gorm.DB
	eventBus         eventbus.EventBus
	upgradeQueue     chan *DeviceUpgrade
	batchProgressCh  chan string
	activeBatches    map[string]*UpgradeBatch
	activeBatchesMu  sync.RWMutex
}

func NewFirmwareOTAService() FirmwareOTAService {
	return &firmwareOTAServiceImpl{
		db:              database.GetDB(),
		eventBus:        eventbus.GetEventBus(),
		upgradeQueue:    make(chan *DeviceUpgrade, 1000),
		batchProgressCh: make(chan string, 100),
		activeBatches:   make(map[string]*UpgradeBatch),
	}
}

func (s *firmwareOTAServiceImpl) GenerateDifferentialFirmware(ctx context.Context, req *DiffGenerationRequest) (*FirmwareImage, error) {
	logger.Info("Generating differential firmware",
		zap.String("name", req.Name),
		zap.String("version", req.Version),
		zap.String("base_version", req.BaseVersion),
	)

	var baseFirmware FirmwareImage
	if err := s.db.Where("version = ? AND device_type = ? AND status = ?",
		req.BaseVersion, req.DeviceType, FirmwareStatusPublished).First(&baseFirmware).Error; err != nil {
		return nil, fmt.Errorf("base firmware not found: %w", err)
	}

	firmware := &FirmwareImage{
		FirmwareID:     utils.GenerateID("fw"),
		Name:           req.Name,
		Version:        req.Version,
		DeviceType:     req.DeviceType,
		Status:         FirmwareStatusDraft,
		Description:    req.Description,
		FileURL:        req.FileURL,
		FileSize:       req.FileSize,
		Checksum:       req.Checksum,
		ChecksumType:   "sha256",
		IsDifferential: true,
		BaseVersion:    req.BaseVersion,
		DiffPatchURL:   req.FileURL + ".patch",
		DiffSize:       req.FileSize / 3,
		Metadata:       req.Metadata,
	}

	if err := s.db.Create(firmware).Error; err != nil {
		return nil, fmt.Errorf("failed to create firmware: %w", err)
	}

	logger.Info("Differential firmware generated",
		zap.String("firmware_id", firmware.FirmwareID),
		zap.Int64("diff_size", firmware.DiffSize),
	)

	s.eventBus.Publish(ctx, eventbus.EventFirmwareGenerated, map[string]interface{}{
		"firmware_id": firmware.FirmwareID,
		"version":     firmware.Version,
	}, "firmware_ota")

	return firmware, nil
}

func (s *firmwareOTAServiceImpl) PublishFirmware(ctx context.Context, req *FirmwarePublishRequest) (*FirmwareImage, error) {
	var firmware FirmwareImage
	if err := s.db.Where("firmware_id = ?", req.FirmwareID).First(&firmware).Error; err != nil {
		return nil, errors.New("firmware not found")
	}

	if firmware.Status != FirmwareStatusDraft {
		return nil, errors.New("firmware is not in draft status")
	}

	now := time.Now().UTC()
	firmware.Status = FirmwareStatusPublished
	firmware.PublishedAt = &now

	if err := s.db.Save(&firmware).Error; err != nil {
		return nil, err
	}

	logger.Info("Firmware published",
		zap.String("firmware_id", firmware.FirmwareID),
		zap.String("version", firmware.Version),
	)

	s.eventBus.Publish(ctx, eventbus.EventFirmwarePublished, map[string]interface{}{
		"firmware_id": firmware.FirmwareID,
		"version":     firmware.Version,
	}, "firmware_ota")

	return &firmware, nil
}

func (s *firmwareOTAServiceImpl) GetFirmware(ctx context.Context, firmwareID string) (*FirmwareImage, error) {
	var firmware FirmwareImage
	if err := s.db.Where("firmware_id = ?", firmwareID).First(&firmware).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("firmware not found")
		}
		return nil, err
	}
	return &firmware, nil
}

func (s *firmwareOTAServiceImpl) ListFirmwares(ctx context.Context, deviceType string, status FirmwareStatus, offset, limit int) ([]FirmwareImage, int64, error) {
	var firmwares []FirmwareImage
	var total int64

	query := s.db.Model(&FirmwareImage{})
	if deviceType != "" {
		query = query.Where("device_type = ?", deviceType)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&firmwares).Error; err != nil {
		return nil, 0, err
	}

	return firmwares, total, nil
}

func (s *firmwareOTAServiceImpl) CreateUpgradeBatch(ctx context.Context, req *UpgradeBatchRequest) (*UpgradeBatch, error) {
	var firmware FirmwareImage
	if err := s.db.Where("firmware_id = ?", req.FirmwareID).First(&firmware).Error; err != nil {
		return nil, errors.New("firmware not found")
	}

	if firmware.Status != FirmwareStatusPublished {
		return nil, errors.New("firmware is not published")
	}

	if req.Strategy == "" {
		req.Strategy = "gradual"
	}
	if req.GradualSteps == 0 {
		req.GradualSteps = 4
	}
	if req.MaxFailures == 0 {
		req.MaxFailures = 5
	}
	if req.MaxConcurrent == 0 {
		req.MaxConcurrent = 10
	}

	batch := &UpgradeBatch{
		BatchID:        utils.GenerateID("bat"),
		Name:           req.Name,
		FirmwareID:     req.FirmwareID,
		DeviceType:     firmware.DeviceType,
		TotalCount:     len(req.DeviceIDs),
		SuccessCount:   0,
		FailedCount:    0,
		RolledBackCount: 0,
		MaxFailures:    req.MaxFailures,
		AutoRollback:   req.AutoRollback,
		Strategy:       req.Strategy,
		GradualSteps:   req.GradualSteps,
		CurrentStep:    0,
		StepProgress:   0,
		Status:         UpgradeStatusQueued,
		MaxConcurrent:  req.MaxConcurrent,
	}

	tx := s.db.Begin()
	if err := tx.Create(batch).Error; err != nil {
		tx.Rollback()
		return nil, fmt.Errorf("failed to create batch: %w", err)
	}

	for _, deviceID := range req.DeviceIDs {
		upgrade := &DeviceUpgrade{
			UpgradeID:       utils.GenerateID("upg"),
			BatchID:         batch.BatchID,
			DeviceID:        deviceID,
			FirmwareID:      req.FirmwareID,
			TargetVersion:   firmware.Version,
			Phase:           UpgradePhasePending,
			Status:          UpgradeStatusQueued,
			Progress:        0,
			UseDifferential: firmware.IsDifferential,
			MaxRetries:      3,
		}
		if err := tx.Create(upgrade).Error; err != nil {
			tx.Rollback()
			return nil, fmt.Errorf("failed to create upgrade for device %s: %w", deviceID, err)
		}
	}

	tx.Commit()

	logger.Info("Upgrade batch created",
		zap.String("batch_id", batch.BatchID),
		zap.Int("total_devices", batch.TotalCount),
	)

	s.eventBus.Publish(ctx, eventbus.EventUpgradeBatchCreated, map[string]interface{}{
		"batch_id":     batch.BatchID,
		"firmware_id":  batch.FirmwareID,
		"total_count":  batch.TotalCount,
	}, "firmware_ota")

	return batch, nil
}

func (s *firmwareOTAServiceImpl) GetUpgradeBatch(ctx context.Context, batchID string) (*UpgradeBatch, error) {
	var batch UpgradeBatch
	if err := s.db.Where("batch_id = ?", batchID).First(&batch).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("batch not found")
		}
		return nil, err
	}
	return &batch, nil
}

func (s *firmwareOTAServiceImpl) ListUpgradeBatches(ctx context.Context, offset, limit int) ([]UpgradeBatch, int64, error) {
	var batches []UpgradeBatch
	var total int64

	if err := s.db.Model(&UpgradeBatch{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := s.db.Order("created_at DESC").Offset(offset).Limit(limit).Find(&batches).Error; err != nil {
		return nil, 0, err
	}

	return batches, total, nil
}

func (s *firmwareOTAServiceImpl) StartUpgradeBatch(ctx context.Context, batchID string) error {
	var batch UpgradeBatch
	if err := s.db.Where("batch_id = ?", batchID).First(&batch).Error; err != nil {
		return errors.New("batch not found")
	}

	if batch.Status != UpgradeStatusQueued {
		return errors.New("batch is not in queued status")
	}

	now := time.Now().UTC()
	batch.Status = UpgradeStatusInProgress
	batch.StartTime = &now
	batch.CurrentStep = 1
	s.db.Save(&batch)

	s.activeBatchesMu.Lock()
	s.activeBatches[batch.BatchID] = &batch
	s.activeBatchesMu.Unlock()

	var upgrades []DeviceUpgrade
	s.db.Where("batch_id = ? AND status = ?", batchID, UpgradeStatusQueued).Find(&upgrades)

	go s.processBatchUpgrades(ctx, &batch, upgrades)

	logger.Info("Upgrade batch started",
		zap.String("batch_id", batchID),
		zap.Int("total_devices", batch.TotalCount),
	)

	s.eventBus.Publish(ctx, eventbus.EventUpgradeStarted, map[string]interface{}{
		"batch_id": batchID,
	}, "firmware_ota")

	return nil
}

func (s *firmwareOTAServiceImpl) processBatchUpgrades(ctx context.Context, batch *UpgradeBatch, upgrades []DeviceUpgrade) {
	sem := make(chan struct{}, batch.MaxConcurrent)
	var wg sync.WaitGroup

	for i, upgrade := range upgrades {
		if batch.Strategy == "gradual" {
			stepSize := (batch.TotalCount + batch.GradualSteps - 1) / batch.GradualSteps
			currentStep := (i / stepSize) + 1
			if currentStep > batch.CurrentStep {
				batch.CurrentStep = currentStep
				batch.StepProgress = float64(currentStep) / float64(batch.GradualSteps)
				s.db.Save(batch)

				var inProgress int64
				s.db.Model(&DeviceUpgrade{}).Where(
					"batch_id = ? AND status IN ?",
					batch.BatchID,
					[]UpgradeStatus{UpgradeStatusInProgress, UpgradeStatusQueued},
				).Count(&inProgress)

				for inProgress > 0 {
					time.Sleep(2 * time.Second)
					s.db.Model(&DeviceUpgrade{}).Where(
						"batch_id = ? AND status IN ?",
						batch.BatchID,
						[]UpgradeStatus{UpgradeStatusInProgress, UpgradeStatusQueued},
					).Count(&inProgress)
				}

				var failedCount int64
				s.db.Model(&DeviceUpgrade{}).Where(
					"batch_id = ? AND status = ?",
					batch.BatchID, UpgradeStatusFailed,
				).Count(&failedCount)

				if failedCount > int64(batch.MaxFailures) && batch.AutoRollback {
					s.RollbackUpgradeBatch(ctx, batch.BatchID, "failure threshold exceeded")
					return
				}
			}
		}

		select {
		case <-ctx.Done():
			return
		default:
		}

		sem <- struct{}{}
		wg.Add(1)

		upg := upgrade
		go func() {
			defer wg.Done()
			defer func() { <-sem }()

			s.processUpgrade(ctx, batch, &upg)
		}()
	}

	wg.Wait()

	s.finalizeBatch(ctx, batch)
}

func (s *firmwareOTAServiceImpl) processUpgrade(ctx context.Context, batch *UpgradeBatch, upgrade *DeviceUpgrade) {
	updates := map[string]interface{}{
		"status": UpgradeStatusInProgress,
		"phase":  UpgradePhaseDownload,
	}
	s.db.Model(upgrade).Updates(updates)

	phases := []struct {
		phase UpgradePhase
		sleep time.Duration
	}{
		{UpgradePhaseDownload, 200 * time.Millisecond},
		{UpgradePhaseInstall, 300 * time.Millisecond},
		{UpgradePhaseVerify, 150 * time.Millisecond},
	}

	for i, p := range phases {
		if ctx.Err() != nil {
			return
		}

		time.Sleep(p.sleep)

		progress := ((i + 1) * 100) / len(phases)
		s.db.Model(upgrade).Updates(map[string]interface{}{
			"phase":    p.phase,
			"progress": progress,
		})

		s.eventBus.Publish(ctx, eventbus.EventUpgradeProgress, map[string]interface{}{
			"upgrade_id": upgrade.UpgradeID,
			"device_id":  upgrade.DeviceID,
			"phase":      p.phase,
			"progress":   progress,
		}, "firmware_ota")
	}

	shouldFail := upgrade.DeviceID[len(upgrade.DeviceID)-1] == '9'

	if shouldFail {
		s.db.Model(upgrade).Updates(map[string]interface{}{
			"status":       UpgradeStatusFailed,
			"phase":        UpgradePhaseFailed,
			"error_detail": "simulated upgrade failure",
		})

		s.db.Model(batch).UpdateColumn("failed_count", gorm.Expr("failed_count + 1"))

		s.eventBus.Publish(ctx, eventbus.EventUpgradeFailed, map[string]interface{}{
			"upgrade_id": upgrade.UpgradeID,
			"device_id":  upgrade.DeviceID,
			"error":      "simulated upgrade failure",
		}, "firmware_ota")

		return
	}

	now := time.Now().UTC()
	s.db.Model(upgrade).Updates(map[string]interface{}{
		"status":                UpgradeStatusSuccess,
		"phase":                 UpgradePhaseComplete,
		"progress":              100,
		"install_completed_at":  now,
	})

	s.db.Model(batch).UpdateColumn("success_count", gorm.Expr("success_count + 1"))

	s.eventBus.Publish(ctx, eventbus.EventUpgradeSuccess, map[string]interface{}{
		"upgrade_id": upgrade.UpgradeID,
		"device_id":  upgrade.DeviceID,
	}, "firmware_ota")
}

func (s *firmwareOTAServiceImpl) finalizeBatch(ctx context.Context, batch *UpgradeBatch) {
	var successCount, failedCount int64
	s.db.Model(&DeviceUpgrade{}).Where(
		"batch_id = ? AND status = ?",
		batch.BatchID, UpgradeStatusSuccess,
	).Count(&successCount)
	s.db.Model(&DeviceUpgrade{}).Where(
		"batch_id = ? AND status = ?",
		batch.BatchID, UpgradeStatusFailed,
	).Count(&failedCount)

	now := time.Now().UTC()
	batch.SuccessCount = int(successCount)
	batch.FailedCount = int(failedCount)
	batch.EndTime = &now

	if failedCount > int64(batch.MaxFailures) {
		batch.Status = UpgradeStatusFailed
	} else {
		batch.Status = UpgradeStatusSuccess
	}
	batch.StepProgress = 1.0
	batch.CurrentStep = batch.GradualSteps

	s.db.Save(batch)

	s.activeBatchesMu.Lock()
	delete(s.activeBatches, batch.BatchID)
	s.activeBatchesMu.Unlock()

	logger.Info("Upgrade batch finalized",
		zap.String("batch_id", batch.BatchID),
		zap.Int("success", int(successCount)),
		zap.Int("failed", int(failedCount)),
	)

	s.eventBus.Publish(ctx, eventbus.EventUpgradeBatchCompleted, map[string]interface{}{
		"batch_id":      batch.BatchID,
		"status":        batch.Status,
		"success_count": successCount,
		"failed_count":  failedCount,
	}, "firmware_ota")
}

func (s *firmwareOTAServiceImpl) PauseUpgradeBatch(ctx context.Context, batchID string) error {
	return errors.New("pause not implemented in this version")
}

func (s *firmwareOTAServiceImpl) CancelUpgradeBatch(ctx context.Context, batchID string) error {
	result := s.db.Model(&DeviceUpgrade{}).Where(
		"batch_id = ? AND status IN ?",
		batchID, []UpgradeStatus{UpgradeStatusQueued, UpgradeStatusInProgress},
	).Updates(map[string]interface{}{
		"status": UpgradeStatusCancelled,
		"phase":  UpgradePhaseFailed,
	})

	if result.Error != nil {
		return result.Error
	}

	s.db.Model(&UpgradeBatch{}).Where("batch_id = ?", batchID).Update("status", UpgradeStatusCancelled)

	s.activeBatchesMu.Lock()
	delete(s.activeBatches, batchID)
	s.activeBatchesMu.Unlock()

	return nil
}

func (s *firmwareOTAServiceImpl) RollbackUpgradeBatch(ctx context.Context, batchID string, reason string) error {
	logger.Warn("Rolling back upgrade batch",
		zap.String("batch_id", batchID),
		zap.String("reason", reason),
	)

	successUpgrades := make([]DeviceUpgrade, 0)
	s.db.Where("batch_id = ? AND status = ?", batchID, UpgradeStatusSuccess).Find(&successUpgrades)

	for i := range successUpgrades {
		upgrade := &successUpgrades[i]
		s.db.Model(upgrade).Updates(map[string]interface{}{
			"status":           UpgradeStatusRolledBack,
			"phase":            UpgradePhaseRollback,
			"rollback_reason":  reason,
		})

		s.eventBus.Publish(ctx, eventbus.EventUpgradeRollback, map[string]interface{}{
			"upgrade_id": upgrade.UpgradeID,
			"device_id":  upgrade.DeviceID,
			"reason":     reason,
		}, "firmware_ota")
	}

	var rolledBack int64
	s.db.Model(&DeviceUpgrade{}).Where(
		"batch_id = ? AND status = ?",
		batchID, UpgradeStatusRolledBack,
	).Count(&rolledBack)

	s.db.Model(&UpgradeBatch{}).Where("batch_id = ?", batchID).Updates(map[string]interface{}{
		"status":            UpgradeStatusRolledBack,
		"rolled_back_count": rolledBack,
	})

	s.activeBatchesMu.Lock()
	delete(s.activeBatches, batchID)
	s.activeBatchesMu.Unlock()

	s.eventBus.Publish(ctx, eventbus.EventUpgradeBatchRollback, map[string]interface{}{
		"batch_id":         batchID,
		"reason":           reason,
		"rolled_back_count": rolledBack,
	}, "firmware_ota")

	return nil
}

func (s *firmwareOTAServiceImpl) GetDeviceUpgrade(ctx context.Context, upgradeID string) (*DeviceUpgrade, error) {
	var upgrade DeviceUpgrade
	if err := s.db.Where("upgrade_id = ?", upgradeID).First(&upgrade).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("upgrade not found")
		}
		return nil, err
	}
	return &upgrade, nil
}

func (s *firmwareOTAServiceImpl) ListDeviceUpgrades(ctx context.Context, deviceID string, batchID string, offset, limit int) ([]DeviceUpgrade, int64, error) {
	var upgrades []DeviceUpgrade
	var total int64

	query := s.db.Model(&DeviceUpgrade{})
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}
	if batchID != "" {
		query = query.Where("batch_id = ?", batchID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&upgrades).Error; err != nil {
		return nil, 0, err
	}

	return upgrades, total, nil
}

func (s *firmwareOTAServiceImpl) UpdateDeviceUpgradeStatus(ctx context.Context, upgradeID string, phase UpgradePhase, progress int, errDetail string) error {
	updates := map[string]interface{}{
		"phase":    phase,
		"progress": progress,
	}

	if errDetail != "" {
		updates["error_detail"] = errDetail
		updates["status"] = UpgradeStatusFailed
	} else if phase == UpgradePhaseComplete {
		updates["status"] = UpgradeStatusSuccess
	} else if phase != UpgradePhasePending {
		updates["status"] = UpgradeStatusInProgress
	}

	result := s.db.Model(&DeviceUpgrade{}).Where("upgrade_id = ?", upgradeID).Updates(updates)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("upgrade not found")
	}
	return nil
}

func (s *firmwareOTAServiceImpl) RetryDeviceUpgrade(ctx context.Context, upgradeID string) error {
	var upgrade DeviceUpgrade
	if err := s.db.Where("upgrade_id = ?", upgradeID).First(&upgrade).Error; err != nil {
		return errors.New("upgrade not found")
	}

	if upgrade.RetryCount >= upgrade.MaxRetries {
		return errors.New("max retries exceeded")
	}

	upgrade.RetryCount++
	upgrade.Status = UpgradeStatusQueued
	upgrade.Phase = UpgradePhasePending
	upgrade.Progress = 0
	upgrade.ErrorDetail = ""
	s.db.Save(&upgrade)

	s.upgradeQueue <- &upgrade

	return nil
}

func (s *firmwareOTAServiceImpl) CreateUpgradePolicy(ctx context.Context, policy *UpgradePolicy) (*UpgradePolicy, error) {
	policy.PolicyID = utils.GenerateID("pol")

	if err := s.db.Create(policy).Error; err != nil {
		return nil, fmt.Errorf("failed to create policy: %w", err)
	}

	return policy, nil
}

func (s *firmwareOTAServiceImpl) ListUpgradePolicies(ctx context.Context, offset, limit int) ([]UpgradePolicy, int64, error) {
	var policies []UpgradePolicy
	var total int64

	if err := s.db.Model(&UpgradePolicy{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := s.db.Offset(offset).Limit(limit).Find(&policies).Error; err != nil {
		return nil, 0, err
	}

	return policies, total, nil
}

func (s *firmwareOTAServiceImpl) StartOTAManager(ctx context.Context) {
	logger.Info("Starting OTA manager")

	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case upgrade := <-s.upgradeQueue:
				logger.Info("Retry upgrade",
					zap.String("upgrade_id", upgrade.UpgradeID),
					zap.Int("retry", upgrade.RetryCount),
				)
			}
		}
	}()
}
