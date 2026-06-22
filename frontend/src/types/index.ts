export type SwitchType = 'BOOLEAN' | 'PERCENTAGE' | 'WHITELIST'
export type SwitchScope = 'GLOBAL' | 'ENVIRONMENT' | 'TENANT'
export type SwitchStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'INACTIVE' | 'SCHEDULED'
export type StrategyOperator = 'AND' | 'OR'
export type WhitelistField = 'USER_ID' | 'DEPARTMENT' | 'TAG'
export type WhitelistOperator = 'IN' | 'NOT_IN' | 'CONTAINS' | 'NOT_CONTAINS'
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
export type EventType = 'SWITCH_CREATED' | 'SWITCH_UPDATED' | 'SWITCH_DELETED' | 
                      'SWITCH_ENABLED' | 'SWITCH_DISABLED' | 'STRATEGY_UPDATED' |
                      'APPROVAL_REQUESTED' | 'APPROVAL_APPROVED' | 'APPROVAL_REJECTED' | 'AUTO_ROLLBACK'

export interface Switch {
  id: string
  key: string
  name: string
  description: string
  type: SwitchType
  scope: SwitchScope
  service_id: string
  service_name?: string
  owner: string
  status: SwitchStatus
  enabled: boolean
  boolean_value: boolean
  percentage_value: number
  environment?: string
  tenant_id?: string
  require_approval: boolean
  auto_rollback_enabled: boolean
  auto_rollback_threshold: number
  created_by: string
  created_at: string
  updated_at: string
  strategies?: Strategy[]
}

export interface Strategy {
  id: string
  switch_id: string
  name: string
  description: string
  operator: StrategyOperator
  priority: number
  enabled: boolean
  conditions?: WhitelistCondition[]
  created_at: string
  updated_at: string
}

export interface WhitelistCondition {
  id: string
  strategy_id: string
  field: WhitelistField
  operator: WhitelistOperator
  values: string[]
  created_at: string
}

export interface SwitchHistory {
  id: string
  switch_id: string
  event_type: EventType
  old_value?: Record<string, any>
  new_value?: Record<string, any>
  operator_user: string
  remark?: string
  created_at: string
}

export interface Approval {
  id: string
  switch_id: string
  switch_key?: string
  switch_name?: string
  title: string
  description?: string
  requester: string
  approver: string
  status: ApprovalStatus
  change_content: Record<string, any>
  approved_at?: string
  rejected_at?: string
  reject_reason?: string
  created_at: string
  updated_at: string
}

export interface ScheduledTask {
  id: string
  switch_id: string
  task_type: string
  target_enabled: boolean
  execute_at: string
  executed_at?: string
  status: string
  error_message?: string
  created_by: string
  created_at: string
}

export interface SwitchStats {
  id: string
  switch_id: string
  date: string
  total_evaluations: number
  true_count: number
  false_count: number
  error_count: number
  avg_latency_ms: number
  p99_latency_ms: number
  created_at: string
  updated_at: string
}

export interface StatsSummary {
  total_evaluations: number
  true_count: number
  false_count: number
  error_count: number
  avg_latency_ms: number
  p99_latency_ms: number
}

export interface SwitchIntegration {
  id: string
  switch_id: string
  service_name: string
  sdk_version?: string
  last_poll_at?: string
  created_at: string
  updated_at: string
}

export interface Service {
  id: string
  name: string
  description: string
  owner: string
  created_at: string
  updated_at: string
}

export interface EvaluationContext {
  user_id: string
  department: string
  tags: string[]
  environment: string
  tenant_id: string
  attributes: Record<string, string>
}

export interface EvaluationResult {
  enabled: boolean
  matched: boolean
  reason: string
  switch_key: string
  value?: any
}

export interface Pagination {
  page: number
  page_size: number
  total: number
}

export interface ListResponse<T> {
  data: T
  pagination: Pagination
}

export interface ApiResponse<T = any> {
  code: number
  message: string
  data?: T
}

export interface ListRequest {
  page?: number
  page_size?: number
  keyword?: string
  service_id?: string
  environment?: string
  status?: string
  owner?: string
  type?: SwitchType
  scope?: SwitchScope
}

export interface CreateSwitchRequest {
  key: string
  name: string
  description?: string
  type: SwitchType
  scope: SwitchScope
  service_id: string
  owner: string
  boolean_value?: boolean
  percentage_value?: number
  environment?: string
  tenant_id?: string
  require_approval?: boolean
  auto_rollback_enabled?: boolean
  auto_rollback_threshold?: number
  strategies?: Strategy[]
}

export interface UpdateSwitchRequest {
  name?: string
  description?: string
  type?: SwitchType
  scope?: SwitchScope
  service_id?: string
  owner?: string
  boolean_value?: boolean
  percentage_value?: number
  environment?: string
  tenant_id?: string
  require_approval?: boolean
  auto_rollback_enabled?: boolean
  auto_rollback_threshold?: number
}

export interface ApprovalRequest {
  switch_id: string
  title: string
  description?: string
  approver: string
  target_enabled: boolean
}

export interface ScheduleRequest {
  switch_id: string
  task_type: string
  target_enabled: boolean
  execute_at: string
}

export interface BatchOperationRequest {
  ids: string[]
  operation: string
}

export interface BatchServiceOperationRequest {
  service_id: string
  operation: string
}
