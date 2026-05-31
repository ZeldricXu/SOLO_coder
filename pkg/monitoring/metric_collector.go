package monitoring

import (
	"context"
	"time"

	"go.uber.org/zap"
)

type MetricsCollector interface {
	Record(ctx context.Context, name string, value float64, dimensions map[string]string)
}

type DefaultMetricsCollector struct {
	store  MetricsStore
	logger *zap.Logger
}

func NewDefaultMetricsCollector(store MetricsStore, logger *zap.Logger) *DefaultMetricsCollector {
	return &DefaultMetricsCollector{
		store:  store,
		logger: logger,
	}
}

func (c *DefaultMetricsCollector) Record(ctx context.Context, name string, value float64, dimensions map[string]string) {
	point := NewMetricPoint(name, value, dimensions, time.Now().UnixNano())
	c.store.Add(point)

	c.logger.Debug("Metric recorded",
		zap.String("name", name),
		zap.Float64("value", value),
		zap.Any("dimensions", dimensions),
	)
}
