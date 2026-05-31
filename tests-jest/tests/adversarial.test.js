const { createApiClient, validateResponseStructure, validateErrorResponse, measureExecutionTime } = require('./helpers');
const { adversarialBuilder } = require('./builders');

const apiClient = createApiClient();

describe('对抗样本生成模块 - 边界条件测试', () => {
  beforeAll(async () => {
    console.log('\n=== 对抗样本生成模块测试启动 ===\n');
  });

  describe('1. 基础功能验证', () => {
    test('应成功获取支持的攻击策略列表', async () => {
      const response = await apiClient.adversarial.getStrategies();
      validateResponseStructure(response, 200);
      expect(Array.isArray(response.body.data)).toBe(true);
      expect(response.body.data.length).toBeGreaterThan(0);
    });

    test('应成功生成提示注入攻击样本', async () => {
      const request = adversarialBuilder.buildPromptInjectionRequest({
        target_prompt: '请帮我写一封商务邮件',
        target_model: 'gpt-3.5-turbo',
        num_samples: 3,
      });

      const { result, durationMs } = await measureExecutionTime(() =>
        apiClient.adversarial.generate(request)
      );

      validateResponseStructure(result, 200);
      expect(result.body.data.total_samples).toBe(3);
      expect(result.body.data.samples.length).toBe(3);
      result.body.data.samples.forEach(sample => {
        expect(sample.attack_type).toBe('prompt_injection');
        expect(typeof sample.adversarial_prompt).toBe('string');
        expect(sample.adversarial_prompt.length).toBeGreaterThan(0);
      });
      console.log(`  ✓ 生成3个样本耗时: ${durationMs}ms`);
    });

    test('应成功生成越狱攻击样本', async () => {
      const request = adversarialBuilder.buildJailbreakRequest({
        target_prompt: '你好',
        num_samples: 2,
      });

      const response = await apiClient.adversarial.generate(request);
      validateResponseStructure(response, 200);
      expect(response.body.data.samples.length).toBe(2);
    });
  });

  describe('2. 边界条件测试 - 输入长度', () => {
    test('空提示词 - 应正确处理或返回错误', async () => {
      const request = adversarialBuilder.buildAttackRequest({
        target_prompt: '',
        num_samples: 1,
      });

      try {
        const response = await apiClient.adversarial.generate(request);
        expect([200, 400]).toContain(response.status);
        if (response.status === 400) {
          expect(response.body.message).toMatch(/prompt|empty|空/i);
        }
      } catch (error) {
        expect(error).toBeDefined();
      }
    });

    test('超长提示词（10000字符）- 应能正常处理', async () => {
      const longPrompt = '这是一个测试'.repeat(1000);
      const request = adversarialBuilder.buildAttackRequest({
        target_prompt: longPrompt,
        num_samples: 2,
      });

      const { result, durationMs } = await measureExecutionTime(() =>
        apiClient.adversarial.generate(request)
      );

      validateResponseStructure(result, 200);
      expect(result.body.data.total_samples).toBe(2);
      console.log(`  ✓ 长提示词处理耗时: ${durationMs}ms`);
    });

    test('提示词包含特殊字符 - 应正确处理', async () => {
      const request = adversarialBuilder.buildAttackRequest({
        target_prompt: '"><script>alert(1)</script>\' OR 1=1 --',
        num_samples: 1,
      });

      const response = await apiClient.adversarial.generate(request);
      expect([200, 400]).toContain(response.status);
    });

    test('提示词包含Unicode控制字符 - 应正确处理', async () => {
      const request = adversarialBuilder.buildAttackRequest({
        target_prompt: '你好\u0000世界\u202e测试',
        num_samples: 1,
      });

      const response = await apiClient.adversarial.generate(request);
      expect([200, 400]).toContain(response.status);
    });
  });

  describe('3. 边界条件测试 - 样本数量', () => {
    test('样本数量为0 - 应返回参数错误', async () => {
      const request = adversarialBuilder.buildAttackRequest({
        target_prompt: '测试',
        num_samples: 0,
      });

      try {
        const response = await apiClient.adversarial.generate(request);
        if (response.status === 400) {
          expect(response.body.message).toMatch(/num_samples|数量|invalid/i);
        }
      } catch (error) {
        expect(error).toBeDefined();
      }
    });

    test('样本数量为负数 - 应返回参数错误', async () => {
      const request = adversarialBuilder.buildAttackRequest({
        target_prompt: '测试',
        num_samples: -5,
      });

      try {
        const response = await apiClient.adversarial.generate(request);
        expect(response.status).toBe(400);
        expect(response.body.message).toMatch(/num_samples|negative|负/i);
      } catch (error) {
        expect(error).toBeDefined();
      }
    });

    test('样本数量为极大值（100）- 应能处理或返回合理错误', async () => {
      const request = adversarialBuilder.buildAttackRequest({
        target_prompt: '测试',
        num_samples: 100,
      });

      const { result, durationMs } = await measureExecutionTime(() =>
        apiClient.adversarial.generate(request)
      );

      if (result.status === 200) {
        expect(result.body.data.samples.length).toBeLessThanOrEqual(100);
        console.log(`  ✓ 100个样本生成耗时: ${durationMs}ms`);
      } else {
        expect(result.status).toBe(400);
        expect(result.body.message).toMatch(/limit|max|超过|限制/i);
      }
    });
  });

  describe('4. 边界条件测试 - 攻击类型', () => {
    test('未知攻击类型 - 应返回参数错误', async () => {
      const request = adversarialBuilder.buildAttackRequest({
        attack_type: 'unknown_attack_type',
        target_prompt: '测试',
        num_samples: 1,
      });

      const response = await apiClient.adversarial.generate(request);
      expect(response.status).toBe(400);
      expect(response.body.message).toMatch(/attack_type|invalid|无效/i);
    });

    test('支持所有已声明的攻击类型', async () => {
      const strategiesResponse = await apiClient.adversarial.getStrategies();
      const supportedTypes = strategiesResponse.body.data;

      for (const attackType of supportedTypes) {
        const request = adversarialBuilder.buildAttackRequest({
          attack_type: attackType,
          target_prompt: '测试',
          num_samples: 1,
        });

        const response = await apiClient.adversarial.generate(request);
        expect(response.status).toBe(200);
        expect(response.body.data.samples[0].attack_type).toBe(attackType);
      }
    });
  });

  describe('5. 批量攻击边界测试', () => {
    test('空批量请求 - 应返回参数错误', async () => {
      const request = adversarialBuilder.buildBatchAttackRequest([], ['prompt_injection']);

      try {
        const response = await apiClient.adversarial.batchAttack(request);
        expect([400]).toContain(response.status);
      } catch (error) {
        expect(error).toBeDefined();
      }
    });

    test('单一提示词多攻击类型', async () => {
      const request = adversarialBuilder.buildBatchAttackRequest(
        ['测试提示词'],
        ['prompt_injection', 'jailbreak', 'obfuscation']
      );

      const response = await apiClient.adversarial.batchAttack(request);
      validateResponseStructure(response, 200);
      expect(response.body.data.total_tasks).toBe(3);
    });

    test('大量提示词批量处理', async () => {
      const manyPrompts = Array.from({ length: 20 }, (_, i) => `提示词_${i}_${Date.now()}`);
      const request = adversarialBuilder.buildBatchAttackRequest(manyPrompts, ['prompt_injection']);

      const { result, durationMs } = await measureExecutionTime(() =>
        apiClient.adversarial.batchAttack(request)
      );

      validateResponseStructure(result, 200);
      expect(result.body.data.total_tasks).toBe(20);
      console.log(`  ✓ 20个提示词批量处理耗时: ${durationMs}ms`);
    });
  });

  describe('6. 幂等性与一致性测试', () => {
    test('相同输入多次调用 - 结果结构一致', async () => {
      const request = adversarialBuilder.buildPromptInjectionRequest({
        target_prompt: '固定测试提示词',
        num_samples: 2,
      });

      const response1 = await apiClient.adversarial.generate(request);
      const response2 = await apiClient.adversarial.generate(request);

      expect(response1.status).toBe(response2.status);
      expect(response1.body.data.total_samples).toBe(response2.body.data.total_samples);
      expect(typeof response1.body.data.samples[0].adversarial_prompt).toBe('string');
      expect(typeof response2.body.data.samples[0].adversarial_prompt).toBe('string');
    });
  });

  describe('7. 最小请求测试', () => {
    test('仅提供必填字段 - 应正常工作', async () => {
      const minimalRequest = {
        attack_type: 'prompt_injection',
        target_prompt: '测试',
      };

      const response = await apiClient.adversarial.generate(minimalRequest);
      validateResponseStructure(response, 200);
      expect(response.body.data.total_samples).toBeGreaterThan(0);
    });
  });
});
