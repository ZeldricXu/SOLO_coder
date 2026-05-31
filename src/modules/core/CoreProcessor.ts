import { RequestContext, ApiResponse, Resource, RunInstance, ErrorDetail, MetricsSnapshot } from '../../types/core';
import { generateId, getCurrentTimestamp, retryWithBackoff, withTimeout, generateUUID } from '../../common/utils';
import { AppError, ValidationError, TimeoutError, NotFoundError, isAppError, handleError } from '../../common/errors';
import { EventEmitter } from 'events';

export interface CoreProcessorConfig {
  defaultTimeoutMs?: number;
  maxRetries?: number;
  retryBackoffMs?: number;
  maxConcurrentRequests?: number;
}

export interface ProcessResult<T = unknown> {
  success: boolean;
  data?: T;
  error?: ErrorDetail;
  durationMs: number;
  traceId: string;
}

export interface HandlerFunction<T = unknown, R = unknown> {
  (request: T, context: RequestContext): Promise<R> | R;
}

export interface HandlerDefinition {
  name: string;
  handler: HandlerFunction;
  timeoutMs?: number;
  retries?: number;
  concurrencyLimit?: number;
}

export type EventType =
  | 'request.received'
  | 'request.completed'
  | 'request.failed'
  | 'resource.created'
  | 'resource.updated'
  | 'resource.deleted'
  | 'task.completed'
  | 'task.failed';

export class CoreProcessor extends EventEmitter {
  private config: Required<CoreProcessorConfig>;
  private handlers: Map<string, HandlerDefinition>;
  private resources: Map<string, Resource>;
  private runInstances: Map<string, RunInstance>;
  private metrics: MetricsSnapshot[];
  private activeRequests: number;
  private requestQueue: Array<() => Promise<void>>;

  constructor(config: CoreProcessorConfig = {}) {
    super();
    this.config = {
      defaultTimeoutMs: config.defaultTimeoutMs ?? 30000,
      maxRetries: config.maxRetries ?? 3,
      retryBackoffMs: config.retryBackoffMs ?? 1000,
      maxConcurrentRequests: config.maxConcurrentRequests ?? 100
    };

    this.handlers = new Map();
    this.resources = new Map();
    this.runInstances = new Map();
    this.metrics = [];
    this.activeRequests = 0;
    this.requestQueue = [];
  }

  registerHandler(definition: HandlerDefinition): void {
    this.handlers.set(definition.name, definition);
  }

  unregisterHandler(name: string): boolean {
    return this.handlers.delete(name);
  }

  hasHandler(name: string): boolean {
    return this.handlers.has(name);
  }

  getHandler(name: string): HandlerDefinition | undefined {
    return this.handlers.get(name);
  }

  private createContext(tenantId?: string, userId?: string): RequestContext {
    return {
      traceId: generateUUID(),
      tenantId,
      userId,
      timestamp: Date.now(),
      metadata: {}
    };
  }

  async processRequest<T = unknown, R = unknown>(
    handlerName: string,
    payload: T,
    options: { tenantId?: string; userId?: string; params?: Record<string, unknown> } = {}
  ): Promise<ProcessResult<R>> {
    const startTime = Date.now();
    const context = this.createContext(options.tenantId, options.userId);
    context.metadata.params = options.params;

    this.emit('request.received', { handlerName, context, payload });

    if (this.activeRequests >= this.config.maxConcurrentRequests) {
      return new Promise((resolve) => {
        this.requestQueue.push(async () => {
          const result = await this.executeHandler(handlerName, payload, context, startTime);
          resolve(result);
        });
        this.processQueue();
      });
    }

    return this.executeHandler(handlerName, payload, context, startTime);
  }

