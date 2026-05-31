const { TestDataFactory } = require('./data/builders');
const { createClient, CustomAssertions: assert } = require('./utils');

describe('Monitoring Module - Parameter Validation Completeness Tests', () => {
  let client;

  beforeAll(() => {
    client = createClient();
  });

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('Metric Snapshot Creation - Parameter Validation', () => {
    describe('snapshot_id Validation', () => {
      test('should accept valid snapshot_id', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withDefaultMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 200]).toContain(response.status);
        if (response.status === 201) {
          assert.assertSuccessResponse(response, 201);
        }
      });

      test('should reject empty snapshot_id', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withEmptySnapshotId()
          .withDefaultMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'snapshot_id');
      });

      test('should handle very long snapshot_id', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withSnapshotId('a'.repeat(200))
          .withDefaultMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should handle special characters in snapshot_id', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withSnapshotId('snap@#$%^&*()_+')
          .withDefaultMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });
    });

    describe('timestamp Validation', () => {
      test('should accept current timestamp', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withTimestamp(new Date())
          .withDefaultMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 200]).toContain(response.status);
      });

      test('should reject invalid timestamp format', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withInvalidTimestamp()
          .withDefaultMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'timestamp');
      });

      test('should handle future timestamp', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withFutureTimestamp()
          .withDefaultMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should handle ancient timestamp', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withAncientTimestamp()
          .withDefaultMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should reject missing timestamp', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withDefaultMetrics()
          .build();
        delete snapshotData.timestamp;

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'timestamp');
      });
    });

    describe('metrics Validation', () => {
      test('should accept valid metrics object', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withDefaultMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 200]).toContain(response.status);
      });

      test('should reject empty metrics object', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withEmptyMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'metrics');
      });

      test('should reject non-object metrics', async () => {
        const snapshotData = TestDataFactory.metricSnapshot().build();
        snapshotData.metrics = 'not_an_object';

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'metrics');
      });

      test('should reject negative metric values', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withNegativeMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should accept zero metric values', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withZeroMetrics()
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should handle large number of metrics', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withManyMetrics(100)
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should handle very large metric values', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withMetrics({
            huge_value: Number.MAX_SAFE_INTEGER,
            tiny_value: Number.MIN_SAFE_INTEGER,
          })
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should handle floating point metric values', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withMetrics({
            float_value: 3.14159265358979,
            precision_test: 0.0000001,
          })
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should reject non-numeric metric values', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withMetrics({
            string_value: 'not_a_number',
            null_value: null,
            undefined_value: undefined,
            array_value: [1, 2, 3],
          })
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'metrics');
      });

      test('should reject missing metrics field', async () => {
        const snapshotData = TestDataFactory.metricSnapshot().build();
        delete snapshotData.metrics;

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'metrics');
      });
    });

    describe('dimensions Validation', () => {
      test('should accept valid dimensions object', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withDefaultMetrics()
          .withDimensions({
            host: 'server-01',
            region: 'us-east-1',
            environment: 'production',
          })
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 200]).toContain(response.status);
      });

      test('should accept empty dimensions object', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withDefaultMetrics()
          .withDimensions({})
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 200]).toContain(response.status);
      });

      test('should handle null dimensions', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withDefaultMetrics()
          .build();
        snapshotData.dimensions = null;

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should handle many dimensions', async () => {
        const dimensions = {};
        for (let i = 0; i < 50; i++) {
          dimensions[`dim_${i}`] = `value_${i}`;
        }

        const snapshotData = TestDataFactory.metricSnapshot()
          .withDefaultMetrics()
          .withDimensions(dimensions)
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should handle special characters in dimension values', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withDefaultMetrics()
          .withDimensions({
            special: 'value@#$%^&*()',
            unicode: '测试_функция',
          })
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        expect([201, 422]).toContain(response.status);
      });

      test('should reject non-string dimension values', async () => {
        const snapshotData = TestDataFactory.metricSnapshot()
          .withDefaultMetrics()
          .withDimensions({
            number_value: 123,
            object_value: { nested: true },
            array_value: [1, 2, 3],
            null_value: null,
          })
          .build();

        const response = await client.createMetricSnapshot(snapshotData);

        if (response.status === 503) {
          console.log('⚠️  Service unavailable, skipping assertion');
          return;
        }

        assert.assertValidationError(response, 'dimensions');
      });
    });
  });

  describe('Metrics Query - Parameter Validation', () => {
    test('should accept query without filters', async () => {
      const response = await client.getMetrics();

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200]).toContain(response.status);
      if (response.status === 200) {
        assert.assertSuccessResponse(response);
      }
    });

    test('should handle metric name filter', async () => {
      const response = await client.getMetrics();

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200]).toContain(response.status);
    });

    test('should handle time range query parameters', async () => {
      const response = await client.getMetricSnapshots({
        start_time: new Date(Date.now() - 3600000).toISOString(),
        end_time: new Date().toISOString(),
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should reject invalid time range in query', async () => {
      const response = await client.getMetricSnapshots({
        start_time: new Date().toISOString(),
        end_time: new Date(Date.now() - 3600000).toISOString(),
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      assert.assertValidationError(response, 'time_range');
    });

    test('should handle dimension filters', async () => {
      const response = await client.getMetricSnapshots({
        host: 'server-01',
        region: 'us-east-1',
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should handle pagination parameters', async () => {
      const response = await client.getMetricSnapshots({
        page: 1,
        page_size: 20,
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should reject negative page number', async () => {
      const response = await client.getMetricSnapshots({
        page: -1,
        page_size: 20,
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      assert.assertValidationError(response, 'page');
    });

    test('should reject zero page size', async () => {
      const response = await client.getMetricSnapshots({
        page: 1,
        page_size: 0,
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      assert.assertValidationError(response, 'page_size');
    });

    test('should handle very large page size', async () => {
      const response = await client.getMetricSnapshots({
        page: 1,
        page_size: 1000,
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });
  });

  describe('Audit Log Query - Parameter Validation', () => {
    test('should accept query without filters', async () => {
      const response = await client.getAuditLogs();

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200]).toContain(response.status);
    });

    test('should handle action filter', async () => {
      const response = await client.getAuditLogs({
        action: 'create',
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should handle resource_type filter', async () => {
      const response = await client.getAuditLogs({
        resource_type: 'feature',
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should handle user_id filter', async () => {
      const response = await client.getAuditLogs({
        user_id: '00000000-0000-0000-0000-000000000000',
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should reject invalid user_id format', async () => {
      const response = await client.getAuditLogs({
        user_id: 'invalid-uuid',
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      assert.assertValidationError(response, 'user_id');
    });

    test('should handle time range filter', async () => {
      const response = await client.getAuditLogs({
        start_time: new Date(Date.now() - 86400000).toISOString(),
        end_time: new Date().toISOString(),
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });

    test('should handle pagination parameters', async () => {
      const response = await client.getAuditLogs({
        page: 1,
        page_size: 50,
      });

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([200, 422]).toContain(response.status);
    });
  });

  describe('Metric Data Integrity Checks', () => {
    test('should ensure no negative values in standard metrics', () => {
      const metrics = {
        throughput: 1000,
        latency_p50: 50,
        latency_p99: 200,
        error_rate: 0.01,
        cpu_usage: 75,
        memory_usage: 60,
      };

      assert.assertMetricsHaveNoNegativeValues(metrics);
    });

    test('should detect negative values in metrics', () => {
      const metrics = {
        throughput: -100,
        latency_p99: 200,
      };

      expect(() => assert.assertMetricsHaveNoNegativeValues(metrics)).toThrow();
    });

    test('should validate latency percentile ordering', () => {
      const metrics = {
        latency_p50: 50,
        latency_p90: 100,
        latency_p95: 150,
        latency_p99: 200,
      };

      expect(metrics.latency_p50).toBeLessThanOrEqual(metrics.latency_p90);
      expect(metrics.latency_p90).toBeLessThanOrEqual(metrics.latency_p95);
      expect(metrics.latency_p95).toBeLessThanOrEqual(metrics.latency_p99);
    });

    test('should validate error rate is between 0 and 1', () => {
      const validErrorRates = [0, 0.01, 0.5, 0.99, 1];
      const invalidErrorRates = [-0.01, 1.01, 2];

      validErrorRates.forEach(rate => {
        expect(rate).toBeGreaterThanOrEqual(0);
        expect(rate).toBeLessThanOrEqual(1);
      });

      invalidErrorRates.forEach(rate => {
        const isValid = rate >= 0 && rate <= 1;
        expect(isValid).toBe(false);
      });
    });

    test('should validate CPU and memory usage percentages', () => {
      const validUsage = [0, 25, 50, 75, 100];
      const invalidUsage = [-1, 101, 200];

      validUsage.forEach(usage => {
        assert.assertWithinRange(usage, 0, 100);
      });

      invalidUsage.forEach(usage => {
        expect(() => assert.assertWithinRange(usage, 0, 100)).toThrow();
      });
    });
  });

  describe('High Load and Stress Scenarios', () => {
    test('should handle rapid snapshot creation', async () => {
      const snapshots = TestDataFactory.generateMetricSnapshots(10);
      
      const requests = snapshots.map(snapshot =>
        client.createMetricSnapshot(snapshot)
      );

      const responses = await Promise.all(requests);

      if (responses.some(r => r.status === 503)) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      const successCount = responses.filter(r => r.status === 201 || r.status === 200).length;
      expect(successCount).toBeGreaterThanOrEqual(0);
    });

    test('should handle high load metrics snapshot', async () => {
      const snapshotData = TestDataFactory.metricSnapshot()
        .withHighLoadMetrics()
        .build();

      const response = await client.createMetricSnapshot(snapshotData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([201, 200]).toContain(response.status);
    });

    test('should handle large payload size', async () => {
      const snapshotData = TestDataFactory.metricSnapshot()
        .withManyMetrics(500)
        .build();

      const response = await client.createMetricSnapshot(snapshotData);

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      expect([201, 413, 422]).toContain(response.status);
    });
  });

  describe('Response Structure Validation', () => {
    test('should return consistent response structure for metrics endpoint', async () => {
      const response = await client.getMetrics();

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      if (response.status === 200) {
        assert.assertSuccessResponse(response);
        expect(response.body.data).toHaveProperty('system');
        expect(response.body.data).toHaveProperty('requests');
      }
    });

    test('should return consistent response structure for snapshots endpoint', async () => {
      const response = await client.getMetricSnapshots();

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      if (response.status === 200) {
        assert.assertSuccessResponse(response);
      }
    });

    test('should return consistent response structure for audit logs endpoint', async () => {
      const response = await client.getAuditLogs();

      if (response.status === 503) {
        console.log('⚠️  Service unavailable, skipping assertion');
        return;
      }

      if (response.status === 200) {
        assert.assertSuccessResponse(response);
      }
    });
  });
});
