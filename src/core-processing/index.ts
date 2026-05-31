import { z } from 'zod';
import logger from '../common/logger';
import { ValidationError } from '../common/errors';
import { ProcessingContext, HandlerResult } from '../types';

export interface DataTransformer {
  name: string;
  version: string;
  transform: (input: unknown, context: ProcessingContext) => Promise<unknown>;
  validate?: (input: unknown) => boolean;
}

export interface PipelineStage {
  id: string;
  name: string;
  transformer: DataTransformer;
  config: Record<string, unknown>;
  skipOnError?: boolean;
  timeoutMs?: number;
}

export interface ProcessingPipeline {
  id: string;
  name: string;
  stages: PipelineStage[];
  version: string;
  createdAt: string;
  updatedAt: string;
}

export interface ProcessingResult<T = unknown> {
  success: boolean;
  data?: T;
  errors: Array<{ stage: string; message: string; details?: unknown }>;
  stageResults: Map<string, unknown>;
  totalTimeMs: number;
}

export interface StandardizationRule {
  field: string;
  type: 'string' | 'number' | 'boolean' | 'date' | 'object' | 'array';
  required?: boolean;
  defaultValue?: unknown;
  transform?: (value: unknown) => unknown;
  validators?: Array<(value: unknown) => boolean>;
}

export interface DataSchema {
  schemaId: string;
  name: string;
  version: string;
  rules: StandardizationRule[];
}

export class DataStandardizer {
  private schemas: Map<string, DataSchema> = new Map();

  registerSchema(schema: DataSchema): void {
    const key = `${schema.name}:v${schema.version}`;
    this.schemas.set(key, schema);
    logger.info({ schemaName: schema.name, version: schema.version }, '注册数据标准化Schema');
  }

  getSchema(name: string, version: string): DataSchema | undefined {
    return this.schemas.get(`${name}:v${version}`);
  }

  async standardize(input: Record<string, unknown>, schemaName: string, schemaVersion: string): Promise<Record<string, unknown>> {
    const schema = this.getSchema(schemaName, schemaVersion);
    if (!schema) {
      throw new ValidationError(`Schema未找到: ${schemaName}:v${schemaVersion}`);
    }

    const output: Record<string, unknown> = {};
    const errors: string[] = [];

    for (const rule of schema.rules) {
      let value = input[rule.field];

      if (value === undefined || value === null) {
        if (rule.required) {
          errors.push(`必填字段缺失: ${rule.field}`);
          continue;
        }
        if (rule.defaultValue !== undefined) {
          value = rule.defaultValue;
        } else {
          continue;
        }
      }

      try {
        value = this.convertType(value, rule.type);
        if (rule.transform) {
          value = rule.transform(value);
        }
        if (rule.validators) {
          for (const validator of rule.validators) {
            if (!validator(value)) {
              errors.push(`字段验证失败: ${rule.field}`);
            }
          }
        }
        output[rule.field] = value;
      } catch (error) {
        errors.push(`字段转换失败: ${rule.field}, ${error instanceof Error ? error.message : String(error)}`);
      }
    }

    if (errors.length > 0) {
      throw new ValidationError('数据标准化失败', errors);
    }

    logger.debug({ schemaName, schemaVersion, fields: Object.keys(output) }, '数据标准化完成');
    return output;
  }

  private convertType(value: unknown, targetType: StandardizationRule['type']): unknown {
    switch (targetType) {
      case 'string':
        return String(value);
      case 'number': {
        const num = Number(value);
        if (isNaN(num)) throw new Error(`无法转换为数字: ${value}`);
        return num;
      }
      case 'boolean':
        return Boolean(value);
      case 'date': {
        const date = new Date(String(value));
        if (isNaN(date.getTime())) throw new Error(`无效日期: ${value}`);
        return date.toISOString();
      }
      case 'object':
        if (typeof value !== 'object' || value === null || Array.isArray(value)) {
          throw new Error('不是有效的对象');
        }
        return value;
      case 'array':
        if (!Array.isArray(value)) {
          throw new Error('不是有效的数组');
        }
        return value;
      default:
        return value;
    }
  }

  validateWithZod<T>(input: unknown, schema: z.ZodSchema<T>): T {
    const result = schema.safeParse(input);
    if (!result.success) {
      throw new ValidationError('数据验证失败', result.error.errors);
    }
    return result.data;
  }
}

export class PipelineProcessor {
  private pipelines: Map<string, ProcessingPipeline> = new Map();
  private transformers: Map<string, DataTransformer> = new Map();

