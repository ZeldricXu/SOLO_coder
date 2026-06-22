import axios from 'axios'
import type {
  User,
  Room,
  Booking,
  MeetingDoc,
  Todo,
  CheckIn,
  Notification,
  NotificationPreference,
  QRCodeData,
  RoomUsageStat,
  MeetingHoursStat,
  HeatmapData,
  EfficiencyStat,
  DisplayInfo,
} from '@/types'

const api = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export const authApi = {
  login: (email: string, password: string) =>
    api.post<{ token: string; user: User }>('/auth/login', { email, password }),
  me: () => api.get<User>('/auth/me'),
}

export const roomApi = {
  list: (params?: { floor?: number; status?: string; search?: string }) =>
    api.get<Room[]>('/rooms', { params }),
  get: (id: string) => api.get<Room>(`/rooms/${id}`),
  create: (data: Partial<Room>) => api.post<Room>('/rooms', data),
  update: (id: string, data: Partial<Room>) => api.put<Room>(`/rooms/${id}`, data),
  delete: (id: string) => api.delete(`/rooms/${id}`),
  getBookings: (id: string, date?: string) =>
    api.get<Booking[]>(`/rooms/${id}/bookings`, { params: { date } }),
  calendar: (id: string, start?: string, end?: string) =>
    api.get<Booking[]>(`/rooms/${id}/calendar`, { params: { start, end } }),
  displayInfo: (id: string) => api.get<DisplayInfo>(`/rooms/${id}/display`),
}

export const bookingApi = {
  list: (params?: { room_id?: string; status?: string; date?: string; page?: number; page_size?: number }) =>
    api.get<{ data: Booking[]; total: number; page: number; page_size: number }>('/bookings', { params }),
  myBookings: (status?: string) =>
    api.get<Booking[]>('/bookings/my', { params: { status } }),
  get: (id: string) => api.get<Booking>(`/bookings/${id}`),
  create: (data: {
    room_id: string
    title: string
    description?: string
    start_time: string
    end_time: string
    recurring_rule?: string
    attendees?: string[]
  }) => api.post('/bookings', data),
  update: (id: string, data: Partial<Booking>) => api.put<Booking>(`/bookings/${id}`, data),
  cancel: (id: string) => api.delete(`/bookings/${id}`),
  checkConflict: (data: { room_id: string; start_time: string; end_time: string }) =>
    api.post<{ conflict: boolean }>('/bookings/check-conflict', data),
  approve: (id: string) => api.post(`/bookings/${id}/approve`),
  reject: (id: string, reason: string) => api.post(`/bookings/${id}/reject`, { reason }),
}

export const meetingDocApi = {
  getByBooking: (bookingId: string) =>
    api.get<MeetingDoc>(`/meeting-docs/booking/${bookingId}`),
  update: (id: string, data: Partial<MeetingDoc>) =>
    api.put<MeetingDoc>(`/meeting-docs/${id}`, data),
  archive: (id: string) => api.post<MeetingDoc>(`/meeting-docs/${id}/archive`),
}

export const todoApi = {
  listByDoc: (docId: string) => api.get<Todo[]>(`/meeting-docs/${docId}/todos`),
  create: (docId: string, data: { content: string; assignee_id: string; due_date?: string; priority?: number }) =>
    api.post<Todo>(`/meeting-docs/${docId}/todos`, data),
  myTodos: (status?: string) => api.get<Todo[]>('/todos/my', { params: { status } }),
  update: (id: string, data: Partial<Todo>) => api.put<Todo>(`/todos/${id}`, data),
  delete: (id: string) => api.delete(`/todos/${id}`),
}

export const checkInApi = {
  getQRCode: (bookingId: string) => api.get<QRCodeData>(`/check-in/qr/${bookingId}`),
  checkIn: (token: string, bookingId?: string) =>
    api.post('/check-in', { token, booking_id: bookingId }),
  getCheckInList: (bookingId: string) => api.get<CheckIn[]>(`/check-in/booking/${bookingId}`),
}

export const notificationApi = {
  list: (params?: { status?: string; type?: string }) =>
    api.get<Notification[]>('/notifications', { params }),
  markRead: (id: string) => api.post(`/notifications/read/${id}`),
  markAllRead: () => api.post('/notifications/read-all'),
  getPreferences: () => api.get<NotificationPreference>('/notifications/preferences'),
  updatePreferences: (data: Partial<NotificationPreference>) =>
    api.put<NotificationPreference>('/notifications/preferences', data),
}

export const statsApi = {
  roomUsage: (start_date?: string, end_date?: string) =>
    api.get<RoomUsageStat[]>('/stats/room-usage', { params: { start_date, end_date } }),
  meetingHours: (start_date?: string, end_date?: string) =>
    api.get<MeetingHoursStat[]>('/stats/meeting-hours', { params: { start_date, end_date } }),
  attendance: (start_date?: string, end_date?: string) =>
    api.get('/stats/attendance', { params: { start_date, end_date } }),
  heatmap: (start_date?: string, end_date?: string) =>
    api.get<HeatmapData[]>('/stats/heatmap', { params: { start_date, end_date } }),
  efficiency: (start_date?: string, end_date?: string) =>
    api.get<EfficiencyStat[]>('/stats/efficiency', { params: { start_date, end_date } }),
}

export const userApi = {
  list: (params?: { department?: string; search?: string }) =>
    api.get<User[]>('/users', { params }),
  get: (id: string) => api.get<User>(`/users/${id}`),
}

export default api
