import NodeCache from 'node-cache';
import logger from '../common/logger';
import { generateId, sleep } from '../common/utils';

export interface CacheEntry<T> {
  key: string;
  value: T;
  createdAt: number;
  expiresAt: number;
  hits: number;
}

export interface CacheConfig {
  defaultTTL: number;
  checkperiod: number;
  maxKeys: number;
  useMemoryCache: boolean;
  useRedis: boolean;
}

export interface DataAccessOptions {
  ttl?: number;
  skipCache?: boolean;
  forceRefresh?: boolean;
  preferredSource?: string;
  consistencyLevel?: 'strong' | 'eventual' | 'weak';
}

export interface DataSource {
  id: string;
  name: string;
  type: 'memory' | 'redis' | 'database' | 'api' | 'file';
  isReadable: boolean;
  isWritable: boolean;
  priority: number;
  health: 'healthy' | 'degraded' | 'unhealthy';
  lastHealthCheck: number;
  config: Record<string, unknown>;
  get?: <T>(key: string) => Promise<T | null>;
  set?: <T>(key: string, value: T, ttl?: number) => Promise<boolean>;
  delete?: (key: string) => Promise<boolean>;
  has?: (key: string) => Promise<boolean>;
  getMany?: <T>(keys: string[]) => Promise<Map<string, T | null>>;
  setMany?: <T>(entries: Array<{ key: string; value: T; ttl?: number }>) => Promise<void>;
  deleteMany?: (keys: string[]) => Promise<number>;
}

export interface RoutingRule {
  id: string;
  name: string;
  pattern: RegExp;
  readSources: string[];
  writeSources: string[];
  condition?: (key: string, value?: unknown) => boolean;
  priority: number;
  enabled: boolean;
}

export interface ReadStrategy {
  type: 'first' | 'parallel' | 'sticky' | 'consistent-hash';
  maxParallelSources?: number;
}

export interface WriteStrategy {
  type: 'sync' | 'async' | 'quorum';
  requiredWrites?: number;
  asyncWriteDelayMs?: number;
}

export interface RoutingConfig {
  defaultReadStrategy: ReadStrategy;
  defaultWriteStrategy: WriteStrategy;
  enableHealthCheck: boolean;
  healthCheckIntervalMs: number;
  enableAutoFailover: boolean;
  circuitBreakerThreshold: number;
}

export interface TimingStats {
  totalOperations: number;
  totalTimeMs: number;
  avgTimeMs: number;
  p50TimeMs: number;
  p95TimeMs: number;
  p99TimeMs: number;
  minTimeMs: number;
  maxTimeMs: number;
}

export interface OperationStats {
  get: TimingStats;
  set: TimingStats;
  delete: TimingStats;
  has: TimingStats;
  getMany: TimingStats;
  setMany: TimingStats;
}

export interface PrometheusMetric {
  name: string;
  help: string;
  type: 'counter' | 'gauge' | 'histogram' | 'summary';
  labels?: Record<string, string>;
  value: number | string;
}

export interface PrometheusHistogramConfig {
  buckets?: number[];
  labelNames?: string[];
}

class HistogramRecorder {
  private values: number[] = [];
  private sum: number = 0;
  private count: number = 0;
  private buckets: number[];
  private bucketCounts: Map<number, number> = new Map();

  constructor(buckets: number[] = [1, 5, 10, 25, 50, 100, 250, 500, 1000]) {
    this.buckets = buckets.sort((a, b) => a - b);
    for (const bucket of this.buckets) {
      this.bucketCounts.set(bucket, 0);
    }
  }

  record(value: number): void {
    this.values.push(value);
    this.sum += value;
    this.count++;

    for (const bucket of this.buckets) {
      if (value <= bucket) {
        this.bucketCounts.set(bucket, (this.bucketCounts.get(bucket) || 0) + 1);
      }
    }
  }

  getStats(): TimingStats {
    if (this.count === 0) {
      return {
        totalOperations: 0,
        totalTimeMs: 0,
        avgTimeMs: 0,
        p50TimeMs: 0,
        p95TimeMs: 0,
        p99TimeMs: 0,
        minTimeMs: 0,
        maxTimeMs: 0
      };
    }

    const sorted = [...this.values].sort((a, b) => a - b);
    return {
      totalOperations: this.count,
      totalTimeMs: this.sum,
      avgTimeMs: this.sum / this.count,
      p50TimeMs: this.percentile(sorted, 50),
      p95TimeMs: this.percentile(sorted, 95),
      p99TimeMs: this.percentile(sorted, 99),
      minTimeMs: sorted[0],
      maxTimeMs: sorted[sorted.length - 1]
    };
  }

