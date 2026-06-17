"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.modelVersionListRequestSchema = exports.modelListRequestSchema = exports.modelVersionCreateRequestSchema = exports.modelCreateRequestSchema = exports.modelDataSchemaSchema = exports.modelInputOutputSchema = exports.statusSchema = exports.modelFormatSchema = void 0;
const zod_1 = require("zod");
exports.modelFormatSchema = zod_1.z.enum(['pkl', 'onnx', 'pt', 'joblib', 'h5', 'pb', 'custom']);
exports.statusSchema = zod_1.z.enum(['active', 'inactive', 'archived', 'deleted']);
exports.modelInputOutputSchema = zod_1.z.object({
    name: zod_1.z.string().min(1),
    type: zod_1.z.enum(['float32', 'float64', 'int32', 'int64', 'string', 'bool']),
    shape: zod_1.z.array(zod_1.z.union([zod_1.z.number(), zod_1.z.null()])).min(1),
    description: zod_1.z.string().optional(),
});
exports.modelDataSchemaSchema = zod_1.z.object({
    inputs: zod_1.z.array(exports.modelInputOutputSchema).min(1),
    outputs: zod_1.z.array(exports.modelInputOutputSchema).min(1),
});
exports.modelCreateRequestSchema = zod_1.z.object({
    name: zod_1.z.string().min(1).max(100),
    description: zod_1.z.string().max(1000).optional(),
    ownerId: zod_1.z.string().min(1),
    team: zod_1.z.string().min(1),
    tags: zod_1.z.array(zod_1.z.string()).default([]),
    metadata: zod_1.z.record(zod_1.z.unknown()).default({}),
});
exports.modelVersionCreateRequestSchema = zod_1.z.object({
    modelId: zod_1.z.string().min(1),
    version: zod_1.z.string().min(1).regex(/^[a-zA-Z0-9.-]+$/),
    semanticVersion: zod_1.z.string().min(1).regex(/^\d+\.\d+\.\d+$/),
    format: exports.modelFormatSchema,
    dataSchema: exports.modelDataSchemaSchema,
    metrics: zod_1.z.array(zod_1.z.object({
        name: zod_1.z.string(),
        value: zod_1.z.number(),
        timestamp: zod_1.z.number(),
        step: zod_1.z.number().optional(),
        context: zod_1.z.record(zod_1.z.unknown()).optional(),
    })).default([]),
    hyperParameters: zod_1.z.record(zod_1.z.union([zod_1.z.string(), zod_1.z.number(), zod_1.z.boolean(), zod_1.z.null()])).default({}),
    loaderConfig: zod_1.z.record(zod_1.z.unknown()).default({}),
    experimentId: zod_1.z.string().optional(),
    tags: zod_1.z.array(zod_1.z.string()).default([]),
});
exports.modelListRequestSchema = zod_1.z.object({
    name: zod_1.z.string().optional(),
    ownerId: zod_1.z.string().optional(),
    team: zod_1.z.string().optional(),
    tags: zod_1.z.array(zod_1.z.string()).optional(),
    status: exports.statusSchema.optional(),
    page: zod_1.z.number().int().min(1).default(1),
    pageSize: zod_1.z.number().int().min(1).max(100).default(20),
});
exports.modelVersionListRequestSchema = zod_1.z.object({
    modelId: zod_1.z.string().min(1),
    status: zod_1.z.enum(['pending', 'ready', 'failed', 'archived']).optional(),
    page: zod_1.z.number().int().min(1).default(1),
    pageSize: zod_1.z.number().int().min(1).max(100).default(20),
});