  registerTransformer(transformer: DataTransformer): void {
    const key = `${transformer.name}:v${transformer.version}`;
    this.transformers.set(key, transformer);
    logger.info({ name: transformer.name, version: transformer.version }, '注册数据转换器');
  }

  registerPipeline(pipeline: ProcessingPipeline): void {
    this.pipelines.set(pipeline.id, pipeline);
    logger.info({ pipelineId: pipeline.id, name: pipeline.name, stages: pipeline.stages.length }, '注册处理流水线');
  }

  getTransformer(name: string, version: string): DataTransformer | undefined {
    return this.transformers.get(`${name}:v${version}`);
  }

  getPipeline(pipelineId: string): ProcessingPipeline | undefined {
    return this.pipelines.get(pipelineId);
  }

  async executePipeline(
    pipelineId: string,
    input: unknown,
    context: ProcessingContext
  ): Promise<ProcessingResult> {
    const pipeline = this.pipelines.get(pipelineId);
    if (!pipeline) {
      throw new ValidationError(`流水线未找到: ${pipelineId}`);
    }

    const startTime = Date.now();
    const stageResults = new Map<string, unknown>();
    const errors: Array<{ stage: string; message: string; details?: unknown }> = [];
    let currentData = input;
    let success = true;

    logger.info({ pipelineId, traceId: context.traceId }, '开始执行流水线');

    for (const stage of pipeline.stages) {
      const stageStart = Date.now();
      try {
        logger.debug({ stageId: stage.id, stageName: stage.name }, '执行流水线阶段');

        const transformer = this.transformers.get(`${stage.transformer.name}:v${stage.transformer.version}`);
        if (!transformer) {
          throw new Error(`转换器未找到: ${stage.transformer.name}:v${stage.transformer.version}`);
        }

        if (transformer.validate && !transformer.validate(currentData)) {
          throw new Error('输入数据验证失败');
        }

        const timeoutMs = stage.timeoutMs ?? 30000;
        const result = await this.withTimeout(
          transformer.transform(currentData, { ...context, metadata: { ...context.metadata, stageConfig: stage.config } }),
          timeoutMs,
          `阶段超时: ${stage.name}`
        );

        stageResults.set(stage.id, result);
        currentData = result;

        const stageTime = Date.now() - stageStart;
        logger.debug({ stageId: stage.id, stageName: stage.name, timeMs: stageTime }, '阶段执行完成');
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : String(error);
        errors.push({ stage: stage.name, message: errorMessage, details: error });
        logger.error({ stageId: stage.id, stageName: stage.name, error: errorMessage }, '阶段执行失败');

        if (!stage.skipOnError) {
          success = false;
          break;
        }
      }
    }

    const totalTimeMs = Date.now() - startTime;
    logger.info({ pipelineId, traceId: context.traceId, success, totalTimeMs, errorCount: errors.length }, '流水线执行完成');

    return {
      success,
      data: success ? currentData : undefined,
      errors,
      stageResults,
      totalTimeMs
    };
  }

  private async withTimeout<T>(promise: Promise<T>, timeoutMs: number, errorMessage: string): Promise<T> {
    return Promise.race([
      promise,
      new Promise<T>((_, reject) => setTimeout(() => reject(new Error(errorMessage)), timeoutMs))
    ]);
  }

  async executeHandler<T>(
    handler: (request: unknown, context: ProcessingContext) => Promise<HandlerResult<T>>,
    request: unknown,
    context: ProcessingContext
  ): Promise<HandlerResult<T>> {
    try {
      logger.info({ traceId: context.traceId }, '开始处理请求');
      const result = await handler(request, context);
      logger.info({ traceId: context.traceId, success: result.success }, '请求处理完成');
      return result;
    } catch (error) {
      logger.error({ traceId: context.traceId, error }, '请求处理异常');
      return {
        success: false,
        error: {
          code: 500,
          message: error instanceof Error ? error.message : '内部处理错误',
          details: error
        }
      };
    }
  }

  listPipelines(): ProcessingPipeline[] {
    return Array.from(this.pipelines.values());
  }

  listTransformers(): DataTransformer[] {
    return Array.from(this.transformers.values());
  }
}

export const createProcessingContext = (traceId?: string, namespace: string = 'default'): ProcessingContext => ({
  traceId: traceId ?? Math.random().toString(36).substring(2, 15),
  startTime: Date.now(),
  namespace,
  metadata: {}
});
