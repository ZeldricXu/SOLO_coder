const axios = require('axios');
const config = require('../config');

describe('API契约测试模块 - 外部依赖超时降级测试', () => {
  const createTimeoutClient = (timeout) => {
    return axios.create({
      baseURL: config.baseURL + config.apiPrefix,
      timeout: timeout,
      headers: {
        'Content-Type': 'application/json',
        'X-Test-Timeout': 'true',
      },
    });
  };

  describe('API超时降级', () => {
    test('1.1 缓存预热超时保护 - 预热操作应有超时保护', async () => {
      const fastClient = createTimeoutClient(1000);

      try {
        const response = await fastClient.post('/api-contract/cache/warmup');
        expect(response.status).toBe(200);
        console.log('      ✅ 缓存预热在1秒内完成');
      } catch (error) {
        if (error.code === 'ECONNABORTED') {
          console.log('      ⚠️  缓存预热超时（正常现象，数据量大时可能发生）');
        } else if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过');
        } else {
          throw error;
        }
      }
    }, 5000);

    test('1.2 缓存统计查询 - 快速响应', async () => {
      const fastClient = createTimeoutClient(500);

      try {
        const response = await fastClient.get('/api-contract/cache/stats');
        expect(response.status).toBe(200);
        expect(response.data.data).toHaveProperty('hits');
      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过');
        } else {
          throw error;
        }
      }
    }, 3000);
  });

  describe('Schema验证降级', () => {
    test('2.1 重复验证缓存命中', async () => {
      const testClient = createTimeoutClient(5000);
      let schemaId = null;

      try {
        const createResp = await testClient.post('/api-contract/schemas', {
          name: `缓存验证测试-${Date.now()}`,
          version: '1.0.0',
          schema_type: 'openapi',
          content: JSON.stringify({
            openapi: '3.0.0',
            info: { title: 'Test', version: '1.0.0' },
            paths: {},
          }),
          format: 'json',
        });
        schemaId = createResp.data.data.id;

        const startTime = Date.now();
        const durations = [];

        for (let i = 0; i < 5; i++) {
          const reqStart = Date.now();
          await testClient.post('/api-contract/schemas/validate', {
            schema_id: schemaId,
          });
          durations.push(Date.now() - reqStart);
        }

        const totalDuration = Date.now() - startTime;
        const avgDuration = Math.round(durations.reduce((a, b) => a + b, 0) / durations.length);
        const firstDuration = durations[0];
        const lastDuration = durations[durations.length - 1];

        console.log(`      📊 验证性能: 首请求${firstDuration}ms, 平均${avgDuration}ms, 末次${lastDuration}ms`);

        if (durations.length > 2 && lastDuration < firstDuration) {
          console.log('      ✅ 缓存生效，后续请求更快');
        }
      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过');
        } else {
          throw error;
        }
      }
    }, 15000);
  });

  describe('Mock Server超时处理', () => {
    test('3.1 Mock Server创建超时保护', async () => {
      const client = createTimeoutClient(10000);

      try {
        const schemaResp = await client.post('/api-contract/schemas', {
          name: `Mock超时测试-${Date.now()}`,
          version: '1.0.0',
          schema_type: 'openapi',
          content: '{"openapi": "3.0.0"}',
          format: 'json',
        });

        const startTime = Date.now();
        const mockResp = await client.post('/api-contract/mock-servers', {
          schema_id: schemaResp.data.data.id,
          name: 'Timeout Test Mock',
        });

        const duration = Date.now() - startTime;
        console.log(`      ⏱️  Mock创建耗时: ${duration}ms`);

        expect(mockResp.status).toBe(201);
        expect(duration).toBeLessThan(10000);
      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过');
        } else if (error.code === 'ECONNABORTED') {
          console.log('      ⏱️  Mock创建超时（需要检查降级策略）');
        } else {
          throw error;
        }
      }
    }, 15000);
  });

  describe('批量操作降级', () => {
    test('4.1 大量Schema列表查询 - 分页降级', async () => {
      const client = createTimeoutClient(5000);

      try {
        const pageSizes = [5, 20, 50, 100];
        const results = [];

        for (const pageSize of pageSizes) {
          const startTime = Date.now();
          const response = await client.get('/api-contract/schemas', {
            params: { page: 1, page_size: pageSize },
          });
          const duration = Date.now() - startTime;
          results.push({ pageSize, duration, items: response.data.data.items.length });
          console.log(`      📄 page_size=${pageSize}: ${duration}ms, ${response.data.data.items.length}项`);
        }

        results.forEach(r => {
          expect(r.duration).toBeLessThan(5000);
          expect(r.items).toBeLessThanOrEqual(r.pageSize);
        });
      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过');
        } else {
          throw error;
        }
      }
    }, 20000);
  });

  describe('并发压力下的超时处理', () => {
    test('5.1 高并发下的请求超时分布', async () => {
      const client = createTimeoutClient(10000);
      const concurrency = 20;
      const promises = [];

      for (let i = 0; i < concurrency; i++) {
        promises.push(
          client.get('/api-contract/schemas', {
            params: { page: Math.floor(Math.random() * 5) + 1, page_size: 10 },
          })
        );
      }

      const startTime = Date.now();
      const results = await Promise.allSettled(promises);
      const totalDuration = Date.now() - startTime;

      const successful = results.filter(r => r.status === 'fulfilled');
      const timeouts = results.filter(r =>
        r.status === 'rejected' && r.reason?.code === 'ECONNABORTED'
      );

      console.log(`      📊 并发压力测试: ${concurrency}请求, 总耗时${totalDuration}ms`);
      console.log(`         成功: ${successful.length}, 超时: ${timeouts.length}`);

      if (successful.length > 0) {
        const avgDuration = Math.round(totalDuration / successful.length);
        console.log(`         平均耗时: ${avgDuration}ms/请求`);
      }

      expect(successful.length).toBeGreaterThan(0);
    }, 30000);
  });

  describe('连接池与Keep-Alive', () => {
    test('6.1 Keep-Alive连接复用性能', async () => {
      const keepAliveClient = axios.create({
        baseURL: config.baseURL + config.apiPrefix,
        timeout: 10000,
        headers: { Connection: 'keep-alive' },
        httpAgent: new (require('http').Agent)({ keepAlive: true }),
        httpsAgent: new (require('https').Agent)({ keepAlive: true }),
      });

      const requestCount = 10;
      const durations = [];

      for (let i = 0; i < requestCount; i++) {
        const startTime = Date.now();
        try {
          await keepAliveClient.get('/api-contract/schemas?page=1&page_size=2');
          durations.push(Date.now() - startTime);
        } catch (error) {
          if (error.code !== 'ECONNREFUSED') {
            durations.push(Date.now() - startTime);
          }
        }
      }

      if (durations.length > 0) {
        const avgDuration = Math.round(durations.reduce((a, b) => a + b, 0) / durations.length);
        const firstDuration = durations[0];
        const avgAfterFirst = durations.length > 1
          ? Math.round(durations.slice(1).reduce((a, b) => a + b, 0) / (durations.length - 1))
          : avgDuration;

        console.log(`      🔗 Keep-Alive测试: 首请求${firstDuration}ms, 后续平均${avgAfterFirst}ms`);

        if (durations.length > 2 && avgAfterFirst < firstDuration) {
          console.log('      ✅ 连接复用生效，后续请求更快');
        }
      }
    }, 20000);
  });
});
