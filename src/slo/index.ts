import { EventEmitter, generateId, parseDuration } from '../utils';
import logger from '../utils/logger';
import { SLIConfig, SLOConfig, SLIMetric, ErrorBudgetState, BurnRateAlert, TimeSeriesPoint } from '../types';
import metricsService from '../metrics';
import cacheManager from '../data-access';
import { InMemoryCache } from '../data-access';
import ReadWriteRouter, { ReadOptions, WriteOptions, OperationResult } from './read-write-router';

interface SLOEvents {
  'sli.recorded': SLIMetric;
  'error_budget.updated': ErrorBudgetState;
  'burn_rate.alert': BurnRateAlert;
  'slo.created': SLOConfig;
  'sli.created': SLIConfig;
}

export class SLICalculator extends EventEmitter<SLOEvents> {
  private sliConfigs: Map<string, SLIConfig>;
  private sloConfigs: Map<string, SLOConfig>;
  private sliMetrics: Map<string, SLIMetric[]>;
  private errorBudgetStates: Map<string, ErrorBudgetState>;
  private maxMetricsPerSLI: number;

  constructor(maxMetricsPerSLI: number = 10000) {
    super();
    this.sliConfigs = new Map();
    this.sloConfigs = new Map();
    this.sliMetrics = new Map();
    this.errorBudgetStates = new Map();
    this.maxMetricsPerSLI = maxMetricsPerSLI;
  }

  addSLIConfig(config: SLIConfig): boolean {
    if (this.sliConfigs.has(config.sli_id)) {
      logger.warn(`SLI config ${config.sli_id} already exists, updating`);
    }
    this.sliConfigs.set(config.sli_id, config);
    this.sliMetrics.set(config.sli_id, []);
    logger.info(`Added SLI config: ${config.sli_id}`);
    this.emit('sli.created', config);
    return true;
  }

  addSLOConfig(config: SLOConfig): boolean {
    if (this.sloConfigs.has(config.slo_id)) {
      logger.warn(`SLO config ${config.slo_id} already exists, updating`);
    }
    this.sloConfigs.set(config.slo_id, config);
    this.initializeErrorBudget(config);
    logger.info(`Added SLO config: ${config.slo_id}`);
    this.emit('slo.created', config);
    return true;
  }

  getSLIConfig(sliId: string): SLIConfig | undefined {
    return this.sliConfigs.get(sliId);
  }

  getSLOConfig(sloId: string): SLOConfig | undefined {
    return this.sloConfigs.get(sloId);
  }

  getAllSLIConfigs(): SLIConfig[] {
    return Array.from(this.sliConfigs.values());
  }

  getAllSLOConfigs(): SLOConfig[] {
    return Array.from(this.sloConfigs.values());
  }

  deleteSLIConfig(sliId: string): boolean {
    this.sliMetrics.delete(sliId);
    return this.sliConfigs.delete(sliId);
  }

  deleteSLOConfig(sloId: string): boolean {
    this.errorBudgetStates.delete(sloId);
    return this.sloConfigs.delete(sloId);
  }

  recordSLI(
    sliId: string,
    goodEvents: number,
    totalEvents: number,
    dimensions?: Record<string, string>
  ): SLIMetric | null {
    const config = this.sliConfigs.get(sliId);
    if (!config) {
      logger.error(`SLI config ${sliId} not found`);
      return null;
    }

    const value = totalEvents > 0 ? goodEvents / totalEvents : 0;

    const metric: SLIMetric = {
      sli_id: sliId,
      timestamp: new Date().toISOString(),
      value,
      good_events: goodEvents,
      total_events: totalEvents,
      dimensions,
    };

    const metrics = this.sliMetrics.get(sliId) || [];
    metrics.push(metric);
    if (metrics.length > this.maxMetricsPerSLI) {
      metrics.shift();
    }
    this.sliMetrics.set(sliId, metrics);

    this.emit('sli.recorded', metric);

    metricsService.recordMetric(`sli_${sliId}`, value, dimensions);
    metricsService.recordMetric(`sli_${sliId}_good`, goodEvents, dimensions);
    metricsService.recordMetric(`sli_${sliId}_total`, totalEvents, dimensions);

    this.updateSLOsForSLI(sliId);

    return metric;
  }

