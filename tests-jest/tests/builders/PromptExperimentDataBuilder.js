const { TestDataBuilder, baseBuilder } = require('./TestDataBuilder');

class PromptExperimentDataBuilder extends TestDataBuilder {
  constructor() {
    super();
    this.trafficSplitTypes = ['random', 'user_id_hash', 'session_id_hash', 'rule_based'];
    this.experimentStatuses = ['draft', 'running', 'paused', 'completed', 'archived'];
    this.promptVariables = ['user_input', 'context', 'history', 'language', 'tone'];
  }

  buildPromptCreateRequest(overrides = {}) {
    const promptId = this.generateId('prompt');
    const variables = this.randomFromArray([
      ['user_input'],
      ['user_input', 'context'],
      ['user_input', 'context', 'history'],
    ]);

    const templateParts = variables.map(v => `{{${v}}}`).join(', ');

    return {
      name: `prompt_${promptId}`,
      description: `测试提示词_${promptId}`,
      content: `你是一个友好的助手，请根据以下信息回答用户问题：${templateParts}`,
      variables,
      tags: [
        { key: 'category', value: this.randomFromArray(['chat', 'qa', 'summary']) },
        { key: 'author', value: 'test_user' },
      ],
      metadata: {
        version: '1.0.0',
        created_by: 'test',
      },
      ...overrides,
    };
  }

  buildValidPromptCreateRequest(overrides = {}) {
    return this.buildPromptCreateRequest({
      name: 'customer_service_bot',
      description: '客服机器人基础提示词',
      content: '你是一个专业的客服机器人，请用友好的语气回答用户问题：{{user_input}}',
      variables: ['user_input'],
      ...overrides,
    });
  }

  buildPromptWithComplexVariables() {
    return this.buildPromptCreateRequest({
      name: 'complex_prompt',
      content: `
        你是一个专业的分析师。
        用户问题：{{user_question}}
        历史对话：{{conversation_history}}
        上下文信息：{{context_documents}}
        用户偏好：{{user_preferences}}
        请综合以上信息给出回答。
      `,
      variables: ['user_question', 'conversation_history', 'context_documents', 'user_preferences'],
    });
  }

  buildVariantConfig(variantId = null, overrides = {}) {
    return {
      variant_id: variantId || this.generateId('var'),
      prompt_id: this.generateId('prompt'),
      traffic_weight: this.randomInt(10, 50),
      description: `变体_${this.randomString(5)}`,
      ...overrides,
    };
  }

  buildExperimentCreateRequest(overrides = {}) {
    const expId = this.generateId('exp');
    const variantA = this.buildVariantConfig('A', { traffic_weight: 50 });
    const variantB = this.buildVariantConfig('B', { traffic_weight: 50 });

    return {
      name: `experiment_${expId}`,
      description: `AB测试实验_${expId}`,
      variants: [variantA, variantB],
      traffic_split_type: this.randomFromArray(this.trafficSplitTypes),
      primary_metric: this.randomFromArray(['user_satisfaction', 'response_quality', 'task_completion']),
      secondary_metrics: ['latency', 'cost_per_request'],
      target_sample_size: this.randomInt(1000, 10000),
      confidence_level: this.randomFromArray([0.9, 0.95, 0.99]),
      traffic_percentage: this.randomInt(10, 100),
      start_time: Date.now(),
      end_time: Date.now() + 86400000 * this.randomInt(7, 30),
      tags: [
        { key: 'team', value: 'ml' },
        { key: 'priority', value: 'high' },
      ],
      ...overrides,
    };
  }

  buildValidExperimentCreateRequest(promptIds, overrides = {}) {
    const variants = promptIds.map((pid, idx) => this.buildVariantConfig(
      String.fromCharCode(65 + idx),
      { prompt_id: pid, traffic_weight: Math.floor(100 / promptIds.length) }
    ));

    return this.buildExperimentCreateRequest({
      name: 'prompt_ab_test_customer_service',
      description: '客服机器人提示词AB测试实验',
      variants,
      traffic_split_type: 'user_id_hash',
      primary_metric: 'user_satisfaction',
      traffic_percentage: 20,
      ...overrides,
    });
  }

  buildRouteRequest(overrides = {}) {
    return {
      experiment_id: this.generateId('exp'),
      user_id: this.generateId('user'),
      session_id: this.generateId('session'),
      context: {
        device: this.randomFromArray(['ios', 'android', 'web']),
        locale: this.randomFromArray(['zh-CN', 'en-US', 'ja-JP']),
        ...overrides.context,
      },
      ...overrides,
    };
  }

