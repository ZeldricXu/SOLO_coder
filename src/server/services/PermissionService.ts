import type { PrismaClient } from '@prisma/client';
import { TRPCError } from '@trpc/server';
import {
  hasRole,
  hasSpacePermission,
  hasDocumentPermission,
  hasCommentPermission,
  canEditRoles,
  canManageRoles,
  canShareRoles,
  ROLE_HIERARCHY,
  ROLES,
} from '@/lib/constants/permissions';
import type {
  Role,
  SpacePermission,
  DocumentPermission,
  CommentPermission,
  PermissionCheckOptions,
  PermissionContext,
} from '@/lib/types/permission';

export class PermissionService {
  private prisma: PrismaClient;

  constructor(prisma: PrismaClient) {
    this.prisma = prisma;
  }

  async checkSpaceMember(userId: string, spaceId: string): Promise<boolean> {
    const member = await this.prisma.spaceMember.findFirst({
      where: {
        userId,
        spaceId,
      },
      select: { id: true },
    });
    return !!member;
  }

  async checkRole(userId: string, spaceId: string, requiredRole: Role): Promise<boolean> {
    const member = await this.prisma.spaceMember.findFirst({
      where: {
        userId,
        spaceId,
      },
      select: { role: true },
    });

    if (!member) return false;
    return hasRole(requiredRole, member.role as Role);
  }

  async checkPermission(
    userId: string,
    spaceId: string,
    permission: SpacePermission | DocumentPermission | CommentPermission,
    permissionType: 'space' | 'document' | 'comment' = 'space'
  ): Promise<boolean> {
    const member = await this.prisma.spaceMember.findFirst({
      where: {
        userId,
        spaceId,
      },
      select: { role: true },
    });

    if (!member) return false;
    const role = member.role as Role;

    switch (permissionType) {
      case 'space':
        return hasSpacePermission(permission as SpacePermission, role);
      case 'document':
        return hasDocumentPermission(permission as DocumentPermission, role);
      case 'comment':
        return hasCommentPermission(permission as CommentPermission, role);
      default:
        return false;
    }
  }

  requireRole(requiredRole: Role) {
    return async (ctx: PermissionContext) => {
      const hasPermission = await this.checkRole(ctx.userId, ctx.spaceId, requiredRole);
      if (!hasPermission) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: `需要 ${requiredRole} 角色`,
        });
      }
    };
  }

  requirePermission(
    permission: SpacePermission | DocumentPermission | CommentPermission,
    permissionType: 'space' | 'document' | 'comment' = 'space'
  ) {
    return async (ctx: PermissionContext) => {
      const hasPerm = await this.checkPermission(ctx.userId, ctx.spaceId, permission, permissionType);
      if (!hasPerm) {
        throw new TRPCError({
          code: 'FORBIDDEN',
          message: `需要 ${permission} 权限`,
        });
      }
    };
  }

  async getHighestRole(userId: string, spaceId: string): Promise<Role | null> {
    const member = await this.prisma.spaceMember.findFirst({
      where: {
        userId,
        spaceId,
      },
      select: { role: true },
    });

    return member ? (member.role as Role) : null;
  }

  async canEditDocument(userId: string, spaceId: string, documentId: string): Promise<boolean> {
    const member = await this.prisma.spaceMember.findFirst({
      where: {
        userId,
        spaceId,
      },
      select: { role: true },
    });

    if (!member) return false;
    const role = member.role as Role;

    return canEditRoles.includes(role);
  }

  async canManageSpace(userId: string, spaceId: string): Promise<boolean> {
    const member = await this.prisma.spaceMember.findFirst({
      where: {
        userId,
        spaceId,
      },
      select: { role: true },
    });

    if (!member) return false;
    const role = member.role as Role;

    return canManageRoles.includes(role);
  }

  async canShareSpace(userId: string, spaceId: string): Promise<boolean> {
    const member = await this.prisma.spaceMember.findFirst({
      where: {
        userId,
        spaceId,
      },
      select: { role: true },
    });

    if (!member) return false;
    const role = member.role as Role;

    return canShareRoles.includes(role);
  }

  async getUserRole(userId: string, spaceId: string): Promise<Role | null> {
    const member = await this.prisma.spaceMember.findFirst({
      where: {
        userId,
        spaceId,
      },
      select: { role: true },
    });

    return member ? (member.role as Role) : null;
  }

  async checkMultiplePermissions(
    userId: string,
    spaceId: string,
    permissions: (SpacePermission | DocumentPermission | CommentPermission)[],
    options: PermissionCheckOptions = {}
  ): Promise<boolean> {
    const { requireAll = true } = options;
    const member = await this.prisma.spaceMember.findFirst({
      where: {
        userId,
        spaceId,
      },
      select: { role: true },
    });

    if (!member) return false;
    const role = member.role as Role;

    const results = permissions.map(perm =>
      hasSpacePermission(perm as SpacePermission, role)
    );

    return requireAll ? results.every(r => r) : results.some(r => r);
  }

  compareRoles(roleA: Role, roleB: Role): number {
    return ROLE_HIERARCHY[roleA] - ROLE_HIERARCHY[roleB];
  }

  getHigherRole(roleA: Role, roleB: Role): Role {
    return this.compareRoles(roleA, roleB) >= 0 ? roleA : roleB;
  }

  getLowerRole(roleA: Role, roleB: Role): Role {
    return this.compareRoles(roleA, roleB) <= 0 ? roleA : roleB;
  }

  getRolesAbove(role: Role): Role[] {
    const threshold = ROLE_HIERARCHY[role];
    return ROLES.filter(r => ROLE_HIERARCHY[r] > threshold) as Role[];
  }

  getRolesBelow(role: Role): Role[] {
    const threshold = ROLE_HIERARCHY[role];
    return ROLES.filter(r => ROLE_HIERARCHY[r] < threshold) as Role[];
  }

  async ensureSpaceMember(userId: string, spaceId: string): Promise<void> {
    const isMember = await this.checkSpaceMember(userId, spaceId);
    if (!isMember) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '不是空间成员',
      });
    }
  }

  async ensureCanManageSpace(userId: string, spaceId: string): Promise<void> {
    const canManage = await this.canManageSpace(userId, spaceId);
    if (!canManage) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '无空间管理权限',
      });
    }
  }

  async ensureCanShareSpace(userId: string, spaceId: string): Promise<void> {
    const canShare = await this.canShareSpace(userId, spaceId);
    if (!canShare) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '无空间分享权限',
      });
    }
  }

  async ensureCanEditDocument(userId: string, spaceId: string, documentId: string): Promise<void> {
    const canEdit = await this.canEditDocument(userId, spaceId, documentId);
    if (!canEdit) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '无文档编辑权限',
      });
    }
  }
}
