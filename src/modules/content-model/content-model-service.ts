import { ContentModel, ContentEntry, ContentStatus } from '@prisma/client';
import { connectionPool } from '../tenant/connection-pool';
import { schemaValidator } from './schema-validator';
import { tableManager } from './table-manager';
import { ContentSchema, TenantContext } from '@types/index';
import { logger } from '@utils/logger';
import { generateId } from '@utils/crypto';

export interface CreateContentModelInput {
  name: string;
  code: string;
  description?: string;
  schema: ContentSchema;
}

export interface UpdateContentModelInput {
  name?: string;
  description?: string;
  schema?: ContentSchema;
}

export interface CreateContentInput {
  data: Record<string, unknown>;
  createdBy: string;
}

export interface UpdateContentInput {
  data: Record<string, unknown>;
  updatedBy: string;
  message?: string;
  expectedUpdatedAt?: Date;
}

export class ContentModelService {
  private prisma = connectionPool.getPlatformPrisma();

  async createContentModel(
    tenant: TenantContext,
    input: CreateContentModelInput
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

    const pool = connectionPool.getTenantPool(tenant.tenantId, tenant.dbSchema);
    await tableManager.createContentTable(pool, tenant.dbSchema, tableName, input.schema);

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
      },
    });

    logger.info({ tenantId: tenant.tenantId, modelId, code: input.code }, 'Created content model');
    return model;
  }

  async getContentModel(tenantId: string, modelId: string): Promise<ContentModel | null> {
    return this.prisma.contentModel.findFirst({
      where: { id: modelId, tenantId, deletedAt: null },
    });
  }

  async getContentModelByCode(tenantId: string, code: string): Promise<ContentModel | null> {
    return this.prisma.contentModel.findFirst({
      where: { tenantId, code, deletedAt: null },
    });
  }

  async listContentModels(
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

  async updateContentModel(
    tenant: TenantContext,
    modelId: string,
    input: UpdateContentModelInput
  ): Promise<{ model: ContentModel; migrationChanges: string[]; warnings: string[] }> {
    const model = await this.getContentModel(tenant.tenantId, modelId);
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

      const pool = connectionPool.getTenantPool(tenant.tenantId, tenant.dbSchema);
      const alterResult = await tableManager.alterContentTable(
        pool,
        tenant.dbSchema,
        model.tableName,
        oldSchema,
        input.schema
      );
      migrationChanges = alterResult.changes;

      if (alterResult.applied) {
        await tableManager.recordMigration(
          pool,
          tenant.dbSchema,
          `v${model.version + 1}`,
          `Schema update for ${model.code}`
        );

        const entries = await this.prisma.contentEntry.findMany({
          where: {
            tenantId: tenant.tenantId,
            modelId,
            deletedAt: null,
          },
          select: { id: true, data: true, publishedData: true },
        });

        for (const entry of entries) {
          const migrateResult = schemaValidator.migrateContent(
            entry.data as Record<string, unknown>,
            oldSchema,
            input.schema
          );
          warnings = [...warnings, ...migrateResult.warnings.map(w => `Entry ${entry.id}: ${w}`)];

          await this.prisma.contentEntry.update({
            where: { id: entry.id },
            data: {
              data: migrateResult.content as unknown as Prisma.JsonValue,
              updatedAt: new Date(),
            },
          });

          if (entry.publishedData) {
            const publishedMigrateResult = schemaValidator.migrateContent(
              entry.publishedData as Record<string, unknown>,
              oldSchema,
              input.schema
            );
            await this.prisma.contentEntry.update({
              where: { id: entry.id },
              data: {
                publishedData: publishedMigrateResult.content as unknown as Prisma.JsonValue,
              },
            });
          }
        }
      }
    }

    const updatedModel = await this.prisma.contentModel.update({
      where: { id: modelId },
      data: {
        name: input.name,
        description: input.description,
        schemaJson: input.schema ? input.schema as unknown as Prisma.JsonValue : undefined,
        version: { increment: input.schema ? 1 : 0 },
      },
    });

    logger.info(
      { tenantId: tenant.tenantId, modelId, migrationChanges, warnings },
      'Updated content model'
    );

    return { model: updatedModel, migrationChanges, warnings };
  }

  async deleteContentModel(tenantId: string, modelId: string): Promise<void> {
    const model = await this.getContentModel(tenantId, modelId);
    if (!model) return;

    const tenant = await this.prisma.tenant.findUnique({ where: { id: tenantId } });
    if (tenant) {
      const pool = connectionPool.getTenantPool(tenantId, tenant.dbSchema);
      await tableManager.dropContentTable(pool, tenant.dbSchema, model.tableName);
    }

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

    logger.info({ tenantId, modelId }, 'Deleted content model');
  }

  async createContent(
    tenant: TenantContext,
    modelId: string,
    input: CreateContentInput
  ): Promise<ContentEntry> {
    const model = await this.getContentModel(tenant.tenantId, modelId);
    if (!model) {
      throw new Error('Content model not found');
    }

    const schema = model.schemaJson as unknown as ContentSchema;
    const validation = schemaValidator.validateContent(input.data, schema);
    if (!validation.valid) {
      throw new Error(`Invalid content: ${validation.errors.join(', ')}`);
    }

    const contentId = generateId('content');

    const content = await this.prisma.contentEntry.create({
      data: {
        id: contentId,
        tenantId: tenant.tenantId,
        modelId,
        status: ContentStatus.DRAFT,
        data: input.data as unknown as Prisma.JsonValue,
        createdBy: input.createdBy,
        updatedBy: input.createdBy,
      },
    });

    logger.info({ tenantId: tenant.tenantId, modelId, contentId }, 'Created content entry');
    return content;
  }

  async getContent(
    tenantId: string,
    modelId: string,
    contentId: string
  ): Promise<ContentEntry | null> {
    return this.prisma.contentEntry.findFirst({
      where: { id: contentId, modelId, tenantId, deletedAt: null },
    });
  }

  async listContent(
    tenantId: string,
    modelId: string,
    page = 1,
    pageSize = 50,
    status?: ContentStatus,
    includePublished = false
  ): Promise<{ content: ContentEntry[]; total: number }> {
    const where: any = { modelId, tenantId, deletedAt: null };
    if (status) {
      where.status = status;
    }

    const [content, total] = await Promise.all([
      this.prisma.contentEntry.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
      }),
      this.prisma.contentEntry.count({ where }),
    ]);

    return { content, total };
  }

  async updateContent(
    tenant: TenantContext,
    modelId: string,
    contentId: string,
    input: UpdateContentInput
  ): Promise<ContentEntry> {
    const [model, existing] = await Promise.all([
      this.getContentModel(tenant.tenantId, modelId),
      this.getContent(tenant.tenantId, modelId, contentId),
    ]);

    if (!model) throw new Error('Content model not found');
    if (!existing) throw new Error('Content not found');

    if (input.expectedUpdatedAt) {
      const expectedTime = new Date(input.expectedUpdatedAt).getTime();
      const actualTime = new Date(existing.updatedAt).getTime();
      if (Math.abs(expectedTime - actualTime) > 1000) {
        const error = new Error(
          `Optimistic lock conflict: Content was modified by another user. ` +
          `Expected updatedAt: ${input.expectedUpdatedAt.toISOString()}, ` +
          `Actual updatedAt: ${existing.updatedAt.toISOString()}`
        );
        error.name = 'OptimisticLockError';
        throw error;
      }
    }

    const schema = model.schemaJson as unknown as ContentSchema;
    const validation = schemaValidator.validateContent(input.data, schema);
    if (!validation.valid) {
      throw new Error(`Invalid content: ${validation.errors.join(', ')}`);
    }

    const content = await this.prisma.contentEntry.update({
      where: { id: contentId },
      data: {
        data: input.data as unknown as Prisma.JsonValue,
        updatedBy: input.updatedBy,
      },
    });

    logger.info({ tenantId: tenant.tenantId, modelId, contentId }, 'Updated content entry');
    return content;
  }

  async deleteContent(
    tenantId: string,
    modelId: string,
    contentId: string
  ): Promise<void> {
    await this.prisma.contentEntry.update({
      where: { id: contentId },
      data: { deletedAt: new Date() },
    });

    logger.info({ tenantId, modelId, contentId }, 'Soft deleted content entry');
  }

  async publishContent(
    tenantId: string,
    modelId: string,
    contentId: string,
    publishedBy: string
  ): Promise<ContentEntry> {
    const content = await this.getContent(tenantId, modelId, contentId);
    if (!content) throw new Error('Content not found');

    return this.prisma.contentEntry.update({
      where: { id: contentId },
      data: {
        status: ContentStatus.PUBLISHED,
        publishedData: content.data,
        publishedAt: new Date(),
        updatedBy: publishedBy,
      },
    });
  }

  async unpublishContent(
    tenantId: string,
    modelId: string,
    contentId: string,
    updatedBy: string
  ): Promise<ContentEntry> {
    return this.prisma.contentEntry.update({
      where: { id: contentId },
      data: {
        status: ContentStatus.DRAFT,
        publishedData: null,
        publishedAt: null,
        updatedBy,
      },
    });
  }

  async getPublishedContent(
    tenantId: string,
    modelId: string,
    contentId: string
  ): Promise<Record<string, unknown> | null> {
    const content = await this.prisma.contentEntry.findFirst({
      where: { id: contentId, modelId, tenantId, status: ContentStatus.PUBLISHED, deletedAt: null },
      select: { publishedData: true },
    });

    return content?.publishedData as Record<string, unknown> || null;
  }

  async listPublishedContent(
    tenantId: string,
    modelId: string,
    page = 1,
    pageSize = 50
  ): Promise<{ content: Array<{ id: string; data: Record<string, unknown>; publishedAt: Date }>; total: number }> {
    const where = { modelId, tenantId, status: ContentStatus.PUBLISHED, deletedAt: null };

    const [content, total] = await Promise.all([
      this.prisma.contentEntry.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        select: { id: true, publishedData: true, publishedAt: true },
        orderBy: { publishedAt: 'desc' },
      }),
      this.prisma.contentEntry.count({ where }),
    ]);

    return {
      content: content.map(c => ({
        id: c.id,
        data: c.publishedData as Record<string, unknown>,
        publishedAt: c.publishedAt!,
      })),
      total,
    };
  }
}

export const contentModelService = new ContentModelService();

import { Prisma } from '@prisma/client';
