package ota

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
	"github.com/edgevision/edgevision/internal/domain/ota"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/internal/infrastructure/mqtt"
	"github.com/edgevision/edgevision/pkg/utils"
	"go.uber.org/zap"
)

type Service struct {
	firmwareRepo       ota.FirmwareRepository
	deltaPackageRepo   ota.DeltaPackageRepository
	upgradeTaskRepo    ota.UpgradeTaskRepository
	deviceUpgradeRepo  ota.DeviceUpgradeRepository
	eventPublisher     ota.EventPublisher
	configManager      ota.ConfigManager
}

func NewService(
	firmwareRepo ota.FirmwareRepository,
	deltaPackageRepo ota.DeltaPackageRepository,
	upgradeTaskRepo ota.UpgradeTaskRepository,
	deviceUpgradeRepo ota.DeviceUpgradeRepository,
	eventPublisher ota.EventPublisher,
	configManager ota.ConfigManager,
) *Service {
	return &Service{
		firmwareRepo:      firmwareRepo,
		deltaPackageRepo:  deltaPackageRepo,
		upgradeTaskRepo:   upgradeTaskRepo,
		deviceUpgradeRepo: deviceUpgradeRepo,
		eventPublisher:    eventPublisher,
		configManager:     configManager,
	}
}

func (s *Service) GetConfigManager() ota.ConfigManager {
	return s.configManager
}

func (s *Service) CreateFirmware(ctx context.Context, req *ota.CreateFirmwareRequest) (*model.Firmware, error) {
	existing, err := s.firmwareRepo.GetByVersionAndDeviceType(ctx, req.Version, req.DeviceType)
	if err == nil && existing != nil {
		return nil, errors.New("firmware with this version already exists for this device type")
	}

	firmware := &model.Firmware{
		ID:            utils.GenerateID("fw"),
		Name:          req.Name,
		Version:       req.Version,
		DeviceType:    req.DeviceType,
		HardwareModel: req.HardwareModel,
		FileSize:      req.FileSize,
		FileURL:       req.FileURL,
		Checksum:      req.Checksum,
		Signature:     req.Signature,
		ReleaseNotes:  req.ReleaseNotes,
		Metadata:      req.Metadata,
		IsActive:      true,
		CreatedAt:     utils.Now(),
		UpdatedAt:     utils.Now(),
	}

	if err := s.firmwareRepo.Create(ctx, firmware); err != nil {
		logger.Get().Error("failed to create firmware", zap.Error(err))
		return nil, err
	}

	return firmware, nil
}

func (s *Service) GetFirmware(ctx context.Context, id string) (*model.Firmware, error) {
	return s.firmwareRepo.GetByID(ctx, id)
}

func (s *Service) ListFirmwares(ctx context.Context, deviceType string, page, pageSize int) ([]model.Firmware, int64, error) {
	return s.firmwareRepo.List(ctx, deviceType, page, pageSize)
}

func (s *Service) GenerateDeltaPackage(ctx context.Context, req *ota.GenerateDeltaPackageRequest) (*model.DeltaPackage, error) {
	fromFirmware, err := s.firmwareRepo.GetByVersionAndDeviceType(ctx, req.FromVersion, req.DeviceType)
	if err != nil {
		return nil, errors.New("from version firmware not found")
	}

	toFirmware, err := s.firmwareRepo.GetByVersionAndDeviceType(ctx, req.ToVersion, req.DeviceType)
	if err != nil {
		return nil, errors.New("to version firmware not found")
	}

	delta := &model.DeltaPackage{
		ID:              utils.GenerateID("delta"),
		FromVersion:     req.FromVersion,
		ToVersion:       req.ToVersion,
		DeviceType:      req.DeviceType,
		FileSize:        toFirmware.FileSize / 2,
		FileURL:         fmt.Sprintf("%s/delta/%s_to_%s", toFirmware.FileURL, req.FromVersion, req.ToVersion),
		Checksum:        utils.HashSHA256(req.FromVersion + req.ToVersion + req.DeviceType),
		CompressionAlgo: "gzip",
		DiffAlgo:        "bsdiff",
		Status:          "ready",
		CreatedAt:       utils.Now(),
		UpdatedAt:       utils.Now(),
	}

	if err := s.deltaPackageRepo.Create(ctx, delta); err != nil {
		return nil, err
	}

	return delta, nil
}

