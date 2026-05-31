package metrics_aggregation

import (
	"context"
	"sync"
	"time"

	"loglevelplatform/internal/common/database"
	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"
	"loglevelplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type AggregationType string

const (
	AggSum   AggregationType = "sum"
	AggAvg   AggregationType = "avg"
	AggMin   AggregationType = "min"
	AggMax   AggregationType = "max"
	AggCount AggregationType = "count"
	AggP50   AggregationType = "p50"
	AggP95   AggregationType = "p95"
	AggP99   AggregationType = "p99"
)

type PreAggregator struct {
	name        string
	aggType     AggregationType
	window      time.Duration
	dataPoints  []float64
	lastAggTime time.Time
	mu          sync.Mutex
}

type StorageEngine interface {
	Write(ctx context.Context, metric *models.MetricPoint) error
	WriteBatch(ctx context.Context, metrics []*models.MetricPoint) error
	Query(ctx context.Context, metricName string, tags map[string]string, startTime, endTime time.Time) ([]*models.MetricPoint, error)
}

type MemoryStorage struct {
	data map[string][]*models.MetricPoint
	mu   sync.RWMutex
}

type Service struct {
	db              *gorm.DB
	preAggregators  map[string]*PreAggregator
	storageEngines  []StorageEngine
	memoryStorage   *MemoryStorage
	ingestChan      chan *models.MetricPoint
	batchSize       int
	flushInterval   time.Duration
	running         bool
	stopChan        chan struct{}
	mu              sync.RWMutex
}

var (
	instance *Service
	once     sync.Once
)

func NewService() *Service {
	once.Do(func() {
		instance = &Service{
			db:             database.GetDB(),
			preAggregators: make(map[string]*PreAggregator),
			storageEngines: make([]StorageEngine, 0),
			memoryStorage: &MemoryStorage{
				data: make(map[string][]*models.MetricPoint),
			},
			ingestChan:    make(chan *models.MetricPoint, 10000),
			batchSize:     1000,
			flushInterval: 5 * time.Second,
			stopChan:      make(chan struct{}),
		}
		instance.storageEngines = append(instance.storageEngines, instance.memoryStorage)
	})
	return instance
}

func (s *Service) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	s.mu.Unlock()

	go s.ingestLoop()
	go s.aggregationLoop()
	logger.Info("metrics aggregation service started")
}

func (s *Service) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		s.running = false
		close(s.stopChan)
		close(s.ingestChan)
		logger.Info("metrics aggregation service stopped")
	}
}

func (s *Service) ingestLoop() {
	batch := make([]*models.MetricPoint, 0, s.batchSize)
	ticker := time.NewTicker(s.flushInterval)
	defer ticker.Stop()

	for {
		select {
		case metric, ok := <-s.ingestChan:
			if !ok {
				if len(batch) > 0 {
					s.flushBatch(batch)
				}
				return
			}
			batch = append(batch, metric)
			if len(batch) >= s.batchSize {
				s.flushBatch(batch)
				batch = batch[:0]
			}
		case <-ticker.C:
			if len(batch) > 0 {
				s.flushBatch(batch)
				batch = batch[:0]
			}
		case <-s.stopChan:
			if len(batch) > 0 {
				s.flushBatch(batch)
			}
			return
		}
	}
}

func (s *Service) flushBatch(batch []*models.MetricPoint) {
	ctx := context.Background()
	for _, engine := range s.storageEngines {
		if err := engine.WriteBatch(ctx, batch); err != nil {
			logger.Error("failed to write batch to storage", zap.Error(err))
		}
	}

	for _, metric := range batch {
		s.preAggregate(metric)
	}
}

func (s *Service) aggregationLoop() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			s.runPreAggregation()
		case <-s.stopChan:
			return
		}
	}
}

func (s *Service) RegisterPreAggregator(name string, aggType AggregationType, window time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.preAggregators[name] = &PreAggregator{
		name:        name,
		aggType:     aggType,
		window:      window,
		dataPoints:  make([]float64, 0),
		lastAggTime: time.Now(),
	}
}

func (s *Service) preAggregate(metric *models.MetricPoint) {
	s.mu.RLock()
	agg, exists := s.preAggregators[metric.Name]
	s.mu.RUnlock()

	if !exists {
		return
	}

	agg.mu.Lock()
	agg.dataPoints = append(agg.dataPoints, metric.Value)
	agg.mu.Unlock()
}

func (s *Service) runPreAggregation() {
	s.mu.RLock()
	defer s.mu.RUnlock()

	now := time.Now()
	for name, agg := range s.preAggregators {
		agg.mu.Lock()
		if now.Sub(agg.lastAggTime) >= agg.window && len(agg.dataPoints) > 0 {
			var result float64
			switch agg.aggType {
			case AggSum:
				result = utils.Average(agg.dataPoints) * float64(len(agg.dataPoints))
			case AggAvg:
				result = utils.Average(agg.dataPoints)
			case AggMin:
				result = s.findMin(agg.dataPoints)
			case AggMax:
				result = s.findMax(agg.dataPoints)
			case AggCount:
				result = float64(len(agg.dataPoints))
			case AggP50:
				result = s.percentile(agg.dataPoints, 50)
			case AggP95:
				result = s.percentile(agg.dataPoints, 95)
			case AggP99:
				result = s.percentile(agg.dataPoints, 99)
			}

			aggMetric := &models.MetricPoint{
				Name:      name + "_" + string(agg.aggType),
				Value:     result,
				Timestamp: now.Unix(),
				Tags:      map[string]string{"aggregated": "true"},
			}

			for _, engine := range s.storageEngines {
				_ = engine.Write(context.Background(), aggMetric)
			}

			logger.Debug("pre-aggregation complete",
				zap.String("name", name),
				zap.String("type", string(agg.aggType)),
				zap.Float64("value", result),
			)

			agg.dataPoints = agg.dataPoints[:0]
			agg.lastAggTime = now
		}
		agg.mu.Unlock()
	}
}

