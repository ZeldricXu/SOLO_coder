import type { PrismaClient } from '@prisma/client';
import type {
  Version,
  VersionListResult,
  VersionDiff,
  DiffStats,
  VersionServiceCreateInput,
  VersionServiceListInput,
  VersionServiceCompareInput,
  VersionServiceRollbackInput,
  VersionServiceCleanupInput,
  VersionSummary,
} from '../../lib/types/version';
import {
  compareLinesToChunks,
  compareWords,
  generateDiffStats,
} from '../../lib/diff';
import { mergeStats } from '../../lib/diff/utils';

export class VersionService {
  private prisma: PrismaClient;

  constructor(prisma: PrismaClient) {
    this.prisma = prisma;
  }

  async createVersion(input: VersionServiceCreateInput): Promise<Version> {
    const document = await this.prisma.document.findUnique({
      where: { id: input.documentId },
      select: {
        _count: {
          select: { versions: true },
        },
      },
    });

    if (!document) {
      throw new Error('文档不存在');
    }

    const nextVersion = document._count.versions + 1;

    let changeSummary: string | undefined;
    if (nextVersion > 1) {
      const previousVersion = await this.prisma.documentVersion.findFirst({
        where: {
          documentId: input.documentId,
          version: nextVersion - 1,
        },
        select: { content: true, title: true },
      });

      if (previousVersion) {
        const stats = generateDiffStats(
          previousVersion.content,
          input.content
        );
        const titleStats = generateDiffStats(
          previousVersion.title,
          input.title
        );
        const mergedStats = mergeStats(stats, titleStats);
        changeSummary = this.generateVersionSummary(mergedStats);
      }
    }

    const version = await this.prisma.documentVersion.create({
      data: {
        documentId: input.documentId,
        title: input.title,
        content: input.content,
        contentHtml: input.contentHtml,
        version: nextVersion,
        message: input.message,
        changeSummary,
        createdById: input.createdById,
      },
      include: {
        createdBy: {
          select: {
            id: true,
            name: true,
            email: true,
            avatar: true,
          },
        },
      },
    });

    await this.prisma.document.update({
      where: { id: input.documentId },
      data: {
        version: nextVersion,
      },
    });

    return this.mapToVersion(version);
  }

