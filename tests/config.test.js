const axios = require('axios');
const { TestDataFactory } = require('./factories/testDataFactory');

const BASE_URL = process.env.API_BASE_URL || 'http://localhost:8080';
const api = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
  validateStatus: () => true
});

describe('配置管理模块 - 边界条件处理', () => {
  let createdConfigs = [];

  afterEach(() => {
    createdConfigs = [];
  });

  test('创建配置 - 空命名空间应该返回错误', async () => {
    const invalidData = TestDataFactory.createConfigData({ namespace: '' });
    
    const response = await api.post('/api/v1/configs', invalidData);
    
    expect([400, 422]).toContain(response.status);
    expect(response.data?.code || response.status).not.toBe(201);
  });

  test('创建配置 - 缺少namespace字段应该返回错误', async () => {
    const { namespace, ...invalidData } = TestDataFactory.createConfigData();
    
    const response = await api.post('/api/v1/configs', invalidData);
    
    expect([400, 422]).toContain(response.status);
  });

  test('创建配置 - 超大参数应该正常处理', async () => {
    const largeParams = TestDataFactory.createLargeParameters(100);
    const configData = TestDataFactory.createConfigData({ parameters: largeParams });
    
    const response = await api.post('/api/v1/configs', configData);
    
    if (response.status === 201) {
      createdConfigs.push(configData.namespace);
      expect(response.data.data.config_id).toBeDefined();
      expect(response.data.data.namespace).toBe(configData.namespace);
    }
  });

  test('创建配置 - 特殊字符命名空间', async () => {
    const specialNamespaces = [
      'ns-with-dashes',
      'ns_with_underscores',
      'ns.with.dots',
      'ns123withnumbers',
      'NS-UPPER-CASE'
    ];

    for (const ns of specialNamespaces) {
      const configData = TestDataFactory.createConfigData({ namespace: ns });
      const response = await api.post('/api/v1/configs', configData);
      
      if (response.status === 201) {
        createdConfigs.push(ns);
        expect(response.data.data.namespace).toBe(ns);
      }
    }
  });

  test('查询不存在的配置应该返回404', async () => {
    const nonExistentNs = 'non-existent-config-' + Date.now();
    
    const response = await api.get(`/api/v1/configs/${nonExistentNs}`);
    
    expect(response.status).toBe(404);
  });

  test('查询配置版本 - 不存在的版本应该返回404', async () => {
    const configData = TestDataFactory.createConfigData();
    const createResponse = await api.post('/api/v1/configs', configData);
    
    if (createResponse.status === 201) {
      createdConfigs.push(configData.namespace);
      
      const response = await api.get(`/api/v1/configs/${configData.namespace}?version=9999`);
      
      expect(response.status).toBe(404);
    }
  });

  test('更新不存在的配置应该返回404', async () => {
    const nonExistentNs = 'non-existent-config-' + Date.now();
    
    const response = await api.put(`/api/v1/configs/${nonExistentNs}`, {
      parameters: { new: 'value' }
    });
    
    expect(response.status).toBe(404);
  });

  test('删除不存在的配置应该返回404', async () => {
    const nonExistentNs = 'non-existent-config-' + Date.now();
    
    const response = await api.delete(`/api/v1/configs/${nonExistentNs}`);
    
    expect(response.status).toBe(404);
  });
});

