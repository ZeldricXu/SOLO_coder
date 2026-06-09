import {
  pgTable,
  uuid,
  varchar,
  text,
  integer,
  bigint,
  boolean,
  jsonb,
  timestamp,
  pgEnum,
  uniqueIndex,
  index,
} from 'drizzle-orm/pg-core';
import { sql } from 'drizzle-orm';

export const tenantStatusEnum = pgEnum('tenant_status', [
  'active',
  'suspended',
  'pending',
  'cancelled',
]);

export const planTierEnum = pgEnum('plan_tier', [
  'free',
  'starter',
  'professional',
  'enterprise',
]);

export const contentStatusEnum = pgEnum('content_status', [
  'draft',
  'reviewing',
  'approved',
  'published',
  'archived',
  'rejected',
]);

export const cdnRegionEnum = pgEnum('cdn_region', [
  'cn_north',
  'cn_south',
  'cn_east',
  'cn_west',
  'ap_southeast',
  'us_west',
  'eu_west',
]);

export const webhookEventEnum = pgEnum('webhook_event', [
  'content_created',
  'content_updated',
  'content_published',
  'content_deleted',
  'workflow_started',
  'workflow_approved',
  'workflow_rejected',
]);

export const webhookStatusEnum = pgEnum('webhook_status', [
  'pending',
  'success',
  'failed',
  'retrying',
]);

export const tenants = pgTable(
  'tenants',
  {
    id: uuid('id').primaryKey().default(sql`gen_random_uuid()`),
    code: varchar('code', { length: 50 }).notNull(),
    name: varchar('name', { length: 100 }).notNull(),
    status: tenantStatusEnum('status').notNull().default('pending'),
    plan: planTierEnum('plan').notNull().default('free'),
    hostPattern: varchar('host_pattern', { length: 200 }),
    apiKey: varchar('api_key', { length: 100 }).notNull(),
    dbSchema: varchar('db_schema', { length: 50 }).notNull(),
    elasticIndexPrefix: varchar('elastic_index_prefix', { length: 50 }).notNull(),
    customDomain: varchar('custom_domain', { length: 100 }),
    maxApiCallsPerDay: integer('max_api_calls_per_day').notNull().default(10000),
    maxStorageGb: integer('max_storage_gb').notNull().default(1),
    maxContentModels: integer('max_content_models').notNull().default(5),
    maxUsers: integer('max_users').notNull().default(10),
    maxWebhooks: integer('max_webhooks').notNull().default(3),
    enableVersioning: boolean('enable_versioning').notNull().default(false),
    enableWorkflow: boolean('enable_workflow').notNull().default(false),
    enableElasticsearch: boolean('enable_elasticsearch').notNull().default(false),
    enableCDN: boolean('enable_cdn').notNull().default(false),
    storageUsedBytes: bigint('storage_used_bytes', { mode: 'number' }).notNull().default(0),
    apiCallsToday: integer('api_calls_today').notNull().default(0),
    lastApiCallReset: timestamp('last_api_call_reset', { withTimezone: true }).notNull().defaultNow(),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).notNull().defaultNow(),
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
  },
  (table) => [
    uniqueIndex('tenants_code_unique').on(table.code),
    uniqueIndex('tenants_api_key_unique').on(table.apiKey),
    uniqueIndex('tenants_db_schema_unique').on(table.dbSchema),
    uniqueIndex('tenants_elastic_index_prefix_unique').on(table.elasticIndexPrefix),
    index('tenants_status_idx').on(table.status),
    index('tenants_host_pattern_idx').on(table.hostPattern),
  ]
);

export const contentModels = pgTable(
  'content_models',
  {
    id: uuid('id').primaryKey().default(sql`gen_random_uuid()`),
    tenantId: uuid('tenant_id').notNull().references(() => tenants.id),
    name: varchar('name', { length: 100 }).notNull(),
    code: varchar('code', { length: 50 }).notNull(),
    description: text('description'),
    tableName: varchar('table_name', { length: 100 }).notNull(),
    schemaJson: jsonb('schema_json').notNull(),
    version: integer('version').notNull().default(1),
    isPublished: boolean('is_published').notNull().default(false),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).notNull().defaultNow(),
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
  },
  (table) => [
    uniqueIndex('content_models_tenant_code_unique').on(table.tenantId, table.code),
    index('content_models_tenant_id_idx').on(table.tenantId),
  ]
);

