import { CoreEntity, ConfigDefinition, RunInstance, APIResponse } from '@apptypes/index';
import { rootLogger } from '@modules/logging';
import { generateId, nowISO, generateTraceId } from '@utils/index';
import { eventBus } from './event-bus';
import { responseBuilder } from './response-builder';
import { z } from 'zod';

const MAX_PAYLOAD_SIZE = 10 * 1024 * 1024;

const ResourceCreateSchema = z.object({
  type: z.string().min(1),
  config: z.record(z.unknown()).default({}),
  labels: z.record(z.string()).default({}),
});

const BatchOperationSchema = z.object({
  operations: z.array(
    z.object({
      action: z.enum(['restart', 'stop', 'delete', 'update']),
      id: z.string(),
      params: z.record(z.unknown()).optional(),
    })
  ).min(1),
});

export class CoreHandler {
  private logger = rootLogger.child({ module: 'CoreHandler' });
  private entities: Map<string, CoreEntity> = new Map();
  private configs: Map<string, ConfigDefinition> = new Map();
  private runs: Map<string, RunInstance> = new Map();

  private validateParams(params: Record<string, unknown>): void {
    if (!params || typeof params !== 'object') {
      throw new Error('Invalid parameters');
    }
  }

  private loadConfig(namespace: string): ConfigDefinition {
    const config = Array.from(this.configs.values()).find((c) => c.namespace === namespace && c.enabled);
    if (!config) {
      throw new Error(`Config not found for namespace: ${namespace}`);
    }
    return config;
  }

  private persistResult(entity: CoreEntity): void {
    this.entities.set(entity.id, entity);
  }

  private emitEvent(eventName: string, data: Record<string, unknown>): void {
    eventBus.emit(eventName, {
      ...data,
      timestamp: nowISO(),
    });
  }

  async executeHandler(
    request: {
      traceId?: string;
      namespace: string;
      params: Record<string, unknown>;
      payload: Record<string, unknown>;
    }
  ): Promise<APIResponse> {
    const traceId = request.traceId || generateTraceId();
    const ctx = {
      traceId,
      startTime: Date.now(),
      attributes: {} as Record<string, unknown>,
    };

    this.logger.info('Handler execution started', {
      trace_id: traceId,
      namespace: request.namespace,
    });

    try {
      this.validateParams(request.params);

      const config = this.loadConfig(request.namespace);

      const result = await this.processCore(request.payload, config);

      this.persistResult(result);

      this.emitEvent('task.completed', {
        entity_id: result.id,
        status: result.status,
        trace_id: traceId,
      });

      this.logger.info('Handler execution completed', {
        trace_id: traceId,
        duration_ms: Date.now() - ctx.startTime,
        entity_id: result.id,
      });

      return responseBuilder.created(result);
    } catch (error) {
      if (error instanceof z.ZodError) {
        return responseBuilder.error(422, 'Validation failed');
      }

      if ((error as Error).message === 'Validation failed') {
        return responseBuilder.error(422, (error as Error).message);
      }

      if ((error as Error).message.includes('timeout')) {
        return responseBuilder.timeout('上游服务响应超时');
      }

      if ((error as Error).message.includes('conflict')) {
        const match = (error as Error).message.match(/conflict: (.+)/);
        return responseBuilder.conflict(match ? match[1] : 'unknown');
      }

      this.logger.error('Handler execution failed', {
        trace_id: traceId,
        error: (error as Error).message,
        stack: (error as Error).stack,
      });

      return responseBuilder.internalError('内部处理错误');
    } finally {
      this.emitEvent('metrics.record', {
        trace_id: traceId,
        duration_ms: Date.now() - ctx.startTime,
        timestamp: nowISO(),
      });
    }
  }

  private async processCore(
    payload: Record<string, unknown>,
    config: ConfigDefinition
  ): Promise<CoreEntity> {
    const payloadSize = JSON.stringify(payload).length;
    if (payloadSize > MAX_PAYLOAD_SIZE) {
      throw new Error(`Payload too large: ${payloadSize} bytes, max: ${MAX_PAYLOAD_SIZE}`);
    }

    const rules = config.parameters.rules as Record<string, unknown> | undefined;
    const poolSize = (config.parameters.poolSize as number) || 10;

    await new Promise((resolve) => setTimeout(resolve, 10));

    const entity: CoreEntity = {
      id: generateId('ent_'),
      type: (payload.type as string) || 'task',
      status: 'completed',
      attributes: {
        ...payload,
        config_version: config.version,
        pool_size_used: poolSize,
        rules_applied: rules || {},
      },
      created_at: nowISO(),
      updated_at: nowISO(),
    };

    return entity;
  }

