const { v4: uuidv4 } = require('uuid');

class TestDataFactory {
  static generateId(prefix = '') {
    return `${prefix}${uuidv4().replace(/-/g, '').substring(0, 12)}`;
  }

  static generateTimestamp(offsetSeconds = 0) {
    const date = new Date();
    date.setSeconds(date.getSeconds() + offsetSeconds);
    return date.toISOString();
  }
}

class InferenceTestDataFactory extends TestDataFactory {
  static createModelData(overrides = {}) {
    const modelId = overrides.model_id || this.generateId('model_');
    return {
      model_id: modelId,
      name: overrides.name || '测试分类模型',
      model_path: overrides.model_path || '/models/classification.onnx',
      model_type: overrides.model_type || 'classification',
      description: overrides.description || '用于图像分类的深度学习模型',
      version: overrides.version || '1.0.0',
      input_schema: overrides.input_schema || {
        type: 'object',
        properties: {
          image: { type: 'array', items: { type: 'number' } }
        },
        required: ['image']
      },
      output_schema: overrides.output_schema || {
        type: 'object',
        properties: {
          predictions: { type: 'array', items: { type: 'object' } }
        }
      },
      tags: overrides.tags || ['vision', 'classification'],
      ...overrides
    };
  }

  static createInferenceTaskData(overrides = {}) {
    return {
      model_id: overrides.model_id || 'model_test_001',
      input_data: overrides.input_data || {
        image: Array(784).fill(0.5)
      },
      device_id: overrides.device_id || 'dev_test_001',
      priority: overrides.priority || 0,
      callback_url: overrides.callback_url || null,
      ...overrides
    };
  }

  static createInvalidModelData(scenario) {
    const scenarios = {
      empty_model_id: {
        model_id: '',
        name: '测试模型',
        model_path: '/models/test.onnx'
      },
      missing_model_id: {
        name: '测试模型',
        model_path: '/models/test.onnx'
      },
      null_model_id: {
        model_id: null,
        name: '测试模型',
        model_path: '/models/test.onnx'
      },
      empty_name: {
        model_id: this.generateId('model_'),
        name: '',
        model_path: '/models/test.onnx'
      },
      missing_name: {
        model_id: this.generateId('model_'),
        model_path: '/models/test.onnx'
      },
      empty_path: {
        model_id: this.generateId('model_'),
        name: '测试模型',
        model_path: ''
      },
      missing_path: {
        model_id: this.generateId('model_'),
        name: '测试模型'
      },
      invalid_model_type: {
        model_id: this.generateId('model_'),
        name: '测试模型',
        model_path: '/models/test.onnx',
        model_type: 'invalid_type'
      },
      invalid_version_format: {
        model_id: this.generateId('model_'),
        name: '测试模型',
        model_path: '/models/test.onnx',
        version: 'invalid_version'
      }
    };
    return scenarios[scenario] || {};
  }

  static createInvalidTaskData(scenario) {
    const scenarios = {
      empty_model_id: {
        model_id: '',
        input_data: { image: [1, 2, 3] }
      },
      missing_model_id: {
        input_data: { image: [1, 2, 3] }
      },
      null_model_id: {
        model_id: null,
        input_data: { image: [1, 2, 3] }
      },
      empty_input: {
        model_id: 'model_test_001',
        input_data: {}
      },
      missing_input: {
        model_id: 'model_test_001'
      },
      null_input: {
        model_id: 'model_test_001',
        input_data: null
      },
      invalid_priority_negative: {
        model_id: 'model_test_001',
        input_data: { image: [1, 2, 3] },
        priority: -100
      },
      invalid_priority_too_high: {
        model_id: 'model_test_001',
        input_data: { image: [1, 2, 3] },
        priority: 101
      },
      invalid_callback_url: {
        model_id: 'model_test_001',
        input_data: { image: [1, 2, 3] },
        callback_url: 'not-a-valid-url'
      },
      large_input_data: {
        model_id: 'model_test_001',
        input_data: {
          image: Array(1000000).fill(0.5),
          metadata: {
            nested: {
              deep: {
                value: 'test'
              }
            }
          }
        }
      }
    };
    return scenarios[scenario] || {};
  }

