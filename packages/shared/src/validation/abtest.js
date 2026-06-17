"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.abTestListRequestSchema = exports.trackEventRequestSchema = exports.assignmentRequestSchema = exports.abTestUpdateRequestSchema = exports.abTestCreateRequestSchema = exports.trafficAllocationConfigSchema = exports.abTestMetricSchema = exports.targetingRuleSchema = exports.abVariantSchema = void 0;
const zod_1 = require("zod");
exports.abVariantSchema = zod_1.z.object({
    name: zod_1.z.string().min(1),
    description: zod_1.z.string().optional(),
    isControl: zod_1.z.boolean().default(false),
    trafficWeight: zod_1.z.number().min(0).default(1),
    config: zod_1.z.record(zod_1.z.unknown()).default({}),
    modelId: zod_1.z.string().optional(),
    modelVersion: zod_1.z.string().optional(),
    status: zod_1.z.enum(['active', 'inactive']).default('active'),
});
exports.targetingRuleSchema = zod_1.z.object({
    type: zod_1.z.enum(['include', 'exclude']),
    attribute: zod_1.z.string().min(1),
    operator: zod_1.z.enum(['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'in', 'not_in', 'contains', 'regex']),
    value: zod_1.z.unknown(),
});
exports.abTestMetricSchema = zod_1.z.object({
    name: zod_1.z.string().min(1),
    description: zod_1.z.string().optional(),
    type: zod_1.z.enum(['primary', 'secondary', 'guardrail']),
    goal: zod_1.z.enum(['increase', 'decrease', 'no_change']),
    significanceLevel: zod_1.z.number().min(0).max(1).default(0.05),
    minimumDetectableEffect: zod_1.z.number().min(0).default(0.01),
});
exports.trafficAllocationConfigSchema = zod_1.z.object({
    type: zod_1.z.enum(['equal', 'weighted', 'custom']),
    totalTrafficPercentage: zod_1.z.number().min(0).max(100).default(100),
    customWeights: zod_1.z.record(zod_1.z.number()).optional(),
});
exports.abTestCreateRequestSchema = zod_1.z.object({
    name: zod_1.z.string().min(1).max(100),
    description: zod_1.z.string().max(1000).optional(),
    projectId: zod_1.z.string().min(1),
    ownerId: zod_1.z.string().min(1),
    team: zod_1.z.string().min(1),
    hypothesis: zod_1.z.string().min(1),
    primaryMetric: zod_1.z.string().min(1),
    bucketStrategy: zod_1.z.enum(['random', 'user_id', 'session_id', 'device_id', 'custom']),
    bucketKey: zod_1.z.string().optional(),
    variants: zod_1.z.array(exports.abVariantSchema).min(2),
    trafficAllocation: exports.trafficAllocationConfigSchema,
    targetingRules: zod_1.z.array(exports.targetingRuleSchema).default([]),
    metrics: zod_1.z.array(exports.abTestMetricSchema).default([]),
    tags: zod_1.z.array(zod_1.z.string()).default([]),
    metadata: zod_1.z.record(zod_1.z.unknown()).default({}),
});
exports.abTestUpdateRequestSchema = zod_1.z.object({
    name: zod_1.z.string().min(1).max(100).optional(),
    description: zod_1.z.string().max(1000).optional(),
    hypothesis: zod_1.z.string().min(1).optional(),
    primaryMetric: zod_1.z.string().min(1).optional(),
    status: zod_1.z.enum(['draft', 'running', 'paused', 'completed', 'archived']).optional(),
    startTime: zod_1.z.number().optional(),
    endTime: zod_1.z.number().optional(),
    variants: zod_1.z.array(exports.abVariantSchema).min(2).optional(),
    trafficAllocation: exports.trafficAllocationConfigSchema.optional(),
    targetingRules: zod_1.z.array(exports.targetingRuleSchema).optional(),
    metrics: zod_1.z.array(exports.abTestMetricSchema).optional(),
    tags: zod_1.z.array(zod_1.z.string()).optional(),
    metadata: zod_1.z.record(zod_1.z.unknown()).optional(),
    expectedUpdatedAt: zod_1.z.number().optional(),
});
exports.assignmentRequestSchema = zod_1.z.object({
    experimentId: zod_1.z.string().min(1),
    userId: zod_1.z.string().optional(),
    sessionId: zod_1.z.string().optional(),
    deviceId: zod_1.z.string().optional(),
    customKey: zod_1.z.string().optional(),
    context: zod_1.z.record(zod_1.z.unknown()).default({}),
    previewVariantId: zod_1.z.string().optional(),
});
exports.trackEventRequestSchema = zod_1.z.object({
    experimentId: zod_1.z.string().min(1),
    variantId: zod_1.z.string().min(1),
    userId: zod_1.z.string().optional(),
    sessionId: zod_1.z.string().optional(),
    eventName: zod_1.z.string().min(1),
    properties: zod_1.z.record(zod_1.z.unknown()).default({}),
    timestamp: zod_1.z.number().optional(),
});
exports.abTestListRequestSchema = zod_1.z.object({
    name: zod_1.z.string().optional(),
    projectId: zod_1.z.string().optional(),
    ownerId: zod_1.z.string().optional(),
    team: zod_1.z.string().optional(),
    status: zod_1.z.enum(['draft', 'running', 'paused', 'completed', 'archived']).optional(),
    page: zod_1.z.number().int().min(1).default(1),
    pageSize: zod_1.z.number().int().min(1).max(100).default(20),
});
