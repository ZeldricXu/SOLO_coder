import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/store/user'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use(
  (config) => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

request.interceptors.response.use(
  (response) => {
    return response.data
  },
  (error) => {
    if (error.response) {
      if (error.response.status === 401) {
        const userStore = useUserStore()
        userStore.logout()
        router.push('/login')
        ElMessage.error('登录已过期，请重新登录')
      } else if (error.response.data?.message) {
        ElMessage.error(error.response.data.message)
      } else {
        ElMessage.error('请求失败')
      }
    }
    return Promise.reject(error)
  }
)

export const authAPI = {
  login: (data) => request.post('/auth/login', data),
  logout: () => request.post('/auth/logout'),
  getCurrentUser: () => request.get('/auth/me')
}

export const pipelineAPI = {
  list: (projectId, params) => request.get('/pipelines', { params: { projectId, ...params } }),
  get: (id) => request.get(`/pipelines/${id}`),
  create: (data) => request.post('/pipelines', data),
  update: (id, data) => request.put(`/pipelines/${id}`, data),
  delete: (id) => request.delete(`/pipelines/${id}`),
  trigger: (id, data) => request.post(`/pipelines/${id}/trigger`, data),
  listExecutions: (pipelineId, params) => request.get(`/pipelines/${pipelineId}/executions`, { params }),
  getExecution: (pipelineId, executionId) => request.get(`/pipelines/${pipelineId}/executions/${executionId}`),
  cancelExecution: (pipelineId, executionId) => request.post(`/pipelines/${pipelineId}/executions/${executionId}/cancel`),
  listTemplates: () => request.get('/pipelines/templates'),
  getTemplate: (id) => request.get(`/pipelines/templates/${id}`),
  validateYaml: (yaml) => request.post('/pipelines/validate', { yamlDefinition: yaml })
}

export const deploymentAPI = {
  list: (projectId, params) => request.get('/deployments', { params: { projectId, ...params } }),
  get: (id) => request.get(`/deployments/${id}`),
  rollback: (id) => request.post(`/deployments/${id}/rollback`)
}

export const artifactAPI = {
  list: (projectId, params) => request.get('/artifacts', { params: { projectId, ...params } }),
  get: (id) => request.get(`/artifacts/${id}`),
  trace: (params) => request.get('/artifacts/trace', { params }),
  listHistory: (projectId, name) => request.get('/artifacts/history', { params: { projectId, name } }),
  pin: (id) => request.post(`/artifacts/${id}/pin`),
  unpin: (id) => request.post(`/artifacts/${id}/unpin`),
  delete: (id) => request.delete(`/artifacts/${id}`),
  triggerCleanup: () => request.post('/artifacts/cleanup')
}

export const approvalAPI = {
  get: (id) => request.get(`/approvals/${id}`),
  pending: () => request.get('/approvals/pending'),
  history: (projectId, params) => request.get(`/approvals/project/${projectId}`, { params }),
  approve: (id, data) => request.post(`/approvals/${id}/approve`, data),
  reject: (id, data) => request.post(`/approvals/${id}/reject`, data)
}

export const dashboardAPI = {
  overview: (projectId) => request.get('/dashboard/overview', { params: { projectId } }),
  pipelineStats: (projectId, range) => request.get('/dashboard/pipeline-stats', { params: { projectId, range } }),
  doraMetrics: (projectId, range) => request.get('/dashboard/dora-metrics', { params: { projectId, range } }),
  environmentVersions: (projectId) => request.get('/dashboard/environment-versions', { params: { projectId } })
}

export const projectAPI = {
  list: (params) => request.get('/projects', { params }),
  get: (id) => request.get(`/projects/${id}`),
  create: (data) => request.post('/projects', data),
  update: (id, data) => request.put(`/projects/${id}`, data),
  delete: (id) => request.delete(`/projects/${id}`)
}

export const environmentAPI = {
  list: (projectId) => request.get('/environments', { params: { projectId } }),
  get: (id) => request.get(`/environments/${id}`),
  create: (data) => request.post('/environments', data),
  update: (id, data) => request.put(`/environments/${id}`, data),
  delete: (id) => request.delete(`/environments/${id}`)
}

export const runnerAPI = {
  list: (params) => request.get('/runners', { params }),
  get: (id) => request.get(`/runners/${id}`),
  create: (data) => request.post('/runners', data),
  update: (id, data) => request.put(`/runners/${id}`, data),
  delete: (id) => request.delete(`/runners/${id}`)
}

export const logsAPI = {
  getLogs: (stepId, params) => request.get(`/logs/${stepId}`, { params }),
  streamLogs: (executionId) => `/ws/logs/${executionId}`
}

export default request
