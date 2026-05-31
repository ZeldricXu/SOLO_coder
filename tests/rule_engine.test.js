const request = require('supertest');
const axios = require('axios');
const { RuleEngineTestDataFactory } = require('./testDataFactory');

const BASE_URL = process.env.TEST_API_BASE_URL || 'http://localhost:8000';
const API_PREFIX = '/api/v1/rules';

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

describe('边缘规则引擎模块 - 参数校验完备性', () => {
  let apiAvailable = false;

  beforeAll(async () => {
    apiAvailable = await checkApiAvailable();
  });

  describe('规则创建 - 参数校验测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('创建规则 - 正常流程', async () => {
      const ruleData = RuleEngineTestDataFactory.createRuleData();
      const response = await api.post(API_PREFIX, ruleData);
      
      expect(response.status).toBe(200);
      expect(response.data).toHaveProperty('rule_id');
      expect(response.data).toHaveProperty('name', ruleData.name);
    });

    const invalidScenarios = [
      'empty_name', 'null_name', 'missing_name',
      'too_long_name',
      'null_description',
      'missing_enabled', 'null_enabled',
      'missing_conditions', 'empty_conditions', 'null_conditions',
      'missing_actions', 'empty_actions', 'null_actions',
      'missing_condition_field', 'empty_condition_field', 'null_condition_field',
      'missing_condition_operator', 'empty_condition_operator', 'null_condition_operator',
      'invalid_condition_operator',
      'missing_action_type', 'empty_action_type', 'null_action_type',
      'invalid_action_type'
    ];

    invalidScenarios.forEach((scenario) => {
      test(`无效规则数据: ${scenario} 应返回400`, async () => {
        const invalidData = RuleEngineTestDataFactory.createInvalidRuleData(scenario);
        try {
          await api.post(API_PREFIX, invalidData);
          throw new Error('Expected request to fail');
        } catch (error) {
          expect([400, 422]).toContain(error.response.status);
        }
      });
    });
  });

  describe('规则条件评估测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    const evaluationScenarios = RuleEngineTestDataFactory.createTelemetryEvaluationScenarios();

    evaluationScenarios.forEach((scenario) => {
      test(`规则评估: ${scenario.name}`, async () => {
        const condition = RuleEngineTestDataFactory.createCondition({
          field: scenario.data.field,
          operator: scenario.data.operator,
          value: scenario.data.conditionValue,
        });

        const ruleData = RuleEngineTestDataFactory.createRuleData({
          name: `eval_test_${scenario.name.replace(/\s+/g, '_')}`,
          conditions: [condition],
        });

        const createResponse = await api.post(API_PREFIX, ruleData);
        const ruleId = createResponse.data.rule_id;

        try {
          const evalResponse = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, {
            telemetry_data: scenario.data.telemetryData,
          });

          expect(evalResponse.status).toBe(200);
          expect(evalResponse.data).toHaveProperty('triggered');
          expect(evalResponse.data.triggered).toBe(scenario.expected.triggered);

          if (scenario.expected.triggered) {
            expect(evalResponse.data.actions_executed).toBeGreaterThan(0);
          }
        } finally {
          try {
            await api.delete(`${API_PREFIX}/${ruleId}`);
          } catch (e) {}
        }
      });
    });
  });

  describe('规则动作参数校验测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    const actionScenarios = RuleEngineTestDataFactory.createRuleActionValidationScenarios();

    actionScenarios.forEach((scenario) => {
      test(`动作验证: ${scenario.name}`, async () => {
        const ruleData = RuleEngineTestDataFactory.createRuleData({
          name: `action_test_${scenario.name.replace(/\s+/g, '_')}`,
          actions: [scenario.data.action],
        });

        try {
          const response = await api.post(API_PREFIX, ruleData);
          
          if (scenario.expected.valid) {
            expect(response.status).toBe(200);
            
            const ruleId = response.data.rule_id;
            try {
              await api.delete(`${API_PREFIX}/${ruleId}`);
            } catch (e) {}
          } else {
            expect([400, 422]).toContain(response.status);
          }
        } catch (error) {
          if (scenario.expected.valid) {
            console.error(`意外失败: ${scenario.name}`, error.message);
            throw error;
          } else {
            expect([400, 422]).toContain(error.response.status);
          }
        }
      });
    });
  });

  describe('规则生命周期测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    const lifecycleScenarios = RuleEngineTestDataFactory.createRuleLifecycleScenarios();

    lifecycleScenarios.forEach((scenario) => {
      test(`生命周期: ${scenario.name}`, async () => {
        if (scenario.name === '创建新规则') {
          const ruleData = RuleEngineTestDataFactory.createRuleData(scenario.data);
          const response = await api.post(API_PREFIX, ruleData);
          
          expect(response.status).toBe(200);
          expect(response.data.name).toBe(scenario.data.name);
          expect(response.data.enabled).toBe(scenario.data.enabled);
        } else if (scenario.name === '启用已存在的规则') {
          const ruleData = RuleEngineTestDataFactory.createRuleData({
            name: 'enable_test_rule',
            enabled: false,
          });
          const createResponse = await api.post(API_PREFIX, ruleData);
          const ruleId = createResponse.data.rule_id;

          try {
            const enableResponse = await api.put(`${API_PREFIX}/${ruleId}/enable`);
            expect(enableResponse.status).toBe(200);
            expect(enableResponse.data.enabled).toBe(true);
          } finally {
            try {
              await api.delete(`${API_PREFIX}/${ruleId}`);
            } catch (e) {}
          }
        } else if (scenario.name === '禁用已存在的规则') {
          const ruleData = RuleEngineTestDataFactory.createRuleData({
            name: 'disable_test_rule',
            enabled: true,
          });
          const createResponse = await api.post(API_PREFIX, ruleData);
          const ruleId = createResponse.data.rule_id;

          try {
            const disableResponse = await api.put(`${API_PREFIX}/${ruleId}/disable`);
            expect(disableResponse.status).toBe(200);
            expect(disableResponse.data.enabled).toBe(false);
          } finally {
            try {
              await api.delete(`${API_PREFIX}/${ruleId}`);
            } catch (e) {}
          }
        } else if (scenario.name === '删除已存在的规则') {
          const ruleData = RuleEngineTestDataFactory.createRuleData({
            name: 'delete_test_rule',
          });
          const createResponse = await api.post(API_PREFIX, ruleData);
          const ruleId = createResponse.data.rule_id;

          const deleteResponse = await api.delete(`${API_PREFIX}/${ruleId}`);
          expect(deleteResponse.status).toBe(200);

          try {
            await api.get(`${API_PREFIX}/${ruleId}`);
            throw new Error('Expected get to fail');
          } catch (error) {
            expect([404, 400]).toContain(error.response.status);
          }
        } else if (scenario.name === '触发频率限制测试') {
          const triggerLimit = scenario.data.triggerLimit;
          const ruleData = RuleEngineTestDataFactory.createRuleData({
            name: 'rate_limit_test_rule',
            trigger_limit: triggerLimit,
            cooldown_seconds: scenario.data.cooldownSeconds,
          });
          const createResponse = await api.post(API_PREFIX, ruleData);
          const ruleId = createResponse.data.rule_id;

          try {
            const telemetryData = { temperature: 100 };
            const triggerPromises = [];
            
            for (let i = 0; i < triggerLimit + 5; i++) {
              triggerPromises.push(
                api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetryData })
              );
            }

            const responses = await Promise.allSettled(triggerPromises);
            const successResponses = responses.filter(
              (r) => r.status === 'fulfilled' && r.value.status === 200
            );
            
            console.log(`触发频率限制: 尝试 ${triggerLimit + 5} 次, 成功 ${successResponses.length} 次`);
            expect(successResponses.length).toBeLessThanOrEqual(triggerLimit + 1);
          } finally {
            try {
              await api.delete(`${API_PREFIX}/${ruleId}`);
            } catch (e) {}
          }
        } else if (scenario.name === '冷却时间测试') {
          const cooldownSeconds = scenario.data.cooldownSeconds;
          const ruleData = RuleEngineTestDataFactory.createRuleData({
            name: 'cooldown_test_rule',
            cooldown_seconds: cooldownSeconds,
          });
          const createResponse = await api.post(API_PREFIX, ruleData);
          const ruleId = createResponse.data.rule_id;

          try {
            const telemetryData = { temperature: 100 };
            
            const response1 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetryData });
            expect(response1.status).toBe(200);
            expect(response1.data.triggered).toBe(true);

            const response2 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetryData });
            expect(response2.status).toBe(200);
            
            if (cooldownSeconds > 0) {
              expect(response2.data.triggered).toBe(false);
              expect(response2.data.cooldown_active).toBe(true);
            }
          } finally {
            try {
              await api.delete(`${API_PREFIX}/${ruleId}`);
            } catch (e) {}
          }
        } else if (scenario.name === '查询不存在的规则') {
          try {
            await api.get(`${API_PREFIX}/nonexistent_rule_999`);
            throw new Error('Expected get to fail');
          } catch (error) {
            expect([404, 400]).toContain(error.response.status);
          }
        }
      }, 15000);
    });
  });

  describe('复合条件测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('AND复合条件 - 所有条件满足时触发', async () => {
      const condition1 = RuleEngineTestDataFactory.createCondition({
        field: 'temperature',
        operator: '>',
        value: 50,
      });

      const condition2 = RuleEngineTestDataFactory.createCondition({
        field: 'pressure',
        operator: '>',
        value: 100,
      });

      const compoundCondition = RuleEngineTestDataFactory.createCompoundCondition('AND', [
        condition1,
        condition2,
      ]);

      const ruleData = RuleEngineTestDataFactory.createRuleData({
        name: 'and_condition_test',
        conditions: [compoundCondition],
      });

      const createResponse = await api.post(API_PREFIX, ruleData);
      const ruleId = createResponse.data.rule_id;

      try {
        const telemetry1 = { temperature: 60, pressure: 110 };
        const response1 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry1 });
        expect(response1.data.triggered).toBe(true);

        const telemetry2 = { temperature: 60, pressure: 90 };
        const response2 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry2 });
        expect(response2.data.triggered).toBe(false);

        const telemetry3 = { temperature: 40, pressure: 110 };
        const response3 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry3 });
        expect(response3.data.triggered).toBe(false);
      } finally {
        try {
          await api.delete(`${API_PREFIX}/${ruleId}`);
        } catch (e) {}
      }
    });

    test('OR复合条件 - 任一条件满足时触发', async () => {
      const condition1 = RuleEngineTestDataFactory.createCondition({
        field: 'temperature',
        operator: '>',
        value: 50,
      });

      const condition2 = RuleEngineTestDataFactory.createCondition({
        field: 'pressure',
        operator: '>',
        value: 100,
      });

      const compoundCondition = RuleEngineTestDataFactory.createCompoundCondition('OR', [
        condition1,
        condition2,
      ]);

      const ruleData = RuleEngineTestDataFactory.createRuleData({
        name: 'or_condition_test',
        conditions: [compoundCondition],
      });

      const createResponse = await api.post(API_PREFIX, ruleData);
      const ruleId = createResponse.data.rule_id;

      try {
        const telemetry1 = { temperature: 60, pressure: 90 };
        const response1 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry1 });
        expect(response1.data.triggered).toBe(true);

        const telemetry2 = { temperature: 40, pressure: 110 };
        const response2 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry2 });
        expect(response2.data.triggered).toBe(true);

        const telemetry3 = { temperature: 40, pressure: 90 };
        const response3 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry3 });
        expect(response3.data.triggered).toBe(false);
      } finally {
        try {
          await api.delete(`${API_PREFIX}/${ruleId}`);
        } catch (e) {}
      }
    });

    test('嵌套复合条件测试', async () => {
      const condition1 = RuleEngineTestDataFactory.createCondition({
        field: 'temperature',
        operator: '>',
        value: 50,
      });

      const condition2 = RuleEngineTestDataFactory.createCondition({
        field: 'humidity',
        operator: '>',
        value: 80,
      });

      const innerOr = RuleEngineTestDataFactory.createCompoundCondition('OR', [
        condition1,
        condition2,
      ]);

      const condition3 = RuleEngineTestDataFactory.createCondition({
        field: 'pressure',
        operator: '>',
        value: 100,
      });

      const outerAnd = RuleEngineTestDataFactory.createCompoundCondition('AND', [
        innerOr,
        condition3,
      ]);

      const ruleData = RuleEngineTestDataFactory.createRuleData({
        name: 'nested_condition_test',
        conditions: [outerAnd],
      });

      const createResponse = await api.post(API_PREFIX, ruleData);
      const ruleId = createResponse.data.rule_id;

      try {
        const telemetry1 = { temperature: 60, humidity: 50, pressure: 110 };
        const response1 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry1 });
        expect(response1.data.triggered).toBe(true);

        const telemetry2 = { temperature: 40, humidity: 90, pressure: 110 };
        const response2 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry2 });
        expect(response2.data.triggered).toBe(true);

        const telemetry3 = { temperature: 60, humidity: 50, pressure: 90 };
        const response3 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry3 });
        expect(response3.data.triggered).toBe(false);
      } finally {
        try {
          await api.delete(`${API_PREFIX}/${ruleId}`);
        } catch (e) {}
      }
    });
  });

  describe('嵌套字段访问测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('单层嵌套字段访问', async () => {
      const condition = RuleEngineTestDataFactory.createCondition({
        field: 'sensor.temperature',
        operator: '>',
        value: 50,
      });

      const ruleData = RuleEngineTestDataFactory.createRuleData({
        name: 'nested_field_test',
        conditions: [condition],
      });

      const createResponse = await api.post(API_PREFIX, ruleData);
      const ruleId = createResponse.data.rule_id;

      try {
        const telemetry = {
          sensor: {
            temperature: 60,
            humidity: 30,
          },
        };

        const response = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry });
        expect(response.status).toBe(200);
        expect(response.data.triggered).toBe(true);
      } finally {
        try {
          await api.delete(`${API_PREFIX}/${ruleId}`);
        } catch (e) {}
      }
    });

    test('多层嵌套字段访问', async () => {
      const condition = RuleEngineTestDataFactory.createCondition({
        field: 'device.sensor.reading.temperature',
        operator: '>',
        value: 50,
      });

      const ruleData = RuleEngineTestDataFactory.createRuleData({
        name: 'deep_nested_test',
        conditions: [condition],
      });

      const createResponse = await api.post(API_PREFIX, ruleData);
      const ruleId = createResponse.data.rule_id;

      try {
        const telemetry = {
          device: {
            sensor: {
              reading: {
                temperature: 60,
                timestamp: Date.now(),
              },
            },
          },
        };

        const response = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry });
        expect(response.status).toBe(200);
        expect(response.data.triggered).toBe(true);
      } finally {
        try {
          await api.delete(`${API_PREFIX}/${ruleId}`);
        } catch (e) {}
      }
    });

    test('不存在的嵌套字段应返回false', async () => {
      const condition = RuleEngineTestDataFactory.createCondition({
        field: 'nonexistent.field',
        operator: '>',
        value: 50,
      });

      const ruleData = RuleEngineTestDataFactory.createRuleData({
        name: 'missing_nested_test',
        conditions: [condition],
      });

      const createResponse = await api.post(API_PREFIX, ruleData);
      const ruleId = createResponse.data.rule_id;

      try {
        const telemetry = { temperature: 60 };
        const response = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, { telemetry_data: telemetry });
        
        expect(response.status).toBe(200);
        expect(response.data.triggered).toBe(false);
      } finally {
        try {
          await api.delete(`${API_PREFIX}/${ruleId}`);
        } catch (e) {}
      }
    });
  });

  describe('类型比较测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('字符串比较测试', async () => {
      const condition = RuleEngineTestDataFactory.createCondition({
        field: 'status',
        operator: '==',
        value: 'ERROR',
      });

      const ruleData = RuleEngineTestDataFactory.createRuleData({
        name: 'string_compare_test',
        conditions: [condition],
      });

      const createResponse = await api.post(API_PREFIX, ruleData);
      const ruleId = createResponse.data.rule_id;

      try {
        const response1 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, {
          telemetry_data: { status: 'ERROR' },
        });
        expect(response1.data.triggered).toBe(true);

        const response2 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, {
          telemetry_data: { status: 'OK' },
        });
        expect(response2.data.triggered).toBe(false);
      } finally {
        try {
          await api.delete(`${API_PREFIX}/${ruleId}`);
        } catch (e) {}
      }
    });

    test('布尔值比较测试', async () => {
      const condition = RuleEngineTestDataFactory.createCondition({
        field: 'alarm_active',
        operator: '==',
        value: true,
      });

      const ruleData = RuleEngineTestDataFactory.createRuleData({
        name: 'boolean_compare_test',
        conditions: [condition],
      });

      const createResponse = await api.post(API_PREFIX, ruleData);
      const ruleId = createResponse.data.rule_id;

      try {
        const response1 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, {
          telemetry_data: { alarm_active: true },
        });
        expect(response1.data.triggered).toBe(true);

        const response2 = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, {
          telemetry_data: { alarm_active: false },
        });
        expect(response2.data.triggered).toBe(false);
      } finally {
        try {
          await api.delete(`${API_PREFIX}/${ruleId}`);
        } catch (e) {}
      }
    });

    test('类型不匹配比较测试', async () => {
      const condition = RuleEngineTestDataFactory.createCondition({
        field: 'value',
        operator: '>',
        value: 50,
      });

      const ruleData = RuleEngineTestDataFactory.createRuleData({
        name: 'type_mismatch_test',
        conditions: [condition],
      });

      const createResponse = await api.post(API_PREFIX, ruleData);
      const ruleId = createResponse.data.rule_id;

      try {
        const response = await api.post(`${API_PREFIX}/${ruleId}/evaluate`, {
          telemetry_data: { value: 'not_a_number' },
        });
        
        expect(response.status).toBe(200);
        expect(response.data.triggered).toBe(false);
      } finally {
        try {
          await api.delete(`${API_PREFIX}/${ruleId}`);
        } catch (e) {}
      }
    });
  });

  describe('规则列表查询测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('查询规则列表 - 无过滤条件', async () => {
      const response = await api.get(API_PREFIX);
      expect(response.status).toBe(200);
      expect(response.data).toHaveProperty('rules');
      expect(Array.isArray(response.data.rules)).toBe(true);
    });

    test('查询规则列表 - 按enabled过滤', async () => {
      const response1 = await api.get(`${API_PREFIX}?enabled=true`);
      expect(response1.status).toBe(200);
      
      const response2 = await api.get(`${API_PREFIX}?enabled=false`);
      expect(response2.status).toBe(200);
    });

    test('查询规则列表 - 分页参数', async () => {
      const response = await api.get(`${API_PREFIX}?limit=10&offset=0`);
      expect(response.status).toBe(200);
      expect(response.data.rules.length).toBeLessThanOrEqual(10);
    });

    test('查询规则列表 - 无效过滤参数', async () => {
      try {
        const response = await api.get(`${API_PREFIX}?invalid_param=test`);
        expect([200, 400]).toContain(response.status);
      } catch (error) {
        expect([400, 422]).toContain(error.response.status);
      }
    });
  });
});

