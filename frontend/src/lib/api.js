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
  (response) => response,
  (error) => {
    console.error('API Error:', error);
    return Promise.reject(error);
  }
);

export const documentApi = {
  async getAll(folderId = null, offset = 0, limit = 20) {
    const params = { offset, limit };
    if (folderId) params.folder_id = folderId;
    
    const response = await api.get('/documents', { params });
    return response.data;
  },
  
  async get(docId) {
    const response = await api.get(`/documents/${docId}`);
    return response.data;
  },
  
  async create(data) {
    const response = await api.post('/documents', data);
    return response.data;
  },
  
  async update(docId, data) {
    const response = await api.put(`/documents/${docId}`, data);
    return response.data;
  },
  
  async delete(docId) {
    const response = await api.delete(`/documents/${docId}`);
    return response.data;
  },
  
  async addCollaborator(docId, userId) {
    const response = await api.post(`/documents/${docId}/collaborators`, { user_id: userId });
    return response.data;
  },
  
  async removeCollaborator(docId, userId) {
    const response = await api.delete(`/documents/${docId}/collaborators/${userId}`);
    return response.data;
  },
  
  async lock(docId) {
    const response = await api.post(`/documents/${docId}/lock`);
    return response.data;
  },
  
  async unlock(docId) {
    const response = await api.post(`/documents/${docId}/unlock`);
    return response.data;
  }
};

export const folderApi = {
  async getAll(parentId = null) {
    const params = parentId ? { parent_id: parentId } : {};
    const response = await api.get('/folders', { params });
    return response.data;
  },
  
  async getTree() {
    const response = await api.get('/folders/tree');
    return response.data;
  },
  
  async get(folderId) {
    const response = await api.get(`/folders/${folderId}`);
    return response.data;
  },
  
  async create(data) {
    const response = await api.post('/folders', data);
    return response.data;
  },
  
  async update(folderId, data) {
    const response = await api.put(`/folders/${folderId}`, data);
    return response.data;
  },
  
  async delete(folderId, recursive = false) {
    const params = recursive ? { recursive: 'true' } : {};
    const response = await api.delete(`/folders/${folderId}`, { params });
    return response.data;
  },
  
  async move(folderId, newParentId) {
    const response = await api.post(`/folders/${folderId}/move`, { new_parent_id: newParentId });
    return response.data;
  },
  
  async reorder(parentId, orderedIds) {
    const response = await api.post('/folders/reorder', { parent_id: parentId, ordered_ids: orderedIds });
    return response.data;
  },
  
  async getPath(folderId) {
    const response = await api.get(`/folders/${folderId}/path`);
    return response.data;
  }
};

export const versionApi = {
  async get(docId, page = 1, limit = 20) {
    const params = { page, limit };
    const response = await api.get(`/versions/${docId}`, { params });
    return response.data;
  },
  
  async getVersion(docId, versionNumber) {
    const response = await api.get(`/versions/${docId}/${versionNumber}`);
    return response.data;
  },
  
  async createSnapshot(docId, userId, editSummary = '') {
    const response = await api.post(`/versions/${docId}/snapshot`, {
      user_id: userId,
      edit_summary: editSummary
    });
    return response.data;
  },
  
  async restore(docId, versionNumber, userId) {
    const response = await api.post(`/versions/${docId}/restore/${versionNumber}`, { user_id: userId });
    return response.data;
  },
  
  async compare(docId, version1, version2) {
    const response = await api.get(`/versions/${docId}/compare/${version1}/${version2}`);
    return response.data;
  },
  
  async cleanup(docId, keepRecent = 100) {
    const response = await api.post(`/versions/${docId}/cleanup`, { keep_recent: keepRecent });
    return response.data;
  }
};