  static createBoundaryConditionScenarios() {
    return [
      {
        name: '零优先级任务',
        data: this.createInferenceTaskData({ priority: 0 }),
        expected: { statusCode: 200, status: 'pending' }
      },
      {
        name: '最高优先级任务',
        data: this.createInferenceTaskData({ priority: 100 }),
        expected: { statusCode: 200, status: 'pending' }
      },
      {
        name: '空输入数据',
        data: this.createInferenceTaskData({ input_data: {} }),
        expected: { statusCode: 400 }
      },
      {
        name: '超大输入数据',
        data: this.createInvalidTaskData('large_input_data'),
        expected: { statusCode: 400 }
      },
      {
        name: '不存在的模型ID',
        data: this.createInferenceTaskData({ model_id: 'model_nonexistent_999' }),
        expected: { statusCode: 400 }
      },
      {
        name: '带回调URL的任务',
        data: this.createInferenceTaskData({
          callback_url: 'http://localhost:9999/callback'
        }),
        expected: { statusCode: 200 }
      },
      {
        name: '关联设备的任务',
        data: this.createInferenceTaskData({ device_id: 'dev_boundary_001' }),
        expected: { statusCode: 200 }
      },
      {
        name: '无设备关联的任务',
        data: this.createInferenceTaskData({ device_id: null }),
        expected: { statusCode: 200 }
      },
      {
        name: '批量提交100个任务',
        data: Array.from({ length: 100 }, (_, i) =>
          this.createInferenceTaskData({ device_id: `dev_batch_${i}` })
        ),
        expected: { statusCode: 200 }
      },
      {
        name: '重复提交相同任务',
        data: this.createInferenceTaskData({ device_id: 'dev_duplicate_001' }),
        expected: { statusCode: 200, allowDuplicate: true }
      }
    ];
  }

  static createTimeoutScenarios() {
    return [
      {
        name: '同步推理超时边界-刚好超时',
        data: this.createInferenceTaskData({ input_data: { simulate_delay: 30000 } }),
        timeout: 30.0,
        expected: { statusCode: 408 }
      },
      {
        name: '同步推理超时边界-刚好不超时',
        data: this.createInferenceTaskData({ input_data: { simulate_delay: 500 } }),
        timeout: 30.0,
        expected: { statusCode: 200 }
      },
      {
        name: '最小超时时间',
        data: this.createInferenceTaskData(),
        timeout: 1.0,
        expected: { statusCode: 200 }
      },
      {
        name: '最大超时时间',
        data: this.createInferenceTaskData(),
        timeout: 300.0,
        expected: { statusCode: 200 }
      }
    ];
  }
}

class DataAggregationTestDataFactory extends TestDataFactory {
  static createTelemetryData(overrides = {}) {
    const deviceId = overrides.device_id || this.generateId('dev_');
    const timestamp = overrides.timestamp || this.generateTimestamp();
    return {
      device_id: deviceId,
      metric_name: overrides.metric_name || 'temperature',
      value: overrides.value !== undefined ? overrides.value : 25.5,
      timestamp: timestamp,
      quality: overrides.quality || 'GOOD',
      ...overrides
    };
  }

  static createBatchTelemetryData(deviceId, count, startOffset = 0, metricGenerator = null) {
    const dataPoints = [];
    for (let i = 0; i < count; i++) {
      const offset = startOffset - (count - i) * 60;
      const baseTemp = 25 + Math.sin(i * 0.1) * 5;
      
      let metricData;
      if (metricGenerator) {
        metricData = metricGenerator(i, baseTemp);
        dataPoints.push({
          device_id: deviceId,
          timestamp: this.generateTimestamp(offset),
          quality: 'GOOD',
          ...metricData
        });
      } else {
        dataPoints.push({
          device_id: deviceId,
          metric_name: 'temperature',
          value: parseFloat((baseTemp + Math.random() * 2).toFixed(2)),
          timestamp: this.generateTimestamp(offset),
          quality: 'GOOD'
        });
      }
    }
    
    return dataPoints;
  }

  static createAggregationRuleData(overrides = {}) {
    return {
      metric_name: overrides.metric_name || 'temperature',
      aggregation_type: overrides.aggregation_type || 'avg',
      interval_seconds: overrides.interval_seconds || 60,
      output_topic: overrides.output_topic || '/aggregated/temperature',
      retention_policy: overrides.retention_policy || {
        retention_days: 30,
        aggregation_intervals: ['1h', '1d', '7d']
      },
      ...overrides
    };
  }

