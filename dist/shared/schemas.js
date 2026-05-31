"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LogLevelSchema = exports.AuthTokenSchema = exports.BatchOperationSchema = exports.TaskCreateSchema = exports.StatsSnapshotSchema = exports.RunInstanceSchema = exports.ConfigDefinitionSchema = exports.CoreEntitySchema = void 0;
const zod_1 = require("zod");
exports.CoreEntitySchema = zod_1.z.object({
    id: zod_1.z.string(),
    type: zod_1.z.string(),
    status: zod_1.z.enum(['active', 'inactive', 'archived']),
    attributes: zod_1.z.record(zod_1.z.unknown()),
    created_at: zod_1.z.string().datetime(),
    updated_at: zod_1.z.string().datetime(),
});
exports.ConfigDefinitionSchema = zod_1.z.object({
    config_id: zod_1.z.string(),
    namespace: zod_1.z.string(),
    version: zod_1.z.number().int().min(1),
    parameters: zod_1.z.record(zod_1.z.unknown()),
    enabled: zod_1.z.boolean(),
    applied_at: zod_1.z.string().datetime(),
    rollback_from: zod_1.z.number().int().min(1).optional(),
});
exports.RunInstanceSchema = zod_1.z.object({
    run_id: zod_1.z.string(),
    entity_id: zod_1.z.string(),
    phase: zod_1.z.enum(['pending', 'executing', 'completed', 'failed', 'rollback']),
    progress: zod_1.z.number().min(0).max(1),
    started_at: zod_1.z.string().datetime(),
    completed_at: zod_1.z.string().datetime().nullable(),
    error_detail: zod_1.z.string().nullable(),
    metadata: zod_1.z.record(zod_1.z.unknown()),
});
exports.StatsSnapshotSchema = zod_1.z.object({
    snapshot_id: zod_1.z.string(),
    timestamp: zod_1.z.string().datetime(),
    metrics: zod_1.z.object({
        throughput: zod_1.z.number(),
        latency_p99: zod_1.z.number(),
        error_rate: zod_1.z.number(),
    }).catchall(zod_1.z.number()),
    dimensions: zod_1.z.object({
        host: zod_1.z.string(),
        region: zod_1.z.string(),
    }).catchall(zod_1.z.string()),
});
exports.TaskCreateSchema = zod_1.z.object({
    type: zod_1.z.string(),
    config: zod_1.z.record(zod_1.z.unknown()),
    labels: zod_1.z.record(zod_1.z.string()),
});
exports.BatchOperationSchema = zod_1.z.object({
    operations: zod_1.z.array(zod_1.z.object({
        action: zod_1.z.enum(['start', 'stop', 'restart', 'delete']),
        id: zod_1.z.string(),
    })),
});
exports.AuthTokenSchema = zod_1.z.object({
    user_id: zod_1.z.string(),
    roles: zod_1.z.array(zod_1.z.string()),
    permissions: zod_1.z.array(zod_1.z.string()),
    tenant_id: zod_1.z.string(),
    exp: zod_1.z.number(),
});
exports.LogLevelSchema = zod_1.z.enum(['debug', 'info', 'warn', 'error', 'fatal']);
//# sourceMappingURL=schemas.js.map