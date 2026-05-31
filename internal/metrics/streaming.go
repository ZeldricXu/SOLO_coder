package metrics

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"observability-platform/pkg/models"
)

type StreamProcessorState string

const (
	StateRunning   StreamProcessorState = "running"
	StatePaused    StreamProcessorState = "paused"
	StateStopped   StreamProcessorState = "stopped"
)

type StreamingConfig struct {
	ChannelBufferSize  int
	BatchSize          int
	FlushInterval      time.Duration
	MaxRetries         int
	RetryBackoff       time.Duration
	EnableBackpressure bool
	HighWatermark      int
	LowWatermark       int
}

func DefaultStreamingConfig() StreamingConfig {
	return StreamingConfig{
		ChannelBufferSize:  10000,
		BatchSize:          1000,
		FlushInterval:      time.Second,
		MaxRetries:         3,
		RetryBackoff:       time.Millisecond * 100,
		EnableBackpressure: true,
		HighWatermark:      8000,
		LowWatermark:       2000,
	}
}

type StreamMetricPoint struct {
	Metric    models.Metric
	Value     float64
	Timestamp time.Time
	Attempts  int
}

type StreamProcessor struct {
	config        StreamingConfig
	aggregator    *MetricAggregator
	storage       StorageEngine
	ingestChan    chan StreamMetricPoint
	batch         []StreamMetricPoint
	lastFlush     time.Time
	state         StreamProcessorState
	stateMu       sync.RWMutex
	ctx           context.Context
	cancel        context.CancelFunc
	wg            sync.WaitGroup
	droppedCount  int64
	processedCount int64
	errorCount    int64
	batchMu       sync.Mutex
	flowControl   *FlowController
}

type FlowController struct {
	enabled        bool
	highWatermark  int
	lowWatermark   int
	isBlocked      bool
	blockedCount   int64
	unblockedCount int64
	mu             sync.RWMutex
}

func NewFlowController(high, low int) *FlowController {
	return &FlowController{
		enabled:       true,
		highWatermark: high,
		lowWatermark:  low,
		isBlocked:     false,
	}
}

func (fc *FlowController) Check(queueSize int) bool {
	if !fc.enabled {
		return true
	}

	fc.mu.Lock()
	defer fc.mu.Unlock()

	if fc.isBlocked {
		if queueSize <= fc.lowWatermark {
			fc.isBlocked = false
			fc.unblockedCount++
			return true
		}
		return false
	}

	if queueSize >= fc.highWatermark {
		fc.isBlocked = true
		fc.blockedCount++
		return false
	}

	return true
}

func (fc *FlowController) Stats() map[string]interface{} {
	fc.mu.RLock()
	defer fc.mu.RUnlock()

	return map[string]interface{}{
		"enabled":         fc.enabled,
		"high_watermark":  fc.highWatermark,
		"low_watermark":   fc.lowWatermark,
		"is_blocked":      fc.isBlocked,
		"blocked_count":   fc.blockedCount,
		"unblocked_count": fc.unblockedCount,
	}
}