  recordAvailabilitySLI(sliId: string, isSuccess: boolean, dimensions?: Record<string, string>): SLIMetric | null {
    return this.recordSLI(sliId, isSuccess ? 1 : 0, 1, dimensions);
  }

  recordLatencySLI(sliId: string, latencyMs: number, thresholdMs: number, dimensions?: Record<string, string>): SLIMetric | null {
    const isGood = latencyMs <= thresholdMs;
    return this.recordSLI(sliId, isGood ? 1 : 0, 1, dimensions);
  }

  recordQualitySLI(sliId: string, qualityScore: number, minScore: number, dimensions?: Record<string, string>): SLIMetric | null {
    const isGood = qualityScore >= minScore;
    return this.recordSLI(sliId, isGood ? 1 : 0, 1, dimensions);
  }

  private initializeErrorBudget(slo: SLOConfig): void {
    const windowStart = new Date();
    const windowEnd = new Date(windowStart.getTime() + slo.time_window_days * 24 * 60 * 60 * 1000);

    const state: ErrorBudgetState = {
      slo_id: slo.slo_id,
      total_budget: 1 - slo.target,
      remaining_budget: 1 - slo.target,
      consumed_budget: 0,
      burn_rate: 0,
      window_start: windowStart.toISOString(),
      window_end: windowEnd.toISOString(),
      last_updated: new Date().toISOString(),
    };

    this.errorBudgetStates.set(slo.slo_id, state);
    this.emit('error_budget.updated', state);
  }

  private updateSLOsForSLI(sliId: string): void {
    for (const slo of this.sloConfigs.values()) {
      if (slo.sli_ids.includes(sliId)) {
        this.updateErrorBudget(slo.slo_id);
      }
    }
  }

  updateErrorBudget(sloId: string): ErrorBudgetState | null {
    const slo = this.sloConfigs.get(sloId);
    if (!slo) return null;

    const state = this.errorBudgetStates.get(sloId);
    if (!state) {
      this.initializeErrorBudget(slo);
      return this.errorBudgetStates.get(sloId) || null;
    }

    const totalGood = this.calculateTotalGoodEvents(slo.sli_ids);
    const totalEvents = this.calculateTotalEvents(slo.sli_ids);

    if (totalEvents === 0) return state;

    const currentSLI = totalGood / totalEvents;
    const consumed = Math.max(0, (1 - currentSLI) / (1 - slo.target));
    const remaining = Math.max(0, 1 - consumed);

    const now = Date.now();
    const windowStart = new Date(state.window_start).getTime();
    const windowEnd = new Date(state.window_end).getTime();
    const windowDuration = windowEnd - windowStart;
    const elapsed = now - windowStart;
    const expectedConsumption = elapsed / windowDuration;

    const burn_rate = expectedConsumption > 0 ? consumed / expectedConsumption : 0;

    state.remaining_budget = remaining;
    state.consumed_budget = consumed;
    state.burn_rate = burn_rate;
    state.last_updated = new Date().toISOString();

    this.errorBudgetStates.set(sloId, state);
    this.emit('error_budget.updated', state);

    metricsService.recordMetric(`slo_error_budget_${sloId}`, remaining);
    metricsService.recordMetric(`slo_burn_rate_${sloId}`, burn_rate);

    this.checkBurnRateAlerts(slo, state);

    return state;
  }

  private calculateTotalGoodEvents(sliIds: string[]): number {
    let total = 0;
    for (const sliId of sliIds) {
      const metrics = this.sliMetrics.get(sliId) || [];
      total += metrics.reduce((sum, m) => sum + m.good_events, 0);
    }
    return total;
  }

  private calculateTotalEvents(sliIds: string[]): number {
    let total = 0;
    for (const sliId of sliIds) {
      const metrics = this.sliMetrics.get(sliId) || [];
      total += metrics.reduce((sum, m) => sum + m.total_events, 0);
    }
    return total;
  }

