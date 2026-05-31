import { EventEmitter } from 'events';
import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { generateId, currentTimestamp, sleep } from '../../utils/helpers';

export type FaultType = 'latency' | 'error' | 'crash' | 'network_partition' | 'disk_full' | 'memory_leak';

export interface FaultScope {
  services?: string[];
  endpoints?: string[];
  hosts?: string[];
  namespaces?: string[];
  headers?: Record<string, string>;
  sampleRate: number;
}

export interface AutoRollbackConfig {
  enabled: boolean;
  failureThreshold: number;
  errorRateThreshold: number;
  latencyThreshold: number;
  checkInterval: number;
  maxDuration: number;
}

export interface FaultScenario {
  id: string;
  name: string;
  description: string;
  faultType: FaultType;
  parameters: Record<string, any>;
  scope: FaultScope;
  duration: number;
  autoRollback: AutoRollbackConfig;
  status: 'draft' | 'active' | 'running' | 'completed' | 'rolled_back' | 'failed';
  createdAt: string;
  startedAt?: string;
  endedAt?: string;
  createdBy: string;
}

export interface InjectionResult {
  injectionId: string;
  scenarioId: string;
  status: 'injected' | 'failed';
  target: string;
  timestamp: string;
  error?: string;
}

export interface RollbackRecord {
  id: string;
  scenarioId: string;
  reason: string;
  timestamp: string;
  success: boolean;
  details: Record<string, any>;
}

export interface FaultMetrics {
  totalInjections: number;
  activeInjections: number;
  successfulRollbacks: number;
  failedRollbacks: number;
  avgFaultDuration: number;
}

export class FaultInjectionOrchestrator extends EventEmitter {
  private scenarios: Map<string, FaultScenario> = new Map();
  private activeInjections: Map<string, { scenario: FaultScenario; timeout: NodeJS.Timeout; checkInterval?: NodeJS.Timeout }> = new Map();
  private rollbackHistory: RollbackRecord[] = [];
  private metrics: FaultMetrics = {
    totalInjections: 0,
    activeInjections: 0,
    successfulRollbacks: 0,
    failedRollbacks: 0,
    avgFaultDuration: 0,
  };

  constructor() {
    super();
    logger.info('FaultInjectionOrchestrator initialized');
  }

  createScenario(
    scenario: Omit<FaultScenario, 'id' | 'status' | 'createdAt'>,
  ): FaultScenario {
    const id = generateId('scenario_');
    const newScenario: FaultScenario = {
      ...scenario,
      id,
      status: 'draft',
      createdAt: currentTimestamp(),
    };
    this.scenarios.set(id, newScenario);
    logger.info('Fault scenario created', { id, name: scenario.name });
    eventBus.emit('fault.scenario.created', newScenario);
    return newScenario;
  }

  updateScenario(id: string, updates: Partial<FaultScenario>): FaultScenario | undefined {
    const scenario = this.scenarios.get(id);
    if (!scenario) return undefined;
    if (scenario.status === 'running') {
      throw new Error('Cannot update running scenario');
    }
    const updated = { ...scenario, ...updates };
    this.scenarios.set(id, updated);
    logger.info('Fault scenario updated', { id });
    eventBus.emit('fault.scenario.updated', updated);
    return updated;
  }

  deleteScenario(id: string): boolean {
    const scenario = this.scenarios.get(id);
    if (!scenario) return false;
    if (scenario.status === 'running') {
      throw new Error('Cannot delete running scenario');
    }
    const deleted = this.scenarios.delete(id);
    if (deleted) {
      logger.info('Fault scenario deleted', { id });
      eventBus.emit('fault.scenario.deleted', { id });
    }
    return deleted;
  }