func NewStreamProcessor(
	aggregator *MetricAggregator,
	storage StorageEngine,
	config StreamingConfig,
) *StreamProcessor {
	if config.ChannelBufferSize <= 0 {
		config.ChannelBufferSize = 10000
	}
	if config.BatchSize <= 0 {
		config.BatchSize = 1000
	}
	if config.FlushInterval <= 0 {
		config.FlushInterval = time.Second
	}
	if config.MaxRetries <= 0 {
		config.MaxRetries = 3
	}
	if config.RetryBackoff <= 0 {
		config.RetryBackoff = time.Millisecond * 100
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &StreamProcessor{
		config:      config,
		aggregator:  aggregator,
		storage:     storage,
		ingestChan:  make(chan StreamMetricPoint, config.ChannelBufferSize),
		batch:       make([]StreamMetricPoint, 0, config.BatchSize),
		state:       StateStopped,
		ctx:         ctx,
		cancel:      cancel,
		flowControl: NewFlowController(config.HighWatermark, config.LowWatermark),
	}
}

func (sp *StreamProcessor) Start() error {
	sp.stateMu.Lock()
	if sp.state == StateRunning {
		sp.stateMu.Unlock()
		return errors.New("stream processor already running")
	}
	sp.state = StateRunning
	sp.stateMu.Unlock()

	sp.wg.Add(2)
	go sp.processLoop()
	go sp.flushLoop()

	return nil
}

func (sp *StreamProcessor) Stop() error {
	sp.stateMu.Lock()
	if sp.state != StateRunning {
		sp.stateMu.Unlock()
		return nil
	}
	sp.state = StateStopped
	sp.stateMu.Unlock()

	sp.cancel()
	close(sp.ingestChan)
	sp.wg.Wait()

	sp.batchMu.Lock()
	if len(sp.batch) > 0 {
		sp.flushBatch(sp.batch)
		sp.batch = sp.batch[:0]
	}
	sp.batchMu.Unlock()

	return nil
}

func (sp *StreamProcessor) Pause() {
	sp.stateMu.Lock()
	defer sp.stateMu.Unlock()
	if sp.state == StateRunning {
		sp.state = StatePaused
	}
}

func (sp *StreamProcessor) Resume() {
	sp.stateMu.Lock()
	defer sp.stateMu.Unlock()
	if sp.state == StatePaused {
		sp.state = StateRunning
	}
}

func (sp *StreamProcessor) GetState() StreamProcessorState {
	sp.stateMu.RLock()
	defer sp.stateMu.RUnlock()
	return sp.state
}

func (sp *StreamProcessor) Ingest(point StreamMetricPoint) error {
	sp.stateMu.RLock()
	state := sp.state
	sp.stateMu.RUnlock()

	if state != StateRunning {
		return fmt.Errorf("stream processor not running (state: %s)", state)
	}

	if point.Timestamp.IsZero() {
		point.Timestamp = time.Now()
	}

	if sp.config.EnableBackpressure {
		queueSize := len(sp.ingestChan)
		if !sp.flowControl.Check(queueSize) {
			sp.droppedCount++
			return fmt.Errorf("backpressure control: channel buffer full (%d), dropping metric", queueSize)
		}
	}

	select {
	case sp.ingestChan <- point:
		return nil
	default:
		sp.droppedCount++
		return errors.New("ingest channel full, dropping metric")
	}
}

func (sp *StreamProcessor) IngestAsync(point StreamMetricPoint) bool {
	sp.stateMu.RLock()
	state := sp.state
	sp.stateMu.RUnlock()

	if state != StateRunning {
		return false
	}

	if point.Timestamp.IsZero() {
		point.Timestamp = time.Now()
	}

	select {
	case sp.ingestChan <- point:
		return true
	default:
		sp.droppedCount++
		return false
	}
}

func (sp *StreamProcessor) processLoop() {
	defer sp.wg.Done()

	for {
		select {
		case <-sp.ctx.Done():
			return
		case point, ok := <-sp.ingestChan:
			if !ok {
				return
			}

			sp.stateMu.RLock()
			state := sp.state
			sp.stateMu.RUnlock()

			if state == StatePaused {
				continue
			}

			sp.batchMu.Lock()
			sp.batch = append(sp.batch, point)
			shouldFlush := len(sp.batch) >= sp.config.BatchSize
			sp.batchMu.Unlock()

			if shouldFlush {
				sp.manualFlush()
			}
		}
	}
}

func (sp *StreamProcessor) flushLoop() {
	defer sp.wg.Done()

	ticker := time.NewTicker(sp.config.FlushInterval)
	defer ticker.Stop()

	for {
		select {
		case <-sp.ctx.Done():
			return
		case <-ticker.C:
			sp.manualFlush()
		}
	}
}

func (sp *StreamProcessor) manualFlush() {
	sp.batchMu.Lock()
	if len(sp.batch) == 0 {
		sp.batchMu.Unlock()
		return
	}

	batch := make([]StreamMetricPoint, len(sp.batch))
	copy(batch, sp.batch)
	sp.batch = sp.batch[:0]
	sp.lastFlush = time.Now()
	sp.batchMu.Unlock()

	sp.flushBatch(batch)
}

func (sp *StreamProcessor) flushBatch(batch []StreamMetricPoint) {
	if len(batch) == 0 {
		return
	}

	points := make([]struct {
		Metric    models.Metric
		Value     float64
		Timestamp time.Time
	}, 0, len(batch))

	for _, p := range batch {
		points = append(points, struct {
			Metric    models.Metric
			Value     float64
			Timestamp time.Time
		}{
			Metric:    p.Metric,
			Value:     p.Value,
			Timestamp: p.Timestamp,
		})
	}

	err := sp.storage.WriteBatch(sp.ctx, points)
	if err != nil {
		sp.errorCount++
		sp.retryBatch(batch)
		return
	}

	for _, p := range batch {
		sp.aggregator.AddDataPoint(p.Metric, p.Value, p.Timestamp)
	}

	sp.processedCount += int64(len(batch))
}

func (sp *StreamProcessor) retryBatch(batch []StreamMetricPoint) {
	retryBatch := make([]StreamMetricPoint, 0, len(batch))

	for _, p := range batch {
		p.Attempts++
		if p.Attempts < sp.config.MaxRetries {
			retryBatch = append(retryBatch, p)
		} else {
			sp.droppedCount++
		}
	}

	if len(retryBatch) > 0 {
		time.Sleep(sp.config.RetryBackoff)
		go func() {
			for _, p := range retryBatch {
				select {
				case sp.ingestChan <- p:
				case <-sp.ctx.Done():
					return
				}
			}
		}()
	}
}

func (sp *StreamProcessor) ForceFlush() int {
	sp.batchMu.Lock()
	batchLen := len(sp.batch)
	if batchLen > 0 {
		batch := make([]StreamMetricPoint, batchLen)
		copy(batch, sp.batch)
		sp.batch = sp.batch[:0]
		sp.batchMu.Unlock()
		sp.flushBatch(batch)
		return batchLen
	}
	sp.batchMu.Unlock()
	return 0
}

func (sp *StreamProcessor) GetStats() map[string]interface{} {
	sp.stateMu.RLock()
	state := sp.state
	sp.stateMu.RUnlock()

	sp.batchMu.Lock()
	pendingBatch := len(sp.batch)
	lastFlush := sp.lastFlush
	sp.batchMu.Unlock()

	return map[string]interface{}{
		"state":            state,
		"channel_size":     len(sp.ingestChan),
		"channel_cap":      cap(sp.ingestChan),
		"pending_batch":    pendingBatch,
		"processed_count":  sp.processedCount,
		"dropped_count":    sp.droppedCount,
		"error_count":      sp.errorCount,
		"last_flush":       lastFlush,
		"batch_size":       sp.config.BatchSize,
		"flush_interval":   sp.config.FlushInterval.String(),
		"flow_control":     sp.flowControl.Stats(),
	}
}

func (sp *StreamProcessor) GetAggregator() *MetricAggregator {
	return sp.aggregator
}

func (sp *StreamProcessor) GetStorage() StorageEngine {
	return sp.storage
}

type StreamMetricsService struct {
	*MetricsService
	processor *StreamProcessor
}

func NewStreamMetricsService(
	aggConfig AggregationConfig,
	storageConfig StorageConfig,
	streamConfig StreamingConfig,
) (*StreamMetricsService, error) {
	baseService, err := NewMetricsService(aggConfig, storageConfig)
	if err != nil {
		return nil, err
	}

	processor := NewStreamProcessor(
		baseService.aggregator,
		baseService.storage,
		streamConfig,
	)

	return &StreamMetricsService{
		MetricsService: baseService,
		processor:      processor,
	}, nil
}

func (s *StreamMetricsService) Start() error {
	return s.processor.Start()
}

func (s *StreamMetricsService) Stop() error {
	err := s.processor.Stop()
	if err != nil {
		return err
	}
	return s.MetricsService.Close()
}

func (s *StreamMetricsService) RecordMetricAsync(metric models.Metric, value float64) bool {
	return s.processor.IngestAsync(StreamMetricPoint{
		Metric:    metric,
		Value:     value,
		Timestamp: time.Now(),
	})
}

func (s *StreamMetricsService) RecordMetricAsyncWithTime(metric models.Metric, value float64, ts time.Time) bool {
	return s.processor.IngestAsync(StreamMetricPoint{
		Metric:    metric,
		Value:     value,
		Timestamp: ts,
	})
}

func (s *StreamMetricsService) GetStreamingStats() map[string]interface{} {
	return s.processor.GetStats()
}

func (s *StreamMetricsService) ForceFlush() int {
	return s.processor.ForceFlush()
}
