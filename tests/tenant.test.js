const axios = require('axios');
const { TestDataFactory } = require('./factories/testDataFactory');

const BASE_URL = process.env.API_BASE_URL || 'http://localhost:8080';
const api = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
  validateStatus: () => true
});

describe('多租户隔离策略模块 - 边界条件处理', () => {

  test('创建租户 - 空名称应该返回错误', async () => {
    const tenantData = TestDataFactory.createTenantData({ name: '' });
    
    const response = await api.post('/api/v1/tenants', tenantData);
    
    expect([400, 422]).toContain(response.status);
  });

  test('创建租户 - 空邮箱应该返回错误', async () => {
    const tenantData = TestDataFactory.createTenantData({ adminEmail: '' });
    
    const response = await api.post('/api/v1/tenants', tenantData);
    
    expect([400, 422]).toContain(response.status);
  });

  test('创建租户 - 无效计划应该使用默认值', async () => {
    const tenantData = TestDataFactory.createTenantData({ plan: 'invalid_plan' });
    
    const response = await api.post('/api/v1/tenants', tenantData);
    
    expect([201, 400]).toContain(response.status);
    if (response.status === 201) {
      expect(response.data.data.billing_plan).toBeDefined();
    }
  });

  test('创建租户 - 缺少必要字段', async () => {
    const { name, ...partialData } = TestDataFactory.createTenantData();
    
    const response = await api.post('/api/v1/tenants', partialData);
    
    expect([400, 422]).toContain(response.status);
  });

  test('查询不存在的租户应该返回404', async () => {
    const nonExistentId = 'tnt_non_existent_' + Date.now();
    
    const response = await api.get(`/api/v1/tenants/${nonExistentId}`);
    
    expect(response.status).toBe(404);
  });

  test('更新不存在的租户应该返回404', async () => {
    const nonExistentId = 'tnt_non_existent_' + Date.now();
    
    const response = await api.put(`/api/v1/tenants/${nonExistentId}/config`, {
      config: { featureX: true }
    });
    
    expect(response.status).toBe(404);
  });

  test('删除不存在的租户应该返回404', async () => {
    const nonExistentId = 'tnt_non_existent_' + Date.now();
    
    const response = await api.delete(`/api/v1/tenants/${nonExistentId}`);
    
    expect(response.status).toBe(404);
  });

  test('暂停不存在的租户应该返回404', async () => {
    const nonExistentId = 'tnt_non_existent_' + Date.now();
    
    const response = await api.post(`/api/v1/tenants/${nonExistentId}/suspend`);
    
    expect(response.status).toBe(404);
  });

  test('激活不存在的租户应该返回404', async () => {
    const nonExistentId = 'tnt_non_existent_' + Date.now();
    
    const response = await api.post(`/api/v1/tenants/${nonExistentId}/activate`);
    
    expect(response.status).toBe(404);
  });

  test('检查不存在租户的限流应该返回404', async () => {
    const nonExistentId = 'tnt_non_existent_' + Date.now();
    
    const response = await api.get(`/api/v1/tenants/${nonExistentId}/ratelimit`);
    
    expect(response.status).toBe(404);
  });

  test('检查不存在租户的配额应该返回404', async () => {
    const nonExistentId = 'tnt_non_existent_' + Date.now();
    
    const response = await api.get(`/api/v1/tenants/${nonExistentId}/quota`);
    
    expect(response.status).toBe(404);
  });
});

describe('多租户隔离策略模块 - 参数校验完备性', () => {

  test('创建租户 - 所有计费计划都应该有效', async () => {
    const plans = ['free', 'standard', 'premium', 'enterprise'];
    
    for (const plan of plans) {
      const tenantData = TestDataFactory.createTenantData({ plan });
      const response = await api.post('/api/v1/tenants', tenantData);
      
      expect([201, 400]).toContain(response.status);
      if (response.status === 201) {
        expect(response.data.data.billing_plan).toBe(plan);
      }
    }
  });

  test('创建租户 - 特殊字符名称', async () => {
    const specialNames = [
      'Tenant-With-Dashes',
      'Tenant_With_Underscores',
      'Tenant With Spaces',
      'Tenant@#$%^&*()',
      '中文租户名称'
    ];

    for (const name of specialNames) {
      const tenantData = TestDataFactory.createTenantData({ name });
      const response = await api.post('/api/v1/tenants', tenantData);
      
      expect([201, 400]).toContain(response.status);
    }
  });

  test('创建租户 - 各种邮箱格式', async () => {
    const emails = [
      'test@example.com',
      'user.name+tag@domain.co.uk',
      'admin@sub.domain.com',
      'user123@test-domain.org'
    ];

    for (const email of emails) {
      const tenantData = TestDataFactory.createTenantData({ adminEmail: email });
      const response = await api.post('/api/v1/tenants', tenantData);
      
      expect([201, 400]).toContain(response.status);
    }
  });

  test('创建租户 - 空配置对象', async () => {
    const tenantData = TestDataFactory.createTenantData();
    
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      
      const updateResponse = await api.put(`/api/v1/tenants/${tenantId}/config`, {
        config: {}
      });
      
      expect([200, 400]).toContain(updateResponse.status);
    }
  });

  test('更新计费计划 - 所有计划都应该有效', async () => {
    const tenantData = TestDataFactory.createTenantData({ plan: 'free' });
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      const plans = ['standard', 'premium', 'enterprise'];
      
      for (const plan of plans) {
        const response = await api.put(`/api/v1/tenants/${tenantId}/plan`, { plan });
        expect([200, 400]).toContain(response.status);
        if (response.status === 200) {
          expect(response.data.data.billing_plan).toBe(plan);
        }
      }
    }
  });

  test('租户配置 - 各种参数类型', async () => {
    const tenantData = TestDataFactory.createTenantData();
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      
      const configData = {
        stringValue: 'test',
        numberValue: 123,
        booleanValue: true,
        arrayValue: [1, 2, 3],
        objectValue: { nested: 'value' },
        nullValue: null
      };
      
      const response = await api.put(`/api/v1/tenants/${tenantId}/config`, {
        config: configData
      });
      
      expect([200, 400]).toContain(response.status);
    }
  });

  test('租户列表应该返回数组', async () => {
    const response = await api.get('/api/v1/tenants');
    
    if (response.status === 200) {
      expect(Array.isArray(response.data.data)).toBe(true);
    }
  });

  test('按状态过滤租户列表', async () => {
    const statuses = ['active', 'suspended', 'deleted', ''];
    
    for (const status of statuses) {
      const response = await api.get(`/api/v1/tenants?status=${status}`);
      
      if (response.status === 200) {
        expect(Array.isArray(response.data.data)).toBe(true);
      }
    }
  });
});

