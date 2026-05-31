import { MetricsCollector, metricsCollector } from './metricsCollector';
import { AlertEvaluator, alertEvaluator } from './alertEvaluator';
import { logger } from '../utils/common';

export class MonitoringManager {
  private metrics: MetricsCollector;
  private alerts: AlertEvaluator;
  private evaluationInterval: any = null;
  private snapshotInterval: any = null;

  constructor() {
    this.metrics = metricsCollector;
    this.alerts = alertEvaluator;
  }

  start(evaluationIntervalMs: number = 60000, snapshotIntervalMs: number = 30000): void {
    this.stop();

    this.evaluationInterval = setInterval(async () => {
      try {
        await this.alerts.evaluateRules();
      } catch (error) {
        logger.error(`Alert evaluation failed`, {
          error: error instanceof Error ? error.message : 'Unknown error',
        });
      }
    }, evaluationIntervalMs);

    this.snapshotInterval = setInterval(() => {
      try {
        const throughput = this.metrics.query({ name: 'requests' }).length;
        const latency = this.metrics.aggregate({ name: 'latency' });
        const errors = this.metrics.aggregate({ name: 'errors' });

        this.metrics.takeSnapshot(
          throughput,
          latency?.p99 || 0,
          errors?.avg || 0
        );
      } catch (error) {
        logger.error(`Snapshot failed`, {
          error: error instanceof Error ? error.message : 'Unknown error',
        });
      }
    }, snapshotIntervalMs);

    logger.info(`Monitoring started`, {
      evaluationIntervalMs,
      snapshotIntervalMs,
    });
  }

  stop(): void {
    if (this.evaluationInterval) {
      clearInterval(this.evaluationInterval);
      this.evaluationInterval = null;
    }
    if (this.snapshotInterval) {
      clearInterval(this.snapshotInterval);
      this.snapshotInterval = null;
    }
    logger.info(`Monitoring stopped`);
  }

  recordRequest(latencyMs: number, success: boolean = true): void {
    this.metrics.incrementCounter('requests_total');
    this.metrics.recordHistogram('request_latency', latencyMs);
    this.metrics.incrementCounter('requests', { status: success ? 'success' : 'error' });

    if (!success) {
      this.metrics.incrementCounter('errors_total');
      const errorRate = this.calculateErrorRate();
      this.metrics.setGauge('error_rate', errorRate);
    }

    const throughput = this.calculateThroughput();
    this.metrics.setGauge('throughput', throughput);
  }

  private calculateErrorRate(): number {
    const total = this.metrics.query({ name: 'requests_total' }).length;
    const errors = this.metrics.query({ name: 'errors_total' }).length;
    return total > 0 ? errors / total : 0;
  }

  private calculateThroughput(): number {
    const now = Date.now();
    const oneMinuteAgo = new Date(now - 60000).toISOString();
    const requests = this.metrics.query({ name: 'requests', startTime: oneMinuteAgo });
    return requests.length / 60;
  }

  getMetricsCollector(): MetricsCollector {
    return this.metrics;
  }

  getAlertEvaluator(): AlertEvaluator {
    return this.alerts;
  }

  getHealth(): {
    status: 'healthy' | 'degraded';
    activeAlerts: number;
    criticalAlerts: number;
    metrics: {
      totalRequests: number;
      errorRate: number;
      throughput: number;
      avgLatency: number;
    };
  } {
    const activeAlerts = this.alerts.getActiveAlerts();
    const criticalAlerts = activeAlerts.filter(a => a.severity === 'CRITICAL');
    const avgLatency = this.metrics.aggregate({ name: 'request_latency' })?.avg || 0;
    const errorRate = this.calculateErrorRate();
    const throughput = this.calculateThroughput();
    const totalRequests = this.metrics.query({ name: 'requests_total' }).length;

    return {
      status: criticalAlerts.length > 0 ? 'degraded' : 'healthy',
      activeAlerts: activeAlerts.length,
      criticalAlerts: criticalAlerts.length,
      metrics: {
        totalRequests,
        errorRate,
        throughput,
        avgLatency,
      },
    };
  }
}

export const monitoringManager = new MonitoringManager();
