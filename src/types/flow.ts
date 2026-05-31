export interface FlowNode {
  id: string;
  type: 'start' | 'end' | 'action' | 'condition' | 'delay' | 'parallel' | 'subflow';
  name: string;
  x: number;
  y: number;
  config: NodeConfig;
  inputs: string[];
  outputs: string[];
  createdAt: string;
  updatedAt: string;
}

export interface NodeConfig {
  action?: string;
  conditions?: ConditionRule[];
  delayMs?: number;
  subflowId?: string;
  timeout?: number;
  retries?: number;
  parameters?: Record<string, unknown>;
}

export interface ConditionRule {
  field: string;
  operator: 'eq' | 'ne' | 'gt' | 'lt' | 'gte' | 'lte' | 'contains' | 'in';
  value: unknown;
}

export interface FlowConnection {
  id: string;
  sourceNodeId: string;
  targetNodeId: string;
  sourcePort: string;
  targetPort: string;
  label?: string;
  condition?: ConditionRule;
  createdAt: string;
}

export interface FlowDefinition {
  id: string;
  name: string;
  description?: string;
  version: number;
  nodes: FlowNode[];
  connections: FlowConnection[];
  status: 'draft' | 'published' | 'archived';
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: ValidationWarning[];
}

export interface ValidationError {
  code: string;
  message: string;
  nodeId?: string;
  connectionId?: string;
  severity: 'error' | 'warning';
}

export interface ValidationWarning {
  code: string;
  message: string;
  nodeId?: string;
  connectionId?: string;
}

export interface ConnectionRule {
  allowedSourceTypes: string[];
  allowedTargetTypes: string[];
  maxConnections?: number;
  minConnections?: number;
  description: string;
}

export interface FlowExecutionContext {
  flowId: string;
  instanceId: string;
  currentNodeId?: string;
  variables: Record<string, unknown>;
  startTime: string;
  status: 'running' | 'paused' | 'completed' | 'failed';
  trace: FlowTraceEntry[];
}

export interface FlowTraceEntry {
  nodeId: string;
  timestamp: string;
  status: 'entered' | 'exited' | 'skipped' | 'error';
  data?: Record<string, unknown>;
  error?: string;
}