  async listVersions(
    input: VersionServiceListInput
  ): Promise<VersionListResult> {
    const {
      documentId,
      page = 1,
      pageSize = 20,
      sortBy = 'version',
      sortOrder = 'desc',
    } = input;

    const skip = (page - 1) * pageSize;

    const where = { documentId };

    const [versions, total] = await Promise.all([
      this.prisma.documentVersion.findMany({
        where,
        skip,
        take: pageSize,
        include: {
          createdBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
        },
        orderBy: { [sortBy]: sortOrder },
      }),
      this.prisma.documentVersion.count({ where }),
    ]);

    return {
      items: versions.map((v) => this.mapToVersion(v)),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async getVersion(
    documentId: string,
    versionNumber: number
  ): Promise<Version | null> {
    const version = await this.prisma.documentVersion.findFirst({
      where: {
        documentId,
        version: versionNumber,
      },
      include: {
        createdBy: {
          select: {
            id: true,
            name: true,
            email: true,
            avatar: true,
          },
        },
      },
    });

    return version ? this.mapToVersion(version) : null;
  }

  async getVersionById(versionId: string): Promise<Version | null> {
    const version = await this.prisma.documentVersion.findUnique({
      where: { id: versionId },
      include: {
        createdBy: {
          select: {
            id: true,
            name: true,
            email: true,
            avatar: true,
          },
        },
      },
    });

    return version ? this.mapToVersion(version) : null;
  }

  async rollbackToVersion(
    input: VersionServiceRollbackInput
  ): Promise<Version> {
    const { documentId, targetVersion, createdById, message } = input;

    const target = await this.prisma.documentVersion.findFirst({
      where: {
        documentId,
        version: targetVersion,
      },
    });

    if (!target) {
      throw new Error('目标版本不存在');
    }

    const document = await this.prisma.document.findUnique({
      where: { id: documentId },
      select: {
        _count: {
          select: { versions: true },
        },
      },
    });

    if (!document) {
      throw new Error('文档不存在');
    }

    const nextVersion = document._count.versions + 1;
    const rollbackMessage = message || `回滚到版本 ${targetVersion}`;

    const stats = generateDiffStats(target.content, target.content);
    const changeSummary = `回滚到版本 ${targetVersion}。${this.generateVersionSummary(stats)}`;

    const [newVersion] = await this.prisma.$transaction([
      this.prisma.documentVersion.create({
        data: {
          documentId,
          title: target.title,
          content: target.content,
          contentHtml: target.contentHtml,
          version: nextVersion,
          message: rollbackMessage,
          changeSummary,
          createdById,
        },
        include: {
          createdBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
        },
      }),
      this.prisma.document.update({
        where: { id: documentId },
        data: {
          title: target.title,
          content: target.content,
          contentHtml: target.contentHtml,
          version: nextVersion,
        },
      }),
    ]);

    return this.mapToVersion(newVersion);
  }

  async compareVersions(
    input: VersionServiceCompareInput
  ): Promise<VersionDiff> {
    const { documentId, versionFrom, versionTo, ignoreWhitespace = false } =
      input;

    const [from, to] = await Promise.all([
      this.prisma.documentVersion.findFirst({
        where: {
          documentId,
          version: versionFrom,
        },
        include: {
          createdBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
        },
      }),
      this.prisma.documentVersion.findFirst({
        where: {
          documentId,
          version: versionTo,
        },
        include: {
          createdBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
        },
      }),
    ]);

    if (!from || !to) {
      throw new Error('版本不存在');
    }

    const contentDiff = compareLinesToChunks(from.content, to.content, {
      ignoreWhitespace,
      includeCharDiff: true,
      contextLines: 3,
    });

    const titleDiff = compareWords(from.title, to.title);

    const stats = generateDiffStats(from.content, to.content);

    return {
      versionFrom: {
        id: from.id,
        version: from.version,
        title: from.title,
        createdAt: from.createdAt,
        createdById: from.createdById,
        createdBy: from.createdBy
          ? {
              id: from.createdBy.id,
              name: from.createdBy.name,
              email: from.createdBy.email,
              avatar: from.createdBy.avatar,
            }
          : undefined,
      },
      versionTo: {
        id: to.id,
        version: to.version,
        title: to.title,
        createdAt: to.createdAt,
        createdById: to.createdById,
        createdBy: to.createdBy
          ? {
              id: to.createdBy.id,
              name: to.createdBy.name,
              email: to.createdBy.email,
              avatar: to.createdBy.avatar,
            }
          : undefined,
      },
      contentDiff,
      titleDiff,
      stats,
    };
  }

  async restoreVersion(
    documentId: string,
    createdById: string
  ): Promise<Version> {
    const document = await this.prisma.document.findUnique({
      where: { id: documentId },
      select: {
        id: true,
        title: true,
        content: true,
        contentHtml: true,
        deletedAt: true,
        _count: {
          select: { versions: true },
        },
      },
    });

    if (!document) {
      throw new Error('文档不存在');
    }

    if (!document.deletedAt) {
      throw new Error('文档未被删除');
    }

    const nextVersion = document._count.versions + 1;

    const [version] = await this.prisma.$transaction([
      this.prisma.documentVersion.create({
        data: {
          documentId,
          title: document.title,
          content: document.content,
          contentHtml: document.contentHtml,
          version: nextVersion,
          message: '恢复已删除文档',
          changeSummary: '文档已从回收站恢复',
          createdById,
        },
        include: {
          createdBy: {
            select: {
              id: true,
              name: true,
              email: true,
              avatar: true,
            },
          },
        },
      }),
      this.prisma.document.update({
        where: { id: documentId },
        data: {
          deletedAt: null,
          isArchived: false,
          version: nextVersion,
        },
      }),
    ]);

    return this.mapToVersion(version);
  }

  async cleanupOldVersions(
    input: VersionServiceCleanupInput
  ): Promise<number> {
    const { documentId, keepCount } = input;

    if (keepCount < 1) {
      throw new Error('保留版本数不能小于1');
    }

    const total = await this.prisma.documentVersion.count({
      where: { documentId },
    });

    if (total <= keepCount) {
      return 0;
    }

    const toDeleteCount = total - keepCount;

    const versionsToDelete = await this.prisma.documentVersion.findMany({
      where: { documentId },
      orderBy: { version: 'asc' },
      take: toDeleteCount,
      select: { id: true },
    });

    await this.prisma.documentVersion.deleteMany({
      where: {
        id: { in: versionsToDelete.map((v) => v.id) },
      },
    });

    return versionsToDelete.length;
  }

  generateVersionSummary(stats: DiffStats): string {
    const parts: string[] = [];
    if (stats.added > 0) {
      parts.push(`新增 ${stats.added} 行`);
    }
    if (stats.removed > 0) {
      parts.push(`删除 ${stats.removed} 行`);
    }
    if (stats.modified > 0) {
      parts.push(`修改 ${stats.modified} 行`);
    }
    if (parts.length === 0) {
      return '无内容变更';
    }
    return parts.join('，');
  }

  async getVersionSummary(
    documentId: string,
    versionNumber: number
  ): Promise<VersionSummary | null> {
    const version = await this.prisma.documentVersion.findFirst({
      where: {
        documentId,
        version: versionNumber,
      },
      include: {
        createdBy: {
          select: {
            id: true,
            name: true,
            avatar: true,
          },
        },
      },
    });

    if (!version) {
      return null;
    }

    let stats: DiffStats = {
      added: 0,
      removed: 0,
      modified: 0,
      unchanged: 0,
      total: 0,
    };

    if (version.version > 1) {
      const previous = await this.prisma.documentVersion.findFirst({
        where: {
          documentId,
          version: version.version - 1,
        },
        select: { content: true },
      });

      if (previous) {
        stats = generateDiffStats(previous.content, version.content);
      }
    }

    return {
      version: version.version,
      title: version.title,
      message: version.message,
      createdAt: version.createdAt,
      createdBy: {
        id: version.createdBy.id,
        name: version.createdBy.name,
        avatar: version.createdBy.avatar,
      },
      stats,
    };
  }

  private mapToVersion(prismaVersion: {
    id: string;
    documentId: string;
    title: string;
    content: string;
    contentHtml: string | null;
    version: number;
    message: string | null;
    createdById: string;
    createdAt: Date;
    createdBy?: {
      id: string;
      name: string;
      email: string;
      avatar: string | null;
    } | null;
  }): Version {
    return {
      id: prismaVersion.id,
      documentId: prismaVersion.documentId,
      title: prismaVersion.title,
      content: prismaVersion.content,
      contentHtml: prismaVersion.contentHtml,
      version: prismaVersion.version,
      message: prismaVersion.message,
      createdById: prismaVersion.createdById,
      createdAt: prismaVersion.createdAt,
      createdBy: prismaVersion.createdBy
        ? {
            id: prismaVersion.createdBy.id,
            name: prismaVersion.createdBy.name,
            email: prismaVersion.createdBy.email,
            avatar: prismaVersion.createdBy.avatar,
          }
        : undefined,
    };
  }
}
