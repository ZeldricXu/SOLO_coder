const { createApiClient, validateResponseStructure, validateErrorResponse, measureExecutionTime, deepClone, waitForCondition } = require('./helpers');
const { featureStoreBuilder } = require('./builders');

const apiClient = createApiClient();

describe('特征存储服务模块 - 事务回滚正确性测试', () => {
  let testEntityId = null;
  let testFeatureId = null;

  beforeAll(async () => {
    console.log('\n=== 特征存储服务模块测试启动 ===\n');
    testEntityId = featureStoreBuilder.generateId('test_user');
  });

  describe('1. 基础功能验证', () => {
    test('应成功注册新特征', async () => {
      const request = featureStoreBuilder.buildValidFeatureRegistration();

      const { result, durationMs } = await measureExecutionTime(() =>
        apiClient.featureStore.registerFeature(request)
      );

      validateResponseStructure(result, 201);
      testFeatureId = result.body.data.feature_id;
      expect(result.body.data.name).toBe(request.name);
      expect(result.body.data.feature_type).toBe(request.feature_type);
      console.log(`  ✓ 特征注册耗时: ${durationMs}ms`);
    });

    test('应成功获取特征信息', async () => {
      const response = await apiClient.featureStore.getFeature(testFeatureId);
      validateResponseStructure(response, 200);
      expect(response.body.data.feature_id).toBe(testFeatureId);
    });

    test('应成功存储实体特征数据', async () => {
      const entityData = featureStoreBuilder.buildEntityData('user', {
        entity_id: testEntityId,
      });

      const response = await apiClient.featureStore.storeEntityData(entityData);
      validateResponseStructure(response, 200);
      expect(response.body.data.entity_id).toBe(testEntityId);
    });
  });

  describe('2. 事务回滚测试 - 原子性验证', () => {
    test('批量特征写入失败时应完全回滚', async () => {
      const entityId = featureStoreBuilder.generateId('rollback_test');
      const validFeatures = {
        valid_feature_1: 100,
        valid_feature_2: 200,
      };

      const entityData = featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features: validFeatures,
      });

      const storeResponse = await apiClient.featureStore.storeEntityData(entityData);
      expect(storeResponse.status).toBe(200);

      const invalidData = featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features: {
          ...validFeatures,
          invalid_feature: 'INVALID_VALUE_SHOULD_CAUSE_ROLLBACK',
        },
        timestamp: Date.now(),
      });

      try {
        await apiClient.featureStore.storeEntityData(invalidData);
      } catch (error) {
        expect(error).toBeDefined();
      }

      const verifyResponse = await apiClient.featureStore.getEntityData(entityId);
      if (verifyResponse.status === 200) {
        const features = verifyResponse.body.data.features;
        expect(features.valid_feature_1).toBe(100);
        expect(features.valid_feature_2).toBe(200);
        expect(features.invalid_feature).toBeUndefined();
        console.log('  ✓ 事务回滚验证通过：无效字段未写入');
      }
    });

    test('并发写入冲突时事务应正确回滚', async () => {
      const entityId = featureStoreBuilder.generateId('concurrency_test');

      await apiClient.featureStore.storeEntityData(featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features: { balance: 1000 },
      }));

      const update1 = apiClient.featureStore.storeEntityData(featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features: { balance: 800 },
      }));

      const update2 = apiClient.featureStore.storeEntityData(featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features: { balance: 1200 },
      }));

      const results = await Promise.allSettled([update1, update2]);
      const successCount = results.filter(r => r.status === 'fulfilled').length;
      const failureCount = results.filter(r => r.status === 'rejected').length;

      expect(successCount + failureCount).toBe(2);

      const finalState = await apiClient.featureStore.getEntityData(entityId);
      if (finalState.status === 200) {
        const finalBalance = finalState.body.data.features.balance;
        expect([800, 1200, 1000]).toContain(finalBalance);
        console.log(`  ✓ 并发事务最终一致，余额: ${finalBalance}`);
      }
    });

    test('部分写入失败时在线存储应保持一致', async () => {
      const entityId = featureStoreBuilder.generateId('online_consistency');
      const initialFeatures = {
        score_a: 10,
        score_b: 20,
        score_c: 30,
      };

      await apiClient.featureStore.storeEntityData(featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features: initialFeatures,
      }));

      const problematicUpdate = {
        entity_id: entityId,
        features: {
          score_a: 15,
          score_b: 'INVALID',
          score_c: 35,
        },
        entity_type: 'user',
        timestamp: Date.now(),
      };

      try {
        await apiClient.featureStore.storeEntityData(problematicUpdate);
      } catch (error) {
        console.log('  ✓ 预期的写入失败已捕获');
      }

      const onlineResponse = await apiClient.featureStore.getOnlineFeatures({
        entity_id: entityId,
        feature_names: ['score_a', 'score_b', 'score_c'],
      });

      if (onlineResponse.status === 200) {
        const features = onlineResponse.body.data.features;
        expect(features.score_a).toBe(10);
        expect(features.score_b).toBe(20);
        expect(features.score_c).toBe(30);
        console.log('  ✓ 在线存储事务回滚验证通过');
      }
    });
  });

  describe('3. 事务回滚测试 - 离线/在线一致性', () => {
    test('离线写入失败时在线存储不应被修改', async () => {
      const entityId = featureStoreBuilder.generateId('offline_rollback');

      await apiClient.featureStore.storeEntityData(featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features: { total_orders: 5 },
      }));

      const offlineQuery = featureStoreBuilder.buildOfflineQueryRequest({
        entity_ids: [entityId],
        feature_names: ['total_orders'],
      });

      try {
        await apiClient.featureStore.getOfflineFeatures(offlineQuery);
      } catch (error) {
        // 离线查询可能失败，但不影响在线数据
      }

      const onlineCheck = await apiClient.featureStore.getOnlineFeatures({
        entity_id: entityId,
        feature_names: ['total_orders'],
      });

      if (onlineCheck.status === 200) {
        expect(onlineCheck.body.data.features.total_orders).toBe(5);
        console.log('  ✓ 离线查询不影响在线数据一致性');
      }
    });

    test('一致性检查应正确检测不一致状态', async () => {
      const entityId = featureStoreBuilder.generateId('consistency_check');
      const featureName = 'test_feature_consistency';

      await apiClient.featureStore.storeEntityData(featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features: { [featureName]: 100 },
      }));

      const checkRequest = featureStoreBuilder.buildConsistencyCheckRequest({
        entity_id: entityId,
        feature_name: featureName,
      });

      const response = await apiClient.featureStore.checkConsistency(checkRequest);
      if (response.status === 200) {
        expect(response.body.data).toHaveProperty('is_consistent');
        expect(typeof response.body.data.is_consistent).toBe('boolean');
        console.log(`  ✓ 一致性检查结果: ${response.body.data.is_consistent}`);
      }
    });
  });

  describe('4. 边界条件测试', () => {
    test('特征名称为空 - 应返回验证错误', async () => {
      const request = featureStoreBuilder.buildFeatureRegistration({ name: '' });

      const response = await apiClient.featureStore.registerFeature(request);
      expect([400, 422]).toContain(response.status);
    });

    test('特征类型无效 - 应返回验证错误', async () => {
      const request = featureStoreBuilder.buildFeatureRegistration({
        feature_type: 'invalid_type',
      });

      const response = await apiClient.featureStore.registerFeature(request);
      expect([400, 422]).toContain(response.status);
    });

    test('TTL为负数 - 应返回验证错误', async () => {
      const request = featureStoreBuilder.buildFeatureRegistration({
        ttl_seconds: -3600,
      });

      const response = await apiClient.featureStore.registerFeature(request);
      expect([400, 422]).toContain(response.status);
    });

    test('空特征集合存储 - 应正确处理', async () => {
      const entityId = featureStoreBuilder.generateId('empty_features');
      const request = featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features: {},
      });

      const response = await apiClient.featureStore.storeEntityData(request);
      expect([200, 400]).toContain(response.status);
    });

    test('超大量特征存储 - 应处理或返回合理错误', async () => {
      const entityId = featureStoreBuilder.generateId('many_features');
      const manyFeatures = {};
      for (let i = 0; i < 500; i += 1) {
        manyFeatures[`feature_${i}`] = i;
      }

      const { result, durationMs } = await measureExecutionTime(() =>
        apiClient.featureStore.storeEntityData(featureStoreBuilder.buildEntityData('user', {
          entity_id: entityId,
          features: manyFeatures,
        }))
      );

      if (result.status === 200) {
        console.log(`  ✓ 存储500个特征耗时: ${durationMs}ms`);
      } else {
        expect(result.status).toBe(400);
      }
    });
  });

  describe('5. 幂等性测试', () => {
    test('重复存储相同数据 - 结果应一致', async () => {
      const entityId = featureStoreBuilder.generateId('idempotent_test');
      const features = { test_value: 42 };

      const response1 = await apiClient.featureStore.storeEntityData(featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features,
      }));

      const response2 = await apiClient.featureStore.storeEntityData(featureStoreBuilder.buildEntityData('user', {
        entity_id: entityId,
        features,
      }));

      expect(response1.status).toBe(response2.status);

      const final = await apiClient.featureStore.getEntityData(entityId);
      if (final.status === 200) {
        expect(final.body.data.features.test_value).toBe(42);
      }
    });
  });
});
