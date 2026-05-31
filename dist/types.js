"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ClassificationLevels = exports.StatsSnapshotSchema = exports.MetricsSchema = exports.RunInstanceSchema = exports.ConfigSchema = exports.EntitySchema = void 0;
const zod_1 = require("zod");
exports.EntitySchema = zod_1.z.object({
    id: zod_1.z.string(),
    type: zod_1.z.string(),
    status: zod_1.z.enum(['pending', 'running', 'completed', 'failed']),
    attributes: zod_1.z.record(zod_1.z.string(), zod_1.z.unknown()),
    created_at: zod_1.z.string().datetime(),
    updated_at: zod_1.z.string().datetime(),
});
exports.ConfigSchema = zod_1.z.object({
    config_id: zod_1.z.string(),
    namespace: zod_1.z.string(),
    version: zod_1.z.number().int().positive(),
    parameters: zod_1.z.record(zod_1.z.string(), zod_1.z.unknown()),
    enabled: zod_1.z.boolean(),
    applied_at: zod_1.z.string().datetime(),
});
exports.RunInstanceSchema = zod_1.z.object({
    run_id: zod_1.z.string(),
    entity_id: zod_1.z.string(),
    phase: zod_1.z.string(),
    progress: zod_1.z.number().min(0).max(1),
    started_at: zod_1.z.string().datetime(),
    completed_at: zod_1.z.string().datetime().nullable(),
    error_detail: zod_1.z.string().nullable(),
});
exports.MetricsSchema = zod_1.z.object({
    throughput: zod_1.z.number(),
    latency_p99: zod_1.z.number(),
    error_rate: zod_1.z.number(),
});
exports.StatsSnapshotSchema = zod_1.z.object({
    snapshot_id: zod_1.z.string(),
    timestamp: zod_1.z.string().datetime(),
    metrics: exports.MetricsSchema,
    dimensions: zod_1.z.record(zod_1.z.string(), zod_1.z.string()),
});
exports.ClassificationLevels = {
    0: { level: 0, name: 'PUBLIC', description: '公开数据，无保密要求', handlingRules: ['无限制访问'] },
    1: { level: 1, name: 'INTERNAL', description: '内部数据，仅限内部人员访问', handlingRules: ['需要内部认证'] },
    2: { level: 2, name: 'CONFIDENTIAL', description: '敏感数据，需要特定权限', handlingRules: ['需要角色授权', '记录访问日志'] },
    3: { level: 3, name: 'SECRET', description: '机密数据，严格限制访问', handlingRules: ['需要高级授权', '审批流程', '完整审计'] },
    4: { level: 4, name: 'TOP_SECRET', description: '最高机密，仅限授权人员', handlingRules: ['需要最高级别授权', '多人审批', '实时监控'] },
};
//# sourceMappingURL=types.js.map