  getBuckets(): Array<{ le: number; count: number }> {
    return this.buckets.map(bucket => ({
      le: bucket,
      count: this.bucketCounts.get(bucket) || 0
    }));
  }

  getSum(): number {
    return this.sum;
  }

  getCount(): number {
    return this.count;
  }

  reset(): void {
    this.values = [];
    this.sum = 0;
    this.count = 0;
    for (const bucket of this.buckets) {
      this.bucketCounts.set(bucket, 0);
    }
  }

  private percentile(sorted: number[], p: number): number {
    if (sorted.length === 0) return 0;
    const index = Math.ceil((p / 100) * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
  }
}

export class ConsistentHashRouter {
  private ring: Array<{ hash: number; sourceId: string }> = [];
  private virtualNodes: number;
  private sourceWeights: Map<string, number> = new Map();

  constructor(virtualNodes: number = 100) {
    this.virtualNodes = virtualNodes;
  }

  addSource(sourceId: string, weight: number = 1): void {
    this.sourceWeights.set(sourceId, weight);
    this.rebuildRing();
  }

  removeSource(sourceId: string): void {
    this.sourceWeights.delete(sourceId);
    this.rebuildRing();
  }

  private rebuildRing(): void {
    this.ring = [];
    for (const [sourceId, weight] of this.sourceWeights) {
      const nodes = this.virtualNodes * weight;
      for (let i = 0; i < nodes; i++) {
        const hash = this.hash(`${sourceId}:${i}`);
        this.ring.push({ hash, sourceId });
      }
    }
    this.ring.sort((a, b) => a.hash - b.hash);
  }

  private hash(key: string): number {
    let hash = 0;
    for (let i = 0; i < key.length; i++) {
      const char = key.charCodeAt(i);
      hash = ((hash << 5) - hash) + char;
      hash = hash & hash;
    }
    return Math.abs(hash);
  }

  getSource(key: string): string | null {
    if (this.ring.length === 0) return null;

    const hash = this.hash(key);
    for (const node of this.ring) {
      if (node.hash >= hash) {
        return node.sourceId;
      }
    }
    return this.ring[0].sourceId;
  }

  getSources(key: string, count: number): string[] {
    if (this.ring.length === 0) return [];
    if (count >= this.sourceWeights.size) {
      return Array.from(this.sourceWeights.keys());
    }

    const sources: string[] = [];
    const seen = new Set<string>();
    const hash = this.hash(key);

    let startIndex = 0;
    for (let i = 0; i < this.ring.length; i++) {
      if (this.ring[i].hash >= hash) {
        startIndex = i;
        break;
      }
    }

    for (let i = 0; i < this.ring.length && seen.size < count; i++) {
      const index = (startIndex + i) % this.ring.length;
      const sourceId = this.ring[index].sourceId;
      if (!seen.has(sourceId)) {
        seen.add(sourceId);
        sources.push(sourceId);
      }
    }

    return sources;
  }
}

export class DataSourceManager {
  private sources: Map<string, DataSource> = new Map();
  private healthCheckTimer?: NodeJS.Timeout;
  private circuitBreakerStates: Map<string, { failures: number; lastFailure: number; open: boolean }> = new Map();

  constructor(private enableHealthCheck: boolean = true, private healthCheckIntervalMs: number = 30000) {
    if (enableHealthCheck) {
      this.startHealthCheckLoop();
    }
  }

  addSource(source: DataSource): void {
    this.sources.set(source.id, source);
    this.circuitBreakerStates.set(source.id, { failures: 0, lastFailure: 0, open: false });
    logger.info({ sourceId: source.id, name: source.name, type: source.type }, '添加数据源');
  }

  removeSource(sourceId: string): boolean {
    this.circuitBreakerStates.delete(sourceId);
    return this.sources.delete(sourceId);
  }

  getSource(sourceId: string): DataSource | undefined {
    return this.sources.get(sourceId);
  }

  getReadableSources(): DataSource[] {
    return Array.from(this.sources.values())
      .filter(s => s.isReadable && s.health !== 'unhealthy')
      .sort((a, b) => a.priority - b.priority);
  }

  getWritableSources(): DataSource[] {
    return Array.from(this.sources.values())
      .filter(s => s.isWritable && s.health !== 'unhealthy')
      .sort((a, b) => a.priority - b.priority);
  }

  recordFailure(sourceId: string): void {
    const state = this.circuitBreakerStates.get(sourceId);
    if (!state) return;

    state.failures++;
    state.lastFailure = Date.now();

    if (state.failures >= 5) {
      state.open = true;
      const source = this.sources.get(sourceId);
      if (source) {
        source.health = 'unhealthy';
        logger.warn({ sourceId }, '数据源熔断器已打开');
      }
    }
  }

