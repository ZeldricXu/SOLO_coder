package lifecycle

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"streamsql/internal/common/logger"
)

type InMemoryDataStorage struct {
	tiers map[DataTier]map[string]*DataRecord
	mu    sync.RWMutex
}

func NewInMemoryDataStorage() *InMemoryDataStorage {
	return &InMemoryDataStorage{
		tiers: map[DataTier]map[string]*DataRecord{
			TierHot:     make(map[string]*DataRecord),
			TierWarm:    make(map[string]*DataRecord),
			TierCold:    make(map[string]*DataRecord),
			TierArchive: make(map[string]*DataRecord),
		},
	}
}

func (s *InMemoryDataStorage) GetRecordsByTier(tier DataTier) ([]*DataRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	records := make([]*DataRecord, 0, len(s.tiers[tier]))
	for _, r := range s.tiers[tier] {
		records = append(records, r)
	}
	return records, nil
}

func (s *InMemoryDataStorage) MoveRecord(record *DataRecord, fromTier, toTier DataTier) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, ok := s.tiers[fromTier][record.ID]; !ok {
		return fmt.Errorf("record not found in tier %s", fromTier)
	}

	delete(s.tiers[fromTier], record.ID)
	s.tiers[toTier][record.ID] = record
	return nil
}

func (s *InMemoryDataStorage) DeleteRecord(record *DataRecord, tier DataTier) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	delete(s.tiers[tier], record.ID)
	return nil
}

func (s *InMemoryDataStorage) GetRecordCount(tier DataTier) (int64, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return int64(len(s.tiers[tier])), nil
}

func (s *InMemoryDataStorage) GetTotalSize(tier DataTier) (int64, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var total int64
	for _, r := range s.tiers[tier] {
		total += r.SizeBytes
	}
	return total, nil
}

func (s *InMemoryDataStorage) AddRecord(record *DataRecord, tier DataTier) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.tiers[tier][record.ID] = record
}

type FileArchiveStorage struct {
	basePath string
}

func NewFileArchiveStorage(basePath string) (*FileArchiveStorage, error) {
	if err := os.MkdirAll(basePath, 0755); err != nil {
		return nil, err
	}
	return &FileArchiveStorage{basePath: basePath}, nil
}

func (a *FileArchiveStorage) Archive(record *DataRecord, data []byte) (string, error) {
	archivePath := filepath.Join(a.basePath, fmt.Sprintf("%s_%d.json", record.ID, record.Timestamp.Unix()))
	if err := os.WriteFile(archivePath, data, 0644); err != nil {
		return "", err
	}
	logger.Sugar().Infof("Archived record %s to %s", record.ID, archivePath)
	return archivePath, nil
}

func (a *FileArchiveStorage) Restore(archivePath string) ([]byte, error) {
	return os.ReadFile(archivePath)
}

func (a *FileArchiveStorage) Delete(archivePath string) error {
	return os.Remove(archivePath)
}

func (a *FileArchiveStorage) List(prefix string) ([]string, error) {
	files, err := os.ReadDir(a.basePath)
	if err != nil {
		return nil, err
	}

	var paths []string
	for _, f := range files {
		if !f.IsDir() {
			paths = append(paths, filepath.Join(a.basePath, f.Name()))
		}
	}
	return paths, nil
}

type LifecycleManager struct {
	storage          DataStorage
	archiveStorage   ArchiveStorage
	policies         map[string]*LifecyclePolicy
	executionLogs    []*PolicyExecutionLog
	maxLogs          int
	logRetentionDays int
	cron             *cron.Cron
	cronEntries      map[string]cron.EntryID
	mu               sync.RWMutex
}

func NewLifecycleManager(storage DataStorage, archiveStorage ArchiveStorage) *LifecycleManager {
	manager := &LifecycleManager{
		storage:          storage,
		archiveStorage:   archiveStorage,
		policies:         make(map[string]*LifecyclePolicy),
		executionLogs:    make([]*PolicyExecutionLog, 0, DefaultMaxExecutionLogs),
		maxLogs:          DefaultMaxExecutionLogs,
		logRetentionDays: DefaultLogRetentionDays,
		cron:             cron.New(),
		cronEntries:      make(map[string]cron.EntryID),
	}
	manager.cron.Start()
	logger.Sugar().Info("Lifecycle manager initialized")
	return manager
}

func (m *LifecycleManager) SetMaxLogs(max int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.maxLogs = max
	logger.Sugar().Infof("Set max execution logs to %d", max)
}

