import { ContentVersion, ContentStatus } from '@prisma/client';
import { connectionPool } from '../tenant/connection-pool';
import { computeDiff, DiffResult, generateVersionMessage, applyPatch, DiffChange } from '@utils/diff';
import { generateId } from '@utils/crypto';
import { logger } from '@utils/logger';
import { TenantContext } from '@types/index';

export interface CreateVersionInput {
  contentId: string;
  modelId: string;
  snapshot: Record<string, unknown>;
  status: ContentStatus;
  createdBy: string;
  message?: string;
  previousSnapshot?: Record<string, unknown>;
}

export interface RestoreVersionInput {
  versionId: string;
  restoredBy: string;
}

export class VersionControlService {
  private prisma = connectionPool.getPlatformPrisma();

  async createVersion(
    tenant: TenantContext,
    input: CreateVersionInput
  ): Promise<{ version: ContentVersion; diff: DiffResult }> {
    if (!tenant.limits.enableVersioning) {
      throw new Error('Versioning is not enabled for this tenant');
    }

    const maxVersion = await this.prisma.contentVersion.aggregate({
      where: {
        tenantId: tenant.tenantId,
        contentId: input.contentId,
      },
      _max: { version: true },
    });

    const nextVersion = (maxVersion._max.version || 0) + 1;

    let diffResult: DiffResult | undefined;
    let diffPatch: string | undefined;
    let message = input.message;

    if (input.previousSnapshot) {
      diffResult = computeDiff(input.previousSnapshot, input.snapshot);
      diffPatch = diffResult.patch;
      
      if (!message && diffResult.changes.length > 0) {
        message = generateVersionMessage(diffResult.changes);
      }
    }

    const version = await this.prisma.contentVersion.create({
      data: {
        id: generateId('ver'),
        tenantId: tenant.tenantId,
        contentId: input.contentId,
        modelId: input.modelId,
        version: nextVersion,
        snapshot: input.snapshot as unknown as Prisma.JsonValue,
        status: input.status,
        diffPatch,
        message,
        createdBy: input.createdBy,
      },
    });

    if (!diffResult) {
      diffResult = {
        changes: [],
        oldSnapshot: input.previousSnapshot || {},
        newSnapshot: input.snapshot,
        patch: diffPatch || '',
      };
    }

    logger.info(
      { tenantId: tenant.tenantId, contentId: input.contentId, version: nextVersion },
      'Created content version'
    );

    return { version, diff: diffResult };
  }

  async getVersion(
    tenantId: string,
    versionId: string
  ): Promise<ContentVersion | null> {
    return this.prisma.contentVersion.findFirst({
      where: { id: versionId, tenantId },
    });
  }

