package metrics

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"observability-platform/pkg/models"
)

type StorageEngine interface {
	Write(ctx context.Context, metric models.Metric, value float64, timestamp time.Time) error
	WriteBatch(ctx context.Context, points []struct {
		Metric    models.Metric
		Value     float64
		Timestamp time.Time
	}) error
	Query(ctx context.Context, metricName string, labels map[string]string, start, end time.Time) ([]models.TimeSeriesDataPoint, error)
	QueryRange(ctx context.Context, metricName string, labels map[string]string, start, end time.Time, step time.Duration) ([]models.TimeSeriesDataPoint, error)
	Close() error
}

type InMemoryStorage struct {
	data      map[string][]models.TimeSeriesDataPoint
	mu        sync.RWMutex
	retention time.Duration
	maxPoints int
}

func NewInMemoryStorage(retention time.Duration, maxPoints int) *InMemoryStorage {
	if retention <= 0 {
		retention = time.Hour * 24
	}
	if maxPoints <= 0 {
		maxPoints = 100000
	}

	s := &InMemoryStorage{
		data:      make(map[string][]models.TimeSeriesDataPoint),
		retention: retention,
		maxPoints: maxPoints,
	}

	go s.cleanupLoop()
	return s
}

func (s *InMemoryStorage) getSeriesKey(metricName string, labels map[string]string) string {
	key := metricName
	for k, v := range labels {
		key += fmt.Sprintf("|%s=%s", k, v)
	}
	return key
}

func (s *InMemoryStorage) Write(ctx context.Context, metric models.Metric, value float64, timestamp time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	key := s.getSeriesKey(metric.Name, metric.Labels)
	dp := models.TimeSeriesDataPoint{
		Timestamp: timestamp,
		Value:     value,
		Labels:    metric.Labels,
	}

	s.data[key] = append(s.data[key], dp)
	if len(s.data[key]) > s.maxPoints {
		s.data[key] = s.data[key][len(s.data[key])-s.maxPoints:]
	}

	return nil
}

func (s *InMemoryStorage) WriteBatch(ctx context.Context, points []struct {
	Metric    models.Metric
	Value     float64
	Timestamp time.Time
}) error {
	if len(points) == 0 {
		return nil
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	for _, p := range points {
		key := s.getSeriesKey(p.Metric.Name, p.Metric.Labels)
		dp := models.TimeSeriesDataPoint{
			Timestamp: p.Timestamp,
			Value:     p.Value,
			Labels:    p.Metric.Labels,
		}
		s.data[key] = append(s.data[key], dp)
		if len(s.data[key]) > s.maxPoints {
			s.data[key] = s.data[key][len(s.data[key])-s.maxPoints:]
		}
	}

	return nil
}

func (s *InMemoryStorage) Query(ctx context.Context, metricName string, labels map[string]string, start, end time.Time) ([]models.TimeSeriesDataPoint, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	key := s.getSeriesKey(metricName, labels)
	points, exists := s.data[key]
	if !exists {
		return []models.TimeSeriesDataPoint{}, nil
	}

	result := make([]models.TimeSeriesDataPoint, 0, len(points))
	for _, dp := range points {
		if (start.IsZero() || !dp.Timestamp.Before(start)) &&
			(end.IsZero() || !dp.Timestamp.After(end)) {
			result = append(result, dp)
		}
	}

	return result, nil
}

func (s *InMemoryStorage) QueryRange(ctx context.Context, metricName string, labels map[string]string, start, end time.Time, step time.Duration) ([]models.TimeSeriesDataPoint, error) {
	points, err := s.Query(ctx, metricName, labels, start, end)
	if err != nil {
		return nil, err
	}

	if step <= 0 || len(points) == 0 {
		return points, nil
	}

	result := make([]models.TimeSeriesDataPoint, 0)
	currentBucket := start.Truncate(step)
	bucketValues := make([]float64, 0)

	for _, dp := range points {
		bucket := dp.Timestamp.Truncate(step)
		if bucket.After(currentBucket) && len(bucketValues) > 0 {
			avg := 0.0
			for _, v := range bucketValues {
				avg += v
			}
			avg /= float64(len(bucketValues))
			result = append(result, models.TimeSeriesDataPoint{
				Timestamp: currentBucket,
				Value:     avg,
				Labels:    labels,
			})
			currentBucket = bucket
			bucketValues = []float64{dp.Value}
		} else {
			bucketValues = append(bucketValues, dp.Value)
		}
	}

	if len(bucketValues) > 0 {
		avg := 0.0
		for _, v := range bucketValues {
			avg += v
		}
		avg /= float64(len(bucketValues))
		result = append(result, models.TimeSeriesDataPoint{
			Timestamp: currentBucket,
			Value:     avg,
			Labels:    labels,
		})
	}

	return result, nil
}

func (s *InMemoryStorage) cleanupLoop() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		s.cleanup()
	}
}