  static createTransactionRollbackScenarios() {
    return [
      {
        name: '批量数据中途失败 - 第N条数据异常',
        data: {
          goodCount: 100,
          badIndex: 50,
          badScenario: 'non_numeric_value'
        },
        expected: {
          shouldRollback: true,
          statusCode: 400
        }
      },
      {
        name: '并发写入冲突 - 同一时间戳',
        data: {
          concurrentWriters: 10,
          timestamp: this.generateTimestamp()
        },
        expected: {
          atomicity: true,
          statusCode: 200
        }
      },
      {
        name: '内存溢出保护 - 超大批量数据',
        data: {
          batchSize: 100000
        },
        expected: {
          shouldRollback: true,
          statusCode: 413
        }
      },
      {
        name: '聚合计算中途失败 - 数据一致性验证',
        data: {
          recordCount: 1000,
          metricName: 'temperature',
          goodValue: 25.0,
          badValue: NaN,
          aggregationType: 'avg'
        },
        expected: {
          shouldRollback: false,
          statusCode: 200
        }
      },
      {
        name: '网络中断模拟 - 批量提交中途断开',
        data: {
          successCount: 50
        },
        expected: {
          shouldRollback: false,
          statusCode: 200
        }
      }
    ];
  }

  static createAggregationBoundaryScenarios() {
    return [
      {
        name: '空数据集聚合',
        data: {
          values: []
        },
        expected: {
          statusCode: 200,
          result: null
        }
      },
      {
        name: '单元素数据集聚合',
        data: {
          values: [42.5]
        },
        expected: {
          result: 42.5
        }
      },
      {
        name: '包含NaN的数据集聚合',
        data: {
          values: [20, NaN, 30]
        },
        expected: {
          result: 25
        }
      },
      {
        name: '全部为NaN的数据集',
        data: {
          values: [NaN, NaN, NaN]
        },
        expected: {
          result: null,
          statusCode: 200
        }
      },
      {
        name: '极值数据集聚合',
        data: {
          values: [Number.MAX_VALUE, Number.MIN_VALUE, 0]
        },
        expected: {
          shouldHandle: true
        }
      },
      {
        name: '时间范围边界测试',
        data: {
          timeRange: {
            start: -5 * 60,
            end: 0
          }
        },
        expected: {
          statusCode: 200
        }
      }
    ];
  }
  static createInvalidTelemetryData(scenario) {
    const scenarios = {
      missing_device_id: {
        metric_name: 'temperature',
        value: 25.5
      },
      empty_device_id: {
        device_id: '',
        metric_name: 'temperature',
        value: 25.5
      },
      null_device_id: {
        device_id: null,
        metric_name: 'temperature',
        value: 25.5
      },
      missing_metric_name: {
        device_id: 'dev_test_001',
        value: 25.5
      },
      empty_metric_name: {
        device_id: 'dev_test_001',
        metric_name: '',
        value: 25.5
      },
      null_metric_name: {
        device_id: 'dev_test_001',
        metric_name: null,
        value: 25.5
      },
      missing_value: {
        device_id: 'dev_test_001',
        metric_name: 'temperature'
      },
      null_value: {
        device_id: 'dev_test_001',
        metric_name: 'temperature',
        value: null
      },
      non_numeric_value: {
        device_id: 'dev_test_001',
        metric_name: 'temperature',
        value: 'not-a-number'
      },
      nan_value: {
        device_id: 'dev_test_001',
        metric_name: 'temperature',
        value: NaN
      },
      infinity_value: {
        device_id: 'dev_test_001',
        metric_name: 'temperature',
        value: Infinity
      },
      negative_infinity_value: {
        device_id: 'dev_test_001',
        metric_name: 'temperature',
        value: -Infinity
      },
      invalid_timestamp_format: {
        device_id: 'dev_test_001',
        metric_name: 'temperature',
        value: 25.5,
        timestamp: 'not-a-valid-timestamp'
      },
      extreme_large_value: {
        device_id: 'dev_test_001',
        metric_name: 'temperature',
        value: Number.MAX_VALUE
      },
      extreme_small_value: {
        device_id: 'dev_test_001',
        metric_name: 'temperature',
        value: Number.MIN_VALUE
      }
    };
    return scenarios[scenario] || {};
  }

