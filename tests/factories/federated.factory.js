const { v4: uuidv4 } = require('uuid');
const Chance = require('chance');

const chance = new Chance();

const TaskStatus = {
  CREATED: 'created',
  REGISTRATION_OPEN: 'registration_open',
  TRAINING: 'training',
  AGGREGATING: 'aggregating',
  COMPLETED: 'completed',
  FAILED: 'failed',
  TIMEOUT: 'timeout',
  DEGRADED: 'degraded'
};

const AggregationStrategy = {
  FED_AVG: 'fed_avg',
  FED_PROX: 'fed_prox',
  FED_ADAM: 'fed_adam',
  SECURE_AGGREGATION: 'secure_aggregation'
};

const TrainingPhase = {
  INIT: 'init',
  DOWNLOAD: 'download',
  TRAIN: 'train',
  UPLOAD: 'upload',
  AGGREGATE: 'aggregate'
};

const generateFederatedConfig = (options = {}) => ({
  task_type: options.task_type || 'classification',
  model_architecture: options.model_architecture || 'logistic_regression',
  aggregation_strategy: options.aggregation_strategy || AggregationStrategy.FED_AVG,
  max_rounds: options.max_rounds || 10,
  min_participants: options.min_participants || 3,
  max_participants: options.max_participants || 10,
  training_timeout: options.training_timeout || 300,
  aggregation_timeout: options.aggregation_timeout || 60,
  participation_rate: options.participation_rate || 0.8,
  learning_rate: options.learning_rate || 0.01,
  batch_size: options.batch_size || 32,
  epochs: options.epochs || 5,
  enable_secure_aggregation: options.enable_secure_aggregation !== false,
  enable_dp: options.enable_dp || false,
  dp_noise_scale: options.dp_noise_scale || 0.01,
  differential_privacy: {
    epsilon: options.dp_epsilon || 1.0,
    delta: options.dp_delta || 1e-5,
    mechanism: options.dp_mechanism || 'laplace'
  }
});

const generateCreateTaskRequest = (options = {}) => ({
  name: options.name || `federated-task-${uuidv4().slice(0, 8)}`,
  description: options.description || '联邦学习训练任务',
  config: generateFederatedConfig(options.config),
  data_requirements: options.data_requirements || {
    min_samples: 100,
    features_count: 10,
    data_type: 'tabular'
  },
  metadata: options.metadata || {
    department: 'data-science',
    priority: 'high'
  }
});

const generateParticipantRegistration = (taskId, options = {}) => ({
  task_id: taskId,
  participant_id: options.participant_id || uuidv4(),
  organization: options.organization || chance.company(),
  data_stats: options.data_stats || {
    sample_count: chance.integer({ min: 100, max: 10000 }),
    features_count: 10,
    data_quality: chance.floating({ min: 0.7, max: 1.0 }),
    label_distribution: {
      class_0: chance.floating({ min: 0.4, max: 0.6 }),
      class_1: chance.floating({ min: 0.4, max: 0.6 })
    }
  },
  compute_capability: options.compute_capability || {
    cpu_cores: chance.integer({ min: 2, max: 32 }),
    memory_gb: chance.integer({ min: 4, max: 64 }),
    network_bandwidth_mbps: chance.integer({ min: 10, max: 1000 })
  },
  public_key: options.public_key || `-----BEGIN PUBLIC KEY-----\n${chance.string({ length: 200 })}\n-----END PUBLIC KEY-----`
});

const generateModelGradient = (dimensions = 10) => {
  const gradients = [];
  for (let i = 0; i < dimensions; i++) {
    gradients.push(chance.floating({ min: -1, max: 1, fixed: 6 }));
  }
  return {
    weights: gradients,
    bias: chance.floating({ min: -0.5, max: 0.5, fixed: 6 }),
    layer_grads: gradients.map(g => [g, g * 0.5])
  };
};