  recordSuccess(sourceId: string): void {
    const state = this.circuitBreakerStates.get(sourceId);
    if (state) {
      state.failures = Math.max(0, state.failures - 1);
      if (state.open && state.failures === 0) {
        state.open = false;
        const source = this.sources.get(sourceId);
        if (source) {
          source.health = 'healthy';
          logger.info({ sourceId }, '数据源熔断器已关闭');
        }
      }
    }
  }

  private async startHealthCheckLoop(): Promise<void> {
    this.healthCheckTimer = setInterval(() => {
      this.checkAllSources().catch(error => {
        logger.error({ error }, '数据源健康检查失败');
      });
    }, this.healthCheckIntervalMs);
  }

  private async checkAllSources(): Promise<void> {
    for (const source of this.sources.values()) {
      try {
        if (source.has) {
          const healthy = await source.has('health-check');
          source.health = healthy !== null ? 'healthy' : 'degraded';
          source.lastHealthCheck = Date.now();
        }
      } catch (error) {
        source.health = 'degraded';
        logger.warn({ sourceId: source.id, error }, '数据源健康检查失败');
      }
    }
  }

  stop(): void {
    if (this.healthCheckTimer) {
      clearInterval(this.healthCheckTimer);
    }
  }

  listSources(): DataSource[] {
    return Array.from(this.sources.values());
  }

  getCircuitBreakerStats(): Array<{ sourceId: string; failures: number; open: boolean; lastFailure: number }> {
    return Array.from(this.circuitBreakerStates.entries()).map(([sourceId, state]) => ({
      sourceId,
      failures: state.failures,
      open: state.open,
      lastFailure: state.lastFailure
    }));
  }
}

export class DataAccessLayer {
  private memoryCache: NodeCache;
  private config: CacheConfig;
  private cacheStats: {
    hits: number;
    misses: number;
    sets: number;
    deletes: number;
  };
  private sourceManager: DataSourceManager;
  private routingRules: RoutingRule[] = [];
  private hashRouter: ConsistentHashRouter;
  private routingConfig: RoutingConfig;
  private pendingAsyncWrites: Map<string, { value: unknown; ttl?: number; sources: string[] }> = new Map();
  private asyncWriteTimer?: NodeJS.Timeout;

  private operationStats: OperationStats;
  private getHistogram: HistogramRecorder;
  private setHistogram: HistogramRecorder;
  private deleteHistogram: HistogramRecorder;
  private hasHistogram: HistogramRecorder;
  private getManyHistogram: HistogramRecorder;
  private setManyHistogram: HistogramRecorder;
  private statsResetAt: number;

  constructor(
    config: Partial<CacheConfig> = {},
    routingConfig: Partial<RoutingConfig> = {}
  ) {
    this.config = {
      defaultTTL: config.defaultTTL ?? 300,
      checkperiod: config.checkperiod ?? 60,
      maxKeys: config.maxKeys ?? 10000,
      useMemoryCache: config.useMemoryCache ?? true,
      useRedis: config.useRedis ?? false
    };

    this.routingConfig = {
      defaultReadStrategy: routingConfig.defaultReadStrategy ?? { type: 'first' },
      defaultWriteStrategy: routingConfig.defaultWriteStrategy ?? { type: 'sync' },
      enableHealthCheck: routingConfig.enableHealthCheck ?? true,
      healthCheckIntervalMs: routingConfig.healthCheckIntervalMs ?? 30000,
      enableAutoFailover: routingConfig.enableAutoFailover ?? true,
      circuitBreakerThreshold: routingConfig.circuitBreakerThreshold ?? 5
    };

    this.memoryCache = new NodeCache({
      stdTTL: this.config.defaultTTL,
      checkperiod: this.config.checkperiod,
      maxKeys: this.config.maxKeys
    });

    this.cacheStats = { hits: 0, misses: 0, sets: 0, deletes: 0 };
    this.setupExpirationListener();

    this.sourceManager = new DataSourceManager(
      this.routingConfig.enableHealthCheck,
      this.routingConfig.healthCheckIntervalMs
    );
    this.hashRouter = new ConsistentHashRouter(100);

    const defaultBuckets = [1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500];
    this.getHistogram = new HistogramRecorder(defaultBuckets);
    this.setHistogram = new HistogramRecorder(defaultBuckets);
    this.deleteHistogram = new HistogramRecorder(defaultBuckets);
    this.hasHistogram = new HistogramRecorder(defaultBuckets);
    this.getManyHistogram = new HistogramRecorder(defaultBuckets);
    this.setManyHistogram = new HistogramRecorder(defaultBuckets);

    this.operationStats = {
      get: this.getHistogram.getStats(),
      set: this.setHistogram.getStats(),
      delete: this.deleteHistogram.getStats(),
      has: this.hasHistogram.getStats(),
      getMany: this.getManyHistogram.getStats(),
      setMany: this.setManyHistogram.getStats()
    };
    this.statsResetAt = Date.now();

    if (this.routingConfig.defaultWriteStrategy.type === 'async') {
      this.startAsyncWriteLoop();
    }
  }