func (s *Service) findMin(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	min := values[0]
	for _, v := range values[1:] {
		if v < min {
			min = v
		}
	}
	return min
}

func (s *Service) findMax(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	max := values[0]
	for _, v := range values[1:] {
		if v > max {
			max = v
		}
	}
	return max
}

func (s *Service) percentile(values []float64, p float64) float64 {
	if len(values) == 0 {
		return 0
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	s.sortFloat64(sorted)

	index := int(p / 100.0 * float64(len(sorted)-1))
	if index >= len(sorted) {
		index = len(sorted) - 1
	}
	return sorted[index]
}

func (s *Service) sortFloat64(arr []float64) {
	for i := 0; i < len(arr); i++ {
		for j := i + 1; j < len(arr); j++ {
			if arr[i] > arr[j] {
				arr[i], arr[j] = arr[j], arr[i]
			}
		}
	}
}

func (s *Service) Ingest(ctx context.Context, metric *models.MetricPoint) error {
	log := logger.FromContext(ctx)

	if metric.Timestamp == 0 {
		metric.Timestamp = time.Now().Unix()
	}

	select {
	case s.ingestChan <- metric:
		log.Debug("metric ingested",
			zap.String("name", metric.Name),
			zap.Float64("value", metric.Value),
		)
		return nil
	default:
		log.Warn("ingest channel full, dropping metric",
			zap.String("name", metric.Name),
		)
		return nil
	}
}

func (s *Service) IngestBatch(ctx context.Context, metrics []*models.MetricPoint) error {
	for _, metric := range metrics {
		if err := s.Ingest(ctx, metric); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) Query(ctx context.Context, metricName string, tags map[string]string, startTime, endTime time.Time) ([]*models.MetricPoint, error) {
	log := logger.FromContext(ctx)

	var allResults []*models.MetricPoint
	for _, engine := range s.storageEngines {
		results, err := engine.Query(ctx, metricName, tags, startTime, endTime)
		if err != nil {
			log.Warn("storage engine query failed", zap.Error(err))
			continue
		}
		allResults = append(allResults, results...)
	}

	return allResults, nil
}

func (s *Service) GetMetricsList(ctx context.Context) []string {
	s.memoryStorage.mu.RLock()
	defer s.memoryStorage.mu.RUnlock()

	metrics := make([]string, 0, len(s.memoryStorage.data))
	for name := range s.memoryStorage.data {
		metrics = append(metrics, name)
	}
	return metrics
}

func (ms *MemoryStorage) Write(ctx context.Context, metric *models.MetricPoint) error {
	ms.mu.Lock()
	defer ms.mu.Unlock()

	key := ms.getKey(metric.Name, metric.Tags)
	ms.data[key] = append(ms.data[key], metric)

	if len(ms.data[key]) > 10000 {
		ms.data[key] = ms.data[key][1000:]
	}
	return nil
}

func (ms *MemoryStorage) WriteBatch(ctx context.Context, metrics []*models.MetricPoint) error {
	for _, m := range metrics {
		_ = ms.Write(ctx, m)
	}
	return nil
}

func (ms *MemoryStorage) Query(ctx context.Context, metricName string, tags map[string]string, startTime, endTime time.Time) ([]*models.MetricPoint, error) {
	ms.mu.RLock()
	defer ms.mu.RUnlock()

	key := ms.getKey(metricName, tags)
	points, exists := ms.data[key]
	if !exists {
		return []*models.MetricPoint{}, nil
	}

	var results []*models.MetricPoint
	startTS := startTime.Unix()
	endTS := endTime.Unix()

	for _, p := range points {
		if (startTime.IsZero() || p.Timestamp >= startTS) &&
			(endTime.IsZero() || p.Timestamp <= endTS) {
			results = append(results, p)
		}
	}

	return results, nil
}

func (ms *MemoryStorage) getKey(name string, tags map[string]string) string {
	key := name
	for k, v := range tags {
		key += "|" + k + "=" + v
	}
	return key
}

func (s *Service) SaveSnapshot(ctx context.Context, dimensions map[string]string) (*models.StatsSnapshot, error) {
	log := logger.FromContext(ctx)

	metrics := make(map[string]float64)
	s.memoryStorage.mu.RLock()
	for name, points := range s.memoryStorage.data {
		if len(points) > 0 {
			latest := points[len(points)-1]
			metrics[name] = latest.Value
		}
	}
	s.memoryStorage.mu.RUnlock()

	snapshot := &models.StatsSnapshot{
		SnapshotID: utils.NewID("snap"),
		Timestamp:  time.Now(),
		Metrics:    metrics,
		Dimensions: dimensions,
	}

	if err := s.db.Create(snapshot).Error; err != nil {
		log.Error("failed to save snapshot", zap.Error(err))
		return nil, err
	}

	return snapshot, nil
}

func (s *Service) ListSnapshots(ctx context.Context, startTime, endTime time.Time, limit int) ([]models.StatsSnapshot, error) {
	var snapshots []models.StatsSnapshot
	query := s.db.Model(&models.StatsSnapshot{})

	if !startTime.IsZero() {
		query = query.Where("timestamp >= ?", startTime)
	}
	if !endTime.IsZero() {
		query = query.Where("timestamp <= ?", endTime)
	}

	if err := query.Order("timestamp DESC").Limit(limit).Find(&snapshots).Error; err != nil {
		return nil, err
	}
	return snapshots, nil
}
