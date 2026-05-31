package aggregation

import (
	"context"
	"errors"
	"fmt"
	"math"
	"sort"
	"time"

	"github.com/edgevision/edgevision/internal/domain/aggregation"
	"github.com/edgevision/edgevision/internal/domain/model"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/pkg/utils"
	"go.uber.org/zap"
)

type Service struct {
	dataStreamRepo     aggregation.DataStreamRepository
	rawDataPointRepo   aggregation.RawDataPointRepository
	aggregatedDataRepo aggregation.AggregatedDataRepository
	eventPublisher     aggregation.EventPublisher
	asyncManager       aggregation.AsyncTaskManager
}

func NewService(
	dataStreamRepo aggregation.DataStreamRepository,
	rawDataPointRepo aggregation.RawDataPointRepository,
	aggregatedDataRepo aggregation.AggregatedDataRepository,
	eventPublisher aggregation.EventPublisher,
	asyncManager aggregation.AsyncTaskManager,
) *Service {
	return &Service{
		dataStreamRepo:     dataStreamRepo,
		rawDataPointRepo:   rawDataPointRepo,
		aggregatedDataRepo: aggregatedDataRepo,
		eventPublisher:     eventPublisher,
		asyncManager:       asyncManager,
	}
}

func (s *Service) GetAsyncManager() aggregation.AsyncTaskManager {
	return s.asyncManager
}

func (s *Service) CreateStream(ctx context.Context, req *aggregation.CreateDataStreamRequest) (*model.DataStream, error) {
	stream := &model.DataStream{
		ID:                  utils.GenerateID("ds"),
		DeviceID:            req.DeviceID,
		Name:                req.Name,
		Description:         req.Description,
		MetricNames:         req.MetricNames,
		AggregationStrategy: req.AggregationStrategy,
		WindowSize:          req.WindowSize,
		WindowUnit:          req.WindowUnit,
		CompressionEnabled:  req.CompressionEnabled,
		SamplingRate:        req.SamplingRate,
		Thresholds:          req.Thresholds,
		Metadata:            req.Metadata,
		IsEnabled:           true,
		CreatedAt:           utils.Now(),
		UpdatedAt:           utils.Now(),
	}

	if err := s.dataStreamRepo.Create(ctx, stream); err != nil {
		logger.Get().Error("failed to create data stream", zap.Error(err))
		return nil, err
	}

	return stream, nil
}

func (s *Service) GetStream(ctx context.Context, streamID string) (*model.DataStream, error) {
	return s.dataStreamRepo.GetByID(ctx, streamID)
}

func (s *Service) ListStreams(ctx context.Context, deviceID string, page, pageSize int) ([]model.DataStream, int64, error) {
	return s.dataStreamRepo.ListByDeviceID(ctx, deviceID, page, pageSize)
}

func (s *Service) IngestDataPoint(ctx context.Context, req *aggregation.IngestDataPointRequest) error {
	point := &model.RawDataPoint{
		ID:        utils.GenerateID("rdp"),
		DeviceID:  req.DeviceID,
		Metric:    req.Metric,
		Value:     req.Value,
		Tags:      req.Tags,
		Timestamp: utils.Now(),
		CreatedAt: utils.Now(),
	}

	if err := s.rawDataPointRepo.Create(ctx, point); err != nil {
		return err
	}

	s.eventPublisher.PublishDataIngested(ctx, "", req.DeviceID)
	s.checkThresholds(ctx, req.DeviceID, req.Metric, req.Value)

	return nil
}

func (s *Service) IngestBatch(ctx context.Context, deviceID string, points []aggregation.IngestDataPointRequest) error {
	if len(points) == 0 {
		return nil
	}

	rawPoints := make([]model.RawDataPoint, 0, len(points))
	for _, req := range points {
		rawPoints = append(rawPoints, model.RawDataPoint{
			ID:        utils.GenerateID("rdp"),
			DeviceID:  deviceID,
			Metric:    req.Metric,
			Value:     req.Value,
			Tags:      req.Tags,
			Timestamp: utils.Now(),
			CreatedAt: utils.Now(),
		})

		s.checkThresholds(ctx, deviceID, req.Metric, req.Value)
	}

	if err := s.rawDataPointRepo.CreateBatch(ctx, rawPoints); err != nil {
		return err
	}

	s.eventPublisher.PublishDataIngested(ctx, "", deviceID)

	return nil
}

func (s *Service) checkThresholds(ctx context.Context, deviceID, metric string, value float64) {
	streams, _, _ := s.dataStreamRepo.ListByDeviceID(ctx, deviceID, 1, 100)

	for _, stream := range streams {
		if stream.Thresholds == nil {
			continue
		}

		if threshold, ok := stream.Thresholds[metric]; ok {
			if value > threshold {
				s.eventPublisher.PublishThresholdExceeded(ctx, stream.ID, metric, value, threshold)
			}
		}
	}
}

