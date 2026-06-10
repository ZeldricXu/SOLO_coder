import { ContentModel } from '@prisma/client';
import { connectionPool } from '../tenant/connection-pool';
import { schemaValidator } from './schema-validator';
import { ContentSchema, TenantContext } from '@types/index';
import { logger } from '@utils/logger';
import { generateId } from '@utils/crypto';
import { schemaEvents } from './schema-events';

export interface SchemaVersion {
  version: number;
  schema: ContentSchema;
  createdAt: Date;
  createdBy?: string;
  description?: string;
  migrationChanges?: string[];
}

export interface CreateSchemaInput {
  name: string;
  code: string;
  description?: string;
  schema: ContentSchema;
  createdBy?: string;
}

export interface UpdateSchemaInput {
  name?: string;
  description?: string;
  schema?: ContentSchema;
  updatedBy?: string;
}

export class SchemaRegistry {
  private prisma = connectionPool.getPlatformPrisma();

  async createSchema(
    tenant: TenantContext,
    input: CreateSchemaInput
  ): Promise<ContentModel> {
    const existing = await this.prisma.contentModel.findFirst({
      where: { tenantId: tenant.tenantId, code: input.code, deletedAt: null },
    });

    if (existing) {
      throw new Error(`Content model with code ${input.code} already exists`);
    }

    const schemaValidation = schemaValidator.validateSchemaDefinition(input.schema);
    if (!schemaValidation.valid) {
      throw new Error(`Invalid schema: ${schemaValidation.errors.join(', ')}`);
    }

    const tableName = `content_${input.code.toLowerCase().replace(/[^a-z0-9_]/g, '_')}`;
    const modelId = generateId('model');

    const model = await this.prisma.contentModel.create({
      data: {
        id: modelId,
        tenantId: tenant.tenantId,
        name: input.name,
        code: input.code,
        description: input.description,
        tableName,
        schemaJson: input.schema as unknown as Prisma.JsonValue,
        version: 1,
        isPublished: true,
        schemaVersions: [
          {
            version: 1,
            schema: input.schema,
            createdAt: new Date(),
            createdBy: input.createdBy,
            description: 'Initial schema creation',
          },
        ] as unknown as Prisma.JsonValue,
      },
    });

    schemaEvents.emit('schema.created', {
      tenantId: tenant.tenantId,
      dbSchema: tenant.dbSchema,
      modelId,
      tableName,
      schema: input.schema,
      version: 1,
    });

    logger.info(
      { tenantId: tenant.tenantId, modelId, code: input.code },
      'Schema registered and creation event emitted'
    );

    return model;
  }

  async updateSchema(
    tenant: TenantContext,
    modelId: string,
    input: UpdateSchemaInput
  ): Promise<{ model: ContentModel; migrationChanges: string[]; warnings: string[] }> {
    const model = await this.getSchema(tenant.tenantId, modelId);
    if (!model) {
      throw new Error('Content model not found');
    }

    const oldSchema = model.schemaJson as unknown as ContentSchema;
    let migrationChanges: string[] = [];
    let warnings: string[] = [];

    if (input.schema) {
      const schemaValidation = schemaValidator.validateSchemaDefinition(input.schema);
      if (!schemaValidation.valid) {
        throw new Error(`Invalid schema: ${schemaValidation.errors.join(', ')}`);
      }

      const diffResult = schemaValidator.diffSchemas(oldSchema, input.schema);
      migrationChanges = diffResult.changes;

      if (diffResult.requiresMigration) {
        schemaEvents.emit('schema.updated', {
          tenantId: tenant.tenantId,
          dbSchema: tenant.dbSchema,
          modelId,
          tableName: model.tableName,
          oldSchema,
          newSchema: input.schema,
          version: model.version + 1,
        });

        schemaEvents.emit('schema.migrate-content', {
          tenantId: tenant.tenantId,
          modelId,
          oldSchema,
          newSchema: input.schema,
          version: model.version + 1,
        });
      }

      const schemaVersions = (model.schemaVersions as unknown as SchemaVersion[]) || [];
      schemaVersions.push({
        version: model.version + 1,
        schema: input.schema,
        createdAt: new Date(),
        createdBy: input.updatedBy,
        migrationChanges,
      });

      const updatedModel = await this.prisma.contentModel.update({
        where: { id: modelId },
        data: {
          name: input.name,
          description: input.description,
          schemaJson: input.schema ? input.schema as unknown as Prisma.JsonValue : undefined,
          version: { increment: input.schema ? 1 : 0 },
          schemaVersions: schemaVersions as unknown as Prisma.JsonValue,
        },
      });

      logger.info(
        { tenantId: tenant.tenantId, modelId, migrationChanges, warnings },
        'Schema updated and events emitted'
      );

      return { model: updatedModel, migrationChanges, warnings };
    }

    const updatedModel = await this.prisma.contentModel.update({
      where: { id: modelId },
      data: {
        name: input.name,
        description: input.description,
      },
    });

    return { model: updatedModel, migrationChanges: [], warnings: [] };
  }

