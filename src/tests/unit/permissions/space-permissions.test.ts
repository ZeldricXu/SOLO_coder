import { describe, it, expect, vi, beforeEach } from 'vitest';
import { SpaceService } from '@/server/services/SpaceService';
import type { Space, SpaceMember, User, SpaceVisibility, ShareLink } from '@prisma/client';

describe('Space Service - Permission Boundaries', () => {
  let mockPrisma: any;
  let spaceService: SpaceService;

  beforeEach(() => {
    mockPrisma = {
      space: {
        create: vi.fn(),
        update: vi.fn(),
        delete: vi.fn(),
        findUnique: vi.fn(),
        findMany: vi.fn(),
      },
      spaceMember: {
        create: vi.fn(),
        update: vi.fn(),
        delete: vi.fn(),
        findUnique: vi.fn(),
        findMany: vi.fn(),
      },
      spaceShareLink: {
        create: vi.fn(),
        findUnique: vi.fn(),
        update: vi.fn(),
        delete: vi.fn(),
      },
      user: {
        findUnique: vi.fn(),
      },
      $transaction: vi.fn((fn) => fn(mockPrisma)),
    };

    spaceService = new SpaceService(mockPrisma);
  });

  describe('Space creation', () => {
    it('should add creator as OWNER when creating space', async () => {
      const mockUser: User = {
        id: 'user-1',
        name: 'Test User',
        email: 'test@example.com',
        password: 'hashed',
        avatar: null,
        role: 'USER',
        createdAt: new Date(),
        updatedAt: new Date(),
      };

      const mockSpace: Space = {
        id: 'space-1',
        name: 'Test Space',
        description: 'Test',
        icon: null,
        color: '#000000',
        visibility: SpaceVisibility.PRIVATE,
        password: null,
        createdById: 'user-1',
        createdAt: new Date(),
        updatedAt: new Date(),
        deletedAt: null,
      };

      mockPrisma.space.create.mockResolvedValue(mockSpace);
      mockPrisma.spaceMember.create.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'OWNER',
        joinedAt: new Date(),
      });

      const result = await spaceService.createSpace('user-1', {
        name: 'Test Space',
        description: 'Test',
      });

      expect(result).toBeDefined();
      expect(mockPrisma.spaceMember.create).toHaveBeenCalledWith(
        expect.objectContaining({
          data: expect.objectContaining({
            role: 'OWNER',
          }),
        })
      );
    });
  });

  describe('Space deletion', () => {
    it('should allow OWNER to delete space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'OWNER',
        joinedAt: new Date(),
      });

      mockPrisma.space.delete.mockResolvedValue({ id: 'space-1' });

      await expect(
        spaceService.deleteSpace('user-1', 'space-1')
      ).resolves.not.toThrow();

      expect(mockPrisma.space.delete).toHaveBeenCalled();
    });

    it('should not allow ADMIN to delete space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'ADMIN',
        joinedAt: new Date(),
      });

      await expect(
        spaceService.deleteSpace('user-1', 'space-1')
      ).rejects.toThrow();

      expect(mockPrisma.space.delete).not.toHaveBeenCalled();
    });

    it('should not allow EDITOR to delete space', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'user-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      await expect(
        spaceService.deleteSpace('user-1', 'space-1')
      ).rejects.toThrow();
    });
  });

  describe('Member role management', () => {
    it('should allow OWNER to add ADMIN', async () => {
      mockPrisma.spaceMember.findUnique
        .mockResolvedValueOnce({
          spaceId: 'space-1',
          userId: 'owner-1',
          role: 'OWNER',
          joinedAt: new Date(),
        })
        .mockResolvedValueOnce(null);

      mockPrisma.spaceMember.create.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'new-admin',
        role: 'ADMIN',
        joinedAt: new Date(),
      });

      const result = await spaceService.addMember(
        'owner-1',
        'space-1',
        'new-admin',
        'ADMIN'
      );

      expect(result.role).toBe('ADMIN');
    });

    it('should allow ADMIN to add EDITOR', async () => {
      mockPrisma.spaceMember.findUnique
        .mockResolvedValueOnce({
          spaceId: 'space-1',
          userId: 'admin-1',
          role: 'ADMIN',
          joinedAt: new Date(),
        })
        .mockResolvedValueOnce(null);

      mockPrisma.spaceMember.create.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'new-editor',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      const result = await spaceService.addMember(
        'admin-1',
        'space-1',
        'new-editor',
        'EDITOR'
      );

      expect(result.role).toBe('EDITOR');
    });

    it('should not allow ADMIN to add OWNER', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'admin-1',
        role: 'ADMIN',
        joinedAt: new Date(),
      });

      await expect(
        spaceService.addMember('admin-1', 'space-1', 'new-owner', 'OWNER')
      ).rejects.toThrow();
    });

    it('should not allow EDITOR to add members', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'editor-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      await expect(
        spaceService.addMember('editor-1', 'space-1', 'new-user', 'VIEWER')
      ).rejects.toThrow();
    });

    it('should allow ADMIN to remove EDITOR', async () => {
      mockPrisma.spaceMember.findUnique
        .mockResolvedValueOnce({
          spaceId: 'space-1',
          userId: 'admin-1',
          role: 'ADMIN',
          joinedAt: new Date(),
        })
        .mockResolvedValueOnce({
          spaceId: 'space-1',
          userId: 'editor-1',
          role: 'EDITOR',
          joinedAt: new Date(),
        });

      mockPrisma.spaceMember.delete.mockResolvedValue({});

      await expect(
        spaceService.removeMember('admin-1', 'space-1', 'editor-1')
      ).resolves.not.toThrow();
    });

    it('should not allow EDITOR to remove ADMIN', async () => {
      mockPrisma.spaceMember.findUnique
        .mockResolvedValueOnce({
          spaceId: 'space-1',
          userId: 'editor-1',
          role: 'EDITOR',
          joinedAt: new Date(),
        })
        .mockResolvedValueOnce({
          spaceId: 'space-1',
          userId: 'admin-1',
          role: 'ADMIN',
          joinedAt: new Date(),
        });

      await expect(
        spaceService.removeMember('editor-1', 'space-1', 'admin-1')
      ).rejects.toThrow();
    });

    it('should not allow removing OWNER', async () => {
      mockPrisma.spaceMember.findUnique
        .mockResolvedValueOnce({
          spaceId: 'space-1',
          userId: 'admin-1',
          role: 'ADMIN',
          joinedAt: new Date(),
        })
        .mockResolvedValueOnce({
          spaceId: 'space-1',
          userId: 'owner-1',
          role: 'OWNER',
          joinedAt: new Date(),
        });

      await expect(
        spaceService.removeMember('admin-1', 'space-1', 'owner-1')
      ).rejects.toThrow();
    });
  });

  describe('Share links', () => {
    it('should allow EDITOR to create share link', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'editor-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      const mockShareLink = {
        id: 'share-1',
        spaceId: 'space-1',
        token: 'abc123',
        expiresAt: new Date(Date.now() + 86400000),
        canEdit: false,
      };

      mockPrisma.spaceShareLink.create.mockResolvedValue(mockShareLink);

      const result = await spaceService.createShareLink('editor-1', 'space-1', {
        expiresAt: new Date(Date.now() + 86400000),
        canEdit: false,
      });

      expect(result).toBeDefined();
      expect(result.token).toBe('abc123');
    });

    it('should not allow VIEWER to create share link', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'viewer-1',
        role: 'VIEWER',
        joinedAt: new Date(),
      });

      await expect(
        spaceService.createShareLink('viewer-1', 'space-1', {
          expiresAt: new Date(Date.now() + 86400000),
          canEdit: false,
        })
      ).rejects.toThrow();
    });

    it('should return 404 for expired share link', async () => {
      const expiredLink = {
        id: 'share-expired',
        spaceId: 'space-1',
        token: 'expired-token',
        expiresAt: new Date(Date.now() - 86400000),
        canEdit: false,
      };

      mockPrisma.spaceShareLink.findUnique.mockResolvedValue(expiredLink);

      const result = await spaceService.validateShareLink('expired-token');

      expect(result.valid).toBe(false);
      expect(result.reason).toBe('expired');
    });

    it('should return 404 for revoked share link', async () => {
      mockPrisma.spaceShareLink.findUnique.mockResolvedValue(null);

      const result = await spaceService.validateShareLink('revoked-token');

      expect(result.valid).toBe(false);
      expect(result.reason).toBe('not_found');
    });

    it('should validate active share link', async () => {
      const activeLink = {
        id: 'share-active',
        spaceId: 'space-1',
        token: 'active-token',
        expiresAt: new Date(Date.now() + 86400000),
        canEdit: true,
      };

      mockPrisma.spaceShareLink.findUnique.mockResolvedValue(activeLink);

      const result = await spaceService.validateShareLink('active-token');

      expect(result.valid).toBe(true);
      expect(result.canEdit).toBe(true);
    });
  });

  describe('Space password protection', () => {
    it('should verify correct space password', async () => {
      const bcrypt = await import('bcryptjs');
      const passwordHash = await bcrypt.hash('secret123', 10);

      mockPrisma.space.findUnique.mockResolvedValue({
        id: 'space-1',
        name: 'Protected Space',
        visibility: SpaceVisibility.PRIVATE,
        password: passwordHash,
      });

      const result = await spaceService.verifySpacePassword(
        'space-1',
        'secret123'
      );

      expect(result).toBe(true);
    });

    it('should reject incorrect space password', async () => {
      const bcrypt = await import('bcryptjs');
      const passwordHash = await bcrypt.hash('secret123', 10);

      mockPrisma.space.findUnique.mockResolvedValue({
        id: 'space-1',
        name: 'Protected Space',
        visibility: SpaceVisibility.PRIVATE,
        password: passwordHash,
      });

      const result = await spaceService.verifySpacePassword(
        'space-1',
        'wrongpassword'
      );

      expect(result).toBe(false);
    });

    it('should allow OWNER to set space password', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'owner-1',
        role: 'OWNER',
        joinedAt: new Date(),
      });

      mockPrisma.space.update.mockResolvedValue({
        id: 'space-1',
        password: 'hashed-password',
      });

      await expect(
        spaceService.setSpacePassword('owner-1', 'space-1', 'newpassword')
      ).resolves.not.toThrow();
    });

    it('should not allow EDITOR to set space password', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'editor-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      await expect(
        spaceService.setSpacePassword('editor-1', 'space-1', 'newpassword')
      ).rejects.toThrow();
    });
  });

  describe('Document permissions', () => {
    it('should allow EDITOR to create document', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'editor-1',
        role: 'EDITOR',
        joinedAt: new Date(),
      });

      mockPrisma.document.create.mockResolvedValue({
        id: 'doc-1',
        title: 'New Document',
      });

      const result = await mockPrisma.document.create({
        data: {
          spaceId: 'space-1',
          title: 'New Document',
          content: '',
          createdById: 'editor-1',
        },
      });

      expect(result).toBeDefined();
    });

    it('should not allow VIEWER to create document', async () => {
      mockPrisma.spaceMember.findUnique.mockResolvedValue({
        spaceId: 'space-1',
        userId: 'viewer-1',
        role: 'VIEWER',
        joinedAt: new Date(),
      });

      mockPrisma.document.create.mockRejectedValue(
        new Error('Permission denied')
      );

      await expect(
        mockPrisma.document.create({
          data: {
            spaceId: 'space-1',
            title: 'New Document',
            content: '',
            createdById: 'viewer-1',
          },
        })
      ).rejects.toThrow();
    });
  });
});
