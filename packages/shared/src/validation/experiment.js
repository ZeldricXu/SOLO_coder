"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.runListRequestSchema = exports.experimentListRequestSchema = exports.runUpdateRequestSchema = exports.runCreateRequestSchema = exports.experimentCreateRequestSchema = exports.metricValueSchema = exports.hyperParameterSchema = exports.experimentSourceSchema = void 0;
const zod_1 = require("zod");
exports.experimentSourceSchema = zod_1.z.object({
    type: zod_1.z.enum(['git', 'notebook', 'script', 'manual']),
    uri: zod_1.z.string().optional(),
    commitHash: zod_1.z.string().optional(),
    entryPoint: zod_1.z.string().optional(),
});
exports.hyperParameterSchema = zod_1.z.object({
    name: zod_1.z.string().min(1),
    value: zod_1.z.union([zod_1.z.string(), zod_1.z.number(), zod_1.z.boolean(), zod_1.z.null()]),
    type: zod_1.z.enum(['string', 'number', 'boolean', 'json']),
});
exports.metricValueSchema = zod_1.z.object({
    name: zod_1.z.string().min(1),
    value: zod_1.z.number(),
    timestamp: zod_1.z.number(),
    step: zod_1.z.number().int().optional(),
    context: zod_1.z.record(zod_1.z.unknown()).optional(),
});
exports.experimentCreateRequestSchema = zod_1.z.object({
    name: zod_1.z.string().min(1).max(100),
    description: zod_1.z.string().max(1000).optional(),
    projectId: zod_1.z.string().min(1),
    ownerId: zod_1.z.string().min(1),
    team: zod_1.z.string().min(1),
    tags: zod_1.z.array(zod_1.z.string()).default([]),
    metadata: zod_1.z.record(zod_1.z.unknown()).default({}),
});
exports.runCreateRequestSchema = zod_1.z.object({
    experimentId: zod_1.z.string().min(1),
    name: zod_1.z.string().min(1).max(100),
    hyperParameters: zod_1.z.array(exports.hyperParameterSchema).default([]),
    tags: zod_1.z.array(zod_1.z.string()).default([]),
    notes: zod_1.z.string().optional(),
    source: exports.experimentSourceSchema.optional(),
    datasetVersion: zod_1.z.string().optional(),
    parentRunId: zod_1.z.string().optional(),
});
exports.runUpdateRequestSchema = zod_1.z.object({
    status: zod_1.z.enum(['running', 'completed', 'failed', 'killed']).optional(),
    endTime: zod_1.z.number().optional(),
    metrics: zod_1.z.array(exports.metricValueSchema).default([]),
    artifactPaths: zod_1.z.array(zod_1.z.string()).default([]),
    modelVersionId: zod_1.z.string().optional(),
    notes: zod_1.z.string().optional(),
    tags: zod_1.z.array(zod_1.z.string()).optional(),
});
exports.experimentListRequestSchema = zod_1.z.object({
    name: zod_1.z.string().optional(),
    projectId: zod_1.z.string().optional(),
    ownerId: zod_1.z.string().optional(),
    team: zod_1.z.string().optional(),
    tags: zod_1.z.array(zod_1.z.string()).optional(),
    status: zod_1.z.enum(['active', 'inactive', 'archived', 'deleted']).optional(),
    page: zod_1.z.number().int().min(1).default(1),
    pageSize: zod_1.z.number().int().min(1).max(100).default(20),
});
exports.runListRequestSchema = zod_1.z.object({
    experimentId: zod_1.z.string().optional(),
    status: zod_1.z.enum(['running', 'completed', 'failed', 'killed']).optional(),
    parentRunId: zod_1.z.string().optional(),
    tags: zod_1.z.array(zod_1.z.string()).optional(),
    page: zod_1.z.number().int().min(1).default(1),
    pageSize: zod_1.z.number().int().min(1).max(100).default(20),
});
