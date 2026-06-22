import request from '@/utils/request'

export const authApi = {
  login: (username, password) => {
    const fd = new FormData()
    fd.append('username', username)
    fd.append('password', password)
    return request.post('/api/auth/login', fd)
  },
  register: (data) => request.post('/api/auth/register', data),
  me: () => request.get('/api/auth/me')
}

export const userApi = {
  list: (params) => request.get('/api/users', { params }),
  get: (id) => request.get(`/api/users/${id}`),
  create: (data) => request.post('/api/users', data),
  update: (id, data) => request.put(`/api/users/${id}`, data),
  remove: (id) => request.delete(`/api/users/${id}`)
}

export const teamApi = {
  list: () => request.get('/api/teams'),
  get: (id) => request.get(`/api/teams/${id}`),
  create: (data) => request.post('/api/teams', data),
  update: (id, data) => request.put(`/api/teams/${id}`, data),
  remove: (id) => request.delete(`/api/teams/${id}`),
  members: (id) => request.get(`/api/teams/${id}/members`),
  getNotifySetting: (id) => request.get(`/api/teams/${id}/notification-setting`),
  updateNotifySetting: (id, data) => request.put(`/api/teams/${id}/notification-setting`, data)
}

export const templateApi = {
  list: (params) => request.get('/api/templates', { params }),
  get: (id) => request.get(`/api/templates/${id}`),
  getDefault: () => request.get('/api/templates/default'),
  versions: (id) => request.get(`/api/templates/${id}/versions`),
  create: (data) => request.post('/api/templates', data),
  update: (id, data) => request.put(`/api/templates/${id}`, data),
  remove: (id) => request.delete(`/api/templates/${id}`)
}

export const reportApi = {
  myCurrent: (week_key) => request.get('/api/reports/my-current', { params: { week_key } }),
  myHistory: () => request.get('/api/reports/my-history'),
  list: (params) => request.get('/api/reports', { params }),
  get: (id) => request.get(`/api/reports/${id}`),
  update: (id, data) => request.put(`/api/reports/${id}`, data),
  proxySubmit: (data, params) => request.post('/api/reports/proxy-submit', data, { params }),
  revoke: (id) => request.post(`/api/reports/${id}/revoke`),
  pendingUsers: (params) => request.get('/api/reports/pending/list', { params })
}

export const summaryApi = {
  list: (params) => request.get('/api/summaries', { params }),
  current: (week_key) => request.get('/api/summaries/current', { params: { week_key } }),
  get: (id) => request.get(`/api/summaries/${id}`),
  generate: (data) => request.post('/api/summaries/generate', data)
}

export const statsApi = {
  overview: (week_key) => request.get('/api/statistics/overview', { params: { week_key } }),
  trend: (weeks) => request.get('/api/statistics/submission-trend', { params: { weeks } }),
  teamRanking: (week_key) => request.get('/api/statistics/team-ranking', { params: { week_key } }),
  personal: (params) => request.get('/api/statistics/personal-stats', { params }),
  wordCloud: (week_key) => request.get('/api/statistics/word-cloud', { params: { week_key } }),
  reminderLogs: (params) => request.get('/api/statistics/reminder-logs', { params })
}

export const exportApi = {
  download: (id, format) => request.get(`/api/export/download/${id}`, {
    params: { format },
    responseType: 'blob'
  }),
  distribute: (data) => request.post('/api/export/distribute', data),
  sendReminder: (data) => request.post('/api/export/send-reminder', data)
}
