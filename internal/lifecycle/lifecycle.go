package lifecycle

import (
	"context"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/models"
	"github.com/datatrace/datatrace/internal/storage"
	"github.com/google/uuid"
)

type DataTier string

const (
	TierHot      DataTier = "hot"
	TierWarm     DataTier = "warm"
	TierCold     DataTier = "cold"
	TierFrozen   DataTier = "frozen"
	TierArchived DataTier = "archived"
)

type LifecyclePolicy struct {
	ID          string        `json:"id"`
	Name        string        `json:"name"`
	Description string        `json:"description"`
	HotDuration time.Duration `json:"hot_duration"`
	WarmDuration time.Duration `json:"warm_duration"`
	ColdDuration time.Duration `json:"cold_duration"`
	MaxAge       time.Duration `json:"max_age"`
	CronSchedule string        `json:"cron_schedule"`
	Enabled      bool          `json:"enabled"`
	CreatedAt    time.Time     `json:"created_at"`
}

type DataRecord struct {
	ID           string                 `json:"id"`
	Key          string                 `json:"key"`
	Tier         DataTier               `json:"tier"`
	Size         int64                  `json:"size"`
	CreatedAt    time.Time              `json:"created_at"`
	LastAccessed time.Time              `json:"last_accessed"`
	AccessCount  int64                  `json:"access_count"`
	Tags         map[string]string      `json:"tags"`
	Attributes   map[string]interface{} `json:"attributes"`
}

type MigrationEvent struct {
	ID         string    `json:"id"`
	RecordID   string    `json:"record_id"`
	FromTier   DataTier  `json:"from_tier"`
	ToTier     DataTier  `json:"to_tier"`
	Status     string    `json:"status"`
	StartedAt  time.Time `json:"started_at"`
	FinishedAt time.Time `json:"finished_at,omitempty"`
	Error      string    `json:"error,omitempty"`
}

type LifecycleManager struct {
	records   map[string]*DataRecord
	policies  map[string]*LifecyclePolicy
	migrations []*MigrationEvent
	storage   *storage.StorageManager
	mu        sync.RWMutex
	stopCh    chan struct{}
	wg        sync.WaitGroup
}

func NewLifecycleManager(storageManager *storage.StorageManager) *LifecycleManager {
	return &LifecycleManager{
		records:    make(map[string]*DataRecord),
		policies:   make(map[string]*LifecyclePolicy),
		migrations: make([]*MigrationEvent, 0),
		storage:    storageManager,
		stopCh:     make(chan struct{}),
	}
}

func (lm *LifecycleManager) Start() {
	lm.wg.Add(1)
	go lm.runLifecycleLoop()
}

func (lm *LifecycleManager) Stop() {
	close(lm.stopCh)
	lm.wg.Wait()
}

func (lm *LifecycleManager) runLifecycleLoop() {
	defer lm.wg.Done()

	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-lm.stopCh:
			return
		case <-ticker.C:
			lm.processLifecycle()
		}
	}
}

func (lm *LifecycleManager) processLifecycle() {
	lm.mu.Lock()
	defer lm.mu.Unlock()

	now := time.Now()

	for _, record := range lm.records {
		age := now.Sub(record.CreatedAt)

		newTier := lm.calculateTier(age, record)

		if newTier != record.Tier {
			lm.migrateRecord(record, newTier)
		}

		for _, policy := range lm.policies {
			if policy.Enabled && age > policy.MaxAge {
				lm.archiveOrDelete(record)
			}
		}
	}
}

func (lm *LifecycleManager) calculateTier(age time.Duration, record *DataRecord) DataTier {
	accessFrequency := float64(record.AccessCount) / age.Hours()

	if age < 24*time.Hour || accessFrequency > 10 {
		return TierHot
	}

	if age < 7*24*time.Hour || accessFrequency > 1 {
		return TierWarm
	}

	if age < 30*24*time.Hour || accessFrequency > 0.1 {
		return TierCold
	}

	if age < 365*24*time.Hour {
		return TierFrozen
	}

	return TierArchived
}

