export interface CoreEntity {
  id: string;
  type: string;
  status: string;
  attributes: Record<string, unknown>;
  createdAt: Date;
  updatedAt: Date;
}

export interface ConfigDefinition {
  configId: string;
  namespace: string;
  version: number;
  parameters: Record<string, unknown>;
  enabled: boolean;
  appliedAt: Date;
}

export interface RunInstance {
  runId: string;
  entityId: string;
  phase: string;
  progress: number;
  startedAt: Date;
  completedAt?: Date;
  errorDetail?: string;
}

export interface MetricsSnapshot {
  snapshotId: string;
  timestamp: Date;
  metrics: {
    throughput: number;
    latency_p99: number;
    error_rate: number;
    [key: string]: number;
  };
  dimensions: Record<string, string>;
}

export interface ApiResponse<T = unknown> {
  code: number;
  data?: T;
  message?: string;
  error?: string;
  traceId?: string;
}

export interface PaginationParams {
  page: number;
  pageSize: number;
}

export interface PaginatedResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface ProcessingContext {
  traceId: string;
  tenantId: string;
  userId?: string;
  startTime: Date;
  transaction?: unknown;
}

export interface Event<T = unknown> {
  id: string;
  type: string;
  source: string;
  timestamp: Date;
  data: T;
  traceId: string;
  [key: string]: unknown;
}

export type ApprovalStrategy = 'ALL' | 'ANY' | 'MAJORITY' | 'SEQUENTIAL';

export type SLAStatus = 'active' | 'warning' | 'breached' | 'met';

export type TicketPriority = 'low' | 'medium' | 'high' | 'urgent';

export type TicketStatus = 'open' | 'assigned' | 'in_progress' | 'pending' | 'resolved' | 'closed';

export interface SkillMatchResult {
  agentId: string;
  score: number;
  skillMatches: SkillMatch[];
  loadFactor: number;
}

export interface SkillMatch {
  skillId: string;
  skillName: string;
  requiredLevel: number;
  agentLevel: number;
  match: boolean;
}

export interface ProcessNodeConfig {
  type: 'start' | 'end' | 'task' | 'decision' | 'parallel' | 'approval' | 'notification';
  name: string;
  description?: string;
  properties: Record<string, unknown>;
}

export interface EdgeCondition {
  field: string;
  operator: 'eq' | 'ne' | 'gt' | 'lt' | 'gte' | 'lte' | 'contains' | 'in';
  value: unknown;
}

export interface DiffSegment {
  type: 'added' | 'removed' | 'unchanged' | 'modified';
  value: string;
  lineNumber?: number;
  confidence?: number;
}

export interface ComparisonResult {
  segments: DiffSegment[];
  statistics: {
    additions: number;
    removals: number;
    changes: number;
    unchanged: number;
  };
  highlights: HighlightedTerm[];
  summary: string;
}

export interface HighlightedTerm {
  term: string;
  type: 'critical' | 'important' | 'warning';
  description: string;
  positions: Array<{
    version: number;
    start: number;
    end: number;
  }>;
}

export interface TenantContext {
  tenantId: string;
  name: string;
  config: Record<string, unknown>;
  quotas: Record<string, { used: number; limit: number }>;
}

export interface BillingItem {
  resourceType: string;
  quantity: number;
  unitPrice: number;
  amount: number;
  description?: string;
}

export interface InvoiceData {
  tenantId: string;
  billingPeriod: string;
  items: BillingItem[];
  totalAmount: number;
  currency: string;
}

export type {
  CreateResourceInput,
  BatchOperationInput,
  TenantInput,
  UsageRecordInput,
  TicketInput,
  SkillInput,
  AgentInput,
  SkillAssessmentInput,
  WorkflowProcessInput,
  ApprovalRuleInput,
  DocumentInput,
  SLAPolicyInput
} from '../validation/schemas';
