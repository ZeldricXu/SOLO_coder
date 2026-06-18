import api from './api';
import type { AuditLog, ApiResponse, PaginatedResponse } from '@/types';

export interface QueryAuditParams {
  page?: number;
  limit?: number;
  userId?: string;
  action?: string;
  resource?: string;
  startTime?: string;
  endTime?: string;
}

export const auditService = {
  list(params?: QueryAuditParams) {
    return api.get<ApiResponse<PaginatedResponse<AuditLog>>>('/audit', { params });
  },

  getActions() {
    return api.get<ApiResponse<string[]>>('/audit/actions');
  },

  getResources() {
    return api.get<ApiResponse<string[]>>('/audit/resources');
  },
};
