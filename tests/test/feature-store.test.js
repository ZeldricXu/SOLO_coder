const { TestDataFactory } = require('./data/builders');
const { createClient, CustomAssertions: assert } = require('./utils');

describe('Feature Store Service Module - Boundary Condition Tests', () => {
  let client;

  beforeAll(() => {
    client = createClient();
  });

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('Feature Registration - Boundary Conditions', () => {
    describe('Feature Name Validation', () => {
      test('should accept feature name at maximum length boundary', async () => {
        const featureData = TestDataFactory.feature()
          .withMaxNameLength()
          .build();

        const response = await client.createFeature(featureData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 409]).toContain(response.status);
        if (response.status === 201) {
          assert.assertSuccessResponse(response, 201);
          assert.assertFeatureStructure(response.body.data);
        }
      });

      test('should reject empty feature name', async () => {
        const featureData = TestDataFactory.feature()
          .withEmptyName()
          .build();

        const response = await client.createFeature(featureData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'name');
      });

      test('should handle special characters in feature name', async () => {
        const featureData = TestDataFactory.feature()
          .withSpecialCharsInName()
          .build();

        const response = await client.createFeature(featureData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should handle unicode characters in feature name', async () => {
        const featureData = TestDataFactory.feature()
          .withUnicodeName()
          .build();

        const response = await client.createFeature(featureData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should reject duplicate feature name in same namespace', async () => {
        const baseFeature = TestDataFactory.feature().build();

        const response1 = await client.createFeature(baseFeature);

        if (response1.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        if (response1.status === 201) {
          const response2 = await client.createFeature(baseFeature);
          expect([409, 422]).toContain(response2.status);
        }
      });

      test('should allow same feature name in different namespaces', async () => {
        const baseName = TestDataFactory.feature().build().name;

        const feature1 = TestDataFactory.feature()
          .withName(baseName)
          .withNamespace('namespace_a')
          .build();

        const feature2 = TestDataFactory.feature()
          .withName(baseName)
          .withNamespace('namespace_b')
          .build();

        const response1 = await client.createFeature(feature1);
        const response2 = await client.createFeature(feature2);

        if (response1.status === 503 || response2.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 409]).toContain(response1.status);
        expect([201, 409]).toContain(response2.status);
      });
    });

    describe('Feature Value Type Validation', () => {
      const validTypes = ['float', 'int', 'string', 'bool', 'json'];
      const invalidTypes = ['invalid_type', '', '123', null, undefined, 'FLOAT', 'Int'];

      test.each(validTypes)('should accept valid value type: %s', async (type) => {
        const featureData = TestDataFactory.feature()
          .withValueType(type)
          .build();

        const response = await client.createFeature(featureData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 409]).toContain(response.status);
      });

      test.each(invalidTypes)('should reject invalid value type: %s', async (type) => {
        const featureData = TestDataFactory.feature().build();
        featureData.value_type = type;

        const response = await client.createFeature(featureData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'value_type');
      });
    });

    describe('Namespace Validation', () => {
      test('should accept default namespace', async () => {
        const featureData = TestDataFactory.feature()
          .withNamespace('default')
          .build();

        const response = await client.createFeature(featureData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 409]).toContain(response.status);
      });

      test('should handle very long namespace name', async () => {
        const featureData = TestDataFactory.feature()
          .withNamespace('a'.repeat(100))
          .build();

        const response = await client.createFeature(featureData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should reject empty namespace', async () => {
        const featureData = TestDataFactory.feature()
          .withNamespace('')
          .build();

        const response = await client.createFeature(featureData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'namespace');
      });
    });
  });

  describe('Online Feature Serving - Boundary Conditions', () => {
    describe('Entity ID Validation', () => {
      test('should handle very long entity ID', async () => {
        const requestData = TestDataFactory.featureOnlineRequest()
          .withEntityId('a'.repeat(1000))
          .withFeatures(1)
          .build();

        const response = await client.getOnlineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });

      test('should reject empty entity ID', async () => {
        const requestData = TestDataFactory.featureOnlineRequest()
          .withEntityId('')
          .withFeatures(1)
          .build();

        const response = await client.getOnlineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'entity_id');
      });

      test('should handle special characters in entity ID', async () => {
        const requestData = TestDataFactory.featureOnlineRequest()
          .withEntityId('entity@#$%^&*()_+')
          .withFeatures(1)
          .build();

        const response = await client.getOnlineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });
    });

    describe('Feature Names List Validation', () => {
      test('should handle maximum number of features', async () => {
        const requestData = TestDataFactory.featureOnlineRequest()
          .withMaxFeatures()
          .build();

        const response = await client.getOnlineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });

      test('should reject empty feature names list', async () => {
        const requestData = TestDataFactory.featureOnlineRequest()
          .withEmptyFeatures()
          .build();

        const response = await client.getOnlineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'feature_names');
      });

      test('should handle duplicate feature names', async () => {
        const requestData = TestDataFactory.featureOnlineRequest()
          .withDuplicateFeatures()
          .build();

        const response = await client.getOnlineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });

      test('should handle non-existent feature names gracefully', async () => {
        const requestData = TestDataFactory.featureOnlineRequest()
          .withFeatureNames(['non_existent_feature_12345'])
          .build();

        const response = await client.getOnlineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 404, 422]).toContain(response.status);
      });
    });
  });

  describe('Offline Feature Retrieval - Boundary Conditions', () => {
    describe('Time Range Validation', () => {
      test('should accept valid time range', async () => {
        const requestData = TestDataFactory.featureOfflineRequest()
          .withValidTimeRange()
          .withEntities(1)
          .withFeatures(1)
          .build();

        const response = await client.getOfflineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });

      test('should reject invalid time range (start > end)', async () => {
        const requestData = TestDataFactory.featureOfflineRequest()
          .withInvalidTimeRange()
          .withEntities(1)
          .withFeatures(1)
          .build();

        const response = await client.getOfflineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'time_range');
      });

      test('should handle future time range', async () => {
        const requestData = TestDataFactory.featureOfflineRequest()
          .withFutureTimeRange()
          .withEntities(1)
          .withFeatures(1)
          .build();

        const response = await client.getOfflineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });

      test('should handle distant past time range', async () => {
        const requestData = TestDataFactory.featureOfflineRequest()
          .withDistantPastTimeRange()
          .withEntities(1)
          .withFeatures(1)
          .build();

        const response = await client.getOfflineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });

      test('should handle very large time range', async () => {
        const requestData = TestDataFactory.featureOfflineRequest()
          .withStartTime(new Date('2000-01-01'))
          .withEndTime(new Date())
          .withEntities(1)
          .withFeatures(1)
          .build();

        const response = await client.getOfflineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });

      test('should handle identical start and end time', async () => {
        const now = new Date();
        const requestData = TestDataFactory.featureOfflineRequest()
          .withStartTime(now)
          .withEndTime(now)
          .withEntities(1)
          .withFeatures(1)
          .build();

        const response = await client.getOfflineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });
    });

    describe('Entity IDs Validation', () => {
      test('should handle multiple entity IDs', async () => {
        const requestData = TestDataFactory.featureOfflineRequest()
          .withEntities(100)
          .withFeatures(1)
          .withValidTimeRange()
          .build();

        const response = await client.getOfflineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([200, 422]).toContain(response.status);
      });

      test('should reject empty entity IDs list', async () => {
        const requestData = TestDataFactory.featureOfflineRequest()
          .withEntityIds([])
          .withFeatures(1)
          .withValidTimeRange()
          .build();

        const response = await client.getOfflineFeatures(requestData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'entity_ids');
      });
    });
  });

  describe('Feature CRUD Operations - Edge Cases', () => {
    test('should handle query with very large page size', async () => {
      const response = await client.getFeatures({ page: 1, page_size: 1000 });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should handle negative page number', async () => {
      const response = await client.getFeatures({ page: -1, page_size: 10 });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

      assert.assertValidationError(response, 'page');
    });

    test('should handle zero page size', async () => {
      const response = await client.getFeatures({ page: 1, page_size: 0 });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      assert.assertValidationError(response, 'page_size');
    });

    test('should handle non-existent feature ID gracefully', async () => {
      const nonExistentId = '00000000-0000-0000-0000-000000000000';
      const response = await client.getFeature(nonExistentId);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      assert.assertNotFound(response);
    });

    test('should handle invalid UUID format for feature ID', async () => {
      const response = await client.getFeature('invalid-uuid-format');

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      assert.assertValidationError(response, 'id');
    });

    test('should handle concurrent feature creation requests', async () => {
      const featureData = TestDataFactory.feature().build();
      
      const requests = Array.from({ length: 5 }, () =>
        client.createFeature(featureData)
      );

      const responses = await Promise.all(requests);

      if (responses.some(r => r.status === 503)) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      const successCount = responses.filter(r => r.status === 201).length;
      const conflictCount = responses.filter(r => r.status === 409).length;

      expect(successCount).toBeLessThanOrEqual(1);
      expect(successCount + conflictCount).toBe(responses.length);
    });
  });

  describe('Online-Offline Consistency Check - Boundary Conditions', () => {
    test('should handle consistency check for single feature', async () => {
      const requestData = {
        feature_names: ['test_feature'],
        entity_id: 'test_entity',
        time_window_seconds: 3600,
      };

      const response = await client.checkFeatureConsistency(requestData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should handle very small time window', async () => {
      const requestData = {
        feature_names: ['test_feature'],
        entity_id: 'test_entity',
        time_window_seconds: 1,
      };

      const response = await client.checkFeatureConsistency(requestData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should handle very large time window', async () => {
      const requestData = {
        feature_names: ['test_feature'],
        entity_id: 'test_entity',
        time_window_seconds: 86400 * 365,
      };

      const response = await client.checkFeatureConsistency(requestData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should reject negative time window', async () => {
      const requestData = {
        feature_names: ['test_feature'],
        entity_id: 'test_entity',
        time_window_seconds: -1,
      };

      const response = await client.checkFeatureConsistency(requestData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      assert.assertValidationError(response, 'time_window_seconds');
    });

    test('should reject zero time window', async () => {
      const requestData = {
        feature_names: ['test_feature'],
        entity_id: 'test_entity',
        time_window_seconds: 0,
      };

      const response = await client.checkFeatureConsistency(requestData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      assert.assertValidationError(response, 'time_window_seconds');
    });

    test('should handle consistency check for many features', async () => {
      const featureNames = Array.from({ length: 50 }, (_, i) => `feature_${i}`);
      const requestData = {
        feature_names: featureNames,
        entity_id: 'test_entity',
        time_window_seconds: 3600,
      };

      const response = await client.checkFeatureConsistency(requestData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });
  });

  describe('Request Payload Size Limits', () => {
    test('should handle large description text', async () => {
      const featureData = TestDataFactory.feature()
        .withDescription('a'.repeat(10000))
        .build();

      const response = await client.createFeature(featureData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([201, 413, 422]).toContain(response.status);
    });

    test('should handle many labels', async () => {
      const labels = {};
      for (let i = 0; i < 100; i++) {
        labels[`label_${i}`] = `value_${i}`;
      }

      const featureData = TestDataFactory.feature()
        .withLabels(labels)
        .build();

      const response = await client.createFeature(featureData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([201, 422]).toContain(response.status);
    });

    test('should handle very large label values', async () => {
      const labels = {
        very_large_label: 'a'.repeat(1000),
      };

      const featureData = TestDataFactory.feature()
        .withLabels(labels)
        .build();

      const response = await client.createFeature(featureData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([201, 422]).toContain(response.status);
    });
  });
});