describe('边缘规则引擎模块 - 单元测试（无需API）', () => {
  describe('数据构造验证', () => {
    test('规则数据包含所有必填字段', () => {
      const ruleData = RuleEngineTestDataFactory.createRuleData();
      
      expect(ruleData).toHaveProperty('name');
      expect(ruleData).toHaveProperty('description');
      expect(ruleData).toHaveProperty('enabled');
      expect(ruleData).toHaveProperty('conditions');
      expect(ruleData).toHaveProperty('actions');
      expect(ruleData).toHaveProperty('priority');
      expect(ruleData).toHaveProperty('trigger_limit');
      expect(ruleData).toHaveProperty('cooldown_seconds');
    });

    test('条件数据包含所有必填字段', () => {
      const condition = RuleEngineTestDataFactory.createCondition();
      
      expect(condition).toHaveProperty('field');
      expect(condition).toHaveProperty('operator');
      expect(condition).toHaveProperty('value');
      expect(condition.type).toBe('comparison');
    });

    test('复合条件包含所有必填字段', () => {
      const cond1 = RuleEngineTestDataFactory.createCondition();
      const cond2 = RuleEngineTestDataFactory.createCondition();
      const compound = RuleEngineTestDataFactory.createCompoundCondition('AND', [cond1, cond2]);
      
      expect(compound).toHaveProperty('type', 'compound');
      expect(compound).toHaveProperty('operator', 'AND');
      expect(compound).toHaveProperty('conditions');
      expect(compound.conditions.length).toBe(2);
    });

    test('动作数据包含所有必填字段', () => {
      const action = RuleEngineTestDataFactory.createAction();
      
      expect(action).toHaveProperty('type');
      expect(action).toHaveProperty('parameters');
    });

    test('无效规则场景覆盖全面', () => {
      const scenarios = [
        'empty_name', 'null_name', 'missing_name',
        'too_long_name',
        'null_description',
        'missing_enabled', 'null_enabled',
        'missing_conditions', 'empty_conditions', 'null_conditions',
        'missing_actions', 'empty_actions', 'null_actions',
        'missing_condition_field', 'empty_condition_field', 'null_condition_field',
        'missing_condition_operator', 'empty_condition_operator', 'null_condition_operator',
        'invalid_condition_operator',
        'missing_action_type', 'empty_action_type', 'null_action_type',
        'invalid_action_type'
      ];
      
      scenarios.forEach((scenario) => {
        const data = RuleEngineTestDataFactory.createInvalidRuleData(scenario);
        expect(data).toBeDefined();
      });
    });

    test('评估场景数量正确', () => {
      const scenarios = RuleEngineTestDataFactory.createTelemetryEvaluationScenarios();
      expect(scenarios.length).toBe(22);
    });

    test('动作验证场景数量正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleActionValidationScenarios();
      expect(scenarios.length).toBe(12);
    });

    test('生命周期场景数量正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleLifecycleScenarios();
      expect(scenarios.length).toBe(7);
    });
  });

  describe('比较运算符验证', () => {
    test('大于运算符构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createTelemetryEvaluationScenarios();
      const gt = scenarios.find((s) => s.name === '大于比较 - 数值');
      
      expect(gt).toBeDefined();
      expect(gt.data.operator).toBe('>');
      expect(gt.data.conditionValue).toBe(50);
      expect(gt.expected.triggered).toBe(true);
    });

    test('小于运算符构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createTelemetryEvaluationScenarios();
      const lt = scenarios.find((s) => s.name === '小于比较 - 数值');
      
      expect(lt).toBeDefined();
      expect(lt.data.operator).toBe('<');
      expect(lt.data.conditionValue).toBe(50);
      expect(lt.expected.triggered).toBe(true);
    });

    test('等于运算符构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createTelemetryEvaluationScenarios();
      const eq = scenarios.find((s) => s.name === '等于比较 - 数值');
      
      expect(eq).toBeDefined();
      expect(eq.data.operator).toBe('==');
      expect(eq.data.conditionValue).toBe(50);
      expect(eq.expected.triggered).toBe(true);
    });

    test('不等于运算符构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createTelemetryEvaluationScenarios();
      const neq = scenarios.find((s) => s.name === '不等于比较 - 数值');
      
      expect(neq).toBeDefined();
      expect(neq.data.operator).toBe('!=');
      expect(neq.data.conditionValue).toBe(50);
      expect(neq.expected.triggered).toBe(true);
    });

    test('大于等于运算符构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createTelemetryEvaluationScenarios();
      const gte = scenarios.find((s) => s.name === '大于等于比较');
      
      expect(gte).toBeDefined();
      expect(gte.data.operator).toBe('>=');
      expect(gte.data.conditionValue).toBe(50);
      expect(gte.expected.triggered).toBe(true);
    });

    test('小于等于运算符构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createTelemetryEvaluationScenarios();
      const lte = scenarios.find((s) => s.name === '小于等于比较');
      
      expect(lte).toBeDefined();
      expect(lte.data.operator).toBe('<=');
      expect(lte.data.conditionValue).toBe(50);
      expect(lte.expected.triggered).toBe(true);
    });

    test('包含运算符构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createTelemetryEvaluationScenarios();
      const contains = scenarios.find((s) => s.name === '包含字符串');
      
      expect(contains).toBeDefined();
      expect(contains.data.operator).toBe('contains');
      expect(contains.data.conditionValue).toBe('ERROR');
      expect(contains.expected.triggered).toBe(true);
    });
  });

  describe('动作类型验证', () => {
    test('alert动作验证正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleActionValidationScenarios();
      const alertValid = scenarios.find((s) => s.name === 'alert动作 - 完整参数');
      const alertInvalid = scenarios.find((s) => s.name === 'alert动作 - 缺少message');
      
      expect(alertValid).toBeDefined();
      expect(alertValid.data.action.type).toBe('alert');
      expect(alertValid.expected.valid).toBe(true);
      
      expect(alertInvalid).toBeDefined();
      expect(alertInvalid.data.action.parameters).not.toHaveProperty('message');
      expect(alertInvalid.expected.valid).toBe(false);
    });

    test('command动作验证正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleActionValidationScenarios();
      const cmdValid = scenarios.find((s) => s.name === 'command动作 - 完整参数');
      const cmdInvalid = scenarios.find((s) => s.name === 'command动作 - 缺少command');
      
      expect(cmdValid).toBeDefined();
      expect(cmdValid.data.action.type).toBe('command');
      expect(cmdValid.expected.valid).toBe(true);
      
      expect(cmdInvalid).toBeDefined();
      expect(cmdInvalid.data.action.parameters).not.toHaveProperty('command');
      expect(cmdInvalid.expected.valid).toBe(false);
    });

    test('notification动作验证正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleActionValidationScenarios();
      const notifValid = scenarios.find((s) => s.name === 'notification动作 - 完整参数');
      const notifInvalid = scenarios.find((s) => s.name === 'notification动作 - 缺少recipient');
      
      expect(notifValid).toBeDefined();
      expect(notifValid.data.action.type).toBe('notification');
      expect(notifValid.expected.valid).toBe(true);
      
      expect(notifInvalid).toBeDefined();
      expect(notifInvalid.data.action.parameters).not.toHaveProperty('recipient');
      expect(notifInvalid.expected.valid).toBe(false);
    });

    test('webhook动作验证正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleActionValidationScenarios();
      const webhookValid = scenarios.find((s) => s.name === 'webhook动作 - 完整参数');
      const webhookInvalid = scenarios.find((s) => s.name === 'webhook动作 - 缺少url');
      
      expect(webhookValid).toBeDefined();
      expect(webhookValid.data.action.type).toBe('webhook');
      expect(webhookValid.expected.valid).toBe(true);
      
      expect(webhookInvalid).toBeDefined();
      expect(webhookInvalid.data.action.parameters).not.toHaveProperty('url');
      expect(webhookInvalid.expected.valid).toBe(false);
    });

    test('set_device_shadow动作验证正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleActionValidationScenarios();
      const shadowValid = scenarios.find((s) => s.name === 'set_device_shadow动作 - 完整参数');
      const shadowInvalid = scenarios.find((s) => s.name === 'set_device_shadow动作 - 缺少desired_state');
      
      expect(shadowValid).toBeDefined();
      expect(shadowValid.data.action.type).toBe('set_device_shadow');
      expect(shadowValid.expected.valid).toBe(true);
      
      expect(shadowInvalid).toBeDefined();
      expect(shadowInvalid.data.action.parameters).not.toHaveProperty('desired_state');
      expect(shadowInvalid.expected.valid).toBe(false);
    });
  });

  describe('生命周期场景验证', () => {
    test('创建规则场景构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleLifecycleScenarios();
      const create = scenarios.find((s) => s.name === '创建新规则');
      
      expect(create).toBeDefined();
      expect(create.data.enabled).toBe(true);
      expect(create.expected.statusCode).toBe(200);
    });

    test('启用规则场景构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleLifecycleScenarios();
      const enable = scenarios.find((s) => s.name === '启用已存在的规则');
      
      expect(enable).toBeDefined();
      expect(enable.data.enabled).toBe(true);
      expect(enable.expected.statusCode).toBe(200);
    });

    test('禁用规则场景构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleLifecycleScenarios();
      const disable = scenarios.find((s) => s.name === '禁用已存在的规则');
      
      expect(disable).toBeDefined();
      expect(disable.data.enabled).toBe(false);
      expect(disable.expected.statusCode).toBe(200);
    });

    test('删除规则场景构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleLifecycleScenarios();
      const del = scenarios.find((s) => s.name === '删除已存在的规则');
      
      expect(del).toBeDefined();
      expect(del.expected.statusCode).toBe(200);
    });

    test('触发频率限制场景构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleLifecycleScenarios();
      const rateLimit = scenarios.find((s) => s.name === '触发频率限制测试');
      
      expect(rateLimit).toBeDefined();
      expect(rateLimit.data.triggerLimit).toBe(5);
      expect(rateLimit.data.cooldownSeconds).toBe(60);
    });

    test('冷却时间场景构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleLifecycleScenarios();
      const cooldown = scenarios.find((s) => s.name === '冷却时间测试');
      
      expect(cooldown).toBeDefined();
      expect(cooldown.data.cooldownSeconds).toBe(10);
    });

    test('查询不存在规则场景构造正确', () => {
      const scenarios = RuleEngineTestDataFactory.createRuleLifecycleScenarios();
      const notFound = scenarios.find((s) => s.name === '查询不存在的规则');
      
      expect(notFound).toBeDefined();
      expect(notFound.expected.statusCode).toBe(404);
    });
  });

  describe('数据覆盖功能验证', () => {
    test('规则数据支持自定义覆盖', () => {
      const customRule = RuleEngineTestDataFactory.createRuleData({
        name: '自定义规则',
        description: '自定义描述',
        enabled: false,
        priority: 10,
        trigger_limit: 100,
        cooldown_seconds: 300,
      });
      
      expect(customRule.name).toBe('自定义规则');
      expect(customRule.description).toBe('自定义描述');
      expect(customRule.enabled).toBe(false);
      expect(customRule.priority).toBe(10);
      expect(customRule.trigger_limit).toBe(100);
      expect(customRule.cooldown_seconds).toBe(300);
    });

    test('条件支持自定义覆盖', () => {
      const customCondition = RuleEngineTestDataFactory.createCondition({
        field: 'custom_field',
        operator: 'contains',
        value: 'custom_value',
      });
      
      expect(customCondition.field).toBe('custom_field');
      expect(customCondition.operator).toBe('contains');
      expect(customCondition.value).toBe('custom_value');
    });

    test('动作支持自定义覆盖', () => {
      const customAction = RuleEngineTestDataFactory.createAction({
        type: 'custom_action',
        parameters: {
          custom_param: 'custom_value',
        },
      });
      
      expect(customAction.type).toBe('custom_action');
      expect(customAction.parameters.custom_param).toBe('custom_value');
    });
  });

  describe('复合条件深度验证', () => {
    test('AND复合条件构造正确', () => {
      const cond1 = RuleEngineTestDataFactory.createCondition({ field: 'a', operator: '>', value: 1 });
      const cond2 = RuleEngineTestDataFactory.createCondition({ field: 'b', operator: '<', value: 10 });
      const compound = RuleEngineTestDataFactory.createCompoundCondition('AND', [cond1, cond2]);
      
      expect(compound.type).toBe('compound');
      expect(compound.operator).toBe('AND');
      expect(compound.conditions.length).toBe(2);
      expect(compound.conditions[0].field).toBe('a');
      expect(compound.conditions[1].field).toBe('b');
    });

    test('OR复合条件构造正确', () => {
      const cond1 = RuleEngineTestDataFactory.createCondition({ field: 'a', operator: '>', value: 1 });
      const cond2 = RuleEngineTestDataFactory.createCondition({ field: 'b', operator: '<', value: 10 });
      const compound = RuleEngineTestDataFactory.createCompoundCondition('OR', [cond1, cond2]);
      
      expect(compound.type).toBe('compound');
      expect(compound.operator).toBe('OR');
      expect(compound.conditions.length).toBe(2);
    });

    test('嵌套复合条件构造正确', () => {
      const cond1 = RuleEngineTestDataFactory.createCondition({ field: 'a', operator: '>', value: 1 });
      const cond2 = RuleEngineTestDataFactory.createCondition({ field: 'b', operator: '<', value: 10 });
      const innerOr = RuleEngineTestDataFactory.createCompoundCondition('OR', [cond1, cond2]);
      
      const cond3 = RuleEngineTestDataFactory.createCondition({ field: 'c', operator: '==', value: true });
      const outerAnd = RuleEngineTestDataFactory.createCompoundCondition('AND', [innerOr, cond3]);
      
      expect(outerAnd.operator).toBe('AND');
      expect(outerAnd.conditions[0].operator).toBe('OR');
      expect(outerAnd.conditions[1].field).toBe('c');
    });
  });
});
