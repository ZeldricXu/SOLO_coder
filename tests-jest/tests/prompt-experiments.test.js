const { createApiClient, validateResponseStructure, measureExecutionTime, deepClone } = require('./helpers');
const { promptExperimentBuilder } = require('./builders');

const apiClient = createApiClient();

describe('Prompt实验管理模块 - 参数校验完备性测试', () => {
  let createdPromptIds = [];
  let createdExperimentId = null;

  beforeAll(async () => {
    console.log('\n=== Prompt实验管理模块测试启动 ===\n');
  });

  afterAll(() => {
    createdPromptIds = [];
    createdExperimentId = null;
  });

  describe('1. 基础功能验证', () => {
    test('应成功创建Prompt', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest();

      const { result, durationMs } = await measureExecutionTime(() =>
        apiClient.promptExperiments.createPrompt(request)
      );

      validateResponseStructure(result, 201);
      expect(result.body.data.name).toBe(request.name);
      expect(result.body.data.content).toBe(request.content);
      expect(result.body.data.variables).toEqual(expect.arrayContaining(request.variables));

      createdPromptIds.push(result.body.data.prompt_id);
      console.log(`  ✓ Prompt创建耗时: ${durationMs}ms, ID: ${result.body.data.prompt_id}`);
    });

    test('应成功获取Prompt详情', async () => {
      const promptId = createdPromptIds[0];
      const response = await apiClient.promptExperiments.getPrompt(promptId);
      validateResponseStructure(response, 200);
      expect(response.body.data.prompt_id).toBe(promptId);
    });

    test('应成功列出Prompts', async () => {
      const response = await apiClient.promptExperiments.listPrompts({ limit: 10 });
      validateResponseStructure(response, 200);
      expect(Array.isArray(response.body.data)).toBe(true);
      expect(response.body.data.length).toBeGreaterThan(0);
    });

    test('应成功创建AB实验', async () => {
      const prompt1 = await apiClient.promptExperiments.createPrompt(
        promptExperimentBuilder.buildValidPromptCreateRequest({
          name: 'ab_test_variant_a',
          content: 'Variant A: {{user_input}}',
        })
      );
      const prompt2 = await apiClient.promptExperiments.createPrompt(
        promptExperimentBuilder.buildValidPromptCreateRequest({
          name: 'ab_test_variant_b',
          content: 'Variant B: {{user_input}}',
        })
      );

      createdPromptIds.push(prompt1.body.data.prompt_id, prompt2.body.data.prompt_id);

      const experimentRequest = promptExperimentBuilder.buildValidExperimentCreateRequest([
        prompt1.body.data.prompt_id,
        prompt2.body.data.prompt_id,
      ]);

      const { result, durationMs } = await measureExecutionTime(() =>
        apiClient.promptExperiments.createExperiment(experimentRequest)
      );

      validateResponseStructure(result, 201);
      createdExperimentId = result.body.data.experiment_id;
      expect(result.body.data.variants.length).toBe(2);
      console.log(`  ✓ 实验创建耗时: ${durationMs}ms, ID: ${createdExperimentId}`);
    });
  });

  describe('2. Prompt创建 - 必填字段校验', () => {
    test('缺少name字段 - 应返回400错误', async () => {
      const request = deepClone(promptExperimentBuilder.buildValidPromptCreateRequest());
      delete request.name;

      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/name|required|必填/i);
    });

    test('缺少content字段 - 应返回400错误', async () => {
      const request = deepClone(promptExperimentBuilder.buildValidPromptCreateRequest());
      delete request.content;

      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/content|required|必填/i);
    });

    test('缺少variables字段 - 应返回400错误', async () => {
      const request = deepClone(promptExperimentBuilder.buildValidPromptCreateRequest());
      delete request.variables;

      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
    });
  });

  describe('3. Prompt创建 - 字段格式校验', () => {
    test('name为空字符串 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({ name: '' });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/name|empty|空/i);
    });

    test('name过长（256字符以上）- 应返回400错误', async () => {
      const longName = 'a'.repeat(256);
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({ name: longName });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/name|length|长度/i);
    });

    test('name包含特殊字符 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({
        name: 'invalid!@#$%^&*()',
      });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
    });

    test('content为空字符串 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({ content: '' });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/content|empty|空/i);
    });

    test('content包含未定义变量 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({
        content: 'Hello {{undefined_var}}',
        variables: ['user_input'],
      });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/undefined_var|variable|变量/i);
    });

    test('variables为空数组 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({
        content: 'Hello World',
        variables: [],
      });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
    });

    test('variables包含重复变量 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({
        content: '{{user_input}} {{user_input}}',
        variables: ['user_input', 'user_input'],
      });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/duplicate|重复|variable/i);
    });

    test('模板语法错误（缺少闭合括号）- 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({
        content: 'Hello {{name',
        variables: ['name'],
      });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/syntax|语法|template/i);
    });

    test('content包含XSS注入代码 - 应正确处理或过滤', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({
        content: '"><script>alert(1)</script>{{user_input}}',
        variables: ['user_input'],
      });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([201, 400]).toContain(response.status);
    });

    test('content包含Unicode控制字符 - 应正确处理', async () => {
      const request = promptExperimentBuilder.buildValidPromptCreateRequest({
        content: '你好\u0000世界\u202e{{user_input}}',
        variables: ['user_input'],
      });
      const response = await apiClient.promptExperiments.createPrompt(request);
      expect([201, 400]).toContain(response.status);
    });
  });

  describe('4. 实验创建 - 必填字段校验', () => {
    test('缺少name字段 - 应返回400错误', async () => {
      const request = deepClone(promptExperimentBuilder.buildExperimentCreateRequest());
      delete request.name;

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/name|required|必填/i);
    });

    test('缺少variants字段 - 应返回400错误', async () => {
      const request = deepClone(promptExperimentBuilder.buildExperimentCreateRequest());
      delete request.variants;

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/variants|required|必填/i);
    });

    test('variants为空数组 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        variants: [],
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/variants|empty|至少/i);
    });

    test('variants只有一个变体 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        variants: [promptExperimentBuilder.buildVariantConfig('A')],
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/variants|至少两个|two/i);
    });

    test('variants包含重复variant_id - 应返回400错误', async () => {
      const variant = promptExperimentBuilder.buildVariantConfig('A');
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        variants: [variant, variant],
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/duplicate|重复|variant_id/i);
    });
  });

  describe('5. 实验创建 - 流量分配校验', () => {
    test('traffic_weight为负数 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        variants: [
          promptExperimentBuilder.buildVariantConfig('A', { traffic_weight: -10 }),
          promptExperimentBuilder.buildVariantConfig('B', { traffic_weight: 100 }),
        ],
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/weight|negative|负/i);
    });

    test('traffic_weight超过100 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        variants: [
          promptExperimentBuilder.buildVariantConfig('A', { traffic_weight: 150 }),
          promptExperimentBuilder.buildVariantConfig('B', { traffic_weight: 50 }),
        ],
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/weight|100|超过/i);
    });

    test('traffic_weight总和不为100 - 应返回400错误或自动调整', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        variants: [
          promptExperimentBuilder.buildVariantConfig('A', { traffic_weight: 30 }),
          promptExperimentBuilder.buildVariantConfig('B', { traffic_weight: 40 }),
        ],
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([201, 400]).toContain(response.status);
      if (response.status === 201) {
        const totalWeight = response.body.data.variants.reduce((sum, v) => sum + v.traffic_weight, 0);
        expect(totalWeight).toBe(100);
        console.log('  ✓ 流量权重自动归一化验证通过');
      }
    });

    test('traffic_percentage为负数 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        traffic_percentage: -10,
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/traffic_percentage|negative|负/i);
    });

    test('traffic_percentage超过100 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        traffic_percentage: 150,
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/traffic_percentage|100|超过/i);
    });
  });

  describe('6. 实验创建 - 时间配置校验', () => {
    test('end_time早于start_time - 应返回400错误', async () => {
      const now = Date.now();
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        start_time: now,
        end_time: now - 86400000,
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/end_time|start_time|早于|before/i);
    });

    test('start_time为过去时间 - 应正确处理', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        start_time: Date.now() - 86400000,
        end_time: Date.now() + 86400000,
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([201, 400]).toContain(response.status);
    });
  });

  describe('7. 实验创建 - 统计参数校验', () => {
    test('confidence_level为0 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        confidence_level: 0,
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/confidence_level|invalid/i);
    });

    test('confidence_level超过1 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        confidence_level: 2,
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/confidence_level|1|超过/i);
    });

    test('target_sample_size为负数 - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        target_sample_size: -100,
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/target_sample_size|negative|负/i);
    });
  });

  describe('8. 路由请求 - 参数校验', () => {
    test('缺少experiment_id - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildRouteRequest();
      delete request.experiment_id;

      const response = await apiClient.promptExperiments.route(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/experiment_id|required|必填/i);
    });

    test('缺少user_id - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildRouteRequest();
      delete request.user_id;

      const response = await apiClient.promptExperiments.route(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/user_id|required|必填/i);
    });

    test('同一user_id多次路由 - 应返回相同variant', async () => {
      const userId = 'test_user_consistency_' + Date.now();
      const request = promptExperimentBuilder.buildRouteRequest({
        user_id: userId,
      });

      const response1 = await apiClient.promptExperiments.route(request);
      const response2 = await apiClient.promptExperiments.route(request);

      if (response1.status === 200 && response2.status === 200) {
        expect(response1.body.data.variant_id).toBe(response2.body.data.variant_id);
        console.log(`  ✓ 路由一致性验证通过: ${response1.body.data.variant_id}`);
      }
    });
  });

  describe('9. 指标记录 - 参数校验', () => {
    test('缺少metric_name - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildMetricRecordRequest();
      delete request.metric_name;

      const response = await apiClient.promptExperiments.recordMetric(request);
      expect([400, 422]).toContain(response.status);
      expect(response.body.message || response.body.error).toMatch(/metric_name|required|必填/i);
    });

    test('metric_value为NaN - 应返回400错误', async () => {
      const request = promptExperimentBuilder.buildMetricRecordRequest({
        metric_value: NaN,
      });

      try {
        const response = await apiClient.promptExperiments.recordMetric(request);
        expect([400, 422]).toContain(response.status);
      } catch (error) {
        expect(error).toBeDefined();
      }
    });

    test('metric_value为极大值 - 应正确处理', async () => {
      const request = promptExperimentBuilder.buildMetricRecordRequest({
        metric_value: Number.MAX_SAFE_INTEGER,
      });

      const response = await apiClient.promptExperiments.recordMetric(request);
      expect([200, 201, 400]).toContain(response.status);
    });

    test('metric_value为极小负值 - 应正确处理', async () => {
      const request = promptExperimentBuilder.buildMetricRecordRequest({
        metric_value: -Number.MAX_SAFE_INTEGER,
      });

      const response = await apiClient.promptExperiments.recordMetric(request);
      expect([200, 201, 400]).toContain(response.status);
    });
  });

  describe('10. 复杂场景校验', () => {
    test('创建包含50个变量的复杂Prompt - 应正确处理', async () => {
      const variables = Array.from({ length: 50 }, (_, i) => `var_${i}`);
      const content = variables.map(v => `{{${v}}}`).join(' ');

      const request = promptExperimentBuilder.buildValidPromptCreateRequest({
        name: 'complex_prompt_50_vars',
        content,
        variables,
      });

      const { result, durationMs } = await measureExecutionTime(() =>
        apiClient.promptExperiments.createPrompt(request)
      );

      if (result.status === 201) {
        createdPromptIds.push(result.body.data.prompt_id);
        console.log(`  ✓ 50个变量的Prompt创建耗时: ${durationMs}ms`);
      }
      expect([201, 400]).toContain(result.status);
    });

    test('创建包含20个变体的实验 - 应处理或返回合理错误', async () => {
      const variants = Array.from({ length: 20 }, (_, i) =>
        promptExperimentBuilder.buildVariantConfig(String(i), {
          traffic_weight: 5,
        })
      );

      const request = promptExperimentBuilder.buildExperimentCreateRequest({
        variants,
      });

      const response = await apiClient.promptExperiments.createExperiment(request);
      expect([201, 400]).toContain(response.status);
      if (response.status === 400) {
        expect(response.body.message).toMatch(/too many|variants|限制|太多/i);
      }
    });
  });
});
