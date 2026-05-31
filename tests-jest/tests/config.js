const config = {
  apiBaseUrl: process.env.API_BASE_URL || 'http://localhost:8080',
  apiTimeout: parseInt(process.env.API_TIMEOUT || '30000', 10),
  retryAttempts: parseInt(process.env.RETRY_ATTEMPTS || '3', 10),
  retryDelay: parseInt(process.env.RETRY_DELAY || '1000', 10),
};

const endpoints = {
  adversarial: {
    generate: '/api/v1/adversarial/generate',
    batchAttack: '/api/v1/adversarial/batch-attack',
    evaluate: '/api/v1/adversarial/evaluate',
    strategies: '/api/v1/adversarial/strategies',
  },
  featureStore: {
    register: '/api/v1/feature-store/features',
    get: '/api/v1/feature-store/features',
    online: '/api/v1/feature-store/online',
    offline: '/api/v1/feature-store/offline',
    consistency: '/api/v1/feature-store/consistency-check',
    entities: '/api/v1/feature-store/entities',
  },
  promptExperiments: {
    prompts: '/api/v1/prompt-experiments/prompts',
    experiments: '/api/v1/prompt-experiments/experiments',
    route: '/api/v1/prompt-experiments/route',
    recordMetric: '/api/v1/prompt-experiments/metrics',
  },
  health: '/health',
};

module.exports = { config, endpoints };