  static createInvalidAggregationRuleData(scenario) {
    const scenarios = {
      missing_metric_name: {
        aggregation_type: 'avg',
        interval_seconds: 60,
        output_topic: '/aggregated/temperature'
      },
      empty_metric_name: {
        metric_name: '',
        aggregation_type: 'avg',
        interval_seconds: 60,
        output_topic: '/aggregated/temperature'
      },
      null_metric_name: {
        metric_name: null,
        aggregation_type: 'avg',
        interval_seconds: 60,
        output_topic: '/aggregated/temperature'
      },
      missing_aggregation_type: {
        metric_name: 'temperature',
        interval_seconds: 60,
        output_topic: '/aggregated/temperature'
      },
      empty_aggregation_type: {
        metric_name: 'temperature',
        aggregation_type: '',
        interval_seconds: 60,
        output_topic: '/aggregated/temperature'
      },
      null_aggregation_type: {
        metric_name: 'temperature',
        aggregation_type: null,
        interval_seconds: 60,
        output_topic: '/aggregated/temperature'
      },
      invalid_aggregation_type: {
        metric_name: 'temperature',
        aggregation_type: 'invalid_type',
        interval_seconds: 60,
        output_topic: '/aggregated/temperature'
      },
      zero_interval_seconds: {
        metric_name: 'temperature',
        aggregation_type: 'avg',
        interval_seconds: 0,
        output_topic: '/aggregated/temperature'
      },
      negative_interval_seconds: {
        metric_name: 'temperature',
        aggregation_type: 'avg',
        interval_seconds: -60,
        output_topic: '/aggregated/temperature'
      },
      too_large_interval_seconds: {
        metric_name: 'temperature',
        aggregation_type: 'avg',
        interval_seconds: 86401,
        output_topic: '/aggregated/temperature'
      },
      invalid_retention_policy: {
        metric_name: 'temperature',
        aggregation_type: 'avg',
        interval_seconds: 60,
        output_topic: '/aggregated/temperature',
        retention_policy: 'invalid'
      },
      invalid_output_topic: {
        metric_name: 'temperature',
        aggregation_type: 'avg',
        interval_seconds: 60,
        output_topic: ''
      }
    };
    return scenarios[scenario] || {};
  }
}

class RuleEngineTestDataFactory extends TestDataFactory {
  static createRuleData(overrides = {}) {
    const ruleId = overrides.rule_id || this.generateId('rule_');
    return {
      rule_id: ruleId,
      name: overrides.name || '温度过高告警',
      description: overrides.description || '当温度超过30度时发送告警',
      enabled: overrides.enabled !== undefined ? overrides.enabled : true,
      priority: overrides.priority || 0,
      conditions: overrides.conditions || [
        this.createCondition({
          field: 'telemetry.temperature',
          operator: 'gt',
          value: 30.0
        })
      ],
      actions: overrides.actions || [
        this.createAction({
          type: 'alert',
          parameters: {
            type: 'warning',
            message: '温度过高告警',
            severity: 'high'
          }
        })
      ],
      device_ids: overrides.device_ids || [],
      device_tags: overrides.device_tags || [],
      trigger_limit: overrides.trigger_limit || 0,
      cooldown_seconds: overrides.cooldown_seconds || 60,
      ...overrides
    };
  }

  static createCondition(overrides = {}) {
    return {
      type: 'comparison',
      field: overrides.field || 'telemetry.temperature',
      operator: overrides.operator || 'gt',
      value: overrides.value !== undefined ? overrides.value : 30.0,
      ...overrides
    };
  }

  static createCompoundCondition(operator = 'and', conditions = []) {
    return {
      type: 'compound',
      operator: operator,
      conditions: conditions.length > 0 ? conditions : [
        this.createCondition({ field: 'telemetry.temperature', operator: 'gt', value: 30 }),
        this.createCondition({ field: 'telemetry.humidity', operator: 'gt', value: 80 })
      ]
    };
  }

  static createAction(overrides = {}) {
    return {
      type: overrides.type || overrides.action_type || 'send_alert',
      parameters: overrides.parameters || {
        type: 'info',
        message: '规则触发',
        severity: 'low'
      },
      delay_seconds: overrides.delay_seconds || 0,
      repeat_count: overrides.repeat_count || 1,
      repeat_interval: overrides.repeat_interval || 0
    };
  }

