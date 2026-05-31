import { EventEmitter, parseDuration, calculatePercentile, average, sum, min, max } from '../utils';
import logger from '../utils/logger';
import { TimeSeriesPoint, MetricAggregationConfig, StatsSnapshot } from '../types';
import cacheManager from '../data-access';
import { InMemoryCache } from '../data-access';

export interface AggregatedMetric {
  metric_name: string;
  timestamp: number;
  granularity: string;
  aggregation: string;
  value: number;
  count: number;
  dimensions?: Record<string, string>;
}

export interface MetricQuery {
  metric_name: string;
  start_time: number;
  end_time: number;
  aggregations?: string[];
  granularity?: string;
  dimensions?: Record<string, string>;
}

export interface StorageAdapter {
  name: string;
  write(points: TimeSeriesPoint[]): Promise<void>;
  writeAggregated(metrics: AggregatedMetric[]): Promise<void>;
  query(query: MetricQuery): Promise<TimeSeriesPoint[]>;
  queryAggregated(query: MetricQuery): Promise<AggregatedMetric[]>;
  healthCheck(): Promise<boolean>;
}

export type AggregationFunction = (values: number[]) => number;

export interface AggregationPlugin {
  name: string;
  version: string;
  description?: string;
  aggregations: Record<string, AggregationFunction>;
  init?: () => void | Promise<void>;
  cleanup?: () => void | Promise<void>;
}

export interface StorageAdapterPlugin {
  name: string;
  version: string;
  description?: string;
  createAdapter: (options?: any) => StorageAdapter | Promise<StorageAdapter>;
  init?: () => void | Promise<void>;
  cleanup?: () => void | Promise<void>;
}

export type MetricsPlugin = AggregationPlugin | StorageAdapterPlugin;

export interface PluginMetadata {
  id: string;
  name: string;
  version: string;
  type: 'aggregation' | 'storage';
  enabled: boolean;
  loadedAt: number;
}

export interface PluginManagerEvents {
  'plugin.loaded': { plugin: MetricsPlugin; metadata: PluginMetadata };
  'plugin.unloaded': { pluginId: string };
  'plugin.enabled': { pluginId: string };
  'plugin.disabled': { pluginId: string };
  'plugin.error': { pluginId: string; error: Error };
}

export class PluginManager extends EventEmitter<PluginManagerEvents> {
  private plugins: Map<string, MetricsPlugin> = new Map();
  private metadata: Map<string, PluginMetadata> = new Map();
  private customAggregations: Map<string, AggregationFunction> = new Map();

  async loadPlugin(plugin: MetricsPlugin): Promise<string> {
    const pluginId = `${plugin.name}@${plugin.version}`;

    if (this.plugins.has(pluginId)) {
      logger.warn(`Plugin ${pluginId} is already loaded`);
      return pluginId;
    }

    try {
      if (plugin.init) {
        await plugin.init();
      }

      this.plugins.set(pluginId, plugin);

      const metadata: PluginMetadata = {
        id: pluginId,
        name: plugin.name,
        version: plugin.version,
        type: 'aggregations' in plugin ? 'aggregation' : 'storage',
        enabled: true,
        loadedAt: Date.now(),
      };
      this.metadata.set(pluginId, metadata);

      if ('aggregations' in plugin) {
        for (const [name, fn] of Object.entries(plugin.aggregations)) {
          this.customAggregations.set(name, fn);
          logger.info(`Registered custom aggregation: ${name} from plugin ${pluginId}`);
        }
      }

      this.emit('plugin.loaded', { plugin, metadata });
      logger.info(`Loaded plugin: ${pluginId}`);

      return pluginId;
    } catch (error) {
      this.emit('plugin.error', { pluginId, error: error as Error });
      logger.error(`Failed to load plugin ${pluginId}:`, error);
      throw error;
    }
  }

  async unloadPlugin(pluginId: string): Promise<boolean> {
    const plugin = this.plugins.get(pluginId);
    if (!plugin) {
      logger.warn(`Plugin ${pluginId} not found`);
      return false;
    }

    try {
      if (plugin.cleanup) {
        await plugin.cleanup();
      }

      if ('aggregations' in plugin) {
        for (const name of Object.keys(plugin.aggregations)) {
          this.customAggregations.delete(name);
        }
      }

      this.plugins.delete(pluginId);
      this.metadata.delete(pluginId);

      this.emit('plugin.unloaded', { pluginId });
      logger.info(`Unloaded plugin: ${pluginId}`);

      return true;
    } catch (error) {
      this.emit('plugin.error', { pluginId, error: error as Error });
      logger.error(`Failed to unload plugin ${pluginId}:`, error);
      return false;
    }
  }

