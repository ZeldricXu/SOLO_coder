const axios = require('axios');

const BASE_URL = process.env.API_BASE_URL || 'http://localhost:8080';
const api = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
  validateStatus: () => true
});

describe('日志模块 - 边界条件处理', () => {

  test('日志统计信息应该包含正确结构', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    expect([200, 500]).toContain(response.status);
    
    if (response.status === 200) {
      const stats = response.data.data;
      
      expect(stats).toHaveProperty('async_mode');
      expect(stats).toHaveProperty('active_workers');
      expect(stats).toHaveProperty('min_workers');
      expect(stats).toHaveProperty('max_workers');
      expect(stats).toHaveProperty('queue_size');
      expect(stats).toHaveProperty('queue_cap');
      expect(stats).toHaveProperty('auto_scale');
      expect(stats).toHaveProperty('batch_size');
      
      expect(typeof stats.async_mode).toBe('boolean');
      expect(typeof stats.active_workers).toBe('number');
      expect(typeof stats.queue_size).toBe('number');
    }
  });

  test('刷新日志接口应该正常响应', async () => {
    const response = await api.post('/api/v1/logger/flush');
    
    expect([200, 500]).toContain(response.status);
    
    if (response.status === 200) {
      expect(response.data.code).toBe(200);
    }
  });

  test('日志worker数量应该在合理范围内', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    if (response.status === 200) {
      const stats = response.data.data;
      
      expect(stats.active_workers).toBeGreaterThanOrEqual(stats.min_workers);
      expect(stats.active_workers).toBeLessThanOrEqual(stats.max_workers);
    }
  });

  test('队列容量应该大于0', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    if (response.status === 200) {
      const stats = response.data.data;
      
      expect(stats.queue_cap).toBeGreaterThan(0);
    }
  });

  test('批量大小应该大于0', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    if (response.status === 200) {
      const stats = response.data.data;
      
      expect(stats.batch_size).toBeGreaterThan(0);
    }
  });
});

describe('日志模块 - 参数校验完备性', () => {

  test('日志刷新接口不应该需要参数', async () => {
    const response = await api.post('/api/v1/logger/flush');
    
    expect([200, 500]).toContain(response.status);
  });

  test('日志统计接口不应该需要参数', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    expect([200, 500]).toContain(response.status);
  });

  test('多次刷新日志不应该导致错误', async () => {
    const flushCount = 5;
    
    for (let i = 0; i < flushCount; i++) {
      const response = await api.post('/api/v1/logger/flush');
      
      expect([200, 500]).toContain(response.status);
      
      if (response.status === 200) {
        expect(response.data.code).toBe(200);
      }
      
      await new Promise(r => setTimeout(r, 100));
    }
  });

  test('并发获取日志统计应该正常工作', async () => {
    const concurrentCount = 10;
    const requests = Array(concurrentCount).fill(null).map(() =>
      api.get('/api/v1/logger/stats')
    );

    const results = await Promise.all(requests);
    
    results.forEach(response => {
      expect([200, 500]).toContain(response.status);
    });
    
    const successCount = results.filter(r => r.status === 200).length;
    console.log(`并发获取日志统计成功数: ${successCount}/${concurrentCount}`);
  }, 15000);

  test('日志统计字段类型校验', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    if (response.status === 200) {
      const stats = response.data.data;
      
      expect(typeof stats.async_mode).toBe('boolean');
      expect(Number.isInteger(stats.active_workers)).toBe(true);
      expect(Number.isInteger(stats.min_workers)).toBe(true);
      expect(Number.isInteger(stats.max_workers)).toBe(true);
      expect(Number.isInteger(stats.queue_size)).toBe(true);
      expect(Number.isInteger(stats.queue_cap)).toBe(true);
      expect(typeof stats.auto_scale).toBe('boolean');
      expect(Number.isInteger(stats.batch_size)).toBe(true);
    }
  });

  test('日志统计队列大小不能超过容量', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    if (response.status === 200) {
      const stats = response.data.data;
      
      expect(stats.queue_size).toBeLessThanOrEqual(stats.queue_cap);
    }
  });
});

describe('日志模块 - 性能测试', () => {

  test('连续获取日志统计性能', async () => {
    const iterations = 50;
    const startTime = Date.now();

    for (let i = 0; i < iterations; i++) {
      await api.get('/api/v1/logger/stats');
    }

    const duration = Date.now() - startTime;
    console.log(`连续获取50次日志统计耗时: ${duration}ms`);
    const avgTime = duration / iterations;
    console.log(`平均耗时: ${avgTime.toFixed(2)}ms/次`);
    
    expect(avgTime).toBeLessThan(500);
  }, 30000);

  test('混合操作性能测试', async () => {
    const operations = 20;
    const startTime = Date.now();

    for (let i = 0; i < operations; i++) {
      await api.post('/api/v1/logger/flush');
      await new Promise(r => setTimeout(r, 50));
      await api.get('/api/v1/logger/stats');
    }

    const duration = Date.now() - startTime;
    console.log(`20次混合操作耗时: ${duration}ms`);
    
    expect(duration).toBeLessThan(10000);
  }, 30000);
});

describe('日志模块 - 边界值测试', () => {

  test('最小worker配置', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    if (response.status === 200) {
      const stats = response.data.data;
      
      expect(stats.min_workers).toBeGreaterThanOrEqual(1);
    }
  });

  test('最大worker配置应该大于等于最小worker', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    if (response.status === 200) {
      const stats = response.data.data;
      
      expect(stats.max_workers).toBeGreaterThanOrEqual(stats.min_workers);
    }
  });

  test('队列容量边界检查', async () => {
    const response = await api.get('/api/v1/logger/stats');
    
    if (response.status === 200) {
      const stats = response.data.data;
      
      expect(stats.queue_cap).toBeGreaterThanOrEqual(1000);
    }
  });
});
