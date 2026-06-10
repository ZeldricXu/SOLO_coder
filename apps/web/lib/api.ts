import axios from 'axios';
import type {
  Model,
  ModelVersion,
  Experiment,
  ExperimentRun,
  FeatureSet,
  ABTest,
  Alert,
  InferenceResponse,
  InferenceGatewayStatus,
  PaginatedResponse,
} from '@mlops/shared';

const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL || 'http://localhost:3001',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('API Error:', error.message);
    return Promise.reject(error);
  }
);

export const api = {
  health: () => apiClient.get('/api/v1/health'),

  models: {
    list: (params?: Record<string, unknown>) =>
      apiClient.get<PaginatedResponse<Model>>('/api/v1/models', { params }),
    get: (id: string) => apiClient.get<Model>(`/api/v1/models/${id}`),
    create: (data: unknown) => apiClient.post<Model>('/api/v1/models', data),
    listVersions: (modelId: string, params?: Record<string, unknown>) =>
      apiClient.get<PaginatedResponse<ModelVersion>>(`/api/v1/models/${modelId}/versions`, { params }),
    getVersion: (versionId: string) => apiClient.get<ModelVersion>(`/api/v1/versions/${versionId}`),
    createVersion: (modelId: string, formData: FormData) =>
      apiClient.post<ModelVersion>(`/api/v1/models/${modelId}/versions`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      }),
    downloadVersion: (versionId: string) =>
      apiClient.get(`/api/v1/versions/${versionId}/download`, { responseType: 'blob' }),
    updateStatus: (id: string, status: string) =>
      apiClient.patch<Model>(`/api/v1/models/${id}/status`, { status }),
    delete: (id: string) => apiClient.delete(`/api/v1/models/${id}`),
    load: (id: string, versionId?: string) =>
      apiClient.post(`/api/v1/models/${id}/load`, { versionId }),
    unload: (id: string, versionId: string) =>
      apiClient.post(`/api/v1/models/${id}/unload`, { versionId }),
  },

  inference: {
    predict: (data: unknown) => apiClient.post<InferenceResponse>('/api/v1/inference', data),
    batchPredict: (data: unknown) => apiClient.post<InferenceResponse>('/api/v1/inference/batch', data),
    status: () => apiClient.get<InferenceGatewayStatus>('/api/v1/inference/status'),
  },

  experiments: {
    list: (params?: Record<string, unknown>) =>
      apiClient.get<PaginatedResponse<Experiment>>('/api/v1/experiments', { params }),
    get: (id: string) => apiClient.get<Experiment>(`/api/v1/experiments/${id}`),
    create: (data: unknown) => apiClient.post<Experiment>('/api/v1/experiments', data),
    listRuns: (params?: Record<string, unknown>) =>
      apiClient.get<PaginatedResponse<ExperimentRun>>('/api/v1/runs', { params }),
    getRun: (id: string) => apiClient.get<ExperimentRun>(`/api/v1/runs/${id}`),
    createRun: (data: unknown) => apiClient.post<ExperimentRun>('/api/v1/runs', data),
    updateRun: (id: string, data: unknown) =>
      apiClient.patch<ExperimentRun>(`/api/v1/runs/${id}`, data),
    getMetricChart: (runIds: string[], metricName: string) =>
      apiClient.get('/api/v1/runs/metrics/chart', { params: { runIds: runIds.join(','), metricName } }),
    compareRuns: (runIds: string[]) =>
      apiClient.post('/api/v1/runs/compare', { runIds }),
    getLineage: (runId: string, depth?: number) =>
      apiClient.get(`/api/v1/runs/${runId}/lineage`, { params: { depth } }),
    getEvolutionTree: (runId: string, params?: Record<string, unknown>) =>
      apiClient.get(`/api/v1/runs/${runId}/evolution-tree`, { params }),
    getExperimentEvolutionTree: (experimentId: string, params?: Record<string, unknown>) =>
      apiClient.get(`/api/v1/experiments/${experimentId}/evolution-tree`, { params }),
    compareLineage: (data: unknown) =>
      apiClient.post('/api/v1/lineage/compare', data),
    forkExperiment: (data: unknown) =>
      apiClient.post('/api/v1/experiments/fork', data),
    getLineageStats: (projectId?: string) =>
      apiClient.get('/api/v1/lineage/stats', { params: { projectId } }),
  },

  pipelines: {
    list: (params?: Record<string, unknown>) =>
      apiClient.get('/api/v1/pipelines', { params }),
    get: (id: string) => apiClient.get(`/api/v1/pipelines/${id}`),
    create: (data: unknown) => apiClient.post('/api/v1/pipelines', data),
    update: (id: string, data: unknown) =>
      apiClient.patch(`/api/v1/pipelines/${id}`, data),
    delete: (id: string) => apiClient.delete(`/api/v1/pipelines/${id}`),
    validate: (id: string) => apiClient.post(`/api/v1/pipelines/${id}/validate`),
    run: (data: unknown) => apiClient.post('/api/v1/pipelines/run', data),
  },

  vectorSearch: {
    createIndex: (data: unknown) => apiClient.post('/api/v1/vector-indexes', data),
    getIndex: (id: string) => apiClient.get(`/api/v1/vector-indexes/${id}`),
    listIndexes: (featureSetId: string) =>
      apiClient.get(`/api/v1/feature-sets/${featureSetId}/vector-indexes`),
    deleteIndex: (id: string) => apiClient.delete(`/api/v1/vector-indexes/${id}`),
    search: (data: unknown) => apiClient.post('/api/v1/vector-search', data),
    rangeQuery: (data: unknown) => apiClient.post('/api/v1/vector-search/range', data),
    ingest: (data: unknown) => apiClient.post('/api/v1/vector-indexes/ingest', data),
    getStats: () => apiClient.get('/api/v1/vector-stats'),
  },

  features: {
    list: (params?: Record<string, unknown>) =>
      apiClient.get<PaginatedResponse<FeatureSet>>('/api/v1/feature-sets', { params }),
    get: (id: string) => apiClient.get<FeatureSet>(`/api/v1/feature-sets/${id}`),
    create: (data: unknown) => apiClient.post<FeatureSet>('/api/v1/feature-sets', data),
    createVersion: (id: string, data: unknown) =>
      apiClient.post(`/api/v1/feature-sets/${id}/versions`, data),
    getFeatures: (data: unknown) => apiClient.post('/api/v1/features/get', data),
    ingest: (data: unknown) => apiClient.post('/api/v1/features/ingest', data),
    getDistribution: (id: string, featureName: string) =>
      apiClient.get(`/api/v1/feature-sets/${id}/features/${featureName}/distribution`),
  },

  abtests: {
    list: (params?: Record<string, unknown>) =>
      apiClient.get<PaginatedResponse<ABTest>>('/api/v1/ab-tests', { params }),
    get: (id: string) => apiClient.get<ABTest>(`/api/v1/ab-tests/${id}`),
    create: (data: unknown) => apiClient.post<ABTest>('/api/v1/ab-tests', data),
    update: (id: string, data: unknown) => apiClient.patch<ABTest>(`/api/v1/ab-tests/${id}`, data),
    assign: (data: unknown) => apiClient.post('/api/v1/ab-tests/assign', data),
    track: (data: unknown) => apiClient.post('/api/v1/ab-tests/track', data),
    getResults: (id: string) => apiClient.post(`/api/v1/ab-tests/${id}/results`),
    getStats: (id: string) => apiClient.get(`/api/v1/ab-tests/${id}/stats`),
  },

  monitoring: {
    listAlerts: (params?: Record<string, unknown>) =>
      apiClient.get<PaginatedResponse<Alert>>('/api/v1/alerts', { params }),
    getAlert: (id: string) => apiClient.get<Alert>(`/api/v1/alerts/${id}`),
    createAlert: (data: unknown) => apiClient.post<Alert>('/api/v1/alerts', data),
    updateAlertStatus: (id: string, data: unknown) =>
      apiClient.patch<Alert>(`/api/v1/alerts/${id}/status`, data),
    createDriftConfig: (data: unknown) => apiClient.post('/api/v1/drift-configs', data),
    runDriftDetection: (id: string) => apiClient.post(`/api/v1/drift-configs/${id}/run`),
    getLatencyMetrics: (params: Record<string, unknown>) =>
      apiClient.get('/api/v1/metrics/latency', { params }),
    getDashboard: (params: Record<string, unknown>) =>
      apiClient.get('/api/v1/monitoring/dashboard', { params }),
  },
};

export default apiClient;
