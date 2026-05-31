package aggregation

import (
	"context"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
)

type DataStreamRepository interface {
	Create(ctx context.Context, stream *model.DataStream) error
	GetByID(ctx context.Context, id string) (*model.DataStream, error)
	ListByDeviceID(ctx context.Context, deviceID string, page, pageSize int) ([]model.DataStream, int64, error)
	Update(ctx context.Context, stream *model.DataStream) error
	Delete(ctx context.Context, id string) error
}

type RawDataPointRepository interface {
	Create(ctx context.Context, point *model.RawDataPoint) error
	CreateBatch(ctx context.Context, points []model.RawDataPoint) error
	ListByStreamAndTimeRange(ctx context.Context, streamID string, startTime, endTime time.Time) ([]model.RawDataPoint, error)
	DeleteByTimeRange(ctx context.Context, streamID string, beforeTime time.Time) error
}

type AggregatedDataRepository interface {
	Create(ctx context.Context, data *model.AggregatedData) error
	ListByStreamID(ctx context.Context, streamID string, startTime, endTime time.Time, page, pageSize int) ([]model.AggregatedData, int64, error)
	GetLatest(ctx context.Context, streamID, metric string) (*model.AggregatedData, error)
}

type StreamService interface {
	CreateStream(ctx context.Context, req *CreateDataStreamRequest) (*model.DataStream, error)
	GetStream(ctx context.Context, streamID string) (*model.DataStream, error)
	ListStreams(ctx context.Context, deviceID string, page, pageSize int) ([]model.DataStream, int64, error)
}

type AggregationService interface {
	IngestDataPoint(ctx context.Context, req *IngestDataPointRequest) error
	IngestBatch(ctx context.Context, deviceID string, points []IngestDataPointRequest) error
	AggregateData(ctx context.Context, streamID string) (*model.AggregatedData, error)
	GetAggregatedData(ctx context.Context, streamID string, startTime, endTime time.Time, page, pageSize int) ([]model.AggregatedData, int64, error)
	GetLatestAggregatedData(ctx context.Context, streamID, metric string) (*model.AggregatedData, error)
	GetStreamStats(ctx context.Context, streamID string) (*StreamStats, error)
}

type DataAggregationService interface {
	StreamService
	AggregationService
	AsyncAggregationService
}

type AsyncAggregationService interface {
	AggregateDataAsync(ctx context.Context, streamID string, callback AggregationCallback) (*AggregationTask, error)
	AggregateDataSync(ctx context.Context, streamID string) (*model.AggregatedData, error)
	GetTaskStatus(ctx context.Context, taskID string) (*AggregationTask, error)
	CancelTask(ctx context.Context, taskID string) error
	WaitForTask(ctx context.Context, taskID string, timeout time.Duration) (*AggregationTask, error)
	GetAsyncManager() AsyncTaskManager
}

type EventPublisher interface {
	PublishDataIngested(ctx context.Context, streamID, deviceID string)
	PublishAggregationCompleted(ctx context.Context, streamID string, metric string, value float64)
	PublishThresholdExceeded(ctx context.Context, streamID, metric string, value, threshold float64)
}

type CreateDataStreamRequest struct {
	DeviceID            string                 `json:"device_id"`
	Name                string                 `json:"name"`
	Description         string                 `json:"description"`
	MetricNames         []string               `json:"metric_names"`
	AggregationStrategy string                 `json:"aggregation_strategy"`
	WindowSize          int                    `json:"window_size"`
	WindowUnit          string                 `json:"window_unit"`
	CompressionEnabled  bool                   `json:"compression_enabled"`
	SamplingRate        float64                `json:"sampling_rate"`
	Thresholds          map[string]float64     `json:"thresholds"`
	Metadata            map[string]interface{} `json:"metadata"`
}

type IngestDataPointRequest struct {
	DeviceID string            `json:"device_id"`
	Metric   string            `json:"metric"`
	Value    float64           `json:"value"`
	Tags     map[string]string `json:"tags"`
}

type AggregateResult struct {
	Metric string  `json:"metric"`
	Min    float64 `json:"min"`
	Max    float64 `json:"max"`
	Avg    float64 `json:"avg"`
	Sum    float64 `json:"sum"`
	Count  int     `json:"count"`
	P50    float64 `json:"p50"`
	P95    float64 `json:"p95"`
	P99    float64 `json:"p99"`
}

type StreamStats struct {
	StreamID        string  `json:"stream_id"`
	TotalPoints     int64   `json:"total_points"`
	TotalAggregated int64   `json:"total_aggregated"`
	CompressionRate float64 `json:"compression_rate"`
	DataReduction   float64 `json:"data_reduction_percent"`
	LastAggregated  string  `json:"last_aggregated_at"`
}