  static createInvalidRuleData(scenario) {
    const baseRule = this.createRuleData();
    const scenarios = {
      empty_rule_id: {
        ...baseRule,
        rule_id: ''
      },
      missing_rule_id: {
        rule_name: '测试规则',
        rule_type: 'telemetry',
        condition: { field: 'temp', operator: 'gt', value: 30 },
        actions: [{ action_type: 'send_alert', parameters: {} }]
      },
      null_rule_id: {
        ...baseRule,
        rule_id: null
      },
      empty_rule_name: {
        ...baseRule,
        rule_name: ''
      },
      whitespace_rule_name: {
        ...baseRule,
        rule_name: '   '
      },
      missing_rule_name: {
        rule_id: this.generateId('rule_'),
        rule_type: 'telemetry',
        condition: { field: 'temp', operator: 'gt', value: 30 },
        actions: [{ action_type: 'send_alert', parameters: {} }]
      },
      invalid_rule_type: {
        ...baseRule,
        rule_type: 'invalid_type'
      },
      missing_rule_type: {
        rule_id: this.generateId('rule_'),
        rule_name: '测试规则',
        condition: { field: 'temp', operator: 'gt', value: 30 },
        actions: [{ action_type: 'send_alert', parameters: {} }]
      },
      empty_condition: {
        ...baseRule,
        condition: {}
      },
      missing_condition: {
        rule_id: this.generateId('rule_'),
        rule_name: '测试规则',
        rule_type: 'telemetry',
        actions: [{ action_type: 'send_alert', parameters: {} }]
      },
      null_condition: {
        ...baseRule,
        condition: null
      },
      invalid_condition_operator: {
        ...baseRule,
        condition: { field: 'temp', operator: 'invalid_op', value: 30 }
      },
      condition_missing_field: {
        ...baseRule,
        condition: { operator: 'gt', value: 30 }
      },
      condition_missing_value: {
        ...baseRule,
        condition: { field: 'temp', operator: 'gt' }
      },
      compound_condition_missing_conditions: {
        ...baseRule,
        condition: { operator: 'and' }
      },
      compound_condition_empty_conditions: {
        ...baseRule,
        condition: { operator: 'and', conditions: [] }
      },
      empty_actions: {
        ...baseRule,
        actions: []
      },
      missing_actions: {
        rule_id: this.generateId('rule_'),
        rule_name: '测试规则',
        rule_type: 'telemetry',
        condition: { field: 'temp', operator: 'gt', value: 30 }
      },
      null_actions: {
        ...baseRule,
        actions: null
      },
      invalid_action_type: {
        ...baseRule,
        actions: [{ action_type: 'invalid_action', parameters: {} }]
      },
      action_missing_type: {
        ...baseRule,
        actions: [{ parameters: { message: 'test' } }]
      },
      negative_priority: {
        ...baseRule,
        priority: -100
      },
      excessive_priority: {
        ...baseRule,
        priority: 10000
      },
      negative_trigger_limit: {
        ...baseRule,
        trigger_limit: -1
      },
      negative_cooldown: {
        ...baseRule,
        cooldown_period: -60
      },
      invalid_between_value: {
        ...baseRule,
        condition: { field: 'temp', operator: 'between', value: [30] }
      },
      between_value_not_array: {
        ...baseRule,
        condition: { field: 'temp', operator: 'between', value: 30 }
      }
    };
    return scenarios[scenario] || baseRule;
  }