func (s *Service) CreateUpgradeTask(ctx context.Context, req *ota.CreateUpgradeTaskRequest) (*model.OTAUpgradeTask, error) {
	firmware, err := s.firmwareRepo.GetByID(ctx, req.FirmwareID)
	if err != nil {
		return nil, errors.New("firmware not found")
	}

	config := s.configManager.GetEffectiveConfig(ctx, req.Profile, firmware.DeviceType)

	strategyConfig := s.getStrategyConfig(config, req.Strategy)

	batchSize := config.DefaultBatchSize
	if strategyConfig != nil && strategyConfig.BatchSize > 0 {
		batchSize = strategyConfig.BatchSize
	}

	autoRollback := config.DefaultAutoRollback
	if req.AutoRollback != nil {
		autoRollback = *req.AutoRollback
	}

	failureThreshold := config.DefaultFailureThreshold
	if req.FailureThreshold != nil {
		failureThreshold = *req.FailureThreshold
	}

	task := &model.OTAUpgradeTask{
		ID:               utils.GenerateID("ota"),
		FirmwareID:       req.FirmwareID,
		Name:             req.Name,
		Description:      req.Description,
		UpgradeType:      req.UpgradeType,
		Strategy:         req.Strategy,
		Profile:          req.Profile,
		Status:           model.OTAStatusPending,
		TotalDevices:     len(req.DeviceIDs),
		Progress:         0,
		AutoRollback:     autoRollback,
		FailureThreshold: failureThreshold,
		ScheduledAt:      req.ScheduledAt,
		Parameters:       req.Parameters,
		CreatedAt:        utils.Now(),
		UpdatedAt:        utils.Now(),
	}

	for i, deviceID := range req.DeviceIDs {
		upgrade := &model.OTADeviceUpgrade{
			ID:          utils.GenerateID("odu"),
			TaskID:      task.ID,
			DeviceID:    deviceID,
			FirmwareID:  firmware.ID,
			Status:      model.OTAStatusPending,
			Phase:       "pending",
			Progress:    0,
			Priority:    len(req.DeviceIDs) - i,
			BatchNumber: (i / batchSize) + 1,
			CreatedAt:   utils.Now(),
			UpdatedAt:   utils.Now(),
		}
		if err := s.deviceUpgradeRepo.Create(ctx, upgrade); err != nil {
			return nil, err
		}
	}

	if err := s.upgradeTaskRepo.Create(ctx, task); err != nil {
		return nil, err
	}

	return task, nil
}

func (s *Service) getStrategyConfig(config *ota.OTAConfig, strategy string) *ota.StrategyConfig {
	if config.Strategies == nil {
		return nil
	}
	if cfg, ok := config.Strategies[strategy]; ok {
		return &cfg
	}
	return nil
}

func (s *Service) StartUpgradeTask(ctx context.Context, taskID string) (*model.OTAUpgradeTask, error) {
	task, err := s.upgradeTaskRepo.GetByID(ctx, taskID)
	if err != nil {
		return nil, errors.New("task not found")
	}

	if task.Status != model.OTAStatusPending {
		return nil, errors.New("task already started or completed")
	}

	task.Status = model.OTAStatusRunning
	task.StartedAt = utils.Now()
	if err := s.upgradeTaskRepo.Update(ctx, task); err != nil {
		return nil, err
	}

	s.eventPublisher.PublishUpgradeStarted(ctx, taskID)

	config := s.configManager.GetEffectiveConfig(ctx, task.Profile, "")
	go s.processUpgradeTask(context.Background(), taskID, config)

	return task, nil
}