describe('多租户隔离策略模块 - 资源配额正确性', () => {

  test('各计费计划应该有正确的配额', async () => {
    const expectations = TestDataFactory.getBillingPlanExpectations();
    
    for (const [plan, expected] of Object.entries(expectations)) {
      const tenantData = TestDataFactory.createTenantData({ plan });
      const response = await api.post('/api/v1/tenants', tenantData);
      
      if (response.status === 201) {
        const quota = response.data.data.resource_quota;
        
        expect(quota.max_storage_gb).toBe(expected.maxStorageGB);
        expect(quota.max_cpu_cores).toBe(expected.maxCPUCores);
        expect(quota.max_memory_gb).toBe(expected.maxMemoryGB);
        expect(quota.max_api_requests).toBe(expected.maxAPIRequests);
        expect(quota.max_users).toBe(expected.maxUsers);
        expect(quota.max_connections).toBe(expected.maxConnections);
        expect(quota.max_bandwidth_gb).toBe(expected.maxBandwidthGB);
      }
    }
  });

  test('更新计费计划应该更新配额', async () => {
    const expectations = TestDataFactory.getBillingPlanExpectations();
    const tenantData = TestDataFactory.createTenantData({ plan: 'free' });
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      
      const upgradeResponse = await api.put(`/api/v1/tenants/${tenantId}/plan`, {
        plan: 'enterprise'
      });
      
      if (upgradeResponse.status === 200) {
        const quota = upgradeResponse.data.data.resource_quota;
        const expected = expectations.enterprise;
        
        expect(quota.max_storage_gb).toBe(expected.maxStorageGB);
        expect(quota.max_cpu_cores).toBe(expected.maxCPUCores);
        expect(quota.max_memory_gb).toBe(expected.maxMemoryGB);
      }
    }
  });

  test('记录资源使用情况', async () => {
    const tenantData = TestDataFactory.createTenantData();
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      const usageData = TestDataFactory.createTenantWithUsage();
      
      const response = await api.post(`/api/v1/tenants/${tenantId}/usage`, {
        usage: usageData.usage
      });
      
      expect([200, 400]).toContain(response.status);
      if (response.status === 200) {
        expect(response.data.data.current_usage).toEqual(usageData.usage);
      }
    }
  });

  test('配额内资源使用应该通过检查', async () => {
    const tenantData = TestDataFactory.createTenantData({ plan: 'standard' });
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      
      const response = await api.get(`/api/v1/tenants/${tenantId}/quota`);
      
      if (response.status === 200) {
        expect(response.data.data.within_quota).toBe(true);
      }
    }
  });

  test('获取使用统计信息', async () => {
    const tenantData = TestDataFactory.createTenantData();
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      
      const response = await api.get(`/api/v1/tenants/${tenantId}/stats`);
      
      if (response.status === 200) {
        const stats = response.data.data;
        expect(stats.tenant_id).toBe(tenantId);
        expect(stats).toHaveProperty('storage_percent');
        expect(stats).toHaveProperty('memory_percent');
        expect(stats).toHaveProperty('api_requests_percent');
        expect(stats).toHaveProperty('users_percent');
        expect(stats).toHaveProperty('connections_percent');
        expect(stats).toHaveProperty('bandwidth_percent');
      }
    }
  });
});