  private setupExpirationListener(): void {
    this.memoryCache.on('expired', (key: string) => {
      logger.debug({ key }, '缓存条目已过期');
    });

    this.memoryCache.on('del', (key: string) => {
      logger.debug({ key }, '缓存条目已删除');
    });
  }

  private startAsyncWriteLoop(): void {
    this.asyncWriteTimer = setInterval(() => {
      this.processAsyncWrites().catch(error => {
        logger.error({ error }, '异步写入处理失败');
      });
    }, this.routingConfig.defaultWriteStrategy.asyncWriteDelayMs ?? 1000);
  }

  private async processAsyncWrites(): Promise<void> {
    const entries = Array.from(this.pendingAsyncWrites.entries());
    this.pendingAsyncWrites.clear();

    for (const [key, { value, ttl, sources }] of entries) {
      for (const sourceId of sources) {
        const source = this.sourceManager.getSource(sourceId);
        if (source && source.set) {
          try {
            await source.set(key, value, ttl);
            this.sourceManager.recordSuccess(sourceId);
          } catch (error) {
            this.sourceManager.recordFailure(sourceId);
            logger.warn({ key, sourceId, error }, '异步写入失败，将重试');
            this.pendingAsyncWrites.set(key, { value, ttl, sources });
          }
        }
      }
    }
  }

  addDataSource(source: DataSource): void {
    this.sourceManager.addSource(source);
    this.hashRouter.addSource(source.id, source.priority);
  }

  removeDataSource(sourceId: string): boolean {
    this.hashRouter.removeSource(sourceId);
    return this.sourceManager.removeSource(sourceId);
  }

  addRoutingRule(rule: Omit<RoutingRule, 'id'>): RoutingRule {
    const fullRule: RoutingRule = {
      ...rule,
      id: generateId('rule_')
    };
    this.routingRules.push(fullRule);
    this.routingRules.sort((a, b) => b.priority - a.priority);
    logger.info({ ruleId: fullRule.id, pattern: rule.pattern.source }, '添加路由规则');
    return fullRule;
  }

  removeRoutingRule(ruleId: string): boolean {
    const index = this.routingRules.findIndex(r => r.id === ruleId);
    if (index > -1) {
      this.routingRules.splice(index, 1);
      return true;
    }
    return false;
  }

  private matchRoutingRules(key: string, value?: unknown): RoutingRule | null {
    for (const rule of this.routingRules) {
      if (!rule.enabled) continue;
      if (rule.pattern.test(key)) {
        if (!rule.condition || rule.condition(key, value)) {
          return rule;
        }
      }
    }
    return null;
  }

  private async readFromSources<T>(
    key: string,
    sources: string[],
    strategy: ReadStrategy
  ): Promise<T | null> {
    const availableSources = sources
      .map(id => this.sourceManager.getSource(id))
      .filter((s): s is DataSource => s !== undefined && s.isReadable && s.health !== 'unhealthy');

    if (availableSources.length === 0) {
      return null;
    }

    switch (strategy.type) {
      case 'first': {
        for (const source of availableSources) {
          try {
            if (source.get) {
              const result = await source.get<T>(key);
              if (result !== null) {
                this.sourceManager.recordSuccess(source.id);
                return result;
              }
            }
          } catch (error) {
            this.sourceManager.recordFailure(source.id);
            logger.warn({ key, sourceId: source.id, error }, '读取数据源失败，尝试下一个');
          }
        }
        return null;
      }

      case 'parallel': {
        const maxSources = strategy.maxParallelSources ?? availableSources.length;
        const selectedSources = availableSources.slice(0, maxSources);
        const promises = selectedSources.map(async s => {
          try {
            if (s.get) {
              const result = await s.get<T>(key);
              this.sourceManager.recordSuccess(s.id);
              return result;
            }
          } catch (error) {
            this.sourceManager.recordFailure(s.id);
          }
          return null;
        });

        const results = await Promise.all(promises);
        return results.find(r => r !== null) ?? null;
      }

      case 'sticky': {
        const sourceId = this.hashRouter.getSource(key);
        if (sourceId) {
          const source = availableSources.find(s => s.id === sourceId);
          if (source && source.get) {
            try {
              const result = await source.get<T>(key);
              this.sourceManager.recordSuccess(source.id);
              return result;
            } catch (error) {
              this.sourceManager.recordFailure(source.id);
            }
          }
        }
        for (const source of availableSources) {
          try {
            if (source.get) {
              const result = await source.get<T>(key);
              if (result !== null) {
                this.sourceManager.recordSuccess(source.id);
                return result;
              }
            }
          } catch (error) {
            this.sourceManager.recordFailure(source.id);
          }
        }
        return null;
      }

      case 'consistent-hash': {
        const sourceId = this.hashRouter.getSource(key);
        if (sourceId) {
          const source = availableSources.find(s => s.id === sourceId);
          if (source && source.get) {
            try {
              const result = await source.get<T>(key);
              this.sourceManager.recordSuccess(source.id);
              return result;
            } catch (error) {
              this.sourceManager.recordFailure(source.id);
            }
          }
        }
        return null;
      }

      default:
        return null;
    }
  }

