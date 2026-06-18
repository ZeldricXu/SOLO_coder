export enum Role {
  SUPER_ADMIN = 'SUPER_ADMIN',
  TENANT_ADMIN = 'TENANT_ADMIN',
  EDITOR = 'EDITOR',
  VIEWER = 'VIEWER',
}

export enum DataSourceType {
  MYSQL = 'MYSQL',
  CLICKHOUSE = 'CLICKHOUSE',
  POSTGRESQL = 'POSTGRESQL',
  HTTP_API = 'HTTP_API',
}

export enum MetricType {
  SQL = 'SQL',
  TEMPLATE = 'TEMPLATE',
}

export enum Aggregation {
  SUM = 'SUM',
  COUNT = 'COUNT',
  AVG = 'AVG',
  MAX = 'MAX',
  MIN = 'MIN',
  NONE = 'NONE',
}

export enum TimeWindow {
  HOUR = 'HOUR',
  DAY = 'DAY',
  WEEK = 'WEEK',
  MONTH = 'MONTH',
}

export enum WidgetType {
  LINE_CHART = 'LINE_CHART',
  BAR_CHART = 'BAR_CHART',
  PIE_CHART = 'PIE_CHART',
  TABLE = 'TABLE',
  NUMBER_CARD = 'NUMBER_CARD',
  FUNNEL = 'FUNNEL',
}

export enum AlertType {
  THRESHOLD = 'THRESHOLD',
  FLUCTUATION = 'FLUCTUATION',
  STREAM_BREAK = 'STREAM_BREAK',
}

export enum AlertChannelType {
  EMAIL = 'EMAIL',
  WECOM = 'WECOM',
  DINGTALK = 'DINGTALK',
}

export interface User {
  id: string;
  email: string;
  name: string;
  role: Role;
  tenantId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  createdAt: string;
  updatedAt: string;
}

export interface BusinessLine {
  id: string;
  name: string;
  code: string;
  tenantId: string;
  createdAt: string;
  updatedAt: string;
}

export interface DataSource {
  id: string;
  name: string;
  type: DataSourceType;
  config: Record<string, unknown>;
  poolSize: number;
  queryTimeout: number;
  businessLineId: string;
  isActive: boolean;
  lastConnectionTest: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Metric {
  id: string;
  name: string;
  description: string;
  type: MetricType;
  sqlTemplate: string | null;
  templateId: string | null;
  aggregation: Aggregation;
  timeWindow: TimeWindow;
  dimensions: Record<string, unknown>;
  dataSourceId: string;
  businessLineId: string;
  isAutoCompare: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Dashboard {
  id: string;
  name: string;
  description: string;
  layout: Record<string, unknown>;
  globalFilters: Record<string, unknown>;
  businessLineId: string;
  createdBy: string;
  isPublic: boolean;
  createdAt: string;
  updatedAt: string;
  widgets?: Widget[];
}

export interface Widget {
  id: string;
  dashboardId: string;
  type: WidgetType;
  title: string;
  metricId: string | null;
  config: Record<string, unknown>;
  layout: Record<string, unknown>;
  filters: Record<string, unknown> | null;
  linkedWidgetIds: string[];
  createdAt: string;
  updatedAt: string;
}

export interface AlertRule {
  id: string;
  name: string;
  type: AlertType;
  condition: Record<string, unknown>;
  metricId: string;
  channels: AlertChannel[];
  silenceMinutes: number;
  escalationMinutes: number;
  escalationChannels: AlertChannel[] | null;
  isActive: boolean;
  lastTriggeredAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AlertChannel {
  type: AlertChannelType;
  target: string;
}

export interface AlertRecord {
  id: string;
  ruleId: string;
  value: number;
  message: string;
  notified: boolean;
  notifiedAt: string | null;
  acknowledged: boolean;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  createdAt: string;
}

export interface AuditLog {
  id: string;
  userId: string;
  userEmail: string;
  action: string;
  resource: string;
  resourceId: string | null;
  details: Record<string, unknown> | null;
  tenantId: string | null;
  ip: string;
  createdAt: string;
}

export interface ApiResponse<T> {
  data: T;
  message?: string;
}

export interface PaginatedResponse<T> {
  data: T[];
  total: number;
  page: number;
  limit: number;
}