func (m *LifecycleManager) SetLogRetentionDays(days int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logRetentionDays = days
	logger.Sugar().Infof("Set log retention days to %d", days)
}

func (m *LifecycleManager) AddPolicy(policy *LifecyclePolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if policy.ID == "" {
		policy.ID = uuid.New().String()
	}
	policy.CreatedAt = time.Now().UTC()
	policy.UpdatedAt = policy.CreatedAt

	m.policies[policy.ID] = policy

	if policy.Enabled && policy.CronSchedule != "" {
		entryID, err := m.cron.AddFunc(policy.CronSchedule, func() {
			_ = m.ExecutePolicy(policy.ID)
		})
		if err != nil {
			return err
		}
		m.cronEntries[policy.ID] = entryID
		logger.Sugar().Infof("Scheduled lifecycle policy %s with cron: %s", policy.ID, policy.CronSchedule)
	}

	logger.Sugar().Infof("Added lifecycle policy: %s (%s)", policy.Name, policy.ID)
	return nil
}

func (m *LifecycleManager) RemovePolicy(policyID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if entryID, ok := m.cronEntries[policyID]; ok {
		m.cron.Remove(entryID)
		delete(m.cronEntries, policyID)
	}

	delete(m.policies, policyID)
	m.cleanupPolicyLogsLocked(policyID)

	logger.Sugar().Infof("Removed lifecycle policy: %s", policyID)
	return nil
}

func (m *LifecycleManager) cleanupPolicyLogsLocked(policyID string) {
	remaining := make([]*PolicyExecutionLog, 0, len(m.executionLogs))
	for _, log := range m.executionLogs {
		if log.PolicyID != policyID {
			remaining = append(remaining, log)
		}
	}
	removed := len(m.executionLogs) - len(remaining)
	if removed > 0 {
		m.executionLogs = remaining
		logger.Sugar().Infof("Cleaned up %d execution logs for policy %s", removed, policyID)
		runtime.GC()
	}
}

func (m *LifecycleManager) GetPolicy(policyID string) (*LifecyclePolicy, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	policy, ok := m.policies[policyID]
	if !ok {
		return nil, fmt.Errorf("policy not found: %s", policyID)
	}
	return policy, nil
}

func (m *LifecycleManager) ListPolicies() []*LifecyclePolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()

	policies := make([]*LifecyclePolicy, 0, len(m.policies))
	for _, p := range m.policies {
		policies = append(policies, p)
	}
	return policies
}

func (m *LifecycleManager) ExecutePolicy(policyID string) (*PolicyExecutionLog, error) {
	m.mu.RLock()
	policy, ok := m.policies[policyID]
	m.mu.RUnlock()

	if !ok {
		return nil, fmt.Errorf("policy not found: %s", policyID)
	}

	if !policy.Enabled {
		return nil, fmt.Errorf("policy is disabled: %s", policyID)
	}

	log := &PolicyExecutionLog{
		ID:         uuid.New().String(),
		PolicyID:   policy.ID,
		PolicyName: policy.Name,
		StartedAt:  time.Now().UTC(),
	}

	logger.Sugar().Infof("Executing lifecycle policy: %s", policy.Name)

	tiers := []DataTier{TierHot, TierWarm, TierCold}
	for _, tier := range tiers {
		records, err := m.storage.GetRecordsByTier(tier)
		if err != nil {
			log.Error = err.Error()
			break
		}

		for _, record := range records {
			log.RecordsTotal++

			if newTier, shouldMigrate := policy.Strategy.ShouldMigrate(record, tier); shouldMigrate {
				log.Action = ActionMigrate
				if err := m.storage.MoveRecord(record, tier, newTier); err != nil {
					log.RecordsFailed++
					logger.Sugar().Errorf("Failed to migrate record %s from %s to %s: %v",
						record.ID, tier, newTier, err)
				} else {
					log.RecordsSuccess++
					logger.Sugar().Infof("Migrated record %s from %s to %s", record.ID, tier, newTier)
				}
				continue
			}

			age := time.Since(record.Timestamp)
			if age > policy.ArchiveAfter && tier == TierCold {
				log.Action = ActionArchive
				data, _ := json.Marshal(record)
				if _, err := m.archiveStorage.Archive(record, data); err != nil {
					log.RecordsFailed++
				} else {
					if err := m.storage.DeleteRecord(record, tier); err == nil {
						log.RecordsSuccess++
					} else {
						log.RecordsFailed++
					}
				}
				continue
			}

			if age > policy.DeleteAfter {
				log.Action = ActionCleanup
				if err := m.storage.DeleteRecord(record, tier); err != nil {
					log.RecordsFailed++
				} else {
					log.RecordsSuccess++
				}
			}
		}
	}

	log.FinishedAt = time.Now().UTC()

	m.mu.Lock()
	m.executionLogs = append(m.executionLogs, log)
	m.trimLogsLocked()
	m.mu.Unlock()

	logger.Sugar().Infof("Policy %s execution completed: total=%d, success=%d, failed=%d",
		policy.Name, log.RecordsTotal, log.RecordsSuccess, log.RecordsFailed)

	return log, nil
}

