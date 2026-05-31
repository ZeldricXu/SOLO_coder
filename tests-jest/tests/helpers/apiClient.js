const request = require('supertest');
const { config, endpoints } = require('../config');

const createApiClient = () => {
  const agent = request(config.apiBaseUrl);

  const withRetry = async (requestFn, maxRetries = config.retryAttempts) => {
    let lastError;
    for (let attempt = 0; attempt < maxRetries; attempt += 1) {
      try {
        return await requestFn();
      } catch (error) {
        lastError = error;
        if (attempt < maxRetries - 1) {
          await new Promise(resolve => setTimeout(resolve, config.retryDelay));
        }
      }
    }
    throw lastError;
  };

  return {
    health: {
      check: () => agent.get(endpoints.health).timeout(config.apiTimeout),
    },

    adversarial: {
      generate: (data) => withRetry(() =>
        agent.post(endpoints.adversarial.generate)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      batchAttack: (data) => withRetry(() =>
        agent.post(endpoints.adversarial.batchAttack)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      evaluate: (data) => withRetry(() =>
        agent.post(endpoints.adversarial.evaluate)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      getStrategies: () => withRetry(() =>
        agent.get(endpoints.adversarial.strategies)
          .timeout(config.apiTimeout)
      ),
    },

    featureStore: {
      registerFeature: (data) => withRetry(() =>
        agent.post(endpoints.featureStore.register)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      getFeature: (featureId) => withRetry(() =>
        agent.get(`${endpoints.featureStore.register}/${featureId}`)
          .timeout(config.apiTimeout)
      ),
      listFeatures: (params = {}) => withRetry(() =>
        agent.get(endpoints.featureStore.register)
          .query(params)
          .timeout(config.apiTimeout)
      ),
      getOnlineFeatures: (data) => withRetry(() =>
        agent.post(endpoints.featureStore.online)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      getOfflineFeatures: (data) => withRetry(() =>
        agent.post(endpoints.featureStore.offline)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      checkConsistency: (data) => withRetry(() =>
        agent.post(endpoints.featureStore.consistency)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      storeEntityData: (data) => withRetry(() =>
        agent.post(endpoints.featureStore.entities)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      getEntityData: (entityId, params = {}) => withRetry(() =>
        agent.get(`${endpoints.featureStore.entities}/${entityId}`)
          .query(params)
          .timeout(config.apiTimeout)
      ),
    },

    promptExperiments: {
      createPrompt: (data) => withRetry(() =>
        agent.post(endpoints.promptExperiments.prompts)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      getPrompt: (promptId) => withRetry(() =>
        agent.get(`${endpoints.promptExperiments.prompts}/${promptId}`)
          .timeout(config.apiTimeout)
      ),
      listPrompts: (params = {}) => withRetry(() =>
        agent.get(endpoints.promptExperiments.prompts)
          .query(params)
          .timeout(config.apiTimeout)
      ),
      createExperiment: (data) => withRetry(() =>
        agent.post(endpoints.promptExperiments.experiments)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      getExperiment: (experimentId) => withRetry(() =>
        agent.get(`${endpoints.promptExperiments.experiments}/${experimentId}`)
          .timeout(config.apiTimeout)
      ),
      listExperiments: (params = {}) => withRetry(() =>
        agent.get(endpoints.promptExperiments.experiments)
          .query(params)
          .timeout(config.apiTimeout)
      ),
      route: (data) => withRetry(() =>
        agent.post(endpoints.promptExperiments.route)
          .send(data)
          .timeout(config.apiTimeout)
      ),
      recordMetric: (data) => withRetry(() =>
        agent.post(endpoints.promptExperiments.recordMetric)
          .send(data)
          .timeout(config.apiTimeout)
      ),
    },
  };
};

module.exports = { createApiClient };
