import { EventEmitter, generateId } from '../utils';
import logger from '../utils/logger';
import { SLIConfig, SLOConfig, SLIMetric, ErrorBudgetState } from '../types';

export type OperationType = 'read' | 'write';

export interface ReadOperation {
  type: 'read';
  operation: string;
  params: any[];
  options?: ReadOptions;
}

export interface WriteOperation {
  type: 'write';
  operation: string;
  params: any[];
  options?: WriteOptions;
}

export interface ReadOptions {
  prefer_replica?: boolean;
  max_stale_ms?: number;
  timeout_ms?: number;
}

export interface WriteOptions {
  sync_replicas?: number;
  timeout_ms?: number;
  durability_level?: 'memory' | 'disk' | 'replicated';
}

export interface OperationResult<T> {
  success: boolean;
  data?: T;
  error?: string;
  latency_ms: number;
  source: 'primary' | 'replica' | 'cache';
}

export interface ReplicaInfo {
  id: string;
  endpoint: string;
  healthy: boolean;
  last_sync_timestamp: number;
  lag_ms: number;
  priority: number;
}

export interface RoutingStats {
  total_operations: number;
  read_operations: number;
  write_operations: number;
  cache_hits: number;
  cache_misses: number;
  primary_reads: number;
  replica_reads: number;
  failed_operations: number;
  average_latency_ms: number;
}

interface RoutingEvents {
  'operation.routed': { operation: string; type: OperationType; target: string };
  'operation.completed': OperationResult<any>;
  'operation.failed': { operation: string; error: string };
  'replica.added': ReplicaInfo;
  'replica.removed': { id: string };
  'failover.started': { from: string; to: string };
  'failover.completed': { new_primary: string };
}

export class ReadWriteRouter extends EventEmitter<RoutingEvents> {
  private primary: string | null = null;
  private replicas: Map<string, ReplicaInfo>;
  private operationRouter: Map<string, OperationType>;
  private stats: RoutingStats;
  private latencyHistory: number[];
  private maxLatencyHistory: number;
  private circuitBreakerState: Map<string, { failures: number; last_failure: number; open: boolean }>;
  private enableReadFromPrimary: boolean = true;

  constructor() {
    super();
    this.replicas = new Map();
    this.operationRouter = new Map();
    this.circuitBreakerState = new Map();
    this.maxLatencyHistory = 1000;
    this.latencyHistory = [];
    this.stats = {
      total_operations: 0,
      read_operations: 0,
      write_operations: 0,
      cache_hits: 0,
      cache_misses: 0,
      primary_reads: 0,
      replica_reads: 0,
      failed_operations: 0,
      average_latency_ms: 0,
    };
    this.initializeOperationRouter();
  }

  private initializeOperationRouter(): void {
    const readOperations = [
      'getSLIConfig',
      'getSLOConfig',
      'getAllSLIConfigs',
      'getAllSLOConfigs',
      'getErrorBudgetState',
      'getAllErrorBudgetStates',
      'getSLIMetrics',
      'calculateSLI',
      'calculateSLO',
      'predictBurnRate',
      'predictTimeToExhaust',
      'getSLO',
      'getSLI',
      'getErrorBudget',
      'getAllErrorBudgets',
    ];

    const writeOperations = [
      'addSLIConfig',
      'addSLOConfig',
      'deleteSLIConfig',
      'deleteSLOConfig',
      'recordSLI',
      'recordAvailabilitySLI',
      'recordLatencySLI',
      'recordQualitySLI',
      'updateErrorBudget',
      'resetErrorBudget',
      'createSLI',
      'createSLO',
      'recordSuccess',
      'recordFailure',
    ];

    for (const op of readOperations) {
      this.operationRouter.set(op, 'read');
    }

    for (const op of writeOperations) {
      this.operationRouter.set(op, 'write');
    }
  }

  registerOperation(operation: string, type: OperationType): void {
    this.operationRouter.set(operation, type);
    logger.info(`Registered operation ${operation} as ${type}`);
  }

  getOperationType(operation: string): OperationType {
    return this.operationRouter.get(operation) || 'read';
  }