func (s *Service) processUpgradeTask(ctx context.Context, taskID string, config *ota.OTAConfig) {
	task, err := s.upgradeTaskRepo.GetByID(ctx, taskID)
	if err != nil {
		return
	}

	strategyConfig := s.getStrategyConfig(config, task.Strategy)
	batchInterval := config.BatchInterval
	if strategyConfig != nil && strategyConfig.BatchInterval > 0 {
		batchInterval = strategyConfig.BatchInterval
	}

	configCh := s.configManager.Watch(ctx, task.Profile)

	batchNum := 1
	for {
		select {
		case newConfig := <-configCh:
			if newConfig != nil {
				config = newConfig
				strategyConfig = s.getStrategyConfig(config, task.Strategy)
				if strategyConfig != nil && strategyConfig.BatchInterval > 0 {
					batchInterval = strategyConfig.BatchInterval
				}
				logger.Get().Info("OTA config updated during upgrade", zap.String("task_id", taskID))
			}
		default:
		}

		upgrades, err := s.deviceUpgradeRepo.GetPendingByBatch(ctx, taskID, batchNum)
		if err != nil || len(upgrades) == 0 {
			break
		}

		failedCount := 0
		successCount := 0

		for _, upgrade := range upgrades {
			go s.processDeviceUpgrade(context.Background(), taskID, upgrade.ID, task.AutoRollback, task.FailureThreshold, config)
		}

		if batchInterval > 0 {
			time.Sleep(batchInterval)
		}

		effectiveThreshold := config.DefaultFailureThreshold
		if strategyConfig != nil {
			effectiveThreshold = strategyConfig.FailureThreshold
		}

		if float64(failedCount)/float64(len(upgrades)) > effectiveThreshold {
			autoRollback := config.DefaultAutoRollback
			if strategyConfig != nil {
				autoRollback = strategyConfig.AutoRollback
			}
			if autoRollback {
				s.rollbackBatch(ctx, taskID, batchNum)
			}
			break
		}

		batchNum++

		if successCount+failedCount >= task.TotalDevices {
			break
		}
	}

	s.finalizeTask(ctx, taskID)
}

func (s *Service) processDeviceUpgrade(ctx context.Context, taskID, upgradeID string, autoRollback bool, threshold float64, config *ota.OTAConfig) {
	if err := s.deviceUpgradeRepo.UpdateStatus(ctx, upgradeID, model.OTAStatusRunning, "downloading", 0.1, nil); err != nil {
		return
	}

	downloadInterval := config.DownloadTimeout / 10
	if downloadInterval > 5*time.Second {
		downloadInterval = 5 * time.Second
	}
	time.Sleep(downloadInterval)
	if err := s.deviceUpgradeRepo.UpdateStatus(ctx, upgradeID, model.OTAStatusRunning, "downloading", 0.5, nil); err != nil {
		return
	}

	time.Sleep(downloadInterval)
	if err := s.deviceUpgradeRepo.UpdateStatus(ctx, upgradeID, model.OTAStatusRunning, "installing", 0.8, nil); err != nil {
		return
	}

	installInterval := config.InstallTimeout / 5
	if installInterval > 3*time.Second {
		installInterval = 3 * time.Second
	}
	time.Sleep(installInterval)
	success := true
	if success {
		if err := s.deviceUpgradeRepo.UpdateStatus(ctx, upgradeID, model.OTAStatusSuccess, "completed", 1.0, nil); err != nil {
			return
		}
		s.upgradeTaskRepo.IncrementProgress(ctx, taskID, 1, 0, 0)
		s.eventPublisher.PublishDeviceUpgradeStatus(ctx, upgradeID, "", model.OTAStatusSuccess)
	} else {
		if autoRollback {
			if err := s.deviceUpgradeRepo.UpdateStatus(ctx, upgradeID, model.OTAStatusRollingBack, "rolling_back", 0, nil); err != nil {
				return
			}
			time.Sleep(2 * time.Second)
			if err := s.deviceUpgradeRepo.UpdateStatus(ctx, upgradeID, model.OTAStatusRolledBack, "rolled_back", 0, nil); err != nil {
				return
			}
			s.upgradeTaskRepo.IncrementProgress(ctx, taskID, 0, 1, 1)
		} else {
			if err := s.deviceUpgradeRepo.UpdateStatus(ctx, upgradeID, model.OTAStatusFailed, "failed", 0, nil); err != nil {
				return
			}
			s.upgradeTaskRepo.IncrementProgress(ctx, taskID, 0, 1, 0)
		}
		s.eventPublisher.PublishDeviceUpgradeStatus(ctx, upgradeID, "", model.OTAStatusFailed)
	}
}

func (s *Service) rollbackBatch(ctx context.Context, taskID string, batchNum int) {
	s.eventPublisher.PublishUpgradeFailed(ctx, taskID, fmt.Sprintf("batch %d exceeded failure threshold", batchNum))
}