func (s *InMemoryStorage) cleanup() {
	s.mu.Lock()
	defer s.mu.Unlock()

	cutoff := time.Now().Add(-s.retention)

	for key, points := range s.data {
		filtered := make([]models.TimeSeriesDataPoint, 0, len(points))
		for _, dp := range points {
			if dp.Timestamp.After(cutoff) {
				filtered = append(filtered, dp)
			}
		}
		if len(filtered) == 0 {
			delete(s.data, key)
		} else {
			s.data[key] = filtered
		}
	}
}

func (s *InMemoryStorage) Close() error {
	return nil
}

func (s *InMemoryStorage) GetAllMetrics() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	metrics := make([]string, 0, len(s.data))
	for key := range s.data {
		metrics = append(metrics, key)
	}
	return metrics
}

type StorageType string

const (
	StorageTypeInMemory   StorageType = "inmemory"
	StorageTypePrometheus StorageType = "prometheus"
	StorageTypeInfluxDB   StorageType = "influxdb"
	StorageTypeClickHouse StorageType = "clickhouse"
)

type StorageConfig struct {
	Type       StorageType
	InMemory   *InMemoryStorageConfig
	Prometheus *PrometheusStorageConfig
	InfluxDB   *InfluxDBStorageConfig
	ClickHouse *ClickHouseStorageConfig
}

type InMemoryStorageConfig struct {
	Retention time.Duration
	MaxPoints int
}

type PrometheusStorageConfig struct {
	Address string
	Timeout time.Duration
}

type InfluxDBStorageConfig struct {
	Address  string
	Token    string
	Org      string
	Bucket   string
	Timeout  time.Duration
}

type ClickHouseStorageConfig struct {
	Address  string
	Database string
	Table    string
	Username string
	Password string
	Timeout  time.Duration
}

func NewStorageEngine(config StorageConfig) (StorageEngine, error) {
	switch config.Type {
	case StorageTypeInMemory:
		inMemConfig := config.InMemory
		if inMemConfig == nil {
			inMemConfig = &InMemoryStorageConfig{}
		}
		return NewInMemoryStorage(inMemConfig.Retention, inMemConfig.MaxPoints), nil

	case StorageTypePrometheus:
		return nil, errors.New("prometheus storage not implemented yet")

	case StorageTypeInfluxDB:
		return nil, errors.New("influxdb storage not implemented yet")

	case StorageTypeClickHouse:
		return nil, errors.New("clickhouse storage not implemented yet")

	default:
		return nil, fmt.Errorf("unknown storage type: %s", config.Type)
	}
}

type PendingWrite struct {
	Metric    models.Metric
	Value     float64
	Timestamp time.Time
}

type MetricsService struct {
	aggregator    *MetricAggregator
	storage       StorageEngine
	mu            sync.Mutex
	pendingWrites []PendingWrite
	pendingMu     sync.Mutex
	walEnabled    bool
}

func NewMetricsService(aggConfig AggregationConfig, storageConfig StorageConfig) (*MetricsService, error) {
	storage, err := NewStorageEngine(storageConfig)
	if err != nil {
		return nil, err
	}

	return &MetricsService{
		aggregator:    NewMetricAggregator(aggConfig),
		storage:       storage,
		pendingWrites: make([]PendingWrite, 0),
		walEnabled:    true,
	}, nil
}

func (s *MetricsService) RecordMetric(metric models.Metric, value float64) error {
	now := time.Now()
	return s.recordMetricAtomic(metric, value, now)
}

func (s *MetricsService) RecordMetricWithTime(metric models.Metric, value float64, timestamp time.Time) error {
	return s.recordMetricAtomic(metric, value, timestamp)
}

