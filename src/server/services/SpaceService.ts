import type { PrismaClient } from '@prisma/client';
import { TRPCError } from '@trpc/server';
import bcrypt from 'bcryptjs';
import { nanoid } from 'nanoid';
import { PermissionService } from './PermissionService';
import type {
  CreateSpaceInput,
  UpdateSpaceInput,
  AddMemberInput,
  UpdateMemberRoleInput,
  RemoveMemberInput,
  SetSpacePasswordInput,
  VerifySpacePasswordInput,
  CreateShareLinkInput,
  SpaceWithOwner,
  SpaceWithMembers,
  SpaceMember,
  SpaceShareLink,
  SpaceListFilter,
  PaginatedSpaces,
  ValidateShareLinkResult,
} from '@/lib/types/space';
import type { Role } from '@/lib/types/permission';
import type { SpaceVisibility } from '@/lib/types/space';

export class SpaceService {
  private prisma: PrismaClient;
  private permissionService: PermissionService;

  constructor(prisma: PrismaClient) {
    this.prisma = prisma;
    this.permissionService = new PermissionService(prisma);
  }

  async createSpace(input: CreateSpaceInput, userId: string): Promise<SpaceWithOwner> {
    const passwordHash = input.password
      ? await bcrypt.hash(input.password, 12)
      : null;

    const space = await this.prisma.space.create({
      data: {
        name: input.name,
        description: input.description ?? null,
        icon: input.icon ?? null,
        color: input.color ?? null,
        visibility: (input.visibility ?? 'PRIVATE') as SpaceVisibility,
        passwordHash,
        createdById: userId,
        members: {
          create: {
            userId,
            role: 'OWNER' as Role,
          },
        },
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
        _count: {
          select: {
            members: true,
            documents: true,
          },
        },
      },
    });

    return space as unknown as SpaceWithOwner;
  }

  async updateSpace(input: UpdateSpaceInput, userId: string): Promise<SpaceWithOwner> {
    await this.permissionService.ensureCanManageSpace(userId, input.id);

    const space = await this.prisma.space.update({
      where: { id: input.id },
      data: {
        ...(input.name !== undefined ? { name: input.name } : {}),
        ...(input.description !== undefined ? { description: input.description } : {}),
        ...(input.icon !== undefined ? { icon: input.icon } : {}),
        ...(input.color !== undefined ? { color: input.color } : {}),
        ...(input.visibility !== undefined ? { visibility: input.visibility as SpaceVisibility } : {}),
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
        _count: {
          select: {
            members: true,
            documents: true,
          },
        },
      },
    });

    return space as unknown as SpaceWithOwner;
  }