  async listVersions(
    tenantId: string,
    contentId: string,
    page = 1,
    pageSize = 20
  ): Promise<{ versions: ContentVersion[]; total: number }> {
    const where = { tenantId, contentId };

    const [versions, total] = await Promise.all([
      this.prisma.contentVersion.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { version: 'desc' },
      }),
      this.prisma.contentVersion.count({ where }),
    ]);

    return { versions, total };
  }

  async compareVersions(
    tenantId: string,
    contentId: string,
    versionA: number,
    versionB: number
  ): Promise<DiffResult & {
    versionA: ContentVersion;
    versionB: ContentVersion;
  }> {
    const [vA, vB] = await Promise.all([
      this.prisma.contentVersion.findFirst({
      where: { tenantId, contentId, version: versionA },
    }),
      this.prisma.contentVersion.findFirst({
      where: { tenantId, contentId, version: versionB },
    }),
    ]);

    if (!vA || !vB) {
      throw new Error('One or both versions not found');
    }

    const diff = computeDiff(
      vA.snapshot as Record<string, unknown>,
      vB.snapshot as Record<string, unknown>
    );

    return { ...diff, versionA: vA, versionB: vB };
  }

  async restoreVersion(
    tenant: TenantContext,
    input: RestoreVersionInput
  ): Promise<{ restoredContent: Record<string, unknown> }> {
    if (!tenant.limits.enableVersioning) {
      throw new Error('Versioning is not enabled for this tenant');
    }

    const version = await this.getVersion(tenant.tenantId, input.versionId);
    if (!version) {
      throw new Error('Version not found');
    }

    const currentContent = await this.prisma.contentEntry.findFirst({
      where: { id: version.contentId, tenantId: tenant.tenantId },
    });

    if (!currentContent) {
      throw new Error('Content not found');
    }

    const restoredSnapshot = version.snapshot as Record<string, unknown>;

    await this.createVersion(tenant, {
      contentId: version.contentId,
      modelId: version.modelId,
      snapshot: currentContent.data as Record<string, unknown>,
      status: currentContent.status,
      createdBy: input.restoredBy,
      message: `Restored from version ${version.version}`,
      previousSnapshot: restoredSnapshot,
    });

    await this.prisma.contentEntry.update({
      where: { id: version.contentId },
      data: {
        data: restoredSnapshot as unknown as Prisma.JsonValue,
        updatedBy: input.restoredBy,
      },
    });

    logger.info(
      { tenantId: tenant.tenantId, contentId: version.contentId, versionId: input.versionId },
      'Restored content version'
    );

    return { restoredContent: restoredSnapshot };
  }

  async getVersionDiff(
    tenantId: string,
    versionId: string
  ): Promise<{ version: ContentVersion; changes: DiffChange[]; patch: string }> {
    const version = await this.getVersion(tenantId, versionId);
    if (!version) {
      throw new Error('Version not found');
    }

    if (!version.diffPatch) {
      return { version, changes: [], patch: '' };
    }

    const previousVersion = await this.prisma.contentVersion.findFirst({
      where: {
        tenantId,
        contentId: version.contentId,
        version: version.version - 1,
      },
    });

    const changes: DiffChange[] = [];

    if (previousVersion) {
      const diff = computeDiff(
        previousVersion.snapshot as Record<string, unknown>,
        version.snapshot as Record<string, unknown>
      );
      return { version, changes: diff.changes, patch: version.diffPatch };
    }

    return { version, changes, patch: version.diffPatch };
  }

  async deleteOldVersions(
    tenantId: string,
    contentId: string,
    keepCount = 50
  ): Promise<number> {
    const versionsToKeep = await this.prisma.contentVersion.findMany({
      where: { tenantId, contentId },
      orderBy: { version: 'desc' },
      take: keepCount,
      select: { id: true },
    });

    const keepIds = new Set(versionsToKeep.map(v => v.id));

    const result = await this.prisma.contentVersion.deleteMany({
      where: {
        tenantId,
        contentId,
        NOT: { id: { in: Array.from(keepIds) } },
      },
    });

    logger.info(
      { tenantId, contentId, deletedCount: result.count },
      'Deleted old content versions'
    );

    return result.count;
  }

  async getDraftContent(
    tenantId: string,
    modelId: string,
    contentId: string
  ): Promise<Record<string, unknown> | null> {
    const content = await this.prisma.contentEntry.findFirst({
      where: {
      id: contentId,
      modelId,
      tenantId,
      deletedAt: null,
    },
    });

    return content?.data as Record<string, unknown> || null;
  }

  async getPublishedContent(
    tenantId: string,
    modelId: string,
    contentId: string
  ): Promise<Record<string, unknown> | null> {
    const content = await this.prisma.contentEntry.findFirst({
      where: {
      id: contentId,
      modelId,
      tenantId,
      status: ContentStatus.PUBLISHED,
      deletedAt: null,
    },
    });

    return content?.publishedData as Record<string, unknown> || null;
  }

  async updateDraft(
    tenant: TenantContext,
    modelId: string,
    contentId: string,
    data: Record<string, unknown>,
    updatedBy: string,
    message?: string
  ): Promise<{ content: any; version?: ContentVersion }> {
    const existing = await this.prisma.contentEntry.findFirst({
      where: { id: contentId, modelId, tenantId: tenant.tenantId, deletedAt: null },
    });

    if (!existing) {
      throw new Error('Content not found');
    }

    let newVersion: ContentVersion | undefined;

    if (tenant.limits.enableVersioning) {
      const result = await this.createVersion(tenant, {
        contentId,
        modelId,
        snapshot: data,
        status: ContentStatus.DRAFT,
        createdBy: updatedBy,
        message,
        previousSnapshot: existing.data as Record<string, unknown>,
      });
      newVersion = result.version;
    }

    const content = await this.prisma.contentEntry.update({
      where: { id: contentId },
      data: {
        data: data as unknown as Prisma.JsonValue,
        status: ContentStatus.DRAFT,
        updatedBy,
      },
    });

    return { content, version: newVersion };
  }

  async publishDraft(
    tenant: TenantContext,
    modelId: string,
    contentId: string,
    publishedBy: string,
    message?: string
  ): Promise<{ content: any; version?: ContentVersion }> {
    const existing = await this.prisma.contentEntry.findFirst({
      where: { id: contentId, modelId, tenantId: tenant.tenantId, deletedAt: null },
    });

    if (!existing) {
      throw new Error('Content not found');
    }

    let newVersion: ContentVersion | undefined;

    if (tenant.limits.enableVersioning) {
      const result = await this.createVersion(tenant, {
        contentId,
        modelId,
        snapshot: existing.data as Record<string, unknown>,
        status: ContentStatus.PUBLISHED,
        createdBy: publishedBy,
        message: message || 'Published',
        previousSnapshot: existing.publishedData as Record<string, unknown> | undefined,
      });
      newVersion = result.version;
    }

    const content = await this.prisma.contentEntry.update({
      where: { id: contentId },
      data: {
        status: ContentStatus.PUBLISHED,
        publishedData: existing.data,
        publishedAt: new Date(),
        updatedBy: publishedBy,
      },
    });

    logger.info(
      { tenantId: tenant.tenantId, contentId },
      'Published content'
    );

    return { content, version: newVersion };
  }
}

export const versionControlService = new VersionControlService();

import { Prisma } from '@prisma/client';