  private checkBurnRateAlerts(slo: SLOConfig, state: ErrorBudgetState): void {
    const thresholds = slo.alerting_thresholds;
    const now = new Date().toISOString();

    if (state.burn_rate >= thresholds.burn_rate_severe) {
      const alert: BurnRateAlert = {
        alert_id: generateId('alert'),
        slo_id: slo.slo_id,
        severity: 'critical',
        burn_rate: state.burn_rate,
        error_budget_remaining: state.remaining_budget,
        timestamp: now,
        message: `SLO ${slo.name} burn rate is critical: ${state.burn_rate.toFixed(2)}x, ${(state.remaining_budget * 100).toFixed(1)}% budget remaining`,
      };
      this.emit('burn_rate.alert', alert);
      logger.error(`Burn rate critical for SLO ${slo.slo_id}: ${state.burn_rate.toFixed(2)}x`);
    } else if (state.burn_rate >= thresholds.burn_rate_warning) {
      const alert: BurnRateAlert = {
        alert_id: generateId('alert'),
        slo_id: slo.slo_id,
        severity: 'warning',
        burn_rate: state.burn_rate,
        error_budget_remaining: state.remaining_budget,
        timestamp: now,
        message: `SLO ${slo.name} burn rate warning: ${state.burn_rate.toFixed(2)}x, ${(state.remaining_budget * 100).toFixed(1)}% budget remaining`,
      };
      this.emit('burn_rate.alert', alert);
      logger.warn(`Burn rate warning for SLO ${slo.slo_id}: ${state.burn_rate.toFixed(2)}x`);
    }

    if (state.remaining_budget <= thresholds.error_budget_remaining) {
      const alert: BurnRateAlert = {
        alert_id: generateId('alert'),
        slo_id: slo.slo_id,
        severity: 'critical',
        burn_rate: state.burn_rate,
        error_budget_remaining: state.remaining_budget,
        timestamp: now,
        message: `SLO ${slo.name} error budget almost exhausted: ${(state.remaining_budget * 100).toFixed(1)}% remaining`,
      };
      this.emit('burn_rate.alert', alert);
      logger.error(`Error budget almost exhausted for SLO ${slo.slo_id}`);
    }
  }

  getErrorBudgetState(sloId: string): ErrorBudgetState | undefined {
    return this.errorBudgetStates.get(sloId);
  }

  getAllErrorBudgetStates(): ErrorBudgetState[] {
    return Array.from(this.errorBudgetStates.values());
  }

  getSLIMetrics(sliId: string, startTime?: number, endTime?: number): SLIMetric[] {
    const metrics = this.sliMetrics.get(sliId) || [];
    if (!startTime && !endTime) return metrics;

    return metrics.filter((m) => {
      const ts = new Date(m.timestamp).getTime();
      if (startTime && ts < startTime) return false;
      if (endTime && ts > endTime) return false;
      return true;
    });
  }

  calculateSLI(sliId: string, startTime: number, endTime: number): number {
    const metrics = this.getSLIMetrics(sliId, startTime, endTime);
    if (metrics.length === 0) return 0;

    const totalGood = metrics.reduce((sum, m) => sum + m.good_events, 0);
    const totalEvents = metrics.reduce((sum, m) => sum + m.total_events, 0);

    return totalEvents > 0 ? totalGood / totalEvents : 0;
  }

  calculateSLO(sloId: string, startTime: number, endTime: number): number {
    const slo = this.sloConfigs.get(sloId);
    if (!slo) return 0;

    const totalGood = this.calculateTotalGoodEvents(slo.sli_ids);
    const totalEvents = this.calculateTotalEvents(slo.sli_ids);

    return totalEvents > 0 ? totalGood / totalEvents : 0;
  }

  predictBurnRate(sloId: string, lookbackMinutes: number = 60): number {
    const state = this.errorBudgetStates.get(sloId);
    if (!state) return 0;

    return state.burn_rate;
  }

  predictTimeToExhaust(sloId: string): number | null {
    const state = this.errorBudgetStates.get(sloId);
    if (!state || state.burn_rate <= 0 || state.remaining_budget <= 0) return null;

    const now = Date.now();
    const windowEnd = new Date(state.window_end).getTime();
    const remainingTime = windowEnd - now;

    if (remainingTime <= 0) return null;

    const timeToExhaust = (state.remaining_budget * remainingTime) / state.burn_rate;
    return Math.max(0, timeToExhaust);
  }

  resetErrorBudget(sloId: string): boolean {
    const slo = this.sloConfigs.get(sloId);
    if (!slo) return false;

    for (const sliId of slo.sli_ids) {
      this.sliMetrics.set(sliId, []);
    }

    this.initializeErrorBudget(slo);
    logger.info(`Reset error budget for SLO ${sloId}`);
    return true;
  }