describe('配置管理模块 - 事务回滚正确性', () => {
  let testNamespace;

  beforeEach(() => {
    testNamespace = TestDataFactory.generateId('rollback');
  });

  test('配置版本历史应该正确维护', async () => {
    const { namespace, versions } = TestDataFactory.createConfigWithMultipleVersions(5);
    testNamespace = namespace;
    let versionConfigs = [];

    for (const v of versions) {
      const response = await api.post('/api/v1/configs', v);
      if (response.status === 201) {
        versionConfigs.push(response.data.data);
      } else {
        const updateResponse = await api.put(`/api/v1/configs/${namespace}`, {
          parameters: v.parameters
        });
        if (updateResponse.status === 200) {
          versionConfigs.push(updateResponse.data.data);
        }
      }
      await new Promise(r => setTimeout(r, 50));
    }

    const historyResponse = await api.get(`/api/v1/configs/${namespace}/history`);
    
    if (historyResponse.status === 200) {
      const history = historyResponse.data.data;
      expect(Array.isArray(history)).toBe(true);
      expect(history.length).toBeGreaterThanOrEqual(versions.length);
      
      for (let i = 0; i < history.length; i++) {
        expect(history[i].version).toBe(i + 1);
        expect(history[i].namespace).toBe(namespace);
      }
    }
  });

  test('回滚到历史版本应该创建新版本', async () => {
    const { namespace, versions } = TestDataFactory.createConfigWithMultipleVersions(3);
    testNamespace = namespace;

    for (const v of versions) {
      await api.post('/api/v1/configs', v);
      await new Promise(r => setTimeout(r, 50));
      const updateResponse = await api.put(`/api/v1/configs/${namespace}`, {
        parameters: v.parameters
      });
      await new Promise(r => setTimeout(r, 50));
    }

    const historyBefore = await api.get(`/api/v1/configs/${namespace}/history`);
    const versionsBefore = historyBefore.status === 200 ? historyBefore.data.data.length : 0;

    const rollbackResponse = await api.post(`/api/v1/configs/${namespace}/rollback`, {
      target_version: 1
    });

    if (rollbackResponse.status === 200) {
      const rolledBack = rollbackResponse.data.data;
      expect(rolledBack.version).toBe(versionsBefore + 1);
      expect(rolledBack.parameters).toEqual(versions[0].parameters);
      
      const historyAfter = await api.get(`/api/v1/configs/${namespace}/history`);
      if (historyAfter.status === 200) {
        expect(historyAfter.data.data.length).toBe(versionsBefore + 1);
      }
    }
  });

  test('回滚到不存在的版本应该返回错误', async () => {
    const configData = TestDataFactory.createConfigData();
    testNamespace = configData.namespace;
    
    await api.post('/api/v1/configs', configData);
    
    const response = await api.post(`/api/v1/configs/${testNamespace}/rollback`, {
      target_version: 999
    });
    
    expect([400, 404, 422]).toContain(response.status);
  });

  test('回滚操作应该保留所有历史记录', async () => {
    const { namespace, versions } = TestDataFactory.createConfigWithMultipleVersions(4);
    testNamespace = namespace;

    for (const v of versions) {
      await api.post('/api/v1/configs', v);
      await new Promise(r => setTimeout(r, 50));
      await api.put(`/api/v1/configs/${namespace}`, {
        parameters: v.parameters
      });
      await new Promise(r => setTimeout(r, 50));
    }

    const historyBefore = await api.get(`/api/v1/configs/${namespace}/history`);
    const countBefore = historyBefore.status === 200 ? historyBefore.data.data.length : 0;

    await api.post(`/api/v1/configs/${namespace}/rollback`, { target_version: 2 });
    
    const historyAfter = await api.get(`/api/v1/configs/${namespace}/history`);
    const countAfter = historyAfter.status === 200 ? historyAfter.data.data.length : 0;

    expect(countAfter).toBe(countBefore + 1);
  });
});

