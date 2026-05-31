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
    setMany?: <T>(entries: Array<{
        key: string;
        value: T;
        ttl?: number;
    }>) => Promise<void>;
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
export declare class ConsistentHashRouter {
    private ring;
    private virtualNodes;
    private sourceWeights;
    constructor(virtualNodes?: number);
    addSource(sourceId: string, weight?: number): void;
    removeSource(sourceId: string): void;
    private rebuildRing;
    private hash;
    getSource(key: string): string | null;
    getSources(key: string, count: number): string[];
}
export declare class DataSourceManager {
    private enableHealthCheck;
    private healthCheckIntervalMs;
    private sources;
    private healthCheckTimer?;
    private circuitBreakerStates;
    constructor(enableHealthCheck?: boolean, healthCheckIntervalMs?: number);
    addSource(source: DataSource): void;
    removeSource(sourceId: string): boolean;
    getSource(sourceId: string): DataSource | undefined;
    getReadableSources(): DataSource[];
    getWritableSources(): DataSource[];
    recordFailure(sourceId: string): void;
    recordSuccess(sourceId: string): void;
    private startHealthCheckLoop;
    private checkAllSources;
    stop(): void;
    listSources(): DataSource[];
    getCircuitBreakerStats(): Array<{
        sourceId: string;
        failures: number;
        open: boolean;
        lastFailure: number;
    }>;
}
export declare class DataAccessLayer {
    private memoryCache;
    private config;
    private cacheStats;
    private sourceManager;
    private routingRules;
    private hashRouter;
    private routingConfig;
    private pendingAsyncWrites;
    private asyncWriteTimer?;
    private operationStats;
    private getHistogram;
    private setHistogram;
    private deleteHistogram;
    private hasHistogram;
    private getManyHistogram;
    private setManyHistogram;
    private statsResetAt;
    constructor(config?: Partial<CacheConfig>, routingConfig?: Partial<RoutingConfig>);
    private setupExpirationListener;
    private startAsyncWriteLoop;
    private processAsyncWrites;
    addDataSource(source: DataSource): void;
    removeDataSource(sourceId: string): boolean;
    addRoutingRule(rule: Omit<RoutingRule, 'id'>): RoutingRule;
    removeRoutingRule(ruleId: string): boolean;
    private matchRoutingRules;
    private readFromSources;
    private writeToSources;
    get<T>(key: string, options?: DataAccessOptions): Promise<T | null>;
    private getFromDataSource;
    set<T>(key: string, value: T, options?: DataAccessOptions): Promise<void>;
    private setToDataSource;
    delete(key: string): Promise<boolean>;
    has(key: string): Promise<boolean>;
    getOrSet<T>(key: string, fetcher: () => Promise<T>, options?: DataAccessOptions): Promise<T>;
    getMany<T>(keys: string[]): Promise<Map<string, T | null>>;
    setMany<T>(entries: Array<{
        key: string;
        value: T;
        ttl?: number;
    }>): Promise<void>;
    deleteMany(keys: string[]): Promise<number>;
    invalidatePattern(pattern: string): Promise<number>;
    clear(): Promise<void>;
    getTimingStats(): OperationStats;
    getPrometheusMetrics(): PrometheusMetric[];
    getPrometheusTextFormat(): string;
    resetTimingStats(): void;
    getStats(): {
        hits: number;
        misses: number;
        hitRate: number;
        sets: number;
        deletes: number;
        keys: number;
        dataSources: Array<{
            id: string;
            name: string;
            health: string;
            type: string;
        }>;
        routingRules: number;
        pendingAsyncWrites: number;
        timingStats?: OperationStats;
        statsResetAt: number;
    };
    getEntry<T>(key: string): Promise<CacheEntry<T> | null>;
    generateCacheKey(...parts: string[]): string;
    resetStats(): void;
    getSourceManager(): DataSourceManager;
    getRoutingRules(): RoutingRule[];
    stop(): void;
}
export declare class CacheInvalidationManager {
    private invalidationRules;
    registerRule(entityType: string, pattern: string, ttl?: number): void;
    invalidate(cache: DataAccessLayer, entityType: string, entityId?: string): Promise<number>;
    getRules(): Map<string, Array<{
        pattern: string;
        ttl?: number;
    }>>;
}
export default DataAccessLayer;
//# sourceMappingURL=index.d.ts.map