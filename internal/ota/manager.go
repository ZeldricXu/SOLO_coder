package ota

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/config"
	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/edgeplatform/session306/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type DiffGenerator interface {
	GenerateDiff(ctx context.Context, oldVersion, newVersion *model.Firmware) (int64, string, error)
}

type SimpleDiffGenerator struct {
	logger *zap.Logger
}

func (g *SimpleDiffGenerator) GenerateDiff(ctx context.Context, oldVersion, newVersion *model.Firmware) (int64, string, error) {
	g.logger.Debug("Generating diff",
		zap.String("from", oldVersion.Version),
		zap.String("to", newVersion.Version),
	)
	diffSize := newVersion.SizeBytes / 2
	return diffSize, fmt.Sprintf("%s.diff", newVersion.DownloadURL), nil
}

type OTAManager struct {
	da             *data.DataAccess
	configManager  *config.ConfigManager
	eventBus       events.EventBus
	logger         *zap.Logger
	diffGenerator  DiffGenerator
	upgradeQueue   chan *model.DeviceUpgrade
	workerCount    int
	mu             sync.RWMutex
	activeJobs     map[string]*model.OTAJob
}

func NewOTAManager(da *data.DataAccess, cm *config.ConfigManager, eb events.EventBus, log *zap.Logger, workerCount int) *OTAManager {
	if workerCount <= 0 {
		workerCount = 5
	}
	return &OTAManager{
		da:            da,
		configManager: cm,
		eventBus:      eb,
		logger:        log,
		diffGenerator: &SimpleDiffGenerator{logger: log},
		upgradeQueue:  make(chan *model.DeviceUpgrade, 1000),
		workerCount:   workerCount,
		activeJobs:    make(map[string]*model.OTAJob),
	}
}

func (m *OTAManager) Start(ctx context.Context) error {
	for i := 0; i < m.workerCount; i++ {
		go m.worker(ctx, i)
	}

	go m.monitorJobs(ctx)

	m.logger.Info("OTA manager started", zap.Int("workers", m.workerCount))
	return nil
}

func (m *OTAManager) RegisterFirmware(ctx context.Context, firmware *model.Firmware) (*model.Firmware, error) {
	firmware.FirmwareID = utils.GenerateID("fw")
	firmware.CreatedAt = utils.NowUTC()
	firmware.UpdatedAt = utils.NowUTC()

	if firmware.PreviousVersion != "" {
		var prevFW model.Firmware
		if err := m.da.DB().WithContext(ctx).
			Where("version = ? AND device_type = ?", firmware.PreviousVersion, firmware.DeviceType).
			First(&prevFW).Error; err == nil {
			diffSize, diffURL, err := m.diffGenerator.GenerateDiff(ctx, &prevFW, firmware)
			if err == nil {
				firmware.IsDelta = true
				firmware.DiffSizeBytes = diffSize
				firmware.DiffURL = diffURL
				firmware.DiffChecksum = fmt.Sprintf("diff_%s", firmware.Checksum)
			}
		}
	}

	if err := m.da.DB().WithContext(ctx).Create(firmware).Error; err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to register firmware")
	}

	m.logger.Info("Firmware registered",
		zap.String("firmware_id", firmware.FirmwareID),
		zap.String("version", firmware.Version),
		zap.String("device_type", firmware.DeviceType),
	)
	return firmware, nil
}

func (m *OTAManager) GetFirmware(ctx context.Context, firmwareID string) (*model.Firmware, error) {
	var firmware model.Firmware
	err := m.da.DB().WithContext(ctx).Where("firmware_id = ?", firmwareID).First(&firmware).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("firmware not found")
	}
	return &firmware, err
}

