import { TRPCError, middleware } from '@trpc/server';
import { PermissionService } from '../services/PermissionService';
import type { Role, SpacePermission, DocumentPermission, CommentPermission, PermissionContext } from '@/lib/types/permission';
import type { TRPCContext } from '../trpc';

interface PermissionMiddlewareOptions {
  requireRole?: Role;
  requirePermission?: {
    permission: SpacePermission | DocumentPermission | CommentPermission;
    type: 'space' | 'document' | 'comment';
  };
  checkMembership?: boolean;
}

export function createPermissionMiddleware(prisma: any) {
  const permissionService = new PermissionService(prisma);

  return middleware(async ({ ctx, next, input, path }) => {
    const { user } = ctx;
    if (!user) {
      throw new TRPCError({
        code: 'UNAUTHORIZED',
        message: '未登录',
      });
    }

    const spaceId = extractSpaceId(input, path);
    if (!spaceId) {
      return next();
    }

    const options = getPermissionOptions(path);

    if (options.checkMembership !== false) {
      const isMember = await permissionService.checkSpaceMember(user.id, spaceId);
      if (!isMember) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: '不是空间成员',
        });
      }
    }

    if (options.requireRole) {
      const hasRole = await permissionService.checkRole(user.id, spaceId, options.requireRole);
      if (!hasRole) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: `需要 ${options.requireRole} 角色`,
        });
      }
    }

    if (options.requirePermission) {
      const hasPerm = await permissionService.checkPermission(
        user.id,
        spaceId,
        options.requirePermission.permission,
        options.requirePermission.type
      );
      if (!hasPerm) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: `需要 ${options.requirePermission.permission} 权限`,
        });
      }
    }

    const userRole = await permissionService.getHighestRole(user.id, spaceId);

    return next({
      ctx: {
        ...ctx,
        user,
        spaceId,
        userRole,
        permissionService,
      },
    });
  });
}

function extractSpaceId(input: any, path: string): string | null {
  if (!input || typeof input !== 'object') return null;

  if ('spaceId' in input && typeof input.spaceId === 'string') {
    return input.spaceId;
  }

  if ('id' in input && typeof input.id === 'string' && path.startsWith('space.')) {
    return input.id;
  }

  if ('space' in input && typeof input.space === 'object' && input.space?.id) {
    return input.space.id;
  }

  return null;
}

const permissionConfig: Record<string, PermissionMiddlewareOptions> = {
  'space.update': { requireRole: 'ADMIN' },
  'space.delete': { requireRole: 'OWNER' },
  'space.addMember': { requireRole: 'ADMIN' },
  'space.updateMemberRole': { requireRole: 'ADMIN' },
  'space.removeMember': { requireRole: 'ADMIN' },
  'space.setPassword': { requireRole: 'ADMIN' },
  'space.createShareLink': { requireRole: 'ADMIN' },
  'space.revokeShareLink': { requireRole: 'ADMIN' },
  'space.listShareLinks': { requireRole: 'ADMIN' },
  'document.create': { requirePermission: { permission: 'create', type: 'document' } },
  'document.update': { requirePermission: { permission: 'edit', type: 'document' } },
  'document.delete': { requirePermission: { permission: 'delete', type: 'document' } },
  'review.create': { requirePermission: { permission: 'review', type: 'document' } },
  'comment.create': { requirePermission: { permission: 'create', type: 'comment' } },
  'comment.resolve': { requirePermission: { permission: 'resolve', type: 'comment' } },
  'comment.delete': { requirePermission: { permission: 'delete', type: 'comment' } },
};

function getPermissionOptions(path: string): PermissionMiddlewareOptions {
  const options = permissionConfig[path];
  return options || { checkMembership: true };
}

export function requireRole(role: Role) {
  return middleware(async ({ ctx, next, input }) => {
    const { user, prisma } = ctx as TRPCContext & { prisma: any };
    if (!user) {
      throw new TRPCError({
        code: 'UNAUTHORIZED',
        message: '未登录',
      });
    }

    const spaceId = extractSpaceId(input, '');
    if (!spaceId) {
      return next();
    }

    const permissionService = new PermissionService(prisma);
    const hasRole = await permissionService.checkRole(user.id, spaceId, role);

    if (!hasRole) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: `需要 ${role} 角色`,
      });
    }

    return next();
  });
}

export function requirePermission(
  permission: SpacePermission | DocumentPermission | CommentPermission,
  type: 'space' | 'document' | 'comment' = 'space'
) {
  return middleware(async ({ ctx, next, input }) => {
    const { user, prisma } = ctx as TRPCContext & { prisma: any };
    if (!user) {
      throw new TRPCError({
        code: 'UNAUTHORIZED',
        message: '未登录',
      });
    }

    const spaceId = extractSpaceId(input, '');
    if (!spaceId) {
      return next();
    }

    const permissionService = new PermissionService(prisma);
    const hasPerm = await permissionService.checkPermission(user.id, spaceId, permission, type);

    if (!hasPerm) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: `需要 ${permission} 权限`,
      });
    }

    return next();
  });
}

export function requireSpaceMember() {
  return middleware(async ({ ctx, next, input }) => {
    const { user, prisma } = ctx as TRPCContext & { prisma: any };
    if (!user) {
      throw new TRPCError({
        code: 'UNAUTHORIZED',
        message: '未登录',
      });
    }

    const spaceId = extractSpaceId(input, '');
    if (!spaceId) {
      return next();
    }

    const permissionService = new PermissionService(prisma);
    const isMember = await permissionService.checkSpaceMember(user.id, spaceId);

    if (!isMember) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '不是空间成员',
      });
    }

    return next();
  });
}

export interface PermissionContextExtended extends TRPCContext {
  user: NonNullable<TRPCContext['user']>;
  spaceId?: string;
  userRole?: Role;
  permissionService?: PermissionService;
}
