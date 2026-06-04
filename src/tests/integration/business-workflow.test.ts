import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import { PrismaClient } from '@prisma/client';
import { appRouter } from '@/server/routers/_app';
import { TestHelper, createTestContext } from '../helpers/test-helpers';

describe('完整业务工作流集成测试', () => {
  let prisma: PrismaClient;
  let testHelper: TestHelper;

  beforeAll(() => {
    prisma = globalThis.prisma || new PrismaClient();
    testHelper = new TestHelper(prisma);
  });

  beforeEach(async () => {
    await testHelper.cleanup();
  });

  describe('管理员创建空间 → 邀请成员 → 同步导入文档 → 协作编辑 → 审阅工作流', () => {
    it('完整链路：管理员创建空间并邀请成员', async () => {
      const admin = await testHelper.createUser({ name: 'Admin User' });
      const member = await testHelper.createUser({ name: 'Member User' });

      const adminCtx = createTestContext(prisma, admin);
      const adminCaller = appRouter.createCaller(adminCtx);

      const space = await adminCaller.space.create({
        name: '产品团队空间',
        description: '产品团队的知识共享空间',
        visibility: 'PRIVATE',
      });

      expect(space).toBeDefined();
      expect(space.name).toBe('产品团队空间');
      expect(space.createdById).toBe(admin.id);

      const addedMember = await adminCaller.space.addMember({
        spaceId: space.id,
        email: member.email,
        role: 'EDITOR',
      });

      expect(addedMember).toBeDefined();
      expect(addedMember.role).toBe('EDITOR');
      expect(addedMember.userId).toBe(member.id);

      const members = await adminCaller.space.listMembers({ spaceId: space.id });
      expect(members).toHaveLength(2);
      expect(members.some((m) => m.userId === admin.id && m.role === 'OWNER')).toBe(true);
      expect(members.some((m) => m.userId === member.id && m.role === 'EDITOR')).toBe(true);
    });

    it('完整链路：创建文档并协同编辑保存版本', async () => {
      const owner = await testHelper.createUser({ name: 'Document Owner' });
      const editor = await testHelper.createUser({ name: 'Document Editor' });

      const ownerCtx = createTestContext(prisma, owner);
      const editorCtx = createTestContext(prisma, editor);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const editorCaller = appRouter.createCaller(editorCtx);

      const space = await ownerCaller.space.create({
        name: '文档协作空间',
        description: '用于文档协作的测试空间',
      });

      await ownerCaller.space.addMember({
        spaceId: space.id,
        email: editor.email,
        role: 'EDITOR',
      });

      const document = await ownerCaller.document.create({
        spaceId: space.id,
        title: '产品需求文档 v1.0',
        content: '# 产品需求文档\n\n## 概述\n\n这是最初的版本。',
      });

      expect(document).toBeDefined();
      expect(document.title).toBe('产品需求文档 v1.0');
      expect(document._count?.versions).toBe(1);

      const updatedDoc = await editorCaller.document.update({
        id: document.id,
        content: '# 产品需求文档\n\n## 概述\n\n这是编辑后的版本，增加了更多内容。\n\n## 功能列表\n\n- 功能一\n- 功能二',
        versionMessage: '添加了功能列表',
      });

      expect(updatedDoc.content).toContain('功能列表');

      const versions = await editorCaller.document.listVersions({ documentId: document.id });
      expect(versions.total).toBe(2);
      expect(versions.items[0].message).toBe('添加了功能列表');
      expect(versions.items[0].version).toBe(2);

      const version1 = await editorCaller.document.getVersion({
        documentId: document.id,
        version: 1,
      });

      expect(version1).toBeDefined();
      expect(version1.content).toBe('# 产品需求文档\n\n## 概述\n\n这是最初的版本。');
    });

    it('完整链路：发起审阅 → 标注批注 → 作者修改 → 审阅通过', async () => {
      const author = await testHelper.createUser({ name: 'Author' });
      const reviewer1 = await testHelper.createUser({ name: 'Reviewer 1' });
      const reviewer2 = await testHelper.createUser({ name: 'Reviewer 2' });

      const authorCtx = createTestContext(prisma, author);
      const reviewer1Ctx = createTestContext(prisma, reviewer1);
      const reviewer2Ctx = createTestContext(prisma, reviewer2);
      const authorCaller = appRouter.createCaller(authorCtx);
      const reviewer1Caller = appRouter.createCaller(reviewer1Ctx);
      const reviewer2Caller = appRouter.createCaller(reviewer2Ctx);

      const space = await authorCaller.space.create({
        name: '审阅工作流测试空间',
      });

      await authorCaller.space.addMember({
        spaceId: space.id,
        email: reviewer1.email,
        role: 'VIEWER',
      });
      await authorCaller.space.addMember({
        spaceId: space.id,
        email: reviewer2.email,
        role: 'VIEWER',
      });

      const document = await authorCaller.document.create({
        spaceId: space.id,
        title: '技术方案文档',
        content: '# 技术方案\n\n## 架构设计\n\n这里是架构设计的内容。',
      });

      const review = await authorCaller.review.create({
        documentId: document.id,
        title: '技术方案评审',
        description: '请审阅这份技术方案文档，重点关注架构设计部分。',
        reviewerIds: [reviewer1.id, reviewer2.id],
      });

      expect(review).toBeDefined();
      expect(review.status).toBe('PENDING');
      expect(review._count?.reviewers).toBe(2);

      const reviewDetail = await reviewer1Caller.review.getById({ id: review.id });
      expect(reviewDetail).toBeDefined();
      expect(reviewDetail.reviewers).toHaveLength(2);

      const comment1 = await reviewer1Caller.comment.create({
        documentId: document.id,
        content: '架构设计部分需要补充更多细节，建议增加时序图。',
        selection: {
          text: '架构设计',
          start: 20,
          end: 50,
        },
      });

      expect(comment1).toBeDefined();
      expect(comment1.content).toContain('架构设计');

      const updatedDoc = await authorCaller.document.update({
        id: document.id,
        content: '# 技术方案\n\n## 架构设计\n\n这里是架构设计的内容。\n\n### 时序图\n\n![时序图](...)',
        versionMessage: '根据审阅意见补充时序图',
      });

      expect(updatedDoc.content).toContain('时序图');

      const updatedReview1 = await reviewer1Caller.review.submitReview({
        reviewId: review.id,
        decision: 'APPROVED',
        comment: '修改到位，架构设计部分已补充完整。',
      });

      expect(updatedReview1.status).toBe('IN_REVIEW');
      expect(updatedReview1.reviewers.find((r) => r.userId === reviewer1.id)?.status).toBe('APPROVED');

      const updatedReview2 = await reviewer2Caller.review.submitReview({
        reviewId: review.id,
        decision: 'APPROVED',
        comment: '整体方案合理，同意通过。',
      });

      expect(updatedReview2.status).toBe('APPROVED');
      expect(updatedReview2.reviewers.every((r) => r.status === 'APPROVED')).toBe(true);

      const finalReview = await authorCaller.review.getById({ id: review.id });
      expect(finalReview.status).toBe('APPROVED');
      expect(finalReview.comments).toHaveLength(1);
    });

    it('完整链路：版本对比和回滚功能', async () => {
      const owner = await testHelper.createUser({ name: 'Version Owner' });
      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '版本控制测试空间',
      });

      const document = await ownerCaller.document.create({
        spaceId: space.id,
        title: '版本测试文档',
        content: '版本1内容',
      });

      await ownerCaller.document.update({
        id: document.id,
        content: '版本2内容',
        versionMessage: '第二次修改',
      });

      await ownerCaller.document.update({
        id: document.id,
        content: '版本3内容',
        versionMessage: '第三次修改',
      });

      const versions = await ownerCaller.document.listVersions({ documentId: document.id });
      expect(versions.total).toBe(3);

      const diff = await ownerCaller.document.compareVersions({
        documentId: document.id,
        versionFrom: 1,
        versionTo: 3,
      });

      expect(diff).toBeDefined();
      expect(diff.versionFrom.version).toBe(1);
      expect(diff.versionTo.version).toBe(3);
      expect(diff.contentDiff).toBeDefined();
      expect(diff.contentDiff.length).toBeGreaterThan(0);

      const rolledBack = await ownerCaller.document.rollbackToVersion({
        documentId: document.id,
        version: 1,
      });

      expect(rolledBack).toBeDefined();
      expect(rolledBack._count?.versions).toBe(4);

      const finalVersions = await ownerCaller.document.listVersions({ documentId: document.id });
      expect(finalVersions.items[0].message).toContain('回滚');
    });
  });

  describe('空间成员管理和角色权限', () => {
    it('角色层级：OWNER可以管理所有成员', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const admin = await testHelper.createUser({ name: 'Admin' });
      const editor = await testHelper.createUser({ name: 'Editor' });

      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '角色测试空间',
      });

      await ownerCaller.space.addMember({
        spaceId: space.id,
        email: admin.email,
        role: 'ADMIN',
      });

      await ownerCaller.space.addMember({
        spaceId: space.id,
        email: editor.email,
        role: 'EDITOR',
      });

      const members = await ownerCaller.space.listMembers({ spaceId: space.id });
      expect(members).toHaveLength(3);

      await ownerCaller.space.updateMemberRole({
        spaceId: space.id,
        userId: editor.id,
        role: 'VIEWER',
      });

      const updatedMembers = await ownerCaller.space.listMembers({ spaceId: space.id });
      const updatedEditor = updatedMembers.find((m) => m.userId === editor.id);
      expect(updatedEditor?.role).toBe('VIEWER');

      await ownerCaller.space.removeMember({
        spaceId: space.id,
        userId: editor.id,
      });

      const finalMembers = await ownerCaller.space.listMembers({ spaceId: space.id });
      expect(finalMembers).toHaveLength(2);
      expect(finalMembers.some((m) => m.userId === editor.id)).toBe(false);
    });
  });
});
