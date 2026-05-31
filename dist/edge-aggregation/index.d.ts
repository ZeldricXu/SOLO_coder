export interface DataPoint {
    timestamp: number;
    value: number;
    tags: Record<string, string>;
}
export interface AggregatedData {
    aggregationId: string;
    startTime: number;
    endTime: number;
    metrics: {
        count: number;
        sum: number;
        avg: number;
        min: number;
        max: number;
        first: number;
        last: number;
        variance?: number;
        stdDev?: number;
    };
    tags: Record<string, string>;
    rawDataPoints: number;
}
export interface WindowConfig {
    type: 'tumbling' | 'sliding' | 'session';
    durationMs: number;
    slideMs?: number;
    sessionTimeoutMs?: number;
}
export interface AggregationRule {
    ruleId: string;
    name: string;
    metricName: string;
    window: WindowConfig;
    aggregationFunctions: Array<'count' | 'sum' | 'avg' | 'min' | 'max' | 'first' | 'last'>;
    tags: string[];
    enabled: boolean;
    uploadThreshold?: {
        minDataPoints?: number;
        maxDelayMs?: number;
    };
}
export interface AggregationResult {
    ruleId: string;
    metricName: string;
    windowStart: number;
    windowEnd: number;
    data: AggregatedData;
    shouldUpload: boolean;
}
export declare class EdgeDataAggregator {
    private rules;
    private dataBuffers;
    private windowTimers;
    private onAggregationComplete?;
    private onUploadReady?;
    setAggregationCallback(callback: (result: AggregationResult) => void): void;
    setUploadCallback(callback: (aggregatedData: AggregatedData[]) => void): void;
    registerRule(rule: AggregationRule): void;
    unregisterRule(ruleId: string): void;
    ingestDataPoint(metricName: string, value: number, tags?: Record<string, string>): void;
    ingestBatch(metricName: string, values: Array<{
        value: number;
        tags: Record<string, string>;
        timestamp?: number;
    }>): void;
    forceAggregate(ruleId: string): AggregationResult[];
    triggerUpload(): void;
    private aggregatePoints;
    private checkUploadThreshold;
    private startWindowTimer;
    private processWindow;
    private matchesTags;
    private getBufferKey;
    getBufferStats(): Array<{
        ruleId: string;
        bufferSize: number;
    }>;
    stop(): void;
    clearBuffers(): void;
    getRules(): AggregationRule[];
}
//# sourceMappingURL=index.d.ts.map