  async deleteSchema(tenantId: string, modelId: string): Promise<void> {
    const model = await this.getSchema(tenantId, modelId);
    if (!model) return;

    schemaEvents.emit('schema.deleted', {
      tenantId,
      dbSchema: (await this.prisma.tenant.findUnique({ where: { id: tenantId } }))?.dbSchema,
      modelId,
      tableName: model.tableName,
    });

    await this.prisma.$transaction([
      this.prisma.contentVersion.deleteMany({ where: { modelId, tenantId } }),
      this.prisma.contentEntry.deleteMany({ where: { modelId, tenantId } }),
      this.prisma.workflowInstance.deleteMany({
        where: { content: { modelId, tenantId } },
      }),
      this.prisma.workflowDefinition.deleteMany({ where: { modelId, tenantId } }),
      this.prisma.searchConfig.deleteMany({ where: { modelId, tenantId } }),
      this.prisma.contentModel.update({
        where: { id: modelId },
        data: { deletedAt: new Date() },
      }),
    ]);

    logger.info({ tenantId, modelId }, 'Schema deleted and event emitted');
  }

  async getSchema(tenantId: string, modelId: string): Promise<ContentModel | null> {
    return this.prisma.contentModel.findFirst({
      where: { id: modelId, tenantId, deletedAt: null },
    });
  }

  async getSchemaByCode(tenantId: string, code: string): Promise<ContentModel | null> {
    return this.prisma.contentModel.findFirst({
      where: { tenantId, code, deletedAt: null },
    });
  }

  async listSchemas(
    tenantId: string,
    page = 1,
    pageSize = 50
  ): Promise<{ models: ContentModel[]; total: number }> {
    const where = { tenantId, deletedAt: null };

    const [models, total] = await Promise.all([
      this.prisma.contentModel.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
      }),
      this.prisma.contentModel.count({ where }),
    ]);

    return { models, total };
  }

  async getSchemaVersionHistory(
    tenantId: string,
    modelId: string
  ): Promise<SchemaVersion[]> {
    const model = await this.getSchema(tenantId, modelId);
    if (!model) return [];

    return (model.schemaVersions as unknown as SchemaVersion[]) || [];
  }

  async getSchemaByVersion(
    tenantId: string,
    modelId: string,
    version: number
  ): Promise<ContentSchema | null> {
    const history = await this.getSchemaVersionHistory(tenantId, modelId);
    const versionEntry = history.find(v => v.version === version);
    return versionEntry?.schema || null;
  }

  async validateContentAgainstSchema(
    tenantId: string,
    modelId: string,
    data: Record<string, unknown>,
    version?: number
  ): Promise<{ valid: boolean; errors: string[] }> {
    const model = await this.getSchema(tenantId, modelId);
    if (!model) {
      return { valid: false, errors: ['Content model not found'] };
    }

    let schema: ContentSchema;
    if (version) {
      const versionSchema = await this.getSchemaByVersion(tenantId, modelId, version);
      if (!versionSchema) {
        return { valid: false, errors: [`Schema version ${version} not found`] };
      }
      schema = versionSchema;
    } else {
      schema = model.schemaJson as unknown as ContentSchema;
    }

    return schemaValidator.validateContent(data, schema);
  }
}

export const schemaRegistry = new SchemaRegistry();

import { Prisma } from '@prisma/client';
