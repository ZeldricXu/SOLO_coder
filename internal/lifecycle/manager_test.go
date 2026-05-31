package lifecycle

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestLifecycleManager_MaxLogs(t *testing.T) {
	storage := NewInMemoryDataStorage()
	archiveStorage, _ := NewFileArchiveStorage("/tmp/test_lifecycle_maxlogs")
	manager := NewLifecycleManager(storage, archiveStorage)

	t.Run("should have default max logs", func(t *testing.T) {
		assert.Equal(t, DefaultMaxExecutionLogs, manager.maxLogs)
	})

	t.Run("should be able to set max logs", func(t *testing.T) {
		manager.SetMaxLogs(100)
		assert.Equal(t, 100, manager.maxLogs)
	})

	t.Run("should have default log retention days", func(t *testing.T) {
		assert.Equal(t, DefaultLogRetentionDays, manager.logRetentionDays)
	})

	t.Run("should be able to set log retention days", func(t *testing.T) {
		manager.SetLogRetentionDays(15)
		assert.Equal(t, 15, manager.logRetentionDays)
	})
}

func TestLifecycleManager_LogTrimming(t *testing.T) {
	storage := NewInMemoryDataStorage()
	archiveStorage, _ := NewFileArchiveStorage("/tmp/test_lifecycle_trim")
	manager := NewLifecycleManager(storage, archiveStorage)
	manager.SetMaxLogs(5)

	policy := &LifecyclePolicy{
		Name:         "Test Policy",
		Strategy:     NewAgeBasedTierStrategy(1, 7, 30),
		Enabled:      true,
		ArchiveAfter: 60 * 24 * time.Hour,
		DeleteAfter:  90 * 24 * time.Hour,
	}

	err := manager.AddPolicy(policy)
	assert.NoError(t, err)

	for i := 0; i < 10; i++ {
		record := &DataRecord{
			ID:        string(rune('A' + i)),
			TableName: "test",
			Timestamp: time.Now().AddDate(0, 0, -100),
			SizeBytes: 100,
		}
		storage.AddRecord(record, TierCold)
	}

	for i := 0; i < 3; i++ {
		_, err := manager.ExecutePolicy(policy.ID)
		assert.NoError(t, err)
	}

	logs := manager.GetExecutionLogs(policy.ID)
	assert.LessOrEqual(t, len(logs), 5)

	assert.LessOrEqual(t, len(manager.executionLogs), 5)
}

func TestLifecycleManager_RemovePolicy_CleansUpLogs(t *testing.T) {
	storage := NewInMemoryDataStorage()
	archiveStorage, _ := NewFileArchiveStorage("/tmp/test_lifecycle_cleanup")
	manager := NewLifecycleManager(storage, archiveStorage)

	policy1 := &LifecyclePolicy{
		Name:         "Policy 1",
		Strategy:     NewAgeBasedTierStrategy(1, 7, 30),
		Enabled:      true,
		ArchiveAfter: 60 * 24 * time.Hour,
		DeleteAfter:  90 * 24 * time.Hour,
	}

	policy2 := &LifecyclePolicy{
		Name:         "Policy 2",
		Strategy:     NewAgeBasedTierStrategy(1, 7, 30),
		Enabled:      true,
		ArchiveAfter: 60 * 24 * time.Hour,
		DeleteAfter:  90 * 24 * time.Hour,
	}

	_ = manager.AddPolicy(policy1)
	_ = manager.AddPolicy(policy2)

	for i := 0; i < 5; i++ {
		_, _ = manager.ExecutePolicy(policy1.ID)
		_, _ = manager.ExecutePolicy(policy2.ID)
	}

	assert.Equal(t, 10, len(manager.executionLogs))

	err := manager.RemovePolicy(policy1.ID)
	assert.NoError(t, err)

	assert.Equal(t, 5, len(manager.executionLogs))

	logsForPolicy2 := manager.GetExecutionLogs(policy2.ID)
	assert.Equal(t, 5, len(logsForPolicy2))

	logsForPolicy1 := manager.GetExecutionLogs(policy1.ID)
	assert.Equal(t, 0, len(logsForPolicy1))
}

