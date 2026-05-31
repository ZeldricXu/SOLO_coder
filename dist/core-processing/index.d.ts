import { z } from 'zod';
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
    errors: Array<{
        stage: string;
        message: string;
        details?: unknown;
    }>;
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
export declare class DataStandardizer {
    private schemas;
    registerSchema(schema: DataSchema): void;
    getSchema(name: string, version: string): DataSchema | undefined;
    standardize(input: Record<string, unknown>, schemaName: string, schemaVersion: string): Promise<Record<string, unknown>>;
    private convertType;
    validateWithZod<T>(input: unknown, schema: z.ZodSchema<T>): T;
}
export declare class PipelineProcessor {
    private pipelines;
    private transformers;
    registerTransformer(transformer: DataTransformer): void;
    registerPipeline(pipeline: ProcessingPipeline): void;
    getTransformer(name: string, version: string): DataTransformer | undefined;
    getPipeline(pipelineId: string): ProcessingPipeline | undefined;
    executePipeline(pipelineId: string, input: unknown, context: ProcessingContext): Promise<ProcessingResult>;
    private withTimeout;
    executeHandler<T>(handler: (request: unknown, context: ProcessingContext) => Promise<HandlerResult<T>>, request: unknown, context: ProcessingContext): Promise<HandlerResult<T>>;
    listPipelines(): ProcessingPipeline[];
    listTransformers(): DataTransformer[];
}
export declare const createProcessingContext: (traceId?: string, namespace?: string) => ProcessingContext;
//# sourceMappingURL=index.d.ts.map