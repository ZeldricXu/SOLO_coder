"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.vectorIngestRequestSchema = exports.vectorIndexUpdateRequestSchema = exports.vectorIndexBuildRequestSchema = exports.rangeQueryRequestSchema = exports.vectorSearchRequestSchema = exports.vectorSearchFilterSchema = exports.exactMatchFilterSchema = exports.rangeFilterSchema = exports.vectorFeatureConfigSchema = exports.ivfConfigSchema = exports.hnswConfigSchema = void 0;
const zod_1 = require("zod");
exports.hnswConfigSchema = zod_1.z.object({
    m: zod_1.z.number().int().positive().min(4).max(64).default(16),
    efConstruction: zod_1.z.number().int().positive().min(100).max(2000).default(200),
    efSearch: zod_1.z.number().int().positive().min(10).max(2000).default(50),
    maxElements: zod_1.z.number().int().positive().default(1000000),
});
exports.ivfConfigSchema = zod_1.z.object({
    nlist: zod_1.z.number().int().positive().min(10).max(65536).default(1024),
    nprobe: zod_1.z.number().int().positive().min(1).max(65536).default(64),
    quantizerType: zod_1.z.enum(['flat', 'pq']).default('flat'),
    pqM: zod_1.z.number().int().positive().min(1).max(64).optional(),
});
exports.vectorFeatureConfigSchema = zod_1.z.object({
    featureName: zod_1.z.string().min(1),
    dimension: zod_1.z.number().int().positive().min(1).max(4096),
    distanceMetric: zod_1.z.enum(['cosine', 'l2', 'inner_product', 'manhattan']).default('cosine'),
    indexType: zod_1.z.enum(['hnsw', 'flat', 'ivf']).default('hnsw'),
    hnswConfig: exports.hnswConfigSchema.optional(),
    ivfConfig: exports.ivfConfigSchema.optional(),
});
exports.rangeFilterSchema = zod_1.z.object({
    featureName: zod_1.z.string().min(1),
    min: zod_1.z.number().optional(),
    max: zod_1.z.number().optional(),
    includeMin: zod_1.z.boolean().default(true),
    includeMax: zod_1.z.boolean().default(true),
});
exports.exactMatchFilterSchema = zod_1.z.object({
    featureName: zod_1.z.string().min(1),
    values: zod_1.z.array(zod_1.z.unknown()).min(1),
});
exports.vectorSearchFilterSchema = zod_1.z.object({
    rangeFilters: zod_1.z.array(exports.rangeFilterSchema).default([]),
    exactMatchFilters: zod_1.z.array(exports.exactMatchFilterSchema).default([]),
    booleanOperator: zod_1.z.enum(['AND', 'OR']).default('AND'),
});
exports.vectorSearchRequestSchema = zod_1.z.object({
    featureSetId: zod_1.z.string().min(1),
    featureName: zod_1.z.string().min(1),
    queryVector: zod_1.z.array(zod_1.z.number()).min(1),
    topK: zod_1.z.number().int().positive().max(1000).default(10),
    filter: exports.vectorSearchFilterSchema.optional(),
    efSearch: zod_1.z.number().int().positive().optional(),
    includeDistances: zod_1.z.boolean().default(true),
    includeFeatures: zod_1.z.boolean().default(true),
    featureNames: zod_1.z.array(zod_1.z.string()).optional(),
    version: zod_1.z.string().optional(),
}).refine(data => {
    if (data.queryVector.length < 1)
        return false;
    if (data.efSearch && (data.efSearch < 10 || data.efSearch > 2000))
        return false;
    return true;
}, {
    message: 'Invalid vector search parameters',
});
exports.rangeQueryRequestSchema = zod_1.z.object({
    featureSetId: zod_1.z.string().min(1),
    featureName: zod_1.z.string().min(1),
    filters: exports.vectorSearchFilterSchema,
    topK: zod_1.z.number().int().positive().max(1000).default(100),
    sortBy: zod_1.z.object({
        featureName: zod_1.z.string().min(1),
        order: zod_1.z.enum(['asc', 'desc']).default('asc'),
    }).optional(),
    includeFeatures: zod_1.z.boolean().default(true),
    featureNames: zod_1.z.array(zod_1.z.string()).optional(),
    version: zod_1.z.string().optional(),
});
exports.vectorIndexBuildRequestSchema = zod_1.z.object({
    featureSetId: zod_1.z.string().min(1),
    featureName: zod_1.z.string().min(1),
    dimension: zod_1.z.number().int().positive().min(1).max(4096),
    distanceMetric: zod_1.z.enum(['cosine', 'l2', 'inner_product', 'manhattan']).default('cosine'),
    indexType: zod_1.z.enum(['hnsw', 'flat', 'ivf']).default('hnsw'),
    hnswConfig: exports.hnswConfigSchema.optional(),
    ivfConfig: exports.ivfConfigSchema.optional(),
    dataSource: zod_1.z.object({
        entityKeyField: zod_1.z.string().min(1),
        vectorField: zod_1.z.string().min(1),
        additionalFields: zod_1.z.array(zod_1.z.string()).default([]),
    }).optional(),
});
exports.vectorIndexUpdateRequestSchema = zod_1.z.object({
    indexId: zod_1.z.string().min(1),
    data: zod_1.z.array(zod_1.z.object({
        entityKey: zod_1.z.string().min(1),
        vector: zod_1.z.array(zod_1.z.number()).min(1),
        features: zod_1.z.record(zod_1.z.unknown()).optional(),
    })).min(1),
    mode: zod_1.z.enum(['add', 'update', 'delete']),
});
exports.vectorIngestRequestSchema = zod_1.z.object({
    featureSetId: zod_1.z.string().min(1),
    featureName: zod_1.z.string().min(1),
    entityKeyField: zod_1.z.string().min(1),
    vectorField: zod_1.z.string().min(1),
    data: zod_1.z.array(zod_1.z.record(zod_1.z.unknown())).min(1),
    additionalFields: zod_1.z.array(zod_1.z.string()).default([]),
    mode: zod_1.z.enum(['overwrite', 'append', 'upsert']).default('upsert'),
    version: zod_1.z.string().optional(),
});
