import { EventEmitter, getCurrentTimestamp } from '../utils';
import logger from '../utils/logger';
import metricsService from '../metrics';
import cacheManager from '../data-access';
import { InMemoryCache } from '../data-access';

export interface PerformanceMetric {
  name: string;
  value: number;
  unit: string;
  timestamp: string;
  tags?: Record<string, string>;
}

export interface HealthCheckResult {
  name: string;
  status: 'healthy' | 'degraded' | 'unhealthy';
  details?: Record<string, any>;
  timestamp: string;
  duration_ms: number;
}

export interface SystemStatus {
  overall_status: 'healthy' | 'degraded' | 'unhealthy';
  components: HealthCheckResult[];
  timestamp: string;
}

interface MonitoringEvents {
  'metric.collected': PerformanceMetric;
  'healthcheck.completed': HealthCheckResult;
  'alert.fired': { metric: string; threshold: number; value: number };
}

export type HealthCheckFn = () => Promise<HealthCheckResult>;

export class PerformanceMonitor extends EventEmitter<MonitoringEvents> {
  private metrics: Map<string, number[]>;
  private healthChecks: Map<string, HealthCheckFn>;
  private collectionInterval: NodeJS.Timeout | null = null;
  private metricCache: InMemoryCache<number>;

  constructor() {
    super();
    this.metrics = new Map();
    this.healthChecks = new Map();
    this.metricCache = cacheManager.createInMemoryCache<number>('performance_metrics', {
      default_ttl: 3600000,
      max_size: 10000,
    });
  }

  recordMetric(name: string, value: number, tags?: Record<string, string>): void {
    if (!this.metrics.has(name)) {
      this.metrics.set(name, []);
    }
    const values = this.metrics.get(name)!;
    values.push(value);
    if (values.length > 1000) {
      values.shift();
    }

    const metric: PerformanceMetric = {
      name,
      value,
      unit: this.getUnitForMetric(name),
      timestamp: getCurrentTimestamp(),
      tags,
    };

    this.emit('metric.collected', metric);
    metricsService.recordMetric(name, value, tags);
    this.metricCache.set(`${name}:${Date.now()}`, value);
  }

  increment(name: string, tags?: Record<string, string>): void {
    this.recordMetric(name, 1, tags);
  }

  decrement(name: string, tags?: Record<string, string>): void {
    this.recordMetric(name, -1, tags);
  }

  gauge(name: string, value: number, tags?: Record<string, string>): void {
    this.recordMetric(name, value, tags);
  }

  histogram(name: string, value: number, tags?: Record<string, string>): void {
    this.recordMetric(name, value, tags);
  }

  timing(name: string, durationMs: number, tags?: Record<string, string>): void {
    this.recordMetric(`${name}_duration`, durationMs, { ...tags, unit: 'ms' });
  }

  async measure<T>(name: string, fn: () => Promise<T>, tags?: Record<string, string>): Promise<T> {
    const start = Date.now();
    try {
      const result = await fn();
      this.recordMetric(`${name}_success`, 1, tags);
      return result;
    } catch (error) {
      this.recordMetric(`${name}_error`, 1, tags);
      throw error;
    } finally {
      const duration = Date.now() - start;
      this.timing(name, duration, tags);
    }
  }

  private getUnitForMetric(name: string): string {
    if (name.includes('duration') || name.includes('latency')) return 'ms';
    if (name.includes('size') || name.includes('bytes')) return 'bytes';
    if (name.includes('count') || name.includes('total')) return 'count';
    if (name.includes('percent') || name.includes('ratio')) return '%';
    return 'unknown';
  }

  getMetricValues(name: string): number[] {
    return this.metrics.get(name) || [];
  }

  getMetricSummary(name: string): { avg: number; min: number; max: number; count: number; p95: number; p99: number } | null {
    const values = this.metrics.get(name);
    if (!values || values.length === 0) return null;

    const sorted = [...values].sort((a, b) => a - b);
    const avg = sorted.reduce((s, v) => s + v, 0) / sorted.length;
    const p95Index = Math.ceil(0.95 * sorted.length) - 1;
    const p99Index = Math.ceil(0.99 * sorted.length) - 1;

    return {
      avg,
      min: sorted[0],
      max: sorted[sorted.length - 1],
      count: sorted.length,
      p95: sorted[p95Index],
      p99: sorted[p99Index],
    };
  }

