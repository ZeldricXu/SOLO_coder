const { TestDataBuilder, baseBuilder } = require('./TestDataBuilder');

class FeatureStoreDataBuilder extends TestDataBuilder {
  constructor() {
    super();
    this.featureTypes = [
      'int',
      'float',
      'string',
      'bool',
      'array<int>',
      'array<float>',
      'embedding',
      'json',
    ];
    this.storageTiers = ['online', 'offline', 'both'];
    this.dataSources = [
      'user_behavior',
      'transaction',
      'user_profile',
      'content_meta',
      'realtime_event',
    ];
  }

  buildFeatureRegistration(overrides = {}) {
    const featureName = this.randomString(8);
    return {
      name: `feature_${featureName}`,
      display_name: `特征_${featureName}`,
      description: `这是${featureName}特征的描述`,
      feature_type: this.randomFromArray(this.featureTypes),
      storage_tier: this.randomFromArray(this.storageTiers),
      data_source: this.randomFromArray(this.dataSources),
      version: `${this.randomInt(0, 9)}.${this.randomInt(0, 99)}.${this.randomInt(0, 999)}`,
      entity_type: this.randomFromArray(['user', 'item', 'session', 'order']),
      ttl_seconds: this.randomInt(3600, 86400 * 30),
      is_active: true,
      tags: [
        { key: 'domain', value: this.randomString(6) },
        { key: 'team', value: this.randomString(5) },
      ],
      schema: {
        type: 'object',
        properties: {
          value: { type: this.randomFromArray(['string', 'number']) },
        },
      },
      ...overrides,
    };
  }

  buildValidFeatureRegistration(overrides = {}) {
    return this.buildFeatureRegistration({
      name: 'user_total_purchases',
      display_name: '用户总购买次数',
      description: '用户历史累计购买商品次数',
      feature_type: 'int',
      storage_tier: 'both',
      data_source: 'transaction',
      entity_type: 'user',
      ...overrides,
    });
  }

  buildEntityData(entityType = 'user', overrides = {}) {
    const entityId = this.generateId(entityType);
    const features = {};
    for (let i = 0; i < this.randomInt(3, 8); i += 1) {
      const featName = this.randomString(6);
      features[featName] = this.randomInt(1, 1000);
    }
    return {
      entity_id: entityId,
      entity_type: entityType,
      features,
      timestamp: Date.now(),
      source: this.randomFromArray(this.dataSources),
      ...overrides,
    };
  }

  buildFeatureValue(featureName = null, overrides = {}) {
    return {
      feature_name: featureName || `feature_${this.randomString(8)}`,
      value: this.randomFloat(0, 100),
      entity_id: this.generateId('user'),
      timestamp: Date.now(),
      ...overrides,
    };
  }

  buildBatchFeatureValues(count = 5, overrides = {}) {
    const values = [];
    const entityId = this.generateId('user');
    for (let i = 0; i < count; i += 1) {
      values.push(this.buildFeatureValue(null, {
        entity_id: entityId,
        ...overrides,
      }));
    }
    return values;
  }

  buildFeatureLookupRequest(featureNames = null, overrides = {}) {
    return {
      entity_id: this.generateId('user'),
      feature_names: featureNames || [
        `feature_${this.randomString(6)}`,
        `feature_${this.randomString(6)}`,
      ],
      allow_partial: true,
      ...overrides,
    };
  }

  buildOfflineQueryRequest(overrides = {}) {
    const now = Date.now();
    return {
      entity_ids: [
        this.generateId('user'),
        this.generateId('user'),
        this.generateId('user'),
      ],
      feature_names: [
        `feature_${this.randomString(6)}`,
        `feature_${this.randomString(6)}`,
      ],
      start_time: now - 86400000 * 7,
      end_time: now,
      resolution: 'hourly',
      ...overrides,
    };
  }

  buildConsistencyCheckRequest(overrides = {}) {
    return {
      entity_id: this.generateId('user'),
      feature_name: `feature_${this.randomString(8)}`,
      tolerance: 0.01,
      ...overrides,
    };
  }

  buildBoundaryFeatureData() {
    return {
      emptyName: this.buildFeatureRegistration({ name: '' }),
      veryLongName: this.buildFeatureRegistration({ name: this.randomString(500) }),
      invalidNameSpecial: this.buildFeatureRegistration({ name: 'feature!@#$%' }),
      invalidType: this.buildFeatureRegistration({ feature_type: 'invalid_type' }),
      negativeTtl: this.buildFeatureRegistration({ ttl_seconds: -1 }),
      zeroTtl: this.buildFeatureRegistration({ ttl_seconds: 0 }),
      emptyFeatures: this.buildEntityData('user', { features: {} }),
      tooManyFeatures: this.buildEntityData('user', {
        features: Array.from({ length: 1000 }, (_, i) => ({
          [`feat_${i}`]: i,
        })).reduce((acc, curr) => ({ ...acc, ...curr }), {}),
      }),
      invalidTimestampPast: this.buildEntityData('user', { timestamp: -1 }),
      invalidTimestampFuture: this.buildEntityData('user', { timestamp: Date.now() + 86400000 * 365 }),
    };
  }

  buildExpectedFeatureResponse() {
    return {
      code: 200,
      message: expect.any(String),
      data: expect.objectContaining({
        feature_id: expect.any(String),
        name: expect.any(String),
        version: expect.any(String),
      }),
    };
  }

  buildExpectedEntityDataResponse() {
    return {
      code: 200,
      message: expect.any(String),
      data: expect.objectContaining({
        entity_id: expect.any(String),
        features: expect.any(Object),
      }),
    };
  }

  buildTransactionTestData() {
    const entityId = this.generateId('user');
    return {
      entityId,
      initialFeatures: {
        total_spent: 100,
        order_count: 5,
      },
      updateFeatures: {
        total_spent: 500,
        order_count: 6,
        new_feature: 42,
      },
      conflictingFeatures: {
        total_spent: 99999,
      },
    };
  }
}

const featureStoreBuilder = new FeatureStoreDataBuilder();
module.exports = { FeatureStoreDataBuilder, featureStoreBuilder };
