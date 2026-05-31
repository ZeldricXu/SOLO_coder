package monitoring

import (
	"context"
	"time"

	"github.com/solocoder/session136/pkg/common/models"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type MetricsExporter interface {
	Export(ctx context.Context) ([]*models.MetricsSnapshot, error)
	Flush(ctx context.Context) error
	StartAutoFlush(interval time.Duration)
	StopAutoFlush()
}

type DefaultMetricsExporter struct {
	store      MetricsStore
	aggregator MetricsAggregator
	snapshots  []*models.MetricsSnapshot
	logger     *zap.Logger
	stopChan   chan struct{}
	isRunning  bool
}

func NewDefaultMetricsExporter(store MetricsStore, aggregator MetricsAggregator, logger *zap.Logger) *DefaultMetricsExporter {
	return &DefaultMetricsExporter{
		store:      store,
		aggregator: aggregator,
		snapshots:  make([]*models.MetricsSnapshot, 0),
		logger:     logger,
		stopChan:   make(chan struct{}),
	}
}

func (e *DefaultMetricsExporter) Export(ctx context.Context) ([]*models.MetricsSnapshot, error) {
	points := e.store.GetAll()
	if len(points) == 0 {
		return nil, nil
	}

	groups := e.groupByKey(points)
	snapshots := make([]*models.MetricsSnapshot, 0, len(groups))

	for key, groupPoints := range groups {
		snapshot := &models.MetricsSnapshot{
			SnapshotID: utils.GenerateID("snap"),
			Timestamp:  time.Now(),
			Metrics:    make(map[string]float64),
			Dimensions: groupPoints[0].CopyDimensions(),
		}

		snapshot.Metrics["count"] = e.aggregator.Count(groupPoints)
		snapshot.Metrics["sum"] = e.aggregator.Sum(groupPoints)
		snapshot.Metrics["avg"] = e.aggregator.Avg(groupPoints)
		snapshot.Metrics["min"] = e.aggregator.Min(groupPoints)
		snapshot.Metrics["max"] = e.aggregator.Max(groupPoints)

		snapshots = append(snapshots, snapshot)

		e.logger.Debug("Metric snapshot generated",
			zap.String("key", key),
			zap.Int("points", len(groupPoints)),
		)
	}

	return snapshots, nil
}

func (e *DefaultMetricsExporter) Flush(ctx context.Context) error {
	snapshots, err := e.Export(ctx)
	if err != nil {
		return err
	}

	if len(snapshots) > 0 {
		e.snapshots = append(e.snapshots, snapshots...)
		e.store.Clear()
		e.logger.Info("Metrics flushed", zap.Int("snapshot_count", len(snapshots)))
	}

	return nil
}

func (e *DefaultMetricsExporter) StartAutoFlush(interval time.Duration) {
	if e.isRunning {
		return
	}

	e.isRunning = true
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				ctx := context.Background()
				if err := e.Flush(ctx); err != nil {
					e.logger.Error("Auto flush failed", zap.Error(err))
				}
			case <-e.stopChan:
				return
			}
		}
	}()
}

func (e *DefaultMetricsExporter) StopAutoFlush() {
	if e.isRunning {
		close(e.stopChan)
		e.isRunning = false
	}
}

func (e *DefaultMetricsExporter) GetSnapshots() []*models.MetricsSnapshot {
	return e.snapshots
}

func (e *DefaultMetricsExporter) groupByKey(points []*MetricPoint) map[string][]*MetricPoint {
	groups := make(map[string][]*MetricPoint)
	for _, point := range points {
		key := point.BuildKey()
		groups[key] = append(groups[key], point)
	}
	return groups
}
