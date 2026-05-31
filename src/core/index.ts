import { EventEmitter, generateId, retry, getCurrentTimestamp, AsyncQueue } from '../utils';
import logger from '../utils/logger';
import { RunInstance, APIResponse, ResourceCreateRequest, ResourceResponse, BatchOperationRequest, BatchOperationResponse } from '../types';
import sloManager from '../slo';
import metricsService from '../metrics';
import performanceMonitor from '../monitoring';
import alertEngine from '../alerting';
import traceCollector from '../tracing';
import configManager from '../config';

interface ProcessingContext {
  traceId: string;
  requestId: string;
  startTime: number;
  namespace: string;
  attributes: Record<string, any>;
}

interface CoreEvents {
  'request.received': { request_id: string; trace_id: string; type: string };
  'request.completed': { request_id: string; trace_id: string; duration_ms: number; success: boolean };
  'request.failed': { request_id: string; trace_id: string; error: string };
  'resource.created': ResourceResponse;
  'task.started': RunInstance;
  'task.completed': RunInstance;
  'task.failed': RunInstance;
  'event.published': { event_type: string; data: any; timestamp: string };
}

export class ValidationError extends Error {
  constructor(message: string, public details?: any) {
    super(message);
    this.name = 'ValidationError';
  }
}

export class TimeoutError extends Error {
  constructor(message: string = 'Operation timed out') {
    super(message);
    this.name = 'TimeoutError';
  }
}

export class ProcessingPipeline<TInput, TOutput> {
  private stages: Array<(input: TInput, context: ProcessingContext) => Promise<any>>;
  private name: string;

  constructor(name: string) {
    this.name = name;
    this.stages = [];
  }

  addStage(stage: (input: TInput, context: ProcessingContext) => Promise<any>): void {
    this.stages.push(stage);
  }

  async execute(input: TInput, context: ProcessingContext): Promise<TOutput> {
    let result: any = input;

    for (let i = 0; i < this.stages.length; i++) {
      const stage = this.stages[i];
      try {
        const stageStart = Date.now();
        result = await stage(result, context);
        const stageDuration = Date.now() - stageStart;
        logger.debug(`Pipeline ${this.name} stage ${i} completed in ${stageDuration}ms`);
      } catch (error) {
        logger.error(`Pipeline ${this.name} stage ${i} failed:`, error);
        throw error;
      }
    }

    return result as TOutput;
  }

  getStageCount(): number {
    return this.stages.length;
  }
}

export class TaskExecutor extends EventEmitter<CoreEvents> {
  private runningTasks: Map<string, RunInstance>;
  private taskQueue: AsyncQueue<{ request: any; context: ProcessingContext }>;
  private maxConcurrentTasks: number;
  private workerCount: number;
  private activeWorkers: number;

  constructor(maxConcurrentTasks: number = 10, workerCount: number = 4) {
    super();
    this.runningTasks = new Map();
    this.taskQueue = new AsyncQueue();
    this.maxConcurrentTasks = maxConcurrentTasks;
    this.workerCount = workerCount;
    this.activeWorkers = 0;
  }

  async submit<T>(request: T, context: ProcessingContext, processor: (request: T, context: ProcessingContext) => Promise<any>): Promise<RunInstance> {
    const runId = generateId('run');
    const task: RunInstance = {
      run_id: runId,
      entity_id: context.requestId,
      phase: 'initializing',
      progress: 0,
      started_at: getCurrentTimestamp(),
      completed_at: null,
      error_detail: null,
    };

    this.runningTasks.set(runId, task);
    this.emit('task.started', task);

    if (this.activeWorkers < this.workerCount && this.runningTasks.size < this.maxConcurrentTasks) {
      this.processTask(runId, request, context, processor);
    } else {
      this.taskQueue.enqueue({ request, context });
      logger.debug(`Task ${runId} queued, active workers: ${this.activeWorkers}`);
    }

    return task;
  }

  private async processTask<T>(
    runId: string,
    request: T,
    context: ProcessingContext,
    processor: (request: T, context: ProcessingContext) => Promise<any>
  ): Promise<void> {
    this.activeWorkers++;
    const task = this.runningTasks.get(runId)!;

    try {
      task.phase = 'processing';
      task.progress = 0.25;
      this.runningTasks.set(runId, task);

      const result = await processor(request, context);

      task.phase = 'completing';
      task.progress = 0.9;
      this.runningTasks.set(runId, task);

      task.phase = 'completed';
      task.progress = 1.0;
      task.completed_at = getCurrentTimestamp();
      this.runningTasks.set(runId, task);

      this.emit('task.completed', task);
      logger.info(`Task ${runId} completed successfully`);

      metricsService.recordMetric('task_completed_total', 1, { namespace: context.namespace });
    } catch (error) {
      task.phase = 'failed';
      task.error_detail = error instanceof Error ? error.message : 'Unknown error';
      task.completed_at = getCurrentTimestamp();
      this.runningTasks.set(runId, task);

      this.emit('task.failed', task);
      logger.error(`Task ${runId} failed:`, error);

      metricsService.recordMetric('task_failed_total', 1, { namespace: context.namespace });
    } finally {
      this.activeWorkers--;
      this.processNextTask(processor);
    }
  }

