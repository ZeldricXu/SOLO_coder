import { EventEmitter } from 'events';
import { StatsSnapshot } from '../types';
interface MetricDataPoint {
    timestamp: number;
    value: number;
    dimensions: Record<string, string>;
}
interface MetricDefinition {
    name: string;
    description: string;
    unit: string;
    type: 'counter' | 'gauge' | 'histogram' | 'timer';
    labels: string[];
    retention_days: number;
}
interface AggregatedMetrics {
    count: number;
    sum: number;
    avg: number;
    min: number;
    max: number;
    p50: number;
    p95: number;
    p99: number;
}
interface AlertRule {
    rule_id: string;
    metric_name: string;
    condition: 'gt' | 'lt' | 'gte' | 'lte' | 'eq';
    threshold: number;
    duration: number;
    enabled: boolean;
    notification_channels: string[];
    created_at: string;
}
declare class MonitoringService extends EventEmitter {
    private metrics;
    private definitions;
    private counters;
    private gauges;
    private timers;
    private snapshots;
    private alertRules;
    private cache;
    private maxDataPointsPerMetric;
    private maxSnapshots;
    private aggregationIntervals;
    constructor();
    private registerDefaultMetrics;
    registerMetric(definition: MetricDefinition): void;
    incrementCounter(metricName: string, value?: number, dimensions?: Record<string, string>): void;
    setGauge(metricName: string, value: number, dimensions?: Record<string, string>): void;
    startTimer(metricName: string, dimensions?: Record<string, string>): string;
    stopTimer(metricName: string, timerId: string, dimensions?: Record<string, string>): number;
    recordLatency(metricName: string, duration: number, dimensions?: Record<string, string>): void;
    private recordDataPoint;
    getMetricValues(metricName: string, dimensions?: Record<string, string>, startTime?: number, endTime?: number): MetricDataPoint[];
    aggregateMetrics(metricName: string, dimensions?: Record<string, string>, startTime?: number, endTime?: number): AggregatedMetrics | null;
    createSnapshot(dimensions: {
        host: string;
        region: string;
        [key: string]: string;
    }): StatsSnapshot;
    getSnapshots(limit?: number, startTime?: number, endTime?: number): StatsSnapshot[];
    createAlertRule(metricName: string, condition: AlertRule['condition'], threshold: number, duration: number, notificationChannels: string[]): AlertRule;
    private checkAlertRules;
    getMetricDefinitions(): MetricDefinition[];
    getCurrentCounters(dimensions?: Record<string, string>): Record<string, number>;
    getCurrentGauges(dimensions?: Record<string, string>): Record<string, number>;
    resetMetric(metricName: string, dimensions?: Record<string, string>): void;
    resetAll(): void;
    generateReport(startTime: number, endTime: number): Record<string, unknown>;
    private getDimensionKey;
    private keyMatchesDimensions;
}
export declare const monitoring: MonitoringService;
export { MonitoringService, MetricDefinition, MetricDataPoint, AggregatedMetrics, AlertRule };
//# sourceMappingURL=index.d.ts.map