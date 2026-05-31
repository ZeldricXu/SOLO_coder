export interface RuleCondition {
  field: string;
  operator: 'eq' | 'ne' | 'gt' | 'lt' | 'gte' | 'lte' | 'contains' | 'matches' | 'in' | 'not_in';
  value: unknown;
}

export interface RuleAction {
  type: string;
  params: Record<string, unknown>;
}

export interface EdgeRule {
  id: string;
  name: string;
  description?: string;
  conditions: RuleCondition[];
  conditionOperator: 'AND' | 'OR';
  action: RuleAction;
  enabled: boolean;
  priority: number;
  created_at: string;
  updated_at: string;
}

export interface RuleExecutionResult {
  ruleId: string;
  ruleName: string;
  triggered: boolean;
  conditionsMet: boolean[];
  actionResult?: unknown;
  error?: string;
  executedAt: string;
}

export interface EdgeRuleConfig {
  maxRules: number;
  evaluationTimeout: number;
  enableSandbox: boolean;
  builtInActions: string[];
}

export interface RuleContext {
  event: Record<string, unknown>;
  device?: Record<string, unknown>;
  state?: Record<string, unknown>;
  timestamp: string;
}