func (m *OTAManager) ListFirmwares(ctx context.Context, deviceType string, offset, limit int) ([]model.Firmware, int64, error) {
	var firmwares []model.Firmware
	var total int64

	query := m.da.DB().WithContext(ctx).Model(&model.Firmware{})
	if deviceType != "" {
		query = query.Where("device_type = ?", deviceType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("created_at DESC").Find(&firmwares).Error
	return firmwares, total, err
}

func (m *OTAManager) CreateOTAJob(ctx context.Context, req *model.OTAJobCreateRequest) (*model.OTAJob, error) {
	firmware, err := m.GetFirmware(ctx, req.FirmwareID)
	if err != nil {
		return nil, err
	}

	deviceIDs, err := m.resolveDeviceIDs(ctx, req.DeviceIDs, req.DeviceFilters, firmware.DeviceType)
	if err != nil {
		return nil, err
	}

	if len(deviceIDs) == 0 {
		return nil, errors.NewValidationError("no devices matched for OTA job")
	}

	job := &model.OTAJob{
		JobID:            utils.GenerateID("ota"),
		FirmwareID:       req.FirmwareID,
		Name:             req.Name,
		Description:      req.Description,
		Status:           model.OTAStatusPending,
		DeviceIDs:        deviceIDs,
		DeviceFilters:    req.DeviceFilters,
		TotalDevices:     len(deviceIDs),
		SuccessCount:     0,
		FailedCount:      0,
		RolledBackCount:  0,
		CurrentBatch:     0,
		TotalBatches:     req.TotalBatches,
		BatchSize:        req.BatchSize,
		AutoRollback:     req.AutoRollback,
		FailureThreshold: req.FailureThreshold,
		ForceUpdate:      req.ForceUpdate,
		StartAt:          req.StartAt,
		CreatedAt:        utils.NowUTC(),
		UpdatedAt:        utils.NowUTC(),
	}

	if job.TotalBatches <= 0 {
		job.TotalBatches = 1
	}
	if job.BatchSize <= 0 {
		job.BatchSize = 100
	}
	if job.FailureThreshold <= 0 {
		job.FailureThreshold = 0.1
	}

	if err := m.da.WithTransaction(ctx, func(tx *gorm.DB) error {
		if err := tx.Create(job).Error; err != nil {
			return err
		}

		for _, deviceID := range deviceIDs {
			var device model.Device
			if err := tx.Where("device_id = ?", deviceID).First(&device).Error; err != nil {
				continue
			}

			upgrade := &model.DeviceUpgrade{
				UpgradeID:   utils.GenerateID("upg"),
				JobID:       job.JobID,
				DeviceID:    deviceID,
				Status:      model.DeviceUpgradePending,
				FromVersion: device.FirmwareVersion,
				ToVersion:   firmware.Version,
				IsDelta:     firmware.IsDelta,
				CreatedAt:   utils.NowUTC(),
				UpdatedAt:   utils.NowUTC(),
			}
			if err := tx.Create(upgrade).Error; err != nil {
				return err
			}
		}

		return nil
	}); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to create OTA job")
	}

	m.mu.Lock()
	m.activeJobs[job.JobID] = job
	m.mu.Unlock()

	m.logger.Info("OTA job created",
		zap.String("job_id", job.JobID),
		zap.String("firmware_id", req.FirmwareID),
		zap.Int("total_devices", len(deviceIDs)),
	)

	if req.StartAt == nil || req.StartAt.Before(utils.NowUTC()) {
		go m.startJob(ctx, job)
	}

	return job, nil
}

func (m *OTAManager) resolveDeviceIDs(ctx context.Context, deviceIDs []string, filters map[string]string, deviceType string) ([]string, error) {
	if len(deviceIDs) > 0 {
		return deviceIDs, nil
	}

	query := m.da.DB().WithContext(ctx).Model(&model.Device{}).
		Where("status IN (?, ?)", model.DeviceStatusActive, model.DeviceStatusOnline)

	if deviceType != "" {
		query = query.Where("type = ?", deviceType)
	}

	for k, v := range filters {
		query = query.Where("tags->>? = ?", k, v)
	}

	var ids []string
	err := query.Pluck("device_id", &ids).Error
	return ids, err
}

func (m *OTAManager) GetOTAJob(ctx context.Context, jobID string) (*model.OTAJob, error) {
	var job model.OTAJob
	err := m.da.DB().WithContext(ctx).Where("job_id = ?", jobID).First(&job).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("OTA job not found")
	}
	return &job, err
}