  private async writeToSources<T>(
    key: string,
    value: T,
    sources: string[],
    strategy: WriteStrategy,
    ttl?: number
  ): Promise<void> {
    const availableSources = sources
      .map(id => this.sourceManager.getSource(id))
      .filter((s): s is DataSource => s !== undefined && s.isWritable && s.health !== 'unhealthy');

    if (availableSources.length === 0) {
      throw new Error('没有可用的写入数据源');
    }

    switch (strategy.type) {
      case 'sync': {
        const requiredWrites = strategy.requiredWrites ?? availableSources.length;
        const promises = availableSources.map(async s => {
          if (s.set) {
            try {
              const result = await s.set(key, value, ttl);
              this.sourceManager.recordSuccess(s.id);
              return result;
            } catch (error) {
              this.sourceManager.recordFailure(s.id);
              throw error;
            }
          }
          return false;
        });

        const results = await Promise.allSettled(promises);
        const successful = results.filter(r => r.status === 'fulfilled' && r.value).length;

        if (successful < requiredWrites) {
          throw new Error(`写入失败：成功 ${successful} 次，需要 ${requiredWrites} 次`);
        }
        break;
      }

      case 'async': {
        for (const source of availableSources) {
          if (source.set) {
            this.pendingAsyncWrites.set(key, {
              value,
              ttl,
              sources: availableSources.map(s => s.id)
            });
          }
        }
        break;
      }

      case 'quorum': {
        const requiredWrites = strategy.requiredWrites ?? Math.ceil(availableSources.length / 2) + 1;
        let successful = 0;
        const promises = availableSources.map(async s => {
          if (s.set) {
            try {
              const result = await s.set(key, value, ttl);
              this.sourceManager.recordSuccess(s.id);
              return result;
            } catch (error) {
              this.sourceManager.recordFailure(s.id);
            }
          }
          return false;
        });

        for (const promise of promises) {
          const result = await promise;
          if (result) successful++;
          if (successful >= requiredWrites) break;
        }

        if (successful < requiredWrites) {
          throw new Error(`Quorum写入失败：成功 ${successful} 次，需要 ${requiredWrites} 次`);
        }
        break;
      }
    }
  }

  async get<T>(key: string, options: DataAccessOptions = {}): Promise<T | null> {
    const startTime = Date.now();
    try {
      if (options.skipCache) {
        logger.debug({ key }, '跳过缓存读取');
        return this.getFromDataSource<T>(key, options);
      }

      const value = this.memoryCache.get<T>(key);
      if (value !== undefined) {
        this.cacheStats.hits++;
        logger.debug({ key }, '缓存命中');
        return value;
      }

      this.cacheStats.misses++;
      logger.debug({ key }, '缓存未命中，尝试从数据源读取');

      const sourceValue = await this.getFromDataSource<T>(key, options);
      if (sourceValue !== null && !options.skipCache) {
        await this.set(key, sourceValue, options);
      }

      return sourceValue;
    } finally {
      this.getHistogram.record(Date.now() - startTime);
    }
  }

  private async getFromDataSource<T>(key: string, options: DataAccessOptions): Promise<T | null> {
    const rule = this.matchRoutingRules(key);
    let sources: string[];
    let strategy = this.routingConfig.defaultReadStrategy;

    if (options.preferredSource) {
      sources = [options.preferredSource];
    } else if (rule) {
      sources = rule.readSources;
    } else {
      sources = this.sourceManager.getReadableSources().map(s => s.id);
    }

    if (sources.length === 0) {
      return null;
    }

    return this.readFromSources<T>(key, sources, strategy);
  }

