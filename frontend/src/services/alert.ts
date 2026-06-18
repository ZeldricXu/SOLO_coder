import api from './api';
import type { AxiosResponse } from 'axios';
import type { AlertRule, AlertRecord, ApiResponse, AlertType, AlertChannelType } from '@/types';

export interface AlertChannel {
  type: AlertChannelType;
  target: string;
}

export interface CreateAlertRuleData {
  name: string;
  type: AlertType;
  condition: Record<string, unknown>;
  metricId: string;
  channels: AlertChannel[];
  silenceMinutes?: number;
  escalationMinutes?: number;
  escalationChannels?: AlertChannel[];
  isActive?: boolean;
}

export interface UpdateAlertRuleData {
  name?: string;
  type?: AlertType;
  condition?: Record<string, unknown>;
  metricId?: string;
  channels?: AlertChannel[];
  silenceMinutes?: number;
  escalationMinutes?: number;
  escalationChannels?: AlertChannel[];
  isActive?: boolean;
}

export interface AcknowledgeData {
  acknowledgedBy: string;
}

export const alertService = {
  listRules(metricId?: string, businessLineId?: string): Promise<AxiosResponse<ApiResponse<AlertRule[]>>> {
    return api.get<ApiResponse<AlertRule[]>>('/alerts/rules', {
      params: { metricId, businessLineId },
    });
  },

  getRule(id: string): Promise<AxiosResponse<ApiResponse<AlertRule>>> {
    return api.get<ApiResponse<AlertRule>>(`/alerts/rules/${id}`);
  },

  createRule(data: CreateAlertRuleData): Promise<AxiosResponse<ApiResponse<AlertRule>>> {
    return api.post<ApiResponse<AlertRule>>('/alerts/rules', data);
  },

  updateRule(id: string, data: UpdateAlertRuleData): Promise<AxiosResponse<ApiResponse<AlertRule>>> {
    return api.put<ApiResponse<AlertRule>>(`/alerts/rules/${id}`, data);
  },

  deleteRule(id: string): Promise<AxiosResponse<ApiResponse<void>>> {
    return api.delete<ApiResponse<void>>(`/alerts/rules/${id}`);
  },

  toggleRule(id: string): Promise<AxiosResponse<ApiResponse<AlertRule>>> {
    return api.patch<ApiResponse<AlertRule>>(`/alerts/rules/${id}/toggle`);
  },

  listRecords(ruleId?: string, acknowledged?: boolean): Promise<AxiosResponse<ApiResponse<AlertRecord[]>>> {
    return api.get<ApiResponse<AlertRecord[]>>('/alerts/records', {
      params: { ruleId, acknowledged: acknowledged?.toString() },
    });
  },

  acknowledgeRecord(id: string, data: AcknowledgeData): Promise<AxiosResponse<ApiResponse<AlertRecord>>> {
    return api.patch<ApiResponse<AlertRecord>>(`/alerts/records/${id}/acknowledge`, data);
  },

  getHistory(ruleId: string): Promise<AxiosResponse<ApiResponse<AlertRecord[]>>> {
    return api.get<ApiResponse<AlertRecord[]>>(`/alerts/rules/${ruleId}/history`);
  },
};
