package offline

import (
	"context"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
)

type DataRecordRepository interface {
	Create(ctx context.Context, record *model.OfflineDataRecord) error
	GetByID(ctx context.Context, id string) (*model.OfflineDataRecord, error)
	GetPending(ctx context.Context, deviceID string, limit int) ([]model.OfflineDataRecord, error)
	List(ctx context.Context, deviceID, status string, page, pageSize int) ([]model.OfflineDataRecord, int64, error)
	UpdateStatus(ctx context.Context, id string, status string, errorMsg *string) error
	MarkAsSynced(ctx context.Context, id string) error
	IncrementRetry(ctx context.Context, id string) error
}

type SyncSessionRepository interface {
	Create(ctx context.Context, session *model.SyncSession) error
	GetByID(ctx context.Context, id string) (*model.SyncSession, error)
	ListByDeviceID(ctx context.Context, deviceID string, page, pageSize int) ([]model.SyncSession, int64, error)
	UpdateStatus(ctx context.Context, id string, status string, syncedCount, failedCount int) error
	Complete(ctx context.Context, id string, syncedCount, failedCount int) error
}

type CacheService interface {
	CacheData(ctx context.Context, req *CacheDataRequest) (*model.OfflineDataRecord, error)
	GetPendingRecords(ctx context.Context, deviceID string, limit int) ([]model.OfflineDataRecord, error)
	ListRecords(ctx context.Context, deviceID, status string, page, pageSize int) ([]model.OfflineDataRecord, int64, error)
}

type SyncService interface {
	SyncData(ctx context.Context, deviceID string) (*model.SyncSession, error)
	NetworkRestored(ctx context.Context, deviceID string)
	GetSyncSessions(ctx context.Context, deviceID string, page, pageSize int) ([]model.SyncSession, int64, error)
	GetStats(ctx context.Context, deviceID string) (*SyncStats, error)
}

type OfflineService interface {
	CacheService
	SyncService
	StrategyManagementService
}

type StrategyManagementService interface {
	GetStrategyManager() StrategyManager
	SetDeviceSyncStrategy(deviceID, strategyName string) error
	GetDeviceSyncStrategy(deviceID string) string
	ListSyncStrategies() []string
	SetStrategyConfig(strategyName string, batchSize int, timeout time.Duration) error
}

type CloudUploader interface {
	Upload(ctx context.Context, record *model.OfflineDataRecord) bool
}

type EventPublisher interface {
	PublishDataSynced(ctx context.Context, recordID, deviceID string)
	PublishSyncCompleted(ctx context.Context, sessionID, deviceID string)
	PublishSyncFailed(ctx context.Context, sessionID, deviceID, reason string)
}

type CacheDataRequest struct {
	DeviceID string                 `json:"device_id"`
	DataType string                 `json:"data_type"`
	Payload  map[string]interface{} `json:"payload"`
	Priority int                    `json:"priority"`
}

type SyncStats struct {
	TotalPending   int   `json:"total_pending"`
	TotalSynced    int   `json:"total_synced"`
	TotalFailed    int   `json:"total_failed"`
	TotalDataSize  int64 `json:"total_data_size"`
	AvgSyncLatency int64 `json:"avg_sync_latency_ms"`
}
