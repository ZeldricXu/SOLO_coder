import { ContentModel, ContentEntry, ContentStatus } from '@prisma/client';
import { connectionPool } from '../tenant/connection-pool';
import { schemaValidator } from './schema-validator';
import { ContentSchema, TenantContext } from '@types/index';
import { logger } from '@utils/logger';
import { generateId } from '@utils/crypto';
import { schemaRegistry } from './schema-registry';
import { tableManagerListener } from './table-manager-listener';
import { Prisma } from '@prisma/client';

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

  constructor() {
    tableManagerListener.start();
  }

  async createContentModel(
    tenant: TenantContext,
    input: CreateContentModelInput
  ): Promise<ContentModel> {
    return schemaRegistry.createSchema(tenant, input);
  }

  async getContentModel(tenantId: string, modelId: string): Promise<ContentModel | null> {
    return schemaRegistry.getSchema(tenantId, modelId);
  }

  async getContentModelByCode(tenantId: string, code: string): Promise<ContentModel | null> {
    return schemaRegistry.getSchemaByCode(tenantId, code);
  }

  async listContentModels(
    tenantId: string,
    page = 1,
    pageSize = 50
  ): Promise<{ models: ContentModel[]; total: number }> {
    return schemaRegistry.listSchemas(tenantId, page, pageSize);
  }

  async updateContentModel(
    tenant: TenantContext,
    modelId: string,
    input: UpdateContentModelInput
  ): Promise<{ model: ContentModel; migrationChanges: string[]; warnings: string[] }> {
    return schemaRegistry.updateSchema(tenant, modelId, input);
  }

  async deleteContentModel(tenantId: string, modelId: string): Promise<void> {
    return schemaRegistry.deleteSchema(tenantId, modelId);
  }

  async getSchemaVersionHistory(
    tenantId: string,
    modelId: string,
    page = 1,
    pageSize = 20
  ): Promise<{
    history: Array<{
      version: number;
      schema: ContentSchema;
      createdAt: Date;
      createdBy?: string;
      description?: string;
      migrationChanges?: string[];
    }>;
    total: number;
  }> {
    return schemaRegistry.getSchemaVersionHistory(tenantId, modelId, page, pageSize);
  }

  async getSchemaByVersion(
    tenantId: string,
    modelId: string,
    version: number
  ): Promise<{
    version: number;
    schema: ContentSchema;
    createdAt: Date;
    createdBy?: string;
    description?: string;
    migrationChanges?: string[];
  } | null> {
    return schemaRegistry.getSchemaByVersion(tenantId, modelId, version);
  }

  async createContent(
    tenant: TenantContext,
    modelId: string,
    input: CreateContentInput
  ): Promise<ContentEntry> {
    const validation = await schemaRegistry.validateContentAgainstSchema(
      tenant.tenantId,
      modelId,
      input.data
    );
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

    const validation = await schemaRegistry.validateContentAgainstSchema(
      tenant.tenantId,
      modelId,
      input.data
    );
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

  async close(): Promise<void> {
    tableManagerListener.stop();
    logger.info('Content model service closed');
  }
}

export const contentModelService = new ContentModelService();