export const searchApi = {
  async query(query, options = {}) {
    const params = {
      q: query,
      limit: options.limit || 20,
      offset: options.offset || 0
    };
    if (options.folder_id) params.folder_id = options.folder_id;
    if (options.user_id) params.user_id = options.user_id;
    
    const response = await api.get('/search/query', { params });
    return response.data;
  },
  
  async suggest(query) {
    const response = await api.get('/search/suggest', { params: { q: query } });
    return response.data;
  },
  
  async reindex() {
    const response = await api.post('/search/reindex');
    return response.data;
  },
  
  async getStatus() {
    const response = await api.get('/search/status');
    return response.data;
  }
};

export const commentApi = {
  async getByDocument(docId, includeResolved = false) {
    const params = { include_resolved: includeResolved };
    const response = await api.get(`/comments/doc/${docId}`, { params });
    return response.data;
  },
  
  async getStats(docId) {
    const response = await api.get(`/comments/stats/${docId}`);
    return response.data;
  },
  
  async get(commentId) {
    const response = await api.get(`/comments/${commentId}`);
    return response.data;
  },
  
  async create(data) {
    const response = await api.post('/comments', data);
    return response.data;
  },
  
  async update(commentId, data) {
    const response = await api.put(`/comments/${commentId}`, data);
    return response.data;
  },
  
  async delete(commentId, userId) {
    const response = await api.delete(`/comments/${commentId}`, { params: { user_id: userId } });
    return response.data;
  },
  
  async resolve(commentId, userId) {
    const response = await api.post(`/comments/${commentId}/resolve`, { user_id: userId });
    return response.data;
  },
  
  async unresolve(commentId, userId) {
    const response = await api.post(`/comments/${commentId}/unresolve`, { user_id: userId });
    return response.data;
  },
  
  async addReply(commentId, userId, content) {
    const response = await api.post(`/comments/${commentId}/reply`, { user_id: userId, content });
    return response.data;
  },
  
  async getThread(commentId) {
    const response = await api.get(`/comments/thread/${commentId}`);
    return response.data;
  },
  
  async batchResolve(docId, commentIds, userId) {
    const response = await api.post('/comments/batch-resolve', {
      doc_id: docId,
      comment_ids: commentIds,
      user_id: userId
    });
    return response.data;
  },
  
  async getRecent(userId, limit = 20) {
    const response = await api.get(`/comments/recent/${userId}`, { params: { limit } });
    return response.data;
  }
};

export const exportApi = {
  async exportHTML(docId, options = {}) {
    const params = {};
    if (options.version_number) params.version_number = options.version_number;
    if (options.include_toc !== undefined) params.include_toc = options.include_toc;
    if (options.include_comments !== undefined) params.include_comments = options.include_comments;
    
    const response = await api.get(`/export/html/${docId}`, {
      params,
      responseType: 'blob'
    });
    return response.data;
  },
  
  async exportPDF(docId, options = {}) {
    const params = {};
    if (options.version_number) params.version_number = options.version_number;
    if (options.include_toc !== undefined) params.include_toc = options.include_toc;
    if (options.include_comments !== undefined) params.include_comments = options.include_comments;
    if (options.format) params.format = options.format;
    if (options.orientation) params.orientation = options.orientation;
    
    const response = await api.get(`/export/pdf/${docId}`, {
      params,
      responseType: 'blob'
    });
    return response.data;
  },
  
  async preview(docId, options = {}) {
    const params = {};
    if (options.version_number) params.version_number = options.version_number;
    if (options.include_toc !== undefined) params.include_toc = options.include_toc;
    
    const response = await api.get(`/export/preview/${docId}`, { params });
    return response.data;
  },
  
  async getFormats() {
    const response = await api.get('/export/formats');
    return response.data;
  },
  
  async batchExport(docIds, format, options = {}) {
    const response = await api.post('/export/batch', {
      doc_ids: docIds,
      format,
      options
    });
    return response.data;
  }
};

export const healthApi = {
  async check() {
    const response = await api.get('/health');
    return response.data;
  },
  
  async getStatus() {
    const response = await api.get('/status');
    return response.data;
  }
};

export default api;