  static createTelemetryEvaluationScenarios() {
    return [
      {
        name: '简单大于条件 - 满足',
        condition: this.createCondition({ field: 'telemetry.temperature', operator: 'gt', value: 30 }),
        data: { telemetry: { temperature: 35.5 } },
        expected: { shouldTrigger: true }
      },
      {
        name: '简单大于条件 - 不满足',
        condition: this.createCondition({ field: 'telemetry.temperature', operator: 'gt', value: 30 }),
        data: { telemetry: { temperature: 25.5 } },
        expected: { shouldTrigger: false }
      },
      {
        name: '边界值等于',
        condition: this.createCondition({ field: 'telemetry.temperature', operator: 'gte', value: 30 }),
        data: { telemetry: { temperature: 30.0 } },
        expected: { shouldTrigger: true }
      },
      {
        name: '字符串包含条件',
        condition: this.createCondition({ field: 'device_id', operator: 'contains', value: 'sensor' }),
        data: { device_id: 'sensor_001' },
        expected: { shouldTrigger: true }
      },
      {
        name: 'IN列表条件 - 存在',
        condition: this.createCondition({ field: 'status', operator: 'in', value: ['online', 'connected'] }),
        data: { status: 'online' },
        expected: { shouldTrigger: true }
      },
      {
        name: 'IN列表条件 - 不存在',
        condition: this.createCondition({ field: 'status', operator: 'in', value: ['online', 'connected'] }),
        data: { status: 'offline' },
        expected: { shouldTrigger: false }
      },
      {
        name: 'BETWEEN范围条件 - 在范围内',
        condition: this.createCondition({ field: 'telemetry.temperature', operator: 'between', value: [20, 30] }),
        data: { telemetry: { temperature: 25 } },
        expected: { shouldTrigger: true }
      },
      {
        name: 'BETWEEN范围条件 - 边界值',
        condition: this.createCondition({ field: 'telemetry.temperature', operator: 'between', value: [20, 30] }),
        data: { telemetry: { temperature: 20 } },
        expected: { shouldTrigger: true }
      },
      {
        name: 'AND复合条件 - 全部满足',
        condition: this.createCompoundCondition('and', [
          this.createCondition({ field: 'telemetry.temperature', operator: 'gt', value: 30 }),
          this.createCondition({ field: 'telemetry.humidity', operator: 'gt', value: 80 })
        ]),
        data: { telemetry: { temperature: 35, humidity: 85 } },
        expected: { shouldTrigger: true }
      },
      {
        name: 'AND复合条件 - 部分满足',
        condition: this.createCompoundCondition('and', [
          this.createCondition({ field: 'telemetry.temperature', operator: 'gt', value: 30 }),
          this.createCondition({ field: 'telemetry.humidity', operator: 'gt', value: 80 })
        ]),
        data: { telemetry: { temperature: 35, humidity: 70 } },
        expected: { shouldTrigger: false }
      },
      {
        name: 'OR复合条件 - 部分满足',
        condition: this.createCompoundCondition('or', [
          this.createCondition({ field: 'telemetry.temperature', operator: 'gt', value: 30 }),
          this.createCondition({ field: 'telemetry.humidity', operator: 'gt', value: 80 })
        ]),
        data: { telemetry: { temperature: 25, humidity: 85 } },
        expected: { shouldTrigger: true }
      },
      {
        name: '嵌套字段访问',
        condition: this.createCondition({ field: 'telemetry.motor.vibration', operator: 'gt', value: 0.5 }),
        data: { telemetry: { motor: { vibration: 0.8 } } },
        expected: { shouldTrigger: true }
      },
      {
        name: '嵌套字段不存在',
        condition: this.createCondition({ field: 'telemetry.nonexistent.value', operator: 'gt', value: 30 }),
        data: { telemetry: { temperature: 35 } },
        expected: { shouldTrigger: false, shouldNotError: true }
      },
      {
        name: '字段为null',
        condition: this.createCondition({ field: 'telemetry.temperature', operator: 'gt', value: 30 }),
        data: { telemetry: { temperature: null } },
        expected: { shouldTrigger: false, shouldNotError: true }
      },
      {
        name: '不同类型比较',
        condition: this.createCondition({ field: 'telemetry.temperature', operator: 'gt', value: '30' }),
        data: { telemetry: { temperature: 35 } },
        expected: { shouldHandleGracefully: true }
      },
      {
        name: '大于比较 - 数值',
        data: {
          operator: '>',
          conditionValue: 50,
        },
        expected: {
          triggered: true,
        }
      },
      {
        name: '小于比较 - 数值',
        data: {
          operator: '<',
          conditionValue: 50,
        },
        expected: {
          triggered: true,
        }
      },
      {
        name: '等于比较 - 数值',
        data: {
          operator: '==',
          conditionValue: 50,
        },
        expected: {
          triggered: true,
        }
      },
      {
        name: '不等于比较 - 数值',
        data: {
          operator: '!=',
          conditionValue: 50,
        },
        expected: {
          triggered: true,
        }
      },
      {
        name: '大于等于比较',
        data: {
          operator: '>=',
          conditionValue: 50,
        },
        expected: {
          triggered: true,
        }
      },
      {
        name: '小于等于比较',
        data: {
          operator: '<=',
          conditionValue: 50,
        },
        expected: {
          triggered: true,
        }
      },
      {
        name: '包含字符串',
        data: {
          operator: 'contains',
          conditionValue: 'ERROR',
        },
        expected: {
          triggered: true,
        }
      }
    ];
  }