func (s *Service) AggregateData(ctx context.Context, streamID string) (*model.AggregatedData, error) {
	return s.AggregateDataSync(ctx, streamID)
}

func (s *Service) AggregateDataSync(ctx context.Context, streamID string) (*model.AggregatedData, error) {
	stream, err := s.dataStreamRepo.GetByID(ctx, streamID)
	if err != nil {
		return nil, fmt.Errorf("stream not found: %w", err)
	}

	windowEnd := utils.Now()
	windowStart := s.calculateWindowStart(windowEnd, stream.WindowSize, stream.WindowUnit)

	points, err := s.rawDataPointRepo.ListByStreamAndTimeRange(ctx, streamID, windowStart, windowEnd)
	if err != nil {
		return nil, err
	}

	if len(points) == 0 {
		return nil, nil
	}

	metrics := make(map[string][]float64)
	for _, point := range points {
		metrics[point.Metric] = append(metrics[point.Metric], point.Value)
	}

	var lastResult *model.AggregatedData
	for metric, values := range metrics {
		result := s.calculateAggregation(values, stream.AggregationStrategy)

		aggregatedData := &model.AggregatedData{
			ID:              utils.GenerateID("agg"),
			StreamID:        streamID,
			Metric:          metric,
			WindowStart:     windowStart,
			WindowEnd:       windowEnd,
			WindowSize:      stream.WindowSize,
			WindowUnit:      stream.WindowUnit,
			Count:           result.Count,
			Min:             result.Min,
			Max:             result.Max,
			Avg:             result.Avg,
			Sum:             result.Sum,
			P50:             result.P50,
			P95:             result.P95,
			P99:             result.P99,
			StandardDev:     s.calculateStdDev(values),
			DataPoints:      int64(len(values)),
			CompressionRate: float64(len(values)),
			CreatedAt:       utils.Now(),
		}

		if err := s.aggregatedDataRepo.Create(ctx, aggregatedData); err != nil {
			return nil, err
		}

		s.eventPublisher.PublishAggregationCompleted(ctx, streamID, metric, result.Avg)
		lastResult = aggregatedData
	}

	return lastResult, nil
}

func (s *Service) AggregateDataAsync(ctx context.Context, streamID string, callback aggregation.AggregationCallback) (*aggregation.AggregationTask, error) {
	if s.asyncManager == nil {
		return nil, errors.New("async manager not configured")
	}

	task, err := s.asyncManager.SubmitTask(ctx, streamID, "")
	if err != nil {
		return nil, err
	}

	if callback != nil {
		_ = s.asyncManager.RegisterCallback(task.TaskID, callback)
	}

	return task, nil
}

func (s *Service) GetTaskStatus(ctx context.Context, taskID string) (*aggregation.AggregationTask, error) {
	if s.asyncManager == nil {
		return nil, errors.New("async manager not configured")
	}

	task, exists := s.asyncManager.GetTask(ctx, taskID)
	if !exists {
		return nil, errors.New("task not found")
	}

	return task, nil
}

func (s *Service) CancelTask(ctx context.Context, taskID string) error {
	if s.asyncManager == nil {
		return errors.New("async manager not configured")
	}

	if !s.asyncManager.CancelTask(ctx, taskID) {
		return errors.New("task not found or cannot be cancelled")
	}

	return nil
}

func (s *Service) WaitForTask(ctx context.Context, taskID string, timeout time.Duration) (*aggregation.AggregationTask, error) {
	if s.asyncManager == nil {
		return nil, errors.New("async manager not configured")
	}

	deadline := time.Now().Add(timeout)
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(timeout):
			task, _ := s.asyncManager.GetTask(ctx, taskID)
			return task, errors.New("timeout waiting for task")
		case <-ticker.C:
			task, exists := s.asyncManager.GetTask(ctx, taskID)
			if !exists {
				return nil, errors.New("task not found")
			}
			if task.Status == aggregation.TaskStatusCompleted || task.Status == aggregation.TaskStatusFailed || task.Status == aggregation.TaskStatusCancelled {
				return task, nil
			}
			if time.Now().After(deadline) {
				return task, errors.New("timeout waiting for task")
			}
		}
	}
}

func (s *Service) calculateWindowStart(endTime time.Time, windowSize int, windowUnit string) time.Time {
	switch windowUnit {
	case "second":
		return endTime.Add(-time.Duration(windowSize) * time.Second)
	case "minute":
		return endTime.Add(-time.Duration(windowSize) * time.Minute)
	case "hour":
		return endTime.Add(-time.Duration(windowSize) * time.Hour)
	case "day":
		return endTime.AddDate(0, 0, -windowSize)
	default:
		return endTime.Add(-time.Duration(windowSize) * time.Minute)
	}
}