func (m *LifecycleManager) trimLogsLocked() {
	if m.maxLogs <= 0 {
		m.maxLogs = DefaultMaxExecutionLogs
	}

	if len(m.executionLogs) > m.maxLogs {
		excess := len(m.executionLogs) - m.maxLogs
		m.executionLogs = m.executionLogs[excess:]
		logger.Sugar().Debugf("Trimmed %d old execution logs, current count: %d", excess, len(m.executionLogs))
		runtime.GC()
	}

	if m.logRetentionDays > 0 {
		cutoff := time.Now().UTC().AddDate(0, 0, -m.logRetentionDays)
		remaining := make([]*PolicyExecutionLog, 0, len(m.executionLogs))
		for _, log := range m.executionLogs {
			if log.FinishedAt.After(cutoff) {
				remaining = append(remaining, log)
			}
		}
		if len(remaining) != len(m.executionLogs) {
			removed := len(m.executionLogs) - len(remaining)
			m.executionLogs = remaining
			logger.Sugar().Debugf("Removed %d logs older than %d days, current count: %d",
				removed, m.logRetentionDays, len(m.executionLogs))
			runtime.GC()
		}
	}
}

func (m *LifecycleManager) ExecuteAllPolicies() []*PolicyExecutionLog {
	m.mu.RLock()
	policyIDs := make([]string, 0, len(m.policies))
	for id := range m.policies {
		policyIDs = append(policyIDs, id)
	}
	m.mu.RUnlock()

	logs := make([]*PolicyExecutionLog, 0, len(policyIDs))
	for _, id := range policyIDs {
		log, err := m.ExecutePolicy(id)
		if err != nil {
			logger.Sugar().Errorf("Failed to execute policy %s: %v", id, err)
			continue
		}
		logs = append(logs, log)
	}
	return logs
}

func (m *LifecycleManager) GetExecutionLogs(policyID string) []*PolicyExecutionLog {
	m.mu.RLock()
	defer m.mu.RUnlock()

	logs := make([]*PolicyExecutionLog, 0)
	for _, log := range m.executionLogs {
		if log.PolicyID == policyID {
			logs = append(logs, log)
		}
	}
	return logs
}

func (m *LifecycleManager) GetStorageStats() map[DataTier]map[string]interface{} {
	stats := make(map[DataTier]map[string]interface{})
	tiers := []DataTier{TierHot, TierWarm, TierCold, TierArchive}

	for _, tier := range tiers {
		count, _ := m.storage.GetRecordCount(tier)
		size, _ := m.storage.GetTotalSize(tier)
		stats[tier] = map[string]interface{}{
			"count": count,
			"size_bytes": size,
		}
	}

	return stats
}

func (m *LifecycleManager) CleanupOldLogs() int {
	m.mu.Lock()
	defer m.mu.Unlock()

	initialCount := len(m.executionLogs)
	m.trimLogsLocked()
	cleaned := initialCount - len(m.executionLogs)

	logger.Sugar().Infof("Cleaned up %d old execution logs", cleaned)
	return cleaned
}

func (m *LifecycleManager) GetMemoryStats() map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var memStats runtime.MemStats
	runtime.ReadMemStats(&memStats)

	return map[string]interface{}{
		"execution_logs_count":  len(m.executionLogs),
		"policies_count":        len(m.policies),
		"alloc_mb":              memStats.Alloc / 1024 / 1024,
		"total_alloc_mb":        memStats.TotalAlloc / 1024 / 1024,
		"sys_mb":                memStats.Sys / 1024 / 1024,
		"heap_objects":          memStats.HeapObjects,
		"goroutines":            runtime.NumGoroutine(),
	}
}

func (m *LifecycleManager) ForceGC() {
	runtime.GC()
	logger.Sugar().Info("Forced garbage collection executed")
}

func (m *LifecycleManager) Stop() {
	m.cron.Stop()
	m.CleanupOldLogs()
	m.ForceGC()
	logger.Sugar().Info("Lifecycle manager stopped")
}
