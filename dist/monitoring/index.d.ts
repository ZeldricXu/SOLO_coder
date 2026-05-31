import { MetricsSnapshot } from '../types';
export interface MetricPoint {
    name: string;
    value: number;
    timestamp: number;
    tags: Record<string, string>;
}
export interface AggregatedMetric {
    name: string;
    count: number;
    sum: number;
    avg: number;
    min: number;
    max: number;
    p50: number;
    p95: number;
    p99: number;
    tags: Record<string, string>;
    windowStart: number;
    windowEnd: number;
}
export interface BatchMetricRecord {
    name: string;
    value: number;
    tags?: Record<string, string>;
    timestamp?: number;
}
export interface MetricMergeRule {
    sourcePattern: RegExp;
    targetName: string;
    mergeType: 'sum' | 'avg' | 'max' | 'min' | 'count';
    tagAggregation?: 'keep' | 'combine' | 'drop';
}
export interface DownsamplingRule {
    metricPattern: RegExp;
    originalResolutionMs: number;
    targetResolutionMs: number;
    aggregationFunction: 'avg' | 'sum' | 'max' | 'min' | 'first' | 'last';
    retentionMs: number;
}
export interface TimeWindow {
    start: number;
    end: number;
    durationMs: number;
}
export interface BatchOperationRequest {
    operation: 'increment' | 'gauge' | 'histogram' | 'timer' | 'custom';
    name: string;
    value?: number;
    tags?: Record<string, string>;
    durationMs?: number;
    status?: 'success' | 'error';
    timestamp?: number;
}
export interface BatchOperationResult {
    operationId: string;
    successCount: number;
    failedCount: number;
    mergedCount: number;
    totalTimeMs: number;
}
export interface BatchProcessorStats {
    totalOperations: number;
    mergedOperations: number;
    batchCount: number;
    avgBatchSize: number;
    avgMergeRatio: number;
    pendingOperations: number;
}
export interface MonitoringConfig {
    retentionPeriodMs: number;
    aggregationIntervalMs: number;
    maxPointsPerMetric: number;
    enableBatching: boolean;
    batchSize: number;
    batchTimeoutMs: number;
    enableMerging: boolean;
    enableDownsampling: boolean;
    alignToWallClock: boolean;
    preAggregationCacheSize: number;
    enableBatchOperations: boolean;
    maxBatchOperations: number;
    autoMergeSimilarMetrics: boolean;
    mergeThresholdMs: number;
    asyncBatchProcessing: boolean;
    asyncBatchConcurrency: number;
}
export declare class MonitoringService {
    private metrics;
    private aggregatedMetrics;
    private preAggregationCache;
    private batchBuffer;
    private batchTimer?;
    private mergeRules;
    private downsamplingRules;
    private config;
    private aggregationTimer?;
    private onSnapshot?;
    private onBatchFlushed?;
    private priorityBatcher?;
    private asyncProcessor?;
    private batchStats;
    private operationBatches;
    private operationBatchTimer?;
    constructor(config?: Partial<MonitoringConfig>);
    private initializeBatchOperationProcessor;
    setSnapshotCallback(callback: (snapshot: MetricsSnapshot) => void): void;
    setBatchFlushedCallback(callback: (batch: BatchMetricRecord[], aggregated: Map<string, AggregatedMetric>) => void): void;
    addMergeRule(rule: MetricMergeRule): void;
    removeMergeRule(index: number): boolean;
    addDownsamplingRule(rule: DownsamplingRule): void;
    recordMetric(name: string, value: number, tags?: Record<string, string>): void;
    recordBatch(records: BatchMetricRecord[]): void;
    executeBatchOperations(operations: BatchOperationRequest[]): Promise<BatchOperationResult>;
    submitBatchOperations(operations: BatchOperationRequest[], priority?: number): void;
    private mergeSimilarOperations;
    private getOperationMergeKey;
    private executeSingleOperation;
    private processOperationBatch;
    private processBatchRecords;
    private startBatchLoop;
    private flushBatch;
    private processMetricPoint;
    private alignTimestamp;
    private applyMergeRules;
    private getValuesFromAggregated;
    private mergeAggregatedMetrics;
    increment(name: string, tags?: Record<string, string>, value?: number): void;
    gauge(name: string, value: number, tags?: Record<string, string>): void;
    histogram(name: string, value: number, tags?: Record<string, string>): void;
    withTimer<T>(name: string, fn: () => Promise<T>, tags?: Record<string, string>): Promise<T>;
    getMetric(name: string, tags?: Record<string, string>): MetricPoint[];
    getAggregatedMetrics(name: string, tags?: Record<string, string>): AggregatedMetric[];
    queryMetrics(namePattern: string, startTime: number, endTime: number, tags?: Record<string, string>): MetricPoint[];
    queryAggregated(namePattern: string, startTime: number, endTime: number, tags?: Record<string, string>): AggregatedMetric[];
    createSnapshot(dimensions?: Record<string, string>): MetricsSnapshot;
    createBatchSnapshot(windowStart: number, windowEnd: number, dimensions?: Record<string, string>): {
        snapshotId: string;
        windowStart: string;
        windowEnd: string;
        metrics: Array<{
            name: string;
            aggregated: AggregatedMetric;
        }>;
        dimensions: Record<string, string>;
    };
    private mergeMultipleAggregated;
    private calculateRate;
    private calculatePercentile;
    private calculateErrorRate;
    private aggregate;
    private applyDownsampling;
    private cleanupOldData;
    private startAggregationLoop;
    getBatchProcessorStats(): BatchProcessorStats;
    stop(): void;
    getAllMetrics(): string[];
    reset(): void;
    getStats(): {
        totalMetrics: number;
        totalAggregated: number;
        metricNames: string[];
        batchBufferSize: number;
        preAggregationCacheSize: number;
        mergeRuleCount: number;
        downsamplingRuleCount: number;
        batchProcessor?: BatchProcessorStats;
    };
    forceFlush(): number;
    private getMetricKey;
}
export default MonitoringService;
//# sourceMappingURL=index.d.ts.map