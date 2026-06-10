import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import { FastifyInstance } from 'fastify';
import {
  setupTestApp,
  simulateConcurrentRequests,
  expectStatus,
  flushRedis,
} from '../helpers';
import {
  createTenant,
  createContentModel,
  createArticleSchemaFactory,
  createProductSchemaFactory,
  createApiRequest,
} from '../factories';
import { ContentSchema, TenantContext } from '@types/index';
import { schemaValidator } from '../../src/modules/content-model/schema-validator';
import { tableManager } from '../../src/modules/content-model/table-manager';
import { connectionPool } from '../../src/modules/tenant/connection-pool';
import { Tenant } from '@prisma/client';
import { tenantResolver } from '../../src/modules/tenant/tenant-resolver';

describe('Content Model Engine - Unit Tests', () => {
  let app: FastifyInstance;
  let prisma: any;
  let testTenant: Tenant;
  let tenantContext: TenantContext;

  const createComprehensiveSchema = (): ContentSchema => ({
    $schema: 'http://json-schema.org/draft-07/schema#',
    title: 'ComprehensiveModel',
    type: 'object',
    properties: {
      name_field: {
        name: 'name_field',
        type: 'string',
        required: true,
        indexed: true,
        validations: { minLength: 1, maxLength: 100 },
      },
      description_field: {
        name: 'description_field',
        type: 'text',
        required: false,
        searchable: true,
        searchWeight: 1.5,
      },
      count_field: {
        name: 'count_field',
        type: 'integer',
        required: true,
        indexed: true,
        validations: { minimum: 0, maximum: 1000000 },
        default: 0,
      },
      price_field: {
        name: 'price_field',
        type: 'float',
        required: true,
        validations: { minimum: 0, maximum: 999999.99 },
        default: 0.0,
      },
      active_field: {
        name: 'active_field',
        type: 'boolean',
        required: true,
        default: true,
      },
      publish_date: {
        name: 'publish_date',
        type: 'date',
        required: false,
        indexed: true,
      },
      created_datetime: {
        name: 'created_datetime',
        type: 'datetime',
        required: false,
      },
      metadata_field: {
        name: 'metadata_field',
        type: 'json',
        required: false,
        default: {},
      },
    },
    required: ['name_field', 'count_field', 'price_field', 'active_field'],
    additionalProperties: false,
  });

  beforeAll(async () => {
    const ctx = await setupTestApp();
    app = ctx.app;
    prisma = ctx.prisma;
  });

  beforeEach(async () => {
    await flushRedis();
    testTenant = await createTenant({
      plan: 'PROFESSIONAL',
      status: 'ACTIVE',
    });
    tenantContext = await tenantResolver.resolveFromRequest({
      headers: { 'x-api-key': testTenant.apiKey },
    } as any) as TenantContext;
  });

  describe('Normal Path - Content Model Creation and Validation', () => {
    it('should create content model with 8 field types (string, text, integer, float, boolean, date, datetime, json)', async () => {
      const schema = createComprehensiveSchema();
      const modelCode = `test_model_${Date.now()}`;

      const response = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Test Comprehensive Model',
          code: modelCode,
          description: 'Test model with all 8 field types',
          schema,
        })
      );

      expectStatus(response, 201);
      const result = response.json();

      expect(result.name).toBe('Test Comprehensive Model');
      expect(result.code).toBe(modelCode);
      expect(result.version).toBe(1);
      expect(result.tableName).toBe(`content_${modelCode}`);

      const savedSchema = result.schemaJson as ContentSchema;
      expect(Object.keys(savedSchema.properties)).toHaveLength(8);
      expect(savedSchema.properties.name_field.type).toBe('string');
      expect(savedSchema.properties.description_field.type).toBe('text');
      expect(savedSchema.properties.count_field.type).toBe('integer');
      expect(savedSchema.properties.price_field.type).toBe('float');
      expect(savedSchema.properties.active_field.type).toBe('boolean');
      expect(savedSchema.properties.publish_date.type).toBe('date');
      expect(savedSchema.properties.created_datetime.type).toBe('datetime');
      expect(savedSchema.properties.metadata_field.type).toBe('json');
    });

    it('should create PostgreSQL table with correct columns and indexes at runtime', async () => {
      const schema = createComprehensiveSchema();
      const modelCode = `table_test_${Date.now()}`;

      const response = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Table Test Model',
          code: modelCode,
          description: 'Test table creation',
          schema,
        })
      );

      expectStatus(response, 201);
      const result = response.json();

      const pool = connectionPool.getTenantPool(testTenant.id, testTenant.dbSchema);

      const tableExists = await tableManager.tableExists(
        pool,
        testTenant.dbSchema,
        result.tableName
      );
      expect(tableExists).toBe(true);

      const columns = await tableManager.getTableColumns(
        pool,
        testTenant.dbSchema,
        result.tableName
      );

      const columnNames = columns.map(c => c.name);
      expect(columnNames).toContain('id');
      expect(columnNames).toContain('tenant_id');
      expect(columnNames).toContain('status');
      expect(columnNames).toContain('name_field');
      expect(columnNames).toContain('description_field');
      expect(columnNames).toContain('count_field');
      expect(columnNames).toContain('price_field');
      expect(columnNames).toContain('active_field');
      expect(columnNames).toContain('publish_date');
      expect(columnNames).toContain('created_datetime');
      expect(columnNames).toContain('metadata_field');
      expect(columnNames).toContain('created_by');
      expect(columnNames).toContain('updated_by');
      expect(columnNames).toContain('created_at');
      expect(columnNames).toContain('updated_at');
      expect(columnNames).toContain('deleted_at');

      const nameCol = columns.find(c => c.name === 'name_field');
      expect(nameCol?.type).toBe('character varying');
      expect(nameCol?.nullable).toBe(false);

      const descCol = columns.find(c => c.name === 'description_field');
      expect(descCol?.type).toBe('text');
      expect(descCol?.nullable).toBe(true);

      const countCol = columns.find(c => c.name === 'count_field');
      expect(countCol?.type).toBe('integer');
      expect(countCol?.nullable).toBe(false);

      const priceCol = columns.find(c => c.name === 'price_field');
      expect(priceCol?.type).toBe('numeric');
      expect(priceCol?.nullable).toBe(false);

      const activeCol = columns.find(c => c.name === 'active_field');
      expect(activeCol?.type).toBe('boolean');
      expect(activeCol?.nullable).toBe(false);

      const dateCol = columns.find(c => c.name === 'publish_date');
      expect(dateCol?.type).toBe('date');
      expect(dateCol?.nullable).toBe(true);

      const metaCol = columns.find(c => c.name === 'metadata_field');
      expect(metaCol?.type).toBe('jsonb');
      expect(metaCol?.nullable).toBe(true);

      const indexResult = await pool.query(
        `SELECT indexname FROM pg_indexes WHERE schemaname = $1 AND tablename = $2`,
        [testTenant.dbSchema, result.tableName]
      );
      const indexNames = indexResult.rows.map(r => r.indexname);

      expect(indexNames).toContain(`${result.tableName}_pkey`);
      expect(indexNames).toContain(`${result.tableName}_tenant_id_idx`);
      expect(indexNames).toContain(`${result.tableName}_status_idx`);
      expect(indexNames).toContain(`${result.tableName}_created_at_idx`);
      expect(indexNames).toContain(`${result.tableName}_name_field_idx`);
      expect(indexNames).toContain(`${result.tableName}_count_field_idx`);
      expect(indexNames).toContain(`${result.tableName}_publish_date_idx`);
    });

    it('should insert record passing schema validation and persist data correctly', async () => {
      const schema = createComprehensiveSchema();
      const modelCode = `insert_test_${Date.now()}`;

      const createResponse = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Insert Test Model',
          code: modelCode,
          description: 'Test data insertion',
          schema,
        })
      );
      expectStatus(createResponse, 201);
      const model = createResponse.json();

      const testData = {
        name_field: 'Test Product',
        description_field: 'This is a detailed description of the test product with extensive text content.',
        count_field: 42,
        price_field: 99.99,
        active_field: true,
        publish_date: '2024-01-15',
        created_datetime: '2024-01-15T10:30:00Z',
        metadata_field: {
          tags: ['test', 'product'],
          ratings: { average: 4.5, count: 128 },
        },
      };

      const insertResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
          data: testData,
          createdBy: 'user-test-123',
        })
      );

      expectStatus(insertResponse, 201);
      const content = insertResponse.json();

      expect(content.status).toBe('DRAFT');
      expect(content.createdBy).toBe('user-test-123');
      expect(content.updatedBy).toBe('user-test-123');

      const savedData = content.data as Record<string, unknown>;
      expect(savedData.name_field).toBe('Test Product');
      expect(savedData.description_field).toBe('This is a detailed description of the test product with extensive text content.');
      expect(savedData.count_field).toBe(42);
      expect(savedData.price_field).toBe(99.99);
      expect(savedData.active_field).toBe(true);
      expect(savedData.publish_date).toBe('2024-01-15');
      expect(savedData.created_datetime).toBe('2024-01-15T10:30:00Z');
      expect(savedData.metadata_field).toEqual({
        tags: ['test', 'product'],
        ratings: { average: 4.5, count: 128 },
      });

      const getResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content/${content.id}`, 'GET')
      );
      expectStatus(getResponse, 200);
      const retrieved = getResponse.json();
      expect(retrieved.data).toEqual(content.data);
    });

    it('should apply default values for missing fields with defaults', async () => {
      const schema = createComprehensiveSchema();
      const modelCode = `default_test_${Date.now()}`;

      const createResponse = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Default Test Model',
          code: modelCode,
          description: 'Test default values',
          schema,
        })
      );
      expectStatus(createResponse, 201);
      const model = createResponse.json();

      const minimalData = {
        name_field: 'Minimal Product',
      };

      const insertResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
          data: minimalData,
          createdBy: 'user-test-123',
        })
      );

      expectStatus(insertResponse, 201);
      const content = insertResponse.json();
      const savedData = content.data as Record<string, unknown>;

      expect(savedData.count_field).toBe(0);
      expect(savedData.price_field).toBe(0.0);
      expect(savedData.active_field).toBe(true);
      expect(savedData.metadata_field).toEqual({});
    });

    it('should handle forward-compatible migration: add new fields with defaults and migrate existing data', async () => {
      const articleSchema = createArticleSchemaFactory();
      const modelCode = `migration_test_${Date.now()}`;

      const createResponse = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Migration Test Model',
          code: modelCode,
          description: 'Test schema migration',
          schema: articleSchema,
        })
      );
      expectStatus(createResponse, 201);
      const model = createResponse.json();

      const insertPromises = [];
      for (let i = 0; i < 5; i++) {
        insertPromises.push(
          app.inject(
            createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
              data: {
                title: `Article ${i}`,
                content: `Content for article ${i}`,
                summary: `Summary ${i}`,
                author: `Author ${i}`,
                tags: ['test'],
                views: i * 10,
                featured: i % 2 === 0,
              },
              createdBy: 'user-migration-123',
            })
          )
        );
      }
      const insertResults = await Promise.all(insertPromises);
      insertResults.forEach(r => expectStatus(r, 201));

      const migratedSchema: ContentSchema = {
        ...articleSchema,
        $schema: 'http://json-schema.org/draft-07/schema#',
        properties: {
          ...articleSchema.properties,
          category: {
            name: 'category',
            type: 'string',
            required: false,
            default: 'uncategorized',
            searchable: true,
          },
          priority: {
            name: 'priority',
            type: 'integer',
            required: false,
            default: 0,
            validations: { minimum: 0, maximum: 10 },
          },
          is_archived: {
            name: 'is_archived',
            type: 'boolean',
            required: false,
            default: false,
          },
        },
        required: articleSchema.required,
      };

      const updateResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}`, 'PUT', {
          schema: migratedSchema,
        })
      );

      expect(updateResponse.statusCode).toBe(200);
      const updateResult = updateResponse.json();

      expect(updateResult.model.version).toBe(2);
      expect(updateResult.migrationChanges).toContain('Added column: category (string)');
      expect(updateResult.migrationChanges).toContain('Added column: priority (integer)');
      expect(updateResult.migrationChanges).toContain('Added column: is_archived (boolean)');

      const listResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'GET')
      );
      expectStatus(listResponse, 200);
      const listResult = listResponse.json();

      for (const entry of listResult.data) {
        const data = entry.data as Record<string, unknown>;
        expect(data.category).toBe('uncategorized');
        expect(data.priority).toBe(0);
        expect(data.is_archived).toBe(false);
      }

      updateResult.warnings.forEach((warning: string) => {
        expect(warning).toContain("Added new field");
        expect(warning).toContain("with default value");
      });
    });
  });

  describe('Exception Path - Schema Validation and Error Handling', () => {
    it('should reject creating model with invalid field type and provide clear error', async () => {
      const invalidSchema = {
        $schema: 'http://json-schema.org/draft-07/schema#',
        title: 'InvalidModel',
        type: 'object',
        properties: {
          valid_field: {
            name: 'valid_field',
            type: 'string',
            required: true,
          },
          invalid_field: {
            name: 'invalid_field',
            type: 'invalid_type_xyz',
            required: false,
          },
        },
        required: ['valid_field'],
        additionalProperties: false,
      };

      const response = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Invalid Type Model',
          code: `invalid_type_${Date.now()}`,
          schema: invalidSchema,
        })
      );

      expect(response.statusCode).toBeGreaterThanOrEqual(400);
      const error = response.json();
      expect(error.message || error.error).toContain('invalid_type_xyz');
      expect(error.message || error.error).toContain('type');
    });

    it('should reject writing content with missing required fields', async () => {
      const schema = createComprehensiveSchema();
      const modelCode = `required_test_${Date.now()}`;

      const createResponse = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Required Test Model',
          code: modelCode,
          schema,
        })
      );
      expectStatus(createResponse, 201);
      const model = createResponse.json();

      const invalidData = {
        description_field: 'Missing required fields',
      };

      const insertResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
          data: invalidData,
          createdBy: 'user-test-123',
        })
      );

      expect(insertResponse.statusCode).toBeGreaterThanOrEqual(400);
      const error = insertResponse.json();
      const errorMsg = error.message || error.error;
      expect(errorMsg).toContain('name_field');
      expect(errorMsg).toContain('required');
    });

    it('should reject writing content when string field exceeds maxLength', async () => {
      const schema = createComprehensiveSchema();
      const modelCode = `length_test_${Date.now()}`;

      const createResponse = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Length Test Model',
          code: modelCode,
          schema,
        })
      );
      expectStatus(createResponse, 201);
      const model = createResponse.json();

      const tooLongName = 'A'.repeat(150);
      const invalidData = {
        name_field: tooLongName,
        count_field: 10,
        price_field: 29.99,
        active_field: true,
      };

      const insertResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
          data: invalidData,
          createdBy: 'user-test-123',
        })
      );

      expect(insertResponse.statusCode).toBeGreaterThanOrEqual(400);
      const error = insertResponse.json();
      const errorMsg = error.message || error.error;
      expect(errorMsg).toContain('name_field');
      expect(errorMsg).toContain('longer');
    });

    it('should reject writing content when integer field exceeds maximum', async () => {
      const schema = createComprehensiveSchema();
      const modelCode = `int_max_test_${Date.now()}`;

      const createResponse = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Int Max Test Model',
          code: modelCode,
          schema,
        })
      );
      expectStatus(createResponse, 201);
      const model = createResponse.json();

      const invalidData = {
        name_field: 'Test',
        count_field: 9999999,
        price_field: 29.99,
        active_field: true,
      };

      const insertResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
          data: invalidData,
          createdBy: 'user-test-123',
        })
      );

      expect(insertResponse.statusCode).toBeGreaterThanOrEqual(400);
      const error = insertResponse.json();
      const errorMsg = error.message || error.error;
      expect(errorMsg).toContain('count_field');
      expect(errorMsg).toContain('maximum');
    });

    it('should provide clear migration warning when changing field type from string to integer with existing data', async () => {
      const initialSchema: ContentSchema = {
        $schema: 'http://json-schema.org/draft-07/schema#',
        title: 'TypeChangeModel',
        type: 'object',
        properties: {
          value: {
            name: 'value',
            type: 'string',
            required: true,
          },
        },
        required: ['value'],
        additionalProperties: false,
      };

      const modelCode = `type_change_${Date.now()}`;

      const createResponse = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Type Change Test Model',
          code: modelCode,
          schema: initialSchema,
        })
      );
      expectStatus(createResponse, 201);
      const model = createResponse.json();

      const insertPromises = [
        app.inject(
          createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
            data: { value: '123' },
            createdBy: 'user-type-1',
          })
        ),
        app.inject(
          createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
            data: { value: 'not_a_number' },
            createdBy: 'user-type-2',
          })
        ),
        app.inject(
          createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
            data: { value: '456' },
            createdBy: 'user-type-3',
          })
        ),
      ];
      const insertResults = await Promise.all(insertPromises);
      insertResults.forEach(r => expectStatus(r, 201));

      const updatedSchema: ContentSchema = {
        ...initialSchema,
        properties: {
          value: {
            name: 'value',
            type: 'integer',
            required: true,
          },
        },
      };

      const updateResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}`, 'PUT', {
          schema: updatedSchema,
        })
      );

      expect(updateResponse.statusCode).toBe(200);
      const updateResult = updateResponse.json();

      expect(updateResult.migrationChanges).toContain(
        'Changed column type: value from string to integer'
      );

      const typeChangeWarnings = updateResult.warnings.filter((w: string) =>
        w.includes('type changed')
      );
      expect(typeChangeWarnings.length).toBeGreaterThanOrEqual(3);

      const listResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'GET')
      );
      expectStatus(listResponse, 200);
      const listResult = listResponse.json();

      const values = listResult.data.map((entry: any) => entry.data.value);
      expect(values).toContain(123);
      expect(values).toContain(0);
      expect(values).toContain(456);
    });

    it('should reject creating model with reserved field names', async () => {
      const invalidSchema = {
        $schema: 'http://json-schema.org/draft-07/schema#',
        title: 'ReservedNameModel',
        type: 'object',
        properties: {
          id: {
            name: 'id',
            type: 'string',
            required: true,
          },
          created_at: {
            name: 'created_at',
            type: 'datetime',
            required: false,
          },
        },
        required: ['id'],
        additionalProperties: false,
      };

      const response = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Reserved Name Model',
          code: `reserved_${Date.now()}`,
          schema: invalidSchema,
        })
      );

      expect(response.statusCode).toBeGreaterThanOrEqual(400);
      const error = response.json();
      const errorMsg = error.message || error.error;
      expect(errorMsg).toContain('reserved');
    });
  });

  describe('Concurrency Scenario - Optimistic Locking', () => {
    it('should handle concurrent edits with optimistic locking: first request succeeds, others return 409', async () => {
      const schema = createArticleSchemaFactory();
      const modelCode = `concurrency_test_${Date.now()}`;

      const createResponse = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'Concurrency Test Model',
          code: modelCode,
          description: 'Test optimistic locking',
          schema,
        })
      );
      expectStatus(createResponse, 201);
      const model = createResponse.json();

      const initialData = {
        title: 'Original Title',
        content: 'Original content that will be edited concurrently.',
        summary: 'Original summary',
        author: 'Original Author',
        views: 0,
        featured: false,
      };

      const insertResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
          data: initialData,
          createdBy: 'user-concurrent-123',
        })
      );
      expectStatus(insertResponse, 201);
      const content = insertResponse.json();

      const getResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content/${content.id}`, 'GET')
      );
      expectStatus(getResponse, 200);
      const currentContent = getResponse.json();
      const originalUpdatedAt = new Date(currentContent.updatedAt);

      const concurrentRequests = [];
      const titles = ['Edit 1 - First', 'Edit 2 - Second', 'Edit 3 - Third', 'Edit 4 - Fourth', 'Edit 5 - Fifth'];

      for (let i = 0; i < 5; i++) {
        concurrentRequests.push(async () => {
          try {
            return await app.inject(
              createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content/${content.id}`, 'PUT', {
                data: {
                  ...initialData,
                  title: titles[i],
                  content: `Updated content by edit ${i + 1}`,
                  views: i + 1,
                },
                updatedBy: `user-editor-${i + 1}`,
                expectedUpdatedAt: originalUpdatedAt.toISOString(),
              })
            );
          } catch (e) {
            return e;
          }
        });
      }

      const results = await simulateConcurrentRequests(concurrentRequests, 5);

      const successResults = results.filter(
        r => r && typeof r.statusCode === 'number' && r.statusCode === 200
      );
      const conflictResults = results.filter(
        r => r && typeof r.statusCode === 'number' && r.statusCode === 409
      );

      expect(successResults.length).toBe(1);
      expect(conflictResults.length).toBe(4);

      const successResult = successResults[0].json();
      expect(successResult.data.title).toBeDefined();
      expect(titles).toContain(successResult.data.title);

      for (const conflict of conflictResults) {
        const error = conflict.json();
        expect(error.code).toBe('OPTIMISTIC_LOCK_CONFLICT');
        expect(error.message).toContain('Optimistic lock conflict');
        expect(error.message).toContain('modified by another user');
      }

      const finalGetResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content/${content.id}`, 'GET')
      );
      expectStatus(finalGetResponse, 200);
      const finalContent = finalGetResponse.json();

      expect(finalContent.data.title).toBe(successResult.data.title);
      expect(new Date(finalContent.updatedAt).getTime()).toBeGreaterThan(
        originalUpdatedAt.getTime()
      );
    });

    it('should allow update without optimistic lock check when expectedUpdatedAt is not provided', async () => {
      const schema = createArticleSchemaFactory();
      const modelCode = `no_lock_test_${Date.now()}`;

      const createResponse = await app.inject(
        createApiRequest(testTenant.apiKey, '/api/v1/content-models', 'POST', {
          name: 'No Lock Test Model',
          code: modelCode,
          schema,
        })
      );
      expectStatus(createResponse, 201);
      const model = createResponse.json();

      const insertResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content`, 'POST', {
          data: {
            title: 'Test Article',
            content: 'Test content',
          },
          createdBy: 'user-no-lock-123',
        })
      );
      expectStatus(insertResponse, 201);
      const content = insertResponse.json();

      const updateResponse = await app.inject(
        createApiRequest(testTenant.apiKey, `/api/v1/content-models/${model.id}/content/${content.id}`, 'PUT', {
          data: {
            title: 'Updated Title',
            content: 'Updated content',
          },
          updatedBy: 'user-no-lock-456',
        })
      );

      expectStatus(updateResponse, 200);
      const updated = updateResponse.json();
      expect(updated.data.title).toBe('Updated Title');
    });
  });

  describe('Schema Validator Unit Tests', () => {
    it('should validate correct schema definition', () => {
      const schema = createComprehensiveSchema();
      const result = schemaValidator.validateSchemaDefinition(schema);
      expect(result.valid).toBe(true);
      expect(result.errors).toEqual([]);
    });

    it('should validate content against schema', () => {
      const schema = createComprehensiveSchema();
      const validContent = {
        name_field: 'Test',
        count_field: 10,
        price_field: 99.99,
        active_field: true,
      };
      const result = schemaValidator.validateContent(validContent, schema);
      expect(result.valid).toBe(true);
    });

    it('should detect invalid content with clear error messages', () => {
      const schema = createComprehensiveSchema();
      const invalidContent = {
        name_field: '',
        count_field: -5,
        price_field: 'not_a_number',
        active_field: 'yes',
      };
      const result = schemaValidator.validateContent(invalidContent, schema);
      expect(result.valid).toBe(false);
      expect(result.errors.length).toBeGreaterThan(0);
      result.errors.forEach(error => {
        expect(typeof error).toBe('string');
        expect(error.length).toBeGreaterThan(0);
      });
    });

    it('should migrate content with new fields with defaults', () => {
      const oldSchema = createArticleSchemaFactory();
      const newSchema: ContentSchema = {
        ...oldSchema,
        $schema: 'http://json-schema.org/draft-07/schema#',
        properties: {
          ...oldSchema.properties,
          new_field: {
            name: 'new_field',
            type: 'string',
            required: false,
            default: 'default_value',
          },
        },
        required: oldSchema.required,
      };

      const oldContent = {
        title: 'Old Title',
        content: 'Old content',
      };

      const result = schemaValidator.migrateContent(oldContent, oldSchema, newSchema);
      expect(result.content.new_field).toBe('default_value');
      expect(result.warnings).toContain("Added new field 'new_field' with default value");
    });
  });
});
