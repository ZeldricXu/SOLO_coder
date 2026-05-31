import { v4 as uuidv4 } from 'uuid';
import { Entity, RunInstance, MetricSnapshot } from '../types';

export interface ProcessingContext {
  traceId: string;
  startTime: number;
  attributes: Record<string, unknown>;
  phase: string;
}

export interface ProcessingResult<T = unknown> {
  success: boolean;
  data?: T;
  error?: string;
  errorCode?: string;
  retryable?: boolean;
}

export interface PipelineStage<I, O> {
  name: string;
  process: (input: I, context: ProcessingContext) => Promise<O>;
}

export class ProcessingPipeline<I, O> {
  private stages: PipelineStage<unknown, unknown>[] = [];

  addStage<A, B>(stage: PipelineStage<A, B>): ProcessingPipeline<I, B> {
    this.stages.push(stage as unknown as PipelineStage<unknown, unknown>);
    return this as unknown as ProcessingPipeline<I, B>;
  }

  async execute(input: I, traceId?: string): Promise<ProcessingResult<O>> {
    const context: ProcessingContext = {
      traceId: traceId || uuidv4(),
      startTime: Date.now(),
      attributes: {},
      phase: 'initializing',
    };

    try {
      let current: unknown = input;

      for (const stage of this.stages) {
        context.phase = stage.name;
        current = await stage.process(current, context);
      }

      return {
        success: true,
        data: current as O,
      };
    } catch (error) {
      const err = error as Error;
      return {
        success: false,
        error: err.message,
        errorCode: (err as { code?: string }).code || 'UNKNOWN_ERROR',
        retryable: (err as { retryable?: boolean }).retryable ?? false,
      };
    }
  }
}

export class DataTransformer {
  static normalize<T extends Record<string, unknown>>(data: T, schema: Record<string, string>): T {
    const result = { ...data };
    for (const [key, type] of Object.entries(schema)) {
      if (result[key] !== undefined) {
        result[key] = this.coerceType(result[key], type);
      }
    }
    return result;
  }

  static coerceType(value: unknown, type: string): unknown {
    switch (type) {
      case 'string':
        return String(value);
      case 'number':
        return Number(value);
      case 'boolean':
        return Boolean(value);
      case 'integer':
        return Math.floor(Number(value));
      case 'timestamp':
        return typeof value === 'string' ? new Date(value).toISOString() : new Date(Number(value)).toISOString();
      default:
        return value;
    }
  }

  static flatten(obj: Record<string, unknown>, prefix = ''): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(obj)) {
      const newKey = prefix ? `${prefix}.${key}` : key;
      if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
        Object.assign(result, this.flatten(value as Record<string, unknown>, newKey));
      } else {
        result[newKey] = value;
      }
    }
    return result;
  }

  static unflatten(obj: Record<string, unknown>): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(obj)) {
      const keys = key.split('.');
      let current = result;
      for (let i = 0; i < keys.length - 1; i++) {
        if (!(keys[i] in current)) {
          current[keys[i]] = {};
        }
        current = current[keys[i]] as Record<string, unknown>;
      }
      current[keys[keys.length - 1]] = value;
    }
    return result;
  }
}

export class EntityNormalizer {
  static normalize(entity: Partial<Entity>): Entity {
    const now = new Date().toISOString();
    return {
      id: entity.id || uuidv4(),
      type: entity.type || 'unknown',
      status: entity.status || 'pending',
      attributes: entity.attributes || {},
      created_at: entity.created_at || now,
      updated_at: entity.updated_at || now,
    };
  }

  static toSnapshot(entity: Entity, dimensions: Record<string, string>): MetricSnapshot {
    const metrics: Record<string, number> = {};
    for (const [key, value] of Object.entries(entity.attributes)) {
      if (typeof value === 'number') {
        metrics[key] = value;
      }
    }
    return {
      snapshot_id: uuidv4(),
      timestamp: new Date().toISOString(),
      metrics,
      dimensions,
    };
  }
}

export class RunInstanceManager {
  private instances: Map<string, RunInstance> = new Map();

  create(entityId: string): RunInstance {
    const instance: RunInstance = {
      run_id: uuidv4(),
      entity_id: entityId,
      phase: 'initializing',
      progress: 0,
      started_at: new Date().toISOString(),
      completed_at: null,
      error_detail: null,
    };
    this.instances.set(instance.run_id, instance);
    return instance;
  }

  updateProgress(runId: string, phase: string, progress: number): void {
    const instance = this.instances.get(runId);
    if (instance) {
      instance.phase = phase;
      instance.progress = progress;
    }
  }

  complete(runId: string, error?: string): void {
    const instance = this.instances.get(runId);
    if (instance) {
      instance.completed_at = new Date().toISOString();
      instance.phase = error ? 'failed' : 'completed';
      instance.progress = 1;
      instance.error_detail = error || null;
    }
  }

  get(runId: string): RunInstance | undefined {
    return this.instances.get(runId);
  }

  list(): RunInstance[] {
    return Array.from(this.instances.values());
  }
}

export interface RetryOptions {
  maxRetries: number;
  initialDelay: number;
  maxDelay: number;
  backoffMultiplier: number;
}

export class RetryHandler {
  static async withRetry<T>(
    fn: () => Promise<T>,
    options: Partial<RetryOptions> = {}
  ): Promise<T> {
    const config: RetryOptions = {
      maxRetries: 3,
      initialDelay: 100,
      maxDelay: 5000,
      backoffMultiplier: 2,
      ...options,
    };

    let lastError: Error;
    let delay = config.initialDelay;

    for (let attempt = 0; attempt <= config.maxRetries; attempt++) {
      try {
        return await fn();
      } catch (error) {
        lastError = error as Error;
        if ((error as { retryable?: boolean }).retryable === false || attempt === config.maxRetries) {
          throw lastError;
        }
        await this.sleep(delay);
        delay = Math.min(delay * config.backoffMultiplier, config.maxDelay);
      }
    }

    throw lastError!;
  }

  private static sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}

export class ProcessingError extends Error {
  public readonly code: string;
  public readonly retryable: boolean;
  public readonly details: Record<string, unknown>;

  constructor(
    message: string,
    code: string = 'PROCESSING_ERROR',
    retryable: boolean = false,
    details: Record<string, unknown> = {}
  ) {
    super(message);
    this.name = 'ProcessingError';
    this.code = code;
    this.retryable = retryable;
    this.details = details;
  }
}
