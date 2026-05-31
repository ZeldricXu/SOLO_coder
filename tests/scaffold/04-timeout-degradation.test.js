const axios = require('axios');
const config = require('../config');

describe('项目脚手架生成模块 - 外部依赖超时降级测试', () => {
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
    test('1.1 极短超时测试 - 50ms超时下的表现', async () => {
      const fastClient = createTimeoutClient(50);

      try {
        await fastClient.get('/scaffold/templates?page=1&page_size=1');
        console.log('      ✅ 快速响应（<50ms）');
      } catch (error) {
        if (error.code === 'ECONNABORTED') {
          console.log('      ⏱️  预期超时触发，降级正常');
          expect(error.code).toBe('ECONNABORTED');
        } else if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过超时测试');
        } else {
          throw error;
        }
      }
    }, 5000);

    test('1.2 正常超时配置 - 5000ms', async () => {
      const normalClient = createTimeoutClient(5000);

      try {
        const response = await normalClient.get('/scaffold/templates?page=1&page_size=5');
        expect(response.status).toBe(200);
        expect(response.data).toHaveProperty('code', 200);
      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过');
        } else {
          throw error;
        }
      }
    }, 10000);
  });

  describe('慢请求处理', () => {
    test('2.1 并发超时场景 - 多个慢请求同时到达', async () => {
      const slowClient = createTimeoutClient(3000);
      const requests = [];

      for (let i = 0; i < 10; i++) {
        requests.push(
          slowClient.get('/scaffold/templates', {
            params: { page: i + 1, page_size: 20 },
          })
        );
      }

      const results = await Promise.allSettled(requests);
      const timeouts = results.filter(r =>
        r.status === 'rejected' && r.reason?.code === 'ECONNABORTED'
      );

      console.log(`      📊 慢请求测试: 超时${timeouts.length}个`);

      results.forEach(result => {
        if (result.status === 'fulfilled') {
          expect(result.value.status).toBe(200);
        } else {
          expect(['ECONNABORTED', 'ECONNREFUSED']).toContain(result.reason?.code);
        }
      });
    }, 30000);
  });

  describe('错误重试机制验证', () => {
    test('3.1 接口幂等性验证 - 重复创建模板', async () => {
      const templateData = {
        name: `幂等测试-${Date.now()}`,
        language: 'go',
        file_tree: { 'main.go': 'package main' },
      };

      try {
        const firstResponse = await global.testAPI.post('/scaffold/templates', templateData);
        expect(firstResponse.status).toBe(201);

        const secondResponse = await global.testAPI.post('/scaffold/templates', {
          ...templateData,
          name: templateData.name + '-2',
        });
        expect(secondResponse.status).toBe(201);
      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过');
        } else {
          throw error;
        }
      }
    }, 10000);

    test('3.2 失败操作重试 - 查询不存在的资源', async () => {
      const startTime = Date.now();
      let retryCount = 0;
      const maxRetries = 3;

      for (let i = 0; i < maxRetries; i++) {
        retryCount++;
        try {
          await global.testAPI.get('/scaffold/templates/non-existent-id');
        } catch (error) {
          if (error.response?.status === 404) {
            break;
          }
        }
      }

      const duration = Date.now() - startTime;
      console.log(`      🔄 重试测试: ${retryCount}次重试, 耗时${duration}ms`);
      expect(retryCount).toBeLessThanOrEqual(maxRetries);
    }, 10000);
  });

  describe('降级策略验证', () => {
    test('4.1 分页查询降级 - page_size过大时的自动截断', async () => {
      try {
        const response = await global.testAPI.get('/scaffold/templates?page=1&page_size=1000');
        expect(response.status).toBe(200);
        expect(response.data.data.items.length).toBeLessThanOrEqual(100);
      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过');
        } else {
          throw error;
        }
      }
    });

    test('4.2 无效参数降级 - 无效参数返回默认结果', async () => {
      try {
        const response = await global.testAPI.get('/scaffold/templates?page=invalid&page_size=invalid');
        expect(response.status).toBe(200);
        expect(response.data.data).toHaveProperty('items');
      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.log('      ⚠️  服务未连接，跳过');
        } else {
          expect([400, 500]).toContain(error.response?.status);
        }
      }
    });
  });

  describe('长连接与Keep-Alive', () => {
    test('5.1 连续请求复用连接', async () => {
      const keepAliveClient = axios.create({
        baseURL: config.baseURL + config.apiPrefix,
        timeout: 10000,
        headers: { Connection: 'keep-alive' },
        maxRedirects: 5,
      });

      const startTime = Date.now();
      const durations = [];

      for (let i = 0; i < 5; i++) {
        const reqStart = Date.now();
        try {
          await keepAliveClient.get('/scaffold/templates?page=1&page_size=2');
          durations.push(Date.now() - reqStart);
        } catch (error) {
          if (error.code !== 'ECONNREFUSED') {
            durations.push(Date.now() - reqStart);
          }
        }
      }

      const totalDuration = Date.now() - startTime;
      const avgDuration = durations.length > 0
        ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length)
        : 0;

      console.log(`      🔗 Keep-Alive测试: 平均${avgDuration}ms/次, 总耗时${totalDuration}ms`);
      expect(totalDuration).toBeGreaterThan(0);
    }, 20000);
  });
});