func (s *MetricsService) recordMetricAtomic(metric models.Metric, value float64, timestamp time.Time) error {
	if s.walEnabled {
		s.pendingMu.Lock()
		s.pendingWrites = append(s.pendingWrites, PendingWrite{
			Metric:    metric,
			Value:     value,
			Timestamp: timestamp,
		})
		s.pendingMu.Unlock()
	}

	if err := s.storage.Write(context.Background(), metric, value, timestamp); err != nil {
		if s.walEnabled {
			s.pendingMu.Lock()
			for i := len(s.pendingWrites) - 1; i >= 0; i-- {
				if s.pendingWrites[i].Metric.Name == metric.Name &&
					s.pendingWrites[i].Value == value &&
					s.pendingWrites[i].Timestamp.Equal(timestamp) {
					s.pendingWrites = append(s.pendingWrites[:i], s.pendingWrites[i+1:]...)
					break
				}
			}
			s.pendingMu.Unlock()
		}
		return fmt.Errorf("storage write failed for metric %s: %w", metric.Name, err)
	}

	s.aggregator.AddDataPoint(metric, value, timestamp)

	if s.walEnabled {
		s.pendingMu.Lock()
		for i := len(s.pendingWrites) - 1; i >= 0; i-- {
			if s.pendingWrites[i].Metric.Name == metric.Name &&
				s.pendingWrites[i].Value == value &&
				s.pendingWrites[i].Timestamp.Equal(timestamp) {
				s.pendingWrites = append(s.pendingWrites[:i], s.pendingWrites[i+1:]...)
				break
			}
		}
		s.pendingMu.Unlock()
	}

	return nil
}

func (s *MetricsService) FlushPendingWrites() (int, error) {
	s.pendingMu.Lock()
	pending := make([]PendingWrite, len(s.pendingWrites))
	copy(pending, s.pendingWrites)
	s.pendingWrites = s.pendingWrites[:0]
	s.pendingMu.Unlock()

	if len(pending) == 0 {
		return 0, nil
	}

	failed := make([]PendingWrite, 0)
	flushed := 0

	for _, pw := range pending {
		if err := s.storage.Write(context.Background(), pw.Metric, pw.Value, pw.Timestamp); err != nil {
			failed = append(failed, pw)
			continue
		}
		s.aggregator.AddDataPoint(pw.Metric, pw.Value, pw.Timestamp)
		flushed++
	}

	if len(failed) > 0 {
		s.pendingMu.Lock()
		s.pendingWrites = append(s.pendingWrites, failed...)
		s.pendingMu.Unlock()
		return flushed, fmt.Errorf("%d writes failed out of %d", len(failed), len(pending))
	}

	return flushed, nil
}

func (s *MetricsService) GetPendingWriteCount() int {
	s.pendingMu.Lock()
	defer s.pendingMu.Unlock()
	return len(s.pendingWrites)
}

func (s *MetricsService) QueryMetric(metricName string, labels map[string]string, start, end time.Time) ([]models.TimeSeriesDataPoint, error) {
	return s.storage.Query(context.Background(), metricName, labels, start, end)
}

func (s *MetricsService) QueryMetricRange(metricName string, labels map[string]string, start, end time.Time, step time.Duration) ([]models.TimeSeriesDataPoint, error) {
	return s.storage.QueryRange(context.Background(), metricName, labels, start, end, step)
}

func (s *MetricsService) ForceAggregation() {
	s.aggregator.AggregateAtomic()
}

func (s *MetricsService) GetAggregatedSeries(metricName string, labels map[string]string, aggType AggregationType) ([]models.TimeSeriesDataPoint, error) {
	return s.aggregator.GetSeries(metricName, labels, aggType, TimeWindow{})
}

func (s *MetricsService) GetStats() map[string]interface{} {
	stats := s.aggregator.GetStats()
	s.pendingMu.Lock()
	stats["pending_writes"] = len(s.pendingWrites)
	s.pendingMu.Unlock()
	return stats
}

func (s *MetricsService) Close() error {
	if _, err := s.FlushPendingWrites(); err != nil {
		return fmt.Errorf("flush pending writes on close: %w", err)
	}
	return s.storage.Close()
}

func (s *MetricsService) MarshalJSON() ([]byte, error) {
	stats := s.GetStats()
	return json.Marshal(stats)
}
