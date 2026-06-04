import { z } from 'zod';
import { TRPCError } from '@trpc/server';
import { router, protectedProcedure, publicProcedure } from '../trpc';
import { SpaceService } from '../services/SpaceService';
import { PermissionService } from '../services/PermissionService';

const SpaceRoleSchema = z.enum(['OWNER', 'ADMIN', 'EDITOR', 'VIEWER']);
const SpaceVisibilitySchema = z.enum(['PRIVATE', 'PUBLIC']);

export const spaceRouter = router({
  create: protectedProcedure
    .input(
      z.object({
        name: z.string().min(1, '空间名称不能为空').max(100, '空间名称最多100个字符'),
        description: z.string().max(500, '描述最多500个字符').optional(),
        icon: z.string().optional(),
        color: z.string().optional(),
        visibility: SpaceVisibilitySchema.default('PRIVATE'),
        password: z.string().min(6, '密码至少6个字符').optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.createSpace(input, ctx.user.id);
    }),

  list: protectedProcedure
    .input(
      z.object({
        page: z.number().min(1).default(1),
        pageSize: z.number().min(1).max(100).default(20),
        search: z.string().optional(),
        role: SpaceRoleSchema.optional(),
      })
    )
    .query(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.listSpaces(ctx.user.id, input);
    }),

  getById: protectedProcedure
    .input(
      z.object({
        id: z.string(),
      })
    )
    .query(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.getSpaceById(input.id, ctx.user.id);
    }),

  update: protectedProcedure
    .input(
      z.object({
        id: z.string(),
        name: z.string().min(1, '空间名称不能为空').max(100, '空间名称最多100个字符').optional(),
        description: z.string().max(500, '描述最多500个字符').optional(),
        icon: z.string().optional(),
        color: z.string().optional(),
        visibility: SpaceVisibilitySchema.optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.updateSpace(input, ctx.user.id);
    }),

  delete: protectedProcedure
    .input(
      z.object({
        id: z.string(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.deleteSpace(input.id, ctx.user.id);
    }),

  addMember: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
        email: z.string().email('邮箱格式不正确'),
        role: SpaceRoleSchema.default('VIEWER'),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const user = await ctx.prisma.user.findUnique({
        where: { email: input.email },
        select: { id: true },
      });

      if (!user) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '用户不存在',
        });
      }

      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.addMember(
        {
          spaceId: input.spaceId,
          userId: user.id,
          role: input.role,
        },
        ctx.user.id
      );
    }),

  updateMemberRole: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
        userId: z.string(),
        role: SpaceRoleSchema,
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.updateMemberRole(input, ctx.user.id);
    }),

  removeMember: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
        userId: z.string(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.removeMember(input, ctx.user.id);
    }),

  listMembers: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
      })
    )
    .query(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.listMembers(input.spaceId, ctx.user.id);
    }),

  setPassword: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
        password: z.string().min(6, '密码至少6个字符'),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.setSpacePassword(input, ctx.user.id);
    }),

  verifyPassword: publicProcedure
    .input(
      z.object({
        spaceId: z.string(),
        password: z.string(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      const valid = await spaceService.verifySpacePassword(input);
      return { valid };
    }),

  createShareLink: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
        password: z.string().min(6).optional(),
        expiresAt: z.date().optional(),
        role: SpaceRoleSchema.default('VIEWER'),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.createShareLink(input, ctx.user.id);
    }),

  revokeShareLink: protectedProcedure
    .input(
      z.object({
        shareLinkId: z.string(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.revokeShareLink(input.shareLinkId, ctx.user.id);
    }),

  listShareLinks: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
      })
    )
    .query(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.listShareLinks(input.spaceId, ctx.user.id);
    }),

  validateShareLink: publicProcedure
    .input(
      z.object({
        token: z.string(),
        password: z.string().optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const spaceService = new SpaceService(ctx.prisma);
      return spaceService.validateShareLink(input.token, input.password);
    }),

  getUserRole: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
      })
    )
    .query(async ({ ctx, input }) => {
      const permissionService = new PermissionService(ctx.prisma);
      const role = await permissionService.getHighestRole(ctx.user.id, input.spaceId);
      return { role };
    }),

  checkPermission: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
        permission: z.enum(['view', 'edit', 'manage', 'share', 'delete', 'create', 'review', 'resolve']),
        type: z.enum(['space', 'document', 'comment']).default('space'),
      })
    )
    .query(async ({ ctx, input }) => {
      const permissionService = new PermissionService(ctx.prisma);
      const hasPermission = await permissionService.checkPermission(
        ctx.user.id,
        input.spaceId,
        input.permission,
        input.type
      );
      return { hasPermission };
    }),

  setAiQaEnabled: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
        enabled: z.boolean(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const permissionService = new PermissionService(ctx.prisma);
      const hasManagePermission = await permissionService.checkPermission(
        ctx.user.id,
        input.spaceId,
        'manage',
        'space'
      );

      if (!hasManagePermission) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '只有空间管理员才能修改AI问答设置',
        });
      }

      const space = await ctx.prisma.space.update({
        where: { id: input.spaceId },
        data: {
          aiQaEnabled: input.enabled,
        },
        select: {
          id: true,
          name: true,
          aiQaEnabled: true,
        },
      });

      return {
        success: true,
        data: space,
      };
    }),

  getAiQaStatus: protectedProcedure
    .input(
      z.object({
        spaceId: z.string(),
      })
    )
    .query(async ({ ctx, input }) => {
      const space = await ctx.prisma.space.findUnique({
        where: {
          id: input.spaceId,
          members: {
            some: {
              userId: ctx.user.id,
            },
          },
        },
        select: {
          id: true,
          aiQaEnabled: true,
        },
      });

      if (!space) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '空间不存在或无访问权限',
        });
      }

      return {
        success: true,
        data: {
          aiQaEnabled: space.aiQaEnabled,
        },
      };
    }),
});