  async startInjection(scenarioId: string): Promise<InjectionResult> {
    const scenario = this.scenarios.get(scenarioId);
    if (!scenario) {
      return {
        injectionId: generateId('inj_'),
        scenarioId,
        status: 'failed',
        target: 'unknown',
        timestamp: currentTimestamp(),
        error: 'Scenario not found',
      };
    }

    if (this.activeInjections.has(scenarioId)) {
      return {
        injectionId: generateId('inj_'),
        scenarioId,
        status: 'failed',
        target: 'unknown',
        timestamp: currentTimestamp(),
        error: 'Injection already active',
      };
    }

    try {
      scenario.status = 'running';
      scenario.startedAt = currentTimestamp();
      this.scenarios.set(scenarioId, scenario);

      await this.injectFault(scenario);

      const timeout = setTimeout(() => {
        this.stopInjection(scenarioId, 'duration_expired');
      }, scenario.duration);

      let checkInterval: NodeJS.Timeout | undefined;
      if (scenario.autoRollback.enabled) {
        checkInterval = setInterval(() => {
          this.checkRollbackCondition(scenario);
        }, scenario.autoRollback.checkInterval);
      }

      this.activeInjections.set(scenarioId, { scenario, timeout, checkInterval });

      this.metrics.totalInjections++;
      this.metrics.activeInjections++;

      logger.info('Fault injection started', { scenarioId, name: scenario.name });
      eventBus.emit('fault.injection.started', { scenario });

      return {
        injectionId: generateId('inj_'),
        scenarioId,
        status: 'injected',
        target: scenario.scope.services?.[0] || 'global',
        timestamp: currentTimestamp(),
      };
    } catch (error: any) {
      scenario.status = 'failed';
      this.scenarios.set(scenarioId, scenario);
      logger.error('Fault injection failed', { scenarioId, error: error.message });
      return {
        injectionId: generateId('inj_'),
        scenarioId,
        status: 'failed',
        target: scenario.scope.services?.[0] || 'global',
        timestamp: currentTimestamp(),
        error: error.message,
      };
    }
  }

  private async injectFault(scenario: FaultScenario): Promise<void> {
    logger.debug('Injecting fault', { type: scenario.faultType, scenarioId: scenario.id });

    switch (scenario.faultType) {
      case 'latency':
        this.injectLatency(scenario);
        break;
      case 'error':
        this.injectError(scenario);
        break;
      case 'crash':
        this.injectCrash(scenario);
        break;
      case 'network_partition':
        this.injectNetworkPartition(scenario);
        break;
      case 'disk_full':
        this.injectDiskFull(scenario);
        break;
      case 'memory_leak':
        this.injectMemoryLeak(scenario);
        break;
      default:
        throw new Error(`Unknown fault type: ${scenario.faultType}`);
    }

    eventBus.emit('fault.injected', { scenario });
  }

  private injectLatency(scenario: FaultScenario): void {
    const delay = scenario.parameters.delay || 1000;
    const jitter = scenario.parameters.jitter || 0;
    logger.debug('Latency fault configured', { delay, jitter, scenarioId: scenario.id });
    eventBus.emit('fault.latency.injected', { scenarioId: scenario.id, delay, jitter });
  }

  private injectError(scenario: FaultScenario): void {
    const errorRate = scenario.parameters.errorRate || 0.1;
    const statusCode = scenario.parameters.statusCode || 500;
    logger.debug('Error fault configured', { errorRate, statusCode, scenarioId: scenario.id });
    eventBus.emit('fault.error.injected', { scenarioId: scenario.id, errorRate, statusCode });
  }

  private injectCrash(scenario: FaultScenario): void {
    logger.debug('Crash fault configured', { scenarioId: scenario.id });
    eventBus.emit('fault.crash.injected', { scenarioId: scenario.id });
  }

  private injectNetworkPartition(scenario: FaultScenario): void {
    const targets = scenario.parameters.targets || [];
    logger.debug('Network partition fault configured', { targets, scenarioId: scenario.id });
    eventBus.emit('fault.network_partition.injected', { scenarioId: scenario.id, targets });
  }

  private injectDiskFull(scenario: FaultScenario): void {
    const path = scenario.parameters.path || '/';
    const percentage = scenario.parameters.percentage || 95;
    logger.debug('Disk full fault configured', { path, percentage, scenarioId: scenario.id });
    eventBus.emit('fault.disk_full.injected', { scenarioId: scenario.id, path, percentage });
  }

  private injectMemoryLeak(scenario: FaultScenario): void {
    const rate = scenario.parameters.rate || '10MB/s';
    logger.debug('Memory leak fault configured', { rate, scenarioId: scenario.id });
    eventBus.emit('fault.memory_leak.injected', { scenarioId: scenario.id, rate });
  }

  private checkRollbackCondition(scenario: FaultScenario): void {
    const metrics = this.getSystemMetrics();
    
    const shouldRollback = 
      metrics.errorRate > scenario.autoRollback.errorRateThreshold ||
      metrics.avgLatency > scenario.autoRollback.latencyThreshold ||
      metrics.failureCount > scenario.autoRollback.failureThreshold;

    if (shouldRollback) {
      logger.warn('Auto rollback triggered', {
        scenarioId: scenario.id,
        metrics,
        thresholds: scenario.autoRollback,
      });
      this.stopInjection(scenario.id, 'auto_rollback');
    }
  }

