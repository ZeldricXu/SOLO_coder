import EventEmitter from 'eventemitter3';
import { v4 as uuidv4 } from 'uuid';
import { ProcessingContext, ProcessingResult, Event, EventHandler, CoreEngineConfig, Resource } from './types';
import { CoreEntity, RunInstance } from '../types';
import { MonitoringService } from '../monitoring';
import { NotificationService } from '../notification';
import { Logger } from '../logging';

export class CoreEngine extends EventEmitter {
  private eventHandlers: Map<string, EventHandler[]> = new Map();
  private resources: Map<string, Resource> = new Map();
  private runInstances: Map<string, RunInstance> = new Map();
  private entityRegistry: Map<string, CoreEntity> = new Map();
  private compensations: Map<string, (context: ProcessingContext) => Promise<void>> = new Map();

  constructor(
    private config: CoreEngineConfig,
    private monitoring?: MonitoringService,
    private notification?: NotificationService,
    private logger?: Logger
  ) {
    super();
  }

  registerEventHandler(eventType: string, handler: EventHandler): void {
    const handlers = this.eventHandlers.get(eventType) || [];
    handlers.push(handler);
    this.eventHandlers.set(eventType, handlers);
  }

  unregisterEventHandler(eventType: string, handler: EventHandler): void {
    const handlers = this.eventHandlers.get(eventType) || [];
    const index = handlers.indexOf(handler);
    if (index > -1) {
      handlers.splice(index, 1);
      this.eventHandlers.set(eventType, handlers);
    }
  }

  async emitEvent(event: Omit<Event, 'id' | 'timestamp'>): Promise<void> {
    const fullEvent: Event = {
      ...event,
      id: uuidv4(),
      timestamp: new Date().toISOString(),
    };

    this.logger?.debug('Event emitted', { event: fullEvent });
    this.monitoring?.increment('events_emitted', 1, { type: event.type });
    this.emit('event', fullEvent);

    const handlers = this.eventHandlers.get(event.type) || [];
    const context = this.initContext(event.traceId || fullEvent.id);

    for (const handler of handlers) {
      try {
        await handler(fullEvent, context);
      } catch (error) {
        this.logger?.error('Event handler failed', {
          eventType: event.type, error: error instanceof Error ? error.message : 'Unknown error'
        });
        this.monitoring?.increment('event_handler_errors', 1, { type: event.type });
      }
    }
  }

  async executeHandler(
    request: {
      entityId?: string;
      payload: Record<string, unknown>;
      namespace?: string;
      traceId?: string;
    },
    processFn: (payload: Record<string, unknown>, config: Record<string, unknown>) => Promise<unknown>
  ): Promise<ProcessingResult> {
    const traceId = request.traceId || uuidv4();
    const context = this.initContext(traceId);

    this.logger?.info('Executing handler', { traceId, entityId: request.entityId });
    this.monitoring?.increment('handler_executions', 1);

    try {
      const startTime = Date.now();

      this.validateParams(request.payload);

      const config = await this.loadConfig(request.namespace || 'default');
      context.config = config;

      if (request.entityId) {
        const entity = this.entityRegistry.get(request.entityId);
        if (entity) {
          context.entity = entity;
          this.updateEntityStatus(entity.id, 'processing');
        }
      }

      const runInstance = this.createRunInstance(request.entityId || 'system');
      context.metadata.runId = runInstance.run_id;

      try {
        const result = await processFn(request.payload, config);

        await this.persistResult(result);

        await this.emitEvent({
          type: 'task.completed',
          source: 'core-engine',
          data: { result, entityId: request.entityId },
          traceId,
        });

        this.updateRunInstance(runInstance.run_id, 'completed', 1.0);

        if (request.entityId) {
          this.updateEntityStatus(request.entityId, 'completed');
        }

        const latency = Date.now() - startTime;
        this.monitoring?.histogram('handler_latency', latency, { namespace: request.namespace });

        return {
          success: true,
          data: result,
          metrics: { latency, retries: 0 },
        };
      } finally {
        this.cleanupContext(context);
      }
    } catch (error) {
      return this.handleError(error, context, request.entityId);
    }
  }

  private initContext(traceId: string): ProcessingContext {
    return {
      traceId,
      startTime: Date.now(),
      metadata: {},
    };
  }

