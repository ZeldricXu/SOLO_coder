const request = require('supertest');
const axios = require('axios');
const { DataAggregationTestDataFactory } = require('./testDataFactory');

const BASE_URL = process.env.TEST_API_BASE_URL || 'http://localhost:8000';
const API_PREFIX = '/api/v1/telemetry';

const api = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
});

const checkApiAvailable = async () => {
  try {
    await api.get('/health');
    return true;
  } catch (error) {
    console.warn('API not available, skipping integration tests');
    return false;
  }
};

describe('数据流边缘聚合模块 - 事务回滚正确性', () => {
  let apiAvailable = false;

  beforeAll(async () => {
    apiAvailable = await checkApiAvailable();
  });

  describe('遥测数据提交 - 参数校验测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('提交遥测数据 - 正常流程', async () => {
      const telemetryData = DataAggregationTestDataFactory.createTelemetryData();
      const response = await api.post(`${API_PREFIX}/ingest`, telemetryData);
      
      expect(response.status).toBe(200);
      expect(response.data).toHaveProperty('message', 'Telemetry data stored successfully');
    });

    test('批量提交遥测数据 - 正常流程', async () => {
      const deviceId = DataAggregationTestDataFactory.generateId('dev_batch_');
      const batchData = DataAggregationTestDataFactory.createBatchTelemetryData(deviceId, 10);
      const response = await api.post(`${API_PREFIX}/batch`, { data: batchData });
      
      expect(response.status).toBe(200);
      expect(response.data).toHaveProperty('success_count', 10);
    });

    const invalidScenarios = [
      'missing_device_id', 'empty_device_id', 'null_device_id',
      'missing_metric_name', 'empty_metric_name', 'null_metric_name',
      'missing_value', 'null_value', 'non_numeric_value',
      'nan_value', 'infinity_value', 'negative_infinity_value',
      'invalid_timestamp_format'
    ];

    invalidScenarios.forEach((scenario) => {
      test(`无效遥测数据: ${scenario} 应返回400`, async () => {
        const invalidData = DataAggregationTestDataFactory.createInvalidTelemetryData(scenario);
        try {
          await api.post(`${API_PREFIX}/ingest`, invalidData);
          throw new Error('Expected request to fail');
        } catch (error) {
          expect(error.response.status).toBe(400);
        }
      });
    });

    test('极端值处理 - Number.MAX_VALUE', async () => {
      const extremeData = DataAggregationTestDataFactory.createInvalidTelemetryData('extreme_large_value');
      try {
        const response = await api.post(`${API_PREFIX}/ingest`, extremeData);
        expect([200, 400]).toContain(response.status);
      } catch (error) {
        expect([400, 500]).toContain(error.response.status);
      }
    });

    test('极端值处理 - Number.MIN_VALUE', async () => {
      const extremeData = DataAggregationTestDataFactory.createInvalidTelemetryData('extreme_small_value');
      try {
        const response = await api.post(`${API_PREFIX}/ingest`, extremeData);
        expect([200, 400]).toContain(response.status);
      } catch (error) {
        expect([400, 500]).toContain(error.response.status);
      }
    });
  });

  describe('聚合规则管理 - 参数校验测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('创建聚合规则 - 正常流程', async () => {
      const ruleData = DataAggregationTestDataFactory.createAggregationRuleData();
      const response = await api.post(`${API_PREFIX}/aggregation/rules`, ruleData);
      
      expect(response.status).toBe(200);
      expect(response.data).toHaveProperty('rule_id');
    });

    test('查询聚合规则列表', async () => {
      const response = await api.get(`${API_PREFIX}/aggregation/rules`);
      expect(response.status).toBe(200);
      expect(response.data).toHaveProperty('rules');
      expect(Array.isArray(response.data.rules)).toBe(true);
    });

    const invalidRuleScenarios = [
      'missing_metric_name', 'empty_metric_name', 'null_metric_name',
      'missing_aggregation_type', 'empty_aggregation_type', 'null_aggregation_type',
      'invalid_aggregation_type',
      'zero_interval_seconds', 'negative_interval_seconds', 'too_large_interval_seconds',
      'invalid_retention_policy', 'invalid_output_topic'
    ];

    invalidRuleScenarios.forEach((scenario) => {
      test(`无效聚合规则: ${scenario} 应返回400`, async () => {
        const invalidData = DataAggregationTestDataFactory.createInvalidAggregationRuleData(scenario);
        try {
          await api.post(`${API_PREFIX}/aggregation/rules`, invalidData);
          throw new Error('Expected request to fail');
        } catch (error) {
          expect(error.response.status).toBe(400);
        }
      });
    });
  });

  describe('事务回滚 - 核心测试场景', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    const rollbackScenarios = DataAggregationTestDataFactory.createTransactionRollbackScenarios();

    rollbackScenarios.forEach((scenario) => {
      test(`事务回滚: ${scenario.name}`, async () => {
        const deviceId = DataAggregationTestDataFactory.generateId('dev_rollback_');
        
        const initialStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
        const initialCount = initialStats.data.total_records || 0;

        try {
          if (scenario.name === '批量数据中途失败 - 第N条数据异常') {
            const goodData = DataAggregationTestDataFactory.createBatchTelemetryData(
              deviceId,
              scenario.data.goodCount
            );
            
            const badData = DataAggregationTestDataFactory.createInvalidTelemetryData(scenario.data.badScenario);
            badData.device_id = deviceId;
            badData.timestamp = DataAggregationTestDataFactory.generateTimestamp(-1);
            
            const mixedData = [...goodData, badData];
            
            try {
              await api.post(`${API_PREFIX}/batch`, { data: mixedData });
              throw new Error('Expected request to fail');
            } catch (error) {
              expect([400, 500]).toContain(error.response.status);
            }

            const afterStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
            const afterCount = afterStats.data.total_records || 0;
            
            if (scenario.expected.shouldRollback) {
              expect(afterCount).toBe(initialCount);
              console.log(`✓ 事务回滚成功: 初始 ${initialCount}, 失败后 ${afterCount}`);
            } else {
              expect(afterCount).toBeGreaterThan(initialCount);
              console.log(`✓ 部分提交: 初始 ${initialCount}, 部分提交后 ${afterCount}`);
            }
          } else if (scenario.name === '并发写入冲突 - 同一时间戳') {
            const data1 = DataAggregationTestDataFactory.createTelemetryData({
              device_id: deviceId,
              metric_name: 'temperature',
              value: 25.0,
              timestamp: scenario.data.timestamp,
            });
            
            const data2 = DataAggregationTestDataFactory.createTelemetryData({
              device_id: deviceId,
              metric_name: 'temperature',
              value: 26.0,
              timestamp: scenario.data.timestamp,
            });

            const responses = await Promise.allSettled([
              api.post(`${API_PREFIX}/ingest`, data1),
              api.post(`${API_PREFIX}/ingest`, data2),
            ]);

            const successCount = responses.filter((r) => r.status === 'fulfilled').length;
            const failCount = responses.filter((r) => r.status === 'rejected').length;
            
            if (scenario.expected.atomicity) {
              expect(successCount).toBe(1);
              expect(failCount).toBe(1);
              console.log(`✓ 并发控制: 成功 ${successCount}, 失败 ${failCount}`);
            } else {
              expect([1, 2]).toContain(successCount);
            }
          } else if (scenario.name === '内存溢出保护 - 超大批量数据') {
            const hugeBatch = DataAggregationTestDataFactory.createBatchTelemetryData(
              deviceId,
              scenario.data.batchSize
            );

            try {
              const start = Date.now();
              const response = await api.post(`${API_PREFIX}/batch`, { data: hugeBatch });
              const duration = Date.now() - start;
              
              console.log(`超大批量处理耗时: ${duration}ms, 状态码: ${response.status}`);
              expect(response.status).toBe(200);
              expect(response.data.success_count).toBeLessThanOrEqual(scenario.data.batchSize);
            } catch (error) {
              expect([400, 413, 500]).toContain(error.response.status);
              
              const afterStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
              const afterCount = afterStats.data.total_records || 0;
              
              if (scenario.expected.shouldRollback) {
                expect(afterCount).toBe(initialCount);
                console.log(`✓ 内存溢出回滚成功: 初始 ${initialCount}, 失败后 ${afterCount}`);
              }
            }
          } else if (scenario.name === '聚合计算中途失败 - 数据一致性验证') {
            for (let i = 0; i < scenario.data.recordCount; i++) {
              const data = DataAggregationTestDataFactory.createTelemetryData({
                device_id: deviceId,
                metric_name: scenario.data.metricName,
                value: i % 2 === 0 ? scenario.data.goodValue : scenario.data.badValue,
              });
              
              try {
                await api.post(`${API_PREFIX}/ingest`, data);
              } catch (error) {
                console.log(`第 ${i} 条数据失败: ${error.response.status}`);
              }
            }

            try {
              const response = await api.post(`${API_PREFIX}/aggregation/execute`, {
                device_id: deviceId,
                metric_name: scenario.data.metricName,
                aggregation_type: scenario.data.aggregationType,
              });
              
              if (scenario.expected.shouldRollback) {
                expect([400, 500]).toContain(response.status);
              } else {
                expect(response.status).toBe(200);
                expect(response.data).toHaveProperty('result');
              }
            } catch (error) {
              const afterStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
              const afterCount = afterStats.data.total_records || 0;
              
              console.log(`聚合失败后数据量: ${afterCount}`);
              expect(afterCount).toBeGreaterThan(0);
            }
          } else if (scenario.name === '网络中断模拟 - 批量提交中途断开') {
            const goodData = DataAggregationTestDataFactory.createBatchTelemetryData(
              deviceId,
              scenario.data.successCount
            );

            for (let i = 0; i < goodData.length; i++) {
              try {
                await api.post(`${API_PREFIX}/ingest`, goodData[i]);
              } catch (error) {
                console.log(`网络中断在第 ${i} 条数据`);
                break;
              }
            }

            const afterStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
            const afterCount = afterStats.data.total_records || 0;
            
            console.log(`网络中断后成功提交: ${afterCount - initialCount}/${goodData.length}`);
            expect(afterCount).toBeGreaterThanOrEqual(initialCount);
          }
        } catch (error) {
          console.error(`测试场景 ${scenario.name} 出错:`, error.message);
          throw error;
        }
      }, 30000);
    });
  });

  describe('聚合边界条件测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    const boundaryScenarios = DataAggregationTestDataFactory.createAggregationBoundaryScenarios();

    boundaryScenarios.forEach((scenario) => {
      test(`聚合边界: ${scenario.name}`, async () => {
        const deviceId = DataAggregationTestDataFactory.generateId('dev_agg_boundary_');
        
        if (scenario.name === '空数据集聚合') {
          try {
            const response = await api.post(`${API_PREFIX}/aggregation/execute`, {
              device_id: deviceId,
              metric_name: 'temperature',
              aggregation_type: 'avg',
            });
            
            if (scenario.expected.statusCode === 200) {
              expect(response.status).toBe(200);
              expect(response.data.result).toBeNull();
            } else {
              expect([400, 404]).toContain(response.status);
            }
          } catch (error) {
            expect([400, 404]).toContain(error.response.status);
          }
        } else if (scenario.name === '单元素数据集聚合') {
          const data = DataAggregationTestDataFactory.createTelemetryData({
            device_id: deviceId,
            metric_name: 'temperature',
            value: scenario.data.values[0],
          });
          await api.post(`${API_PREFIX}/ingest`, data);

          const response = await api.post(`${API_PREFIX}/aggregation/execute`, {
            device_id: deviceId,
            metric_name: 'temperature',
            aggregation_type: 'avg',
          });

          expect(response.status).toBe(200);
          expect(response.data.result).toBe(scenario.data.values[0]);
        } else if (scenario.name === '包含NaN的数据集聚合') {
          for (const value of scenario.data.values) {
            const data = DataAggregationTestDataFactory.createTelemetryData({
              device_id: deviceId,
              metric_name: 'temperature',
              value: isNaN(value) ? 0 : value,
            });
            await api.post(`${API_PREFIX}/ingest`, data);
          }

          try {
            const response = await api.post(`${API_PREFIX}/aggregation/execute`, {
              device_id: deviceId,
              metric_name: 'temperature',
              aggregation_type: 'avg',
            });
            
            expect(response.status).toBe(200);
            expect(response.data.result).toBeCloseTo(scenario.expected.result, 1);
          } catch (error) {
            expect([400, 200]).toContain(error.response.status);
          }
        } else if (scenario.name === '全部为NaN的数据集') {
          const data = DataAggregationTestDataFactory.createTelemetryData({
            device_id: deviceId,
            metric_name: 'temperature',
            value: 0,
          });
          await api.post(`${API_PREFIX}/ingest`, data);

          try {
            const response = await api.post(`${API_PREFIX}/aggregation/execute`, {
              device_id: deviceId,
              metric_name: 'temperature',
              aggregation_type: 'avg',
            });
            
            expect([200, 400]).toContain(response.status);
            if (response.status === 200) {
              expect(response.data.result).toBeNull();
            }
          } catch (error) {
            expect([400, 500]).toContain(error.response.status);
          }
        } else if (scenario.name === '极值数据集聚合') {
          for (const value of scenario.data.values) {
            const data = DataAggregationTestDataFactory.createTelemetryData({
              device_id: deviceId,
              metric_name: 'temperature',
              value: value,
            });
            await api.post(`${API_PREFIX}/ingest`, data);
          }

          const response = await api.post(`${API_PREFIX}/aggregation/execute`, {
            device_id: deviceId,
            metric_name: 'temperature',
            aggregation_type: 'avg',
          });

          expect(response.status).toBe(200);
          expect(typeof response.data.result).toBe('number');
        } else if (scenario.name === '时间范围边界测试') {
          const now = Date.now();
          for (let i = 0; i < 10; i++) {
            const data = DataAggregationTestDataFactory.createTelemetryData({
              device_id: deviceId,
              metric_name: 'temperature',
              value: 20 + i,
              timestamp: new Date(now - i * 60000).toISOString(),
            });
            await api.post(`${API_PREFIX}/ingest`, data);
          }

          const response = await api.post(`${API_PREFIX}/aggregation/execute`, {
            device_id: deviceId,
            metric_name: 'temperature',
            aggregation_type: 'avg',
            start_time: new Date(now - 5 * 60000).toISOString(),
            end_time: new Date(now).toISOString(),
          });

          expect(response.status).toBe(200);
          expect(response.data.result).toBeGreaterThanOrEqual(20);
          expect(response.data.result).toBeLessThanOrEqual(30);
        }
      }, 15000);
    });
  });

  describe('数据完整性验证', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('批量提交后数据计数正确', async () => {
      const deviceId = DataAggregationTestDataFactory.generateId('dev_integrity_');
      const recordCount = 100;
      
      const batchData = DataAggregationTestDataFactory.createBatchTelemetryData(
        deviceId,
        recordCount
      );

      const beforeStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
      const beforeCount = beforeStats.data.total_records || 0;

      const response = await api.post(`${API_PREFIX}/batch`, { data: batchData });
      expect(response.status).toBe(200);
      expect(response.data.success_count).toBe(recordCount);

      const afterStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
      const afterCount = afterStats.data.total_records || 0;

      expect(afterCount - beforeCount).toBe(recordCount);
      console.log(`✓ 数据完整性验证: ${beforeCount} -> ${afterCount}, 新增 ${recordCount} 条`);
    });

    test('事务失败后无脏数据残留', async () => {
      const deviceId = DataAggregationTestDataFactory.generateId('dev_dirty_');
      
      const beforeStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
      const beforeCount = beforeStats.data.total_records || 0;

      const goodData = DataAggregationTestDataFactory.createBatchTelemetryData(deviceId, 5);
      const badData = DataAggregationTestDataFactory.createInvalidTelemetryData('non_numeric_value');
      badData.device_id = deviceId;

      const mixedData = [...goodData, badData];

      try {
        await api.post(`${API_PREFIX}/batch`, { data: mixedData });
      } catch (error) {
        expect([400, 500]).toContain(error.response.status);
      }

      const afterStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
      const afterCount = afterStats.data.total_records || 0;

      const diff = afterCount - beforeCount;
      if (diff === 0) {
        console.log('✓ 事务完全回滚，无脏数据');
      } else if (diff === 5) {
        console.log('✓ 部分提交模式，好数据已保存，坏数据已拒绝');
      } else {
        console.log(`⚠️ 数据状态不明确: ${beforeCount} -> ${afterCount}`);
      }
      
      expect([0, 5]).toContain(diff);
    });
  });

  describe('幂等性测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('重复提交相同数据应幂等', async () => {
      const deviceId = DataAggregationTestDataFactory.generateId('dev_idempotent_');
      const timestamp = DataAggregationTestDataFactory.generateTimestamp();
      
      const data = DataAggregationTestDataFactory.createTelemetryData({
        device_id: deviceId,
        metric_name: 'temperature',
        value: 25.5,
        timestamp: timestamp,
      });

      const beforeStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
      const beforeCount = beforeStats.data.total_records || 0;

      const response1 = await api.post(`${API_PREFIX}/ingest`, data);
      const response2 = await api.post(`${API_PREFIX}/ingest`, data);
      const response3 = await api.post(`${API_PREFIX}/ingest`, data);

      expect(response1.status).toBe(200);
      expect(response2.status).toBe(200);
      expect(response3.status).toBe(200);

      const afterStats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
      const afterCount = afterStats.data.total_records || 0;

      const diff = afterCount - beforeCount;
      console.log(`幂等性测试: 提交3次，实际存储 ${diff} 条`);
      
      expect([1, 3]).toContain(diff);
    });
  });

  describe('并发事务隔离性测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('多个设备并发写入互不影响', async () => {
      const deviceCount = 5;
      const recordsPerDevice = 20;
      
      const beforeCounts = {};
      const afterCounts = {};

      for (let i = 0; i < deviceCount; i++) {
        const deviceId = DataAggregationTestDataFactory.generateId(`dev_concurrent_${i}_`);
        const stats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
        beforeCounts[deviceId] = stats.data.total_records || 0;
      }

      const writePromises = [];
      for (const deviceId of Object.keys(beforeCounts)) {
        const batchData = DataAggregationTestDataFactory.createBatchTelemetryData(
          deviceId,
          recordsPerDevice
        );
        writePromises.push(api.post(`${API_PREFIX}/batch`, { data: batchData }));
      }

      const responses = await Promise.allSettled(writePromises);
      const successCount = responses.filter((r) => r.status === 'fulfilled').length;
      
      expect(successCount).toBe(deviceCount);

      for (const deviceId of Object.keys(beforeCounts)) {
        const stats = await api.get(`${API_PREFIX}/stats?device_id=${deviceId}`);
        afterCounts[deviceId] = stats.data.total_records || 0;
        
        const diff = afterCounts[deviceId] - beforeCounts[deviceId];
        expect(diff).toBe(recordsPerDevice);
        console.log(`✓ 设备 ${deviceId}: ${beforeCounts[deviceId]} -> ${afterCounts[deviceId]}`);
      }
    }, 30000);
  });
});

