import {
  IMetricsStorage,
  MemoryMetricsStorage,
  MetricsAggregator,
  createMetricsAggregator,
} from './storage-adapter';

export { IMetricsStorage, MemoryMetricsStorage, MetricsAggregator, createMetricsAggregator };

export const metricsAggregator = createMetricsAggregator();
