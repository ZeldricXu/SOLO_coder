const axios = require('axios');

class APIClient {
  constructor(baseURL = process.env.API_BASE_URL || 'http://localhost:8080') {
    this.baseURL = baseURL;
    this.client = axios.create({
      baseURL,
      timeout: 30000,
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      }
    });

    this.client.interceptors.request.use(config => {
      config.headers['X-Request-Id'] = config.headers['X-Request-Id'] || require('uuid').v4();
      config.headers['X-Timestamp'] = Date.now().toString();
      return config;
    });

    this.client.interceptors.response.use(
      response => response,
      error => {
        console.error(`❌ API请求失败: ${error.config?.method?.toUpperCase()} ${error.config?.url} - ${error.message}`);
        return Promise.reject(error);
      }
    );
  }

  async get(path, options = {}) {
    const response = await this.client.get(path, options);
    return response.data;
  }

  async post(path, data, options = {}) {
    const response = await this.client.post(path, data, options);
    return response.data;
  }

  async put(path, data, options = {}) {
    const response = await this.client.put(path, data, options);
    return response.data;
  }

  async delete(path, options = {}) {
    const response = await this.client.delete(path, options);
    return response.data;
  }

  async health() {
    return this.get('/health');
  }

  async createResource(data) {
    return this.post('/api/v1/resources', data);
  }

  async getResourceStatus(id) {
    return this.get(`/api/v1/resources/${id}/status`);
  }

  async batchOperation(data) {
    return this.post('/api/v1/resources/batch', data);
  }

  async createEnclave(data) {
    return this.post('/api/v1/tee/enclaves', data);
  }

  async getEnclave(id) {
    return this.get(`/api/v1/tee/enclaves/${id}`);
  }

  async listEnclaves() {
    return this.get('/api/v1/tee/enclaves');
  }

  async startEnclave(id) {
    return this.put(`/api/v1/tee/enclaves/${id}/start`);
  }

  async stopEnclave(id) {
    return this.put(`/api/v1/tee/enclaves/${id}/stop`);
  }

  async terminateEnclave(id) {
    return this.delete(`/api/v1/tee/enclaves/${id}`);
  }

  async generateAttestation(id, data) {
    return this.post(`/api/v1/tee/enclaves/${id}/attest`, data);
  }

  async executeSecureFunction(id, data) {
    return this.post(`/api/v1/tee/enclaves/${id}/execute`, data);
  }

  async heartbeat(id) {
    return this.post(`/api/v1/tee/enclaves/${id}/heartbeat`);
  }

  async maskData(data) {
    return this.post('/api/v1/masking/mask', data);
  }

  async listMaskingRules() {
    return this.get('/api/v1/masking/rules');
  }

  async createFederatedTask(data) {
    return this.post('/api/v1/federated/tasks', data);
  }

  async getFederatedTask(id) {
    return this.get(`/api/v1/federated/tasks/${id}`);
  }

  async listFederatedTasks() {
    return this.get('/api/v1/federated/tasks');
  }

  async registerParticipant(taskId, data) {
    return this.post(`/api/v1/federated/tasks/${taskId}/register`, data);
  }

  async submitGradient(taskId, data) {
    return this.post(`/api/v1/federated/tasks/${taskId}/gradient`, data);
  }

  async aggregateGradients(taskId, data) {
    return this.post(`/api/v1/federated/tasks/${taskId}/aggregate`, data);
  }
}

module.exports = APIClient;
