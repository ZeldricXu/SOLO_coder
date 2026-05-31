package aggregation

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/domain/aggregation"
	"github.com/edgevision/edgevision/internal/domain/model"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/pkg/utils"
	"go.uber.org/zap"
)

type AsyncTaskManagerImpl struct {
	dataStreamRepo     aggregation.DataStreamRepository
	rawDataPointRepo   aggregation.RawDataPointRepository
	aggregatedDataRepo aggregation.AggregatedDataRepository
	eventPublisher     aggregation.EventPublisher

	tasks      map[string]*aggregation.AggregationTask
	taskQueue  chan *aggregation.AggregationTask
	callbacks  map[string][]aggregation.AggregationCallback
	workerCount int
	wg         sync.WaitGroup
	stopChan   chan struct{}
	mu         sync.RWMutex
	running    bool
}

func NewAsyncTaskManager(
	dataStreamRepo aggregation.DataStreamRepository,
	rawDataPointRepo aggregation.RawDataPointRepository,
	aggregatedDataRepo aggregation.AggregatedDataRepository,
	eventPublisher aggregation.EventPublisher,
	workerCount int,
) *AsyncTaskManagerImpl {
	if workerCount <= 0 {
		workerCount = 3
	}

	return &AsyncTaskManagerImpl{
		dataStreamRepo:     dataStreamRepo,
		rawDataPointRepo:   rawDataPointRepo,
		aggregatedDataRepo: aggregatedDataRepo,
		eventPublisher:     eventPublisher,
		tasks:              make(map[string]*aggregation.AggregationTask),
		taskQueue:          make(chan *aggregation.AggregationTask, 1000),
		callbacks:          make(map[string][]aggregation.AggregationCallback),
		workerCount:        workerCount,
		stopChan:           make(chan struct{}),
	}
}

func (m *AsyncTaskManagerImpl) SubmitTask(ctx context.Context, streamID string, metric string) (*aggregation.AggregationTask, error) {
	task := &aggregation.AggregationTask{
		TaskID:    utils.GenerateID("agg_task"),
		StreamID:  streamID,
		Metric:    metric,
		Status:    aggregation.TaskStatusPending,
		Progress:  0,
		CreatedAt: time.Now(),
		Context:   ctx,
	}

	m.mu.Lock()
	m.tasks[task.TaskID] = task
	m.mu.Unlock()

	select {
	case m.taskQueue <- task:
		logger.Get().Info("Aggregation task submitted", zap.String("task_id", task.TaskID), zap.String("stream_id", streamID))
		return task, nil
	default:
		m.mu.Lock()
		delete(m.tasks, task.TaskID)
		m.mu.Unlock()
		return nil, errors.New("task queue is full")
	}
}

func (m *AsyncTaskManagerImpl) GetTask(ctx context.Context, taskID string) (*aggregation.AggregationTask, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	task, exists := m.tasks[taskID]
	if !exists {
		return nil, false
	}

	taskCopy := *task
	return &taskCopy, true
}

func (m *AsyncTaskManagerImpl) ListTasks(ctx context.Context, streamID string, status aggregation.AsyncTaskStatus) []*aggregation.AggregationTask {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var tasks []*aggregation.AggregationTask
	for _, task := range m.tasks {
		if streamID != "" && task.StreamID != streamID {
			continue
		}
		if status != "" && task.Status != status {
			continue
		}
		taskCopy := *task
		tasks = append(tasks, &taskCopy)
	}
	return tasks
}

func (m *AsyncTaskManagerImpl) CancelTask(ctx context.Context, taskID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	task, exists := m.tasks[taskID]
	if !exists {
		return false
	}

	if task.Status == aggregation.TaskStatusRunning || task.Status == aggregation.TaskStatusPending {
		task.Status = aggregation.TaskStatusCancelled
		return true
	}
	return false
}

func (m *AsyncTaskManagerImpl) RegisterCallback(taskID string, callback aggregation.AggregationCallback) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	_, exists := m.tasks[taskID]
	if !exists {
		return errors.New("task not found")
	}

	m.callbacks[taskID] = append(m.callbacks[taskID], callback)
	return nil
}