func TestLifecycleManager_CleanupOldLogs(t *testing.T) {
	storage := NewInMemoryDataStorage()
	archiveStorage, _ := NewFileArchiveStorage("/tmp/test_lifecycle_cleanup_old")
	manager := NewLifecycleManager(storage, archiveStorage)
	manager.SetMaxLogs(3)
	manager.SetLogRetentionDays(1)

	policy := &LifecyclePolicy{
		Name:         "Test Policy",
		Strategy:     NewAgeBasedTierStrategy(1, 7, 30),
		Enabled:      true,
		ArchiveAfter: 60 * 24 * time.Hour,
		DeleteAfter:  90 * 24 * time.Hour,
	}

	_ = manager.AddPolicy(policy)

	oldLog := &PolicyExecutionLog{
		ID:         "old_log_1",
		PolicyID:   policy.ID,
		PolicyName: policy.Name,
		FinishedAt: time.Now().UTC().AddDate(0, 0, -10),
	}
	manager.mu.Lock()
	manager.executionLogs = append(manager.executionLogs, oldLog)
	manager.mu.Unlock()

	for i := 0; i < 5; i++ {
		_, _ = manager.ExecutePolicy(policy.ID)
	}

	cleaned := manager.CleanupOldLogs()
	assert.GreaterOrEqual(t, cleaned, 0)

	assert.LessOrEqual(t, len(manager.executionLogs), 3)
}

func TestLifecycleManager_ForceGC(t *testing.T) {
	storage := NewInMemoryDataStorage()
	archiveStorage, _ := NewFileArchiveStorage("/tmp/test_lifecycle_gc")
	manager := NewLifecycleManager(storage, archiveStorage)

	assert.NotPanics(t, func() {
		manager.ForceGC()
	})
}

func TestLifecycleManager_GetMemoryStats(t *testing.T) {
	storage := NewInMemoryDataStorage()
	archiveStorage, _ := NewFileArchiveStorage("/tmp/test_lifecycle_memstats")
	manager := NewLifecycleManager(storage, archiveStorage)

	stats := manager.GetMemoryStats()

	assert.Contains(t, stats, "execution_logs_count")
	assert.Contains(t, stats, "policies_count")
	assert.Contains(t, stats, "alloc_mb")
	assert.Contains(t, stats, "total_alloc_mb")
	assert.Contains(t, stats, "sys_mb")
	assert.Contains(t, stats, "heap_objects")
	assert.Contains(t, stats, "goroutines")

	assert.Equal(t, 0, stats["execution_logs_count"])
	assert.Equal(t, 0, stats["policies_count"])
}

func TestLifecycleManager_Stop(t *testing.T) {
	storage := NewInMemoryDataStorage()
	archiveStorage, _ := NewFileArchiveStorage("/tmp/test_lifecycle_stop")
	manager := NewLifecycleManager(storage, archiveStorage)

	assert.NotPanics(t, func() {
		manager.Stop()
	})
}

func TestLifecycleManager_Integration(t *testing.T) {
	storage := NewInMemoryDataStorage()
	archiveStorage, _ := NewFileArchiveStorage("/tmp/test_lifecycle_integration")
	manager := NewLifecycleManager(storage, archiveStorage)
	manager.SetMaxLogs(10)
	manager.SetLogRetentionDays(7)

	policy := &LifecyclePolicy{
		Name:         "Integration Test Policy",
		Strategy:     NewAgeBasedTierStrategy(1, 7, 30),
		Enabled:      true,
		ArchiveAfter: 60 * 24 * time.Hour,
		DeleteAfter:  90 * 24 * time.Hour,
	}

	err := manager.AddPolicy(policy)
	assert.NoError(t, err)

	hotRecord := &DataRecord{
		ID:        "hot_001",
		TableName: "test_table",
		Timestamp: time.Now(),
		SizeBytes: 1024,
	}
	storage.AddRecord(hotRecord, TierHot)

	warmRecord := &DataRecord{
		ID:        "warm_001",
		TableName: "test_table",
		Timestamp: time.Now().AddDate(0, 0, -10),
		SizeBytes: 2048,
	}
	storage.AddRecord(warmRecord, TierWarm)

	coldRecord := &DataRecord{
		ID:        "cold_001",
		TableName: "test_table",
		Timestamp: time.Now().AddDate(0, 0, -60),
		SizeBytes: 4096,
	}
	storage.AddRecord(coldRecord, TierCold)

	stats := manager.GetStorageStats()
	assert.Equal(t, int64(1), stats[TierHot]["count"])
	assert.Equal(t, int64(1), stats[TierWarm]["count"])
	assert.Equal(t, int64(1), stats[TierCold]["count"])

	result, err := manager.ExecutePolicy(policy.ID)
	assert.NoError(t, err)
	assert.NotNil(t, result)
	assert.Greater(t, result.RecordsTotal, 0)

	logs := manager.GetExecutionLogs(policy.ID)
	assert.Equal(t, 1, len(logs))
}