  getPlugin(pluginId: string): MetricsPlugin | undefined {
    return this.plugins.get(pluginId);
  }

  getPluginMetadata(pluginId: string): PluginMetadata | undefined {
    return this.metadata.get(pluginId);
  }

  getAllPlugins(): MetricsPlugin[] {
    return Array.from(this.plugins.values());
  }

  getAllMetadata(): PluginMetadata[] {
    return Array.from(this.metadata.values());
  }

  getAggregationFunction(name: string): AggregationFunction | undefined {
    return this.customAggregations.get(name);
  }

  hasAggregation(name: string): boolean {
    return this.customAggregations.has(name);
  }

  getCustomAggregations(): string[] {
    return Array.from(this.customAggregations.keys());
  }

  enablePlugin(pluginId: string): boolean {
    const meta = this.metadata.get(pluginId);
    if (!meta) return false;
    meta.enabled = true;
    this.emit('plugin.enabled', { pluginId });
    return true;
  }

  disablePlugin(pluginId: string): boolean {
    const meta = this.metadata.get(pluginId);
    if (!meta) return false;
    meta.enabled = false;
    this.emit('plugin.disabled', { pluginId });
    return true;
  }

  isPluginEnabled(pluginId: string): boolean {
    return this.metadata.get(pluginId)?.enabled ?? false;
  }

  clear(): void {
    for (const pluginId of this.plugins.keys()) {
      this.unloadPlugin(pluginId).catch(() => {});
    }
    this.plugins.clear();
    this.metadata.clear();
    this.customAggregations.clear();
  }
}

interface MetricsEvents {
  'metric.received': TimeSeriesPoint;
  'metric.aggregated': AggregatedMetric;
  'storage.write': { count: number; adapter: string };
  'storage.error': { error: Error; adapter: string };
}

export class InMemoryStorageAdapter implements StorageAdapter {
  name = 'in-memory';
  private rawPoints: Map<string, TimeSeriesPoint[]>;
  private aggregatedMetrics: Map<string, AggregatedMetric[]>;
  private maxRawPoints: number;

  constructor(maxRawPoints: number = 100000) {
    this.rawPoints = new Map();
    this.aggregatedMetrics = new Map();
    this.maxRawPoints = maxRawPoints;
  }

  private getKey(metricName: string, dimensions?: Record<string, string>): string {
    const dimsStr = dimensions ? JSON.stringify(dimensions) : '';
    return `${metricName}:${dimsStr}`;
  }

  async write(points: TimeSeriesPoint[]): Promise<void> {
    for (const point of points) {
      const key = this.getKey(point.dimensions ? (point.dimensions.__metric_name as string) || 'default' : 'default', point.dimensions);
      if (!this.rawPoints.has(key)) {
        this.rawPoints.set(key, []);
      }
      const arr = this.rawPoints.get(key)!;
      arr.push(point);
      if (arr.length > this.maxRawPoints) {
        arr.shift();
      }
    }
  }

  async writeAggregated(metrics: AggregatedMetric[]): Promise<void> {
    for (const metric of metrics) {
      const key = `${metric.metric_name}:${metric.granularity}:${metric.aggregation}`;
      if (!this.aggregatedMetrics.has(key)) {
        this.aggregatedMetrics.set(key, []);
      }
      const arr = this.aggregatedMetrics.get(key)!;
      arr.push(metric);
      if (arr.length > this.maxRawPoints) {
        arr.shift();
      }
    }
  }

  async query(query: MetricQuery): Promise<TimeSeriesPoint[]> {
    const key = this.getKey(query.metric_name, query.dimensions);
    const points = this.rawPoints.get(key) || [];
    return points.filter(
      (p) => p.timestamp >= query.start_time && p.timestamp <= query.end_time
    );
  }

  async queryAggregated(query: MetricQuery): Promise<AggregatedMetric[]> {
    const results: AggregatedMetric[] = [];
    const granularity = query.granularity || '1m';
    const aggregations = query.aggregations || ['avg'];

    for (const agg of aggregations) {
      const key = `${query.metric_name}:${granularity}:${agg}`;
      const metrics = this.aggregatedMetrics.get(key) || [];
      results.push(
        ...metrics.filter(
          (m) => m.timestamp >= query.start_time && m.timestamp <= query.end_time
        )
      );
    }

    return results;
  }

