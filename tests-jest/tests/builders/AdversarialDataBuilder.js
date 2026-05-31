const { TestDataBuilder, baseBuilder } = require('./TestDataBuilder');

class AdversarialDataBuilder extends TestDataBuilder {
  constructor() {
    super();
    this.attackTypes = [
      'prompt_injection',
      'jailbreak',
      'role_playing',
      'obfuscation',
      'few_shot_adversarial',
    ];
    this.targetModels = [
      'gpt-3.5-turbo',
      'gpt-4',
      'claude-3-opus',
      'llama-2-70b',
      'qwen-72b',
    ];
    this.victimTasks = [
      'text_generation',
      'translation',
      'summarization',
      'classification',
      'qa',
    ];
  }

  buildAttackRequest(overrides = {}) {
    return {
      attack_type: this.randomFromArray(this.attackTypes),
      target_prompt: `请帮我${this.randomString(20)}`,
      target_model: this.randomFromArray(this.targetModels),
      num_samples: this.randomInt(1, 10),
      severity: this.randomFromArray(['low', 'medium', 'high', 'critical']),
      victim_task: this.randomFromArray(this.victimTasks),
      custom_constraints: [],
      ...overrides,
    };
  }

  buildValidAttackRequest(attackType = null, overrides = {}) {
    return this.buildAttackRequest({
      attack_type: attackType || 'prompt_injection',
      target_prompt: '请帮我写一封商务邮件',
      target_model: 'gpt-3.5-turbo',
      num_samples: 3,
      ...overrides,
    });
  }

  buildPromptInjectionRequest(overrides = {}) {
    return this.buildValidAttackRequest('prompt_injection', overrides);
  }

  buildJailbreakRequest(overrides = {}) {
    return this.buildValidAttackRequest('jailbreak', overrides);
  }

  buildBatchAttackRequest(prompts = null, attackTypes = null, overrides = {}) {
    return {
      prompts: prompts || [
        `请帮我${this.randomString(15)}`,
        `解释${this.randomString(10)}的概念`,
        `总结以下内容：${this.randomString(50)}`,
      ],
      attack_types: attackTypes || ['prompt_injection', 'jailbreak'],
      target_model: this.randomFromArray(this.targetModels),
      ...overrides,
    };
  }

  buildEvaluationRequest(sampleIds = null, overrides = {}) {
    return {
      sample_ids: sampleIds || [
        this.generateId('sample'),
        this.generateId('sample'),
      ],
      evaluation_metrics: ['success_rate', 'stealthiness', 'transferability'],
      baseline_model: this.randomFromArray(this.targetModels),
      ...overrides,
    };
  }

  buildBoundaryRequest() {
    return {
      emptyPrompt: this.buildAttackRequest({ target_prompt: '' }),
      veryLongPrompt: this.buildAttackRequest({ target_prompt: this.randomString(10000) }),
      maxSamples: this.buildAttackRequest({ num_samples: 100 }),
      zeroSamples: this.buildAttackRequest({ num_samples: 0 }),
      negativeSamples: this.buildAttackRequest({ num_samples: -1 }),
      unknownAttackType: this.buildAttackRequest({ attack_type: 'unknown_attack' }),
      specialCharsPrompt: this.buildAttackRequest({
        target_prompt: '"><script>alert(1)</script>\' OR 1=1 --',
      }),
      unicodePrompt: this.buildAttackRequest({
        target_prompt: '你好\u0000世界\u202e测试',
      }),
      minimalRequest: {
        attack_type: 'prompt_injection',
        target_prompt: 'test',
      },
    };
  }

  buildExpectedAttackResponse(sampleCount = 3) {
    return {
      code: 200,
      message: expect.any(String),
      data: {
        batch_id: expect.any(String),
        total_samples: sampleCount,
        samples: expect.arrayContaining([
          expect.objectContaining({
            sample_id: expect.any(String),
            attack_type: expect.any(String),
            adversarial_prompt: expect.any(String),
          }),
        ]),
        generation_duration_ms: expect.any(Number),
      },
    };
  }

  buildExpectedErrorResponse(expectedCode, expectedMessagePattern) {
    return {
      code: expectedCode,
      message: expect.stringMatching(expectedMessagePattern),
      data: null,
    };
  }
}

const adversarialBuilder = new AdversarialDataBuilder();
module.exports = { AdversarialDataBuilder, adversarialBuilder };