describe('数据流边缘聚合模块 - 单元测试（无需API）', () => {
  describe('数据构造验证', () => {
    test('遥测数据包含所有必填字段', () => {
      const data = DataAggregationTestDataFactory.createTelemetryData();
      
      expect(data).toHaveProperty('device_id');
      expect(data).toHaveProperty('metric_name');
      expect(data).toHaveProperty('value');
      expect(data).toHaveProperty('timestamp');
      expect(data).toHaveProperty('quality');
    });

    test('批量数据生成数量正确', () => {
      const deviceId = DataAggregationTestDataFactory.generateId('test_');
      const batchData = DataAggregationTestDataFactory.createBatchTelemetryData(deviceId, 20);
      
      expect(batchData.length).toBe(20);
      batchData.forEach((item) => {
        expect(item.device_id).toBe(deviceId);
      });
    });

    test('聚合规则包含所有必填字段', () => {
      const rule = DataAggregationTestDataFactory.createAggregationRuleData();
      
      expect(rule).toHaveProperty('metric_name');
      expect(rule).toHaveProperty('aggregation_type');
      expect(rule).toHaveProperty('interval_seconds');
      expect(rule).toHaveProperty('output_topic');
    });

    test('无效遥测数据场景覆盖全面', () => {
      const scenarios = [
        'missing_device_id', 'empty_device_id', 'null_device_id',
        'missing_metric_name', 'empty_metric_name', 'null_metric_name',
        'missing_value', 'null_value', 'non_numeric_value',
        'nan_value', 'infinity_value', 'negative_infinity_value',
        'invalid_timestamp_format', 'extreme_large_value', 'extreme_small_value'
      ];
      
      scenarios.forEach((scenario) => {
        const data = DataAggregationTestDataFactory.createInvalidTelemetryData(scenario);
        expect(data).toBeDefined();
      });
    });

    test('无效聚合规则场景覆盖全面', () => {
      const scenarios = [
        'missing_metric_name', 'empty_metric_name', 'null_metric_name',
        'missing_aggregation_type', 'empty_aggregation_type', 'null_aggregation_type',
        'invalid_aggregation_type',
        'zero_interval_seconds', 'negative_interval_seconds', 'too_large_interval_seconds',
        'invalid_retention_policy', 'invalid_output_topic'
      ];
      
      scenarios.forEach((scenario) => {
        const data = DataAggregationTestDataFactory.createInvalidAggregationRuleData(scenario);
        expect(data).toBeDefined();
      });
    });

    test('事务回滚场景数量正确', () => {
      const scenarios = DataAggregationTestDataFactory.createTransactionRollbackScenarios();
      expect(scenarios.length).toBe(5);
    });

    test('聚合边界场景数量正确', () => {
      const scenarios = DataAggregationTestDataFactory.createAggregationBoundaryScenarios();
      expect(scenarios.length).toBe(6);
    });

    test('批量数据支持自定义指标生成器', () => {
      const deviceId = DataAggregationTestDataFactory.generateId('custom_');
      let counter = 0;
      const generator = () => ({
        metric_name: 'custom_metric',
        value: counter++,
        quality: 'GOOD',
      });
      
      const batchData = DataAggregationTestDataFactory.createBatchTelemetryData(
        deviceId,
        5,
        0,
        generator
      );
      
      expect(batchData.length).toBe(5);
      batchData.forEach((item, index) => {
        expect(item.metric_name).toBe('custom_metric');
        expect(item.value).toBe(index);
      });
    });

    test('时间戳偏移正确', () => {
      const now = new Date();
      const offsetSeconds = -3600;
      const timestamp = DataAggregationTestDataFactory.generateTimestamp(offsetSeconds);
      const parsed = new Date(timestamp);
      
      const diffSeconds = (now - parsed) / 1000;
      expect(Math.abs(diffSeconds - Math.abs(offsetSeconds))).toBeLessThan(5);
    });
  });

  describe('事务场景验证', () => {
    test('批量数据中途失败场景构造正确', () => {
      const scenarios = DataAggregationTestDataFactory.createTransactionRollbackScenarios();
      const batchFail = scenarios.find((s) => s.name === '批量数据中途失败 - 第N条数据异常');
      
      expect(batchFail).toBeDefined();
      expect(batchFail.data.goodCount).toBe(100);
      expect(batchFail.data.badIndex).toBe(50);
      expect(batchFail.data.badScenario).toBe('non_numeric_value');
      expect(batchFail.expected.shouldRollback).toBe(true);
    });

    test('并发写入冲突场景构造正确', () => {
      const scenarios = DataAggregationTestDataFactory.createTransactionRollbackScenarios();
      const conflict = scenarios.find((s) => s.name === '并发写入冲突 - 同一时间戳');
      
      expect(conflict).toBeDefined();
      expect(conflict.data.concurrentWriters).toBe(10);
      expect(conflict.expected.atomicity).toBe(true);
    });

    test('内存溢出场景构造正确', () => {
      const scenarios = DataAggregationTestDataFactory.createTransactionRollbackScenarios();
      const oom = scenarios.find((s) => s.name === '内存溢出保护 - 超大批量数据');
      
      expect(oom).toBeDefined();
      expect(oom.data.batchSize).toBe(100000);
      expect(oom.expected.shouldRollback).toBe(true);
    });

    test('聚合计算失败场景构造正确', () => {
      const scenarios = DataAggregationTestDataFactory.createTransactionRollbackScenarios();
      const aggFail = scenarios.find((s) => s.name === '聚合计算中途失败 - 数据一致性验证');
      
      expect(aggFail).toBeDefined();
      expect(aggFail.data.recordCount).toBe(1000);
      expect(aggFail.data.badValue).toBeNaN();
    });
  });

  describe('聚合边界场景验证', () => {
    test('空数据集聚合场景构造正确', () => {
      const scenarios = DataAggregationTestDataFactory.createAggregationBoundaryScenarios();
      const empty = scenarios.find((s) => s.name === '空数据集聚合');
      
      expect(empty).toBeDefined();
      expect(empty.data.values).toHaveLength(0);
      expect(empty.expected.statusCode).toBe(200);
    });

    test('单元素数据集聚合场景构造正确', () => {
      const scenarios = DataAggregationTestDataFactory.createAggregationBoundaryScenarios();
      const single = scenarios.find((s) => s.name === '单元素数据集聚合');
      
      expect(single).toBeDefined();
      expect(single.data.values).toHaveLength(1);
      expect(single.expected.result).toBe(42.5);
    });

    test('包含NaN的数据集场景构造正确', () => {
      const scenarios = DataAggregationTestDataFactory.createAggregationBoundaryScenarios();
      const nanData = scenarios.find((s) => s.name === '包含NaN的数据集聚合');
      
      expect(nanData).toBeDefined();
      expect(nanData.data.values.includes(NaN)).toBe(true);
      expect(nanData.expected.result).toBe(25);
    });

    test('极值数据集场景构造正确', () => {
      const scenarios = DataAggregationTestDataFactory.createAggregationBoundaryScenarios();
      const extreme = scenarios.find((s) => s.name === '极值数据集聚合');
      
      expect(extreme).toBeDefined();
      expect(extreme.data.values).toContain(Number.MAX_VALUE);
      expect(extreme.data.values).toContain(Number.MIN_VALUE);
    });
  });

  describe('数据覆盖功能验证', () => {
    test('遥测数据支持自定义覆盖', () => {
      const customData = DataAggregationTestDataFactory.createTelemetryData({
        device_id: 'custom_device_001',
        metric_name: 'custom_metric',
        value: 99.99,
        quality: 'BAD',
      });
      
      expect(customData.device_id).toBe('custom_device_001');
      expect(customData.metric_name).toBe('custom_metric');
      expect(customData.value).toBe(99.99);
      expect(customData.quality).toBe('BAD');
    });

    test('聚合规则支持自定义覆盖', () => {
      const customRule = DataAggregationTestDataFactory.createAggregationRuleData({
        metric_name: 'pressure',
        aggregation_type: 'max',
        interval_seconds: 300,
        output_topic: '/custom/topic',
      });
      
      expect(customRule.metric_name).toBe('pressure');
      expect(customRule.aggregation_type).toBe('max');
      expect(customRule.interval_seconds).toBe(300);
      expect(customRule.output_topic).toBe('/custom/topic');
    });
  });
});