const generateGradientSubmission = (taskId, options = {}) => ({
  task_id: taskId,
  participant_id: options.participant_id || uuidv4(),
  round: options.round || 1,
  gradient: generateModelGradient(options.dimensions || 10),
  metrics: options.metrics || {
    training_loss: chance.floating({ min: 0.01, max: 0.5, fixed: 4 }),
    validation_accuracy: chance.floating({ min: 0.7, max: 0.99, fixed: 4 }),
    samples_trained: chance.integer({ min: 100, max: 10000 }),
    training_time_ms: chance.integer({ min: 1000, max: 60000 })
  },
  signature: options.signature || chance.string({ length: 64 }),
  timestamp: options.timestamp || Date.now()
});

const generateTimeoutTestScenarios = () => ([
  {
    name: '参与者训练超时触发降级',
    config: {
      training_timeout: 5,
      min_participants: 3,
      participation_rate: 0.5
    },
    timeoutParticipants: 2,
    totalParticipants: 5,
    expectedStatus: TaskStatus.DEGRADED,
    expectedMessage: '部分参与者超时，启用降级模式'
  },
  {
    name: '聚合阶段超时触发重试',
    config: {
      aggregation_timeout: 3,
      max_retries: 3
    },
    expectedRetryCount: 3,
    expectedFinalStatus: TaskStatus.FAILED
  },
  {
    name: '关键参与者全部超时任务失败',
    config: {
      training_timeout: 5,
      min_participants: 3
    },
    timeoutParticipants: 3,
    totalParticipants: 3,
    expectedStatus: TaskStatus.TIMEOUT,
    expectedMessage: '未达到最小参与者要求，任务超时失败'
  },
  {
    name: '超时后启用备用参与者',
    config: {
      training_timeout: 5,
      min_participants: 3,
      max_participants: 8,
      enable_fallback: true
    },
    primaryParticipants: 5,
    fallbackParticipants: 3,
    expectedFallbackActivation: true,
    expectedStatus: TaskStatus.TRAINING
  },
  {
    name: '渐进式超时阈值调整',
    initialTimeout: 30,
    adjustmentFactor: 1.5,
    maxAdjustments: 3,
    expectedTimeoutSequence: [30, 45, 67, 100]
  }
]);

const generateDegradationTestCases = () => ([
  {
    name: '降级模式下减少聚合轮次',
    normalRounds: 10,
    degradedRounds: 3,
    expectedQualityImpact: 'acceptable'
  },
  {
    name: '降级模式下放松收敛条件',
    normalThreshold: 0.001,
    degradedThreshold: 0.01,
    expectedSpeedup: '2x'
  },
  {
    name: '降级模式下启用差分隐私简化版',
    normalEpsilon: 1.0,
    degradedEpsilon: 2.0,
    expectedPrivacyTradeoff: 'reduced'
  }
]);

const generateGlobalModelUpdate = (options = {}) => ({
  task_id: options.task_id || uuidv4(),
  round: options.round || 1,
  weights: generateModelGradient(options.dimensions || 10).weights,
  metrics: {
    global_loss: chance.floating({ min: 0.01, max: 0.3, fixed: 4 }),
    global_accuracy: chance.floating({ min: 0.8, max: 0.99, fixed: 4 }),
    participants_count: options.participants_count || 5,
    aggregation_time_ms: chance.integer({ min: 100, max: 5000 })
  },
  metadata: {
    aggregation_strategy: AggregationStrategy.FED_AVG,
    timestamp: Date.now(),
    checksum: chance.string({ length: 64 })
  }
});

module.exports = {
  TaskStatus,
  AggregationStrategy,
  TrainingPhase,
  generateFederatedConfig,
  generateCreateTaskRequest,
  generateParticipantRegistration,
  generateModelGradient,
  generateGradientSubmission,
  generateTimeoutTestScenarios,
  generateDegradationTestCases,
  generateGlobalModelUpdate
};
