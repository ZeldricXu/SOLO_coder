export type TenantStatus = 'active' | 'suspended' | 'pending' | 'cancelled';
export type PlanTier = 'free' | 'starter' | 'professional' | 'enterprise';
export type ContentStatus = 'draft' | 'reviewing' | 'approved' | 'published' | 'archived' | 'rejected';
export type WorkflowNodeType = 'start' | 'approval' | 'condition' | 'parallel' | 'end';
export type ApprovalType = 'any' | 'all' | 'percentage';
export type WebhookEvent = 'content.created' | 'content.updated' | 'content.published' | 'content.deleted' | 'workflow.started' | 'workflow.approved' | 'workflow.rejected';
export type WebhookStatus = 'pending' | 'success' | 'failed' | 'retrying';
export type CDNRegion = 'cn-north' | 'cn-south' | 'cn-east' | 'cn-west' | 'ap-southeast' | 'us-west' | 'eu-west';
export type FieldType = 'string' | 'text' | 'integer' | 'float' | 'boolean' | 'date' | 'datetime' | 'json' | 'reference' | 'file' | 'image' | 'richtext' | 'select' | 'multiselect';

export interface TenantContext {
  tenantId: string;
  tenantCode: string;
  plan: PlanTier;
  dbSchema: string;
  elasticIndexPrefix: string;
  limits: TenantLimits;
}

export interface TenantLimits {
  maxApiCallsPerDay: number;
  maxStorageGb: number;
  maxContentModels: number;
  maxUsers: number;
  maxWebhooks: number;
  enableVersioning: boolean;
  enableWorkflow: boolean;
  enableElasticsearch: boolean;
  enableCDN: boolean;
}

export interface ContentField {
  name: string;
  type: FieldType;
  required: boolean;
  unique?: boolean;
  indexed?: boolean;
  searchable?: boolean;
  searchWeight?: number;
  default?: unknown;
  validations?: Record<string, unknown>;
  relation?: {
    modelId: string;
    field: string;
  };
  options?: Array<{ label: string; value: string | number }>;
}

export interface ContentSchema {
  $schema: string;
  title: string;
  type: 'object';
  properties: Record<string, ContentField>;
  required: string[];
  additionalProperties: boolean;
}

export interface ContentVersion {
  id: string;
  contentId: string;
  version: number;
  snapshot: Record<string, unknown>;
  status: ContentStatus;
  createdBy: string;
  createdAt: Date;
  message?: string;
}

export interface WorkflowNode {
  id: string;
  type: WorkflowNodeType;
  name: string;
  config: {
    approvalType?: ApprovalType;
    approvers?: string[];
    approvalPercentage?: number;
    condition?: string;
    branches?: Array<{ condition: string; nodeId: string }>;
    parallelNodes?: string[];
  };
  nextNodeId?: string;
}

export interface WorkflowDefinition {
  id: string;
  tenantId: string;
  modelId: string;
  name: string;
  description?: string;
  nodes: WorkflowNode[];
  startNodeId: string;
  endNodeId: string;
  isDefault: boolean;
  version: number;
  createdAt: Date;
  updatedAt: Date;
}

export interface WorkflowInstance {
  id: string;
  definitionId: string;
  contentId: string;
  currentNodeId: string;
  status: 'running' | 'approved' | 'rejected';
  approvals: Array<{
    nodeId: string;
    userId: string;
    decision: 'approved' | 'rejected';
    comment?: string;
    timestamp: Date;
    signature: string;
  }>;
  startedBy: string;
  startedAt: Date;
  completedAt?: Date;
}

export interface SearchConfig {
  tenantId: string;
  modelId: string;
  fieldWeights: Record<string, number>;
  defaultOperator: 'AND' | 'OR';
  fuzziness: number;
  analyzer: string;
}

export interface CDNPublishStatus {
  contentId: string;
  region: CDNRegion;
  status: 'pending' | 'publishing' | 'published' | 'failed';
  cdnUrl: string;
  publishedAt?: Date;
  errorMessage?: string;
}

export interface WebhookConfig {
  id: string;
  tenantId: string;
  url: string;
  secret: string;
  events: WebhookEvent[];
  active: boolean;
  timeoutMs: number;
  maxRetries: number;
}

export interface WebhookDelivery {
  id: string;
  webhookId: string;
  event: WebhookEvent;
  payload: Record<string, unknown>;
  status: WebhookStatus;
  attempts: number;
  lastAttemptAt?: Date;
  nextAttemptAt?: Date;
  responseStatusCode?: number;
  errorMessage?: string;
}

export interface TenantUsage {
  tenantId: string;
  date: string;
  apiCalls: number;
  storageUsedBytes: number;
  contentCount: number;
  userCount: number;
  webhookCount: number;
}
