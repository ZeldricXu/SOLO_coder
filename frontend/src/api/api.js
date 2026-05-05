import axios from 'axios';

const API_BASE_URL = '/api/v1';

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
    'X-User-Id': localStorage.getItem('userId') || 'user_001'
  }
});

api.interceptors.request.use(
  (config) => {
    const userId = localStorage.getItem('userId') || 'user_001';
    config.headers['X-User-Id'] = userId;
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

api.interceptors.response.use(
  (response) => {
    if (response.data.code === 200) {
      return response.data;
    }
    throw new Error(response.data.error || '请求失败');
  },
  (error) => {
    const message = error.response?.data?.error || error.message || '网络错误';
    throw new Error(message);
  }
);

export const documentApi = {
  create: (data) => api.post('/docs/create', data),
  list: (params) => api.get('/docs/list', { params }),
  get: (docId) => api.get(`/docs/${docId}`),
  edit: (docId, data) => api.post(`/docs/${docId}/edit`, data),
  delete: (docId) => api.delete(`/docs/${docId}`),
  updateStatus: (docId, status) => api.post(`/docs/${docId}/status`, { status })
};

export const versionApi = {
  getHistory: (docId, params) => api.get(`/versions/${docId}/history`, { params }),
  getVersion: (docId, version) => api.get(`/versions/${docId}/${version}`),
  compare: (docId, version1, version2) => api.get(`/versions/${docId}/compare/${version1}/${version2}`),
  restore: (docId, version) => api.post(`/versions/${docId}/restore/${version}`),
  create: (docId, data) => api.post(`/versions/${docId}/create`, data)
};

export const searchApi = {
  search: (params) => api.get('/search/search', { params }),
  quick: (keyword, limit = 10) => api.get('/search/quick', { params: { keyword, limit } }),
  recent: (limit = 10) => api.get('/search/recent', { params: { limit } }),
  byCategory: (categoryName, params) => api.get(`/search/category/${encodeURIComponent(categoryName)}`, { params }),
  byTags: (tags, params) => api.get('/search/tags', { params: { tags: tags.join(','), ...params } })
};

export const categoryApi = {
  create: (data) => api.post('/categories/create', data),
  list: (includeParent = false) => api.get('/categories/list', { params: { include_parent: includeParent } }),
  get: (categoryId) => api.get(`/categories/${categoryId}`),
  update: (categoryId, data) => api.put(`/categories/${categoryId}`, data),
  delete: (categoryId, moveTo) => api.delete(`/categories/${categoryId}`, { params: { move_to: moveTo } }),
  popularTags: (limit = 20) => api.get('/categories/tags/popular', { params: { limit } }),
  addTags: (docId, tags) => api.post(`/categories/${docId}/tags`, { tags }),
  removeTag: (docId, tag) => api.delete(`/categories/${docId}/tags/${encodeURIComponent(tag)}`)
};

export const shareApi = {
  share: (docId, data) => api.post(`/shares/${docId}`, data),
  list: (docId) => api.get(`/shares/${docId}`),
  revoke: (shareId) => api.delete(`/shares/${shareId}`),
  checkAccess: (docId) => api.get(`/shares/access/${docId}`)
};

export const commentApi = {
  create: (docId, data) => api.post(`/comments/${docId}`, data),
  list: (docId, status = 'all') => api.get(`/comments/${docId}`, { params: { status } }),
  update: (commentId, content) => api.put(`/comments/${commentId}`, { content }),
  delete: (commentId) => api.delete(`/comments/${commentId}`),
  resolve: (commentId) => api.post(`/comments/${commentId}/resolve`),
  close: (commentId) => api.post(`/comments/${commentId}/close`)
};

export const favoriteApi = {
  toggle: (docId) => api.post(`/favorites/${docId}/toggle`),
  list: (params) => api.get('/favorites/list', { params }),
  check: (docId) => api.get(`/favorites/${docId}/check`)
};

export default api;