  async set<T>(key: string, value: T, options: DataAccessOptions = {}): Promise<void> {
    const startTime = Date.now();
    try {
      const ttl = options.ttl ?? this.config.defaultTTL;
      const success = this.memoryCache.set(key, value, ttl);

      if (success) {
        this.cacheStats.sets++;
        logger.debug({ key, ttl }, '内存缓存设置成功');
      } else {
        logger.warn({ key }, '内存缓存设置失败');
      }

      await this.setToDataSource(key, value, options);
    } finally {
      this.setHistogram.record(Date.now() - startTime);
    }
  }

  private async setToDataSource<T>(key: string, value: T, options: DataAccessOptions): Promise<void> {
    const rule = this.matchRoutingRules(key, value);
    let sources: string[];
    let strategy = this.routingConfig.defaultWriteStrategy;

    if (rule) {
      sources = rule.writeSources;
    } else {
      sources = this.sourceManager.getWritableSources().map(s => s.id);
    }

    if (sources.length === 0) {
      return;
    }

    try {
      await this.writeToSources(key, value, sources, strategy, options.ttl);
    } catch (error) {
      logger.error({ key, error }, '数据源写入失败');
      if (this.routingConfig.enableAutoFailover) {
        const fallbackSources = this.sourceManager.getWritableSources()
          .map(s => s.id)
          .filter(id => !sources.includes(id));
        if (fallbackSources.length > 0) {
          logger.info({ key, fallbackSources }, '尝试使用备用数据源写入');
          await this.writeToSources(key, value, fallbackSources, strategy, options.ttl);
        }
      }
    }
  }

  async delete(key: string): Promise<boolean> {
    const startTime = Date.now();
    try {
      let deleted = false;

      const memoryDeleted = this.memoryCache.del(key);
      if (memoryDeleted > 0) {
        this.cacheStats.deletes++;
        deleted = true;
        logger.debug({ key }, '内存缓存删除成功');
      }

      for (const source of this.sourceManager.getWritableSources()) {
        if (source.delete) {
          try {
            const sourceDeleted = await source.delete(key);
            if (sourceDeleted) {
              deleted = true;
              this.sourceManager.recordSuccess(source.id);
            }
          } catch (error) {
            this.sourceManager.recordFailure(source.id);
            logger.warn({ key, sourceId: source.id, error }, '数据源删除失败');
          }
        }
      }

      return deleted;
    } finally {
      this.deleteHistogram.record(Date.now() - startTime);
    }
  }

  async has(key: string): Promise<boolean> {
    const startTime = Date.now();
    try {
      if (this.memoryCache.has(key)) {
        return true;
      }

      for (const source of this.sourceManager.getReadableSources()) {
        if (source.has) {
          try {
            const exists = await source.has(key);
            if (exists) {
              this.sourceManager.recordSuccess(source.id);
              return true;
            }
          } catch (error) {
            this.sourceManager.recordFailure(source.id);
          }
        }
      }

      return false;
    } finally {
      this.hasHistogram.record(Date.now() - startTime);
    }
  }

  async getOrSet<T>(
    key: string,
    fetcher: () => Promise<T>,
    options: DataAccessOptions = {}
  ): Promise<T> {
    if (!options.forceRefresh) {
      const cached = await this.get<T>(key, options);
      if (cached !== null) {
        return cached;
      }
    }

    logger.debug({ key }, '执行数据获取');
    const value = await fetcher();
    await this.set(key, value, options);
    return value;
  }

  async getMany<T>(keys: string[]): Promise<Map<string, T | null>> {
    const startTime = Date.now();
    try {
      const result = new Map<string, T | null>();
      const values = this.memoryCache.mget<T>(keys);

      const missingKeys: string[] = [];
      for (const key of keys) {
        const value = values[key];
        if (value !== undefined) {
          this.cacheStats.hits++;
          result.set(key, value);
        } else {
          this.cacheStats.misses++;
          missingKeys.push(key);
        }
      }

      if (missingKeys.length > 0) {
        const readableSources = this.sourceManager.getReadableSources();
        for (const source of readableSources) {
          if (source.getMany && missingKeys.length > 0) {
            try {
              const sourceResults = await source.getMany<T>(missingKeys);
              for (const [key, value] of sourceResults) {
                if (value !== null && !result.has(key)) {
                  result.set(key, value);
                  const index = missingKeys.indexOf(key);
                  if (index > -1) missingKeys.splice(index, 1);
                }
              }
              this.sourceManager.recordSuccess(source.id);
            } catch (error) {
              this.sourceManager.recordFailure(source.id);
            }
          }
        }
      }

      for (const key of missingKeys) {
        if (!result.has(key)) {
          result.set(key, null);
        }
      }

      return result;
    } finally {
      this.getManyHistogram.record(Date.now() - startTime);
    }
  }

