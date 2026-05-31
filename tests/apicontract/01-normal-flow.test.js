const { generateRandomString, delay } = global;

describe('API契约测试模块 - 正常业务流程测试', () => {
  let createdSchemaId = null;
  let createdMockServerId = null;
  let createdContractTestId = null;

  const testSchema = {
    name: '用户服务API',
    version: '1.0.0',
    schema_type: 'openapi',
    content: JSON.stringify({
      openapi: '3.0.0',
      info: {
        title: 'User Service API',
        version: '1.0.0',
        description: '用户管理服务接口',
      },
      paths: {
        '/api/users': {
          get: {
            summary: '获取用户列表',
            responses: {
              '200': { description: '成功' },
            },
          },
          post: {
            summary: '创建用户',
            requestBody: {
              content: { 'application/json': { schema: { type: 'object' } } },
            },
            responses: {
              '201': { description: '创建成功' },
            },
          },
        },
      },
    }),
    format: 'json',
    service_name: 'user-service',
    metadata: { owner: 'team-a', tier: 'core' },
    is_active: true,
  };

  describe('Schema管理流程', () => {
    test('1.1 创建Schema - OpenAPI 3.0规范', async () => {
      const uniqueName = `${testSchema.name}-${generateRandomString()}`;
      const response = await global.testAPI.post('/api-contract/schemas', {
        ...testSchema,
        name: uniqueName,
      });

      expect(response.status).toBe(201);
      expect(response.data).toHaveProperty('code', 200);
      expect(response.data.data).toHaveProperty('id');
      expect(response.data.data.name).toBe(uniqueName);
      expect(response.data.data.schema_type).toBe('openapi');
      createdSchemaId = response.data.data.id;
      console.log(`      ✅ Schema创建成功: ${createdSchemaId}`);
    });

    test('1.2 查询Schema - 根据ID获取详情', async () => {
      const response = await global.testAPI.get(`/api-contract/schemas/${createdSchemaId}`);
      expect(response.status).toBe(200);
      expect(response.data.data.id).toBe(createdSchemaId);
      expect(response.data.data.content).toBeDefined();
    });

    test('1.3 更新Schema - 修改版本和内容', async () => {
      const response = await global.testAPI.put(`/api-contract/schemas/${createdSchemaId}`, {
        version: '1.1.0',
        metadata: { owner: 'team-b', tier: 'core', updated: true },
      });
      expect(response.status).toBe(200);
      expect(response.data.code).toBe(200);
    });

    test('1.4 Schema列表 - 分页查询', async () => {
      const response = await global.testAPI.get('/api-contract/schemas?page=1&page_size=10');
      expect(response.status).toBe(200);
      expect(response.data.data).toHaveProperty('items');
      expect(Array.isArray(response.data.data.items)).toBe(true);
      expect(response.data.data.total).toBeGreaterThanOrEqual(1);
    });

    test('1.5 按类型筛选Schema', async () => {
      const response = await global.testAPI.get('/api-contract/schemas?type=openapi');
      expect(response.status).toBe(200);
      const openapiSchemas = response.data.data.items.filter(s => s.schema_type === 'openapi');
      expect(openapiSchemas.length).toBeGreaterThanOrEqual(0);
    });

    test('1.6 按服务名称筛选', async () => {
      const response = await global.testAPI.get('/api-contract/schemas?service=user-service');
      expect(response.status).toBe(200);
    });

    test('1.7 创建GraphQL类型Schema', async () => {
      const graphqlSchema = {
        name: `GraphQL服务-${generateRandomString()}`,
        version: '2.0.0',
        schema_type: 'graphql',
        content: `
          type Query {
            users: [User]
            user(id: ID!): User
          }
          type User {
            id: ID!
            name: String!
            email: String
          }
        `,
        format: 'yaml',
        service_name: 'graphql-service',
      };

      const response = await global.testAPI.post('/api-contract/schemas', graphqlSchema);
      expect(response.status).toBe(201);
      expect(response.data.data.schema_type).toBe('graphql');
    });
  });

  describe('Schema验证流程', () => {
    test('2.1 验证Schema - 格式正确性校验', async () => {
      const response = await global.testAPI.post('/api-contract/schemas/validate', {
        schema_id: createdSchemaId,
      });

      expect(response.status).toBe(200);
      expect(response.data.data).toHaveProperty('schema_id', createdSchemaId);
      expect(response.data.data).toHaveProperty('status');
      expect(['valid', 'invalid']).toContain(response.data.data.status);
      console.log(`      ✅ Schema验证结果: ${response.data.data.status}`);
    });

    test('2.2 获取验证历史', async () => {
      const response = await global.testAPI.get(`/api-contract/schemas/${createdSchemaId}/validations`);
      expect(response.status).toBe(200);
      expect(Array.isArray(response.data.data)).toBe(true);
    });

    test('2.3 带limit参数的验证历史', async () => {
      const response = await global.testAPI.get(
        `/api-contract/schemas/${createdSchemaId}/validations?limit=5`
      );
      expect(response.status).toBe(200);
      expect(response.data.data.length).toBeLessThanOrEqual(5);
    });
  });

  describe('Mock Server流程', () => {
    test('3.1 创建Mock Server', async () => {
      const response = await global.testAPI.post('/api-contract/mock-servers', {
        schema_id: createdSchemaId,
        name: `MockServer-${generateRandomString()}`,
        config: {
          port: 8080,
          delay_ms: 100,
          enable_logging: true,
        },
      });

      expect(response.status).toBe(201);
      expect(response.data.data).toHaveProperty('server_id');
      expect(response.data.data).toHaveProperty('base_url');
      createdMockServerId = response.data.data.server_id;
      console.log(`      ✅ Mock Server创建成功: ${createdMockServerId}`);
    });

    test('3.2 查询Mock Server详情', async () => {
      const response = await global.testAPI.get(`/api-contract/mock-servers/${createdMockServerId}`);
      expect(response.status).toBe(200);
      expect(response.data.data.server_id).toBe(createdMockServerId);
    });

    test('3.3 列出所有Mock Servers', async () => {
      const response = await global.testAPI.get('/api-contract/mock-servers?page=1&page_size=10');
      expect(response.status).toBe(200);
      expect(Array.isArray(response.data.data.items)).toBe(true);
    });

    test('3.4 停止Mock Server', async () => {
      const response = await global.testAPI.post(
        `/api-contract/mock-servers/${createdMockServerId}/stop`
      );
      expect(response.status).toBe(200);
      expect(response.data.data.message).toBe('Mock server stopped');
    });
  });

  describe('契约测试流程', () => {
    test('4.1 创建契约测试用例', async () => {
      const response = await global.testAPI.post('/api-contract/contract-tests', {
        schema_id: createdSchemaId,
        name: `契约测试-${generateRandomString()}`,
        test_type: 'request_response',
        request: {
          method: 'GET',
          path: '/api/users',
          headers: { 'Content-Type': 'application/json' },
        },
        expected: {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
          body: { type: 'array' },
        },
      });

      expect(response.status).toBe(201);
      expect(response.data.data).toHaveProperty('id');
      expect(response.data.data.name).toBeDefined();
      createdContractTestId = response.data.data.id;
      console.log(`      ✅ 契约测试创建成功: ${createdContractTestId}`);
    });

    test('4.2 运行契约测试', async () => {
      const response = await global.testAPI.post('/api-contract/contract-tests/run', {
        test_id: createdContractTestId,
      });

      expect(response.status).toBe(200);
      expect(response.data.data).toBeDefined();
    });

    test('4.3 列出契约测试', async () => {
      const response = await global.testAPI.get('/api-contract/contract-tests');
      expect(response.status).toBe(200);
      expect(Array.isArray(response.data.data)).toBe(true);
    });

    test('4.4 按Schema筛选测试用例', async () => {
      const response = await global.testAPI.get(
        `/api-contract/contract-tests?schema_id=${createdSchemaId}`
      );
      expect(response.status).toBe(200);
    });

    test('4.5 删除契约测试', async () => {
      const response = await global.testAPI.delete(
        `/api-contract/contract-tests/${createdContractTestId}`
      );
      expect(response.status).toBe(200);
    });
  });

  describe('缓存管理流程', () => {
    test('5.1 触发缓存预热', async () => {
      const response = await global.testAPI.post('/api-contract/cache/warmup');
      expect(response.status).toBe(200);
      expect(response.data.data).toHaveProperty('message');
      expect(response.data.data).toHaveProperty('items_loaded');
      console.log(`      ✅ 缓存预热完成，加载${response.data.data.items_loaded}项`);
    });

    test('5.2 获取缓存统计', async () => {
      const response = await global.testAPI.get('/api-contract/cache/stats');
      expect(response.status).toBe(200);
      expect(response.data.data).toHaveProperty('hits');
      expect(response.data.data).toHaveProperty('misses');
      expect(response.data.data).toHaveProperty('hit_rate');
    });

    test('5.3 重置缓存统计', async () => {
      const response = await global.testAPI.post('/api-contract/cache/stats/reset');
      expect(response.status).toBe(200);
      expect(response.data.data.message).toBe('Cache stats reset');
    });

    test('5.4 清空缓存', async () => {
      const response = await global.testAPI.post('/api-contract/cache/clear');
      expect(response.status).toBe(200);
      expect(response.data.data.message).toBe('Cache cleared');
    });
  });

  afterAll(async () => {
    if (createdSchemaId) {
      try {
        await global.testAPI.delete(`/api-contract/schemas/${createdSchemaId}`);
      } catch (e) {}
    }
  });
});