  private async processNextTask<T>(processor: (request: T, context: ProcessingContext) => Promise<any>): Promise<void> {
    if (!this.taskQueue.isEmpty() && this.activeWorkers < this.workerCount) {
      const next = await this.taskQueue.dequeue();
      const runId = generateId('run');
      const task: RunInstance = {
        run_id: runId,
        entity_id: next.context.requestId,
        phase: 'initializing',
        progress: 0,
        started_at: getCurrentTimestamp(),
        completed_at: null,
        error_detail: null,
      };
      this.runningTasks.set(runId, task);
      this.emit('task.started', task);
      this.processTask(runId, next.request, next.context, processor);
    }
  }

  getTask(runId: string): RunInstance | undefined {
    return this.runningTasks.get(runId);
  }

  getTasksByEntity(entityId: string): RunInstance[] {
    return Array.from(this.runningTasks.values()).filter((t) => t.entity_id === entityId);
  }

  getActiveTaskCount(): number {
    return this.runningTasks.size;
  }

  getQueueSize(): number {
    return this.taskQueue.size();
  }

  cancelTask(runId: string): boolean {
    const task = this.runningTasks.get(runId);
    if (!task || task.phase === 'completed' || task.phase === 'failed') {
      return false;
    }

    task.phase = 'cancelled';
    task.completed_at = getCurrentTimestamp();
    task.error_detail = 'Task cancelled by user';
    this.runningTasks.set(runId, task);
    this.emit('task.failed', task);

    logger.info(`Task ${runId} cancelled`);
    return true;
  }

  clearCompletedTasks(maxAgeMs: number = 3600000): number {
    const now = Date.now();
    let cleared = 0;

    for (const [runId, task] of this.runningTasks.entries()) {
      if (task.completed_at) {
        const completedTime = new Date(task.completed_at).getTime();
        if (now - completedTime > maxAgeMs) {
          this.runningTasks.delete(runId);
          cleared++;
        }
      }
    }

    if (cleared > 0) {
      logger.debug(`Cleared ${cleared} completed tasks`);
    }

    return cleared;
  }
}

export class RequestHandler extends EventEmitter<CoreEvents> {
  private taskExecutor: TaskExecutor;
  private rateLimiter: Map<string, { count: number; window_start: number }>;
  private rateLimitConfig: { max_requests: number; window_ms: number };

  constructor() {
    super();
    this.taskExecutor = new TaskExecutor();
    this.rateLimiter = new Map();
    this.rateLimitConfig = {
      max_requests: 1000,
      window_ms: 60000,
    };
  }

  setRateLimit(maxRequests: number, windowMs: number): void {
    this.rateLimitConfig = { max_requests: maxRequests, window_ms: windowMs };
    logger.info(`Rate limit set to ${maxRequests} requests per ${windowMs}ms`);
  }

  private checkRateLimit(clientId: string): boolean {
    const now = Date.now();
    const client = this.rateLimiter.get(clientId);

    if (!client || now - client.window_start > this.rateLimitConfig.window_ms) {
      this.rateLimiter.set(clientId, { count: 1, window_start: now });
      return true;
    }

    if (client.count >= this.rateLimitConfig.max_requests) {
      return false;
    }

    client.count++;
    return true;
  }

  createContext(traceId: string, namespace: string = 'default'): ProcessingContext {
    return {
      traceId,
      requestId: generateId('req'),
      startTime: Date.now(),
      namespace,
      attributes: {},
    };
  }

  async processRequest<T>(
    request: any,
    context: ProcessingContext,
    handler: (request: any, context: ProcessingContext) => Promise<T>
  ): Promise<APIResponse<T>> {
    this.emit('request.received', {
      request_id: context.requestId,
      trace_id: context.traceId,
      type: request.type || 'unknown',
    });

    const clientId = context.attributes.client_id || 'default';
    if (!this.checkRateLimit(clientId)) {
      logger.warn(`Rate limit exceeded for client ${clientId}`);
      return this.createErrorResponse(429, 'Rate limit exceeded');
    }

    try {
      const result = await performanceMonitor.measure('request_processing', async () => {
        return await retry(() => handler(request, context), 3, 1000, 2);
      }, { namespace: context.namespace });

      const duration = Date.now() - context.startTime;
      this.emit('request.completed', {
        request_id: context.requestId,
        trace_id: context.traceId,
        duration_ms: duration,
        success: true,
      });

      sloManager.recordSuccess('api_requests', { namespace: context.namespace });
      metricsService.recordMetric('request_duration_ms', duration, { namespace: context.namespace });

      return this.createSuccessResponse(result);
    } catch (error) {
      const duration = Date.now() - context.startTime;

      if (error instanceof ValidationError) {
        this.emit('request.failed', {
          request_id: context.requestId,
          trace_id: context.traceId,
          error: error.message,
        });
        sloManager.recordFailure('api_requests', { namespace: context.namespace });
        return this.createErrorResponse(422, error.message, error.details);
      }

      if (error instanceof TimeoutError) {
        this.emit('request.failed', {
          request_id: context.requestId,
          trace_id: context.traceId,
          error: error.message,
        });
        sloManager.recordFailure('api_requests', { namespace: context.namespace });
        return this.createErrorResponse(504, '上游服务响应超时');
      }

      this.emit('request.failed', {
        request_id: context.requestId,
        trace_id: context.traceId,
        error: error instanceof Error ? error.message : 'Unknown error',
      });

      logger.error(`Request ${context.requestId} failed:`, error);
      sloManager.recordFailure('api_requests', { namespace: context.namespace });

      return this.createErrorResponse(500, '内部处理错误');
    }
  }