  clear(): void {
    this.sliConfigs.clear();
    this.sloConfigs.clear();
    this.sliMetrics.clear();
    this.errorBudgetStates.clear();
  }

  getSnapshot(): {
    sliCount: number;
    sloCount: number;
    totalMetrics: number;
  } {
    let totalMetrics = 0;
    for (const metrics of this.sliMetrics.values()) {
      totalMetrics += metrics.length;
    }
    return {
      sliCount: this.sliConfigs.size,
      sloCount: this.sloConfigs.size,
      totalMetrics,
    };
  }
}

export class RoutedSLOManager {
  private primaryCalculator: SLICalculator;
  private replicaCalculators: Map<string, SLICalculator>;
  private router: ReadWriteRouter;
  private sloCache: InMemoryCache<SLOConfig>;
  private sliCache: InMemoryCache<SLIConfig>;
  private errorBudgetCache: InMemoryCache<ErrorBudgetState>;

  constructor() {
    this.primaryCalculator = new SLICalculator();
    this.replicaCalculators = new Map();
    this.router = new ReadWriteRouter();
    this.sloCache = cacheManager.createInMemoryCache<SLOConfig>('slo_configs', {
      default_ttl: 3600000,
      max_size: 1000,
    });
    this.sliCache = cacheManager.createInMemoryCache<SLIConfig>('sli_configs', {
      default_ttl: 3600000,
      max_size: 1000,
    });
    this.errorBudgetCache = cacheManager.createInMemoryCache<ErrorBudgetState>('error_budgets', {
      default_ttl: 30000,
      max_size: 1000,
    });

    this.setupEventListeners();
  }

  private setupEventListeners(): void {
    this.primaryCalculator.on('sli.recorded', (metric) => {
      this.replicateToReplicas('recordSLI', [metric.sli_id, metric.good_events, metric.total_events, metric.dimensions]);
    });

    this.primaryCalculator.on('sli.created', (config) => {
      this.replicateToReplicas('addSLIConfig', [config]);
    });

    this.primaryCalculator.on('slo.created', (config) => {
      this.replicateToReplicas('addSLOConfig', [config]);
    });

    this.primaryCalculator.on('error_budget.updated', (state) => {
      this.errorBudgetCache.set(state.slo_id, state);
    });
  }

  private async replicateToReplicas(operation: string, params: any[]): Promise<void> {
    for (const [replicaId, calculator] of this.replicaCalculators.entries()) {
      try {
        (calculator as any)[operation](...params);
        logger.debug(`Replicated ${operation} to replica ${replicaId}`);
      } catch (error) {
        logger.error(`Failed to replicate ${operation} to replica ${replicaId}:`, error);
      }
    }
  }

  addReplica(id: string, calculator: SLICalculator): void {
    this.replicaCalculators.set(id, calculator);
    logger.info(`Added SLO replica: ${id}`);
  }

  removeReplica(id: string): boolean {
    const removed = this.replicaCalculators.delete(id);
    if (removed) {
      logger.info(`Removed SLO replica: ${id}`);
    }
    return removed;
  }

  getRouter(): ReadWriteRouter {
    return this.router;
  }

  getPrimaryCalculator(): SLICalculator {
    return this.primaryCalculator;
  }

  getReplica(id: string): SLICalculator | undefined {
    return this.replicaCalculators.get(id);
  }

  getAllReplicas(): SLICalculator[] {
    return Array.from(this.replicaCalculators.values());
  }

  async createSLI(config: Omit<SLIConfig, 'sli_id'>): Promise<OperationResult<SLIConfig>> {
    const sliId = generateId('sli');
    const sliConfig: SLIConfig = {
      ...config,
      sli_id: sliId,
    };

    return this.router.routeWrite(
      'createSLI',
      [sliConfig],
      async () => {
        this.primaryCalculator.addSLIConfig(sliConfig);
        await this.sliCache.set(sliId, sliConfig);
        return sliConfig;
      }
    );
  }

