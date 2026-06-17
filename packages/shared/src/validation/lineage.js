"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.experimentCreateWithParentSchema = exports.experimentForkRequestSchema = exports.lineageCompareRequestSchema = exports.lineageQueryRequestSchema = void 0;
const zod_1 = require("zod");
exports.lineageQueryRequestSchema = zod_1.z.object({
    runId: zod_1.z.string().optional(),
    experimentId: zod_1.z.string().optional(),
    depth: zod_1.z.number().int().min(1).max(10).default(3),
    direction: zod_1.z.enum(['up', 'down', 'both']).default('both'),
    includeMetrics: zod_1.z.boolean().default(true),
    primaryMetric: zod_1.z.string().optional(),
    improvementDirection: zod_1.z.enum(['higher', 'lower']).default('higher'),
}).refine(data => data.runId || data.experimentId, {
    message: 'Either runId or experimentId must be provided',
});
exports.lineageCompareRequestSchema = zod_1.z.object({
    runIds: zod_1.z.array(zod_1.z.string()).min(2).max(20),
    metrics: zod_1.z.array(zod_1.z.string()).optional(),
    includeHyperParameters: zod_1.z.boolean().default(false),
});
exports.experimentForkRequestSchema = zod_1.z.object({
    sourceRunId: zod_1.z.string().min(1),
    name: zod_1.z.string().min(1).max(100),
    description: zod_1.z.string().max(1000).optional(),
    hyperParameterOverrides: zod_1.z.record(zod_1.z.string(), zod_1.z.any()).optional(),
    tags: zod_1.z.array(zod_1.z.string()).max(20).optional(),
    notes: zod_1.z.string().max(2000).optional(),
});
exports.experimentCreateWithParentSchema = zod_1.z.object({
    name: zod_1.z.string().min(1).max(100),
    description: zod_1.z.string().max(1000).optional(),
    projectId: zod_1.z.string().min(1),
    ownerId: zod_1.z.string().min(1),
    team: zod_1.z.string().optional(),
    parentExperimentId: zod_1.z.string().min(1),
    parentRunId: zod_1.z.string().optional(),
    variantType: zod_1.z.enum(['baseline', 'variant', 'finetune']).default('variant'),
    tags: zod_1.z.array(zod_1.z.string()).max(20).optional(),
    metadata: zod_1.z.record(zod_1.z.string(), zod_1.z.any()).optional(),
});