  async createResource(
    request: {
      type: string;
      config: Record<string, unknown>;
      labels: Record<string, string>;
    },
    principal?: { user_id: string }
  ): Promise<APIResponse> {
    const validated = ResourceCreateSchema.parse(request);

    const resourceId = generateId('rsc_');

    const entity: CoreEntity = {
      id: resourceId,
      type: validated.type,
      status: 'provisioning',
      attributes: {
        ...validated.config,
        labels: validated.labels,
        created_by: principal?.user_id,
      },
      created_at: nowISO(),
      updated_at: nowISO(),
    };

    this.entities.set(resourceId, entity);

    this.emitEvent('resource.created', {
      resource_id: resourceId,
      type: validated.type,
    });

    return responseBuilder.created({
      id: resourceId,
      status: 'provisioning',
    });
  }

  async getResourceStatus(id: string): Promise<APIResponse> {
    const entity = this.entities.get(id);
    if (!entity) {
      return responseBuilder.notFound(`Resource not found: ${id}`);
    }

    const run = Array.from(this.runs.values()).find((r) => r.entity_id === id && !r.completed_at);

    return responseBuilder.success({
      id: entity.id,
      status: entity.status,
      progress: run?.progress ?? 1.0,
      type: entity.type,
      attributes: entity.attributes,
      created_at: entity.created_at,
      updated_at: entity.updated_at,
    });
  }

  async batchOperation(request: {
    operations: Array<{
      action: string;
      id: string;
      params?: Record<string, unknown>;
    }>;
  }): Promise<APIResponse> {
    const validated = BatchOperationSchema.parse(request);

    const batchId = generateId('batch_');
    const results: Array<{
      id: string;
      action: string;
      success: boolean;
      error?: string;
    }> = [];

    for (const op of validated.operations) {
      try {
        const entity = this.entities.get(op.id);
        if (!entity) {
          results.push({
            id: op.id,
            action: op.action,
            success: false,
            error: 'Resource not found',
          });
          continue;
        }

        switch (op.action) {
          case 'restart':
            entity.status = 'restarting';
            entity.updated_at = nowISO();
            break;
          case 'stop':
            entity.status = 'stopped';
            entity.updated_at = nowISO();
            break;
          case 'delete':
            this.entities.delete(op.id);
            break;
          case 'update':
            if (op.params) {
              entity.attributes = { ...entity.attributes, ...op.params };
              entity.updated_at = nowISO();
            }
            break;
        }

        results.push({
          id: op.id,
          action: op.action,
          success: true,
        });

        this.emitEvent(`resource.${op.action}`, {
          resource_id: op.id,
          batch_id: batchId,
        });
      } catch (error) {
        results.push({
          id: op.id,
          action: op.action,
          success: false,
          error: (error as Error).message,
        });
      }
    }

    this.emitEvent('batch.completed', {
      batch_id: batchId,
      total: validated.operations.length,
      success: results.filter((r) => r.success).length,
    });

    return responseBuilder.success({
      batch_id: batchId,
      results,
    });
  }

  createRun(entityId: string, phase: string): RunInstance {
    const run: RunInstance = {
      run_id: generateId('run_'),
      entity_id: entityId,
      phase,
      progress: 0.0,
      started_at: nowISO(),
      completed_at: null,
      error_detail: null,
    };
    this.runs.set(run.run_id, run);
    return run;
  }

  updateProgress(runId: string, progress: number, phase?: string): boolean {
    const run = this.runs.get(runId);
    if (!run) return false;

    run.progress = Math.min(1.0, Math.max(0.0, progress));
    if (phase) run.phase = phase;

    if (run.progress >= 1.0 && !run.completed_at) {
      run.completed_at = nowISO();
      run.phase = 'finalizing';
    }

    return true;
  }

  getRun(runId: string): RunInstance | null {
    return this.runs.get(runId) || null;
  }

  saveConfig(config: Omit<ConfigDefinition, 'applied_at'>): ConfigDefinition {
    const newConfig: ConfigDefinition = {
      ...config,
      applied_at: nowISO(),
    };
    this.configs.set(config.config_id, newConfig);

    this.emitEvent('config.applied', {
      config_id: config.config_id,
      namespace: config.namespace,
      version: config.version,
    });

    return newConfig;
  }

  getConfig(configId: string): ConfigDefinition | null {
    return this.configs.get(configId) || null;
  }

  listConfigs(namespace?: string): ConfigDefinition[] {
    const configs = Array.from(this.configs.values());
    if (namespace) {
      return configs.filter((c) => c.namespace === namespace);
    }
    return configs;
  }

  listResources(
    options: {
      type?: string;
      status?: string;
      page?: number;
      page_size?: number;
    } = {}
  ): APIResponse<CoreEntity[]> {
    let entities = Array.from(this.entities.values());

    if (options.type) {
      entities = entities.filter((e) => e.type === options.type);
    }
    if (options.status) {
      entities = entities.filter((e) => e.status === options.status);
    }

    const page = options.page || 1;
    const pageSize = options.page_size || 20;
    const total = entities.length;
    const totalPages = Math.ceil(total / pageSize);
    const start = (page - 1) * pageSize;
    const paginated = entities.slice(start, start + pageSize);

    return responseBuilder.success(paginated, {
      page,
      page_size: pageSize,
      total,
      total_pages: totalPages,
    });
  }
}

export const coreHandler = new CoreHandler();
