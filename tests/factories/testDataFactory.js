const { randomUUID } = require('crypto');

class TestDataFactory {
  constructor() {
    this.counters = {
      config: 0,
      tenant: 0,
      log: 0
    };
  }

  resetCounters() {
    this.counters = {
      config: 0,
      tenant: 0,
      log: 0
    };
  }

  generateId(prefix) {
    const count = ++this.counters[prefix] || 1;
    this.counters[prefix] = count;
    return `${prefix}_test_${count.toString().padStart(4, '0')}`;
  }

  createConfigData(overrides = {}) {
    const ns = overrides.namespace || this.generateId('config');
    return {
      namespace: ns,
      parameters: overrides.parameters || {
        timeout: 30,
        retries: 3,
        maxConnections: 100,
        enabled: true
      },
      ...overrides
    };
  }

  createTenantData(overrides = {}) {
    return {
      name: overrides.name || `Test Tenant ${this.counters.tenant + 1}`,
      adminEmail: overrides.adminEmail || `admin${this.counters.tenant + 1}@test.com`,
      plan: overrides.plan || 'standard',
      ...overrides
    };
  }

  createLogEntry(overrides = {}) {
    return {
      level: overrides.level || 'INFO',
      service: overrides.service || 'test-service',
      traceID: overrides.traceID || randomUUID(),
      message: overrides.message || 'Test log message',
      fields: overrides.fields || {
        userID: 'user_123',
        requestID: randomUUID()
      },
      ...overrides
    };
  }

  createRollbackData(overrides = {}) {
    return {
      namespace: overrides.namespace || this.generateId('config'),
      targetVersion: overrides.targetVersion || 1,
      ...overrides
    };
  }

  createConfigWithMultipleVersions(versionCount = 3) {
    const namespace = this.generateId('config');
    const versions = [];
    
    for (let i = 0; i < versionCount; i++) {
      versions.push({
        namespace,
        parameters: {
          version: i + 1,
          timeout: 30 + i * 10,
          retries: 3 + i
        }
      });
    }
    
    return { namespace, versions };
  }

  createTenantWithUsage(overrides = {}) {
    const tenantData = this.createTenantData(overrides);
    return {
      ...tenantData,
      usage: overrides.usage || {
        storageUsedGB: 5,
        cpuUsedCores: 0.5,
        memoryUsedGB: 1.0,
        apiRequestsCount: 1000,
        activeUsers: 3,
        activeConnections: 5,
        bandwidthUsedGB: 10
      }
    };
  }

  createLargeParameters(sizeKB = 10) {
    const params = {};
    const targetSize = sizeKB * 1024;
    let currentSize = 0;
    let index = 0;
    
    while (currentSize < targetSize) {
      const key = `param_${index}`;
      const value = 'x'.repeat(Math.min(256, targetSize - currentSize));
      params[key] = value;
      currentSize += key.length + value.length + 5;
      index++;
    }
    
    return params;
  }

  createInvalidConfigData() {
    return [
      { namespace: '', parameters: { key: 'value' } },
      { parameters: { key: 'value' } },
      { namespace: 'test', parameters: null },
      { namespace: 'test', parameters: 'not an object' }
    ];
  }

  createInvalidTenantData() {
    return [
      { name: '', adminEmail: 'test@test.com', plan: 'free' },
      { name: 'Test', adminEmail: '', plan: 'free' },
      { name: 'Test', adminEmail: 'test@test.com', plan: 'invalid_plan' },
      { adminEmail: 'test@test.com', plan: 'free' }
    ];
  }

  createBulkConfigs(count = 100) {
    const configs = [];
    for (let i = 0; i < count; i++) {
      configs.push(this.createConfigData({
        namespace: `bulk_test_${i.toString().padStart(6, '0')}`
      }));
    }
    return configs;
  }

  createBulkTenants(count = 100) {
    const tenants = [];
    const plans = ['free', 'standard', 'premium', 'enterprise'];
    for (let i = 0; i < count; i++) {
      tenants.push(this.createTenantData({
        name: `Bulk Tenant ${i}`,
        adminEmail: `admin${i}@bulk.com`,
        plan: plans[i % plans.length]
      }));
    }
    return tenants;
  }

  createRateLimitTestData(plan = 'free') {
    const limits = {
      free: { perMinute: 60, perHour: 1000, perDay: 10000 },
      standard: { perMinute: 600, perHour: 10000, perDay: 100000 },
      premium: { perMinute: 6000, perHour: 100000, perDay: 1000000 },
      enterprise: { perMinute: 60000, perHour: 1000000, perDay: 10000000 }
    };
    return {
      tenant: this.createTenantData({ plan }),
      expectedLimits: limits[plan] || limits.free
    };
  }

  getBillingPlanExpectations() {
    return {
      free: {
        maxStorageGB: 10,
        maxCPUCores: 1,
        maxMemoryGB: 2,
        maxAPIRequests: 10000,
        maxUsers: 5,
        maxConnections: 10,
        maxBandwidthGB: 100
      },
      standard: {
        maxStorageGB: 100,
        maxCPUCores: 4,
        maxMemoryGB: 8,
        maxAPIRequests: 100000,
        maxUsers: 50,
        maxConnections: 100,
        maxBandwidthGB: 1000
      },
      premium: {
        maxStorageGB: 1000,
        maxCPUCores: 16,
        maxMemoryGB: 32,
        maxAPIRequests: 1000000,
        maxUsers: 500,
        maxConnections: 500,
        maxBandwidthGB: 10000
      },
      enterprise: {
        maxStorageGB: 10000,
        maxCPUCores: 64,
        maxMemoryGB: 128,
        maxAPIRequests: 10000000,
        maxUsers: 10000,
        maxConnections: 5000,
        maxBandwidthGB: 100000
      }
    };
  }
}

const factoryInstance = new TestDataFactory();

module.exports = {
  TestDataFactory: factoryInstance,
  testDataFactory: factoryInstance
};
