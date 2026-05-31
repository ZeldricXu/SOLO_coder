package monitoring

import (
	"context"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/session136/pkg/common/models"
	"github.com/solocoder/session136/pkg/common/utils"
)

type TaskType int

const (
	TaskTypeRecord TaskType = iota
	TaskTypeAggregate
	TaskTypeFlush
	TaskTypeExport
	TaskTypeSnapshot
)

type AsyncTask struct {
	ID          string
	Type          TaskType
	CreatedAt     int64
	Data          interface{}
	Callback      func(result interface{}, err error)
	ResultChan    chan *TaskResult
	Ctx           context.Context
}

type TaskResult struct {
	TaskID  *AsyncTask
	Result  interface{}
	Error   error
	CompletedAt int64
}

type TaskQueue interface {
	Enqueue(task *AsyncTask)
	Start(ctx context.Context)
	Stop()
	Size() int
}

type WorkerPoolTaskQueue struct {
	workerCount int
	taskQueue     chan *AsyncTask
	workers     []*worker
	wg           sync.WaitGroup
	metrics      MetricsStore
	aggregator   MetricsAggregator
	exporter     MetricsExporter
	logger       *zap.Logger
	stopChan     chan struct{}
	mu           sync.RWMutex
}

type worker struct {
	id           int
	taskQueue    chan *AsyncTask
	stopChan     chan struct{}
	metrics      MetricsStore
	aggregator   MetricsAggregator
	exporter     MetricsExporter
	logger       *zap.Logger
}

func NewWorkerPoolTaskQueue(workerCount int, bufferSize int, metrics MetricsStore,
	aggregator MetricsAggregator, exporter MetricsExporter) *WorkerPoolTaskQueue {
	logger := utils.GetLogger()
	return &WorkerPoolTaskQueue{
		workerCount: workerCount,
		taskQueue:     make(chan *AsyncTask, bufferSize),
		metrics:     metrics,
		aggregator: aggregator,
		exporter:   exporter,
		logger:     logger,
		stopChan:   make(chan struct{}),
	}
}

func (q *WorkerPoolTaskQueue) Enqueue(task *AsyncTask) {
	select {
	case q.taskQueue <- task:
	default:
		q.logger.Warn("Task queue is full, dropping task",
			zap.String("task_id", task.ID),
			zap.Int("queue_size", len(q.taskQueue)),
		)
	}
}

func (q *WorkerPoolTaskQueue) Start(ctx context.Context) {
	q.mu.Lock()
	defer q.mu.Unlock()

	q.workers = make([]*worker, q.workerCount)
	for i := 0; i < q.workerCount; i++ {
		w := &worker{
			id:           i,
			taskQueue:    q.taskQueue,
			stopChan:     make(chan struct{}),
			metrics:      q.metrics,
			aggregator:   q.aggregator,
			exporter:     q.exporter,
			logger:       q.logger,
		}
		q.workers[i] = w

		q.wg.Add(1)
		go w.run(ctx)
	}

	q.logger.Info("Worker pool started", zap.Int("worker_count", q.workerCount))
}

func (q *WorkerPoolTaskQueue) Stop() {
	close(q.stopChan)

	q.mu.RLock()
	for _, w := range q.workers {
		close(w.stopChan)
	}
	q.mu.RUnlock()

	q.wg.Wait()
	q.logger.Info("Worker pool stopped")
}

func (q *WorkerPoolTaskQueue) Size() int {
	return len(q.taskQueue)
}

func (w *worker) run(ctx context.Context) {
	defer w.done()

	for {
		select {
		case <-ctx.Done():
			return
		case <-w.stopChan:
			return
		case task, ok := <-w.taskQueue:
			if !ok {
				return
			}
			w.processTask(task)
		}
	}
}

func (w *worker) done() {
	w.logger.Debug("Worker stopped", zap.Int("worker_id", w.id))
}

func (w *worker) processTask(task *AsyncTask) {
	startTime := time.Now().UnixNano()

	var result interface{}
	var err error

	switch task.Type {
	case TaskTypeRecord:
		if data, ok := task.Data.(*MetricPoint); ok {
			w.metrics.Add(data)
			result = nil
			err = nil
		}

	case TaskTypeAggregate:
		if req, ok := task.Data.(*AggregateRequest); ok {
			points := w.metrics.GetByName(req.MetricName, req.StartTime, req.EndTime)
			aggValue, aggErr := w.aggregator.Aggregate(points, req.AggType)
			result = aggValue
			err = aggErr
		}

	case TaskTypeFlush:
		err = w.exporter.Flush(task.Ctx)

	case TaskTypeExport:
		snapshots, exportErr := w.exporter.Export(task.Ctx)
		result = snapshots
		err = exportErr

	case TaskTypeSnapshot:
		if req, ok := task.Data.(*SnapshotRequest); ok {
			points := w.metrics.GetByName(req.MetricName, req.StartTime, req.EndTime)
			snapshots := w.convertToSnapshots(points, req.MetricName)
			result = snapshots
		}
	}

	processingTime := time.Now().UnixNano()

	taskResult := &TaskResult{
		Task:        task,
		Result:      result,
		Error:       err,
		CompletedAt: processingTime,
	}

	if task.Callback != nil {
		go task.Callback(result, err)
	}

	if task.ResultChan != nil {
		select {
		case task.ResultChan <- taskResult:
		default:
		}
	}

	w.logger.Debug("Task completed",
		zap.String("task_id", task.ID),
		zap.Int64("duration_ns", processingTime-startTime),
		zap.Error(err),
	)
}

type AggregateRequest struct {
	MetricName string
	StartTime  int64
	EndTime    int64
	AggType    string
}

type SnapshotRequest struct {
	MetricName string
	StartTime  int64
	EndTime    int64
}

func (w *worker) convertToSnapshots(points []*MetricPoint, metricName string) []*models.MetricsSnapshot {
	snapshots := make([]*models.MetricsSnapshot, 0, len(points))
	for _, p := range points {
		snapshots = append(snapshots, &models.MetricsSnapshot{
			Name:       metricName,
			Value:      p.Value,
			Timestamp:  p.Timestamp,
			Dimensions: p.Dimensions,
		})
	}
	return snapshots
}
