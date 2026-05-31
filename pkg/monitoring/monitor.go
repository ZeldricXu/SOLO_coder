package monitoring

import (
	"context"
	"time"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/models"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type AsyncMonitor interface {
	RecordMetricAsync(ctx context.Context, name string, value float64, dimensions map[string]string, callback func(error))
	AggregateAsync(ctx context.Context, metricName string, aggType string, startTime, endTime int64, callback func(float64, error))
	FlushAsync(ctx context.Context, callback func(error))
	ExportAsync(ctx context.Context, callback func([]*models.MetricsSnapshot, error))
	Subscribe(eventType EventType, handler EventHandler)
	StartAsyncProcessing(ctx context.Context)
	StopAsyncProcessing()
}

type DefaultMonitor struct {
	store            MetricsStore
	collector        MetricsCollector
	aggregator       MetricsAggregator
	exporter         MetricsExporter
	snapshotProvider SnapshotProvider
	taskQueue        TaskQueue
	eventBus         EventBus
	logger           *zap.Logger
	flushInterval    time.Duration
	asyncEnabled     bool
}

func NewDefaultMonitor(maxPoints int, flushInterval time.Duration) interfaces.Monitor {
	return NewDefaultMonitorWithAsync(maxPoints, flushInterval, 0, 0)
}

func NewDefaultMonitorWithAsync(maxPoints int, flushInterval time.Duration, workerCount int, queueSize int) *DefaultMonitor {
	logger := utils.GetLogger()
	store := NewInMemoryMetricsStore(maxPoints)
	aggregator := NewDefaultMetricsAggregator()
	collector := NewDefaultMetricsCollector(store, logger)
	exporter := NewDefaultMetricsExporter(store, aggregator, logger)
	snapshotProvider := NewDefaultSnapshotProvider(store, aggregator)

	m := &DefaultMonitor{
		store:            store,
		collector:        collector,
		aggregator:       aggregator,
		exporter:         exporter,
		snapshotProvider: snapshotProvider,
		logger:           logger,
		flushInterval:    flushInterval,
		eventBus:         NewDefaultEventBus(),
	}

	if workerCount > 0 && queueSize > 0 {
		m.taskQueue = NewWorkerPoolTaskQueue(workerCount, queueSize, store, aggregator, exporter)
		m.asyncEnabled = true
		logger.Info("Async processing enabled", zap.Int("workers", workerCount), zap.Int("queue_size", queueSize))
	}

	if flushInterval > 0 {
		exporter.StartAutoFlush(flushInterval)
	}

	return m
}

func (m *DefaultMonitor) RecordMetric(ctx context.Context, name string, value float64, dimensions map[string]string) {
	m.collector.Record(ctx, name, value, dimensions)
}

func (m *DefaultMonitor) GetMetrics(ctx context.Context, name string, startTime, endTime int64) []*models.MetricsSnapshot {
	return m.snapshotProvider.GetMetrics(ctx, name, startTime, endTime)
}

func (m *DefaultMonitor) Aggregate(ctx context.Context, metricName string, aggType string, dimensions map[string]string) (float64, error) {
	points := m.store.GetByDimensions(metricName, dimensions)
	return m.aggregator.Aggregate(points, aggType)
}

func (m *DefaultMonitor) Flush(ctx context.Context) error {
	return m.exporter.Flush(ctx)
}

func (m *DefaultMonitor) Stop() {
	m.exporter.StopAutoFlush()
}

func (m *DefaultMonitor) GetMetricsStore() MetricsStore {
	return m.store
}

func (m *DefaultMonitor) GetMetricsCollector() MetricsCollector {
	return m.collector
}

func (m *DefaultMonitor) GetMetricsAggregator() MetricsAggregator {
	return m.aggregator
}

func (m *DefaultMonitor) GetMetricsExporter() MetricsExporter {
	return m.exporter
}

