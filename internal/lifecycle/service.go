package lifecycle

import (
	"time"

	"streamsql/internal/common/config"
	"streamsql/internal/common/logger"
)

type LifecycleService struct {
	manager        *LifecycleManager
	storage        *InMemoryDataStorage
	archiveStorage *FileArchiveStorage
	config         config.LifecycleConfig
}

func NewLifecycleService(cfg config.LifecycleConfig) (*LifecycleService, error) {
	storage := NewInMemoryDataStorage()
	archiveStorage, err := NewFileArchiveStorage(cfg.ArchivePath)
	if err != nil {
		return nil, err
	}

	manager := NewLifecycleManager(storage, archiveStorage)

	svc := &LifecycleService{
		manager:        manager,
		storage:        storage,
		archiveStorage: archiveStorage,
		config:         cfg,
	}

	svc.initSampleData()
	svc.initDefaultPolicies()

	logger.Sugar().Info("Lifecycle service initialized")
	return svc, nil
}

func (s *LifecycleService) initSampleData() {
	now := time.Now()

	sampleRecords := []struct {
		record    DataRecord
		tier      DataTier
		timestamp time.Time
	}{
		{
			record:    DataRecord{ID: "rec_001", TableName: "sensor_data", SizeBytes: 1024},
			tier:      TierHot,
			timestamp: now,
		},
		{
			record:    DataRecord{ID: "rec_002", TableName: "sensor_data", SizeBytes: 2048},
			tier:      TierWarm,
			timestamp: now.AddDate(0, 0, -8),
		},
		{
			record:    DataRecord{ID: "rec_003", TableName: "sensor_data", SizeBytes: 4096},
			tier:      TierCold,
			timestamp: now.AddDate(0, 0, -40),
		},
		{
			record:    DataRecord{ID: "rec_004", TableName: "events", SizeBytes: 512},
			tier:      TierHot,
			timestamp: now,
		},
	}

	for _, sr := range sampleRecords {
		sr.record.Timestamp = sr.timestamp
		s.storage.AddRecord(&sr.record, sr.tier)
	}

	logger.Sugar().Info("Initialized sample lifecycle data")
}

func (s *LifecycleService) initDefaultPolicies() {
	defaultPolicies := []*LifecyclePolicy{
		{
			Name:         "Sensor Data Lifecycle",
			Description:  "Automatic tier management for sensor data",
			TableName:    "sensor_data",
			Strategy:     NewAgeBasedTierStrategy(s.config.HotThresholdDays, s.config.WarmThresholdDays, s.config.WarmThresholdDays*2),
			Enabled:      true,
			ArchiveAfter: time.Duration(s.config.WarmThresholdDays*2+30) * 24 * time.Hour,
			DeleteAfter:  time.Duration(s.config.WarmThresholdDays*2+90) * 24 * time.Hour,
			CronSchedule: s.config.CleanupCron,
		},
		{
			Name:         "Events Data Lifecycle",
			Description:  "Automatic tier management for events data",
			TableName:    "events",
			Strategy:     NewAgeBasedTierStrategy(3, 7, 30),
			Enabled:      true,
			ArchiveAfter: time.Duration(60) * 24 * time.Hour,
			DeleteAfter:  time.Duration(120) * 24 * time.Hour,
			CronSchedule: s.config.CleanupCron,
		},
	}

	for _, policy := range defaultPolicies {
		_ = s.manager.AddPolicy(policy)
	}
}

func (s *LifecycleService) AddPolicy(policy *LifecyclePolicy) error {
	return s.manager.AddPolicy(policy)
}

func (s *LifecycleService) RemovePolicy(policyID string) error {
	return s.manager.RemovePolicy(policyID)
}

func (s *LifecycleService) GetPolicy(policyID string) (*LifecyclePolicy, error) {
	return s.manager.GetPolicy(policyID)
}

func (s *LifecycleService) ListPolicies() []*LifecyclePolicy {
	return s.manager.ListPolicies()
}

func (s *LifecycleService) ExecutePolicy(policyID string) (*PolicyExecutionLog, error) {
	return s.manager.ExecutePolicy(policyID)
}

func (s *LifecycleService) ExecuteAllPolicies() []*PolicyExecutionLog {
	return s.manager.ExecuteAllPolicies()
}

func (s *LifecycleService) GetExecutionLogs(policyID string) []*PolicyExecutionLog {
	return s.manager.GetExecutionLogs(policyID)
}

func (s *LifecycleService) GetStorageStats() map[DataTier]map[string]interface{} {
	return s.manager.GetStorageStats()
}

func (s *LifecycleService) AddRecord(record *DataRecord, tier DataTier) {
	s.storage.AddRecord(record, tier)
}

func (s *LifecycleService) CleanupOldLogs() int {
	return s.manager.CleanupOldLogs()
}

func (s *LifecycleService) GetMemoryStats() map[string]interface{} {
	return s.manager.GetMemoryStats()
}

func (s *LifecycleService) SetMaxLogs(max int) {
	s.manager.SetMaxLogs(max)
}

func (s *LifecycleService) SetLogRetentionDays(days int) {
	s.manager.SetLogRetentionDays(days)
}

func (s *LifecycleService) ForceGC() {
	s.manager.ForceGC()
}

func (s *LifecycleService) Stop() {
	s.manager.Stop()
}