func (m *AsyncTaskManagerImpl) Start(ctx context.Context) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.running {
		return
	}

	m.running = true

	for i := 0; i < m.workerCount; i++ {
		m.wg.Add(1)
		go m.worker(ctx, i)
	}

	logger.Get().Info("Async aggregation manager started", zap.Int("workers", m.workerCount))
}

func (m *AsyncTaskManagerImpl) Stop() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.running {
		return
	}

	m.running = false
	close(m.stopChan)
	m.wg.Wait()
	close(m.taskQueue)

	logger.Get().Info("Async aggregation manager stopped")
}

func (m *AsyncTaskManagerImpl) worker(ctx context.Context, workerID int) {
	defer m.wg.Done()

	logger.Get().Debug("Aggregation worker started", zap.Int("worker_id", workerID))

	for {
		select {
		case <-m.stopChan:
			logger.Get().Debug("Aggregation worker stopped", zap.Int("worker_id", workerID))
			return
		case task, ok := <-m.taskQueue:
			if !ok {
				return
			}
			m.processTask(ctx, task, workerID)
		}
	}
}

func (m *AsyncTaskManagerImpl) processTask(ctx context.Context, task *aggregation.AggregationTask, workerID int) {
	m.mu.Lock()
	if task.Status != aggregation.TaskStatusPending {
		m.mu.Unlock()
		return
	}
	now := time.Now()
	task.Status = aggregation.TaskStatusRunning
	task.StartedAt = &now
	task.Progress = 0.1
	m.mu.Unlock()

	m.notifyProgress(task)
	m.eventPublisher.PublishDataIngested(ctx, task.StreamID, "")

	stream, err := m.dataStreamRepo.GetByID(ctx, task.StreamID)
	if err != nil {
		m.failTask(task, fmt.Errorf("stream not found: %w", err))
		return
	}

	windowEnd := time.Now()
	windowStart := m.calculateWindowStart(windowEnd, stream.WindowSize, stream.WindowUnit)

	points, err := m.rawDataPointRepo.ListByStreamAndTimeRange(ctx, task.StreamID, windowStart, windowEnd)
	if err != nil {
		m.failTask(task, fmt.Errorf("failed to load data points: %w", err))
		return
	}

	m.mu.Lock()
	task.Progress = 0.5
	m.mu.Unlock()
	m.notifyProgress(task)

	if len(points) == 0 {
		m.completeTask(task, nil)
		return
	}

	metrics := make(map[string][]float64)
	for _, point := range points {
		metrics[point.Metric] = append(metrics[point.Metric], point.Value)
	}

	var result *model.AggregatedData
	for metric, values := range metrics {
		if task.Metric != "" && task.Metric != metric {
			continue
		}

		aggResult := m.calculateAggregation(values, stream.AggregationStrategy)

		aggregatedData := &model.AggregatedData{
			ID:              utils.GenerateID("agg"),
			StreamID:        task.StreamID,
			Metric:          metric,
			WindowStart:     windowStart,
			WindowEnd:       windowEnd,
			WindowSize:      stream.WindowSize,
			WindowUnit:      stream.WindowUnit,
			Count:           aggResult.Count,
			Min:             aggResult.Min,
			Max:             aggResult.Max,
			Avg:             aggResult.Avg,
			Sum:             aggResult.Sum,
			P50:             aggResult.P50,
			P95:             aggResult.P95,
			P99:             aggResult.P99,
			StandardDev:     m.calculateStdDev(values),
			DataPoints:      int64(len(values)),
			CompressionRate: float64(len(values)),
			CreatedAt:       time.Now(),
		}

		if err := m.aggregatedDataRepo.Create(ctx, aggregatedData); err != nil {
			logger.Get().Warn("Failed to save aggregated data", zap.Error(err))
		}

		m.eventPublisher.PublishAggregationCompleted(ctx, task.StreamID, metric, aggResult.Avg)
		result = aggregatedData
	}

	m.completeTask(task, result)
}

func (m *AsyncTaskManagerImpl) completeTask(task *aggregation.AggregationTask, result *model.AggregatedData) {
	m.mu.Lock()
	now := time.Now()
	task.Status = aggregation.TaskStatusCompleted
	task.Progress = 1.0
	task.Result = result
	task.FinishedAt = &now
	callbacks := m.callbacks[task.TaskID]
	m.mu.Unlock()

	for _, cb := range callbacks {
		go cb.OnComplete(task.Context, task)
	}

	m.eventPublisher.PublishAggregationCompleted(task.Context, task.StreamID, "", 0)
	logger.Get().Info("Aggregation task completed", zap.String("task_id", task.TaskID))
}