func (m *DefaultMonitor) RecordMetricAsync(ctx context.Context, name string, value float64, dimensions map[string]string, callback func(error)) {
	if !m.asyncEnabled {
		m.collector.Record(ctx, name, value, dimensions)
		if callback != nil {
			callback(nil)
		}
		return
	}

	task := &AsyncTask{
		ID:        utils.GenerateID(),
		Type:      TaskTypeRecord,
		CreatedAt: getCurrentTimestamp(),
		Data: &MetricPoint{
			Name:       name,
			Value:      value,
			Dimensions: dimensions,
			Timestamp:  getCurrentTimestamp(),
		},
		Callback: func(result interface{}, err error) {
			if callback != nil {
				callback(err)
			}
		},
		Ctx: ctx,
	}

	m.taskQueue.Enqueue(task)
	m.eventBus.Publish(NewMetricRecordedEvent(name, value, dimensions))
}

func (m *DefaultMonitor) AggregateAsync(ctx context.Context, metricName string, aggType string, startTime, endTime int64, callback func(float64, error)) {
	if !m.asyncEnabled {
		points := m.store.GetByName(metricName, startTime, endTime)
		result, err := m.aggregator.Aggregate(points, aggType)
		if callback != nil {
			callback(result, err)
		}
		return
	}

	task := &AsyncTask{
		ID:        utils.GenerateID(),
		Type:      TaskTypeAggregate,
		CreatedAt: getCurrentTimestamp(),
		Data: &AggregateRequest{
			MetricName: metricName,
			StartTime:  startTime,
			EndTime:    endTime,
			AggType:    aggType,
		},
		Callback: func(result interface{}, err error) {
			if callback != nil {
				var aggResult float64
				if result != nil {
					aggResult = result.(float64)
				}
				callback(aggResult, err)
			}
		},
		Ctx: ctx,
	}

	m.taskQueue.Enqueue(task)
}

func (m *DefaultMonitor) FlushAsync(ctx context.Context, callback func(error)) {
	if !m.asyncEnabled {
		err := m.exporter.Flush(ctx)
		if callback != nil {
			callback(err)
		}
		return
	}

	task := &AsyncTask{
		ID:        utils.GenerateID(),
		Type:      TaskTypeFlush,
		CreatedAt: getCurrentTimestamp(),
		Callback: func(result interface{}, err error) {
			if callback != nil {
				callback(err)
			}
		},
		Ctx: ctx,
	}

	m.taskQueue.Enqueue(task)
	m.eventBus.Publish(NewFlushCompleteEvent(nil))
}

func (m *DefaultMonitor) ExportAsync(ctx context.Context, callback func([]*models.MetricsSnapshot, error)) {
	if !m.asyncEnabled {
		snapshots, err := m.exporter.Export(ctx)
		if callback != nil {
			callback(snapshots, err)
		}
		return
	}

	task := &AsyncTask{
		ID:        utils.GenerateID(),
		Type:      TaskTypeExport,
		CreatedAt: getCurrentTimestamp(),
		Callback: func(result interface{}, err error) {
			if callback != nil {
				var snapshots []*models.MetricsSnapshot
				if result != nil {
					snapshots = result.([]*models.MetricsSnapshot)
				}
				callback(snapshots, err)
			}
		},
		Ctx: ctx,
	}

	m.taskQueue.Enqueue(task)
}

func (m *DefaultMonitor) Subscribe(eventType EventType, handler EventHandler) {
	m.eventBus.Subscribe(eventType, handler)
}

func (m *DefaultMonitor) StartAsyncProcessing(ctx context.Context) {
	if m.asyncEnabled && m.taskQueue != nil {
		m.taskQueue.Start(ctx)
	}
}

func (m *DefaultMonitor) StopAsyncProcessing() {
	if m.taskQueue != nil {
		m.taskQueue.Stop()
	}
}

func (m *DefaultMonitor) GetEventBus() EventBus {
	return m.eventBus
}

func (m *DefaultMonitor) GetTaskQueue() TaskQueue {
	return m.taskQueue
}

func (m *DefaultMonitor) IsAsyncEnabled() bool {
	return m.asyncEnabled
}