  private async executeHandler<T = unknown, R = unknown>(
    handlerName: string,
    payload: T,
    context: RequestContext,
    startTime: number
  ): Promise<ProcessResult<R>> {
    this.activeRequests++;

    try {
      const handlerDef = this.handlers.get(handlerName);
      if (!handlerDef) {
        throw new NotFoundError(`处理器不存在: ${handlerName}`);
      }

      const timeoutMs = handlerDef.timeoutMs ?? this.config.defaultTimeoutMs;
      const retries = handlerDef.retries ?? this.config.maxRetries;

      const result = await retryWithBackoff(
        async () => {
          return withTimeout(
            handlerDef.handler(payload, context),
            timeoutMs,
            `处理器执行超时 (${timeoutMs}ms)`
          );
        },
        retries,
        this.config.retryBackoffMs
      );

      const durationMs = Date.now() - startTime;

      this.emit('request.completed', { handlerName, context, result, durationMs });
      this.recordMetrics(context, durationMs, true);

      return {
        success: true,
        data: result as R,
        durationMs,
        traceId: context.traceId
      };
    } catch (error) {
      const durationMs = Date.now() - startTime;
      const appError = handleError(error);

      this.emit('request.failed', { handlerName, context, error: appError, durationMs });
      this.recordMetrics(context, durationMs, false);

      return {
        success: false,
        error: {
          code: appError.code,
          message: appError.message,
          metadata: appError.details
        },
        durationMs,
        traceId: context.traceId
      };
    } finally {
      this.activeRequests--;
      this.processQueue();
    }
  }

  private processQueue(): void {
    if (this.requestQueue.length > 0 && this.activeRequests < this.config.maxConcurrentRequests) {
      const next = this.requestQueue.shift();
      if (next) {
        next();
      }
    }
  }

  createResource(
    type: Resource['type'],
    config: Record<string, unknown> = {},
    labels: Record<string, string> = {},
    options: { tenantId?: string; userId?: string } = {}
  ): Resource {
    const now = getCurrentTimestamp();

    const resource: Resource = {
      id: generateId('rsc'),
      type,
      status: 'pending',
      attributes: {},
      config,
      labels,
      created_at: now,
      updated_at: now
    };

    if (options.tenantId) {
      resource.attributes.tenantId = options.tenantId;
    }
    if (options.userId) {
      resource.attributes.createdBy = options.userId;
    }

    this.resources.set(resource.id, resource);
    this.emit('resource.created', resource);

    return resource;
  }

  getResource(resourceId: string): Resource {
    const resource = this.resources.get(resourceId);
    if (!resource) {
      throw new NotFoundError(`资源不存在: ${resourceId}`);
    }
    return resource;
  }

  updateResource(
    resourceId: string,
    updates: Partial<Pick<Resource, 'config' | 'labels' | 'status' | 'attributes'>>
  ): Resource {
    const resource = this.getResource(resourceId);
    const updated = {
      ...resource,
      ...updates,
      updated_at: getCurrentTimestamp()
    };

    this.resources.set(resourceId, updated);
    this.emit('resource.updated', updated);

    return updated;
  }

  deleteResource(resourceId: string): void {
    const resource = this.getResource(resourceId);
    this.resources.delete(resourceId);
    this.emit('resource.deleted', resource);
  }

  listResources(filters?: {
    type?: Resource['type'];
    status?: Resource['status'];
    tenantId?: string;
    labels?: Record<string, string>;
  }): Resource[] {
    let resources = Array.from(this.resources.values());

    if (filters) {
      if (filters.type) {
        resources = resources.filter(r => r.type === filters.type);
      }
      if (filters.status) {
        resources = resources.filter(r => r.status === filters.status);
      }
      if (filters.tenantId) {
        resources = resources.filter(r => r.attributes.tenantId === filters.tenantId);
      }
      if (filters.labels) {
        resources = resources.filter(r =>
          Object.entries(filters.labels!).every(
            ([key, value]) => r.labels[key] === value
          )
        );
      }
    }

    return resources.sort((a, b) =>
      new Date(b.created_at).getTime() - new Date(a.created_at).getTime()
    );
  }

  startRunInstance(entityId: string): RunInstance {
    const now = getCurrentTimestamp();
    const instance: RunInstance = {
      run_id: generateId('run'),
      entity_id: entityId,
      phase: 'initializing',
      progress: 0,
      started_at: now,
      completed_at: null,
      error_detail: null
    };

    this.runInstances.set(instance.run_id, instance);
    return instance;
  }

  updateRunInstance(
    runId: string,
    updates: Partial<Pick<RunInstance, 'phase' | 'progress' | 'error_detail'>>
  ): RunInstance {
    const instance = this.runInstances.get(runId);
    if (!instance) {
      throw new NotFoundError(`运行实例不存在: ${runId}`);
    }

    const updated = { ...instance, ...updates };

    if (updates.phase === 'completed' || updates.phase === 'failed') {
      updated.completed_at = getCurrentTimestamp();
    }

    this.runInstances.set(runId, updated);

    if (updates.phase === 'completed') {
      this.emit('task.completed', updated);
    } else if (updates.phase === 'failed') {
      this.emit('task.failed', updated);
    }

    return updated;
  }

