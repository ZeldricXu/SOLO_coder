import { RequestContext } from '../../common/types';

export type DataSourceType = 'json' | 'csv' | 'xml' | 'protobuf' | 'avro' | 'custom';

export type ProcessingStatus = 'idle' | 'validating' | 'transforming' | 'normalizing' | 'completed' | 'failed';

export interface DataRecord {
  id: string;
  sourceType: DataSourceType;
  rawData: unknown;
  transformedData?: unknown;
  metadata: Record<string, unknown>;
  created_at: string;
}

export interface ProcessingRule {
  id: string;
  name: string;
  type: 'transform' | 'normalize' | 'filter' | 'enrich';
  config: Record<string, unknown>;
  enabled: boolean;
}

export interface ProcessingPipeline {
  id: string;
  name: string;
  rules: ProcessingRule[];
  version: number;
}

export interface ProcessingResult {
  success: boolean;
  records: DataRecord[];
  errors: ProcessingError[];
  startTime: number;
  endTime: number;
}

export interface ProcessingError {
  recordId: string;
  ruleId?: string;
  message: string;
  details?: Record<string, unknown>;
}

export interface DataTransformer {
  transform(data: unknown, config: Record<string, unknown>): Promise<unknown>;
}

export interface DataNormalizer {
  normalize(data: unknown, schema: Record<string, unknown>): Promise<unknown>;
}

export interface DataValidator {
  validate(data: unknown, schema: Record<string, unknown>): Promise<{
    valid: boolean;
    errors: string[];
  }>;
}

export interface DataProcessingService {
  process(
    ctx: RequestContext,
    records: DataRecord[],
    pipelineId: string
  ): Promise<ProcessingResult>;

  registerTransformer(type: string, transformer: DataTransformer): void;

  registerNormalizer(type: string, normalizer: DataNormalizer): void;

  getPipeline(pipelineId: string): Promise<ProcessingPipeline | null>;

  createPipeline(pipeline: Omit<ProcessingPipeline, 'id' | 'version'>): Promise<string>;
}