  async setMany<T>(entries: Array<{ key: string; value: T; ttl?: number }>): Promise<void> {
    const startTime = Date.now();
    try {
      for (const entry of entries) {
        await this.set(entry.key, entry.value, { ttl: entry.ttl });
      }
    } finally {
      this.setManyHistogram.record(Date.now() - startTime);
    }
  }

  async deleteMany(keys: string[]): Promise<number> {
    let deleted = 0;
    for (const key of keys) {
      if (await this.delete(key)) {
        deleted++;
      }
    }
    return deleted;
  }

  async invalidatePattern(pattern: string): Promise<number> {
    const regex = new RegExp(pattern);
    const allKeys = this.memoryCache.keys();
    const matchingKeys = allKeys.filter(key => regex.test(key));

    return this.deleteMany(matchingKeys);
  }

  async clear(): Promise<void> {
    this.memoryCache.flushAll();
    logger.info('内存缓存已全部清空');

    for (const source of this.sourceManager.getWritableSources()) {
      if (source.type === 'memory') {
        logger.info({ sourceId: source.id }, '跳过内存数据源清空');
      }
    }
  }

  getTimingStats(): OperationStats {
    return {
      get: this.getHistogram.getStats(),
      set: this.setHistogram.getStats(),
      delete: this.deleteHistogram.getStats(),
      has: this.hasHistogram.getStats(),
      getMany: this.getManyHistogram.getStats(),
      setMany: this.setManyHistogram.getStats()
    };
  }

  getPrometheusMetrics(): PrometheusMetric[] {
    const metrics: PrometheusMetric[] = [];
    const totalRequests = this.cacheStats.hits + this.cacheStats.misses;

    metrics.push({
      name: 'data_access_cache_hits_total',
      help: 'Total number of cache hits',
      type: 'counter',
      value: this.cacheStats.hits
    });

    metrics.push({
      name: 'data_access_cache_misses_total',
      help: 'Total number of cache misses',
      type: 'counter',
      value: this.cacheStats.misses
    });

    metrics.push({
      name: 'data_access_cache_hit_rate',
      help: 'Cache hit rate',
      type: 'gauge',
      value: totalRequests > 0 ? this.cacheStats.hits / totalRequests : 0
    });

    metrics.push({
      name: 'data_access_cache_sets_total',
      help: 'Total number of cache sets',
      type: 'counter',
      value: this.cacheStats.sets
    });

    metrics.push({
      name: 'data_access_cache_entries',
      help: 'Number of entries in cache',
      type: 'gauge',
      value: this.memoryCache.keys().length
    });

    const timingStats = this.getTimingStats();
    for (const [op, stats] of Object.entries(timingStats)) {
      metrics.push({
        name: `data_access_operation_duration_seconds_count`,
        help: `Count of ${op} operations`,
        type: 'histogram',
        labels: { operation: op },
        value: stats.totalOperations
      });

      metrics.push({
        name: `data_access_operation_duration_seconds_sum`,
        help: `Sum of ${op} operation durations`,
        type: 'histogram',
        labels: { operation: op },
        value: stats.totalTimeMs / 1000
      });
    }

    const sources = this.sourceManager.listSources();
    for (const source of sources) {
      metrics.push({
        name: 'data_access_source_health',
        help: 'Health status of data source',
        type: 'gauge',
        labels: { source_id: source.id, source_name: source.name, source_type: source.type },
        value: source.health === 'healthy' ? 1 : source.health === 'degraded' ? 0.5 : 0
      });
    }

    const circuitBreakerStats = this.sourceManager.getCircuitBreakerStats();
    for (const stat of circuitBreakerStats) {
      metrics.push({
        name: 'data_access_circuit_breaker_open',
        help: 'Circuit breaker status (1=open, 0=closed)',
        type: 'gauge',
        labels: { source_id: stat.sourceId },
        value: stat.open ? 1 : 0
      });

      metrics.push({
        name: 'data_access_circuit_breaker_failures',
        help: 'Number of consecutive failures',
        type: 'gauge',
        labels: { source_id: stat.sourceId },
        value: stat.failures
      });
    }

    return metrics;
  }