func (s *Service) finalizeTask(ctx context.Context, taskID string) {
	task, err := s.upgradeTaskRepo.GetByID(ctx, taskID)
	if err != nil {
		return
	}

	task.Status = model.OTAStatusSuccess
	if task.FailedCount > 0 {
		task.Status = model.OTAStatusPartialSuccess
	}
	if task.FailedCount == task.TotalDevices {
		task.Status = model.OTAStatusFailed
	}

	task.CompletedAt = utils.Now()
	task.Progress = 1.0
	task.UpdatedAt = utils.Now()

	if err := s.upgradeTaskRepo.Update(ctx, task); err != nil {
		return
	}

	if task.Status == model.OTAStatusSuccess || task.Status == model.OTAStatusPartialSuccess {
		s.eventPublisher.PublishUpgradeCompleted(ctx, taskID)
	} else {
		s.eventPublisher.PublishUpgradeFailed(ctx, taskID, "all devices failed")
	}
}

func (s *Service) GetTask(ctx context.Context, taskID string) (*model.OTAUpgradeTask, error) {
	return s.upgradeTaskRepo.GetByID(ctx, taskID)
}

func (s *Service) ListTasks(ctx context.Context, status string, page, pageSize int) ([]model.OTAUpgradeTask, int64, error) {
	return s.upgradeTaskRepo.List(ctx, status, page, pageSize)
}

func (s *Service) GetDeviceUpgrades(ctx context.Context, taskID string, page, pageSize int) ([]model.OTADeviceUpgrade, int64, error) {
	return s.deviceUpgradeRepo.ListByTaskID(ctx, taskID, page, pageSize)
}

func (s *Service) ReportDeviceUpgradeStatus(ctx context.Context, upgradeID, status, phase string, progress float64, errorMsg *string) (*model.OTADeviceUpgrade, error) {
	if err := s.deviceUpgradeRepo.UpdateStatus(ctx, upgradeID, status, phase, progress, errorMsg); err != nil {
		return nil, err
	}

	s.eventPublisher.PublishDeviceUpgradeStatus(ctx, upgradeID, "", status)

	return s.deviceUpgradeRepo.GetByID(ctx, upgradeID)
}

type eventPublisher struct {
	mqtt  *mqtt.Client
	cache *cache.Cache
}

func NewEventPublisher(mqtt *mqtt.Client, cache *cache.Cache) *eventPublisher {
	return &eventPublisher{
		mqtt:  mqtt,
		cache: cache,
	}
}

func (p *eventPublisher) PublishUpgradeStarted(ctx context.Context, taskID string) {
	event := map[string]interface{}{
		"event":    "upgrade.started",
		"task_id":  taskID,
		"timestamp": time.Now().Unix(),
	}
	if p.mqtt != nil {
		_ = p.mqtt.Publish(ctx, fmt.Sprintf("ota/%s/events", taskID), event)
	}
	if p.cache != nil {
		_ = p.cache.Publish(ctx, fmt.Sprintf("ota:%s:events", taskID), utils.ToJSON(event))
	}
}

func (p *eventPublisher) PublishUpgradeCompleted(ctx context.Context, taskID string) {
	event := map[string]interface{}{
		"event":    "upgrade.completed",
		"task_id":  taskID,
		"timestamp": time.Now().Unix(),
	}
	if p.mqtt != nil {
		_ = p.mqtt.Publish(ctx, fmt.Sprintf("ota/%s/events", taskID), event)
	}
	if p.cache != nil {
		_ = p.cache.Publish(ctx, fmt.Sprintf("ota:%s:events", taskID), utils.ToJSON(event))
	}
}

func (p *eventPublisher) PublishUpgradeFailed(ctx context.Context, taskID string, reason string) {
	event := map[string]interface{}{
		"event":    "upgrade.failed",
		"task_id":  taskID,
		"reason":   reason,
		"timestamp": time.Now().Unix(),
	}
	if p.mqtt != nil {
		_ = p.mqtt.Publish(ctx, fmt.Sprintf("ota/%s/events", taskID), event)
	}
	if p.cache != nil {
		_ = p.cache.Publish(ctx, fmt.Sprintf("ota:%s:events", taskID), utils.ToJSON(event))
	}
}

func (p *eventPublisher) PublishDeviceUpgradeStatus(ctx context.Context, upgradeID, deviceID, status string) {
	event := map[string]interface{}{
		"event":     "device.upgrade.status",
		"upgrade_id": upgradeID,
		"device_id":  deviceID,
		"status":     status,
		"timestamp":  time.Now().Unix(),
	}
	if p.cache != nil {
		_ = p.cache.Publish(ctx, fmt.Sprintf("ota:device:%s:events", upgradeID), utils.ToJSON(event))
	}
}
