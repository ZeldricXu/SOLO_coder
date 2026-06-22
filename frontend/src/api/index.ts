import { request } from './request'
import type {
  Switch,
  Strategy,
  SwitchHistory,
  Approval,
  ScheduledTask,
  SwitchStats,
  StatsSummary,
  SwitchIntegration,
  Service,
  EvaluationResult,
  ListResponse,
  ListRequest,
  CreateSwitchRequest,
  UpdateSwitchRequest,
  ApprovalRequest,
  ScheduleRequest,
  BatchOperationRequest,
  BatchServiceOperationRequest,
  EvaluationContext,
} from '@/types'

export const switchApi = {
  list: (params?: ListRequest) => {
    return request.get<ListResponse<Switch[]>>('/switches', { params })
  },

  getById: (id: string) => {
    return request.get<Switch>(`/switches/${id}`)
  },

  getByKey: (key: string) => {
    return request.get<Switch>(`/switches/key/${key}`)
  },

  create: (data: CreateSwitchRequest) => {
    return request.post<Switch>('/switches', data)
  },

  update: (id: string, data: UpdateSwitchRequest) => {
    return request.put<Switch>(`/switches/${id}`, data)
  },

  delete: (id: string) => {
    return request.delete(`/switches/${id}`)
  },

  enable: (id: string) => {
    return request.post<Switch>(`/switches/${id}/enable`)
  },

  disable: (id: string) => {
    return request.post<Switch>(`/switches/${id}/disable`)
  },

  batchEnable: (data: BatchOperationRequest) => {
    return request.post<{ updated_count: number }>('/switches/batch/enable', data)
  },

  batchDisable: (data: BatchOperationRequest) => {
    return request.post<{ updated_count: number }>('/switches/batch/disable', data)
  },

  batchEnableByService: (data: BatchServiceOperationRequest) => {
    return request.post<{ updated_count: number }>('/switches/batch/service/enable', data)
  },

  batchDisableByService: (data: BatchServiceOperationRequest) => {
    return request.post<{ updated_count: number }>('/switches/batch/service/disable', data)
  },

  evaluate: (key: string, context: EvaluationContext) => {
    return request.post<EvaluationResult>('/switches/evaluate', { key, ...context })
  },

  batchEvaluate: (context: EvaluationContext & { keys?: string[] }) => {
    return request.post<Record<string, EvaluationResult>>('/switches/evaluate/batch', context)
  },

  getHistory: (id: string, page = 1, page_size = 20) => {
    return request.get<ListResponse<SwitchHistory[]>>(`/switches/${id}/history`, {
      params: { page, page_size },
    })
  },

  saveStrategies: (id: string, strategies: Strategy[]) => {
    return request.post(`/switches/${id}/strategies`, strategies)
  },

  createSchedule: (id: string, data: ScheduleRequest) => {
    return request.post<ScheduledTask>(`/switches/${id}/schedule`, data)
  },

  listSchedules: (id: string, page = 1, page_size = 20) => {
    return request.get<ListResponse<ScheduledTask[]>>(`/switches/${id}/schedule`, {
      params: { page, page_size },
    })
  },

  getStats: (id: string, start_date?: string, end_date?: string) => {
    return request.get<SwitchStats[]>(`/switches/${id}/stats`, {
      params: { start_date, end_date },
    })
  },

  getStatsSummary: (id: string) => {
    return request.get<StatsSummary>(`/switches/${id}/stats/summary`)
  },

  getIntegrations: (id: string) => {
    return request.get<SwitchIntegration[]>(`/switches/${id}/integrations`)
  },
}

export const approvalApi = {
  list: (params?: { status?: string; requester?: string; approver?: string; page?: number; page_size?: number }) => {
    return request.get<ListResponse<Approval[]>>('/approvals', { params })
  },

  getById: (id: string) => {
    return request.get<Approval>(`/approvals/${id}`)
  },

  create: (data: ApprovalRequest) => {
    return request.post<Approval>('/approvals', data)
  },

  approve: (id: string) => {
    return request.post<Approval>(`/approvals/${id}/approve`)
  },

  reject: (id: string, reject_reason?: string) => {
    return request.post<Approval>(`/approvals/${id}/reject`, { reject_reason })
  },
}

export const serviceApi = {
  list: () => {
    return request.get<Service[]>('/services')
  },
}

export const sdkApi = {
  getConfig: (version?: number) => {
    return request.get('/sdk/config', { params: { version } })
  },

  evaluate: (key: string, context: EvaluationContext) => {
    return request.post<EvaluationResult>('/sdk/evaluate', { key, ...context })
  },
}

export default {
  switch: switchApi,
  approval: approvalApi,
  service: serviceApi,
  sdk: sdkApi,
}