  buildMetricRecordRequest(overrides = {}) {
    return {
      experiment_id: this.generateId('exp'),
      variant_id: this.randomFromArray(['A', 'B']),
      metric_name: this.randomFromArray(['user_satisfaction', 'response_quality', 'latency']),
      metric_value: this.randomFloat(0, 5),
      user_id: this.generateId('user'),
      session_id: this.generateId('session'),
      timestamp: Date.now(),
      metadata: {
        request_id: this.generateId('req'),
        model: 'gpt-3.5-turbo',
      },
      ...overrides,
    };
  }

  buildBoundaryPromptData() {
    return {
      emptyName: this.buildPromptCreateRequest({ name: '' }),
      veryLongName: this.buildPromptCreateRequest({ name: this.randomString(300) }),
      emptyContent: this.buildPromptCreateRequest({ content: '' }),
      veryLongContent: this.buildPromptCreateRequest({ content: this.randomString(50000) }),
      emptyVariables: this.buildPromptCreateRequest({ variables: [] }),
      tooManyVariables: this.buildPromptCreateRequest({
        variables: Array.from({ length: 50 }, (_, i) => `var_${i}`),
        content: Array.from({ length: 50 }, (_, i) => `{{var_${i}}}`).join(' '),
      }),
      undefinedVariable: this.buildPromptCreateRequest({
        content: 'Hello {{undefined_var}}',
        variables: ['other_var'],
      }),
      mismatchedVariables: this.buildPromptCreateRequest({
        content: 'Hello {{name}}',
        variables: ['age'],
      }),
      invalidTemplateSyntax: this.buildPromptCreateRequest({
        content: 'Hello {{name',
        variables: ['name'],
      }),
      specialCharsInContent: this.buildPromptCreateRequest({
        content: '"><script>alert(1)</script>{{user_input}}',
        variables: ['user_input'],
      }),
      unicodeContent: this.buildPromptCreateRequest({
        content: '你好\u0000世界\u202e{{user_input}}',
        variables: ['user_input'],
      }),
    };
  }

  buildBoundaryExperimentData() {
    return {
      emptyName: this.buildExperimentCreateRequest({ name: '' }),
      singleVariant: this.buildExperimentCreateRequest({
        variants: [this.buildVariantConfig('A')],
      }),
      tooManyVariants: this.buildExperimentCreateRequest({
        variants: Array.from({ length: 20 }, (_, i) => this.buildVariantConfig(String(i))),
      }),
      invalidTrafficWeight: this.buildExperimentCreateRequest({
        variants: [
          this.buildVariantConfig('A', { traffic_weight: 101 }),
          this.buildVariantConfig('B', { traffic_weight: -1 }),
        ],
      }),
      trafficSumNot100: this.buildExperimentCreateRequest({
        variants: [
          this.buildVariantConfig('A', { traffic_weight: 30 }),
          this.buildVariantConfig('B', { traffic_weight: 40 }),
        ],
      }),
      invalidTrafficPercentage: this.buildExperimentCreateRequest({
        traffic_percentage: 150,
      }),
      negativeTrafficPercentage: this.buildExperimentCreateRequest({
        traffic_percentage: -10,
      }),
      endTimeBeforeStartTime: this.buildExperimentCreateRequest({
        start_time: Date.now(),
        end_time: Date.now() - 86400000,
      }),
      zeroConfidence: this.buildExperimentCreateRequest({
        confidence_level: 0,
      }),
      tooHighConfidence: this.buildExperimentCreateRequest({
        confidence_level: 2,
      }),
    };
  }

  buildExpectedPromptResponse() {
    return {
      code: 200,
      message: expect.any(String),
      data: expect.objectContaining({
        prompt_id: expect.any(String),
        name: expect.any(String),
        content: expect.any(String),
        variables: expect.any(Array),
      }),
    };
  }

  buildExpectedExperimentResponse() {
    return {
      code: 200,
      message: expect.any(String),
      data: expect.objectContaining({
        experiment_id: expect.any(String),
        name: expect.any(String),
        variants: expect.any(Array),
        status: expect.any(String),
      }),
    };
  }

  buildExpectedRouteResponse() {
    return {
      code: 200,
      message: expect.any(String),
      data: expect.objectContaining({
        variant_id: expect.any(String),
        prompt_id: expect.any(String),
        experiment_id: expect.any(String),
      }),
    };
  }

  buildValidationTestCases() {
    return {
      requiredFields: [
        { field: 'name', message: /name.*required/i },
        { field: 'content', message: /content.*required/i },
        { field: 'variables', message: /variables.*required/i },
      ],
      fieldConstraints: [
        { field: 'name', value: '', message: /name.*empty/i },
        { field: 'name', value: 'a'.repeat(256), message: /name.*length/i },
      ],
    };
  }
}

const promptExperimentBuilder = new PromptExperimentDataBuilder();
module.exports = { PromptExperimentDataBuilder, promptExperimentBuilder };