func (m *OTAManager) ListOTAJobs(ctx context.Context, status model.OTAStatus, offset, limit int) ([]model.OTAJob, int64, error) {
	var jobs []model.OTAJob
	var total int64

	query := m.da.DB().WithContext(ctx).Model(&model.OTAJob{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("created_at DESC").Find(&jobs).Error
	return jobs, total, err
}

func (m *OTAManager) startJob(ctx context.Context, job *model.OTAJob) {
	now := utils.NowUTC()
	m.da.DB().WithContext(ctx).Model(job).
		Updates(map[string]interface{}{
			"status":     model.OTAStatusRollingOut,
			"updated_at": now,
		})

	job.Status = model.OTAStatusRollingOut

	event := events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      events.EventOTARollout,
		Source:    "ota_manager",
		Timestamp: now,
		TraceID:   ctx.Value("trace_id").(string),
		Payload: map[string]interface{}{
			"job_id":      job.JobID,
			"firmware_id": job.FirmwareID,
		},
	}
	_ = m.eventBus.Publish(ctx, event)

	m.processNextBatch(ctx, job)
}

func (m *OTAManager) processNextBatch(ctx context.Context, job *model.OTAJob) {
	if job.CurrentBatch >= job.TotalBatches {
		m.completeJob(ctx, job)
		return
	}

	job.CurrentBatch++

	var upgrades []model.DeviceUpgrade
	start := (job.CurrentBatch - 1) * job.BatchSize
	err := m.da.DB().WithContext(ctx).
		Where("job_id = ? AND status = ?", job.JobID, model.DeviceUpgradePending).
		Order("created_at ASC").
		Limit(job.BatchSize).
		Offset(start).
		Find(&upgrades).Error
	if err != nil {
		m.logger.Error("Failed to fetch batch upgrades", zap.Error(err))
		return
	}

	if len(upgrades) == 0 {
		m.completeJob(ctx, job)
		return
	}

	m.logger.Info("Processing OTA batch",
		zap.String("job_id", job.JobID),
		zap.Int("batch", job.CurrentBatch),
		zap.Int("batch_size", len(upgrades)),
	)

	for i := range upgrades {
		select {
		case m.upgradeQueue <- &upgrades[i]:
		default:
			m.logger.Warn("OTA upgrade queue full",
				zap.String("upgrade_id", upgrades[i].UpgradeID),
			)
		}
	}

	m.da.DB().WithContext(ctx).Model(job).
		Updates(map[string]interface{}{
			"current_batch": job.CurrentBatch,
			"updated_at":    utils.NowUTC(),
		})
}

func (m *OTAManager) worker(ctx context.Context, workerID int) {
	m.logger.Debug("OTA worker started", zap.Int("worker_id", workerID))

	for {
		select {
		case <-ctx.Done():
			m.logger.Debug("OTA worker stopped", zap.Int("worker_id", workerID))
			return
		case upgrade := <-m.upgradeQueue:
			m.processUpgrade(ctx, upgrade)
		}
	}
}

func (m *OTAManager) processUpgrade(ctx context.Context, upgrade *model.DeviceUpgrade) {
	m.da.DB().WithContext(ctx).Model(upgrade).
		Updates(map[string]interface{}{
			"status":     model.DeviceUpgradeDownloading,
			"updated_at": utils.NowUTC(),
		})

	downloadTime := time.Duration(500) * time.Millisecond
	time.Sleep(downloadTime)

	m.da.DB().WithContext(ctx).Model(upgrade).
		Updates(map[string]interface{}{
			"download_progress": 1.0,
			"downloaded_at":     utils.NowUTC(),
			"status":            model.DeviceUpgradeInstalling,
			"updated_at":        utils.NowUTC(),
		})

	installTime := time.Duration(300) * time.Millisecond
	time.Sleep(installTime)

	success := true
	var errMsg *string

	if success {
		now := utils.NowUTC()
		m.da.DB().WithContext(ctx).Model(upgrade).
			Updates(map[string]interface{}{
				"status":           model.DeviceUpgradeSuccess,
				"install_progress": 1.0,
				"installed_at":     now,
				"updated_at":       now,
			})

		m.da.DB().WithContext(ctx).Model(&model.Device{}).
			Where("device_id = ?", upgrade.DeviceID).
			Update("firmware_version", upgrade.ToVersion)

		m.mu.Lock()
		if job, ok := m.activeJobs[upgrade.JobID]; ok {
			job.SuccessCount++
		}
		m.mu.Unlock()
	} else {
		errorStr := "installation failed"
		errMsg = &errorStr
		now := utils.NowUTC()
		m.da.DB().WithContext(ctx).Model(upgrade).
			Updates(map[string]interface{}{
				"status":        model.DeviceUpgradeFailed,
				"error_detail":  errorStr,
				"updated_at":    now,
			})

		m.mu.Lock()
		if job, ok := m.activeJobs[upgrade.JobID]; ok {
			job.FailedCount++
		}
		m.mu.Unlock()

		if errMsg != nil {
			m.rollbackUpgrade(ctx, upgrade)
		}
	}

	m.checkBatchCompletion(ctx, upgrade.JobID)

	m.logger.Debug("Device upgrade processed",
		zap.String("upgrade_id", upgrade.UpgradeID),
		zap.String("device_id", upgrade.DeviceID),
		zap.Bool("success", success),
	)
}

func (m *OTAManager) rollbackUpgrade(ctx context.Context, upgrade *model.DeviceUpgrade) {
	job, err := m.GetOTAJob(ctx, upgrade.JobID)
	if err != nil || !job.AutoRollback {
		return
	}

	m.da.DB().WithContext(ctx).Model(upgrade).
		Updates(map[string]interface{}{
			"status":        model.DeviceUpgradeRolledBack,
			"rolled_back_at": utils.NowUTC(),
			"updated_at":    utils.NowUTC(),
		})

	m.mu.Lock()
	if j, ok := m.activeJobs[upgrade.JobID]; ok {
		j.RolledBackCount++
	}
	m.mu.Unlock()

	m.logger.Info("Device upgrade rolled back",
		zap.String("upgrade_id", upgrade.UpgradeID),
		zap.String("device_id", upgrade.DeviceID),
	)
}

func (m *OTAManager) checkBatchCompletion(ctx context.Context, jobID string) {
	m.mu.RLock()
	job, ok := m.activeJobs[jobID]
	m.mu.RUnlock()

	if !ok {
		return
	}

	total := job.SuccessCount + job.FailedCount + job.RolledBackCount
	processed := total
	batchEnd := job.CurrentBatch * job.BatchSize

	if processed >= batchEnd || processed >= job.TotalDevices {
		if job.FailureThreshold > 0 && job.TotalDevices > 0 {
			failureRate := float64(job.FailedCount) / float64(job.TotalDevices)
			if failureRate > job.FailureThreshold {
				m.pauseJob(ctx, job)
				return
			}
		}

		if processed < job.TotalDevices {
			go m.processNextBatch(ctx, job)
		} else {
			m.completeJob(ctx, job)
		}
	}
}

func (m *OTAManager) pauseJob(ctx context.Context, job *model.OTAJob) {
	now := utils.NowUTC()
	m.da.DB().WithContext(ctx).Model(job).
		Updates(map[string]interface{}{
			"status":     model.OTAStatusPaused,
			"updated_at": now,
		})

	event := events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      events.EventOTAFailed,
		Source:    "ota_manager",
		Timestamp: now,
		TraceID:   ctx.Value("trace_id").(string),
		Payload: map[string]interface{}{
			"job_id":        job.JobID,
			"failed_count":  job.FailedCount,
			"total_devices": job.TotalDevices,
		},
	}
	_ = m.eventBus.Publish(ctx, event)

	m.logger.Warn("OTA job paused due to high failure rate",
		zap.String("job_id", job.JobID),
		zap.Int("failed_count", job.FailedCount),
	)
}

