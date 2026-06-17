"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.featureIngestRequestSchema = exports.featureGetRequestSchema = exports.featureListRequestSchema = exports.featureVersionCreateRequestSchema = exports.featureSetCreateRequestSchema = exports.featureStorageConfigSchema = exports.featureSchema = exports.featureEntitySchema = exports.featureValueTypeSchema = void 0;
const zod_1 = require("zod");
exports.featureValueTypeSchema = zod_1.z.enum(['float', 'int', 'string', 'bool', 'bytes', 'json']);
exports.featureEntitySchema = zod_1.z.object({
    name: zod_1.z.string().min(1),
    description: zod_1.z.string().optional(),
    joinKey: zod_1.z.string().min(1),
});
exports.featureSchema = zod_1.z.object({
    name: zod_1.z.string().min(1),
    description: zod_1.z.string().optional(),
    valueType: exports.featureValueTypeSchema,
    defaultValue: zod_1.z.unknown().optional(),
    isNullable: zod_1.z.boolean().default(true),
    tags: zod_1.z.array(zod_1.z.string()).default([]),
});
exports.featureStorageConfigSchema = zod_1.z.object({
    type: zod_1.z.enum(['redis', 'postgres', 's3', 'bigquery', 'snowflake']),
    connectionString: zod_1.z.string().optional(),
    tableName: zod_1.z.string().optional(),
    bucketName: zod_1.z.string().optional(),
    prefix: zod_1.z.string().optional(),
});
exports.featureSetCreateRequestSchema = zod_1.z.object({
    name: zod_1.z.string().min(1).max(100),
    description: zod_1.z.string().max(1000).optional(),
    projectId: zod_1.z.string().min(1),
    ownerId: zod_1.z.string().min(1),
    team: zod_1.z.string().min(1),
    mode: zod_1.z.enum(['online', 'offline', 'both']),
    entities: zod_1.z.array(exports.featureEntitySchema).min(1),
    features: zod_1.z.array(exports.featureSchema).min(1),
    ttlSeconds: zod_1.z.number().int().min(0).optional(),
    onlineStorage: exports.featureStorageConfigSchema.optional(),
    offlineStorage: exports.featureStorageConfigSchema.optional(),
    tags: zod_1.z.array(zod_1.z.string()).default([]),
});
exports.featureVersionCreateRequestSchema = zod_1.z.object({
    featureSetId: zod_1.z.string().min(1),
    version: zod_1.z.string().min(1).regex(/^[a-zA-Z0-9.-]+$/),
    featureSchema: zod_1.z.object({
        entities: zod_1.z.array(exports.featureEntitySchema).min(1),
        features: zod_1.z.array(exports.featureSchema).min(1),
    }),
    sourceUri: zod_1.z.string().optional(),
    rowCount: zod_1.z.number().int().min(0).optional(),
    sizeBytes: zod_1.z.number().int().min(0).optional(),
});
exports.featureListRequestSchema = zod_1.z.object({
    name: zod_1.z.string().optional(),
    projectId: zod_1.z.string().optional(),
    ownerId: zod_1.z.string().optional(),
    team: zod_1.z.string().optional(),
    mode: zod_1.z.enum(['online', 'offline', 'both']).optional(),
    tags: zod_1.z.array(zod_1.z.string()).optional(),
    status: zod_1.z.enum(['active', 'inactive', 'archived', 'deleted']).optional(),
    page: zod_1.z.number().int().min(1).default(1),
    pageSize: zod_1.z.number().int().min(1).max(100).default(20),
});
exports.featureGetRequestSchema = zod_1.z.object({
    featureSetId: zod_1.z.string().min(1),
    featureNames: zod_1.z.array(zod_1.z.string()).optional(),
    entityKeys: zod_1.z.array(zod_1.z.string()).min(1),
    version: zod_1.z.string().optional(),
});
exports.featureIngestRequestSchema = zod_1.z.object({
    featureSetId: zod_1.z.string().min(1),
    version: zod_1.z.string().optional(),
    data: zod_1.z.array(zod_1.z.record(zod_1.z.unknown())).min(1),
    entityKeyField: zod_1.z.string().min(1),
    timestampField: zod_1.z.string().optional(),
    mode: zod_1.z.enum(['overwrite', 'append', 'upsert']).default('upsert'),
});