export const contentEntries = pgTable(
  'content_entries',
  {
    id: uuid('id').primaryKey().default(sql`gen_random_uuid()`),
    tenantId: uuid('tenant_id').notNull().references(() => tenants.id),
    modelId: uuid('model_id').notNull().references(() => contentModels.id),
    status: contentStatusEnum('status').notNull().default('draft'),
    data: jsonb('data').notNull(),
    publishedData: jsonb('published_data'),
    publishedAt: timestamp('published_at', { withTimezone: true }),
    createdBy: varchar('created_by', { length: 100 }).notNull(),
    updatedBy: varchar('updated_by', { length: 100 }).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).notNull().defaultNow(),
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
  },
  (table) => [
    index('content_entries_tenant_model_idx').on(table.tenantId, table.modelId),
    index('content_entries_status_idx').on(table.status),
    index('content_entries_created_at_idx').on(table.createdAt),
  ]
);

export const contentVersions = pgTable(
  'content_versions',
  {
    id: uuid('id').primaryKey().default(sql`gen_random_uuid()`),
    tenantId: uuid('tenant_id').notNull().references(() => tenants.id),
    contentId: uuid('content_id').notNull().references(() => contentEntries.id),
    modelId: uuid('model_id').notNull().references(() => contentModels.id),
    version: integer('version').notNull(),
    snapshot: jsonb('snapshot').notNull(),
    status: contentStatusEnum('status').notNull(),
    diffPatch: text('diff_patch'),
    message: varchar('message', { length: 500 }),
    createdBy: varchar('created_by', { length: 100 }).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    uniqueIndex('content_versions_content_version_unique').on(table.contentId, table.version),
    index('content_versions_content_id_idx').on(table.contentId),
    index('content_versions_created_at_idx').on(table.createdAt),
  ]
);

export const webhookConfigs = pgTable(
  'webhook_configs',
  {
    id: uuid('id').primaryKey().default(sql`gen_random_uuid()`),
    tenantId: uuid('tenant_id').notNull().references(() => tenants.id),
    url: varchar('url', { length: 500 }).notNull(),
    secret: varchar('secret', { length: 100 }).notNull(),
    events: webhookEventEnum('events').array().notNull(),
    active: boolean('active').notNull().default(true),
    timeoutMs: integer('timeout_ms').notNull().default(5000),
    maxRetries: integer('max_retries').notNull().default(5),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    index('webhook_configs_tenant_id_idx').on(table.tenantId),
    index('webhook_configs_active_idx').on(table.active),
  ]
);

export const webhookDeliveries = pgTable(
  'webhook_deliveries',
  {
    id: uuid('id').primaryKey().default(sql`gen_random_uuid()`),
    webhookId: uuid('webhook_id').notNull().references(() => webhookConfigs.id),
    event: webhookEventEnum('event').notNull(),
    payload: jsonb('payload').notNull(),
    status: webhookStatusEnum('status').notNull().default('pending'),
    attempts: integer('attempts').notNull().default(0),
    lastAttemptAt: timestamp('last_attempt_at', { withTimezone: true }),
    nextAttemptAt: timestamp('next_attempt_at', { withTimezone: true }),
    responseStatusCode: integer('response_status_code'),
    errorMessage: text('error_message'),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    index('webhook_deliveries_webhook_id_idx').on(table.webhookId),
    index('webhook_deliveries_status_idx').on(table.status),
    index('webhook_deliveries_next_attempt_at_idx').on(table.nextAttemptAt),
  ]
);

export const tenantUsage = pgTable(
  'tenant_usage',
  {
    id: uuid('id').primaryKey().default(sql`gen_random_uuid()`),
    tenantId: uuid('tenant_id').notNull().references(() => tenants.id),
    date: varchar('date', { length: 10 }).notNull(),
    apiCalls: integer('api_calls').notNull().default(0),
    storageUsedBytes: bigint('storage_used_bytes', { mode: 'number' }).notNull().default(0),
    contentCount: integer('content_count').notNull().default(0),
    userCount: integer('user_count').notNull().default(0),
    webhookCount: integer('webhook_count').notNull().default(0),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    uniqueIndex('tenant_usage_tenant_date_unique').on(table.tenantId, table.date),
    index('tenant_usage_tenant_id_idx').on(table.tenantId),
    index('tenant_usage_date_idx').on(table.date),
  ]
);
