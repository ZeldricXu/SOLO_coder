import EventEmitter from 'eventemitter3';
import { v4 as uuidv4 } from 'uuid';
import { EdgeRule, RuleCondition, RuleExecutionResult, EdgeRuleConfig, RuleContext } from './types';
import { createActionHandler, ActionHandler } from './actions';

export class EdgeRuleEngine extends EventEmitter {
  private rules: Map<string, EdgeRule> = new Map();
  private actionHandlers: Map<string, ActionHandler> = new Map();
  private executionHistory: RuleExecutionResult[] = [];

  constructor(private config: EdgeRuleConfig) {
    super();
    this.initializeBuiltInActions();
  }

  private initializeBuiltInActions(): void {
    for (const actionType of this.config.builtInActions) {
      try {
        const handler = createActionHandler(actionType);
        this.actionHandlers.set(actionType, handler);
      } catch (error) {
        console.warn(`[EdgeRuleEngine] Failed to initialize action: ${actionType}`);
      }
    }
  }

  addRule(rule: Omit<EdgeRule, 'id' | 'created_at' | 'updated_at'>): EdgeRule {
    if (this.rules.size >= this.config.maxRules) {
      throw new Error(`Maximum number of rules (${this.config.maxRules}) reached`);
    }

    const now = new Date().toISOString();
    const newRule: EdgeRule = {
      ...rule,
      id: uuidv4(),
      created_at: now,
      updated_at: now,
    };

    this.rules.set(newRule.id, newRule);
    this.emit('rule-added', newRule);
    return newRule;
  }

  updateRule(ruleId: string, updates: Partial<EdgeRule>): EdgeRule | null {
    const rule = this.rules.get(ruleId);
    if (!rule) return null;

    const updatedRule: EdgeRule = {
      ...rule,
      ...updates,
      id: ruleId,
      updated_at: new Date().toISOString(),
    };

    this.rules.set(ruleId, updatedRule);
    this.emit('rule-updated', updatedRule);
    return updatedRule;
  }

  deleteRule(ruleId: string): boolean {
    const deleted = this.rules.delete(ruleId);
    if (deleted) {
      this.emit('rule-deleted', ruleId);
    }
    return deleted;
  }

  getRule(ruleId: string): EdgeRule | undefined {
    return this.rules.get(ruleId);
  }

  listRules(enabledOnly: boolean = false): EdgeRule[] {
    const rules = Array.from(this.rules.values());
    return enabledOnly ? rules.filter(r => r.enabled) : rules;
  }

  enableRule(ruleId: string): boolean {
    const rule = this.rules.get(ruleId);
    if (!rule) return false;
    rule.enabled = true;
    rule.updated_at = new Date().toISOString();
    this.emit('rule-enabled', rule);
    return true;
  }

  disableRule(ruleId: string): boolean {
    const rule = this.rules.get(ruleId);
    if (!rule) return false;
    rule.enabled = false;
    rule.updated_at = new Date().toISOString();
    this.emit('rule-disabled', rule);
    return true;
  }

  async evaluate(event: Record<string, unknown>, state?: Record<string, unknown>): Promise<RuleExecutionResult[]> {
    const context: RuleContext = {
      event,
      state,
      timestamp: new Date().toISOString(),
    };

    const sortedRules = Array.from(this.rules.values())
      .filter(r => r.enabled)
      .sort((a, b) => a.priority - b.priority);

    const results: RuleExecutionResult[] = [];

    for (const rule of sortedRules) {
      const result = await this.executeRule(rule, context);
      results.push(result);
      this.executionHistory.push(result);

      if (this.executionHistory.length > 1000) {
        this.executionHistory = this.executionHistory.slice(-1000);
      }
    }

    return results;
  }

  private async executeRule(rule: EdgeRule, context: RuleContext): Promise<RuleExecutionResult> {
    const result: RuleExecutionResult = {
      ruleId: rule.id,
      ruleName: rule.name,
      triggered: false,
      conditionsMet: [],
      executedAt: new Date().toISOString(),
    };

    try {
      const conditionsMet = rule.conditions.map(condition =>
        this.evaluateCondition(condition, context.event)
      );
      result.conditionsMet = conditionsMet;

      const allConditionsMet = rule.conditionOperator === 'AND'
        ? conditionsMet.every(Boolean)
        : conditionsMet.some(Boolean);

      if (!allConditionsMet) {
        return result;
      }

      result.triggered = true;
      this.emit('rule-triggered', rule, context);

      const handler = this.actionHandlers.get(rule.action.type);
      if (handler) {
        result.actionResult = await Promise.race([
          handler.execute(rule.action, context),
          new Promise<never>((_, reject) => {
            setTimeout(() => reject(new Error('Action execution timeout')), this.config.evaluationTimeout);
          }),
        ]);
      } else {
        throw new Error(`No handler for action type: ${rule.action.type}`);
      }

      this.emit('action-executed', rule, result.actionResult);
    } catch (error) {
      result.error = error instanceof Error ? error.message : 'Unknown error';
      this.emit('rule-error', rule, result.error);
    }

    return result;
  }

  private evaluateCondition(condition: RuleCondition, event: Record<string, unknown>): boolean {
    const value = this.getNestedValue(event, condition.field);

    switch (condition.operator) {
      case 'eq':
        return value === condition.value;
      case 'ne':
        return value !== condition.value;
      case 'gt':
        return typeof value === 'number' && value > (condition.value as number);
      case 'lt':
        return typeof value === 'number' && value < (condition.value as number);
      case 'gte':
        return typeof value === 'number' && value >= (condition.value as number);
      case 'lte':
        return typeof value === 'number' && value <= (condition.value as number);
      case 'contains':
        return typeof value === 'string' && value.includes(condition.value as string);
      case 'matches':
        return typeof value === 'string' && new RegExp(condition.value as string).test(value);
      case 'in':
        return Array.isArray(condition.value) && condition.value.includes(value);
      case 'not_in':
        return Array.isArray(condition.value) && !condition.value.includes(value);
      default:
        return false;
    }
  }

  private getNestedValue(obj: Record<string, unknown>, path: string): unknown {
    return path.split('.').reduce((current, key) => {
      if (current && typeof current === 'object') {
        return (current as Record<string, unknown>)[key];
      }
      return undefined;
    }, obj);
  }

  getExecutionHistory(limit?: number): RuleExecutionResult[] {
    return limit ? this.executionHistory.slice(-limit) : this.executionHistory;
  }

  clearHistory(): void {
    this.executionHistory = [];
  }

  registerActionHandler(type: string, handler: ActionHandler): void {
    this.actionHandlers.set(type, handler);
    this.emit('action-registered', type);
  }

  getRegisteredActions(): string[] {
    return Array.from(this.actionHandlers.keys());
  }

  destroy(): void {
    this.removeAllListeners();
  }
}

export * from './types';
export * from './actions';
