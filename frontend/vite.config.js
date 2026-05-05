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
  getEventOverview: (eventId) => api.get(`/events/${eventId}/analytics/overview`),
  getRegistrationTrend: (eventId, params = {}) => api.get(`/events/${eventId}/analytics/registrations`, { params }),
  getTicketSales: (eventId) => api.get(`/events/${eventId}/analytics/tickets`),
  getCheckInStats: (eventId) => api.get(`/events/${eventId}/analytics/checkin`),
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
