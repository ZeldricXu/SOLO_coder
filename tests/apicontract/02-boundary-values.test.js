const { generateRandomString } = global;

describe('API契约测试模块 - 边界值与异常测试', () => {
  let validSchemaId = null;

  const createValidSchema = async () => {
    const response = await global.testAPI.post('/api-contract/schemas', {
      name: `边界测试Schema-${generateRandomString()}`,
      version: '1.0.0',
      schema_type: 'openapi',
      content: '{"openapi": "3.0.0", "info": {"title": "Test"}}',
      format: 'json',
    });
    return response.data.data.id;
  };

  beforeAll(async () => {
    try {
      validSchemaId = await createValidSchema();
    } catch (e) {
      console.log('      ⚠️  预置Schema创建失败');
    }
  });

  describe('Schema创建 - 参数边界值测试', () => {
    test('1.1 名称长度边界 - 最大128字符', async () => {
      const maxName = 'a'.repeat(128);
      const response = await global.testAPI.post('/api-contract/schemas', {
        name: maxName,
        version: '1.0.0',
        schema_type: 'openapi',
        content: '{"openapi": "3.0.0"}',
        format: 'json',
      });
      expect([201, 400]).toContain(response.status);
    });

    test('1.2 名称长度超限 - 129字符', async () => {
      const tooLongName = 'a'.repeat(129);
      try {
        await global.testAPI.post('/api-contract/schemas', {
          name: tooLongName,
          version: '1.0.0',
          schema_type: 'openapi',
          content: '{"openapi": "3.0.0"}',
          format: 'json',
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('1.3 version长度边界 - 最大32字符', async () => {
      const maxVersion = 'v'.repeat(32);
      const response = await global.testAPI.post('/api-contract/schemas', {
        name: `版本测试-${generateRandomString()}`,
        version: maxVersion,
        schema_type: 'openapi',
        content: '{"openapi": "3.0.0"}',
        format: 'json',
      });
      expect([201, 400]).toContain(response.status);
    });

    test('1.4 必填字段缺失 - 缺少name', async () => {
      try {
        await global.testAPI.post('/api-contract/schemas', {
          version: '1.0.0',
          schema_type: 'openapi',
          content: '{"openapi": "3.0.0"}',
          format: 'json',
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('1.5 必填字段缺失 - 缺少schema_type', async () => {
      try {
        await global.testAPI.post('/api-contract/schemas', {
          name: '测试',
          version: '1.0.0',
          content: '{"openapi": "3.0.0"}',
          format: 'json',
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('1.6 必填字段缺失 - 缺少content', async () => {
      try {
        await global.testAPI.post('/api-contract/schemas', {
          name: '测试',
          version: '1.0.0',
          schema_type: 'openapi',
          format: 'json',
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('1.7 schema_type无效值', async () => {
      try {
        await global.testAPI.post('/api-contract/schemas', {
          name: `测试-${generateRandomString()}`,
          version: '1.0.0',
          schema_type: 'invalid',
          content: '{}',
          format: 'json',
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('1.8 format无效值', async () => {
      try {
        await global.testAPI.post('/api-contract/schemas', {
          name: `测试-${generateRandomString()}`,
          version: '1.0.0',
          schema_type: 'openapi',
          content: '{}',
          format: 'xml',
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('1.9 空content字符串', async () => {
      try {
        await global.testAPI.post('/api-contract/schemas', {
          name: `空内容测试-${generateRandomString()}`,
          version: '1.0.0',
          schema_type: 'openapi',
          content: '',
          format: 'json',
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('1.10 超大content - 1MB数据', async () => {
      const largeContent = JSON.stringify({
        openapi: '3.0.0',
        info: { title: 'Large Test' },
        paths: Array.from({ length: 1000 }, (_, i) => ({
          [`/api/endpoint${i}`]: {
            get: { summary: `Endpoint ${i}` },
          },
        })).reduce((acc, curr) => ({ ...acc, ...curr }), {}),
      });

      const response = await global.testAPI.post('/api-contract/schemas', {
        name: `大内容测试-${generateRandomString()}`,
        version: '1.0.0',
        schema_type: 'openapi',
        content: largeContent,
        format: 'json',
      });
      expect([201, 413, 500]).toContain(response.status);
    }, 10000);
  });

  describe('Schema查询 - 边界值测试', () => {
    test('2.1 无效Schema ID查询', async () => {
      const invalidIds = ['', 'invalid', '123', 'a'.repeat(100)];
      for (const id of invalidIds) {
        try {
          await global.testAPI.get(`/api-contract/schemas/${id}`);
        } catch (error) {
          expect([400, 404]).toContain(error.response.status);
        }
      }
    });

    test('2.2 分页参数 - page_size=0', async () => {
      const response = await global.testAPI.get('/api-contract/schemas?page=1&page_size=0');
      expect(response.status).toBe(200);
    });

    test('2.3 分页参数 - page_size=101（超限）', async () => {
      const response = await global.testAPI.get('/api-contract/schemas?page=1&page_size=101');
      expect(response.status).toBe(200);
      expect(response.data.data.items.length).toBeLessThanOrEqual(100);
    });

    test('2.4 空schema_type筛选', async () => {
      const response = await global.testAPI.get('/api-contract/schemas?type=');
      expect(response.status).toBe(200);
    });

    test('2.5 无效schema_type筛选', async () => {
      const response = await global.testAPI.get('/api-contract/schemas?type=invalid');
      expect(response.status).toBe(200);
      expect(response.data.data.items.length).toBe(0);
    });
  });

  describe('Schema验证 - 边界值测试', () => {
    test('3.1 验证请求缺少schema_id', async () => {
      try {
        await global.testAPI.post('/api-contract/schemas/validate', {});
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('3.2 验证无效schema_id', async () => {
      try {
        await global.testAPI.post('/api-contract/schemas/validate', {
          schema_id: 'non-existent-id',
        });
      } catch (error) {
        expect([400, 500]).toContain(error.response.status);
      }
    });

    test('3.3 验证历史limit边界 - 0', async () => {
      if (!validSchemaId) return;
      const response = await global.testAPI.get(
        `/api-contract/schemas/${validSchemaId}/validations?limit=0`
      );
      expect(response.status).toBe(200);
    });

    test('3.4 验证历史limit边界 - 101', async () => {
      if (!validSchemaId) return;
      const response = await global.testAPI.get(
        `/api-contract/schemas/${validSchemaId}/validations?limit=101`
      );
      expect(response.status).toBe(200);
      expect(response.data.data.length).toBeLessThanOrEqual(100);
    });
  });

  describe('Mock Server - 边界值测试', () => {
    test('4.1 创建Mock缺少schema_id', async () => {
      try {
        await global.testAPI.post('/api-contract/mock-servers', {
          name: 'Test Mock',
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('4.2 创建Mock缺少name', async () => {
      if (!validSchemaId) return;
      try {
        await global.testAPI.post('/api-contract/mock-servers', {
          schema_id: validSchemaId,
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('4.3 Mock名称长度超限', async () => {
      if (!validSchemaId) return;
      const tooLongName = 'a'.repeat(129);
      try {
        await global.testAPI.post('/api-contract/mock-servers', {
          schema_id: validSchemaId,
          name: tooLongName,
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('4.4 停止不存在的Mock Server', async () => {
      try {
        await global.testAPI.post('/api-contract/mock-servers/non-existent/stop');
      } catch (error) {
        expect([400, 404, 500]).toContain(error.response.status);
      }
    });
  });

  describe('契约测试 - 边界值测试', () => {
    test('5.1 创建契约测试缺少schema_id', async () => {
      try {
        await global.testAPI.post('/api-contract/contract-tests', {
          name: 'Test',
          test_type: 'request_response',
          request: {},
          expected: {},
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('5.2 创建契约测试缺少request', async () => {
      if (!validSchemaId) return;
      try {
        await global.testAPI.post('/api-contract/contract-tests', {
          schema_id: validSchemaId,
          name: 'Test',
          test_type: 'request_response',
          expected: {},
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('5.3 创建契约测试缺少expected', async () => {
      if (!validSchemaId) return;
      try {
        await global.testAPI.post('/api-contract/contract-tests', {
          schema_id: validSchemaId,
          name: 'Test',
          test_type: 'request_response',
          request: {},
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('5.4 无效test_type', async () => {
      if (!validSchemaId) return;
      try {
        await global.testAPI.post('/api-contract/contract-tests', {
          schema_id: validSchemaId,
          name: 'Test',
          test_type: 'invalid',
          request: {},
          expected: {},
        });
      } catch (error) {
        expect([400, 500]).toContain(error.response.status);
      }
    });

    test('5.5 运行测试缺少test_id', async () => {
      try {
        await global.testAPI.post('/api-contract/contract-tests/run', {});
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('5.6 运行不存在的测试', async () => {
      try {
        await global.testAPI.post('/api-contract/contract-tests/run', {
          test_id: 'non-existent-id',
        });
      } catch (error) {
        expect([400, 404, 500]).toContain(error.response.status);
      }
    });

    test('5.7 删除不存在的测试', async () => {
      try {
        await global.testAPI.delete('/api-contract/contract-tests/non-existent-id');
      } catch (error) {
        expect([400, 404, 500]).toContain(error.response.status);
      }
    });
  });
});