  setPrimary(endpoint: string): void {
    this.primary = endpoint;
    logger.info(`Set primary endpoint: ${endpoint}`);
  }

  addReplica(replica: Omit<ReplicaInfo, 'id'>): string {
    const id = generateId('replica');
    const replicaInfo: ReplicaInfo = {
      ...replica,
      id,
    };
    this.replicas.set(id, replicaInfo);
    this.emit('replica.added', replicaInfo);
    logger.info(`Added replica: ${endpoint}`);
    return id;
  }

  removeReplica(id: string): boolean {
    const removed = this.replicas.delete(id);
    if (removed) {
      this.emit('replica.removed', { id });
      logger.info(`Removed replica: ${id}`);
    }
    return removed;
  }

  updateReplicaStatus(id: string, updates: Partial<ReplicaInfo>): boolean {
    const replica = this.replicas.get(id);
    if (!replica) return false;

    Object.assign(replica, updates);
    this.replicas.set(id, replica);
    return true;
  }

  getHealthyReplicas(): ReplicaInfo[] {
    return Array.from(this.replicas.values()).filter((r) => r.healthy);
  }

  selectReplica(options?: ReadOptions): ReplicaInfo | null {
    const healthyReplicas = this.getHealthyReplicas();
    if (healthyReplicas.length === 0) return null;

    const maxStale = options?.max_stale_ms ?? 5000;
    const eligibleReplicas = healthyReplicas.filter(
      (r) => r.lag_ms <= maxStale
    );

    if (eligibleReplicas.length === 0) {
      logger.warn('No eligible replicas, falling back to primary');
      return null;
    }

    eligibleReplicas.sort((a, b) => a.priority - b.priority || a.lag_ms - b.lag_ms);
    return eligibleReplicas[0];
  }

  async routeRead<T>(
    operation: string,
    params: any[],
    primaryFn: () => Promise<T>,
    replicaFn?: (replica: ReplicaInfo) => Promise<T>,
    options?: ReadOptions
  ): Promise<OperationResult<T>> {
    const startTime = Date.now();
    this.stats.total_operations++;
    this.stats.read_operations++;

    this.emit('operation.routed', { operation, type: 'read', target: 'pending' });

    try {
      let result: T;
      let source: 'primary' | 'replica' | 'cache' = 'primary';

      if (replicaFn && options?.prefer_replica !== false) {
        const replica = this.selectReplica(options);
        if (replica && !this.isCircuitBreakerOpen(replica.id)) {
          try {
            result = await this.withTimeout(
              replicaFn(replica),
              options?.timeout_ms ?? 5000
            );
            source = 'replica';
            this.stats.replica_reads++;
            this.recordSuccess(replica.id);
          } catch (error) {
            logger.warn(`Replica read failed for ${operation}, falling back to primary:`, error);
            this.recordFailure(replica.id);
            if (!this.enableReadFromPrimary) {
              throw error;
            }
            result = await this.withTimeout(primaryFn(), options?.timeout_ms ?? 5000);
            this.stats.primary_reads++;
          }
        } else {
          result = await this.withTimeout(primaryFn(), options?.timeout_ms ?? 5000);
          this.stats.primary_reads++;
        }
      } else {
        result = await this.withTimeout(primaryFn(), options?.timeout_ms ?? 5000);
        this.stats.primary_reads++;
      }

      const latency = Date.now() - startTime;
      this.recordLatency(latency);

      const operationResult: OperationResult<T> = {
        success: true,
        data: result,
        latency_ms: latency,
        source,
      };

      this.emit('operation.completed', operationResult);
      return operationResult;
    } catch (error) {
      this.stats.failed_operations++;
      const latency = Date.now() - startTime;

      const operationResult: OperationResult<T> = {
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
        latency_ms: latency,
        source: 'primary',
      };

      this.emit('operation.failed', { operation, error: operationResult.error! });
      return operationResult;
    }
  }

