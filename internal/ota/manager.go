package ota

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/common/eventbus"
	"github.com/edgevision/edgevision/internal/common/logger"
	"github.com/edgevision/edgevision/internal/common/utils"
	"go.uber.org/zap"
)

type FirmwareInfo struct {
	ID          string    `json:"id"`
	Version     string    `json:"version"`
	Model       string    `json:"model"`
	Size        int64     `json:"size"`
	Checksum    string    `json:"checksum"`
	ReleasedAt  time.Time `json:"released_at"`
	Description string    `json:"description"`
	MinVersion  string    `json:"min_version"`
}

type UpgradeStatus string

const (
	UpgradeStatusPending   UpgradeStatus = "pending"
	UpgradeStatusDownloading UpgradeStatus = "downloading"
	UpgradeStatusUpgrading UpgradeStatus = "upgrading"
	UpgradeStatusVerifying UpgradeStatus = "verifying"
	UpgradeStatusSuccess   UpgradeStatus = "success"
	UpgradeStatusFailed    UpgradeStatus = "failed"
	UpgradeStatusRollback  UpgradeStatus = "rollback"
	UpgradeStatusCancelled UpgradeStatus = "cancelled"
)

type UpgradeJob struct {
	ID            string                 `json:"id"`
	FirmwareID    string                 `json:"firmware_id"`
	DeviceID      string                 `json:"device_id"`
	Status        UpgradeStatus          `json:"status"`
	Progress      int                    `json:"progress"`
	CurrentVersion string                `json:"current_version"`
	TargetVersion  string                `json:"target_version"`
	CreatedAt     time.Time              `json:"created_at"`
	StartedAt     *time.Time             `json:"started_at"`
	CompletedAt   *time.Time             `json:"completed_at"`
	ErrorDetail   string                 `json:"error_detail,omitempty"`
	RollbackInfo  map[string]interface{} `json:"rollback_info,omitempty"`
}

type UpgradeStrategy struct {
	Type            string            `json:"type"`
	BatchPercentage int               `json:"batch_percentage"`
	MaxConcurrent   int               `json:"max_concurrent"`
	AutoRollback    bool              `json:"auto_rollback"`
	GrayScale       map[string]int    `json:"gray_scale"`
}

type RollbackInfo struct {
	PreviousVersion string
	BackupImage     string
	Timestamp       time.Time
}

type Manager struct {
	firmwares    map[string]*FirmwareInfo
	jobs         map[string]*UpgradeJob
	deviceJobs   map[string]string
	strategy     UpgradeStrategy
	rollbackData map[string]RollbackInfo
	mu           sync.RWMutex
	jobQueue     chan string
	workers      int
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
}

func NewManager(workers int) *Manager {
	ctx, cancel := context.WithCancel(context.Background())
	return &Manager{
		firmwares:    make(map[string]*FirmwareInfo),
		jobs:         make(map[string]*UpgradeJob),
		deviceJobs:   make(map[string]string),
		rollbackData: make(map[string]RollbackInfo),
		jobQueue:     make(chan string, 1000),
		workers:      workers,
		strategy: UpgradeStrategy{
			Type:            "batch",
			BatchPercentage: 10,
			MaxConcurrent:   5,
			AutoRollback:    true,
			GrayScale:       map[string]int{"canary": 5, "beta": 20, "stable": 100},
		},
		ctx:    ctx,
		cancel: cancel,
	}
}

func (m *Manager) UploadFirmware(version, model string, data []byte, description, minVersion string) (*FirmwareInfo, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	hash := sha256.Sum256(data)
	checksum := hex.EncodeToString(hash[:])
	firmware := &FirmwareInfo{
		ID:          utils.GenerateID("fw"),
		Version:     version,
		Model:       model,
		Size:        int64(len(data)),
		Checksum:    checksum,
		ReleasedAt:  time.Now().UTC(),
		Description: description,
		MinVersion:  minVersion,
	}
	m.firmwares[firmware.ID] = firmware
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "firmware.uploaded",
		Payload: map[string]interface{}{
			"firmware_id": firmware.ID,
			"version":     version,
			"model":       model,
		},
	})
	logger.Get().Info("Firmware uploaded",
		zap.String("firmware_id", firmware.ID),
		zap.String("version", version))
	return firmware, nil
}

func (m *Manager) GetFirmware(id string) (*FirmwareInfo, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	fw, exists := m.firmwares[id]
	return fw, exists
}

func (m *Manager) ListFirmwares() []*FirmwareInfo {
	m.mu.RLock()
	defer m.mu.RUnlock()
	fws := make([]*FirmwareInfo, 0, len(m.firmwares))
	for _, fw := range m.firmwares {
		fws = append(fws, fw)
	}
	return fws
}

func (m *Manager) DeleteFirmware(id string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, exists := m.firmwares[id]; !exists {
		return false
	}
	delete(m.firmwares, id)
	return true
}

