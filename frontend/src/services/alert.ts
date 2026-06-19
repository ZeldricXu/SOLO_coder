import api from './api';
import type { AlertRule, AlertRecord, ApiResponse } from '@/types';

export const alertService = {
  listRules(metricId?: string, businessLineId?: string) {
    return api.get<ApiResponse<AlertRule[]>>('/alerts/rules', {
      params: { metricId, businessLineId },
    });
  },

  createRule(data: Partial<AlertRule>) {
    return api.post<ApiResponse<AlertRule>>('/alerts/rules', data);
  },

  updateRule(id: string, data: Partial<AlertRule>) {
    return api.put<ApiResponse<AlertRule>>(`/alerts/rules/${id}`, data);
  },

  deleteRule(id: string) {
    return api.delete(`/alerts/rules/${id}`);
  },

  toggleRule(id: string) {
    return api.patch<ApiResponse<AlertRule>>(`/alerts/rules/${id}/toggle`);
  },

  listRecords(ruleId?: string, acknowledged?: boolean) {
    return api.get<ApiResponse<AlertRecord[]>>('/alerts/records', {
      params: { ruleId, acknowledged },
    });
  },

  acknowledgeRecord(id: string, acknowledgedBy: string) {
    return api.patch<ApiResponse<AlertRecord>>(`/alerts/records/${id}/acknowledge`, {
      acknowledgedBy,
    });
  },

  getHistory(ruleId: string) {
    return api.get<ApiResponse<AlertRecord[]>>(`/alerts/rules/${ruleId}/history`);
  },
};