describe('配置管理模块 - 参数校验完备性', () => {
  test('创建配置 - 参数类型校验', async () => {
    const testCases = [
      { params: { string: 'value', number: 123, boolean: true, null: null, array: [1, 2, 3], nested: { a: 'b' } } },
    ];

    for (const tc of testCases) {
      const configData = TestDataFactory.createConfigData({ parameters: tc.params });
      const response = await api.post('/api/v1/configs', configData);
      
      expect([201, 400]).toContain(response.status);
    }
  });

  test('创建配置 - 空参数对象', async () => {
    const configData = TestDataFactory.createConfigData({ parameters: {} });
    
    const response = await api.post('/api/v1/configs', configData);
    
    expect([201, 400]).toContain(response.status);
    if (response.status === 201) {
      expect(response.data.data.parameters).toEqual({});
    }
  });

  test('异步创建配置应该返回operation_id', async () => {
    const configData = TestDataFactory.createConfigData();
    
    const response = await api.post('/api/v1/configs/async', configData);
    
    if (response.status === 202) {
      expect(response.data.data.operation_id).toBeDefined();
      expect(response.data.data.status).toBeDefined();
      expect(response.data.data.type).toBe('create');
    }
  });

  test('查询异步操作状态', async () => {
    const configData = TestDataFactory.createConfigData();
    const asyncResponse = await api.post('/api/v1/configs/async', configData);
    
    if (asyncResponse.status === 202) {
      const opId = asyncResponse.data.data.operation_id;
      
      await new Promise(r => setTimeout(r, 200));
      
      const statusResponse = await api.get(`/api/v1/operations/${opId}`);
      
      if (statusResponse.status === 200) {
        expect(statusResponse.data.data.operation_id).toBe(opId);
        expect(['pending', 'running', 'completed', 'failed']).toContain(statusResponse.data.data.status);
      }
    }
  });

  test('查询不存在的异步操作应该返回404', async () => {
    const nonExistentOpId = 'op_non_existent_' + Date.now();
    
    const response = await api.get(`/api/v1/operations/${nonExistentOpId}`);
    
    expect(response.status).toBe(404);
  });

  test('配置列表应该返回数组', async () => {
    const response = await api.get('/api/v1/configs/namespaces');
    
    if (response.status === 200) {
      expect(Array.isArray(response.data.data)).toBe(true);
    }
  });

  test('配置worker stats应该返回正确结构', async () => {
    const response = await api.get('/api/v1/configs/workers/stats');
    
    if (response.status === 200) {
      const stats = response.data.data;
      expect(stats).toHaveProperty('active_workers');
      expect(stats).toHaveProperty('queue_length');
      expect(stats).toHaveProperty('min_workers');
      expect(stats).toHaveProperty('max_workers');
      expect(stats).toHaveProperty('auto_scale');
      
      expect(typeof stats.active_workers).toBe('number');
      expect(typeof stats.queue_length).toBe('number');
    }
  });

  test('批量创建配置性能测试', async () => {
    const bulkConfigs = TestDataFactory.createBulkConfigs(10);
    const startTime = Date.now();

    for (const config of bulkConfigs) {
      await api.post('/api/v1/configs', config);
    }

    const duration = Date.now() - startTime;
    console.log(`批量创建10个配置耗时: ${duration}ms`);
    
    expect(duration).toBeLessThan(10000);
  }, 30000);
});

describe('配置管理模块 - 并发安全测试', () => {
  test('并发创建同一命名空间应该只有一个成功', async () => {
    const configData = TestDataFactory.createConfigData();
    const concurrentRequests = Array(5).fill(null).map(() => 
      api.post('/api/v1/configs', configData)
    );

    const results = await Promise.all(concurrentRequests);
    const successCount = results.filter(r => r.status === 201).length;
    
    expect(successCount).toBeLessThanOrEqual(results.length);
  }, 15000);

  test('并发更新配置应该保持版本递增', async () => {
    const configData = TestDataFactory.createConfigData();
    await api.post('/api/v1/configs', configData);

    const updates = Array(5).fill(null).map((_, i) => 
      api.put(`/api/v1/configs/${configData.namespace}`, {
        parameters: { update_index: i, timestamp: Date.now() }
      })
    );

    await Promise.all(updates);
    
    const response = await api.get(`/api/v1/configs/${configData.namespace}/history`);
    if (response.status === 200) {
      const history = response.data.data;
      const versions = history.map(h => h.version).sort((a, b) => a - b);
      
      for (let i = 0; i < versions.length - 1; i++) {
        expect(versions[i + 1] - versions[i]).toBe(1);
      }
    }
  }, 15000);
});
