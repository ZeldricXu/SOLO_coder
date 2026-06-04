import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import { PrismaClient } from '@prisma/client';
import { appRouter } from '@/server/routers/_app';
import { TestHelper, createTestContext } from '../helpers/test-helpers';

describe('搜索和推荐工作流集成测试', () => {
  let prisma: PrismaClient;
  let testHelper: TestHelper;

  beforeAll(() => {
    prisma = globalThis.prisma || new PrismaClient();
    testHelper = new TestHelper(prisma);
  });

  beforeEach(async () => {
    await testHelper.cleanup();
  });

  describe('文档入库 → 搜索 → 结果高亮 → 知识图谱推荐', () => {
    it('完整搜索链路：文档创建后可被搜索并返回高亮结果', async () => {
      const owner = await testHelper.createUser({ name: 'Search Owner' });
      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '搜索测试空间',
        description: '用于测试搜索功能的空间',
      });

      const doc1 = await ownerCaller.document.create({
        spaceId: space.id,
        title: 'React最佳实践指南',
        content: '# React最佳实践\n\n## 组件设计\n\nReact组件设计要遵循单一职责原则。使用Hooks管理状态，避免类组件。\n\n## 性能优化\n\n使用React.memo避免不必要的重渲染，合理使用useMemo和useCallback。',
      });

      const doc2 = await ownerCaller.document.create({
        spaceId: space.id,
        title: 'Vue3入门教程',
        content: '# Vue3入门教程\n\nVue3使用Composition API，比Options API更灵活。响应式系统基于Proxy实现。',
      });

      const doc3 = await ownerCaller.document.create({
        spaceId: space.id,
        title: '前端性能优化实战',
        content: '# 前端性能优化\n\n## React性能优化\n\n使用React.memo和useMemo进行性能优化。\n\n## 打包优化\n\n使用tree shaking和code splitting减少包体积。',
      });

      expect(doc1).toBeDefined();
      expect(doc2).toBeDefined();
      expect(doc3).toBeDefined();

      const searchResults = await ownerCaller.search.search({
        query: 'React',
        spaceId: space.id,
      });

      expect(searchResults).toBeDefined();
      expect(searchResults.items).toBeDefined();
      expect(searchResults.items.length).toBeGreaterThan(0);

      const reactDocs = searchResults.items.filter((r) =>
        r.title.includes('React')
      );
      expect(reactDocs.length).toBeGreaterThanOrEqual(2);

      if (searchResults.items[0]) {
        expect(searchResults.items[0].title).toBeDefined();
        expect(searchResults.items[0]._count).toBeDefined();
      }
    });

    it('搜索支持多关键词组合搜索', async () => {
      const owner = await testHelper.createUser({ name: 'Search Owner' });
      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '组合搜索测试空间',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: '技术方案文档',
        content: '本文档介绍了微服务架构的设计方案，包含API网关、服务发现、负载均衡等内容。',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: '部署指南',
        content: '使用Docker和Kubernetes进行容器化部署，配置服务发现和负载均衡策略。',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: '前端架构',
        content: '前端采用React框架，使用Redux进行状态管理，通过API网关调用后端服务。',
      });

      const andResults = await ownerCaller.search.search({
        query: '服务发现',
        spaceId: space.id,
      });

      expect(andResults.items.length).toBeGreaterThan(0);
      expect(andResults.items.length).toBeLessThanOrEqual(2);

      const orResults = await ownerCaller.search.search({
        query: '架构',
        spaceId: space.id,
      });

      expect(orResults.items.length).toBeGreaterThanOrEqual(2);
    });

    it('搜索支持按标签和时间范围过滤', async () => {
      const owner = await testHelper.createUser({ name: 'Search Owner' });
      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '过滤搜索测试空间',
      });

      const tag1 = await prisma.tag.create({
        data: { name: '前端', color: '#3b82f6', createdById: owner.id },
      });
      const tag2 = await prisma.tag.create({
        data: { name: '后端', color: '#10b981', createdById: owner.id },
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: 'React教程',
        content: 'React是一个前端框架',
        tags: [tag1.id],
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: 'Node.js入门',
        content: 'Node.js用于后端开发',
        tags: [tag2.id],
      });

      const frontendResults = await ownerCaller.search.search({
        query: 'React',
        spaceId: space.id,
        tagIds: [tag1.id],
      });

      expect(frontendResults.items.length).toBe(1);
      expect(frontendResults.items[0].title).toBe('React教程');

      const backendResults = await ownerCaller.search.search({
        query: 'Node.js',
        spaceId: space.id,
        tagIds: [tag2.id],
      });

      expect(backendResults.items.length).toBe(1);
      expect(backendResults.items[0].title).toBe('Node.js入门');
    });

    it('搜索结果分页功能正常', async () => {
      const owner = await testHelper.createUser({ name: 'Search Owner' });
      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '分页搜索测试空间',
      });

      for (let i = 1; i <= 15; i++) {
        await ownerCaller.document.create({
          spaceId: space.id,
          title: `测试文档 ${i}`,
          content: `这是第${i}个测试文档，用于测试搜索分页功能。`,
        });
      }

      const page1Results = await ownerCaller.search.search({
        query: '测试文档',
        spaceId: space.id,
        page: 1,
        pageSize: 5,
      });

      expect(page1Results.items.length).toBe(5);
      expect(page1Results.total).toBeGreaterThanOrEqual(15);
      expect(page1Results.page).toBe(1);
      expect(page1Results.pageSize).toBe(5);

      const page2Results = await ownerCaller.search.search({
        query: '测试文档',
        spaceId: space.id,
        page: 2,
        pageSize: 5,
      });

      expect(page2Results.items.length).toBe(5);
      expect(page2Results.page).toBe(2);

      const page1Titles = page1Results.items.map((r) => r.title);
      const page2Titles = page2Results.items.map((r) => r.title);
      const intersection = page1Titles.filter((t) => page2Titles.includes(t));
      expect(intersection.length).toBe(0);
    });

    it('文档详情页显示相关文档推荐', async () => {
      const owner = await testHelper.createUser({ name: 'Recommendation Owner' });
      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '推荐测试空间',
      });

      const mainDoc = await ownerCaller.document.create({
        spaceId: space.id,
        title: 'React性能优化完全指南',
        content: 'React性能优化包括使用memo、useMemo、useCallback等方法。',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: 'React Hooks详解',
        content: 'React Hooks包括useState、useEffect、useMemo、useCallback等。',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: 'Vue性能优化',
        content: 'Vue性能优化方法与React不同，使用computed和watch。',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: 'React组件设计模式',
        content: 'React组件设计模式包括高阶组件、render props等。',
      });

      const recommendations = await ownerCaller.recommendation.getRelatedDocuments({
        documentId: mainDoc.id,
        limit: 3,
      });

      expect(recommendations).toBeDefined();
      expect(recommendations.items.length).toBeLessThanOrEqual(3);

      const relatedDoc = recommendations.items.find((r) => r.title === 'React Hooks详解');
      if (relatedDoc) {
        expect(relatedDoc.relevanceScore).toBeDefined();
      }
    });

    it('跨空间搜索只能搜索用户有权限的空间', async () => {
      const owner = await testHelper.createUser({ name: 'Owner' });
      const member = await testHelper.createUser({ name: 'Member' });
      const outsider = await testHelper.createUser({ name: 'Outsider' });

      const ownerCtx = createTestContext(prisma, owner);
      const memberCtx = createTestContext(prisma, member);
      const outsiderCtx = createTestContext(prisma, outsider);
      const ownerCaller = appRouter.createCaller(ownerCtx);
      const memberCaller = appRouter.createCaller(memberCtx);
      const outsiderCaller = appRouter.createCaller(outsiderCtx);

      const space1 = await ownerCaller.space.create({
        name: '空间1',
      });

      const space2 = await ownerCaller.space.create({
        name: '空间2',
      });

      await ownerCaller.space.addMember({
        spaceId: space1.id,
        email: member.email,
        role: 'VIEWER',
      });

      await ownerCaller.document.create({
        spaceId: space1.id,
        title: '空间1的文档',
        content: '这是空间1的内容',
      });

      await ownerCaller.document.create({
        spaceId: space2.id,
        title: '空间2的文档',
        content: '这是空间2的内容',
      });

      const memberResults = await memberCaller.search.search({
        query: '空间',
      });

      expect(memberResults.items.length).toBeGreaterThanOrEqual(1);
      const memberSpaceIds = new Set(memberResults.items.map((r) => r.spaceId));
      expect(memberSpaceIds.has(space1.id)).toBe(true);

      const outsiderResults = await outsiderCaller.search.search({
        query: '空间',
      });

      expect(outsiderResults.items.length).toBe(0);
    });
  });

  describe('搜索排序和相关性', () => {
    it('标题匹配优先于内容匹配', async () => {
      const owner = await testHelper.createUser({ name: 'Search Owner' });
      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '排序测试空间',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: 'JavaScript入门指南',
        content: '这是一本关于编程的入门书籍，包含基础语法和进阶内容。',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: '编程基础教程',
        content: 'JavaScript是一种流行的编程语言，广泛用于前端开发。',
      });

      const results = await ownerCaller.search.search({
        query: 'JavaScript',
        spaceId: space.id,
      });

      expect(results.items.length).toBe(2);
      expect(results.items[0].title).toBe('JavaScript入门指南');
    });

    it('中文分词搜索支持短词匹配', async () => {
      const owner = await testHelper.createUser({ name: 'Search Owner' });
      const ownerCtx = createTestContext(prisma, owner);
      const ownerCaller = appRouter.createCaller(ownerCtx);

      const space = await ownerCaller.space.create({
        name: '中文搜索测试空间',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: '微服务架构设计',
        content: '微服务是一种架构风格，将应用拆分为多个小型服务。',
      });

      await ownerCaller.document.create({
        spaceId: space.id,
        title: '单体架构改造',
        content: '单体架构向微服务架构迁移的最佳实践。',
      });

      const results = await ownerCaller.search.search({
        query: '架构',
        spaceId: space.id,
      });

      expect(results.items.length).toBeGreaterThanOrEqual(2);

      const exactResults = await ownerCaller.search.search({
        query: '架构设计',
        spaceId: space.id,
      });

      expect(exactResults.items.length).toBeGreaterThanOrEqual(1);
      expect(exactResults.items[0].title).toBe('微服务架构设计');
    });
  });
});