func (m *AsyncTaskManagerImpl) failTask(task *aggregation.AggregationTask, err error) {
	m.mu.Lock()
	now := time.Now()
	task.Status = aggregation.TaskStatusFailed
	task.Error = err.Error()
	task.FinishedAt = &now
	callbacks := m.callbacks[task.TaskID]
	m.mu.Unlock()

	for _, cb := range callbacks {
		go cb.OnFailure(task.Context, task)
	}

	logger.Get().Error("Aggregation task failed", zap.String("task_id", task.TaskID), zap.Error(err))
}

func (m *AsyncTaskManagerImpl) notifyProgress(task *aggregation.AggregationTask) {
	m.mu.RLock()
	callbacks := m.callbacks[task.TaskID]
	m.mu.RUnlock()

	for _, cb := range callbacks {
		go cb.OnProgress(task.Context, task)
	}
}

func (m *AsyncTaskManagerImpl) calculateWindowStart(endTime time.Time, windowSize int, windowUnit string) time.Time {
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

func (m *AsyncTaskManagerImpl) calculateAggregation(values []float64, strategy string) aggregation.AggregateResult {
	if len(values) == 0 {
		return aggregation.AggregateResult{}
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	for i := 0; i < len(sorted); i++ {
		for j := i + 1; j < len(sorted); j++ {
			if sorted[j] < sorted[i] {
				sorted[i], sorted[j] = sorted[j], sorted[i]
			}
		}
	}

	min := sorted[0]
	max := sorted[len(sorted)-1]
	sum := 0.0
	for _, v := range sorted {
		sum += v
	}
	avg := sum / float64(len(sorted))

	return aggregation.AggregateResult{
		Min:   min,
		Max:   max,
		Avg:   avg,
		Sum:   sum,
		Count: len(sorted),
		P50:   m.percentile(sorted, 50),
		P95:   m.percentile(sorted, 95),
		P99:   m.percentile(sorted, 99),
	}
}

func (m *AsyncTaskManagerImpl) percentile(sortedValues []float64, p int) float64 {
	if len(sortedValues) == 0 {
		return 0
	}
	index := int(float64(p) / 100.0 * float64(len(sortedValues)))
	if index >= len(sortedValues) {
		index = len(sortedValues) - 1
	}
	return sortedValues[index]
}

func (m *AsyncTaskManagerImpl) calculateStdDev(values []float64) float64 {
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
		variance += (v - mean) * (v - mean)
	}
	variance /= float64(len(values))

	return variance
}

type eventNotifier struct {
	cache *cache.Cache
}

func NewEventNotifier(cache *cache.Cache) *eventNotifier {
	return &eventNotifier{cache: cache}
}

func (n *eventNotifier) NotifyTaskComplete(ctx context.Context, task *aggregation.AggregationTask) {
	if n.cache == nil {
		return
	}

	event := map[string]interface{}{
		"event":     "aggregation.completed",
		"task_id":   task.TaskID,
		"stream_id": task.StreamID,
		"timestamp": time.Now().Unix(),
	}
	_ = n.cache.Publish(ctx, fmt.Sprintf("agg:task:%s", task.TaskID), utils.ToJSON(event))
}

func (n *eventNotifier) NotifyTaskFailed(ctx context.Context, task *aggregation.AggregationTask) {
	if n.cache == nil {
		return
	}

	event := map[string]interface{}{
		"event":     "aggregation.failed",
		"task_id":   task.TaskID,
		"stream_id": task.StreamID,
		"error":     task.Error,
		"timestamp": time.Now().Unix(),
	}
	_ = n.cache.Publish(ctx, fmt.Sprintf("agg:task:%s", task.TaskID), utils.ToJSON(event))
}

func (n *eventNotifier) NotifyThresholdExceeded(ctx context.Context, streamID, metric string, value, threshold float64) {
	if n.cache == nil {
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
	_ = n.cache.Publish(ctx, fmt.Sprintf("agg:alert:%s", streamID), utils.ToJSON(event))
}
