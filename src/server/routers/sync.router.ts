import { z } from 'zod';
import { TRPCError } from '@trpc/server';
import { router, protectedProcedure } from '../trpc';

const SyncTypeSchema = z.enum(['NOTION', 'CONFLUENCE', 'GITBOOK', 'OBSIDIAN', 'WEBHOOK', 'API']);
const SyncStatusSchema = z.enum(['IDLE', 'RUNNING', 'SUCCESS', 'FAILED', 'PAUSED']);
const SyncFrequencySchema = z.enum(['MANUAL', 'HOURLY', 'DAILY', 'WEEKLY']);

export const syncRouter = router({
  create: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        type: SyncTypeSchema,
        name: z.string().min(1, '同步名称不能为空').max(100, '同步名称最多100个字符'),
        description: z.string().max(500, '描述最多500个字符').optional(),
        config: z.record(z.any()),
        frequency: SyncFrequencySchema.default('MANUAL'),
        enabled: z.boolean().default(true),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: input.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限创建同步配置',
        });
      }

      const syncConfig = await ctx.prisma.syncConfig.create({
        data: {
          spaceId: input.spaceId,
          type: input.type,
          name: input.name,
          description: input.description,
          config: input.config,
          frequency: input.frequency,
          enabled: input.enabled,
          status: 'IDLE',
          createdById: ctx.user.id,
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
          space: {
            select: {
              id: true,
              name: true,
              icon: true,
            },
          },
          _count: {
            select: {
              logs: true,
            },
          },
        },
      });

      return syncConfig;
    }),

  list: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid().optional(),
        type: SyncTypeSchema.optional(),
        status: SyncStatusSchema.optional(),
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(100).default(20),
      })
    )
    .query(async ({ ctx, input }) => {
      const where = {
        ...(input.spaceId ? { spaceId: input.spaceId } : {}),
        ...(input.type ? { type: input.type } : {}),
        ...(input.status ? { status: input.status } : {}),
        space: {
          members: {
            some: {
              userId: ctx.user.id,
            },
          },
        },
      };

      const skip = (input.page - 1) * input.pageSize;

      const [syncConfigs, total] = await Promise.all([
        ctx.prisma.syncConfig.findMany({
          where,
          skip,
          take: input.pageSize,
          include: {
            createdBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
            space: {
              select: {
                id: true,
                name: true,
                icon: true,
              },
            },
            _count: {
              select: {
                logs: true,
              },
            },
          },
          orderBy: {
            updatedAt: 'desc',
          },
        }),
        ctx.prisma.syncConfig.count({ where }),
      ]);

      return {
        items: syncConfigs,
        total,
        page: input.page,
        pageSize: input.pageSize,
        totalPages: Math.ceil(total / input.pageSize),
      };
    }),

  update: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
        name: z.string().min(1, '同步名称不能为空').max(100, '同步名称最多100个字符').optional(),
        description: z.string().max(500, '描述最多500个字符').optional(),
        config: z.record(z.any()).optional(),
        frequency: SyncFrequencySchema.optional(),
        enabled: z.boolean().optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const syncConfig = await ctx.prisma.syncConfig.findUnique({
        where: { id: input.id },
        select: {
          id: true,
          spaceId: true,
          createdById: true,
        },
      });

      if (!syncConfig) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '同步配置不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: syncConfig.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN'] },
        },
      });

      if (!membership && syncConfig.createdById !== ctx.user.id) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限修改此同步配置',
        });
      }

      const updatedSyncConfig = await ctx.prisma.syncConfig.update({
        where: { id: syncConfig.id },
        data: {
          ...(input.name !== undefined ? { name: input.name } : {}),
          ...(input.description !== undefined ? { description: input.description } : {}),
          ...(input.config !== undefined ? { config: input.config } : {}),
          ...(input.frequency !== undefined ? { frequency: input.frequency } : {}),
          ...(input.enabled !== undefined ? { enabled: input.enabled } : {}),
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
          space: {
            select: {
              id: true,
              name: true,
              icon: true,
            },
          },
          _count: {
            select: {
              logs: true,
            },
          },
        },
      });

      return updatedSyncConfig;
    }),

  delete: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const syncConfig = await ctx.prisma.syncConfig.findUnique({
        where: { id: input.id },
        select: {
          id: true,
          spaceId: true,
          createdById: true,
        },
      });

      if (!syncConfig) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '同步配置不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: syncConfig.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN'] },
        },
      });

      if (!membership && syncConfig.createdById !== ctx.user.id) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限删除此同步配置',
        });
      }

      await ctx.prisma.syncConfig.delete({
        where: { id: syncConfig.id },
      });

      return { success: true };
    }),

  triggerSync: protectedProcedure
    .input(
      z.object({
        id: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const syncConfig = await ctx.prisma.syncConfig.findUnique({
        where: { id: input.id },
        select: {
          id: true,
          spaceId: true,
          enabled: true,
          status: true,
        },
      });

      if (!syncConfig) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '同步配置不存在',
        });
      }

      const membership = await ctx.prisma.spaceMember.findFirst({
        where: {
          spaceId: syncConfig.spaceId,
          userId: ctx.user.id,
          role: { in: ['OWNER', 'ADMIN', 'EDITOR'] },
        },
      });

      if (!membership) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '无权限触发同步',
        });
      }

      if (!syncConfig.enabled) {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '同步配置已被禁用',
        });
      }

      if (syncConfig.status === 'RUNNING') {
        throw new TRPCError({
          code: 'BAD_REQUEST',
          message: '同步正在进行中',
        });
      }

      await ctx.prisma.syncConfig.update({
        where: { id: syncConfig.id },
        data: {
          status: 'RUNNING',
          lastRunAt: new Date(),
        },
      });

      const syncLog = await ctx.prisma.syncLog.create({
        data: {
          syncConfigId: syncConfig.id,
          status: 'RUNNING',
          startedAt: new Date(),
          triggeredById: ctx.user.id,
        },
      });

      return {
        syncConfigId: syncConfig.id,
        syncLogId: syncLog.id,
        status: 'RUNNING',
      };
    }),

  getSyncLogs: protectedProcedure
    .input(
      z.object({
        syncConfigId: z.string().cuid(),
        status: SyncStatusSchema.optional(),
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(100).default(20),
      })
    )
    .query(async ({ ctx, input }) => {
      const syncConfig = await ctx.prisma.syncConfig.findUnique({
        where: {
          id: input.syncConfigId,
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
        },
        select: {
          id: true,
        },
      });

      if (!syncConfig) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '同步配置不存在或无权限访问',
        });
      }

      const skip = (input.page - 1) * input.pageSize;

      const where = {
        syncConfigId: syncConfig.id,
        ...(input.status ? { status: input.status } : {}),
      };

      const [logs, total] = await Promise.all([
        ctx.prisma.syncLog.findMany({
          where,
          skip,
          take: input.pageSize,
          include: {
            triggeredBy: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
          },
          orderBy: {
            startedAt: 'desc',
          },
        }),
        ctx.prisma.syncLog.count({ where }),
      ]);

      return {
        items: logs,
        total,
        page: input.page,
        pageSize: input.pageSize,
        totalPages: Math.ceil(total / input.pageSize),
      };
    }),

  getSyncStatus: protectedProcedure
    .input(
      z.object({
        syncConfigId: z.string().cuid(),
      })
    )
    .query(async ({ ctx, input }) => {
      const syncConfig = await ctx.prisma.syncConfig.findUnique({
        where: {
          id: input.syncConfigId,
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
        },
        include: {
          _count: {
            select: {
              logs: true,
            },
          },
        },
      });

      if (!syncConfig) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '同步配置不存在或无权限访问',
        });
      }

      const lastSuccessfulLog = await ctx.prisma.syncLog.findFirst({
        where: {
          syncConfigId: syncConfig.id,
          status: 'SUCCESS',
        },
        orderBy: {
          startedAt: 'desc',
        },
        select: {
          id: true,
          startedAt: true,
          completedAt: true,
          recordsSynced: true,
          recordsFailed: true,
          errorMessage: true,
        },
      });

      const lastLog = await ctx.prisma.syncLog.findFirst({
        where: {
          syncConfigId: syncConfig.id,
        },
        orderBy: {
          startedAt: 'desc',
        },
        select: {
          id: true,
          status: true,
          startedAt: true,
          completedAt: true,
          recordsSynced: true,
          recordsFailed: true,
          errorMessage: true,
          triggeredBy: {
            select: {
              id: true,
              name: true,
              avatar: true,
            },
          },
        },
      });

      return {
        syncConfig: {
          id: syncConfig.id,
          name: syncConfig.name,
          type: syncConfig.type,
          status: syncConfig.status,
          enabled: syncConfig.enabled,
          frequency: syncConfig.frequency,
          lastRunAt: syncConfig.lastRunAt,
          createdAt: syncConfig.createdAt,
          updatedAt: syncConfig.updatedAt,
        },
        lastLog,
        lastSuccessfulLog,
        totalLogs: syncConfig._count.logs,
      };
    }),
});