  async deleteSpace(spaceId: string, userId: string): Promise<{ success: boolean }> {
    const isOwner = await this.permissionService.checkRole(userId, spaceId, 'OWNER');
    if (!isOwner) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '只有空间所有者可以删除空间',
      });
    }

    await this.prisma.space.delete({
      where: { id: spaceId },
    });

    return { success: true };
  }

  async addMember(input: AddMemberInput, currentUserId: string): Promise<SpaceMember> {
    await this.permissionService.ensureCanManageSpace(currentUserId, input.spaceId);

    const existingMember = await this.prisma.spaceMember.findFirst({
      where: {
        spaceId: input.spaceId,
        userId: input.userId,
      },
    });

    if (existingMember) {
      throw new TRPCError({
        code: 'CONFLICT',
        message: '该用户已是空间成员',
      });
    }

    const targetUser = await this.prisma.user.findUnique({
      where: { id: input.userId },
      select: { id: true, name: true, email: true, avatar: true },
    });

    if (!targetUser) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '用户不存在',
      });
    }

    const member = await this.prisma.spaceMember.create({
      data: {
        spaceId: input.spaceId,
        userId: input.userId,
        role: (input.role ?? 'VIEWER') as Role,
      },
      include: {
        user: {
          select: {
            id: true,
            name: true,
            email: true,
            avatar: true,
          },
        },
      },
    });

    return member as unknown as SpaceMember;
  }

  async updateMemberRole(input: UpdateMemberRoleInput, currentUserId: string): Promise<SpaceMember> {
    await this.permissionService.ensureCanManageSpace(currentUserId, input.spaceId);

    const targetMember = await this.prisma.spaceMember.findFirst({
      where: {
        spaceId: input.spaceId,
        userId: input.userId,
      },
    });

    if (!targetMember) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '该用户不是空间成员',
      });
    }

    if (targetMember.role === 'OWNER') {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '不能修改所有者的角色',
      });
    }

    if (input.role === 'OWNER') {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '不能将其他用户设为所有者',
      });
    }

    const currentUserRole = await this.permissionService.getHighestRole(currentUserId, input.spaceId);
    const targetUserRole = targetMember.role as Role;

    if (currentUserRole && this.permissionService.compareRoles(currentUserRole, targetUserRole) <= 0) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '无权限修改该成员的角色',
      });
    }

    const member = await this.prisma.spaceMember.update({
      where: { id: targetMember.id },
      data: { role: input.role as Role },
      include: {
        user: {
          select: {
            id: true,
            name: true,
            email: true,
            avatar: true,
          },
        },
      },
    });

    return member as unknown as SpaceMember;
  }

  async removeMember(input: RemoveMemberInput, currentUserId: string): Promise<{ success: boolean }> {
    await this.permissionService.ensureCanManageSpace(currentUserId, input.spaceId);

    const targetMember = await this.prisma.spaceMember.findFirst({
      where: {
        spaceId: input.spaceId,
        userId: input.userId,
      },
    });

    if (!targetMember) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '该用户不是空间成员',
      });
    }

    if (targetMember.role === 'OWNER') {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '不能移除空间所有者',
      });
    }

    if (input.userId === currentUserId) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '不能移除自己',
      });
    }

    await this.prisma.spaceMember.delete({
      where: { id: targetMember.id },
    });

    return { success: true };
  }

  async listMembers(spaceId: string, userId: string): Promise<SpaceMember[]> {
    await this.permissionService.ensureSpaceMember(userId, spaceId);

    const members = await this.prisma.spaceMember.findMany({
      where: { spaceId },
      include: {
        user: {
          select: {
            id: true,
            name: true,
            email: true,
            avatar: true,
          },
        },
      },
      orderBy: [
        { role: 'asc' },
        { joinedAt: 'asc' },
      ],
    });

    return members as unknown as SpaceMember[];
  }

  async setSpacePassword(input: SetSpacePasswordInput, userId: string): Promise<{ success: boolean }> {
    await this.permissionService.ensureCanManageSpace(userId, input.spaceId);

    const passwordHash = await bcrypt.hash(input.password, 12);

    await this.prisma.space.update({
      where: { id: input.spaceId },
      data: { passwordHash },
    });

    return { success: true };
  }

  async verifySpacePassword(input: VerifySpacePasswordInput): Promise<boolean> {
    const space = await this.prisma.space.findUnique({
      where: { id: input.spaceId },
      select: { passwordHash: true },
    });

    if (!space || !space.passwordHash) {
      return true;
    }

    return bcrypt.compare(input.password, space.passwordHash);
  }

  async createShareLink(input: CreateShareLinkInput, userId: string): Promise<SpaceShareLink & { token: string }> {
    await this.permissionService.ensureCanShareSpace(userId, input.spaceId);

    const token = nanoid(32);
    const hashedPassword = input.password
      ? await bcrypt.hash(input.password, 12)
      : null;

    const shareLink = await this.prisma.spaceShareLink.create({
      data: {
        spaceId: input.spaceId,
        token,
        password: hashedPassword,
        expiresAt: input.expiresAt ?? null,
        role: (input.role ?? 'VIEWER') as Role,
        createdById: userId,
      },
    });

    return {
      ...shareLink,
      token,
    } as unknown as SpaceShareLink & { token: string };
  }

  async revokeShareLink(shareLinkId: string, userId: string): Promise<{ success: boolean }> {
    const shareLink = await this.prisma.spaceShareLink.findUnique({
      where: { id: shareLinkId },
      select: { spaceId: true, createdById: true },
    });

    if (!shareLink) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '分享链接不存在',
      });
    }

    const canManage = await this.permissionService.canManageSpace(userId, shareLink.spaceId);
    const isCreator = shareLink.createdById === userId;

    if (!canManage && !isCreator) {
      throw new TRPCError({
        code: 'FORBIDDEN',
        message: '无权限撤销此分享链接',
      });
    }

    await this.prisma.spaceShareLink.delete({
      where: { id: shareLinkId },
    });

    return { success: true };
  }

  async validateShareLink(token: string, password?: string): Promise<ValidateShareLinkResult> {
    const shareLink = await this.prisma.spaceShareLink.findUnique({
      where: { token },
      include: {
        space: {
          select: {
            id: true,
            name: true,
            description: true,
            icon: true,
            color: true,
            visibility: true,
            createdById: true,
            createdAt: true,
            updatedAt: true,
          },
        },
      },
    });

    const requiresPassword = !!shareLink?.password;

    if (!shareLink) {
      return {
        valid: false,
        requiresPassword: false,
        error: '分享链接无效',
      };
    }

    if (shareLink.expiresAt && shareLink.expiresAt < new Date()) {
      return {
        valid: false,
        requiresPassword: false,
        error: '分享链接已过期',
      };
    }

    if (shareLink.password) {
      if (!password) {
        return {
          valid: false,
          requiresPassword: true,
          expiresAt: shareLink.expiresAt,
        };
      }

      const isPasswordValid = await bcrypt.compare(password, shareLink.password);
      if (!isPasswordValid) {
        return {
          valid: false,
          requiresPassword: true,
          expiresAt: shareLink.expiresAt,
          error: '密码错误',
        };
      }
    }

    return {
      valid: true,
      requiresPassword: false,
      space: shareLink.space as unknown as ValidateShareLinkResult['space'],
      role: shareLink.role as Role,
      expiresAt: shareLink.expiresAt,
    };
  }

  async listShareLinks(spaceId: string, userId: string): Promise<SpaceShareLink[]> {
    await this.permissionService.ensureCanManageSpace(userId, spaceId);

    const shareLinks = await this.prisma.spaceShareLink.findMany({
      where: { spaceId },
      orderBy: { createdAt: 'desc' },
    });

    return shareLinks as unknown as SpaceShareLink[];
  }

  async getSpaceById(spaceId: string, userId: string): Promise<SpaceWithMembers> {
    await this.permissionService.ensureSpaceMember(userId, spaceId);

    const space = await this.prisma.space.findUnique({
      where: { id: spaceId },
      include: {
        createdBy: {
          select: {
            id: true,
            name: true,
            email: true,
            avatar: true,
          },
        },
        members: {
          include: {
            user: {
              select: {
                id: true,
                name: true,
                email: true,
                avatar: true,
              },
            },
          },
        },
        _count: {
          select: {
            members: true,
            documents: true,
          },
        },
      },
    });

    if (!space) {
      throw new TRPCError({
        code: 'NOT_FOUND',
        message: '空间不存在',
      });
    }

    return space as unknown as SpaceWithMembers;
  }

  async listSpaces(userId: string, filter: SpaceListFilter = {}): Promise<PaginatedSpaces> {
    const page = filter.page ?? 1;
    const pageSize = filter.pageSize ?? 20;
    const skip = (page - 1) * pageSize;

    const where = {
      members: {
        some: {
          userId,
          ...(filter.role ? { role: filter.role as Role } : {}),
        },
      },
      ...(filter.search
        ? {
            OR: [
              { name: { contains: filter.search, mode: 'insensitive' } },
              { description: { contains: filter.search, mode: 'insensitive' } },
            ],
          }
        : {}),
    };

    const [spaces, total] = await Promise.all([
      this.prisma.space.findMany({
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
          _count: {
            select: {
              members: true,
              documents: true,
            },
          },
        },
        orderBy: { updatedAt: 'desc' },
      }),
      this.prisma.space.count({ where }),
    ]);

    return {
      items: spaces as unknown as SpaceWithOwner[],
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }
}