  private validateParams(params: Record<string, unknown>): void {
    if (!params || typeof params !== 'object') {
      throw new Error('Invalid parameters');
    }
  }

  private async loadConfig(namespace: string): Promise<Record<string, unknown>> {
    return {
      timeout: 30,
      retries: this.config.maxRetries,
      namespace,
    };
  }

  private async persistResult(result: unknown): Promise<void> {
    this.logger?.debug('Result persisted', { result });
  }

  private createRunInstance(entityId: string): RunInstance {
    const run: RunInstance = {
      run_id: uuidv4(),
      entity_id: entityId,
      phase: 'initializing',
      progress: 0,
      started_at: new Date().toISOString(),
      completed_at: null,
      error_detail: null,
    };
    this.runInstances.set(run.run_id, run);
    return run;
  }

  private updateRunInstance(runId: string, phase: RunInstance['phase'], progress: number): void {
    const run = this.runInstances.get(runId);
    if (run) {
      run.phase = phase;
      run.progress = progress;
      if (phase === 'completed' || phase === 'failed') {
        run.completed_at = new Date().toISOString();
      }
      this.runInstances.set(runId, run);
    }
  }

  private updateEntityStatus(entityId: string, status: CoreEntity['status']): void {
    const entity = this.entityRegistry.get(entityId);
    if (entity) {
      entity.status = status;
      entity.updated_at = new Date().toISOString();
      this.entityRegistry.set(entityId, entity);
      this.emit('entity-updated', entity);
    }
  }

  private handleError(error: unknown, context: ProcessingContext, entityId?: string): ProcessingResult {
    const errorMessage = error instanceof Error ? error.message : 'Unknown error';

    this.logger?.error('Handler execution failed', {
      traceId: context.traceId,
      error: errorMessage,
    });

    this.monitoring?.increment('handler_errors', 1);

    if (entityId) {
      this.updateEntityStatus(entityId, 'failed');
    }

    if (context.metadata.runId) {
      const run = this.runInstances.get(context.metadata.runId as string);
      if (run) {
        run.phase = 'failed';
        run.error_detail = errorMessage;
        run.completed_at = new Date().toISOString();
        this.runInstances.set(run.run_id, run);
      }
    }

    if (this.config.enableCompensation) {
      this.rollbackTransaction(context);
    }

    if (error instanceof Error && error.name === 'ValidationError') {
      return { success: false, error: errorMessage };
    }

    return { success: false, error: 'Internal processing error' };
  }

  private rollbackTransaction(context: ProcessingContext): void {
    this.logger?.warn('Rolling back transaction', { traceId: context.traceId });
    this.emit('rollback', context);
  }

  private cleanupContext(context: ProcessingContext): void {
    this.monitoring?.histogram('handler_duration', Date.now() - context.startTime, {});
  }

  registerEntity(entity: Omit<CoreEntity>): CoreEntity {
    this.entityRegistry.set(entity.id, entity);
    this.emit('entity-created', entity);
    return entity;
  }

  getEntity(entityId: string): CoreEntity | undefined {
    return this.entityRegistry.get(entityId);
  }

  listEntities(): CoreEntity[] {
    return Array.from(this.entityRegistry.values());
  }

  createResource(resource: Omit<Resource, 'id' | 'created_at' | 'updated_at' | 'status'>): Resource {
    const now = new Date().toISOString();
    const newResource: Resource = {
      ...resource,
      id: uuidv4(),
      status: 'provisioning',
      created_at: now,
      updated_at: now,
    };
    this.resources.set(newResource.id, newResource);
    this.emit('resource-created', newResource);
    return newResource;
  }

  getResource(resourceId: string): Resource | undefined {
    return this.resources.get(resourceId);
  }

  updateResourceStatus(resourceId: string, status: Resource['status']): Resource | null {
    const resource = this.resources.get(resourceId);
    if (!resource) return null;
    resource.status = status;
    resource.updated_at = new Date().toISOString();
    this.resources.set(resourceId, resource);
    this.emit('resource-updated', resource);
    return resource;
  }

  listResources(): Resource[] {
    return Array.from(this.resources.values());
  }

  getRunInstance(runId: string): RunInstance | undefined {
    return this.runInstances.get(runId);
  }

  listRunInstances(): RunInstance[] {
    return Array.from(this.runInstances.values());
  }

  destroy(): void {
    this.removeAllListeners();
  }
}