  async healthCheck(): Promise<boolean> {
    return true;
  }

  clear(): void {
    this.rawPoints.clear();
    this.aggregatedMetrics.clear();
  }

  getStats(): { rawPoints: number; aggregatedMetrics: number } {
    let rawCount = 0;
    let aggCount = 0;
    for (const points of this.rawPoints.values()) {
      rawCount += points.length;
    }
    for (const metrics of this.aggregatedMetrics.values()) {
      aggCount += metrics.length;
    }
    return { rawPoints: rawCount, aggregatedMetrics: aggCount };
  }
}

export const InMemoryStoragePlugin: StorageAdapterPlugin = {
  name: 'in-memory-storage',
  version: '1.0.0',
  description: 'In-memory storage adapter plugin',
  createAdapter: (options?: any) => new InMemoryStorageAdapter(options?.maxRawPoints),
};

export const StatisticalAggregationPlugin: AggregationPlugin = {
  name: 'statistical-aggregations',
  version: '1.0.0',
  description: 'Additional statistical aggregation functions',
  aggregations: {
    sum_of_squares: (values: number[]) => values.reduce((acc, v) => acc + v * v, 0),
    variance: (values: number[]) => {
      if (values.length < 2) return 0;
      const avg = average(values);
      const squareDiffs = values.map((v) => Math.pow(v - avg, 2));
      return average(squareDiffs);
    },
    stddev: (values: number[]) => {
      if (values.length < 2) return 0;
      const avg = average(values);
      const squareDiffs = values.map((v) => Math.pow(v - avg, 2));
      return Math.sqrt(average(squareDiffs));
    },
    median: (values: number[]) => calculatePercentile(values, 50),
    range: (values: number[]) => max(values) - min(values),
  },
};

export class MetricsAggregator extends EventEmitter<MetricsEvents> {
  private aggregationConfigs: Map<string, MetricAggregationConfig>;
  private aggregationWindows: Map<string, TimeSeriesPoint[]>;
  private flushInterval: NodeJS.Timeout | null = null;
  private storageAdapters: StorageAdapter[];
  private flushIntervalMs: number;
  private pluginManager: PluginManager;

  constructor(flushIntervalMs: number = 60000, pluginManager?: PluginManager) {
    super();
    this.aggregationConfigs = new Map();
    this.aggregationWindows = new Map();
    this.storageAdapters = [];
    this.flushIntervalMs = flushIntervalMs;
    this.pluginManager = pluginManager || new PluginManager();
  }

  getPluginManager(): PluginManager {
    return this.pluginManager;
  }

  registerAggregationConfig(config: MetricAggregationConfig): void {
    this.aggregationConfigs.set(config.metric_name, config);
    logger.info(`Registered aggregation config for metric: ${config.metric_name}`);
  }

  unregisterAggregationConfig(metricName: string): boolean {
    return this.aggregationConfigs.delete(metricName);
  }

  addStorageAdapter(adapter: StorageAdapter): void {
    this.storageAdapters.push(adapter);
    logger.info(`Added storage adapter: ${adapter.name}`);
  }

  removeStorageAdapter(name: string): boolean {
    const index = this.storageAdapters.findIndex((a) => a.name === name);
    if (index !== -1) {
      this.storageAdapters.splice(index, 1);
      logger.info(`Removed storage adapter: ${name}`);
      return true;
    }
    return false;
  }

  getStorageAdapters(): StorageAdapter[] {
    return [...this.storageAdapters];
  }

  receiveMetric(point: TimeSeriesPoint): void {
    const metricName = point.dimensions?.__metric_name as string || 'default';
    const windowKey = `${metricName}:${JSON.stringify(point.dimensions || {})}`;

    if (!this.aggregationWindows.has(windowKey)) {
      this.aggregationWindows.set(windowKey, []);
    }
    this.aggregationWindows.get(windowKey)!.push(point);
    this.emit('metric.received', point);
  }

  receiveMetrics(points: TimeSeriesPoint[]): void {
    for (const point of points) {
      this.receiveMetric(point);
    }
  }