func (m *OTAManager) completeJob(ctx context.Context, job *model.OTAJob) {
	now := utils.NowUTC()
	status := model.OTAStatusCompleted
	if job.FailedCount > 0 && job.FailedCount == job.TotalDevices {
		status = model.OTAStatusFailed
	}

	m.da.DB().WithContext(ctx).Model(job).
		Updates(map[string]interface{}{
			"status":       status,
			"success_count": job.SuccessCount,
			"failed_count":  job.FailedCount,
			"rolled_back_count": job.RolledBackCount,
			"completed_at": now,
			"updated_at":   now,
		})

	m.mu.Lock()
	delete(m.activeJobs, job.JobID)
	m.mu.Unlock()

	eventType := events.EventOTACompleted
	if status == model.OTAStatusFailed {
		eventType = events.EventOTAFailed
	}

	event := events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      eventType,
		Source:    "ota_manager",
		Timestamp: now,
		TraceID:   ctx.Value("trace_id").(string),
		Payload: map[string]interface{}{
			"job_id":           job.JobID,
			"success_count":    job.SuccessCount,
			"failed_count":     job.FailedCount,
			"rolled_back_count": job.RolledBackCount,
		},
	}
	_ = m.eventBus.Publish(ctx, event)

	m.logger.Info("OTA job completed",
		zap.String("job_id", job.JobID),
		zap.String("status", string(status)),
		zap.Int("success_count", job.SuccessCount),
		zap.Int("failed_count", job.FailedCount),
	)
}

func (m *OTAManager) GetDeviceUpgrades(ctx context.Context, jobID string, deviceID string, offset, limit int) ([]model.DeviceUpgrade, int64, error) {
	var upgrades []model.DeviceUpgrade
	var total int64

	query := m.da.DB().WithContext(ctx).Model(&model.DeviceUpgrade{})
	if jobID != "" {
		query = query.Where("job_id = ?", jobID)
	}
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("created_at DESC").Find(&upgrades).Error
	return upgrades, total, err
}

func (m *OTAManager) monitorJobs(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			m.checkStalledJobs(ctx)
		}
	}
}

func (m *OTAManager) checkStalledJobs(ctx context.Context) {
	var jobs []model.OTAJob
	m.da.DB().WithContext(ctx).
		Where("status IN (?, ?)", model.OTAStatusRollingOut, model.OTAStatusPaused).
		Find(&jobs)

	for _, job := range jobs {
		m.logger.Debug("Monitoring OTA job",
			zap.String("job_id", job.JobID),
			zap.String("status", string(job.Status)),
		)
	}
}

func (m *OTAManager) Stop() {
	close(m.upgradeQueue)
	m.logger.Info("OTA manager stopped")
}