  async createResource(request: ResourceCreateRequest, context: ProcessingContext): Promise<APIResponse<ResourceResponse>> {
    return this.processRequest(request, context, async (req) => {
      this.validateResourceRequest(req);

      const configId = generateId('cfg');
      await configManager.addOrUpdateConfig({
        config_id: configId,
        namespace: context.namespace,
        version: 1,
        parameters: req.config,
        enabled: true,
        applied_at: getCurrentTimestamp(),
      });

      const resource: ResourceResponse = {
        id: generateId('rsc'),
        status: 'provisioning',
        type: req.type,
        created_at: getCurrentTimestamp(),
      };

      this.emit('resource.created', resource);
      logger.info(`Resource ${resource.id} created, type: ${req.type}`);

      return resource;
    });
  }

  private validateResourceRequest(request: ResourceCreateRequest): void {
    if (!request.type) {
      throw new ValidationError('Resource type is required');
    }
    if (!request.config || typeof request.config !== 'object') {
      throw new ValidationError('Resource config must be an object');
    }
  }

  async getResourceStatus(resourceId: string, context: ProcessingContext): Promise<APIResponse<any>> {
    return this.processRequest({ resourceId }, context, async (req) => {
      const config = configManager.getConfig(context.namespace, req.resourceId);
      if (!config) {
        throw new ValidationError(`Resource ${req.resourceId} not found`);
      }

      return {
        id: req.resourceId,
        status: config.enabled ? 'active' : 'inactive',
        progress: 1.0,
        config: config.parameters,
      };
    });
  }

  async batchOperation(
    request: BatchOperationRequest,
    context: ProcessingContext
  ): Promise<APIResponse<BatchOperationResponse>> {
    return this.processRequest(request, context, async (req) => {
      const batchId = generateId('batch');
      const results: Array<{ id: string; action: string; success: boolean; error?: string }> = [];

      for (const op of req.operations) {
        try {
          const result = await this.executeOperation(op, context);
          results.push({ id: op.id, action: op.action, success: true, ...result });
        } catch (error) {
          results.push({
            id: op.id,
            action: op.action,
            success: false,
            error: error instanceof Error ? error.message : 'Unknown error',
          });
        }
      }

      logger.info(`Batch operation ${batchId} completed: ${results.filter((r) => r.success).length}/${results.length} successful`);

      return {
        batch_id: batchId,
        results,
      };
    });
  }

  private async executeOperation(
    operation: { action: string; id: string; params?: Record<string, any> },
    context: ProcessingContext
  ): Promise<any> {
    switch (operation.action) {
      case 'start':
        return { message: `Resource ${operation.id} started` };
      case 'stop':
        return { message: `Resource ${operation.id} stopped` };
      case 'restart':
        return { message: `Resource ${operation.id} restarted` };
      case 'delete':
        configManager.deleteConfig(context.namespace, operation.id);
        return { message: `Resource ${operation.id} deleted` };
      default:
        throw new ValidationError(`Unknown action: ${operation.action}`);
    }
  }

  private createSuccessResponse<T>(data: T): APIResponse<T> {
    return {
      code: 200,
      data,
      timestamp: getCurrentTimestamp(),
    };
  }

  private createErrorResponse(code: number, message: string, error?: any): APIResponse {
    return {
      code,
      message,
      error: error || message,
      timestamp: getCurrentTimestamp(),
    };
  }

  publishEvent(eventType: string, data: any): void {
    this.emit('event.published', {
      event_type: eventType,
      data,
      timestamp: getCurrentTimestamp(),
    });
    logger.debug(`Published event: ${eventType}`);
  }

  getTaskExecutor(): TaskExecutor {
    return this.taskExecutor;
  }

  async getStats(): Promise<any> {
    return {
      active_tasks: this.taskExecutor.getActiveTaskCount(),
      queued_tasks: this.taskExecutor.getQueueSize(),
      rate_limit: this.rateLimitConfig,
    };
  }
}

const requestHandler = new RequestHandler();

export default requestHandler;
