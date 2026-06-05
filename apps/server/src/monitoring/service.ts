import { v4 as uuidv4 } from 'uuid';
import type { FastifyRequest, FastifyReply } from 'fastify';
import { prisma } from '../config/database';
import { logger } from '../config/logger';
import { env } from '../config/env';
import type {
  Alert,
  AlertCreateRequest,
  AlertListRequest,
  DriftDetectionConfig,
  DriftDetectionResult,
  InferenceLatencyMetrics,
  ErrorRateMetrics,
  ThroughputMetrics,
  FeatureDistributionMetrics,
  MonitoringDashboardData,
  PaginatedResponse,
  DistributionStats,
} from '@mlops/shared';

export class MonitoringService {
  private driftDetectionTimers: Map<string, NodeJS.Timeout> = new Map();
  private alertEvaluationTimers: Map<string, NodeJS.Timeout> = new Map();
  private recentLatencies: Map<string, number[]> = new Map();
  private recentErrors: Map<string, { count: number; total: number }[]> = new Map();

  constructor() {
    this.startScheduledJobs().catch((err) => {
      logger.error({ error: err }, 'Failed to start scheduled monitoring jobs');
    });
  }

  private async startScheduledJobs(): Promise<void> {
    const configs = await prisma.driftDetectionConfig.findMany({
      where: { enabled: true },
    });

    for (const config of configs) {
      this.scheduleDriftDetection(config);
    }

    const alerts = await prisma.alert.findMany({
      where: { status: 'active' },
    });

    for (const alert of alerts) {
      this.scheduleAlertEvaluation(alert);
    }
  }

  async createAlert(request: AlertCreateRequest): Promise<Alert> {
    const alert = await prisma.alert.create({
      data: {
        id: uuidv4(),
        name: request.name,
        description: request.description,
        type: request.type,
        severity: request.severity,
        modelId: request.modelId,
        version: request.version,
        featureSetId: request.featureSetId,
        featureName: request.featureName,
        threshold: request.threshold,
        condition: request.condition,
        notificationChannels: request.notificationChannels,
      },
    });

    this.scheduleAlertEvaluation(alert);
    logger.info({ alertId: alert.id, name: alert.name, type: alert.type }, 'Alert created');

    return this.transformAlert(alert);
  }

  async getAlert(id: string): Promise<Alert | null> {
    const alert = await prisma.alert.findUnique({ where: { id } });
    if (!alert) return null;
    return this.transformAlert(alert);
  }