  async routeWrite<T>(
    operation: string,
    params: any[],
    primaryFn: () => Promise<T>,
    options?: WriteOptions
  ): Promise<OperationResult<T>> {
    const startTime = Date.now();
    this.stats.total_operations++;
    this.stats.write_operations++;

    this.emit('operation.routed', { operation, type: 'write', target: 'primary' });

    try {
      const result = await this.withTimeout(primaryFn(), options?.timeout_ms ?? 10000);
      const latency = Date.now() - startTime;
      this.recordLatency(latency);

      const operationResult: OperationResult<T> = {
        success: true,
        data: result,
        latency_ms: latency,
        source: 'primary',
      };

      this.emit('operation.completed', operationResult);
      return operationResult;
    } catch (error) {
      this.stats.failed_operations++;
      const latency = Date.now() - startTime;

      const operationResult: OperationResult<T> = {
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
        latency_ms: latency,
        source: 'primary',
      };

      this.emit('operation.failed', { operation, error: operationResult.error! });
      return operationResult;
    }
  }

  private async withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
    return Promise.race([
      promise,
      new Promise<T>((_, reject) =>
        setTimeout(() => reject(new Error(`Operation timed out after ${timeoutMs}ms`)), timeoutMs)
      ),
    ]);
  }

  private recordLatency(latency: number): void {
    this.latencyHistory.push(latency);
    if (this.latencyHistory.length > this.maxLatencyHistory) {
      this.latencyHistory.shift();
    }
    this.stats.average_latency_ms =
      this.latencyHistory.reduce((sum, l) => sum + l, 0) / this.latencyHistory.length;
  }

  private isCircuitBreakerOpen(replicaId: string): boolean {
    const state = this.circuitBreakerState.get(replicaId);
    if (!state) return false;

    if (state.open) {
      if (Date.now() - state.last_failure > 30000) {
        state.open = false;
        state.failures = 0;
      }
    }

    return state.open;
  }

  private recordFailure(replicaId: string): void {
    const state = this.circuitBreakerState.get(replicaId) || { failures: 0, last_failure: 0, open: false };
    state.failures++;
    state.last_failure = Date.now();

    if (state.failures >= 5) {
      state.open = true;
      logger.warn(`Circuit breaker opened for replica ${replicaId}`);
    }

    this.circuitBreakerState.set(replicaId, state);
  }

  private recordSuccess(replicaId: string): void {
    const state = this.circuitBreakerState.get(replicaId);
    if (state) {
      state.failures = Math.max(0, state.failures - 1);
      if (state.failures === 0 && state.open) {
        state.open = false;
        logger.info(`Circuit breaker closed for replica ${replicaId}`);
      }
    }
  }

  async triggerFailover(newPrimaryId: string): Promise<boolean> {
    const newPrimary = this.replicas.get(newPrimaryId);
    if (!newPrimary || !newPrimary.healthy) {
      logger.error(`Cannot failover to unhealthy or non-existent replica: ${newPrimaryId}`);
      return false;
    }

    this.emit('failover.started', { from: this.primary || 'unknown', to: newPrimary.endpoint });

    this.primary = newPrimary.endpoint;
    this.replicas.delete(newPrimaryId);

    this.emit('failover.completed', { new_primary: this.primary });
    logger.info(`Failover complete, new primary: ${this.primary}`);

    return true;
  }

  getStats(): RoutingStats {
    return { ...this.stats };
  }

  resetStats(): void {
    this.stats = {
      total_operations: 0,
      read_operations: 0,
      write_operations: 0,
      cache_hits: 0,
      cache_misses: 0,
      primary_reads: 0,
      replica_reads: 0,
      failed_operations: 0,
      average_latency_ms: 0,
    };
    this.latencyHistory = [];
  }

  getReplicas(): ReplicaInfo[] {
    return Array.from(this.replicas.values());
  }

  getPrimary(): string | null {
    return this.primary;
  }

  setReadFromPrimary(enabled: boolean): void {
    this.enableReadFromPrimary = enabled;
    logger.info(`Read from primary ${enabled ? 'enabled' : 'disabled'}`);
  }

  clear(): void {
    this.replicas.clear();
    this.circuitBreakerState.clear();
    this.resetStats();
    this.primary = null;
  }
}

export default ReadWriteRouter;
