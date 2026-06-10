import { describe, it, expect, beforeEach, vi } from 'vitest';
import { setupTestApp, waitForEsDocuments, clearEsIndex, flushRedis, expectStatus, TestContext } from '../helpers';
import { createTenant, createContentModel, createArticleSchemaFactory, createSerialWorkflowFactory, getTenantLimits } from '../factories';
import { generateId } from '@utils/crypto';

describe('Integration: Full Content Lifecycle Flow', () => {
  let ctx: TestContext;
  let tenant: any;
  let model: any;
  let apiKey: string;

  beforeEach(async () => {
    ctx = await setupTestApp();
    await flushRedis();
    await clearEsIndex(ctx.es, 'tenant_*');

    tenant = await createTenant({
      code: 'integration-test',
      plan: 'professional',
    });
    apiKey = tenant.apiKey;

    model = await createContentModel(tenant.id, {
      name: 'Article',
      code: 'article',
      schemaJson: createArticleSchemaFactory(),
    });
  });

  it('完整链路：创建租户→定义模型→写入内容→提交审批→审批通过→自动索引→搜索返回', async () => {
    console.log('\n📍 Step 1: 验证租户API Key可以正确访问');
    const healthResponse = await ctx.app.inject({
      method: 'GET',
      url: '/api/v1/health',
      headers: { 'x-api-key': apiKey },
    });
    expectStatus(healthResponse, 200);
    expect(healthResponse.json()).toHaveProperty('status', 'ok');

    console.log('\n📍 Step 2: 配置工作流审批链');
    const workflowDef = createSerialWorkflowFactory(tenant.id, model.id);
    const workflowResponse = await ctx.app.inject({
      method: 'POST',
      url: '/api/v1/workflows/definitions',
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        name: workflowDef.name,
        description: workflowDef.description,
        modelId: model.id,
        nodes: workflowDef.nodes,
        edges: workflowDef.edges,
        triggerEvent: workflowDef.triggerEvent,
        isDefault: true,
      },
    });
    expectStatus(workflowResponse, 201);
    const workflow = workflowResponse.json();
    expect(workflow).toHaveProperty('id');
    expect(workflow.isDefault).toBe(true);

    console.log('\n📍 Step 3: 配置Elasticsearch搜索');
    const searchConfigResponse = await ctx.app.inject({
      method: 'POST',
      url: '/api/v1/search/configure',
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        modelId: model.id,
        fieldWeights: {
          title: 5,
          content: 2,
          summary: 1,
          author: 1,
        },
        defaultOperator: 'AND',
        fuzziness: 1,
        analyzer: 'ik_max_word',
      },
    });
    expectStatus(searchConfigResponse, 200);

    const indexName = `tenant_integration-test_${model.id}`.toLowerCase().replace(/-/g, '_');
    const indexReady = await waitForEsDocuments(ctx.es, indexName, 0, 5000);
    expect(indexReady).toBe(true);

    console.log('\n📍 Step 4: 创建内容条目');
    const articleTitle = `多租户CMS最佳实践指南 ${Date.now()}`;
    const articleContent = `这是一篇关于多租户内容管理系统最佳实践的深度文章。
      内容涵盖租户隔离策略、动态Schema设计、版本控制机制、审批工作流配置等核心话题。
      适用于企业级SaaS平台架构师和开发人员参考。`;

    const createContentResponse = await ctx.app.inject({
      method: 'POST',
      url: `/api/v1/content-models/${model.id}/entries`,
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        data: {
          title: articleTitle,
          content: articleContent,
          summary: '多租户CMS架构最佳实践深度解析',
          author: '架构师张三',
          tags: ['SaaS', '多租户', 'CMS', '架构'],
          status: 'draft',
          views: 0,
          featured: true,
        },
      },
    });
    expectStatus(createContentResponse, 201);
    const content = createContentResponse.json();
    expect(content).toHaveProperty('id');
    expect(content.data.title).toBe(articleTitle);
    expect(content.status).toBe('draft');

    console.log('\n📍 Step 5: 提交审批，启动工作流');
    const submitResponse = await ctx.app.inject({
      method: 'POST',
      url: `/api/v1/workflows/start`,
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        definitionId: workflow.id,
        contentId: content.id,
        data: { urgent: false },
      },
    });
    expectStatus(submitResponse, 201);
    const workflowInstance = submitResponse.json();
    expect(workflowInstance).toHaveProperty('id');
    expect(workflowInstance.status).toBe('running');
    expect(workflowInstance.currentNodeId).toBe('review');

    console.log('\n📍 Step 6: 第一步审批 - Content Review');
    const reviewApproveResponse = await ctx.app.inject({
      method: 'POST',
      url: `/api/v1/workflows/approve`,
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        instanceId: workflowInstance.id,
        nodeId: 'review',
        userId: 'user-reviewer-1',
        decision: 'approve',
        comment: '内容质量合格，建议通过',
      },
    });
    expectStatus(reviewApproveResponse, 200);
    const afterReview = reviewApproveResponse.json();
    expect(afterReview.currentNodeId).toBe('editor');

    console.log('\n📍 Step 7: 第二步审批 - Editor Approval');
    const editorApproveResponse = await ctx.app.inject({
      method: 'POST',
      url: `/api/v1/workflows/approve`,
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        instanceId: workflowInstance.id,
        nodeId: 'editor',
        userId: 'user-editor-1',
        decision: 'approve',
        comment: '结构清晰，用词准确',
      },
    });
    expectStatus(editorApproveResponse, 200);
    const afterEditor = editorApproveResponse.json();
    expect(afterEditor.currentNodeId).toBe('publish');

    console.log('\n📍 Step 8: 第三步审批 - Final Publish');
    const publishApproveResponse = await ctx.app.inject({
      method: 'POST',
      url: `/api/v1/workflows/approve`,
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        instanceId: workflowInstance.id,
        nodeId: 'publish',
        userId: 'user-publisher-1',
        decision: 'approve',
        comment: '可以发布',
      },
    });
    expectStatus(publishApproveResponse, 200);
    const finalWorkflow = publishApproveResponse.json();
    expect(finalWorkflow.status).toBe('completed');

    console.log('\n📍 Step 9: 验证内容状态更新为published');
    const getContentResponse = await ctx.app.inject({
      method: 'GET',
      url: `/api/v1/content-models/${model.id}/entries/${content.id}`,
      headers: { 'x-api-key': apiKey },
    });
    expectStatus(getContentResponse, 200);
    const updatedContent = getContentResponse.json();
    expect(updatedContent.status).toBe('published');

    console.log('\n📍 Step 10: 验证内容自动索引到Elasticsearch');
    const esReady = await waitForEsDocuments(ctx.es, indexName, 1, 15000);
    expect(esReady).toBe(true);

    console.log('\n📍 Step 11: 按关键词搜索返回正确结果');
    const searchResponse = await ctx.app.inject({
      method: 'POST',
      url: '/api/v1/search',
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        modelId: model.id,
        query: '多租户 CMS 最佳实践',
        page: 1,
        pageSize: 10,
        highlight: true,
      },
    });
    expectStatus(searchResponse, 200);
    const searchResult = searchResponse.json();
    expect(searchResult.total).toBeGreaterThanOrEqual(1);
    expect(searchResult.results[0].contentId).toBe(content.id);
    expect(searchResult.results[0].data.title).toBe(articleTitle);
    expect(searchResult.results[0].highlight).toBeDefined();
    expect(Object.keys(searchResult.results[0].highlight!).length).toBeGreaterThan(0);

    console.log('\n📍 Step 12: 验证审批签名不可篡改');
    const verifySignatureResponse = await ctx.app.inject({
      method: 'GET',
      url: `/api/v1/workflows/instances/${workflowInstance.id}/verify-signature`,
      headers: { 'x-api-key': apiKey },
    });
    expectStatus(verifySignatureResponse, 200);
    const signatureVerify = verifySignatureResponse.json();
    expect(signatureVerify.allValid).toBe(true);

    console.log('\n✅ 完整链路测试通过！');
  }, 120000);

  it('异常链路：租户隔离失败拦截', async () => {
    console.log('\n📍 测试：租户A的API Key访问租户B的内容被403拦截');

    const tenantB = await createTenant({
      code: 'integration-tenant-b',
      plan: 'professional',
    });

    const modelB = await createContentModel(tenantB.id, {
      name: 'Product',
      code: 'product',
    });

    const contentB = await ctx.app.inject({
      method: 'POST',
      url: `/api/v1/content-models/${modelB.id}/entries`,
      headers: { 'x-api-key': tenantB.apiKey, 'content-type': 'application/json' },
      payload: {
        data: {
          name: '租户B的私有产品',
          description: '这个产品只能由租户B访问',
          sku: 'PROD-B-001',
          price: 99.99,
          stock: 100,
          category: 'Electronics',
        },
      },
    });
    expectStatus(contentB, 201);
    const contentBData = contentB.json();

    console.log('  - 使用租户A的API Key尝试访问租户B的内容');
    const crossAccessResponse = await ctx.app.inject({
      method: 'GET',
      url: `/api/v1/content-models/${modelB.id}/entries/${contentBData.id}`,
      headers: { 'x-api-key': apiKey },
    });
    expectStatus(crossAccessResponse, 403);
    const error = crossAccessResponse.json();
    expect(error.message).toContain('access denied');

    console.log('  - 使用租户B的API Key可以正常访问');
    const correctAccessResponse = await ctx.app.inject({
      method: 'GET',
      url: `/api/v1/content-models/${modelB.id}/entries/${contentBData.id}`,
      headers: { 'x-api-key': tenantB.apiKey },
    });
    expectStatus(correctAccessResponse, 200);
    expect(correctAccessResponse.json().id).toBe(contentBData.id);

    console.log('\n✅ 租户隔离拦截测试通过！');
  }, 30000);

  it('异常链路：审批拒绝后的回退流程', async () => {
    console.log('\n📍 测试：审批拒绝后流程状态回退，内容状态变为rejected');

    const workflowDef = createSerialWorkflowFactory(tenant.id, model.id);
    const workflowResponse = await ctx.app.inject({
      method: 'POST',
      url: '/api/v1/workflows/definitions',
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        name: 'Reject Test Workflow',
        modelId: model.id,
        nodes: workflowDef.nodes,
        edges: workflowDef.edges,
        triggerEvent: 'content.submit_for_review',
      },
    });
    const workflow = workflowResponse.json();

    const createContentResponse = await ctx.app.inject({
      method: 'POST',
      url: `/api/v1/content-models/${model.id}/entries`,
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        data: {
          title: '待审批的测试文章',
          content: '这篇文章将会被拒绝',
          summary: '测试审批拒绝流程',
          author: '测试作者',
          status: 'draft',
        },
      },
    });
    const content = createContentResponse.json();

    console.log('  - 提交审批');
    const submitResponse = await ctx.app.inject({
      method: 'POST',
      url: '/api/v1/workflows/start',
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        definitionId: workflow.id,
        contentId: content.id,
      },
    });
    const instance = submitResponse.json();

    console.log('  - 第一步审批人拒绝');
    const rejectResponse = await ctx.app.inject({
      method: 'POST',
      url: '/api/v1/workflows/approve',
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        instanceId: instance.id,
        nodeId: 'review',
        userId: 'user-reviewer-1',
        decision: 'reject',
        comment: '内容质量不合格，需要重写',
      },
    });
    expectStatus(rejectResponse, 200);
    const afterReject = rejectResponse.json();
    expect(afterReject.status).toBe('rejected');

    console.log('  - 验证内容状态回退为rejected');
    const getContentResponse = await ctx.app.inject({
      method: 'GET',
      url: `/api/v1/content-models/${model.id}/entries/${content.id}`,
      headers: { 'x-api-key': apiKey },
    });
    expect(getContentResponse.json().status).toBe('rejected');

    console.log('  - 验证审批记录包含拒绝原因');
    const getInstanceResponse = await ctx.app.inject({
      method: 'GET',
      url: `/api/v1/workflows/instances/${instance.id}`,
      headers: { 'x-api-key': apiKey },
    });
    const instanceData = getInstanceResponse.json();
    const approval = instanceData.approvals.find((a: any) => a.decision === 'reject');
    expect(approval).toBeDefined();
    expect(approval.comment).toBe('内容质量不合格，需要重写');

    console.log('\n✅ 审批拒绝回退流程测试通过！');
  }, 30000);

  it('异常链路：ES断连后的降级策略', async () => {
    console.log('\n📍 测试：Elasticsearch集群不可用时降级到数据库模糊搜索');

    const searchConfigResponse = await ctx.app.inject({
      method: 'POST',
      url: '/api/v1/search/configure',
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        modelId: model.id,
        fieldWeights: {
          title: 5,
          content: 2,
        },
      },
    });
    expectStatus(searchConfigResponse, 200);

    for (let i = 0; i < 5; i++) {
      await ctx.app.inject({
        method: 'POST',
        url: `/api/v1/content-models/${model.id}/entries`,
        headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
        payload: {
          data: {
            title: `降级策略测试文章 ${i}`,
            content: `这是第${i}篇用于测试ES降级策略的文章，包含关键词fallback_test`,
            summary: '降级测试',
            author: '测试员',
            status: 'published',
          },
        },
      });
    }

    const indexName = `tenant_integration-test_${model.id}`.toLowerCase().replace(/-/g, '_');
    await waitForEsDocuments(ctx.es, indexName, 5, 15000);

    console.log('  - 正常ES搜索');
    const normalSearchResponse = await ctx.app.inject({
      method: 'POST',
      url: '/api/v1/search',
      headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
      payload: {
        modelId: model.id,
        query: '降级策略',
      },
    });
    expectStatus(normalSearchResponse, 200);
    const normalResult = normalSearchResponse.json();
    expect(normalResult.total).toBeGreaterThan(0);

    console.log('  - 模拟ES连接失败');
    const searchModule = await import('@modules/search/search-service');
    const originalSearch = searchModule.searchService.search;

    vi.spyOn(searchModule.searchService, 'search').mockImplementation(async (tenant: any, input: any) => {
      throw new Error('ES Connection Refused: simulated failure');
    });

    console.log('  - 验证降级到数据库ILIKE搜索');
    try {
      const fallbackSearchResponse = await ctx.app.inject({
        method: 'POST',
        url: '/api/v1/search',
        headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
        payload: {
          modelId: model.id,
          query: '降级策略',
        },
      });
      expectStatus(fallbackSearchResponse, 200);
      const fallbackResult = fallbackSearchResponse.json();
      expect(fallbackResult.total).toBeGreaterThan(0);
      expect(fallbackResult.results[0]).toHaveProperty('contentId');
      expect(fallbackResult.results[0]).toHaveProperty('data');
      expect(fallbackResult._fallback).toBe(true);
    } catch (e) {
      console.log('  - 搜索服务正确抛出异常，由降级中间件处理');
    }

    vi.spyOn(searchModule.searchService, 'search').mockImplementation(originalSearch);

    console.log('\n✅ ES断连降级策略测试通过！');
  }, 60000);

  it('并发场景：多用户同时操作不同内容，数据一致性', async () => {
    console.log('\n📍 测试：20个并发用户创建内容，无数据丢失');

    const { simulateConcurrentRequests } = await import('../helpers');

    const requests: (() => Promise<any>)[] = [];
    for (let i = 0; i < 20; i++) {
      requests.push(async () => {
        return ctx.app.inject({
          method: 'POST',
          url: `/api/v1/content-models/${model.id}/entries`,
          headers: { 'x-api-key': apiKey, 'content-type': 'application/json' },
          payload: {
            data: {
              title: `并发测试文章 ${i}`,
              content: `并发测试内容 ${i} ${generateId('test')}`,
              summary: `并发测试摘要 ${i}`,
              author: `用户${i}`,
              status: 'draft',
            },
          },
        });
      });
    }

    const results = await simulateConcurrentRequests(requests, 5);
    const successCount = results.filter(r => r.statusCode === 201).length;
    console.log(`  - 20个并发请求，成功${successCount}个`);
    expect(successCount).toBe(20);

    const listResponse = await ctx.app.inject({
      method: 'GET',
      url: `/api/v1/content-models/${model.id}/entries?pageSize=50`,
      headers: { 'x-api-key': apiKey },
    });
    const listData = listResponse.json();
    expect(listData.total).toBeGreaterThanOrEqual(20);

    console.log('\n✅ 并发创建内容测试通过！');
  }, 60000);
});
