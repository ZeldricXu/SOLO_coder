"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.autoscalingConfigSchema = exports.batchConfigSchema = exports.batchInferenceRequestSchema = exports.inferenceRequestSchema = void 0;
const zod_1 = require("zod");
exports.inferenceRequestSchema = zod_1.z.object({
    modelId: zod_1.z.string().min(1),
    version: zod_1.z.string().optional(),
    inputs: zod_1.z.union([
        zod_1.z.record(zod_1.z.unknown()),
        zod_1.z.array(zod_1.z.record(zod_1.z.unknown())).min(1),
    ]),
    requestId: zod_1.z.string().optional(),
    userId: zod_1.z.string().optional(),
    sessionId: zod_1.z.string().optional(),
    context: zod_1.z.record(zod_1.z.unknown()).default({}),
    bypassCache: zod_1.z.boolean().default(false),
});
exports.batchInferenceRequestSchema = zod_1.z.object({
    modelId: zod_1.z.string().min(1),
    version: zod_1.z.string().optional(),
    inputs: zod_1.z.array(zod_1.z.record(zod_1.z.unknown())).min(1),
    batchSize: zod_1.z.number().int().min(1).max(1024).optional(),
    maxConcurrency: zod_1.z.number().int().min(1).max(64).optional(),
    requestId: zod_1.z.string().optional(),
    context: zod_1.z.record(zod_1.z.unknown()).default({}),
});
exports.batchConfigSchema = zod_1.z.object({
    maxBatchSize: zod_1.z.number().int().min(1).max(1024).default(32),
    batchTimeoutMs: zod_1.z.number().int().min(1).max(60000).default(10),
    dynamicBatching: zod_1.z.boolean().default(true),
    maxQueueDepth: zod_1.z.number().int().min(1).default(1000),
});
exports.autoscalingConfigSchema = zod_1.z.object({
    minReplicas: zod_1.z.number().int().min(0).default(1),
    maxReplicas: zod_1.z.number().int().min(1).default(10),
    targetRPS: zod_1.z.number().int().min(1).default(100),
    targetP99LatencyMs: zod_1.z.number().int().min(1).default(500),
    scaleDownDelaySeconds: zod_1.z.number().int().min(0).default(300),
});