func (s *Service) calculateAggregation(values []float64, strategy string) aggregation.AggregateResult {
	if len(values) == 0 {
		return aggregation.AggregateResult{}
	}

	sort.Float64s(values)

	min := values[0]
	max := values[len(values)-1]
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	avg := sum / float64(len(values))

	result := aggregation.AggregateResult{
		Min:   min,
		Max:   max,
		Avg:   avg,
		Sum:   sum,
		Count: len(values),
		P50:   s.percentile(values, 50),
		P95:   s.percentile(values, 95),
		P99:   s.percentile(values, 99),
	}

	return result
}

func (s *Service) percentile(sortedValues []float64, p int) float64 {
	if len(sortedValues) == 0 {
		return 0
	}
	index := int(math.Ceil(float64(p)/100.0 * float64(len(sortedValues))))
	if index >= len(sortedValues) {
		index = len(sortedValues) - 1
	}
	return sortedValues[index]
}

func (s *Service) calculateStdDev(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}

	var sum float64
	for _, v := range values {
		sum += v
	}
	mean := sum / float64(len(values))

	var variance float64
	for _, v := range values {
		variance += math.Pow(v-mean, 2)
	}
	variance /= float64(len(values))

	return math.Sqrt(variance)
}

func (s *Service) GetAggregatedData(ctx context.Context, streamID string, startTime, endTime time.Time, page, pageSize int) ([]model.AggregatedData, int64, error) {
	return s.aggregatedDataRepo.ListByStreamID(ctx, streamID, startTime, endTime, page, pageSize)
}

func (s *Service) GetLatestAggregatedData(ctx context.Context, streamID, metric string) (*model.AggregatedData, error) {
	return s.aggregatedDataRepo.GetLatest(ctx, streamID, metric)
}

func (s *Service) GetStreamStats(ctx context.Context, streamID string) (*aggregation.StreamStats, error) {
	stream, err := s.dataStreamRepo.GetByID(ctx, streamID)
	if err != nil {
		return nil, err
	}

	endTime := utils.Now()
	startTime := endTime.AddDate(0, 0, -7)

	points, err := s.rawDataPointRepo.ListByStreamAndTimeRange(ctx, streamID, startTime, endTime)
	if err != nil {
		return nil, err
	}

	aggregatedData, _, err := s.aggregatedDataRepo.ListByStreamID(ctx, streamID, startTime, endTime, 1, 10000)
	if err != nil {
		return nil, err
	}

	var lastAggregated string
	if len(aggregatedData) > 0 {
		lastAggregated = aggregatedData[0].CreatedAt.Format(time.RFC3339)
	}

	rawSize := int64(len(points) * 100)
	aggSize := int64(len(aggregatedData) * 150)
	compressionRate := 0.0
	dataReduction := 0.0

	if rawSize > 0 {
		compressionRate = float64(rawSize) / float64(aggSize+1)
		dataReduction = (1 - float64(aggSize)/float64(rawSize)) * 100
	}

	stats := &aggregation.StreamStats{
		StreamID:        streamID,
		TotalPoints:     int64(len(points)),
		TotalAggregated: int64(len(aggregatedData)),
		CompressionRate: compressionRate,
		DataReduction:   dataReduction,
		LastAggregated:  lastAggregated,
	}

	_ = stream
	return stats, nil
}

type eventPublisher struct {
	cache *cache.Cache
}

func NewEventPublisher(cache *cache.Cache) *eventPublisher {
	return &eventPublisher{cache: cache}
}

func (p *eventPublisher) PublishDataIngested(ctx context.Context, streamID, deviceID string) {
	if p.cache == nil {
		return
	}

	event := map[string]interface{}{
		"event":     "data.ingested",
		"stream_id": streamID,
		"device_id": deviceID,
		"timestamp": time.Now().Unix(),
	}

	_ = p.cache.Publish(ctx, fmt.Sprintf("agg:ingest:%s", deviceID), utils.ToJSON(event))
}

func (p *eventPublisher) PublishAggregationCompleted(ctx context.Context, streamID, metric string, value float64) {
	if p.cache == nil {
		return
	}

	event := map[string]interface{}{
		"event":     "aggregation.completed",
		"stream_id": streamID,
		"metric":    metric,
		"value":     value,
		"timestamp": time.Now().Unix(),
	}

	_ = p.cache.Publish(ctx, fmt.Sprintf("agg:complete:%s", streamID), utils.ToJSON(event))
}

func (p *eventPublisher) PublishThresholdExceeded(ctx context.Context, streamID, metric string, value, threshold float64) {
	if p.cache == nil {
		return
	}

	event := map[string]interface{}{
		"event":     "threshold.exceeded",
		"stream_id": streamID,
		"metric":    metric,
		"value":     value,
		"threshold": threshold,
		"timestamp": time.Now().Unix(),
	}

	_ = p.cache.Publish(ctx, fmt.Sprintf("agg:alert:%s", streamID), utils.ToJSON(event))
}