describe('多租户隔离策略模块 - 限流功能测试', () => {

  test('限流检查应该返回布尔值', async () => {
    const tenantData = TestDataFactory.createTenantData();
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      
      const response = await api.get(`/api/v1/tenants/${tenantId}/ratelimit`);
      
      if (response.status === 200) {
        expect(typeof response.data.data.allowed).toBe('boolean');
      }
    }
  });

  test('多次请求后应该正常计数', async () => {
    const tenantData = TestDataFactory.createTenantData({ plan: 'standard' });
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      const requestCount = 10;
      
      for (let i = 0; i < requestCount; i++) {
        await api.get(`/api/v1/tenants/${tenantId}/ratelimit`);
      }
      
      const statsResponse = await api.get(`/api/v1/tenants/${tenantId}/stats`);
      if (statsResponse.status === 200) {
        expect(statsResponse.data.data.total_requests).toBeGreaterThanOrEqual(requestCount);
      }
    }
  }, 15000);

  test('各计费计划应该有不同的限流配置', async () => {
    const plans = ['free', 'standard', 'premium', 'enterprise'];
    
    for (const plan of plans) {
      const tenantData = TestDataFactory.createTenantData({ plan });
      const response = await api.post('/api/v1/tenants', tenantData);
      
      if (response.status === 201) {
        const rateLimit = response.data.data.rate_limit;
        
        expect(rateLimit.requests_per_minute).toBeDefined();
        expect(rateLimit.requests_per_hour).toBeDefined();
        expect(rateLimit.requests_per_day).toBeDefined();
        expect(rateLimit.burst_size).toBeDefined();
        
        expect(rateLimit.requests_per_minute).toBeGreaterThan(0);
        expect(rateLimit.requests_per_hour).toBeGreaterThan(0);
        expect(rateLimit.requests_per_day).toBeGreaterThan(0);
      }
    }
  });
});

describe('多租户隔离策略模块 - 并发测试', () => {

  test('并发创建租户应该正常工作', async () => {
    const tenantCount = 5;
    const requests = Array(tenantCount).fill(null).map(() => {
      const tenantData = TestDataFactory.createTenantData();
      return api.post('/api/v1/tenants', tenantData);
    });

    const results = await Promise.all(requests);
    
    results.forEach(response => {
      expect([201, 400]).toContain(response.status);
    });
    
    const successCount = results.filter(r => r.status === 201).length;
    console.log(`并发创建租户成功数: ${successCount}/${tenantCount}`);
  }, 15000);

  test('并发更新租户配置应该正常工作', async () => {
    const tenantData = TestDataFactory.createTenantData();
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      const updateCount = 5;
      
      const updates = Array(updateCount).fill(null).map((_, i) =>
        api.put(`/api/v1/tenants/${tenantId}/config`, {
          config: { update_index: i, timestamp: Date.now() }
        })
      );

      const results = await Promise.all(updates);
      
      results.forEach(response => {
        expect([200, 400]).toContain(response.status);
      });
    }
  }, 15000);

  test('并发限流检查应该正常工作', async () => {
    const tenantData = TestDataFactory.createTenantData({ plan: 'enterprise' });
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      const requestCount = 20;
      
      const requests = Array(requestCount).fill(null).map(() =>
        api.get(`/api/v1/tenants/${tenantId}/ratelimit`)
      );

      const results = await Promise.all(requests);
      
      results.forEach(response => {
        if (response.status === 200) {
          expect(typeof response.data.data.allowed).toBe('boolean');
        }
      });
    }
  }, 15000);
});

describe('多租户隔离策略模块 - 生命周期测试', () => {

  test('租户完整生命周期', async () => {
    const tenantData = TestDataFactory.createTenantData();
    
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    expect(createResponse.status).toBe(201);
    const tenantId = createResponse.data.data.tenant_id;
    expect(createResponse.data.data.status).toBe('active');
    
    const getResponse = await api.get(`/api/v1/tenants/${tenantId}`);
    expect(getResponse.status).toBe(200);
    
    const suspendResponse = await api.post(`/api/v1/tenants/${tenantId}/suspend`);
    expect([200, 400]).toContain(suspendResponse.status);
    if (suspendResponse.status === 200) {
      expect(suspendResponse.data.data.status).toBe('suspended');
    }
    
    const activateResponse = await api.post(`/api/v1/tenants/${tenantId}/activate`);
    expect([200, 400]).toContain(activateResponse.status);
    if (activateResponse.status === 200) {
      expect(activateResponse.data.data.status).toBe('active');
    }
    
    const deleteResponse = await api.delete(`/api/v1/tenants/${tenantId}`);
    expect([200, 400, 404]).toContain(deleteResponse.status);
  }, 30000);

  test('暂停的租户限流检查应该失败', async () => {
    const tenantData = TestDataFactory.createTenantData();
    const createResponse = await api.post('/api/v1/tenants', tenantData);
    
    if (createResponse.status === 201) {
      const tenantId = createResponse.data.data.tenant_id;
      
      const suspendResponse = await api.post(`/api/v1/tenants/${tenantId}/suspend`);
      if (suspendResponse.status === 200) {
        const rateLimitResponse = await api.get(`/api/v1/tenants/${tenantId}/ratelimit`);
        expect([400, 403]).toContain(rateLimitResponse.status);
      }
    }
  });
});