func (lm *LifecycleManager) migrateRecord(record *DataRecord, newTier DataTier) {
	event := &MigrationEvent{
		ID:        uuid.New().String(),
		RecordID:  record.ID,
		FromTier:  record.Tier,
		ToTier:    newTier,
		Status:    "in_progress",
		StartedAt: time.Now(),
	}

	ctx := context.Background()
	data, _, err := lm.storage.Retrieve(ctx, record.Key)
	if err != nil {
		event.Status = "failed"
		event.Error = err.Error()
		event.FinishedAt = time.Now()
		lm.migrations = append(lm.migrations, event)
		return
	}

	tags := make(map[string]string)
	for k, v := range record.Tags {
		tags[k] = v
	}
	tags["tier"] = string(newTier)

	_, err = lm.storage.Store(ctx, record.Key, data, tags, record.Attributes)
	if err != nil {
		event.Status = "failed"
		event.Error = err.Error()
		event.FinishedAt = time.Now()
		lm.migrations = append(lm.migrations, event)
		return
	}

	record.Tier = newTier
	event.Status = "completed"
	event.FinishedAt = time.Now()
	lm.migrations = append(lm.migrations, event)
}

func (lm *LifecycleManager) archiveOrDelete(record *DataRecord) {
	ctx := context.Background()
	lm.storage.Delete(ctx, record.Key)
	delete(lm.records, record.ID)
}

func (lm *LifecycleManager) RegisterRecord(key string, size int64, tags map[string]string, attributes map[string]interface{}) *DataRecord {
	lm.mu.Lock()
	defer lm.mu.Unlock()

	record := &DataRecord{
		ID:           uuid.New().String(),
		Key:          key,
		Tier:         TierHot,
		Size:         size,
		CreatedAt:    time.Now(),
		LastAccessed: time.Now(),
		AccessCount:  0,
		Tags:         tags,
		Attributes:   attributes,
	}

	lm.records[record.ID] = record
	return record
}

func (lm *LifecycleManager) AccessRecord(recordID string) {
	lm.mu.Lock()
	defer lm.mu.Unlock()

	if record, ok := lm.records[recordID]; ok {
		record.LastAccessed = time.Now()
		record.AccessCount++
	}
}

func (lm *LifecycleManager) GetRecord(recordID string) (*DataRecord, bool) {
	lm.mu.RLock()
	defer lm.mu.RUnlock()

	record, ok := lm.records[recordID]
	return record, ok
}

func (lm *LifecycleManager) ListRecords() []*DataRecord {
	lm.mu.RLock()
	defer lm.mu.RUnlock()

	records := make([]*DataRecord, 0, len(lm.records))
	for _, r := range lm.records {
		records = append(records, r)
	}
	return records
}

func (lm *LifecycleManager) AddPolicy(policy *LifecyclePolicy) {
	lm.mu.Lock()
	defer lm.mu.Unlock()

	if policy.ID == "" {
		policy.ID = uuid.New().String()
	}
	policy.CreatedAt = time.Now()
	lm.policies[policy.ID] = policy
}

func (lm *LifecycleManager) GetPolicy(policyID string) (*LifecyclePolicy, bool) {
	lm.mu.RLock()
	defer lm.mu.RUnlock()

	policy, ok := lm.policies[policyID]
	return policy, ok
}

func (lm *LifecycleManager) ListPolicies() []*LifecyclePolicy {
	lm.mu.RLock()
	defer lm.mu.RUnlock()

	policies := make([]*LifecyclePolicy, 0, len(lm.policies))
	for _, p := range lm.policies {
		policies = append(policies, p)
	}
	return policies
}

func (lm *LifecycleManager) GetTierStats() map[DataTier]int64 {
	lm.mu.RLock()
	defer lm.mu.RUnlock()

	stats := make(map[DataTier]int64)
	for _, record := range lm.records {
		stats[record.Tier] += record.Size
	}
	return stats
}

func (lm *LifecycleManager) GetMigrationHistory() []*MigrationEvent {
	lm.mu.RLock()
	defer lm.mu.RUnlock()

	history := make([]*MigrationEvent, len(lm.migrations))
	copy(history, lm.migrations)
	return history
}

func (lm *LifecycleManager) ToEntity() *models.Entity {
	return &models.Entity{
		ID:        uuid.New().String(),
		Type:      "lifecycle_manager",
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}