  async listAlerts(request: AlertListRequest): Promise<PaginatedResponse<Alert>> {
    const page = request.page || 1;
    const pageSize = request.pageSize || 20;

    const where: Record<string, unknown> = {};
    if (request.name) where.name = { contains: request.name };
    if (request.type) where.type = request.type;
    if (request.severity) where.severity = request.severity;
    if (request.status) where.status = request.status;
    if (request.modelId) where.modelId = request.modelId;
    if (request.featureSetId) where.featureSetId = request.featureSetId;

    const [total, alerts] = await Promise.all([
      prisma.alert.count({ where }),
      prisma.alert.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
      }),
    ]);

    return {
      data: alerts.map((a) => this.transformAlert(a)),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async updateAlertStatus(
    id: string,
    status: Alert['status'],
    userId?: string,
    reason?: string
  ): Promise<Alert | null> {
    const updateData: Record<string, unknown> = {
      status,
      updatedAt: new Date(),
    };

    if (status === 'acknowledged') {
      updateData.acknowledgedAt = new Date();
      updateData.acknowledgedBy = userId;
    } else if (status === 'resolved') {
      updateData.resolvedAt = new Date();
      updateData.resolvedBy = userId;
      updateData.resolvedReason = reason;
      updateData.lastResolvedAt = new Date();
    }

    const alert = await prisma.alert.update({
      where: { id },
      data: updateData,
    });

    if (status === 'resolved') {
      const timer = this.alertEvaluationTimers.get(id);
      if (timer) {
        clearInterval(timer);
        this.alertEvaluationTimers.delete(id);
      }
    }

    logger.info({ alertId: id, status }, 'Alert status updated');
    return this.transformAlert(alert);
  }

  async createDriftDetectionConfig(
    config: Omit<DriftDetectionConfig, 'id' | 'createdAt' | 'updatedAt'>
  ): Promise<DriftDetectionConfig> {
    const result = await prisma.driftDetectionConfig.create({
      data: {
        id: uuidv4(),
        ...config,
      },
    });

    if (config.enabled) {
      this.scheduleDriftDetection(result);
    }

    logger.info({ configId: result.id, modelId: config.modelId }, 'Drift detection config created');
    return this.transformDriftConfig(result);
  }

  async runDriftDetection(configId: string): Promise<DriftDetectionResult[]> {
    const config = await prisma.driftDetectionConfig.findUnique({
      where: { id: configId },
    });

    if (!config) {
      throw new Error(`Drift config not found: ${configId}`);
    }

    const windowEnd = Date.now();
    const windowStart = windowEnd - config.windowSizeMinutes * 60 * 1000;
    const baselineWindowEnd = windowStart;
    const baselineWindowStart = baselineWindowEnd - config.baselineWindowSizeMinutes * 60 * 1000;

    const currentInferences = await prisma.inferenceMetrics.findMany({
      where: {
        modelId: config.modelId,
        version: config.version || undefined,
        timestamp: {
          gte: new Date(windowStart),
          lte: new Date(windowEnd),
        },
      },
      take: config.sampleSize,
    });

    const baselineInferences = await prisma.inferenceMetrics.findMany({
      where: {
        modelId: config.modelId,
        version: config.version || undefined,
        timestamp: {
          gte: new Date(baselineWindowStart),
          lte: new Date(baselineWindowEnd),
        },
      },
      take: config.sampleSize,
    });

    const results: DriftDetectionResult[] = [];
    const featureNames = config.featureNames.length > 0 ? config.featureNames : ['prediction'];

    for (const featureName of featureNames) {
      const currentValues = currentInferences
        .map((inf) => {
          const features = inf.outputFeatures as Record<string, unknown>;
          return features?.[featureName] as number;
        })
        .filter((v) => typeof v === 'number');

      const baselineValues = baselineInferences
        .map((inf) => {
          const features = inf.outputFeatures as Record<string, unknown>;
          return features?.[featureName] as number;
        })
        .filter((v) => typeof v === 'number');

      if (currentValues.length < 30 || baselineValues.length < 30) {
        continue;
      }

      const result = this.performStatisticalTest(
        baselineValues,
        currentValues,
        config.statisticalTest as any
      );

      const isDrift = result.pValue < config.thresholdPValue;

      const driftResult = await prisma.driftDetectionResult.create({
        data: {
          id: uuidv4(),
          configId: config.id,
          modelId: config.modelId,
          version: config.version,
          driftType: config.driftType,
          featureName,
          statisticalTest: config.statisticalTest,
          statistic: result.statistic,
          pValue: result.pValue,
          thresholdPValue: config.thresholdPValue,
          isDriftDetected: isDrift,
          effectSize: result.effectSize,
          baselineStats: this.calculateDistributionStats(baselineValues),
          currentStats: this.calculateDistributionStats(currentValues),
          windowStart: new Date(windowStart),
          windowEnd: new Date(windowEnd),
        },
      });

      results.push(this.transformDriftResult(driftResult));

      if (isDrift && config.alertOnDetection) {
        this.triggerDriftAlert(config, driftResult).catch(() => {});
      }
    }

    logger.info(
      { configId, modelId: config.modelId, results: results.length, driftCount: results.filter((r) => r.isDriftDetected).length },
      'Drift detection completed'
    );

    return results;
  }

  private performStatisticalTest(
    baseline: number[],
    current: number[],
    test: 'ks' | 'chi_square' | 't_test' | 'mann_whitney' | 'adversarial'
  ): { statistic: number; pValue: number; effectSize: number } {
    const baselineStats = this.calculateDistributionStats(baseline);
    const currentStats = this.calculateDistributionStats(current);

    const effectSize = Math.abs(currentStats.mean - baselineStats.mean) / baselineStats.std;

    const pooledStd = Math.sqrt(
      ((baseline.length - 1) * baselineStats.std ** 2 + (current.length - 1) * currentStats.statistics) /
        (baseline.length + current.length - 2)
    );
    const tStat =
      (currentStats.mean - baselineStats.mean) / (pooledStd * Math.sqrt(1 / baseline.length + 1 / current.length));

    const df = baseline.length + current.length - 2;
    const pValue = this.tTestPValue(Math.abs(tStat), df);

    let statistic = tStat;
    if (test === 'ks') {
      statistic = this.kolmogorovSmirnov(baseline, current);
    }

    return {
      statistic,
      pValue,
      effectSize,
    };
  }

  private kolmogorovSmirnov(sample1: number[], sample2: number[]): number {
    const sorted1 = [...sample1].sort((a, b) => a - b);
    const sorted2 = [...sample2].sort((a, b) => a - b);

    let maxDiff = 0;
    const allValues = [...new Set([...sorted1, ...sorted2])].sort((a, b) => a - b);

    for (const v of allValues) {
      const cdf1 = sorted1.filter((x) => x <= v).length / sorted1.length;
      const cdf2 = sorted2.filter((x) => x <= v).length / sorted2.length;
      maxDiff = Math.max(maxDiff, Math.abs(cdf1 - cdf2));
    }

    return maxDiff;
  }

  private tTestPValue(t: number, df: number): number {
    const x = df / (df + t * t);
    const a = df / 2;
    const b = 0.5;

    const incompleteBeta = this.incompleteBeta(x, a, b);
    const p = 1 - incompleteBeta;

    return Math.max(0.001, Math.min(1, p * 2));
  }

  private incompleteBeta(x: number, a: number, b: number): number {
    const bt =
      x === 0 || x === 1
        ? 0
        : Math.exp(this.gammaLn(a + b) - this.gammaLn(a) - this.gammaLn(b) + a * Math.log(x) + b * Math.log(1 - x));

    if (x < (a + 1) / (a + b + 2)) {
      return (bt * this.betaCF(x, a, b)) / a;
    } else {
      return 1 - (bt * this.betaCF(1 - x, b, a)) / b;
    }
  }

  private gammaLn(x: number): number {
    const cof = [
      76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2,
      -0.5395239384953e-5,
    ];
    let y = x;
    let tmp = x + 5.5;
    tmp -= (x + 0.5) * Math.log(tmp);
    let ser = 1.000000000190015;
    for (let j = 0; j < 6; j++) {
      ser += cof[j]! / ++y;
    }
    return -tmp + Math.log((2.5066282746310005 * ser) / x);
  }

  private betaCF(x: number, a: number, b: number): number {
    const MAXIT = 100;
    const EPS = 3e-7;
    let m = 1;
    let qab = a + b;
    let qap = a + 1;
    let qam = a - 1;
    let c = 1;
    let d = 1 - (qab * x) / qap;
    if (Math.abs(d) < 1e-30) d = 1e-30;
    d = 1 / d;
    let h = d;

    for (let i = 1; i <= MAXIT; i++) {
      let m2 = 2 * m;
      let aa = (m * (b - m) * x) / ((qam + m2) * (a + m2));
      d = 1 + aa * d;
      if (Math.abs(d) < 1e-30) d = 1e-30;
      c = 1 + aa / c;
      if (Math.abs(c) < 1e-30) c = 1e-30;
      d = 1 / d;
      h *= d * c;
      aa = (-(a + m) * (qab + m) * x) / ((a + m2) * (qap + m2));
      d = 1 + aa * d;
      if (Math.abs(d) < 1e-30) d = 1e-30;
      c = 1 + aa / c;
      if (Math.abs(c) < 1e-30) c = 1e-30;
      d = 1 / d;
      const del = d * c;
      h *= del;
      if (Math.abs(del - 1) < EPS) break;
      m++;
    }
    return h;
  }

  private calculateDistributionStats(values: number[]): DistributionStats {
    const sorted = [...values].sort((a, b) => a - b);
    const n = values.length;
    const mean = values.reduce((a, b) => a + b, 0) / n;
    const variance = values.reduce((acc, v) => acc + Math.pow(v - mean, 2), 0) / (n - 1);
    const std = Math.sqrt(variance);

    const percentile = (p: number) => {
      const idx = Math.ceil((p / 100) * n) - 1;
      return sorted[Math.max(0, Math.min(idx, n - 1))] || 0;
    };

    const numBins = 20;
    const min = sorted[0]!;
    const max = sorted[n - 1]!;
    const binWidth = (max - min) / numBins;
    const bins = Array.from({ length: numBins + 1 }, (_, i) => min + i * binWidth);
    const counts = new Array(numBins).fill(0);

    for (const v of values) {
      const binIdx = Math.min(Math.floor((v - min) / binWidth), numBins - 1);
      if (binIdx >= 0) counts[binIdx]++;
    }

    return {
      mean,
      std,
      min,
      max,
      median: percentile(50),
      p25: percentile(25),
      p75: percentile(75),
      p95: percentile(95),
      p99: percentile(99),
      sampleCount: n,
      histogram: { bins, counts },
    };
  }

  private async triggerDriftAlert(
    config: DriftDetectionConfig,
    result: any
  ): Promise<void> {
    const existingAlert = await prisma.alert.findFirst({
      where: {
        modelId: config.modelId,
        type: 'model_drift',
        featureName: result.featureName,
        status: 'active',
      },
    });

    if (existingAlert) {
      await prisma.alertEvent.create({
        data: {
          id: uuidv4(),
          alertId: existingAlert.id,
          severity: config.alertSeverity,
          message: `Drift detected for feature ${result.featureName}: p-value = ${result.pValue.toFixed(6)}`,
          metricValue: result.pValue,
          threshold: config.thresholdPValue,
          context: {
            driftType: config.driftType,
            statisticalTest: config.statisticalTest,
            effectSize: result.effectSize,
          },
        },
      });

      await prisma.alert.update({
        where: { id: existingAlert.id },
        data: {
          lastTriggeredAt: new Date(),
          triggerCount: { increment: 1 },
        },
      });
    }
  }

  private scheduleDriftDetection(config: any): void {
    if (this.driftDetectionTimers.has(config.id)) {
      clearInterval(this.driftDetectionTimers.get(config.id)!);
    }

    const timer = setInterval(
      () => {
        this.runDriftDetection(config.id).catch((err) => {
          logger.error({ error: err, configId: config.id }, 'Drift detection failed');
        });
      },
      env.DRIFT_DETECTION_INTERVAL_MINUTES * 60 * 1000
    );

    this.driftDetectionTimers.set(config.id, timer);
  }

  private scheduleAlertEvaluation(alert: any): void {
    if (this.alertEvaluationTimers.has(alert.id)) {
      clearInterval(this.alertEvaluationTimers.get(alert.id)!);
    }

    const timer = setInterval(
      () => {
        this.evaluateAlert(alert.id).catch((err) => {
          logger.error({ error: err, alertId: alert.id }, 'Alert evaluation failed');
        });
      },
      60 * 1000
    );

    this.alertEvaluationTimers.set(alert.id, timer);
  }

  private async evaluateAlert(alertId: string): Promise<void> {
    const alert = await prisma.alert.findUnique({ where: { id: alertId } });
    if (!alert || alert.status !== 'active') return;

    const threshold = alert.threshold as any;
    const now = Date.now();
    const windowStart = now - threshold.durationMinutes * 60 * 1000;

    let metricValue = 0;

    switch (alert.type) {
      case 'inference_latency': {
        const metrics = await prisma.inferenceMetrics.findMany({
          where: {
            modelId: alert.modelId!,
            timestamp: { gte: new Date(windowStart) },
          },
          select: { latencyMs: true },
        });

        const latencies = metrics.map((m) => m.latencyMs).sort((a, b) => a - b);
        const p = threshold.percentile || 99;
        const idx = Math.ceil((p / 100) * latencies.length) - 1;
        metricValue = latencies[idx] || 0;
        break;
      }
      case 'error_rate': {
        const [total, errors] = await Promise.all([
          prisma.inferenceMetrics.count({
            where: {
              modelId: alert.modelId!,
              timestamp: { gte: new Date(windowStart) },
            },
          }),
          prisma.inferenceMetrics.count({
            where: {
              modelId: alert.modelId!,
              timestamp: { gte: new Date(windowStart) },
              success: false,
            },
          }),
        ]);
        metricValue = total > 0 ? errors / total : 0;
        break;
      }
      case 'throughput': {
        const count = await prisma.inferenceMetrics.count({
          where: {
            modelId: alert.modelId!,
            timestamp: { gte: new Date(windowStart) },
          },
        });
        metricValue = count / (threshold.durationMinutes * 60);
        break;
      }
    }

    const shouldTrigger = this.evaluateThreshold(metricValue, threshold);
    if (shouldTrigger) {
      await prisma.alertEvent.create({
        data: {
          id: uuidv4(),
          alertId: alert.id,
          severity: alert.severity,
          message: `${alert.name} threshold exceeded: ${metricValue.toFixed(2)} ${threshold.operator} ${threshold.value}`,
          metricValue,
          threshold: threshold.value,
        },
      });

      await prisma.alert.update({
        where: { id: alert.id },
        data: {
          lastTriggeredAt: new Date(),
          triggerCount: { increment: 1 },
        },
      });

      logger.warn(
        { alertId, metricValue, threshold: threshold.value },
        'Alert triggered'
      );
    }
  }

  private evaluateThreshold(value: number, threshold: any): boolean {
    switch (threshold.operator) {
      case 'gt':
        return value > threshold.value;
      case 'gte':
        return value >= threshold.value;
      case 'lt':
        return value < threshold.value;
      case 'lte':
        return value <= threshold.value;
      case 'eq':
        return value === threshold.value;
      case 'ne':
        return value !== threshold.value;
      default:
        return false;
    }
  }

  async getLatencyMetrics(
    modelId: string,
    startTime: number,
    endTime: number,
    aggregationSeconds = 60
  ): Promise<InferenceLatencyMetrics[]> {
    const metrics = await prisma.inferenceMetrics.findMany({
      where: {
        modelId,
        timestamp: {
          gte: new Date(startTime),
          lte: new Date(endTime),
        },
      },
      orderBy: { timestamp: 'asc' },
    });

    const buckets = new Map<string, number[]>();

    for (const m of metrics) {
      const bucketTime =
        Math.floor(m.timestamp.getTime() / (aggregationSeconds * 1000)) *
        aggregationSeconds *
        1000;
      if (!buckets.has(String(bucketTime))) {
        buckets.set(String(bucketTime), []);
      }
      buckets.get(String(bucketTime))!.push(m.latencyMs);
    }

    const results: InferenceLatencyMetrics[] = [];
    for (const [timestampStr, latencies] of buckets.entries()) {
      const sorted = [...latencies].sort((a, b) => a - b);
      const n = sorted.length;
      const percentile = (p: number) => sorted[Math.ceil((p / 100) * n) - 1] || 0;

      results.push({
        modelId,
        version: metrics[0]?.version || '',
        timestamp: parseInt(timestampStr),
        windowSeconds: aggregationSeconds,
        count: n,
        avg: latencies.reduce((a, b) => a + b, 0) / n,
        min: sorted[0] || 0,
        max: sorted[n - 1] || 0,
        p50: percentile(50),
        p75: percentile(75),
        p90: percentile(90),
        p95: percentile(95),
        p99: percentile(99),
        p999: percentile(99.9),
      });
    }

    return results.sort((a, b) => a.timestamp - b.timestamp);
  }

  async getDashboardData(
    startTime: number,
    endTime: number,
    modelId?: string
  ): Promise<MonitoringDashboardData> {
    const where: Record<string, unknown> = {};
    if (modelId) where.modelId = modelId;

    const [activeAlerts, recentDetections, allDetections] = await Promise.all([
      prisma.alert.findMany({
        where: { status: 'active' },
        take: 10,
        orderBy: { severity: 'desc' },
      }),
      prisma.driftDetectionResult.findMany({
        where: { ...where, isDriftDetected: true },
        take: 10,
        orderBy: { timestamp: 'desc' },
      }),
      prisma.driftDetectionResult.findMany({
        where,
        take: 100,
        orderBy: { timestamp: 'desc' },
      }),
    ]);

    return {
      latencyMetrics: await this.getLatencyMetrics(modelId || 'all', startTime, endTime),
      errorRateMetrics: [],
      throughputMetrics: [],
      activeAlerts: activeAlerts.map((a) => this.transformAlert(a)),
      recentDriftDetections: recentDetections.map((d) => this.transformDriftResult(d)),
      driftDetections: allDetections.map((d) => this.transformDriftResult(d)),
    };
  }

  private transformAlert(prismaAlert: any): Alert {
    return {
      id: prismaAlert.id,
      name: prismaAlert.name,
      description: prismaAlert.description ?? undefined,
      type: prismaAlert.type as Alert['type'],
      severity: prismaAlert.severity as Alert['severity'],
      status: prismaAlert.status as Alert['status'],
      modelId: prismaAlert.modelId ?? undefined,
      version: prismaAlert.version ?? undefined,
      featureSetId: prismaAlert.featureSetId ?? undefined,
      featureName: prismaAlert.featureName ?? undefined,
      threshold: prismaAlert.threshold as Alert['threshold'],
      condition: prismaAlert.condition as Alert['condition'],
      notificationChannels: prismaAlert.notificationChannels as Alert['notificationChannels'],
      lastTriggeredAt: prismaAlert.lastTriggeredAt?.getTime(),
      lastResolvedAt: prismaAlert.lastResolvedAt?.getTime(),
      triggerCount: prismaAlert.triggerCount,
      acknowledgedBy: prismaAlert.acknowledgedBy ?? undefined,
      acknowledgedAt: prismaAlert.acknowledgedAt?.getTime(),
      resolvedBy: prismaAlert.resolvedBy ?? undefined,
      resolvedReason: prismaAlert.resolvedReason ?? undefined,
      createdAt: prismaAlert.createdAt.getTime(),
      updatedAt: prismaAlert.updatedAt.getTime(),
    };
  }

  private transformDriftConfig(prismaConfig: any): DriftDetectionConfig {
    return {
      id: prismaConfig.id,
      modelId: prismaConfig.modelId,
      version: prismaConfig.version ?? undefined,
      name: prismaConfig.name,
      driftType: prismaConfig.driftType as DriftDetectionConfig['driftType'],
      statisticalTest: prismaConfig.statisticalTest as DriftDetectionConfig['statisticalTest'],
      featureNames: prismaConfig.featureNames,
      thresholdPValue: prismaConfig.thresholdPValue,
      windowSizeMinutes: prismaConfig.windowSizeMinutes,
      baselineWindowSizeMinutes: prismaConfig.baselineWindowSizeMinutes,
      sampleSize: prismaConfig.sampleSize,
      alertOnDetection: prismaConfig.alertOnDetection,
      alertSeverity: prismaConfig.alertSeverity as any,
      enabled: prismaConfig.enabled,
      createdAt: prismaConfig.createdAt.getTime(),
      updatedAt: prismaConfig.updatedAt.getTime(),
    };
  }

  private transformDriftResult(prismaResult: any): DriftDetectionResult {
    return {
      id: prismaResult.id,
      configId: prismaResult.configId,
      modelId: prismaResult.modelId,
      version: prismaResult.version ?? undefined,
      driftType: prismaResult.driftType as DriftDetectionResult['driftType'],
      featureName: prismaResult.featureName ?? undefined,
      statisticalTest: prismaResult.statisticalTest as DriftDetectionResult['statisticalTest'],
      statistic: prismaResult.statistic,
      pValue: prismaResult.pValue,
      thresholdPValue: prismaResult.thresholdPValue,
      isDriftDetected: prismaResult.isDriftDetected,
      effectSize: prismaResult.effectSize,
      baselineStats: prismaResult.baselineStats as DistributionStats,
      currentStats: prismaResult.currentStats as DistributionStats,
      timestamp: prismaResult.timestamp.getTime(),
      windowStart: prismaResult.windowStart.getTime(),
      windowEnd: prismaResult.windowEnd.getTime(),
      alertId: prismaResult.alertId ?? undefined,
    };
  }
}

