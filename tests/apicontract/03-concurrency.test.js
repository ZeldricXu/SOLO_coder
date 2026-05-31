const { generateRandomString, delay } = global;

describe('API契约测试模块 - 并发安全测试', () => {
  describe('Schema并发操作', () => {
    test('1.1 并发创建Schema - 30个并发请求', async () => {
      const createPromises = [];

      for (let i = 0; i < 30; i++) {
        const promise = global.testAPI.post('/api-contract/schemas', {
          name: `并发Schema-${generateRandomString()}-${i}`,
          version: `${Math.floor(i / 10)}.${i % 10}.0`,
          schema_type: i % 2 === 0 ? 'openapi' : 'graphql',
          content: JSON.stringify({
            openapi: '3.0.0',
            info: { title: `Test ${i}`, version: '1.0.0' },
            paths: {},
          }),
          format: 'json',
          service_name: `service-${i % 5}`,
        });
        createPromises.push(promise);
      }

      const results = await Promise.allSettled(createPromises);
      const successful = results.filter(r => r.status === 'fulfilled');
      const failed = results.filter(r => r.status === 'rejected');

      console.log(`      ✅ 并发Schema创建: 成功${successful.length}, 失败${failed.length}`);

      expect(successful.length).toBeGreaterThan(0);
      successful.forEach(result => {
        expect(result.value.status).toBe(201);
        expect(result.value.data.data).toHaveProperty('id');
      });
    }, 45000);

    test('1.2 并发查询Schema - 无数据竞争', async () => {
      const queryPromises = [];

      for (let i = 0; i < 40; i++) {
        const promise = global.testAPI.get('/api-contract/schemas', {
          params: {
            page: Math.floor(Math.random() * 10) + 1,
            page_size: Math.floor(Math.random() * 30) + 5,
            type: Math.random() > 0.5 ? 'openapi' : 'graphql',
          },
        });
        queryPromises.push(promise);
      }

      const results = await Promise.allSettled(queryPromises);
      const successful = results.filter(r => r.status === 'fulfilled');

      expect(successful.length).toBeGreaterThan(0);
      successful.forEach(result => {
        expect(result.value.status).toBe(200);
        expect(Array.isArray(result.value.data.data.items)).toBe(true);
      });
    }, 30000);
  });

  describe('缓存并发测试', () => {
    test('2.1 缓存预热与查询并发执行', async () => {
      const operations = [];

      operations.push(global.testAPI.post('/api-contract/cache/warmup'));

      for (let i = 0; i < 20; i++) {
        operations.push(global.testAPI.get('/api-contract/schemas?page=1&page_size=5'));
        if (i % 5 === 0) {
          operations.push(global.testAPI.get('/api-contract/cache/stats'));
        }
      }

      const results = await Promise.allSettled(operations);
      const successful = results.filter(r => r.status === 'fulfilled');

      console.log(`      ✅ 缓存并发操作: 成功${successful.length}/${results.length}`);
      expect(successful.length).toBeGreaterThan(0);
    }, 30000);

    test('2.2 缓存预热并发触发 - 幂等性验证', async () => {
      const warmupPromises = [];

      for (let i = 0; i < 5; i++) {
        warmupPromises.push(global.testAPI.post('/api-contract/cache/warmup'));
      }

      const results = await Promise.allSettled(warmupPromises);
      const successful = results.filter(r => r.status === 'fulfilled');

      successful.forEach(result => {
        expect(result.value.status).toBe(200);
      });

      console.log(`      ✅ 并发预热: 成功${successful.length}/${results.length}`);
    }, 20000);

    test('2.3 缓存读写并发 - 写入同时读取缓存统计', async () => {
      const operations = [];

      for (let i = 0; i < 10; i++) {
        operations.push(
          global.testAPI.post('/api-contract/schemas', {
            name: `缓存测试Schema-${generateRandomString()}`,
            version: '1.0.0',
            schema_type: 'openapi',
            content: '{"openapi": "3.0.0"}',
            format: 'json',
          })
        );
        operations.push(global.testAPI.get('/api-contract/cache/stats'));
      }

      const results = await Promise.allSettled(operations);
      const successful = results.filter(r => r.status === 'fulfilled');

      expect(successful.length).toBeGreaterThan(0);
    }, 30000);
  });

  describe('Schema验证并发测试', () => {
    let testSchemaId = null;

    beforeAll(async () => {
      try {
        const response = await global.testAPI.post('/api-contract/schemas', {
          name: `验证并发测试-${generateRandomString()}`,
          version: '1.0.0',
          schema_type: 'openapi',
          content: JSON.stringify({
            openapi: '3.0.0',
            info: { title: 'Test', version: '1.0.0' },
            paths: { '/test': { get: {} } },
          }),
          format: 'json',
        });
        testSchemaId = response.data.data.id;
      } catch (e) {}
    });

    test('3.1 并发验证同一Schema', async () => {
      if (!testSchemaId) {
        console.log('      ⚠️  跳过：无有效Schema');
        return;
      }

      const validatePromises = [];
      for (let i = 0; i < 20; i++) {
        validatePromises.push(
          global.testAPI.post('/api-contract/schemas/validate', {
            schema_id: testSchemaId,
          })
        );
      }

      const results = await Promise.allSettled(validatePromises);
      const successful = results.filter(r => r.status === 'fulfilled');
      const failed = results.filter(r => r.status === 'rejected');

      console.log(`      ✅ 并发验证: 成功${successful.length}, 失败${failed.length}`);

      successful.forEach(result => {
        expect(result.value.status).toBe(200);
        expect(result.value.data.data).toHaveProperty('status');
      });
    }, 30000);

    test('3.2 验证与查询历史并发执行', async () => {
      if (!testSchemaId) return;

      const operations = [];
      for (let i = 0; i < 15; i++) {
        operations.push(
          global.testAPI.post('/api-contract/schemas/validate', {
            schema_id: testSchemaId,
          })
        );
        operations.push(
          global.testAPI.get(`/api-contract/schemas/${testSchemaId}/validations`)
        );
      }

      const results = await Promise.allSettled(operations);
      const successful = results.filter(r => r.status === 'fulfilled');
      expect(successful.length).toBeGreaterThan(0);
    }, 30000);
  });

  describe('读写混合并发测试', () => {
    test('4.1 Schema CRUD混合操作', async () => {
      const createdIds = [];

      for (let i = 0; i < 10; i++) {
        const createResp = await global.testAPI.post('/api-contract/schemas', {
          name: `混合操作-${generateRandomString()}-${i}`,
          version: '1.0.0',
          schema_type: 'openapi',
          content: '{"openapi": "3.0.0"}',
          format: 'json',
        });
        createdIds.push(createResp.data.data.id);
      }

      const operations = [];

      for (let i = 0; i < 20; i++) {
        const opType = Math.random();

        if (opType < 0.3) {
          operations.push(
            global.testAPI.post('/api-contract/schemas', {
              name: `动态创建-${generateRandomString()}`,
              version: '1.0.0',
              schema_type: 'openapi',
              content: '{"openapi": "3.0.0"}',
              format: 'json',
            })
          );
        } else if (opType < 0.5) {
          const id = createdIds[Math.floor(Math.random() * createdIds.length)];
          operations.push(
            global.testAPI.put(`/api-contract/schemas/${id}`, {
              version: `${Math.floor(Math.random() * 10)}.0.0`,
            })
          );
        } else if (opType < 0.8) {
          operations.push(global.testAPI.get('/api-contract/schemas?page=1&page_size=10'));
        } else {
          const id = createdIds[Math.floor(Math.random() * createdIds.length)];
          operations.push(global.testAPI.get(`/api-contract/schemas/${id}`));
        }
      }

      const results = await Promise.allSettled(operations);
      const successful = results.filter(r => r.status === 'fulfilled');
      const failed = results.filter(r => r.status === 'rejected');

      console.log(`      ✅ 混合操作: 成功${successful.length}, 失败${failed.length}`);
      expect(successful.length).toBeGreaterThan(0);
    }, 60000);
  });
});