  getRunInstance(runId: string): RunInstance {
    const instance = this.runInstances.get(runId);
    if (!instance) {
      throw new NotFoundError(`运行实例不存在: ${runId}`);
    }
    return instance;
  }

  listRunInstances(entityId?: string): RunInstance[] {
    let instances = Array.from(this.runInstances.values());

    if (entityId) {
      instances = instances.filter(i => i.entity_id === entityId);
    }

    return instances.sort((a, b) =>
      new Date(b.started_at).getTime() - new Date(a.started_at).getTime()
    );
  }

  private recordMetrics(context: RequestContext, durationMs: number, success: boolean): void {
    const snapshot: MetricsSnapshot = {
      snapshot_id: generateId('snap'),
      timestamp: getCurrentTimestamp(),
      metrics: {
        throughput: 1,
        latency_p99: durationMs,
        error_rate: success ? 0 : 1,
        durationMs
      },
      dimensions: {
        host: process.env.HOSTNAME || 'localhost',
        region: process.env.REGION || 'default',
        tenantId: context.tenantId || 'unknown',
        userId: context.userId || 'unknown'
      }
    };

    this.metrics.push(snapshot);

    if (this.metrics.length > 1000) {
      this.metrics = this.metrics.slice(-1000);
    }
  }

  getMetrics(limit: number = 100): MetricsSnapshot[] {
    return this.metrics.slice(-limit);
  }

  getAggregatedMetrics() {
    if (this.metrics.length === 0) {
      return {
        totalRequests: 0,
        successRate: 0,
        avgLatency: 0,
        p99Latency: 0,
        errorRate: 0
      };
    }

    const latencies = this.metrics.map(m => m.metrics.latency_p99).sort((a, b) => a - b);
    const errors = this.metrics.filter(m => m.metrics.error_rate > 0).length;
    const p99Index = Math.floor(latencies.length * 0.99);

    return {
      totalRequests: this.metrics.length,
      successRate: (this.metrics.length - errors) / this.metrics.length,
      avgLatency: latencies.reduce((a, b) => a + b, 0) / latencies.length,
      p99Latency: latencies[p99Index] || 0,
      errorRate: errors / this.metrics.length
    };
  }

  buildSuccessResponse<T>(data: T, message?: string): ApiResponse<T> {
    return {
      code: 200,
      data,
      message
    };
  }

  buildErrorResponse(error: unknown): ApiResponse {
    const appError = handleError(error);
    return {
      code: appError.statusCode,
      error: appError.code,
      message: appError.message,
      details: appError.details
    };
  }

  executeHandlerSync<T, R>(handler: (payload: T) => R, payload: T): R {
    try {
      return handler(payload);
    } catch (error) {
      throw handleError(error);
    }
  }

  async executeHandlerAsync<T, R>(
    handler: (payload: T) => Promise<R>,
    payload: T,
    timeoutMs?: number
  ): Promise<R> {
    try {
      return await withTimeout(
        handler(payload),
        timeoutMs ?? this.config.defaultTimeoutMs
      );
    } catch (error) {
      throw handleError(error);
    }
  }

  async batchProcess<T, R>(
    handlerName: string,
    items: T[],
    options: { concurrency?: number; tenantId?: string; userId?: string } = {}
  ): Promise<Array<ProcessResult<R>>> {
    const concurrency = options.concurrency ?? 10;
    const results: Array<ProcessResult<R>> = [];
    const chunks: T[][] = [];

    for (let i = 0; i < items.length; i += concurrency) {
      chunks.push(items.slice(i, i + concurrency));
    }

    for (const chunk of chunks) {
      const chunkResults = await Promise.all(
        chunk.map(item =>
          this.processRequest<T, R>(handlerName, item, options)
        )
      );
      results.push(...chunkResults);
    }

    return results;
  }

  getStats() {
    return {
      activeRequests: this.activeRequests,
      queuedRequests: this.requestQueue.length,
      registeredHandlers: this.handlers.size,
      totalResources: this.resources.size,
      totalRunInstances: this.runInstances.size,
      totalMetrics: this.metrics.length,
      maxConcurrentRequests: this.config.maxConcurrentRequests
    };
  }

  destroy(): void {
    this.handlers.clear();
    this.resources.clear();
    this.runInstances.clear();
    this.metrics = [];
    this.requestQueue = [];
    this.removeAllListeners();
  }
}
