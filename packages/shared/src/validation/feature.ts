import { z } from 'zod';

export const featureValueTypeSchema = z.enum(['float', 'int', 'string', 'bool', 'bytes', 'json']);

export const featureEntitySchema = z.object({
  name: z.string().min(1),
  description: z.string().optional(),
  joinKey: z.string().min(1),
});

export const featureSchema = z.object({
  name: z.string().min(1),
  description: z.string().optional(),
  valueType: featureValueTypeSchema,
  defaultValue: z.unknown().optional(),
  isNullable: z.boolean().default(true),
  tags: z.array(z.string()).default([]),
});

export const featureStorageConfigSchema = z.object({
  type: z.enum(['redis', 'postgres', 's3', 'bigquery', 'snowflake']),
  connectionString: z.string().optional(),
  tableName: z.string().optional(),
  bucketName: z.string().optional(),
  prefix: z.string().optional(),
});

export const featureSetCreateRequestSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().max(1000).optional(),
  projectId: z.string().min(1),
  ownerId: z.string().min(1),
  team: z.string().min(1),
  mode: z.enum(['online', 'offline', 'both']),
  entities: z.array(featureEntitySchema).min(1),
  features: z.array(featureSchema).min(1),
  ttlSeconds: z.number().int().min(0).optional(),
  onlineStorage: featureStorageConfigSchema.optional(),
  offlineStorage: featureStorageConfigSchema.optional(),
  tags: z.array(z.string()).default([]),
});

export const featureVersionCreateRequestSchema = z.object({
  featureSetId: z.string().min(1),
  version: z.string().min(1).regex(/^[a-zA-Z0-9.-]+$/),
  featureSchema: z.object({
    entities: z.array(featureEntitySchema).min(1),
    features: z.array(featureSchema).min(1),
  }),
  sourceUri: z.string().optional(),
  rowCount: z.number().int().min(0).optional(),
  sizeBytes: z.number().int().min(0).optional(),
});

export const featureListRequestSchema = z.object({
  name: z.string().optional(),
  projectId: z.string().optional(),
  ownerId: z.string().optional(),
  team: z.string().optional(),
  mode: z.enum(['online', 'offline', 'both']).optional(),
  tags: z.array(z.string()).optional(),
  status: z.enum(['active', 'inactive', 'archived', 'deleted']).optional(),
  page: z.number().int().min(1).default(1),
  pageSize: z.number().int().min(1).max(100).default(20),
});

export const featureGetRequestSchema = z.object({
  featureSetId: z.string().min(1),
  featureNames: z.array(z.string()).optional(),
  entityKeys: z.array(z.string()).min(1),
  version: z.string().optional(),
});

export const featureIngestRequestSchema = z.object({
  featureSetId: z.string().min(1),
  version: z.string().optional(),
  data: z.array(z.record(z.unknown())).min(1),
  entityKeyField: z.string().min(1),
  timestampField: z.string().optional(),
  mode: z.enum(['overwrite', 'append', 'upsert']).default('upsert'),
});

export type FeatureSetCreateRequestInput = z.infer<typeof featureSetCreateRequestSchema>;
export type FeatureVersionCreateRequestInput = z.infer<typeof featureVersionCreateRequestSchema>;
export type FeatureListRequestInput = z.infer<typeof featureListRequestSchema>;
export type FeatureGetRequestInput = z.infer<typeof featureGetRequestSchema>;
export type FeatureIngestRequestInput = z.infer<typeof featureIngestRequestSchema>;