  private getSystemMetrics() {
    return {
      errorRate: Math.random() * 0.1,
      avgLatency: Math.random() * 500,
      failureCount: Math.floor(Math.random() * 10),
    };
  }

  stopInjection(scenarioId: string, reason: string): RollbackRecord {
    const injection = this.activeInjections.get(scenarioId);
    const scenario = this.scenarios.get(scenarioId);

    if (!scenario) {
      const record: RollbackRecord = {
        id: generateId('rb_'),
        scenarioId,
        reason,
        timestamp: currentTimestamp(),
        success: false,
        details: { error: 'Scenario not found' },
      };
      this.rollbackHistory.push(record);
      return record;
    }

    try {
      if (injection) {
        clearTimeout(injection.timeout);
        if (injection.checkInterval) {
          clearInterval(injection.checkInterval);
        }
        this.activeInjections.delete(scenarioId);
      }

      this.rollbackFault(scenario);

      scenario.status = reason === 'auto_rollback' ? 'rolled_back' : 'completed';
      scenario.endedAt = currentTimestamp();
      this.scenarios.set(scenarioId, scenario);

      this.metrics.activeInjections--;
      this.metrics.successfulRollbacks++;

      const record: RollbackRecord = {
        id: generateId('rb_'),
        scenarioId,
        reason,
        timestamp: currentTimestamp(),
        success: true,
        details: {
          duration: scenario.startedAt
            ? new Date(scenario.endedAt!).getTime() - new Date(scenario.startedAt).getTime()
            : 0,
        },
      };
      this.rollbackHistory.push(record);

      logger.info('Fault injection stopped', { scenarioId, reason });
      eventBus.emit('fault.injection.stopped', { scenario, reason });

      return record;
    } catch (error: any) {
      this.metrics.failedRollbacks++;
      const record: RollbackRecord = {
        id: generateId('rb_'),
        scenarioId,
        reason,
        timestamp: currentTimestamp(),
        success: false,
        details: { error: error.message },
      };
      this.rollbackHistory.push(record);
      return record;
    }
  }

  private rollbackFault(scenario: FaultScenario): void {
    logger.debug('Rolling back fault', { type: scenario.faultType, scenarioId: scenario.id });
    eventBus.emit('fault.rolledback', { scenarioId: scenario.id });
  }

  getScenario(id: string): FaultScenario | undefined {
    return this.scenarios.get(id);
  }

  listScenarios(status?: FaultScenario['status']): FaultScenario[] {
    const scenarios = Array.from(this.scenarios.values());
    if (status) {
      return scenarios.filter(s => s.status === status);
    }
    return scenarios;
  }

  getRollbackHistory(scenarioId?: string): RollbackRecord[] {
    if (scenarioId) {
      return this.rollbackHistory.filter(r => r.scenarioId === scenarioId);
    }
    return this.rollbackHistory;
  }

  getActiveInjections(): FaultScenario[] {
    return Array.from(this.activeInjections.values()).map(i => i.scenario);
  }

  getMetrics(): FaultMetrics {
    const durations = this.rollbackHistory
      .filter(r => r.success && r.details.duration)
      .map(r => r.details.duration as number);
    
    return {
      ...this.metrics,
      avgFaultDuration: durations.length > 0
        ? durations.reduce((a, b) => a + b, 0) / durations.length
        : 0,
    };
  }

  shouldApplyFault(
    scenarioId: string,
    requestInfo: {
      service: string;
      endpoint: string;
      host: string;
      headers: Record<string, string>;
    },
  ): boolean {
    const injection = this.activeInjections.get(scenarioId);
    if (!injection) return false;

    const scope = injection.scenario.scope;

    if (scope.services?.length && !scope.services.includes(requestInfo.service)) {
      return false;
    }
    if (scope.endpoints?.length && !scope.endpoints.includes(requestInfo.endpoint)) {
      return false;
    }
    if (scope.hosts?.length && !scope.hosts.includes(requestInfo.host)) {
      return false;
    }
    if (scope.headers) {
      for (const [key, value] of Object.entries(scope.headers)) {
        if (requestInfo.headers[key] !== value) {
          return false;
        }
      }
    }

    return Math.random() < scope.sampleRate;
  }

  stopAll(): void {
    for (const scenarioId of this.activeInjections.keys()) {
      this.stopInjection(scenarioId, 'shutdown');
    }
    logger.info('All fault injections stopped');
  }
}

export const faultOrchestrator = new FaultInjectionOrchestrator();
