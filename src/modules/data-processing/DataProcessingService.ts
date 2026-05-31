import {
  DataProcessingService,
  DataRecord,
  ProcessingPipeline,
  ProcessingResult,
  ProcessingError,
  DataTransformer,
  DataNormalizer,
  ProcessingRule
} from '../../core/ports';
import { RequestContext, Config, generateId, ContextLogger, ValidationError, eventBus, withRetry } from '../../common';
import { defaultTransformers, defaultNormalizers, defaultValidators } from './transformers/DefaultTransformers';

export interface ProcessingSnapshot {
  recordId: string;
  stage: string;
  data: unknown;
  timestamp: string;
}

export interface ProcessingTransaction {
  recordId: string;
  snapshots: ProcessingSnapshot[];
  currentStage: number;
  committed: boolean;
}

export class DefaultDataProcessingService implements DataProcessingService {
  private transformers: Map<string, DataTransformer> = new Map();
  private normalizers: Map<string, DataNormalizer> = new Map();
  private pipelines: Map<string, ProcessingPipeline> = new Map();
  private configs: Map<string, Config> = new Map();
  private activeTransactions: Map<string, ProcessingTransaction> = new Map();

  constructor() {
    for (const [name, transformer] of Object.entries(defaultTransformers)) {
      this.transformers.set(name, transformer);
    }

    for (const [name, normalizer] of Object.entries(defaultNormalizers)) {
      this.normalizers.set(name, normalizer);
    }
  }

  registerTransformer(type: string, transformer: DataTransformer): void {
    this.transformers.set(type, transformer);
  }

  registerNormalizer(type: string, normalizer: DataNormalizer): void {
    this.normalizers.set(type, normalizer);
  }

  async process(
    ctx: RequestContext,
    records: DataRecord[],
    pipelineId: string
  ): Promise<ProcessingResult> {
    const logger = new ContextLogger(ctx);
    const startTime = Date.now();
    const errors: ProcessingError[] = [];
    const processedRecords: DataRecord[] = [];

    logger.info('Starting data processing', {
      recordCount: records.length,
      pipelineId
    });

    const pipeline = await this.getPipeline(pipelineId);
    if (!pipeline) {
      throw new ValidationError(`Pipeline not found: ${pipelineId}`);
    }

    const config = await this.loadConfig(ctx.namespace);

    for (const record of records) {
      try {
        const processedRecord = await this.processRecord(ctx, record, pipeline, config);
        processedRecords.push(processedRecord);

        eventBus.emit('record.processed', {
          recordId: record.id,
          pipelineId,
          success: true
        });
      } catch (error) {
        const processingError: ProcessingError = {
          recordId: record.id,
          message: (error as Error).message,
          details: { stack: (error as Error).stack }
        };

        errors.push(processingError);

        eventBus.emit('record.failed', {
          recordId: record.id,
          pipelineId,
          error: processingError
        });

        logger.error('Record processing failed', {
          recordId: record.id,
          error: (error as Error).message
        });
      }
    }

    const result: ProcessingResult = {
      success: errors.length === 0,
      records: processedRecords,
      errors,
      startTime,
      endTime: Date.now()
    };

    eventBus.emit('pipeline.completed', {
      pipelineId,
      processedCount: processedRecords.length,
      failedCount: errors.length,
      duration: result.endTime - result.startTime
    });

    logger.info('Data processing completed', {
      processedCount: processedRecords.length,
      failedCount: errors.length,
      duration: result.endTime - result.startTime
    });

    return result;
  }

  private async processRecord(
    ctx: RequestContext,
    record: DataRecord,
    pipeline: ProcessingPipeline,
    config: Config
  ): Promise<DataRecord> {
    const transaction = this.beginTransaction(record.id, record.rawData);
    let currentData = this.deepClone(record.rawData);

    try {
      for (let i = 0; i < pipeline.rules.length; i++) {
        const rule = pipeline.rules[i];
        if (!rule.enabled) {
          continue;
        }

        this.saveSnapshot(transaction, `rule-${i}-${rule.type}`, currentData);

        currentData = await withRetry(
          () => this.applyRule(this.deepClone(currentData), rule),
          {
            maxAttempts: (config.parameters.retryCount as number) || 3,
            initialDelayMs: 100
          }
        );
      }

      this.commitTransaction(transaction);

      return {
        ...record,
        transformedData: currentData
      };
    } catch (error) {
      this.rollbackTransaction(transaction);
      throw error;
    }
  }

  private beginTransaction(recordId: string, initialData: unknown): ProcessingTransaction {
    const transaction: ProcessingTransaction = {
      recordId,
      snapshots: [{
        recordId,
        stage: 'initial',
        data: this.deepClone(initialData),
        timestamp: new Date().toISOString()
      }],
      currentStage: 0,
      committed: false
    };
    this.activeTransactions.set(recordId, transaction);
    return transaction;
  }

