import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import { PrismaClient } from '@prisma/client';
import { TRPCError } from '@trpc/server';
import { appRouter } from '@/server/routers/_app';
import { TestHelper, createTestContext } from '../helpers/test-helpers';

describe('权限边界和访问控制集成测试', () => {
  let prisma: PrismaClient;
  let testHelper: TestHelper;

  beforeAll(() => {
    prisma = globalThis.prisma || new PrismaClient();
    testHelper = new TestHelper(prisma);
  });

  beforeEach(async () => {
    await testHelper.cleanup();
  });

  describe('非空间成员访问控制', () => {
    it('非空间成员尝试访问文档被拒绝', async () => {
      const owner = await testHelper.createUser({ name: 'Space Owner' });
      const outsider = await testHelper.createUser({ name: 'Outsider' });

      const ownerCtx = createTestContext(prisma, owner);
      const outsiderCtx = createTestContext(prisma, outsider);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const outsiderCaller = appRouter.createCaller(outsiderCtx);

      const space = await ownerCaller.space.create({
        name: '私有空间',
        description: '这是一个私有空间',
        visibility: 'PRIVATE',
      });

      const document = await ownerCaller.document.create({
        spaceId: space.id,
        title: '私密文档',
        content: '这是私密内容',
      });

      await expect(
        outsiderCaller.document.getById({ id: document.id })
      ).rejects.toThrow(TRPCError);

      await expect(
        outsiderCaller.document.getById({ id: document.id })
      ).rejects.toMatchObject({
        code: 'NOT_FOUND',
      });

      const outsiderSpaces = await outsiderCaller.space.list({});
      const spaceIds = new Set(outsiderSpaces.items.map((s) => s.id));
      expect(spaceIds.has(space.id)).toBe(false);
    });

    it('非空间成员尝试直接调用API修改文档被拒绝', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const attacker = await testHelper.createUser({ name: 'Attacker' });

      const ownerCtx = createTestContext(prisma, owner);
      const attackerCtx = createTestContext(prisma, attacker);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const attackerCaller = appRouter.createCaller(attackerCtx);

      const space = await ownerCaller.space.create({
        name: '测试空间',
      });

      const document = await ownerCaller.document.create({
        spaceId: space.id,
        title: '原始文档',
        content: '原始内容',
      });

      await expect(
        attackerCaller.document.update({
          id: document.id,
          content: '被篡改的内容',
        })
      ).rejects.toThrow(TRPCError);

      await expect(
        attackerCaller.document.update({
          id: document.id,
          content: '被篡改的内容',
        })
      ).rejects.toMatchObject({
        code: 'FORBIDDEN',
      });

      const finalDoc = await ownerCaller.document.getById({ id: document.id });
      expect(finalDoc.content).toBe('原始内容');
    });

    it('非空间成员尝试删除文档被拒绝', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const attacker = await testHelper.createUser({ name: 'Attacker' });

      const ownerCtx = createTestContext(prisma, owner);
      const attackerCtx = createTestContext(prisma, attacker);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const attackerCaller = appRouter.createCaller(attackerCtx);

      const space = await ownerCaller.space.create({
        name: '测试空间',
      });

      const document = await ownerCaller.document.create({
        spaceId: space.id,
        title: '测试文档',
        content: '测试内容',
      });

      await expect(
        attackerCaller.document.delete({
          id: document.id,
          permanent: true,
        })
      ).rejects.toThrow(TRPCError);

      await expect(
        attackerCaller.document.delete({
          id: document.id,
          permanent: true,
        })
      ).rejects.toMatchObject({
        code: 'FORBIDDEN',
      });

      const docExists = await ownerCaller.document.getById({ id: document.id });
      expect(docExists).toBeDefined();
    });
  });

  describe('分享链接访问控制', () => {
    it('未过期的分享链接可以正常访问', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const visitor = await testHelper.createUser({ name: 'Visitor' });

      const ownerCtx = createTestContext(prisma, owner);
      const visitorCtx = createTestContext(prisma, visitor);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const visitorCaller = appRouter.createCaller(visitorCtx);

      const space = await ownerCaller.space.create({
        name: '分享测试空间',
      });

      const shareLink = await ownerCaller.space.createShareLink({
        spaceId: space.id,
        role: 'VIEWER',
      });

      expect(shareLink).toBeDefined();
      expect(shareLink.token).toBeDefined();

      const validation = await visitorCaller.space.validateShareLink({
        token: shareLink.token,
      });

      expect(validation).toBeDefined();
      expect(validation.valid).toBe(true);
      expect(validation.spaceId).toBe(space.id);
    });

    it('已过期的分享链接访问返回404', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const visitor = await testHelper.createUser({ name: 'Visitor' });

      const ownerCtx = createTestContext(prisma, owner);
      const visitorCtx = createTestContext(prisma, visitor);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const visitorCaller = appRouter.createCaller(visitorCtx);

      const space = await ownerCaller.space.create({
        name: '分享测试空间',
      });

      const pastDate = new Date();
      pastDate.setHours(pastDate.getHours() - 1);

      const expiredLink = await testHelper.createShareLink(
        space.id,
        owner.id,
        {
          expiresAt: pastDate,
          role: 'VIEWER',
        }
      );

      await expect(
        visitorCaller.space.validateShareLink({
          token: expiredLink.token,
        })
      ).rejects.toThrow(TRPCError);

      await expect(
        visitorCaller.space.validateShareLink({
          token: expiredLink.token,
        })
      ).rejects.toMatchObject({
        code: 'NOT_FOUND',
      });
    });

    it('已撤销的分享链接访问被拒绝', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const visitor = await testHelper.createUser({ name: 'Visitor' });

      const ownerCtx = createTestContext(prisma, owner);
      const visitorCtx = createTestContext(prisma, visitor);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const visitorCaller = appRouter.createCaller(visitorCtx);

      const space = await ownerCaller.space.create({
        name: '分享测试空间',
      });

      const shareLink = await ownerCaller.space.createShareLink({
        spaceId: space.id,
        role: 'VIEWER',
      });

      const validation1 = await visitorCaller.space.validateShareLink({
        token: shareLink.token,
      });
      expect(validation1.valid).toBe(true);

      await ownerCaller.space.revokeShareLink({
        shareLinkId: shareLink.id,
      });

      await expect(
        visitorCaller.space.validateShareLink({
          token: shareLink.token,
        })
      ).rejects.toThrow(TRPCError);
    });

    it('受密码保护的分享链接需要正确密码', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const visitor = await testHelper.createUser({ name: 'Visitor' });

      const ownerCtx = createTestContext(prisma, owner);
      const visitorCtx = createTestContext(prisma, visitor);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const visitorCaller = appRouter.createCaller(visitorCtx);

      const space = await ownerCaller.space.create({
        name: '密码保护空间',
      });

      const shareLink = await ownerCaller.space.createShareLink({
        spaceId: space.id,
        password: 'secret123',
        role: 'VIEWER',
      });

      await expect(
        visitorCaller.space.validateShareLink({
          token: shareLink.token,
        })
      ).rejects.toThrow(TRPCError);

      await expect(
        visitorCaller.space.validateShareLink({
          token: shareLink.token,
          password: 'wrongpassword',
        })
      ).rejects.toThrow(TRPCError);

      const validation = await visitorCaller.space.validateShareLink({
        token: shareLink.token,
        password: 'secret123',
      });
      expect(validation.valid).toBe(true);
    });
  });

  describe('角色权限边界', () => {
    it('EDITOR不能删除空间但可以创建文档', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const editor = await testHelper.createUser({ name: 'Editor' });

      const ownerCtx = createTestContext(prisma, owner);
      const editorCtx = createTestContext(prisma, editor);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const editorCaller = appRouter.createCaller(editorCtx);

      const space = await ownerCaller.space.create({
        name: '角色测试空间',
      });

      await ownerCaller.space.addMember({
        spaceId: space.id,
        email: editor.email,
        role: 'EDITOR',
      });

      const document = await editorCaller.document.create({
        spaceId: space.id,
        title: '编辑者创建的文档',
        content: '编辑者可以创建文档',
      });

      expect(document).toBeDefined();
      expect(document.createdById).toBe(editor.id);

      await expect(
        editorCaller.space.delete({ id: space.id })
      ).rejects.toThrow(TRPCError);

      await expect(
        editorCaller.space.delete({ id: space.id })
      ).rejects.toMatchObject({
        code: 'FORBIDDEN',
      });

      const spaceExists = await ownerCaller.space.getById({ id: space.id });
      expect(spaceExists).toBeDefined();
    });

    it('VIEWER只能查看不能编辑', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const viewer = await testHelper.createUser({ name: 'Viewer' });

      const ownerCtx = createTestContext(prisma, owner);
      const viewerCtx = createTestContext(prisma, viewer);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const viewerCaller = appRouter.createCaller(viewerCtx);

      const space = await ownerCaller.space.create({
        name: '查看者测试空间',
      });

      await ownerCaller.space.addMember({
        spaceId: space.id,
        email: viewer.email,
        role: 'VIEWER',
      });

      const document = await ownerCaller.document.create({
        spaceId: space.id,
        title: '测试文档',
        content: '测试内容',
      });

      const viewedDoc = await viewerCaller.document.getById({ id: document.id });
      expect(viewedDoc).toBeDefined();
      expect(viewedDoc.title).toBe('测试文档');

      await expect(
        viewerCaller.document.update({
          id: document.id,
          content: '尝试修改',
        })
      ).rejects.toThrow(TRPCError);

      await expect(
        viewerCaller.document.update({
          id: document.id,
          content: '尝试修改',
        })
      ).rejects.toMatchObject({
        code: 'FORBIDDEN',
      });

      await expect(
        viewerCaller.document.create({
          spaceId: space.id,
          title: '尝试创建文档',
          content: '内容',
        })
      ).rejects.toThrow(TRPCError);
    });

    it('ADMIN可以管理成员但不能删除OWNER', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const admin = await testHelper.createUser({ name: 'Admin' });
      const member = await testHelper.createUser({ name: 'Member' });

      const ownerCtx = createTestContext(prisma, owner);
      const adminCtx = createTestContext(prisma, admin);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const adminCaller = appRouter.createCaller(adminCtx);

      const space = await ownerCaller.space.create({
        name: '管理员测试空间',
      });

      await ownerCaller.space.addMember({
        spaceId: space.id,
        email: admin.email,
        role: 'ADMIN',
      });

      await ownerCaller.space.addMember({
        spaceId: space.id,
        email: member.email,
        role: 'VIEWER',
      });

      await adminCaller.space.updateMemberRole({
        spaceId: space.id,
        userId: member.id,
        role: 'EDITOR',
      });

      const members = await adminCaller.space.listMembers({ spaceId: space.id });
      const updatedMember = members.find((m) => m.userId === member.id);
      expect(updatedMember?.role).toBe('EDITOR');

      await expect(
        adminCaller.space.removeMember({
          spaceId: space.id,
          userId: owner.id,
        })
      ).rejects.toThrow(TRPCError);

      const finalMembers = await adminCaller.space.listMembers({ spaceId: space.id });
      expect(finalMembers.some((m) => m.userId === owner.id && m.role === 'OWNER')).toBe(true);
    });
  });

  describe('权限中间件保护', () => {
    it('未登录用户访问受保护的接口被拒绝', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '测试空间',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: '测试文档',
        content: '测试内容',
      });

      const publicCtx = createTestContext(prisma);
      const publicCaller = appRouter.createCaller(publicCtx);

      await expect(
        publicCaller.space.list({})
      ).rejects.toThrow(TRPCError);

      await expect(
        publicCaller.space.list({})
      ).rejects.toMatchObject({
        code: 'UNAUTHORIZED',
      });
    });

    it('权限检查在input解析前执行，避免信息泄露', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const attacker = await testHelper.createUser({ name: 'Attacker' });

      const ownerCtx = createTestContext(prisma, owner);
      const attackerCtx = createTestContext(prisma, attacker);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const attackerCaller = appRouter.createCaller(attackerCtx);

      const space = await ownerCaller.space.create({
        name: '测试空间',
      });

      const document = await ownerCaller.document.create({
        spaceId: space.id,
        title: '敏感文档',
        content: '敏感内容',
      });

      await expect(
        attackerCaller.document.update({
          id: document.id,
          content: '尝试修改内容',
        })
      ).rejects.toThrow(TRPCError);

      try {
        await attackerCaller.document.update({
          id: document.id,
          content: '尝试修改内容',
        });
      } catch (error) {
        expect(error).toBeInstanceOf(TRPCError);
        expect((error as TRPCError).code).toBe('FORBIDDEN');
        expect((error as TRPCError).message).not.toContain('敏感');
        expect((error as TRPCError).message).not.toContain('敏感内容');
      }
    });
  });
});