func (m *Manager) CreateUpgradeJob(deviceID, firmwareID, currentVersion string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if existingJob, exists := m.deviceJobs[deviceID]; exists {
		if job, ok := m.jobs[existingJob]; ok {
			if job.Status == UpgradeStatusPending || job.Status == UpgradeStatusDownloading ||
				job.Status == UpgradeStatusUpgrading {
				return "", errors.New("device already has an active upgrade job")
			}
		}
	}
	firmware, exists := m.firmwares[firmwareID]
	if !exists {
		return "", errors.New("firmware not found")
	}
	job := &UpgradeJob{
		ID:             utils.GenerateID("job"),
		FirmwareID:     firmwareID,
		DeviceID:       deviceID,
		Status:         UpgradeStatusPending,
		Progress:       0,
		CurrentVersion: currentVersion,
		TargetVersion:  firmware.Version,
		CreatedAt:      time.Now().UTC(),
	}
	m.jobs[job.ID] = job
	m.deviceJobs[deviceID] = job.ID
	m.rollbackData[deviceID] = RollbackInfo{
		PreviousVersion: currentVersion,
		Timestamp:       time.Now().UTC(),
	}
	select {
	case m.jobQueue <- job.ID:
	default:
		return "", errors.New("job queue is full")
	}
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "upgrade.job.created",
		Payload: map[string]interface{}{
			"job_id":    job.ID,
			"device_id": deviceID,
			"version":   firmware.Version,
		},
	})
	logger.Get().Info("Upgrade job created",
		zap.String("job_id", job.ID),
		zap.String("device_id", deviceID))
	return job.ID, nil
}

func (m *Manager) GetJob(id string) (*UpgradeJob, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	job, exists := m.jobs[id]
	return job, exists
}

func (m *Manager) ListJobs() []*UpgradeJob {
	m.mu.RLock()
	defer m.mu.RUnlock()
	jobs := make([]*UpgradeJob, 0, len(m.jobs))
	for _, job := range m.jobs {
		jobs = append(jobs, job)
	}
	return jobs
}

func (m *Manager) CancelJob(id string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	job, exists := m.jobs[id]
	if !exists {
		return false
	}
	if job.Status == UpgradeStatusPending || job.Status == UpgradeStatusDownloading {
		job.Status = UpgradeStatusCancelled
		return true
	}
	return false
}

func (m *Manager) Start() {
	for i := 0; i < m.workers; i++ {
		m.wg.Add(1)
		go m.worker(i)
	}
	logger.Get().Info("OTA manager started", zap.Int("workers", m.workers))
}

func (m *Manager) worker(id int) {
	defer m.wg.Done()
	for {
		select {
		case jobID := <-m.jobQueue:
			m.processJob(jobID)
		case <-m.ctx.Done():
			return
		}
	}
}

func (m *Manager) processJob(jobID string) {
	m.mu.Lock()
	job, exists := m.jobs[jobID]
	if !exists {
		m.mu.Unlock()
		return
	}
	job.Status = UpgradeStatusDownloading
	job.StartedAt = utils.NowPtr()
	m.mu.Unlock()
	if err := m.simulateDownload(job); err != nil {
		m.handleFailure(job, err)
		return
	}
	m.updateProgress(job, 30)
	job.Status = UpgradeStatusUpgrading
	if err := m.simulateUpgrade(job); err != nil {
		m.handleFailure(job, err)
		return
	}
	m.updateProgress(job, 70)
	job.Status = UpgradeStatusVerifying
	if err := m.simulateVerify(job); err != nil {
		m.handleFailure(job, err)
		return
	}
	m.updateProgress(job, 100)
	job.Status = UpgradeStatusSuccess
	job.CompletedAt = utils.NowPtr()
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "upgrade.job.success",
		Payload: map[string]interface{}{
			"job_id":    job.ID,
			"device_id": job.DeviceID,
		},
	})
	logger.Get().Info("Upgrade job completed successfully",
		zap.String("job_id", job.ID),
		zap.String("device_id", job.DeviceID))
}

func (m *Manager) simulateDownload(job *UpgradeJob) error {
	time.Sleep(200 * time.Millisecond)
	return nil
}

func (m *Manager) simulateUpgrade(job *UpgradeJob) error {
	time.Sleep(300 * time.Millisecond)
	return nil
}

func (m *Manager) simulateVerify(job *UpgradeJob) error {
	time.Sleep(100 * time.Millisecond)
	return nil
}

func (m *Manager) updateProgress(job *UpgradeJob, progress int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	job.Progress = progress
}

func (m *Manager) handleFailure(job *UpgradeJob, err error) {
	m.mu.Lock()
	job.Status = UpgradeStatusFailed
	job.ErrorDetail = err.Error()
	job.CompletedAt = utils.NowPtr()
	if m.strategy.AutoRollback {
		job.Status = UpgradeStatusRollback
	}
	m.mu.Unlock()
	if m.strategy.AutoRollback {
		m.performRollback(job)
	}
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "upgrade.job.failed",
		Payload: map[string]interface{}{
			"job_id":    job.ID,
			"device_id": job.DeviceID,
			"error":     err.Error(),
		},
	})
	logger.Get().Error("Upgrade job failed",
		zap.String("job_id", job.ID),
		zap.String("device_id", job.DeviceID),
		zap.Error(err))
}

func (m *Manager) performRollback(job *UpgradeJob) {
	m.mu.Lock()
	rollbackInfo, exists := m.rollbackData[job.DeviceID]
	m.mu.Unlock()
	if !exists {
		logger.Get().Warn("No rollback data found", zap.String("device_id", job.DeviceID))
		return
	}
	logger.Get().Info("Performing rollback",
		zap.String("device_id", job.DeviceID),
		zap.String("previous_version", rollbackInfo.PreviousVersion))
	time.Sleep(150 * time.Millisecond)
	m.mu.Lock()
	job.RollbackInfo = map[string]interface{}{
		"previous_version": rollbackInfo.PreviousVersion,
		"rolled_back":      true,
	}
	job.Progress = 100
	m.mu.Unlock()
}

func (m *Manager) GetStrategy() UpgradeStrategy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.strategy
}

func (m *Manager) UpdateStrategy(strategy UpgradeStrategy) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.strategy = strategy
	logger.Get().Info("Upgrade strategy updated")
}

func (m *Manager) Stop() {
	m.cancel()
	close(m.jobQueue)
	m.wg.Wait()
	logger.Get().Info("OTA manager stopped")
}
