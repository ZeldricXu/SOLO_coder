import axios from 'axios';

const API_BASE_URL = '/api/v1';

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

api.interceptors.response.use(
  (response) => {
    return response.data;
  },
  (error) => {
    console.error('API请求错误:', error);
    return Promise.reject(error);
  }
);

export const analysisApi = {
  analyzeCommit: (data) => api.post('/analysis/commit', data),
  
  getComplexityAnalysis: (analysis_id) => api.get(`/analysis/complexity/${analysis_id}`),
  
  getComplexityByCommit: (commit_id) => api.get(`/analysis/complexity/commit/${commit_id}`),
  
  getLintResults: (commit_id) => api.get(`/analysis/lint/${commit_id}`),
  
  getDuplicateResults: (commit_id) => api.get(`/analysis/duplicate/${commit_id}`)
};

export const reviewApi = {
  getTasks: (params) => api.get('/review/tasks', { params }),
  
  createTask: (data) => api.post('/review/tasks', data),
  
  getTask: (task_id) => api.get(`/review/tasks/${task_id}`),
  
  assignTask: (task_id, assignee) => api.put(`/review/tasks/${task_id}/assign`, { assignee }),
  
  updateTaskStatus: (task_id, status) => api.put(`/review/tasks/${task_id}/status`, { status }),
  
  getTasksByCommit: (commit_id) => api.get(`/review/tasks/commit/${commit_id}`),
  
  createComment: (data) => api.post('/review/comment', data),
  
  getComment: (comment_id) => api.get(`/review/comments/${comment_id}`),
  
  getCommentsByCommit: (commit_id, include_replies = true) => 
    api.get(`/review/comments/commit/${commit_id}`, { params: { include_replies } }),
  
  getCommentsByFile: (commit_id, file_path) => 
    api.get(`/review/comments/file/${commit_id}`, { params: { file_path } }),
  
  updateCommentStatus: (comment_id, status) => 
    api.put(`/review/comments/${comment_id}/status`, { status }),
  
  replyComment: (comment_id, data) => 
    api.post(`/review/comments/${comment_id}/reply`, data),
  
  deleteComment: (comment_id) => api.delete(`/review/comments/${comment_id}`),
  
  getStatistics: (params) => api.get('/review/statistics', { params })
};

export const reportApi = {
  generateReport: (data) => api.post('/report/generate', data),
  
  getQualityReport: (params) => api.get('/report/quality', { params }),
  
  getTrend: (params) => api.get('/report/trend', { params }),
  
  getReport: (report_id) => api.get(`/report/${report_id}`),
  
  getReportByCommit: (commit_id) => api.get(`/report/commit/${commit_id}`),
  
  getReportsByRepo: (repo_id, params) => api.get(`/report/repo/${repo_id}`, { params }),
  
  getLatestReport: (repo_id) => api.get(`/report/latest/${repo_id}`),
  
  getStatistics: (params) => api.get('/report/statistics', { params })
};

export const codeApi = {
  getCommit: (commit_id) => api.get(`/code/commits/${commit_id}`),
  
  getCommitsByRepo: (repo_id, params) => api.get(`/code/commits/repo/${repo_id}`, { params }),
  
  getFiles: (commit_id) => api.get(`/code/files/${commit_id}`),
  
  getFile: (commit_id, file_path) => api.get(`/code/files/${commit_id}/${file_path}`),
  
  getFileDiff: (commit_id, file_path) => api.get(`/code/diff/${commit_id}/${file_path}`),
  
  getCommitDiff: (commit_id) => api.get(`/code/diff/compare/${commit_id}`),
  
  sendWebhook: (data) => api.post('/code/webhook', data)
};

export const healthApi = {
  check: () => axios.get('/health')
};

export default {
  analysisApi,
  reviewApi,
  reportApi,
  codeApi,
  healthApi
};