  private saveSnapshot(transaction: ProcessingTransaction, stage: string, data: unknown): void {
    transaction.snapshots.push({
      recordId: transaction.recordId,
      stage,
      data: this.deepClone(data),
      timestamp: new Date().toISOString()
    });
    transaction.currentStage++;
  }

  private commitTransaction(transaction: ProcessingTransaction): void {
    transaction.committed = true;
    this.activeTransactions.delete(transaction.recordId);
  }

  private rollbackTransaction(transaction: ProcessingTransaction): void {
    transaction.committed = false;
    this.activeTransactions.delete(transaction.recordId);
  }

  private deepClone<T>(data: T): T {
    if (data === null || data === undefined) {
      return data;
    }
    if (typeof data !== 'object') {
      return data;
    }
    if (Array.isArray(data)) {
      return data.map(item => this.deepClone(item)) as unknown as T;
    }
    const cloned: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(data as Record<string, unknown>)) {
      cloned[key] = this.deepClone(value);
    }
    return cloned as T;
  }

  getActiveTransactionCount(): number {
    return this.activeTransactions.size;
  }

  private async applyRule(data: unknown, rule: ProcessingRule): Promise<unknown> {
    switch (rule.type) {
      case 'transform': {
        const transformerType = rule.config.transformerType as string;
        const transformer = this.transformers.get(transformerType);
        if (!transformer) {
          throw new ValidationError(`Transformer not found: ${transformerType}`);
        }
        return transformer.transform(data, rule.config);
      }

      case 'normalize': {
        const normalizerType = rule.config.normalizerType as string || 'basic';
        const normalizer = this.normalizers.get(normalizerType);
        if (!normalizer) {
          throw new ValidationError(`Normalizer not found: ${normalizerType}`);
        }
        return normalizer.normalize(data, rule.config.schema as Record<string, unknown>);
      }

      case 'filter': {
        const condition = rule.config.condition as (data: unknown) => boolean;
        if (condition && !condition(data)) {
          throw new ValidationError('Record filtered out');
        }
        return data;
      }

      case 'enrich': {
        return data;
      }

      default:
        return data;
    }
  }

  async getPipeline(pipelineId: string): Promise<ProcessingPipeline | null> {
    return this.pipelines.get(pipelineId) || null;
  }

  async createPipeline(pipeline: Omit<ProcessingPipeline, 'id' | 'version'>): Promise<string> {
    const id = generateId('pipeline');
    const newPipeline: ProcessingPipeline = {
      ...pipeline,
      id,
      version: 1
    };
    this.pipelines.set(id, newPipeline);
    return id;
  }

  async updatePipeline(pipelineId: string, updates: Partial<ProcessingPipeline>): Promise<ProcessingPipeline | null> {
    const existing = this.pipelines.get(pipelineId);
    if (!existing) {
      return null;
    }

    const updated: ProcessingPipeline = {
      ...existing,
      ...updates,
      id: pipelineId,
      version: existing.version + 1
    };

    this.pipelines.set(pipelineId, updated);
    return updated;
  }

  async deletePipeline(pipelineId: string): Promise<boolean> {
    return this.pipelines.delete(pipelineId);
  }

  async listPipelines(): Promise<ProcessingPipeline[]> {
    return Array.from(this.pipelines.values());
  }

  private async loadConfig(namespace: string): Promise<Config> {
    const key = `config:${namespace}`;
    let config = this.configs.get(key);

    if (!config) {
      config = {
        config_id: generateId('config'),
        namespace,
        version: 1,
        parameters: {
          timeout: 30000,
          retryCount: 3,
          maxBatchSize: 100
        },
        enabled: true,
        applied_at: new Date().toISOString()
      };
      this.configs.set(key, config);
    }

    return config;
  }

  async setConfig(namespace: string, parameters: Record<string, unknown>): Promise<Config> {
    const key = `config:${namespace}`;
    const existing = this.configs.get(key);

    const config: Config = {
      config_id: existing?.config_id || generateId('config'),
      namespace,
      version: (existing?.version || 0) + 1,
      parameters: {
        ...existing?.parameters,
        ...parameters
      },
      enabled: true,
      applied_at: new Date().toISOString()
    };

    this.configs.set(key, config);
    return config;
  }

  async validateRecords(records: DataRecord[], schema: Record<string, unknown>): Promise<{
    valid: DataRecord[];
    invalid: Array<{ record: DataRecord; errors: string[] }>;
  }> {
    const validator = defaultValidators.schema;
    const valid: DataRecord[] = [];
    const invalid: Array<{ record: DataRecord; errors: string[] }> = [];

    for (const record of records) {
      const result = await validator.validate(record.rawData, schema);
      if (result.valid) {
        valid.push(record);
      } else {
        invalid.push({ record, errors: result.errors });
      }
    }

    return { valid, invalid };
  }
}