  getPrometheusTextFormat(): string {
    const metrics = this.getPrometheusMetrics();
    let output = '';

    const groupedMetrics = new Map<string, PrometheusMetric[]>();
    for (const metric of metrics) {
      if (!groupedMetrics.has(metric.name)) {
        groupedMetrics.set(metric.name, []);
      }
      groupedMetrics.get(metric.name)!.push(metric);
    }

    for (const [name, metricGroup] of groupedMetrics) {
      const first = metricGroup[0];
      output += `# HELP ${name} ${first.help}\n`;
      output += `# TYPE ${name} ${first.type}\n`;

      for (const metric of metricGroup) {
        const labelStr = metric.labels
          ? Object.entries(metric.labels)
              .map(([k, v]) => `${k}="${v}"`)
              .join(',')
          : '';
        
        if (labelStr) {
          output += `${name}{${labelStr}} ${metric.value}\n`;
        } else {
          output += `${name} ${metric.value}\n`;
        }
      }
    }

    return output;
  }

  resetTimingStats(): void {
    this.getHistogram.reset();
    this.setHistogram.reset();
    this.deleteHistogram.reset();
    this.hasHistogram.reset();
    this.getManyHistogram.reset();
    this.setManyHistogram.reset();
    this.statsResetAt = Date.now();
    logger.info('数据访问层计时统计已重置');
  }

  getStats(): {
    hits: number;
    misses: number;
    hitRate: number;
    sets: number;
    deletes: number;
    keys: number;
    dataSources: Array<{ id: string; name: string; health: string; type: string }>;
    routingRules: number;
    pendingAsyncWrites: number;
    timingStats?: OperationStats;
    statsResetAt: number;
  } {
    const total = this.cacheStats.hits + this.cacheStats.misses;
    return {
      hits: this.cacheStats.hits,
      misses: this.cacheStats.misses,
      hitRate: total > 0 ? this.cacheStats.hits / total : 0,
      sets: this.cacheStats.sets,
      deletes: this.cacheStats.deletes,
      keys: this.memoryCache.keys().length,
      dataSources: this.sourceManager.listSources().map(s => ({
        id: s.id,
        name: s.name,
        health: s.health,
        type: s.type
      })),
      routingRules: this.routingRules.filter(r => r.enabled).length,
      pendingAsyncWrites: this.pendingAsyncWrites.size,
      timingStats: this.getTimingStats(),
      statsResetAt: this.statsResetAt
    };
  }

  async getEntry<T>(key: string): Promise<CacheEntry<T> | null> {
    const value = this.memoryCache.get<T>(key);
    if (value === undefined) return null;

    const ttl = this.memoryCache.getTtl(key);
    return {
      key,
      value,
      createdAt: ttl ? ttl - (this.config.defaultTTL * 1000) : Date.now(),
      expiresAt: ttl ?? Date.now() + this.config.defaultTTL * 1000,
      hits: 0
    };
  }

  generateCacheKey(...parts: string[]): string {
    return parts.join(':');
  }

  resetStats(): void {
    this.cacheStats = { hits: 0, misses: 0, sets: 0, deletes: 0 };
    this.resetTimingStats();
  }

  getSourceManager(): DataSourceManager {
    return this.sourceManager;
  }

  getRoutingRules(): RoutingRule[] {
    return [...this.routingRules];
  }

  stop(): void {
    this.sourceManager.stop();
    if (this.asyncWriteTimer) {
      clearInterval(this.asyncWriteTimer);
    }
  }
}

export class CacheInvalidationManager {
  private invalidationRules: Map<string, Array<{ pattern: string; ttl?: number }>> = new Map();

  registerRule(entityType: string, pattern: string, ttl?: number): void {
    if (!this.invalidationRules.has(entityType)) {
      this.invalidationRules.set(entityType, []);
    }
    this.invalidationRules.get(entityType)!.push({ pattern, ttl });
    logger.info({ entityType, pattern }, '注册缓存失效规则');
  }

  async invalidate(cache: DataAccessLayer, entityType: string, entityId?: string): Promise<number> {
    const rules = this.invalidationRules.get(entityType);
    if (!rules) return 0;

    let totalInvalidated = 0;
    for (const rule of rules) {
      const pattern = entityId ? rule.pattern.replace('{id}', entityId) : rule.pattern;
      const invalidated = await cache.invalidatePattern(pattern);
      totalInvalidated += invalidated;
    }

    logger.info({ entityType, entityId, totalInvalidated }, '缓存失效完成');
    return totalInvalidated;
  }

  getRules(): Map<string, Array<{ pattern: string; ttl?: number }>> {
    return new Map(this.invalidationRules);
  }
}

export default DataAccessLayer;