  private aggregateValues(values: number[], aggregation: string): number {
    const customFn = this.pluginManager.getAggregationFunction(aggregation);
    if (customFn) {
      return customFn(values);
    }

    switch (aggregation) {
      case 'sum':
        return sum(values);
      case 'avg':
        return average(values);
      case 'min':
        return min(values);
      case 'max':
        return max(values);
      case 'count':
        return values.length;
      case 'p50':
        return calculatePercentile(values, 50);
      case 'p95':
        return calculatePercentile(values, 95);
      case 'p99':
        return calculatePercentile(values, 99);
      default:
        logger.warn(`Unknown aggregation function: ${aggregation}, using avg as fallback`);
        return average(values);
    }
  }

  getAvailableAggregations(): string[] {
    const builtIn = ['sum', 'avg', 'min', 'max', 'count', 'p50', 'p95', 'p99'];
    const custom = this.pluginManager.getCustomAggregations();
    return [...builtIn, ...custom];
  }

  async flushAggregations(): Promise<void> {
    const allAggregated: AggregatedMetric[] = [];
    const now = Date.now();

    for (const [windowKey, points] of this.aggregationWindows.entries()) {
      if (points.length === 0) continue;

      const [metricName] = windowKey.split(':');
      const config = this.aggregationConfigs.get(metricName);
      const dimensions = points[0].dimensions;

      if (!config) continue;

      const values = points.map((p) => p.value);

      for (const granularity of config.granularities) {
        const granularityMs = parseDuration(granularity);
        const windowStart = Math.floor(now / granularityMs) * granularityMs;

        for (const aggregation of config.aggregations) {
          const value = this.aggregateValues(values, aggregation);
          const aggregated: AggregatedMetric = {
            metric_name: metricName,
            timestamp: windowStart,
            granularity,
            aggregation,
            value,
            count: values.length,
            dimensions,
          };
          allAggregated.push(aggregated);
          this.emit('metric.aggregated', aggregated);
        }
      }
    }

    if (allAggregated.length > 0) {
      await this.writeToStorage(allAggregated);
    }

    this.aggregationWindows.clear();
    logger.debug(`Flushed ${allAggregated.length} aggregated metrics`);
  }

  private async writeToStorage(aggregated: AggregatedMetric[]): Promise<void> {
    for (const adapter of this.storageAdapters) {
      try {
        await adapter.writeAggregated(aggregated);
        this.emit('storage.write', { count: aggregated.length, adapter: adapter.name });
      } catch (error) {
        logger.error(`Failed to write to storage adapter ${adapter.name}:`, error);
        this.emit('storage.error', { error: error as Error, adapter: adapter.name });
      }
    }
  }

  async writeRawMetrics(points: TimeSeriesPoint[]): Promise<void> {
    for (const adapter of this.storageAdapters) {
      try {
        await adapter.write(points);
      } catch (error) {
        logger.error(`Failed to write raw metrics to ${adapter.name}:`, error);
      }
    }
  }

  startAutoFlush(): void {
    if (this.flushInterval) {
      clearInterval(this.flushInterval);
    }
    this.flushInterval = setInterval(() => {
      this.flushAggregations().catch((error) => {
        logger.error('Auto flush failed:', error);
      });
    }, this.flushIntervalMs);
    logger.info(`Started auto metrics flush with interval: ${this.flushIntervalMs}ms`);
  }

  stopAutoFlush(): void {
    if (this.flushInterval) {
      clearInterval(this.flushInterval);
      this.flushInterval = null;
      logger.info('Stopped auto metrics flush');
    }
  }

  async query(query: MetricQuery): Promise<TimeSeriesPoint[]> {
    for (const adapter of this.storageAdapters) {
      try {
        return await adapter.query(query);
      } catch (error) {
        logger.error(`Query failed on adapter ${adapter.name}:`, error);
      }
    }
    return [];
  }

  async queryAggregated(query: MetricQuery): Promise<AggregatedMetric[]> {
    for (const adapter of this.storageAdapters) {
      try {
        return await adapter.queryAggregated(query);
      } catch (error) {
        logger.error(`Aggregated query failed on adapter ${adapter.name}:`, error);
      }
    }
    return [];
  }

