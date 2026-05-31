const request = require('supertest');

class ApiClient {
  constructor(baseUrl, apiVersion = 'v1') {
    this.baseUrl = baseUrl || process.env.API_BASE_URL || 'http://localhost:8000';
    this.apiVersion = apiVersion || process.env.API_VERSION || 'v1';
    this.basePath = `/api/${this.apiVersion}`;
    this.client = request(this.baseUrl);
    this.authToken = null;
    this.apiKey = null;
  }

  setAuthToken(token) {
    this.authToken = token;
  }

  setApiKey(key) {
    this.apiKey = key;
  }

  clearAuth() {
    this.authToken = null;
    this.apiKey = null;
  }

  _getHeaders(customHeaders = {}) {
    const headers = {
      'Content-Type': 'application/json',
      ...customHeaders,
    };

    if (this.authToken) {
      headers['Authorization'] = `Bearer ${this.authToken}`;
    }

    if (this.apiKey) {
      headers['X-API-Key'] = this.apiKey;
    }

    return headers;
  }

  async _request(method, path, options = {}) {
    const { body, query, headers } = options;
    const url = `${this.basePath}${path}`;
    
    let req = this.client[method](url);
    
    if (query) {
      req = req.query(query);
    }

    req = req.set(this._getHeaders(headers));

    if (body) {
      req = req.send(body);
    }

    try {
      return await req;
    } catch (error) {
      if (error.code === 'ECONNREFUSED') {
        return {
          status: 503,
          body: {
            code: 503,
            message: 'Service unavailable',
            details: { error: 'Connection refused' },
          },
        };
      }
      throw error;
    }
  }

  async get(path, query, headers) {
    return this._request('get', path, { query, headers });
  }

  async post(path, body, headers) {
    return this._request('post', path, { body, headers });
  }

  async put(path, body, headers) {
    return this._request('put', path, { body, headers });
  }

  async delete(path, headers) {
    return this._request('delete', path, { headers });
  }

  async patch(path, body, headers) {
    return this._request('patch', path, { body, headers });
  }

  async login(username, password) {
    const response = await this.post('/auth/login', { username, password });
    if (response.status === 200 && response.body.data?.access_token) {
      this.setAuthToken(response.body.data.access_token);
    }
    return response;
  }

  async register(userData) {
    return this.post('/auth/register', userData);
  }

  async createFeature(featureData) {
    return this.post('/features', featureData);
  }

  async getFeatures(query) {
    return this.get('/features', query);
  }

  async getFeature(featureId) {
    return this.get(`/features/${featureId}`);
  }

  async updateFeature(featureId, featureData) {
    return this.put(`/features/${featureId}`, featureData);
  }

  async deleteFeature(featureId) {
    return this.delete(`/features/${featureId}`);
  }

  async getOnlineFeatures(requestData) {
    return this.post('/features/online', requestData);
  }

  async getOfflineFeatures(requestData) {
    return this.post('/features/offline', requestData);
  }

  async checkFeatureConsistency(requestData) {
    return this.post('/features/check-consistency', requestData);
  }

  async createMetricSnapshot(snapshotData) {
    return this.post('/monitoring/snapshots', snapshotData);
  }

  async getMetricSnapshots(query) {
    return this.get('/monitoring/snapshots', query);
  }

  async getMetrics() {
    return this.get('/monitoring/metrics');
  }

  async getAuditLogs(query) {
    return this.get('/monitoring/audit-logs', query);
  }

  async executeTask(taskData) {
    return this.post('/core/tasks/execute', taskData);
  }

  async getTaskResult(taskId) {
    return this.get(`/core/tasks/${taskId}/result`);
  }

  async batchOperations(requestData) {
    return this.post('/core/resources/batch', requestData);
  }

  async createResource(resourceData) {
    return this.post('/core/resources', resourceData);
  }

  async getResourceStatus(resourceId) {
    return this.get(`/core/resources/${resourceId}/status`);
  }

  async withRetry(fn, retries = 3, delay = 1000) {
    for (let i = 0; i < retries; i++) {
      try {
        return await fn();
      } catch (error) {
        if (i === retries - 1) throw error;
        await new Promise(resolve => setTimeout(resolve, delay));
      }
    }
  }
}

const createClient = (baseUrl, apiVersion) => new ApiClient(baseUrl, apiVersion);

module.exports = {
  ApiClient,
  createClient,
  default: createClient,
};
