import { describe, it, expect, vi, beforeEach } from 'vitest';
import { PermissionService } from '@/server/services/PermissionService';
import type { Role, SpaceMember } from '@prisma/client';

describe('Permission Service', () => {
  let mockPrisma: any;
  let permissionService: PermissionService;

  beforeEach(() => {
    mockPrisma = {
      spaceMember: {
        findUnique: vi.fn(),
      },
    };

    permissionService = new PermissionService(mockPrisma);
  });

  describe('Role hierarchy', () => {
    it('should have correct role hierarchy: OWNER > ADMIN > EDITOR > VIEWER', () => {
      const hierarchy = ['OWNER', 'ADMIN', 'EDITOR', 'VIEWER'];
      
      for (let i = 0; i < hierarchy.length - 1; i++) {
        const higherRole = hierarchy[i] as Role;
        const lowerRole = hierarchy[i + 1] as Role;
        
        expect(permissionService.compareRoles(higherRole, lowerRole)).toBeGreaterThan(0);
        expect(permissionService.compareRoles(lowerRole, higherRole)).toBeLessThan(0);
      }
    });

    it('should return 0 when comparing same roles', () => {
      expect(permissionService.compareRoles('EDITOR', 'EDITOR')).toBe(0);
    });
  });

  describe('Space member check', () => {
    it('should return true when user is member of space', async () => {
      const mockMember: SpaceMember = {
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      };

      mockPrisma.spaceMember.findUnique.mockResolvedValue(mockMember);

      const result = await permissionService.checkSpaceMember('user-1', 'space-1');
      
      expect(result).toBe(true);
      expect(mockPrisma.spaceMember.findUnique).toHaveBeenCalledWith({
        where: {
          spaceId_userId: {
            spaceId: 'space-1',
            userId: 'user-1',
          },
        },
      });
    });

    it('should return false when user is not member of space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue(null);

      const result = await permissionService.checkSpaceMember('user-1', 'space-1');
      
      expect(result).toBe(false);
    });
  });

  describe('Role checks', () => {
    it('should return true when user has exact role', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      const result = await permissionService.checkRole('user-1', 'space-1', 'EDITOR');
      
      expect(result).toBe(true);
    });

    it('should return true when user has higher role', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'ADMIN',
        joinedAt: new Date(),
      });

      const result = await permissionService.checkRole('user-1', 'space-1', 'EDITOR');
      
      expect(result).toBe(true);
    });

    it('should return false when user has lower role', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'VIEWER',
        joinedAt: new Date(),
      });

      const result = await permissionService.checkRole('user-1', 'space-1', 'EDITOR');
      
      expect(result).toBe(false);
    });

    it('should return false when user is not a member', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue(null);

      const result = await permissionService.checkRole('user-1', 'space-1', 'EDITOR');
      
      expect(result).toBe(false);
    });
  });

  describe('Specific permission checks', () => {
    it('should allow editors to create documents', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      const result = await permissionService.canEditDocument('user-1', 'space-1');
      
      expect(result).toBe(true);
    });

    it('should not allow viewers to create documents', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'VIEWER',
        joinedAt: new Date(),
      });

      const result = await permissionService.canEditDocument('user-1', 'space-1');
      
      expect(result).toBe(false);
    });

    it('should allow admins to manage space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'ADMIN',
        joinedAt: new Date(),
      });

      const result = await permissionService.canManageSpace('user-1', 'space-1');
      
      expect(result).toBe(true);
    });

    it('should not allow editors to manage space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      const result = await permissionService.canManageSpace('user-1', 'space-1');
      
      expect(result).toBe(false);
    });

    it('should allow owners to delete space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'OWNER',
        joinedAt: new Date(),
      });

      const result = await permissionService.canDeleteSpace('user-1', 'space-1');
      
      expect(result).toBe(true);
    });

    it('should not allow admins to delete space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'ADMIN',
        joinedAt: new Date(),
      });

      const result = await permissionService.canDeleteSpace('user-1', 'space-1');
      
      expect(result).toBe(false);
    });

    it('should allow editors to share space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      const result = await permissionService.canShareSpace('user-1', 'space-1');
      
      expect(result).toBe(true);
    });

    it('should not allow viewers to share space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'VIEWER',
        joinedAt: new Date(),
      });

      const result = await permissionService.canShareSpace('user-1', 'space-1');
      
      expect(result).toBe(false);
    });
  });

  describe('requireRole throws correct errors', () => {
    it('should throw UNAUTHORIZED when user is not a member', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue(null);

      await expect(
        permissionService.requireRole('user-1', 'space-1', 'VIEWER')
      ).rejects.toThrow();
    });

    it('should throw FORBIDDEN when user has insufficient role', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'VIEWER',
        joinedAt: new Date(),
      });

      await expect(
        permissionService.requireRole('user-1', 'space-1', 'EDITOR')
      ).rejects.toThrow();
    });

    it('should not throw when user has sufficient role', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'ADMIN',
        joinedAt: new Date(),
      });

      await expect(
        permissionService.requireRole('user-1', 'space-1', 'EDITOR')
      ).resolves.not.toThrow();
    });
  });

  describe('getHighestRole', () => {
    it('should return correct role for member', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      const result = await permissionService.getHighestRole('user-1', 'space-1');
      
      expect(result).toBe('EDITOR');
    });

    it('should return null for non-member', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue(null);

      const result = await permissionService.getHighestRole('user-1', 'space-1');
      
      expect(result).toBeNull();
    });
  });

  describe('Multiple permission checks', () => {
    it('should check multiple permissions with AND logic', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'ADMIN',
        joinedAt: new Date(),
      });

      const result = await permissionService.checkPermissions(
        'user-1',
        'space-1',
        ['canEdit', 'canManage']
      );

      expect(result).toBe(true);
    });

    it('should fail if any permission check fails', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      const result = await permissionService.checkPermissions(
        'user-1',
        'space-1',
        ['canEdit', 'canManage']
      );

      expect(result).toBe(false);
    });
  });
});