  async createSLO(config: Omit<SLOConfig, 'slo_id'>): Promise<OperationResult<SLOConfig>> {
    const sloId = generateId('slo');
    const sloConfig: SLOConfig = {
      ...config,
      slo_id: sloId,
    };

    return this.router.routeWrite(
      'createSLO',
      [sloConfig],
      async () => {
        this.primaryCalculator.addSLOConfig(sloConfig);
        await this.sloCache.set(sloId, sloConfig);
        return sloConfig;
      }
    );
  }

  async getSLO(sloId: string, options?: ReadOptions): Promise<OperationResult<SLOConfig | undefined>> {
    return this.router.routeRead(
      'getSLO',
      [sloId],
      async () => {
        let slo = await this.sloCache.get(sloId);
        if (!slo) {
          slo = this.primaryCalculator.getSLOConfig(sloId);
          if (slo) {
            await this.sloCache.set(sloId, slo);
          }
        }
        return slo;
      },
      undefined,
      options
    );
  }

  async getSLI(sliId: string, options?: ReadOptions): Promise<OperationResult<SLIConfig | undefined>> {
    return this.router.routeRead(
      'getSLI',
      [sliId],
      async () => {
        let sli = await this.sliCache.get(sliId);
        if (!sli) {
          sli = this.primaryCalculator.getSLIConfig(sliId);
          if (sli) {
            await this.sliCache.set(sliId, sli);
          }
        }
        return sli;
      },
      undefined,
      options
    );
  }

  async recordSuccess(sliId: string, dimensions?: Record<string, string>): Promise<OperationResult<SLIMetric | null>> {
    return this.router.routeWrite(
      'recordSuccess',
      [sliId, dimensions],
      async () => {
        return this.primaryCalculator.recordAvailabilitySLI(sliId, true, dimensions);
      }
    );
  }

  async recordFailure(sliId: string, dimensions?: Record<string, string>): Promise<OperationResult<SLIMetric | null>> {
    return this.router.routeWrite(
      'recordFailure',
      [sliId, dimensions],
      async () => {
        return this.primaryCalculator.recordAvailabilitySLI(sliId, false, dimensions);
      }
    );
  }

  async recordSLI(
    sliId: string,
    goodEvents: number,
    totalEvents: number,
    dimensions?: Record<string, string>
  ): Promise<OperationResult<SLIMetric | null>> {
    return this.router.routeWrite(
      'recordSLI',
      [sliId, goodEvents, totalEvents, dimensions],
      async () => {
        return this.primaryCalculator.recordSLI(sliId, goodEvents, totalEvents, dimensions);
      }
    );
  }

  async getErrorBudget(sloId: string, options?: ReadOptions): Promise<OperationResult<ErrorBudgetState | undefined>> {
    return this.router.routeRead(
      'getErrorBudget',
      [sloId],
      async () => {
        let budget = await this.errorBudgetCache.get(sloId);
        if (!budget) {
          budget = this.primaryCalculator.getErrorBudgetState(sloId);
          if (budget) {
            await this.errorBudgetCache.set(sloId, budget);
          }
        }
        return budget;
      },
      undefined,
      options
    );
  }

  async getAllErrorBudgets(options?: ReadOptions): Promise<OperationResult<ErrorBudgetState[]>> {
    return this.router.routeRead(
      'getAllErrorBudgets',
      [],
      async () => {
        return this.primaryCalculator.getAllErrorBudgetStates();
      },
      undefined,
      options
    );
  }

  async getAllSLOs(options?: ReadOptions): Promise<OperationResult<SLOConfig[]>> {
    return this.router.routeRead(
      'getAllSLOs',
      [],
      async () => {
        return this.primaryCalculator.getAllSLOConfigs();
      },
      undefined,
      options
    );
  }

  async getAllSLIs(options?: ReadOptions): Promise<OperationResult<SLIConfig[]>> {
    return this.router.routeRead(
      'getAllSLIs',
      [],
      async () => {
        return this.primaryCalculator.getAllSLIConfigs();
      },
      undefined,
      options
    );
  }

  async calculateSLI(
    sliId: string,
    startTime: number,
    endTime: number,
    options?: ReadOptions
  ): Promise<OperationResult<number>> {
    return this.router.routeRead(
      'calculateSLI',
      [sliId, startTime, endTime],
      async () => {
        return this.primaryCalculator.calculateSLI(sliId, startTime, endTime);
      },
      undefined,
      options
    );
  }

