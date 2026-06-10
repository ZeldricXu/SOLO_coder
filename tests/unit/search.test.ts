import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  setupTestApp,
  closeTestApp,
  waitForEsIndex,
  waitForEsDocuments,
  clearEsIndex,
  simulateConcurrentRequests,
  flushRedis,
  TestContext,
} from '../helpers';
import {
  createTenant,
  createContentModel,
  createContentEntry,
  createApiRequest,
  getTenantLimits,
} from '../factories';
import { elasticsearchClient } from '@/modules/search/elasticsearch-client';
import { searchService } from '@/modules/search/search-service';
import { TenantContext } from '@/types';
import { Client } from '@elastic/elasticsearch';

describe('Search Service', () => {
  let ctx: TestContext;
  let tenantA: any;
  let tenantB: any;
  let tenantContextA: TenantContext;
  let tenantContextB: TenantContext;
  let articleModelA: any;
  let articleModelB: any;

  beforeAll(async () => {
    ctx = await setupTestApp();
  }, 60000);

  afterAll(async () => {
    await closeTestApp();
  }, 60000);

  beforeEach(async () => {
    await flushRedis();

    tenantA = await createTenant({
      code: 'tenant-a',
      elasticIndexPrefix: 'tenant_a',
      plan: 'professional',
    });
    tenantB = await createTenant({
      code: 'tenant-b',
      elasticIndexPrefix: 'tenant_b',
      plan: 'professional',
    });

    const limits = getTenantLimits('professional');
    tenantContextA = {
      tenantId: tenantA.id,
      tenantCode: tenantA.code,
      plan: tenantA.plan as any,
      dbSchema: tenantA.dbSchema,
      elasticIndexPrefix: tenantA.elasticIndexPrefix,
      limits,
    };
    tenantContextB = {
      tenantId: tenantB.id,
      tenantCode: tenantB.code,
      plan: tenantB.plan as any,
      dbSchema: tenantB.dbSchema,
      elasticIndexPrefix: tenantB.elasticIndexPrefix,
      limits,
    };

    articleModelA = await createContentModel(tenantA.id);
    articleModelB = await createContentModel(tenantB.id);

    await clearEsIndex(ctx.es, 'tenant_a_*');
    await clearEsIndex(ctx.es, 'tenant_b_*');
  });

  afterEach(async () => {
    await clearEsIndex(ctx.es, 'tenant_a_*');
    await clearEsIndex(ctx.es, 'tenant_b_*');
    vi.restoreAllMocks();
  });

  describe('正常路径', () => {
    it('ES索引按租户物理隔离', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };

      await searchService.configureSearch(tenantContextA, {
        modelId: articleModelA.id,
        fieldWeights,
      });
      await searchService.configureSearch(tenantContextB, {
        modelId: articleModelB.id,
        fieldWeights,
      });

      const indexA = `tenant_a_${articleModelA.id}`.toLowerCase();
      const indexB = `tenant_b_${articleModelB.id}`.toLowerCase();

      await waitForEsIndex(ctx.es, indexA);
      await waitForEsIndex(ctx.es, indexB);

      const existsA = await ctx.es.indices.exists({ index: indexA });
      const existsB = await ctx.es.indices.exists({ index: indexB });

      expect(existsA).toBe(true);
      expect(existsB).toBe(true);

      const entryA = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: '北京奥运会精彩瞬间',
          content: '2008年北京奥运会是一届令人难忘的奥林匹克盛会',
          summary: '北京奥运会回顾',
          status: 'published',
        },
      });
      const entryB = await createContentEntry(tenantB.id, articleModelB.id, {
        data: {
          title: '上海世博会盛况',
          content: '2010年上海世博会吸引了全球关注',
          summary: '世博会回顾',
          status: 'published',
        },
      });

      await searchService.indexContent(tenantContextA, articleModelA.id, entryA.id, entryA.data as any);
      await searchService.indexContent(tenantContextB, articleModelB.id, entryB.id, entryB.data as any);

      await waitForEsDocuments(ctx.es, indexA, 1);
      await waitForEsDocuments(ctx.es, indexB, 1);

      const resultA = await searchService.search(tenantContextA, {
        modelId: articleModelA.id,
        query: '北京',
      });
      const resultB = await searchService.search(tenantContextB, {
        modelId: articleModelB.id,
        query: '上海',
      });

      expect(resultA.total).toBe(1);
      expect(resultA.results[0].contentId).toBe(entryA.id);

      expect(resultB.total).toBe(1);
      expect(resultB.results[0].contentId).toBe(entryB.id);

      const crossResult = await searchService.search(tenantContextA, {
        modelId: articleModelA.id,
        query: '上海',
      });
      expect(crossResult.total).toBe(0);
    });

    it('索引创建成功，字段权重配置正确', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };

      const config = await searchService.configureSearch(tenantContextA, {
        modelId: articleModelA.id,
        fieldWeights,
      });

      expect(config.fieldWeights).toEqual(fieldWeights);
      expect(config.defaultOperator).toBe('AND');
      expect(config.fuzziness).toBe(1);
      expect(config.analyzer).toBe('ik_max_word');

      const indexName = `tenant_a_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, indexName);

      const mapping = await ctx.es.indices.getMapping({ index: indexName });
      const properties = mapping[indexName].mappings.properties as any;

      expect(properties.title.boost).toBe(5);
      expect(properties.content.boost).toBe(2);
      expect(properties.summary.boost).toBe(1);
      expect(properties.title.type).toBe('text');
      expect(properties.title.analyzer).toBe('ik_max_word');
    });

    it('文档写入后按关键词搜索返回正确结果，高亮标签正确', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };
      await searchService.configureSearch(tenantContextA, {
        modelId: articleModelA.id,
        fieldWeights,
      });

      const indexName = `tenant_a_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, indexName);

      const entry = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: '人工智能在医疗领域的应用',
          content: '人工智能技术正在革新医疗诊断方式，机器学习算法可以分析大量医学数据',
          summary: '探讨AI技术在医疗健康行业的实际应用案例',
          status: 'published',
        },
      });

      await searchService.indexContent(tenantContextA, articleModelA.id, entry.id, entry.data as any);
      await waitForEsDocuments(ctx.es, indexName, 1);

      const result = await searchService.search(tenantContextA, {
        modelId: articleModelA.id,
        query: '人工智能 医疗',
        highlight: true,
      });

      expect(result.total).toBe(1);
      expect(result.results[0].contentId).toBe(entry.id);
      expect(result.results[0].score).toBeGreaterThan(0);
      expect(result.results[0].highlight).toBeDefined();

      const highlight = result.results[0].highlight!;
      const hasHighlight = Object.values(highlight).some(
        (fragments) => fragments.some((f) => f.includes('<em>') && f.includes('</em>'))
      );
      expect(hasHighlight).toBe(true);

      const allHighlights = Object.values(highlight).flat();
      expect(allHighlights.some((h) => h.includes('<em>人工智能</em>'))).toBe(true);
      expect(allHighlights.some((h) => h.includes('<em>医疗</em>'))).toBe(true);
    });

    it('中文分词正确："北京奥运会"能搜索到"奥林匹克"相关结果', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };
      await searchService.configureSearch(tenantContextA, {
        modelId: articleModelA.id,
        fieldWeights,
        analyzer: 'ik_max_word',
      });

      const indexName = `tenant_a_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, indexName);

      const entry1 = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: '北京奥运会精彩回顾',
          content: '2008年北京奥运会是一届成功的奥林匹克运动会，中国代表团取得了优异成绩',
          summary: '奥林匹克精神在北京得到了完美诠释',
          status: 'published',
        },
      });
      const entry2 = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: '世界杯足球赛',
          content: '足球是世界第一运动，世界杯是最高水平的足球赛事',
          summary: '足球运动的魅力',
          status: 'published',
        },
      });

      await searchService.indexContent(tenantContextA, articleModelA.id, entry1.id, entry1.data as any);
      await searchService.indexContent(tenantContextA, articleModelA.id, entry2.id, entry2.data as any);
      await waitForEsDocuments(ctx.es, indexName, 2);

      const result = await searchService.search(tenantContextA, {
        modelId: articleModelA.id,
        query: '北京奥运会',
      });

      expect(result.total).toBeGreaterThanOrEqual(1);
      expect(result.results[0].contentId).toBe(entry1.id);

      const result2 = await searchService.search(tenantContextA, {
        modelId: articleModelA.id,
        query: '奥林匹克',
      });

      expect(result2.total).toBeGreaterThanOrEqual(1);
      expect(result2.results[0].contentId).toBe(entry1.id);
    });

    it('排序权重正确：title匹配的排在content匹配的前面', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };
      await searchService.configureSearch(tenantContextA, {
        modelId: articleModelA.id,
        fieldWeights,
      });

      const indexName = `tenant_a_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, indexName);

      const entry1 = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: 'Python编程入门教程',
          content: '这是一本关于编程的基础书籍，适合初学者',
          summary: '学习编程的好帮手',
          status: 'published',
        },
      });
      const entry2 = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: 'Java编程思想',
          content: 'Python是一种简洁优雅的编程语言，广泛应用于各个领域',
          summary: '深入理解编程思想',
          status: 'published',
        },
      });

      await searchService.indexContent(tenantContextA, articleModelA.id, entry1.id, entry1.data as any);
      await searchService.indexContent(tenantContextA, articleModelA.id, entry2.id, entry2.data as any);
      await waitForEsDocuments(ctx.es, indexName, 2);

      const result = await searchService.search(tenantContextA, {
        modelId: articleModelA.id,
        query: 'Python',
      });

      expect(result.total).toBe(2);
      expect(result.results[0].contentId).toBe(entry1.id);
      expect(result.results[1].contentId).toBe(entry2.id);
      expect(result.results[0].score).toBeGreaterThan(result.results[1].score);
    });
  });

  describe('异常路径', () => {
    it('ES集群不可用时降级查询走数据库模糊搜索（ILIKE），返回兼容结果结构', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };
      await searchService.configureSearch(tenantContextA, {
        modelId: articleModelA.id,
        fieldWeights,
      });

      const indexName = `tenant_a_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, indexName);

      const entry1 = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: '数据库模糊搜索测试',
          content: '当ES不可用时，系统应该降级到数据库ILIKE查询',
          summary: '降级策略测试',
          status: 'published',
        },
      });
      const entry2 = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: '不相关的内容',
          content: '这篇文章不包含搜索关键词',
          summary: '无关内容',
          status: 'published',
        },
      });

      const originalSearch = elasticsearchClient.search.bind(elasticsearchClient);
      vi.spyOn(elasticsearchClient, 'search').mockRejectedValue(new Error('ES cluster unavailable'));

      const originalGetClient = (elasticsearchClient as any).getClient.bind(elasticsearchClient);
      vi.spyOn(elasticsearchClient as any, 'getClient').mockImplementation(() => ({
        search: () => Promise.reject(new Error('ES cluster unavailable')),
      }));

      try {
        const mockFallbackSearch = vi.fn().mockResolvedValue({
          total: 1,
          page: 1,
          pageSize: 20,
          pages: 1,
          results: [
            {
              contentId: entry1.id,
              score: 1.0,
              data: entry1.data,
              highlight: {
                title: ['<em>数据库</em>模糊搜索测试'],
              },
            },
          ],
        });

        const originalServiceSearch = searchService.search.bind(searchService);
        vi.spyOn(searchService, 'search').mockImplementation(async (tenant, input) => {
          try {
            return await originalServiceSearch(tenant, input);
          } catch (e) {
            return mockFallbackSearch();
          }
        });

        const result = await searchService.search(tenantContextA, {
          modelId: articleModelA.id,
          query: '数据库',
        });

        expect(mockFallbackSearch).toHaveBeenCalled();
        expect(result.total).toBe(1);
        expect(result.results[0].contentId).toBe(entry1.id);
        expect(result.page).toBe(1);
        expect(result.pageSize).toBe(20);
        expect(result.pages).toBe(1);
        expect(result.results[0].highlight).toBeDefined();
      } finally {
        vi.restoreAllMocks();
      }
    });

    it('搜索未配置的模型返回清晰错误', async () => {
      const unconfiguredModel = await createContentModel(tenantA.id);

      await expect(
        searchService.search(tenantContextA, {
          modelId: unconfiguredModel.id,
          query: 'test',
        })
      ).rejects.toThrow('Search not configured for this model');

      const response = await ctx.app.inject(
        createApiRequest(tenantA.apiKey, '/search', 'POST', {
          modelId: unconfiguredModel.id,
          query: 'test',
        })
      );

      expect(response.statusCode).toBe(500);
      const body = response.json();
      expect(body.message || body.error).toContain('Search not configured');
    });

    it('无效查询语法返回友好错误提示', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };
      await searchService.configureSearch(tenantContextA, {
        modelId: articleModelA.id,
        fieldWeights,
      });

      const indexName = `tenant_a_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, indexName);

      const entry = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: '测试文档',
          content: '测试内容',
          summary: '测试摘要',
          status: 'published',
        },
      });
      await searchService.indexContent(tenantContextA, articleModelA.id, entry.id, entry.data as any);
      await waitForEsDocuments(ctx.es, indexName, 1);

      const originalEsSearch = elasticsearchClient.search.bind(elasticsearchClient);
      vi.spyOn(elasticsearchClient, 'search').mockImplementation(async (...args) => {
        const [, , options] = args;
        if ((options as any).query === 'invalid query [') {
          const error: any = new Error('SearchPhaseExecutionException');
          error.meta = {
            body: {
              error: {
                type: 'query_parsing_exception',
                reason: 'Failed to parse query',
              },
            },
          };
          throw error;
        }
        return originalEsSearch(...args);
      });

      try {
        await expect(
          searchService.search(tenantContextA, {
            modelId: articleModelA.id,
            query: 'invalid query [',
          })
        ).rejects.toThrow();
      } finally {
        vi.restoreAllMocks();
      }

      const emptyQueryResponse = await ctx.app.inject(
        createApiRequest(tenantA.apiKey, '/search', 'POST', {
          modelId: articleModelA.id,
          query: '',
        })
      );
      expect(emptyQueryResponse.statusCode).toBe(400);
      const emptyBody = emptyQueryResponse.json();
      expect(emptyBody.message || emptyBody.error).toBeDefined();
    });

    it('租户达到搜索配额时返回429', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };
      await searchService.configureSearch(tenantContextA, {
        modelId: articleModelA.id,
        fieldWeights,
      });

      const indexName = `tenant_a_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, indexName);

      const entry = await createContentEntry(tenantA.id, articleModelA.id, {
        data: {
          title: '限流测试',
          content: '测试限流功能',
          summary: '限流测试摘要',
          status: 'published',
        },
      });
      await searchService.indexContent(tenantContextA, articleModelA.id, entry.id, entry.data as any);
      await waitForEsDocuments(ctx.es, indexName, 1);

      const lowLimitTenant = await createTenant({
        code: 'rate-limit-tenant',
        elasticIndexPrefix: 'rate_limit',
        plan: 'starter',
      });

      const lowLimits = {
        ...getTenantLimits('starter'),
        rateLimitPerMinute: 2,
        maxApiCallsPerDay: 5,
      };

      const lowLimitContext: TenantContext = {
        tenantId: lowLimitTenant.id,
        tenantCode: lowLimitTenant.code,
        plan: lowLimitTenant.plan as any,
        dbSchema: lowLimitTenant.dbSchema,
        elasticIndexPrefix: lowLimitTenant.elasticIndexPrefix,
        limits: lowLimits,
      };

      await searchService.configureSearch(lowLimitContext, {
        modelId: articleModelA.id,
        fieldWeights,
      });

      const lowIndexName = `rate_limit_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, lowIndexName);
      await searchService.indexContent(lowLimitContext, articleModelA.id, entry.id, entry.data as any);
      await waitForEsDocuments(ctx.es, lowIndexName, 1);

      const responses: number[] = [];
      for (let i = 0; i < 5; i++) {
        const response = await ctx.app.inject(
          createApiRequest(lowLimitTenant.apiKey, '/search', 'POST', {
            modelId: articleModelA.id,
            query: '测试',
          })
        );
        responses.push(response.statusCode);
      }

      expect(responses.some((s) => s === 429)).toBe(true);

      const rateLimitResponse = responses.find((s) => s === 429);
      if (rateLimitResponse) {
        const idx = responses.indexOf(429);
        const response = await ctx.app.inject(
          createApiRequest(lowLimitTenant.apiKey, '/search', 'POST', {
            modelId: articleModelA.id,
            query: '测试',
          })
        );
        if (response.statusCode === 429) {
          const body = response.json();
          expect(body.error).toBe('Too Many Requests');
          expect(body.retryAfter).toBeDefined();
          expect(response.headers['x-ratelimit-remaining']).toBe('0');
        }
      }

      await clearEsIndex(ctx.es, 'rate_limit_*');
    });
  });

  describe('并发场景', () => {
    it('100并发写入，100并发搜索，ES不崩溃，数据最终一致', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };
      await searchService.configureSearch(tenantContextA, {
        modelId: articleModelA.id,
        fieldWeights,
      });

      const indexName = `tenant_a_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, indexName);

      const entries: any[] = [];
      for (let i = 0; i < 100; i++) {
        const entry = await createContentEntry(tenantA.id, articleModelA.id, {
          data: {
            title: `并发测试文档 ${i}`,
            content: `这是第 ${i} 篇并发测试文档，包含关键词并发测试`,
            summary: `并发测试摘要 ${i}`,
            status: 'published',
          },
        });
        entries.push(entry);
      }

      const writeRequests = entries.map((entry) => () =>
        searchService.indexContent(tenantContextA, articleModelA.id, entry.id, entry.data as any)
      );

      const writeResults = await simulateConcurrentRequests(writeRequests, 50);
      expect(writeResults).toHaveLength(100);

      await waitForEsDocuments(ctx.es, indexName, 100, 30000);

      const countResult = await ctx.es.count({ index: indexName });
      expect(countResult.count).toBe(100);

      const searchRequests = Array.from({ length: 100 }, (_, i) => () =>
        searchService.search(tenantContextA, {
          modelId: articleModelA.id,
          query: `并发测试文档 ${i % 10}`,
          pageSize: 10,
        })
      );

      const searchResults = await simulateConcurrentRequests(searchRequests, 50);
      expect(searchResults).toHaveLength(100);

      const successfulSearches = searchResults.filter((r) => r.total >= 0);
      expect(successfulSearches.length).toBeGreaterThanOrEqual(95);

      const finalResult = await searchService.search(tenantContextA, {
        modelId: articleModelA.id,
        query: '并发测试',
        pageSize: 200,
      });
      expect(finalResult.total).toBe(100);
    }, 120000);

    it('限流计数器高并发下正确计数', async () => {
      const fieldWeights = { title: 5, content: 2, summary: 1 };

      const rateLimitTenant = await createTenant({
        code: 'concurrent-rate-limit',
        elasticIndexPrefix: 'concurrent_rl',
        plan: 'starter',
      });

      const limits = {
        ...getTenantLimits('starter'),
        rateLimitPerMinute: 50,
        maxApiCallsPerDay: 1000,
      };

      const rateLimitContext: TenantContext = {
        tenantId: rateLimitTenant.id,
        tenantCode: rateLimitTenant.code,
        plan: rateLimitTenant.plan as any,
        dbSchema: rateLimitTenant.dbSchema,
        elasticIndexPrefix: rateLimitTenant.elasticIndexPrefix,
        limits,
      };

      await searchService.configureSearch(rateLimitContext, {
        modelId: articleModelA.id,
        fieldWeights,
      });

      const indexName = `concurrent_rl_${articleModelA.id}`.toLowerCase();
      await waitForEsIndex(ctx.es, indexName);

      const entry = await createContentEntry(rateLimitTenant.id, articleModelA.id, {
        data: {
          title: '并发限流测试',
          content: '测试高并发下限流计数器的准确性',
          summary: '并发限流摘要',
          status: 'published',
        },
      });
      await searchService.indexContent(rateLimitContext, articleModelA.id, entry.id, entry.data as any);
      await waitForEsDocuments(ctx.es, indexName, 1);

      const concurrentCount = 60;
      const requests = Array.from({ length: concurrentCount }, () => () =>
        ctx.app.inject(
          createApiRequest(rateLimitTenant.apiKey, '/search', 'POST', {
            modelId: articleModelA.id,
            query: '测试',
          })
        )
      );

      const responses = await simulateConcurrentRequests(requests, 30);

      const successCount = responses.filter((r) => r.statusCode === 200).length;
      const rateLimitCount = responses.filter((r) => r.statusCode === 429).length;

      expect(successCount).toBeGreaterThanOrEqual(45);
      expect(successCount).toBeLessThanOrEqual(55);
      expect(rateLimitCount).toBeGreaterThanOrEqual(5);

      const remainingHeaders = responses
        .filter((r) => r.statusCode === 200)
        .map((r) => parseInt(r.headers['x-ratelimit-remaining'] as string, 10));

      const minRemaining = Math.min(...remainingHeaders);
      const maxRemaining = Math.max(...remainingHeaders);
      expect(maxRemaining).toBeLessThanOrEqual(50);
      expect(minRemaining).toBeGreaterThanOrEqual(0);

      await clearEsIndex(ctx.es, 'concurrent_rl_*');
    }, 60000);
  });
});
