import axios from 'axios';
import type {
  App,
  Version,
  Feedback,
  Statistics,
  Notification,
  ApiResponse,
  ChartData,
  SummaryStats,
  AsyncStatsResponse,
  ClassificationResult,
  FeedbackStats,
  PermissionCheck,
} from '../types';

const API_BASE_URL = 'http://localhost:8080/api';

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('API Error:', error.message);
    if (error.response?.status === 403) {
      console.error('Permission denied:', error.response?.data?.message);
    }
    return Promise.reject(error);
  }
);

export const appApi = {
  createApp: (data: { name: string; platform: string; category: string; icon?: string; description?: string; developerId?: string }) =>
    apiClient.post<ApiResponse<{ appId: string; name: string; status: string }>>('/v1/apps/create', data),

  getApps: (params?: { developerId?: string; status?: string }) =>
    apiClient.get<ApiResponse<App[]>>('/v1/apps', { params }),

  getApp: (appId: string) =>
    apiClient.get<ApiResponse<App>>(`/v1/apps/${appId}`),

  updateApp: (appId: string, data: { name?: string; icon?: string; description?: string; category?: string; platform?: string; status?: string }) =>
    apiClient.put<ApiResponse<App>>(`/v1/apps/${appId}`, data),

  deleteApp: (appId: string) =>
    apiClient.delete<ApiResponse<void>>(`/v1/apps/${appId}`),
};

export const versionApi = {
  publish: (data: { appId: string; versionCode: string; versionName?: string; packageUrl: string; releaseNote?: string; submitter?: string }) =>
    apiClient.post<ApiResponse<{ versionId: string; status: string; versionCode: string; submitter: string }>>('/v1/apps/publish', data),

  approve: (data: { versionId: string; result: string; comment?: string; approver?: string }) =>
    apiClient.post<ApiResponse<{ versionId: string; status: string; approvedAt?: string; approver?: string }>>('/v1/apps/approve', data),

  checkApprovalPermission: (userId?: string) =>
    apiClient.get<ApiResponse<PermissionCheck>>('/v1/apps/approve/permission', { params: { userId } }),

  getVersions: (params?: { appId?: string; status?: string }) =>
    apiClient.get<ApiResponse<Version[]>>('/v1/apps/versions', { params }),

  getVersion: (versionId: string) =>
    apiClient.get<ApiResponse<Version>>(`/v1/apps/versions/${versionId}`),

  getLogs: (versionId: string) =>
    apiClient.get<ApiResponse<Array<{ logId: string; versionId: string; action: string; operator?: string; comment?: string; createdAt: string }>>>(`/v1/apps/versions/${versionId}/logs`),
};

export const feedbackApi = {
  submit: (data: { appId: string; userId?: string; feedbackType?: string; content: string; rating?: number; title?: string }) =>
    apiClient.post<ApiResponse<{ feedbackId: string; status: string; priority: string; assignee: string; feedbackType: string; matchedKeywords: string[] }>>('/v1/apps/feedback', data),

  classifyPreview: (data: { appId: string; userId?: string; feedbackType?: string; content: string; rating?: number; title?: string }) =>
    apiClient.post<ApiResponse<ClassificationResult>>('/v1/apps/feedback/classify-preview', data),

  getClassificationRules: () =>
    apiClient.get<ApiResponse<Record<string, string[]>>>('/v1/apps/feedback/classification-rules'),

  getFeedbacks: (params?: { appId?: string; status?: string; priority?: string }) =>
    apiClient.get<ApiResponse<Feedback[]>>('/v1/apps/feedback', { params }),

  getFeedback: (feedbackId: string) =>
    apiClient.get<ApiResponse<Feedback>>(`/v1/apps/feedback/${feedbackId}`),

  process: (feedbackId: string, data: { status?: string; processingNote?: string; assignee?: string }, operator?: string) =>
    apiClient.put<ApiResponse<Feedback>>(`/v1/apps/feedback/${feedbackId}`, data, { params: { operator } }),

  getStats: (appId: string) =>
    apiClient.get<ApiResponse<FeedbackStats>>('/v1/apps/feedback/stats', { params: { appId } }),
};

export const statisticsApi = {
  getStatistics: (params: { appId: string; startDate?: string; endDate?: string; type?: string }) =>
    apiClient.get<ApiResponse<AsyncStatsResponse<ChartData>>>('/v1/statistics', { params }),

  getSummary: (appId: string) =>
    apiClient.get<ApiResponse<AsyncStatsResponse<SummaryStats>>>('/v1/statistics/summary', { params: { appId } }),

  waitForSummary: (appId: string, maxWaitSeconds: number = 10) =>
    apiClient.get<ApiResponse<{ ready: boolean; waitedSeconds: number; data?: SummaryStats; message?: string }>>('/v1/statistics/summary/wait', { params: { appId, maxWaitSeconds } }),

  getChartData: (params: { appId: string; startDate?: string; endDate?: string; type?: string }) =>
    apiClient.get<ApiResponse<AsyncStatsResponse<ChartData>>>('/v1/statistics/chart', { params }),

  waitForChart: (appId: string, startDate: string, endDate: string, maxWaitSeconds: number = 10) =>
    apiClient.get<ApiResponse<{ ready: boolean; waitedSeconds: number; data?: ChartData; message?: string }>>('/v1/statistics/chart/wait', { params: { appId, startDate, endDate, maxWaitSeconds } }),

  getTaskStatus: (taskId: string) =>
    apiClient.get<ApiResponse<{ exists: boolean; taskId?: string; status?: string; updatedAt?: string; message?: string }>>(`/v1/statistics/task/${taskId}`),

  forceRefresh: (appId: string) =>
    apiClient.post<ApiResponse<{ refreshed: boolean; data: SummaryStats }>>(`/v1/statistics/refresh/${appId}`),

  getCacheInfo: (appId: string) =>
    apiClient.get<ApiResponse<{ summaryCached: boolean; chartCacheCount: number }>>(`/v1/statistics/cache/${appId}`),

  generateDemo: (appId: string, days: number = 30) =>
    apiClient.post<ApiResponse<Statistics[]>>(`/v1/statistics/demo/${appId}`, null, { params: { days } }),
};

export const reportApi = {
  getDaily: (appId: string, date?: string) =>
    apiClient.get<ApiResponse<any>>('/v1/reports/daily', { params: { appId, date } }),

  getWeekly: (appId: string, endDate?: string) =>
    apiClient.get<ApiResponse<any>>('/v1/reports/weekly', { params: { appId, endDate } }),

  getMonthly: (appId: string, endDate?: string) =>
    apiClient.get<ApiResponse<any>>('/v1/reports/monthly', { params: { appId, endDate } }),

  getCustom: (appId: string, startDate: string, endDate: string) =>
    apiClient.get<ApiResponse<any>>('/v1/reports/custom', { params: { appId, startDate, endDate } }),
};

export const notificationApi = {
  getNotifications: (recipientId: string, isRead?: boolean) =>
    apiClient.get<ApiResponse<Notification[]>>('/v1/notifications', { params: { recipientId, isRead } }),

  getUnreadCount: (recipientId: string) =>
    apiClient.get<ApiResponse<{ unreadCount: number }>>('/v1/notifications/unread-count', { params: { recipientId } }),

  markAsRead: (notificationId: string) =>
    apiClient.put<ApiResponse<Notification>>(`/v1/notifications/${notificationId}/read`),

  markAllAsRead: (recipientId: string) =>
    apiClient.put<ApiResponse<{ markedCount: number }>>('/v1/notifications/read-all', { params: { recipientId } }),
};
