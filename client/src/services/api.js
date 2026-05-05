import axios from 'axios';

const API_BASE_URL = process.env.REACT_APP_API_URL || '/api/v1';

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
});

api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

api.interceptors.response.use(
  (response) => {
    return response;
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export const authAPI = {
  login: (email, password) => 
    api.post('/auth/login', { email, password }),
  
  register: (username, email, password) => 
    api.post('/auth/register', { username, email, password }),
  
  getCurrentUser: () => 
    api.get('/auth/me'),
};

export const taskAPI = {
  getTasks: (params = {}) => 
    api.get('/tasks', { params }),
  
  getTaskById: (taskId) => 
    api.get(`/tasks/${taskId}`),
  
  createTask: (taskData) => 
    api.post('/tasks/create', taskData),
  
  updateStatus: (taskId, newStatus, progress, version) => 
    api.put('/tasks/status', { task_id: taskId, new_status: newStatus, progress, version }),
  
  getGanttData: (params = {}) => 
    api.get('/tasks/gantt', { params }),
  
  getAvailableTransitions: (currentStatus) => 
    api.get('/tasks/transitions/available', { params: { current_status: currentStatus } }),
  
  addDependency: (taskId, prerequisiteTaskId, dependencyType = 'finish_to_start', lagDays = 0) => 
    api.post(`/tasks/${taskId}/dependencies`, { 
      prerequisite_task_id: prerequisiteTaskId, 
      dependency_type: dependencyType, 
      lag_days: lagDays 
    }),
  
  removeDependency: (taskId, prerequisiteTaskId) => 
    api.delete(`/tasks/${taskId}/dependencies/${prerequisiteTaskId}`),
  
  getDependencyWarnings: (taskId) => 
    api.get(`/tasks/${taskId}/dependencies/warnings`),
};

export const eventAPI = {
  getEvents: (startDate, endDate) => 
    api.get('/calendar/events', { params: { start_date: startDate, end_date: endDate } }),
  
  getEventById: (eventId) => 
    api.get(`/calendar/events/${eventId}`),
  
  createEvent: (eventData) => 
    api.post('/calendar/events/create', eventData),
  
  updateParticipation: (eventId, status) => 
    api.put(`/calendar/events/${eventId}/participation`, { status }),
};

export const fileAPI = {
  getAttachmentsByTask: (taskId) => 
    api.get(`/files/task/${taskId}`),
  
  uploadAttachment: (file, taskId) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('task_id', taskId);
    return api.post('/files/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });
  },
  
  deleteAttachment: (attachmentId) => 
    api.delete(`/files/${attachmentId}`),
  
  getDownloadUrl: (attachmentId) => 
    `${API_BASE_URL}/files/download/${attachmentId}`,
};

export const notificationAPI = {
  getUnread: () => 
    api.get('/notifications/unread'),
  
  markAsRead: (notificationId) => 
    api.put(`/notifications/${notificationId}/read`),
};

export default api;