  async calculateSLO(
    sloId: string,
    startTime: number,
    endTime: number,
    options?: ReadOptions
  ): Promise<OperationResult<number>> {
    return this.router.routeRead(
      'calculateSLO',
      [sloId, startTime, endTime],
      async () => {
        return this.primaryCalculator.calculateSLO(sloId, startTime, endTime);
      },
      undefined,
      options
    );
  }

  async predictTimeToExhaust(sloId: string, options?: ReadOptions): Promise<OperationResult<number | null>> {
    return this.router.routeRead(
      'predictTimeToExhaust',
      [sloId],
      async () => {
        return this.primaryCalculator.predictTimeToExhaust(sloId);
      },
      undefined,
      options
    );
  }

  async resetErrorBudget(sloId: string): Promise<OperationResult<boolean>> {
    return this.router.routeWrite(
      'resetErrorBudget',
      [sloId],
      async () => {
        const result = this.primaryCalculator.resetErrorBudget(sloId);
        if (result) {
          await this.errorBudgetCache.delete(sloId);
        }
        return result;
      }
    );
  }

  getRoutingStats() {
    return this.router.getStats();
  }

  clear(): void {
    this.primaryCalculator.clear();
    for (const replica of this.replicaCalculators.values()) {
      replica.clear();
    }
    this.replicaCalculators.clear();
    this.router.clear();
  }
}

export class SLOManager {
  private sliCalculator: SLICalculator;
  private sloCache: InMemoryCache<SLOConfig>;
  private sliCache: InMemoryCache<SLIConfig>;

  constructor() {
    this.sliCalculator = new SLICalculator();
    this.sloCache = cacheManager.createInMemoryCache<SLOConfig>('slo_configs', {
      default_ttl: 3600000,
      max_size: 1000,
    });
    this.sliCache = cacheManager.createInMemoryCache<SLIConfig>('sli_configs', {
      default_ttl: 3600000,
      max_size: 1000,
    });
  }

  getCalculator(): SLICalculator {
    return this.sliCalculator;
  }

  async createSLI(config: Omit<SLIConfig, 'sli_id'>): Promise<SLIConfig> {
    const sliId = generateId('sli');
    const sliConfig: SLIConfig = {
      ...config,
      sli_id: sliId,
    };
    this.sliCalculator.addSLIConfig(sliConfig);
    await this.sliCache.set(sliId, sliConfig);
    return sliConfig;
  }

  async createSLO(config: Omit<SLOConfig, 'slo_id'>): Promise<SLOConfig> {
    const sloId = generateId('slo');
    const sloConfig: SLOConfig = {
      ...config,
      slo_id: sloId,
    };
    this.sliCalculator.addSLOConfig(sloConfig);
    await this.sloCache.set(sloId, sloConfig);
    return sloConfig;
  }

  async getSLO(sloId: string): Promise<SLOConfig | undefined> {
    let slo = await this.sloCache.get(sloId);
    if (!slo) {
      slo = this.sliCalculator.getSLOConfig(sloId);
      if (slo) {
        await this.sloCache.set(sloId, slo);
      }
    }
    return slo;
  }

  async getSLI(sliId: string): Promise<SLIConfig | undefined> {
    let sli = await this.sliCache.get(sliId);
    if (!sli) {
      sli = this.sliCalculator.getSLIConfig(sliId);
      if (sli) {
        await this.sliCache.set(sliId, sli);
      }
    }
    return sli;
  }

  recordSuccess(sliId: string, dimensions?: Record<string, string>): void {
    this.sliCalculator.recordAvailabilitySLI(sliId, true, dimensions);
  }

  recordFailure(sliId: string, dimensions?: Record<string, string>): void {
    this.sliCalculator.recordAvailabilitySLI(sliId, false, dimensions);
  }

  async getErrorBudget(sloId: string): Promise<ErrorBudgetState | undefined> {
    return this.sliCalculator.getErrorBudgetState(sloId);
  }

  async getAllErrorBudgets(): Promise<ErrorBudgetState[]> {
    return this.sliCalculator.getAllErrorBudgetStates();
  }
}

const sloManager = new SLOManager();
const routedSLOManager = new RoutedSLOManager();

export default sloManager;
export { routedSLOManager };