  registerHealthCheck(name: string, fn: HealthCheckFn): void {
    this.healthChecks.set(name, fn);
    logger.info(`Registered health check: ${name}`);
  }

  unregisterHealthCheck(name: string): boolean {
    return this.healthChecks.delete(name);
  }

  async runHealthCheck(name: string): Promise<HealthCheckResult | null> {
    const fn = this.healthChecks.get(name);
    if (!fn) return null;

    const start = Date.now();
    try {
      const result = await fn();
      result.duration_ms = Date.now() - start;
      result.timestamp = getCurrentTimestamp();
      this.emit('healthcheck.completed', result);
      return result;
    } catch (error) {
      return {
        name,
        status: 'unhealthy',
        details: { error: (error as Error).message },
        timestamp: getCurrentTimestamp(),
        duration_ms: Date.now() - start,
      };
    }
  }

  async runAllHealthChecks(): Promise<SystemStatus> {
    const results: HealthCheckResult[] = [];
    for (const [name] of this.healthChecks) {
      const result = await this.runHealthCheck(name);
      if (result) {
        results.push(result);
      }
    }

    const hasUnhealthy = results.some((r) => r.status === 'unhealthy');
    const hasDegraded = results.some((r) => r.status === 'degraded');

    let overall_status: 'healthy' | 'degraded' | 'unhealthy' = 'healthy';
    if (hasUnhealthy) {
      overall_status = 'unhealthy';
    } else if (hasDegraded) {
      overall_status = 'degraded';
    }

    return {
      overall_status,
      components: results,
      timestamp: getCurrentTimestamp(),
    };
  }

  startHealthChecks(intervalMs: number = 30000): void {
    if (this.collectionInterval) {
      clearInterval(this.collectionInterval);
    }
    this.collectionInterval = setInterval(() => {
      this.runAllHealthChecks().catch((error) => {
        logger.error('Health checks failed:', error);
      });
    }, intervalMs);
    logger.info(`Started health checks with interval: ${intervalMs}ms`);
  }

  stopHealthChecks(): void {
    if (this.collectionInterval) {
      clearInterval(this.collectionInterval);
      this.collectionInterval = null;
      logger.info('Stopped health checks');
    }
  }

  async queryMetrics(
    name: string,
    startTime: number,
    endTime: number
  ): Promise<Array<{ timestamp: number; value: number }>> {
    return metricsService.getMetricValues(name, startTime, endTime);
  }

  exportMetrics(): Record<string, PerformanceMetric[]> {
    const result: Record<string, PerformanceMetric[]> = {};
    for (const [name, values] of this.metrics) {
      result[name] = values.map((v, i) => ({
        name,
        value: v,
        unit: this.getUnitForMetric(name),
        timestamp: new Date(Date.now() - (values.length - i) * 1000).toISOString(),
      }));
    }
    return result;
  }

  clear(): void {
    this.stopHealthChecks();
    this.metrics.clear();
    this.healthChecks.clear();
  }
}

export class RequestMetricsMiddleware {
  private monitor: PerformanceMonitor;

  constructor(monitor: PerformanceMonitor) {
    this.monitor = monitor;
  }

  getMiddleware() {
    return (req: any, res: any, next: any) => {
      const start = Date.now();
      const method = req.method;
      const path = req.route?.path || req.path || 'unknown';

      res.on('finish', () => {
        const duration = Date.now() - start;
        const statusCode = res.statusCode;
        const tags = {
          method,
          path,
          status_code: statusCode.toString(),
        };

        this.monitor.timing('http_request', duration, tags);
        this.monitor.increment(`http_request_${statusCode}`, tags);

        if (statusCode >= 500) {
          this.monitor.increment('http_request_error', tags);
        } else if (statusCode >= 400) {
          this.monitor.increment('http_request_client_error', tags);
        } else {
          this.monitor.increment('http_request_success', tags);
        }
      });

      next();
    };
  }
}

const performanceMonitor = new PerformanceMonitor();

export default performanceMonitor;