export const monitoringService = new MonitoringService();

export async function registerMonitoringRoutes(fastify: any): Promise<void> {
  const service = monitoringService;

  fastify.post('/api/v1/alerts', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await service.createAlert(request.body as AlertCreateRequest);
    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/alerts/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.getAlert(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Alert not found' });
    return result;
  });

  fastify.get('/api/v1/alerts', async (request: FastifyRequest) => {
    return service.listAlerts(request.query as AlertListRequest);
  });

  fastify.patch('/api/v1/alerts/:id/status', async (
    request: FastifyRequest<{
      Params: { id: string };
      Body: { status: Alert['status']; userId?: string; reason?: string };
    }>,
    reply: FastifyReply
  ) => {
    const result = await service.updateAlertStatus(
      request.params.id,
      request.body.status,
      request.body.userId,
      request.body.reason
    );
    if (!result) return reply.status(404).send({ error: 'Alert not found' });
    return result;
  });

  fastify.post('/api/v1/drift-configs', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await service.createDriftDetectionConfig(request.body as any);
    return reply.status(201).send(result);
  });

  fastify.post('/api/v1/drift-configs/:id/run', async (request: FastifyRequest<{ Params: { id: string } }>) => {
    return service.runDriftDetection(request.params.id);
  });

  fastify.get('/api/v1/metrics/latency', async (
    request: FastifyRequest<{
      Querystring: { modelId: string; startTime: string; endTime: string; aggregationSeconds?: string };
    }>
  ) => {
    return service.getLatencyMetrics(
      request.query.modelId,
      parseInt(request.query.startTime),
      parseInt(request.query.endTime),
      request.query.aggregationSeconds ? parseInt(request.query.aggregationSeconds) : 60
    );
  });

  fastify.get('/api/v1/monitoring/dashboard', async (
    request: FastifyRequest<{
      Querystring: { startTime: string; endTime: string; modelId?: string };
    }>
  ) => {
    return service.getDashboardData(
      parseInt(request.query.startTime),
      parseInt(request.query.endTime),
      request.query.modelId
    );
  });
}
