package monitoring

import (
	"context"
	"time"

	"github.com/solocoder/session136/pkg/common/models"
	"github.com/solocoder/session136/pkg/common/utils"
)

type SnapshotProvider interface {
	GetMetrics(ctx context.Context, name string, startTime, endTime int64) []*models.MetricsSnapshot
}

type DefaultSnapshotProvider struct {
	store      MetricsStore
	aggregator MetricsAggregator
}

func NewDefaultSnapshotProvider(store MetricsStore, aggregator MetricsAggregator) *DefaultSnapshotProvider {
	return &DefaultSnapshotProvider{
		store:      store,
		aggregator: aggregator,
	}
}

func (p *DefaultSnapshotProvider) GetMetrics(ctx context.Context, name string, startTime, endTime int64) []*models.MetricsSnapshot {
	points := p.store.GetByName(name, startTime, endTime)

	var snapshots []*models.MetricsSnapshot
	currentMinute := int64(-1)
	var currentSnapshot *models.MetricsSnapshot
	var currentPoints []*MetricPoint

	for _, point := range points {
		minute := point.Timestamp / 60000000000
		if minute != currentMinute {
			if currentSnapshot != nil && len(currentPoints) > 0 {
				p.populateSnapshotMetrics(currentSnapshot, currentPoints)
				snapshots = append(snapshots, currentSnapshot)
			}

			currentMinute = minute
			currentSnapshot = &models.MetricsSnapshot{
				SnapshotID: utils.GenerateID("snap"),
				Timestamp:  time.Unix(0, minute*60000000000),
				Metrics:    make(map[string]float64),
				Dimensions: make(map[string]string),
			}
			currentPoints = make([]*MetricPoint, 0)
		}

		currentPoints = append(currentPoints, point)

		for k, v := range point.Dimensions {
			currentSnapshot.Dimensions[k] = v
		}
	}

	if currentSnapshot != nil && len(currentPoints) > 0 {
		p.populateSnapshotMetrics(currentSnapshot, currentPoints)
		snapshots = append(snapshots, currentSnapshot)
	}

	return snapshots
}

func (p *DefaultSnapshotProvider) populateSnapshotMetrics(snapshot *models.MetricsSnapshot, points []*MetricPoint) {
	snapshot.Metrics["count"] = p.aggregator.Count(points)
	snapshot.Metrics["sum"] = p.aggregator.Sum(points)
	snapshot.Metrics["avg"] = p.aggregator.Avg(points)
	snapshot.Metrics["min"] = p.aggregator.Min(points)
	snapshot.Metrics["max"] = p.aggregator.Max(points)
}
