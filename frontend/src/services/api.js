import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:3001/api/v1';

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
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
    if (response.data.code === 200) {
      return response.data;
    }
    return Promise.reject(new Error(response.data.message || 'Request failed'));
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export const eventApi = {
  getEvents: (params = {}) => api.get('/events', { params }),
  getEvent: (eventId) => api.get(`/events/${eventId}`),
  getEventDetails: (eventId) => api.get(`/events/${eventId}/details`),
  createEvent: (data) => api.post('/events', data),
  updateEvent: (eventId, data) => api.put(`/events/${eventId}`, data),
  deleteEvent: (eventId) => api.delete(`/events/${eventId}`),
  publishEvent: (eventId) => api.post(`/events/${eventId}/publish`),
  closeEvent: (eventId) => api.post(`/events/${eventId}/close`),
  cancelEvent: (eventId) => api.post(`/events/${eventId}/cancel`),
};

export const ticketApi = {
  getTickets: (eventId) => api.get(`/events/${eventId}/tickets`),
  createTicket: (eventId, data) => api.post(`/events/${eventId}/tickets`, data),
  updateTicket: (eventId, ticketId, data) => api.put(`/events/${eventId}/tickets/${ticketId}`, data),
  deleteTicket: (eventId, ticketId) => api.delete(`/events/${eventId}/tickets/${ticketId}`),
};

export const formFieldApi = {
  getFormFields: (eventId) => api.get(`/events/${eventId}/form-fields`),
  createFormField: (eventId, data) => api.post(`/events/${eventId}/form-fields`, data),
  updateFormField: (eventId, fieldId, data) => api.put(`/events/${eventId}/form-fields/${fieldId}`, data),
  deleteFormField: (eventId, fieldId) => api.delete(`/events/${eventId}/form-fields/${fieldId}`),
  reorderFormFields: (eventId, data) => api.post(`/events/${eventId}/form-fields/reorder`, data),
};

export const registrationApi = {
  submit: (data) => api.post('/registrations/submit', data),
  getRegistration: (registrationId) => api.get(`/registrations/${registrationId}`),
  getRegistrations: (eventId, params = {}) => api.get(`/events/${eventId}/registrations`, { params }),
  getPendingReviews: (params = {}) => api.get('/registrations/pending', { params }),
  processReview: (registrationId, data) => api.put(`/registrations/${registrationId}/review`, data),
  getRegistrationStats: (eventId) => api.get(`/events/${eventId}/registrations/stats`),
  getEventWithRegistrations: (eventId) => api.get(`/events/${eventId}/registrations/full`),
};

export const checkInApi = {
  checkIn: (data) => api.post('/checkins', data),
  getCheckIns: (eventId) => api.get(`/events/${eventId}/checkins`),
  batchCheckIn: (eventId, data) => api.post(`/events/${eventId}/checkins/batch`, data),
  getCheckInStats: (eventId) => api.get(`/checkins/stats/${eventId}`),
};

export const analyticsApi = {
  getEventOverview: (eventId) => api.get(`/analytics/event/${eventId}/overview`),
  getRegistrationTrend: (eventId, params = {}) => api.get(`/analytics/event/${eventId}/registrations`, { params }),
  getTicketSales: (eventId) => api.get(`/analytics/event/${eventId}/tickets`),
  getCheckInStats: (eventId) => api.get(`/analytics/event/${eventId}/checkin`),
  getRevenueStats: (eventId, params = {}) => api.get(`/analytics/event/${eventId}/revenue`, { params }),
  getAvailableDimensions: () => api.get('/analytics/dimensions'),
  getAvailableMetrics: () => api.get('/analytics/metrics'),
  getAvailableChartTypes: () => api.get('/analytics/chart-types'),
  getTemplates: (params = {}) => api.get('/analytics/templates', { params }),
  getCustomReport: (data) => api.post('/analytics/custom', data),
  getReportFromTemplate: (data) => api.post('/analytics/from-template', data),
};

export const reportConfigApi = {
  getTemplates: (params = {}) => api.get('/reports/templates', { params }),
  getTemplate: (templateId) => api.get(`/reports/templates/${templateId}`),
  getConfigs: (params = {}) => api.get('/reports', { params }),
  getConfig: (configId) => api.get(`/reports/${configId}`),
  createConfig: (data) => api.post('/reports', data),
  updateConfig: (configId, data) => api.put(`/reports/${configId}`, data),
  deleteConfig: (configId) => api.delete(`/reports/${configId}`),
  getAvailableDimensions: () => api.get('/reports/dimensions'),
  getAvailableMetrics: () => api.get('/reports/metrics'),
  getAvailableChartTypes: () => api.get('/reports/chart-types'),
  generateReport: (configId, data) => api.post(`/reports/${configId}/generate`, data),
  generateCustomReport: (data) => api.post('/reports/custom', data),
};

export const queueApi = {
  getStats: () => api.get('/queue/stats'),
  getPending: () => api.get('/queue/pending'),
  processQueue: () => api.post('/queue/process'),
  enqueueEmail: (data) => api.post('/queue/email', data),
  enqueueSMS: (data) => api.post('/queue/sms', data),
  cancelMessage: (queueId) => api.post(`/queue/${queueId}/cancel`),
  retryMessage: (queueId) => api.post(`/queue/${queueId}/retry`),
};

export const authApi = {
  login: (data) => api.post('/auth/login', data),
  register: (data) => api.post('/auth/register', data),
  getCurrentUser: () => api.get('/auth/me'),
  logout: () => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
  },
};

export default api;