  static createRuleActionValidationScenarios() {
    return [
      {
        name: 'alert动作 - 完整参数',
        data: {
          action: this.createAction({
            type: 'alert',
            parameters: { type: 'warning', message: '告警消息', severity: 'high' }
          })
        },
        expected: { valid: true }
      },
      {
        name: 'alert动作 - 缺少message',
        data: {
          action: this.createAction({
            type: 'alert',
            parameters: { type: 'warning' }
          })
        },
        expected: { valid: false }
      },
      {
        name: 'command动作 - 缺少command',
        data: {
          action: this.createAction({
            type: 'command',
            parameters: { parameters: {} }
          })
        },
        expected: { valid: false }
      },
      {
        name: 'command动作 - 完整参数',
        data: {
          action: this.createAction({
            type: 'command',
            parameters: { command: 'reboot', parameters: { delay: 10 } }
          })
        },
        expected: { valid: true }
      },
      {
        name: 'notification动作 - 缺少recipient',
        data: {
          action: this.createAction({
            type: 'notification',
            parameters: { message: '通知消息' }
          })
        },
        expected: { valid: false }
      },
      {
        name: 'notification动作 - 完整参数',
        data: {
          action: this.createAction({
            type: 'notification',
            parameters: { recipient: 'admin@example.com', message: '通知消息', channel: 'email' }
          })
        },
        expected: { valid: true }
      },
      {
        name: 'webhook动作 - 缺少url',
        data: {
          action: this.createAction({
            type: 'webhook',
            parameters: { method: 'POST' }
          })
        },
        expected: { valid: false }
      },
      {
        name: 'webhook动作 - 完整参数',
        data: {
          action: this.createAction({
            type: 'webhook',
            parameters: { url: 'http://example.com/webhook', method: 'POST', headers: {} }
          })
        },
        expected: { valid: true }
      },
      {
        name: 'set_device_shadow动作 - 缺少desired_state',
        data: {
          action: this.createAction({
            type: 'set_device_shadow',
            parameters: { device_id: 'dev_001' }
          })
        },
        expected: { valid: false }
      },
      {
        name: 'set_device_shadow动作 - 完整参数',
        data: {
          action: this.createAction({
            type: 'set_device_shadow',
            parameters: { device_id: 'dev_001', desired_state: { power: 'on' } }
          })
        },
        expected: { valid: true }
      },
      {
        name: '延迟动作 - 负延迟',
        data: {
          action: this.createAction({
            type: 'alert',
            parameters: { message: 'test' },
            delay_seconds: -10
          })
        },
        expected: { valid: false }
      },
      {
        name: '重复动作 - 无效重复次数',
        data: {
          action: this.createAction({
            type: 'alert',
            parameters: { message: 'test' },
            repeat_count: 0
          })
        },
        expected: { valid: false }
      }
    ];
  }

  static createRuleLifecycleScenarios() {
    return [
      {
        name: '创建新规则',
        data: {
          name: '测试规则',
          enabled: true,
        },
        expected: {
          statusCode: 200,
        }
      },
      {
        name: '启用已存在的规则',
        data: {
          enabled: true,
        },
        expected: {
          statusCode: 200,
        }
      },
      {
        name: '禁用已存在的规则',
        data: {
          enabled: false,
        },
        expected: {
          statusCode: 200,
        }
      },
      {
        name: '删除已存在的规则',
        data: {},
        expected: {
          statusCode: 200,
        }
      },
      {
        name: '触发频率限制测试',
        data: {
          triggerLimit: 5,
          cooldownSeconds: 60,
        },
        expected: {
          statusCode: 200,
        }
      },
      {
        name: '冷却时间测试',
        data: {
          cooldownSeconds: 10,
        },
        expected: {
          statusCode: 200,
        }
      },
      {
        name: '查询不存在的规则',
        data: {},
        expected: {
          statusCode: 404,
        }
      }
    ];
  }
}

module.exports = {
  TestDataFactory,
  InferenceTestDataFactory,
  DataAggregationTestDataFactory,
  RuleEngineTestDataFactory
};
