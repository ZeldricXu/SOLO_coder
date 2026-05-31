package lifecycle

import (
	"time"
)

type DataTier string

const (
	TierHot     DataTier = "hot"
	TierWarm    DataTier = "warm"
	TierCold    DataTier = "cold"
	TierArchive DataTier = "archive"

	DefaultMaxExecutionLogs = 10000
	DefaultLogRetentionDays = 30
)

type LifecycleAction string

const (
	ActionMigrate LifecycleAction = "migrate"
	ActionArchive LifecycleAction = "archive"
	ActionCleanup LifecycleAction = "cleanup"
)

type DataRecord struct {
	ID        string
	TableName string
	Timestamp time.Time
	SizeBytes int64
	Metadata  map[string]interface{}
}

type MigrationResult struct {
	RecordID   string
	FromTier   DataTier
	ToTier     DataTier
	Success    bool
	Error      string
	StartedAt  time.Time
	FinishedAt time.Time
}

type ArchiveResult struct {
	RecordID   string
	ArchivePath string
	Success    bool
	Error      string
	StartedAt  time.Time
	FinishedAt time.Time
}

type CleanupResult struct {
	RecordID   string
	Success    bool
	Error      string
	StartedAt  time.Time
	FinishedAt time.Time
}

type TierStrategy interface {
	Name() string
	ShouldMigrate(record *DataRecord, currentTier DataTier) (DataTier, bool)
	GetTierThreshold(tier DataTier) time.Duration
}

type AgeBasedTierStrategy struct {
	hotThreshold  time.Duration
	warmThreshold time.Duration
	coldThreshold time.Duration
}

func NewAgeBasedTierStrategy(hotDays, warmDays, coldDays int) *AgeBasedTierStrategy {
	return &AgeBasedTierStrategy{
		hotThreshold:  time.Duration(hotDays) * 24 * time.Hour,
		warmThreshold: time.Duration(warmDays) * 24 * time.Hour,
		coldThreshold: time.Duration(coldDays) * 24 * time.Hour,
	}
}

func (s *AgeBasedTierStrategy) Name() string {
	return "age_based"
}

func (s *AgeBasedTierStrategy) ShouldMigrate(record *DataRecord, currentTier DataTier) (DataTier, bool) {
	age := time.Since(record.Timestamp)

	switch currentTier {
	case TierHot:
		if age > s.warmThreshold {
			return TierWarm, true
		}
	case TierWarm:
		if age > s.coldThreshold {
			return TierCold, true
		}
	case TierCold:
		if age > s.coldThreshold*2 {
			return TierArchive, true
		}
	}

	return currentTier, false
}

func (s *AgeBasedTierStrategy) GetTierThreshold(tier DataTier) time.Duration {
	switch tier {
	case TierHot:
		return s.hotThreshold
	case TierWarm:
		return s.warmThreshold
	case TierCold:
		return s.coldThreshold
	default:
		return 0
	}
}

type DataStorage interface {
	GetRecordsByTier(tier DataTier) ([]*DataRecord, error)
	MoveRecord(record *DataRecord, fromTier, toTier DataTier) error
	DeleteRecord(record *DataRecord, tier DataTier) error
	GetRecordCount(tier DataTier) (int64, error)
	GetTotalSize(tier DataTier) (int64, error)
}

type ArchiveStorage interface {
	Archive(record *DataRecord, data []byte) (string, error)
	Restore(archivePath string) ([]byte, error)
	Delete(archivePath string) error
	List(prefix string) ([]string, error)
}

type LifecyclePolicy struct {
	ID           string
	Name         string
	Description  string
	TableName    string
	Strategy     TierStrategy
	Enabled      bool
	ArchiveAfter time.Duration
	DeleteAfter  time.Duration
	CronSchedule string
	CreatedAt    time.Time
	UpdatedAt    time.Time
}

type PolicyExecutionLog struct {
	ID            string
	PolicyID      string
	PolicyName    string
	Action        LifecycleAction
	RecordsTotal  int
	RecordsSuccess int
	RecordsFailed  int
	StartedAt     time.Time
	FinishedAt    time.Time
	Error         string
}