  createSnapshot(metrics: string[], dimensions?: Record<string, string>): StatsSnapshot {
    const snapshot: StatsSnapshot = {
      snapshot_id: `snap_${Date.now()}`,
      timestamp: new Date().toISOString(),
      metrics: {},
      dimensions: dimensions || {},
    };

    const now = Date.now();
    const fiveMinutesAgo = now - 5 * 60 * 1000;

    for (const metricName of metrics) {
      const query: MetricQuery = {
        metric_name: metricName,
        start_time: fiveMinutesAgo,
        end_time: now,
        aggregations: ['avg', 'p99', 'count'],
        granularity: '1m',
        dimensions,
      };

      const aggregated = this.queryAggregated(query);
      if (aggregated && Array.isArray(aggregated) && aggregated.length > 0) {
        const avgMetric = aggregated.find((m) => m.aggregation === 'avg');
        const p99Metric = aggregated.find((m) => m.aggregation === 'p99');
        const countMetric = aggregated.find((m) => m.aggregation === 'count');

        if (avgMetric) snapshot.metrics[`${metricName}_avg`] = avgMetric.value;
        if (p99Metric) snapshot.metrics[`${metricName}_p99`] = p99Metric.value;
        if (countMetric) snapshot.metrics[`${metricName}_count`] = countMetric.value;
      }
    }

    return snapshot;
  }

  getWindowSize(): number {
    let size = 0;
    for (const points of this.aggregationWindows.values()) {
      size += points.length;
    }
    return size;
  }

  clear(): void {
    this.stopAutoFlush();
    this.aggregationConfigs.clear();
    this.aggregationWindows.clear();
    this.storageAdapters = [];
    this.pluginManager.clear();
  }
}

export class MetricsService {
  private aggregator: MetricsAggregator;
  private inMemoryStorage: InMemoryStorageAdapter;
  private snapshotsCache: InMemoryCache<StatsSnapshot>;

  constructor() {
    this.inMemoryStorage = new InMemoryStorageAdapter();
    this.aggregator = new MetricsAggregator();
    this.aggregator.addStorageAdapter(this.inMemoryStorage);
    this.snapshotsCache = cacheManager.createInMemoryCache<StatsSnapshot>('metrics_snapshots', {
      default_ttl: 300000,
      max_size: 1000,
    });

    this.aggregator.getPluginManager().loadPlugin(StatisticalAggregationPlugin).catch(() => {});
  }

  getAggregator(): MetricsAggregator {
    return this.aggregator;
  }

  getInMemoryStorage(): InMemoryStorageAdapter {
    return this.inMemoryStorage;
  }

  getPluginManager(): PluginManager {
    return this.aggregator.getPluginManager();
  }

  async loadPlugin(plugin: MetricsPlugin): Promise<string> {
    return this.aggregator.getPluginManager().loadPlugin(plugin);
  }

  async unloadPlugin(pluginId: string): Promise<boolean> {
    return this.aggregator.getPluginManager().unloadPlugin(pluginId);
  }

  recordMetric(metricName: string, value: number, dimensions?: Record<string, string>): void {
    const point: TimeSeriesPoint = {
      timestamp: Date.now(),
      value,
      dimensions: { ...dimensions, __metric_name: metricName },
    };
    this.aggregator.receiveMetric(point);
  }

  recordMetrics(points: Array<{ metricName: string; value: number; dimensions?: Record<string, string> }>): void {
    const timeSeriesPoints = points.map((p) => ({
      timestamp: Date.now(),
      value: p.value,
      dimensions: { ...p.dimensions, __metric_name: p.metricName },
    }));
    this.aggregator.receiveMetrics(timeSeriesPoints);
  }

  async getMetricValues(metricName: string, startTime: number, endTime: number): Promise<TimeSeriesPoint[]> {
    return this.aggregator.query({
      metric_name: metricName,
      start_time: startTime,
      end_time: endTime,
    });
  }

  async getAggregatedMetric(
    metricName: string,
    startTime: number,
    endTime: number,
    aggregation: string = 'avg',
    granularity: string = '1m'
  ): Promise<AggregatedMetric[]> {
    return this.aggregator.queryAggregated({
      metric_name: metricName,
      start_time: startTime,
      end_time: endTime,
      aggregations: [aggregation],
      granularity,
    });
  }

  async createAndCacheSnapshot(metrics: string[], dimensions?: Record<string, string>): Promise<StatsSnapshot> {
    const snapshot = this.aggregator.createSnapshot(metrics, dimensions);
    await this.snapshotsCache.set(snapshot.snapshot_id, snapshot);
    return snapshot;
  }

  async getSnapshot(snapshotId: string): Promise<StatsSnapshot | undefined> {
    return this.snapshotsCache.get(snapshotId);
  }

  start(): void {
    this.aggregator.startAutoFlush();
  }

  stop(): void {
    this.aggregator.stopAutoFlush();
  }
}

const metricsService = new MetricsService();

export default metricsService;
