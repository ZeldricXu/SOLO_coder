"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.pipelineListRequestSchema = exports.pipelineInferenceRequestSchema = exports.pipelineCreateRequestSchema = exports.pipelineEdgeSchema = exports.pipelineStepSchema = exports.stepTransformConfigSchema = exports.stepAggregatorConfigSchema = exports.stepConditionSchema = exports.inputOutputMappingSchema = exports.fieldMappingSchema = void 0;
const zod_1 = require("zod");
exports.fieldMappingSchema = zod_1.z.object({
    source: zod_1.z.string().min(1),
    target: zod_1.z.string().min(1),
    transform: zod_1.z.string().optional(),
    defaultValue: zod_1.z.any().optional(),
});
exports.inputOutputMappingSchema = zod_1.z.object({
    type: zod_1.z.enum(['direct', 'mapped', 'custom']).default('direct'),
    mappings: zod_1.z.array(exports.fieldMappingSchema).default([]),
});
exports.stepConditionSchema = zod_1.z.object({
    type: zod_1.z.enum(['field_equals', 'field_greater', 'field_less', 'field_contains', 'custom']),
    field: zod_1.z.string().min(1),
    value: zod_1.z.any().optional(),
    expression: zod_1.z.string().optional(),
    trueStepId: zod_1.z.string().optional(),
    falseStepId: zod_1.z.string().optional(),
});
exports.stepAggregatorConfigSchema = zod_1.z.object({
    sourceSteps: zod_1.z.array(zod_1.z.string()).min(1),
    operation: zod_1.z.enum(['concat', 'sum', 'average', 'max', 'min']).optional(),
    fields: zod_1.z.array(zod_1.z.string()).optional(),
    separator: zod_1.z.string().optional(),
    target: zod_1.z.string().optional(),
});
exports.stepTransformConfigSchema = zod_1.z.object({
    type: zod_1.z.enum(['scale', 'normalize', 'one_hot', 'bucketize', 'custom']),
    scale: zod_1.z.object({ factor: zod_1.z.number(), offset: zod_1.z.number() }).optional(),
    normalize: zod_1.z.object({
        method: zod_1.z.enum(['min_max', 'z_score']),
        min: zod_1.z.number().optional(),
        max: zod_1.z.number().optional(),
        mean: zod_1.z.number().optional(),
        std: zod_1.z.number().optional(),
    }).optional(),
    oneHot: zod_1.z.object({
        categories: zod_1.z.array(zod_1.z.string()),
        dropFirst: zod_1.z.boolean().default(false),
    }).optional(),
    bucketize: zod_1.z.object({
        boundaries: zod_1.z.array(zod_1.z.number()),
        labels: zod_1.z.array(zod_1.z.string()).optional(),
    }).optional(),
    expression: zod_1.z.string().optional(),
});
exports.pipelineStepSchema = zod_1.z.object({
    id: zod_1.z.string().optional(),
    name: zod_1.z.string().min(1).max(100),
    type: zod_1.z.enum(['model', 'transform', 'condition', 'aggregator']).default('model'),
    description: zod_1.z.string().max(500).optional(),
    modelId: zod_1.z.string().optional(),
    version: zod_1.z.string().optional(),
    inputMapping: exports.inputOutputMappingSchema.default({ type: 'direct', mappings: [] }),
    outputMapping: exports.inputOutputMappingSchema.default({ type: 'direct', mappings: [] }),
    condition: exports.stepConditionSchema.optional(),
    aggregatorConfig: exports.stepAggregatorConfigSchema.optional(),
    transformConfig: exports.stepTransformConfigSchema.optional(),
    dependsOn: zod_1.z.array(zod_1.z.string()).default([]),
    timeoutMs: zod_1.z.number().int().min(100).max(60000).default(30000),
    retryCount: zod_1.z.number().int().min(0).max(5).default(0),
    enabled: zod_1.z.boolean().default(true),
});
exports.pipelineEdgeSchema = zod_1.z.object({
    fromStepId: zod_1.z.string().min(1),
    toStepId: zod_1.z.string().min(1),
    condition: zod_1.z.string().optional(),
});
exports.pipelineCreateRequestSchema = zod_1.z.object({
    name: zod_1.z.string().min(1).max(100),
    description: zod_1.z.string().max(1000).optional(),
    projectId: zod_1.z.string().min(1),
    ownerId: zod_1.z.string().min(1),
    team: zod_1.z.string().optional(),
    entryPoint: zod_1.z.string().min(1),
    outputStep: zod_1.z.string().min(1),
    steps: zod_1.z.array(exports.pipelineStepSchema).min(1).max(20),
    edges: zod_1.z.array(exports.pipelineEdgeSchema).default([]),
    inputSchema: zod_1.z.record(zod_1.z.string(), zod_1.z.any()).optional(),
    outputSchema: zod_1.z.record(zod_1.z.string(), zod_1.z.any()).optional(),
    tags: zod_1.z.array(zod_1.z.string()).max(20).optional(),
    metadata: zod_1.z.record(zod_1.z.string(), zod_1.z.any()).optional(),
});
exports.pipelineInferenceRequestSchema = zod_1.z.object({
    pipelineId: zod_1.z.string().min(1),
    pipelineVersion: zod_1.z.number().int().min(1).optional(),
    inputs: zod_1.z.record(zod_1.z.string(), zod_1.z.any()),
    requestId: zod_1.z.string().optional(),
    userId: zod_1.z.string().optional(),
    sessionId: zod_1.z.string().optional(),
    context: zod_1.z.record(zod_1.z.string(), zod_1.z.any()).optional(),
    includeStepOutputs: zod_1.z.boolean().default(false),
    bypassCache: zod_1.z.boolean().default(false),
});
exports.pipelineListRequestSchema = zod_1.z.object({
    page: zod_1.z.number().int().min(1).default(1),
    pageSize: zod_1.z.number().int().min(1).max(100).default(20),
    name: zod_1.z.string().optional(),
    projectId: zod_1.z.string().optional(),
    ownerId: zod_1.z.string().optional(),
    team: zod_1.z.string().optional(),
    status: zod_1.z.enum(['draft', 'active', 'archived']).optional(),
    tags: zod_1.z.array(zod_1.z.string()).optional(),
});
