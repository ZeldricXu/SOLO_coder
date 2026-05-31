import { v4 as uuidv4 } from 'uuid';
import logger from '../common/logger';

export type Operator = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'contains' | 'startsWith' | 'endsWith' | 'in' | 'notIn' | 'regex';
export type LogicalOperator = 'AND' | 'OR';

export interface Condition {
  field: string;
  operator: Operator;
  value: unknown;
}

export interface RuleCondition {
  logicalOp: LogicalOperator;
  conditions: Array<Condition | RuleCondition>;
}

export interface RuleAction {
  type: 'webhook' | 'log' | 'alert' | 'command' | 'set_state';
  config: Record<string, unknown>;
}

export interface Rule {
  ruleId: string;
  name: string;
  description: string;
  enabled: boolean;
  priority: number;
  condition: RuleCondition;
  actions: RuleAction[];
  eventTypes: string[];
  createdAt: string;
  updatedAt: string;
  lastTriggeredAt?: string;
  triggerCount: number;
}

export interface RuleDefinition {
  name: string;
  description?: string;
  priority?: number;
  condition: RuleCondition;
  actions: RuleAction[];
  eventTypes: string[];
  enabled?: boolean;
}

export interface EventContext {
  eventId: string;
  eventType: string;
  timestamp: number;
  data: Record<string, unknown>;
  source: string;
  metadata: Record<string, unknown>;
}

export interface ActionExecutor {
  (action: RuleAction, context: EventContext, rule: Rule): Promise<void>;
}

export class RuleEngine {
  private rules: Map<string, Rule> = new Map();
  private actionExecutors: Map<string, ActionExecutor> = new Map();
  private eventBus: Map<string, Array<(context: EventContext) => void>> = new Map();

  constructor() {
    this.registerDefaultActions();
  }

  private registerDefaultActions(): void {
    this.registerActionExecutor('log', async (action, context) => {
      const level = (action.config.level as string) || 'info';
      const message = (action.config.message as string) || '规则触发';
      const logFn = (logger as unknown as Record<string, (obj: unknown, msg: string) => void>)[level];
      if (typeof logFn === 'function') {
        logFn({ eventId: context.eventId, ruleId: action.config.ruleId, data: context.data }, message);
      } else {
        logger.info({ eventId: context.eventId, ruleId: action.config.ruleId, data: context.data }, message);
      }
    });

    this.registerActionExecutor('alert', async (action, context) => {
      const alertType = (action.config.alertType as string) || 'info';
      const message = (action.config.message as string) || '告警触发';
      logger.warn({ alertType, eventId: context.eventId, data: context.data }, message);
    });

    this.registerActionExecutor('set_state', async (action, context) => {
      const key = action.config.key as string;
      const value = action.config.value;
      logger.info({ key, value, eventId: context.eventId }, '设置状态');
    });
  }

  registerActionExecutor(actionType: string, executor: ActionExecutor): void {
    this.actionExecutors.set(actionType, executor);
    logger.info({ actionType }, '注册动作执行器');
  }

  createRule(definition: RuleDefinition): Rule {
    const rule: Rule = {
      ruleId: uuidv4(),
      name: definition.name,
      description: definition.description || '',
      enabled: definition.enabled ?? true,
      priority: definition.priority ?? 5,
      condition: definition.condition,
      actions: definition.actions,
      eventTypes: definition.eventTypes,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      triggerCount: 0
    };

    this.rules.set(rule.ruleId, rule);
    logger.info({ ruleId: rule.ruleId, name: rule.name, eventTypes: rule.eventTypes }, '创建规则');
    return rule;
  }

  updateRule(ruleId: string, updates: Partial<Omit<Rule, 'ruleId' | 'createdAt' | 'triggerCount' | 'lastTriggeredAt'>>): Rule | null {
    const rule = this.rules.get(ruleId);
    if (!rule) return null;

    Object.assign(rule, updates, { updatedAt: new Date().toISOString() });
    logger.info({ ruleId }, '更新规则');
    return rule;
  }

  deleteRule(ruleId: string): boolean {
    const deleted = this.rules.delete(ruleId);
    if (deleted) {
      logger.info({ ruleId }, '删除规则');
    }
    return deleted;
  }

  getRule(ruleId: string): Rule | undefined {
    return this.rules.get(ruleId);
  }

  listRules(eventType?: string): Rule[] {
    let rules = Array.from(this.rules.values());
    if (eventType) {
      rules = rules.filter(r => r.eventTypes.includes(eventType));
    }
    return rules.sort((a, b) => b.priority - a.priority);
  }

  enableRule(ruleId: string): boolean {
    const rule = this.rules.get(ruleId);
    if (!rule) return false;
    rule.enabled = true;
    rule.updatedAt = new Date().toISOString();
    logger.info({ ruleId }, '启用规则');
    return true;
  }

  disableRule(ruleId: string): boolean {
    const rule = this.rules.get(ruleId);
    if (!rule) return false;
    rule.enabled = false;
    rule.updatedAt = new Date().toISOString();
    logger.info({ ruleId }, '禁用规则');
    return true;
  }

  async processEvent(event: EventContext): Promise<Array<{ ruleId: string; matched: boolean; actionsExecuted: number }>> {
    const matchingRules = this.listRules(event.eventType).filter(r => r.enabled);
    const results: Array<{ ruleId: string; matched: boolean; actionsExecuted: number }> = [];

    logger.debug({ eventId: event.eventId, eventType: event.eventType, ruleCount: matchingRules.length }, '处理事件');

    for (const rule of matchingRules) {
      try {
        const matched = this.evaluateCondition(rule.condition, event.data);
        let actionsExecuted = 0;

        if (matched) {
          rule.lastTriggeredAt = new Date().toISOString();
          rule.triggerCount++;

          for (const action of rule.actions) {
            try {
              await this.executeAction(action, event, rule);
              actionsExecuted++;
            } catch (error) {
              logger.error({ ruleId: rule.ruleId, actionType: action.type, error }, '动作执行失败');
            }
          }
        }

        results.push({ ruleId: rule.ruleId, matched, actionsExecuted });
      } catch (error) {
        logger.error({ ruleId: rule.ruleId, error }, '规则评估失败');
        results.push({ ruleId: rule.ruleId, matched: false, actionsExecuted: 0 });
      }
    }

    this.emit(event.eventType, event);
    return results;
  }

  private evaluateCondition(condition: RuleCondition, data: Record<string, unknown>): boolean {
    const results: boolean[] = [];

    for (const cond of condition.conditions) {
      if ('logicalOp' in cond) {
        results.push(this.evaluateCondition(cond, data));
      } else {
        results.push(this.evaluateSingleCondition(cond, data));
      }
    }

    if (condition.logicalOp === 'AND') {
      return results.every(r => r);
    } else {
      return results.some(r => r);
    }
  }

  private evaluateSingleCondition(condition: Condition, data: Record<string, unknown>): boolean {
    const fieldValue = this.getNestedValue(data, condition.field);
    const expectedValue = condition.value;

    switch (condition.operator) {
      case 'eq':
        return fieldValue === expectedValue;
      case 'ne':
        return fieldValue !== expectedValue;
      case 'gt':
        return Number(fieldValue) > Number(expectedValue);
      case 'gte':
        return Number(fieldValue) >= Number(expectedValue);
      case 'lt':
        return Number(fieldValue) < Number(expectedValue);
      case 'lte':
        return Number(fieldValue) <= Number(expectedValue);
      case 'contains':
        return String(fieldValue).includes(String(expectedValue));
      case 'startsWith':
        return String(fieldValue).startsWith(String(expectedValue));
      case 'endsWith':
        return String(fieldValue).endsWith(String(expectedValue));
      case 'in':
        return Array.isArray(expectedValue) && expectedValue.includes(fieldValue);
      case 'notIn':
        return Array.isArray(expectedValue) && !expectedValue.includes(fieldValue);
      case 'regex':
        return new RegExp(String(expectedValue)).test(String(fieldValue));
      default:
        return false;
    }
  }

  private getNestedValue(obj: Record<string, unknown>, path: string): unknown {
    return path.split('.').reduce((current: unknown, key) => {
      if (current && typeof current === 'object' && !Array.isArray(current)) {
        return (current as Record<string, unknown>)[key];
      }
      return undefined;
    }, obj);
  }

  private async executeAction(action: RuleAction, context: EventContext, rule: Rule): Promise<void> {
    const executor = this.actionExecutors.get(action.type);
    if (!executor) {
      logger.warn({ actionType: action.type }, '未找到动作执行器');
      return;
    }
    await executor(action, context, rule);
  }

  on(eventType: string, handler: (context: EventContext) => void): () => void {
    if (!this.eventBus.has(eventType)) {
      this.eventBus.set(eventType, []);
    }
    this.eventBus.get(eventType)!.push(handler);

    return () => {
      const handlers = this.eventBus.get(eventType);
      if (handlers) {
        const index = handlers.indexOf(handler);
        if (index > -1) handlers.splice(index, 1);
      }
    };
  }

  private emit(eventType: string, context: EventContext): void {
    const handlers = this.eventBus.get(eventType) || [];
    for (const handler of handlers) {
      try {
        handler(context);
      } catch (error) {
        logger.error({ eventType, error }, '事件处理函数异常');
      }
    }
  }

  createEvent(eventType: string, data: Record<string, unknown>, source: string = 'rule-engine'): EventContext {
    return {
      eventId: uuidv4(),
      eventType,
      timestamp: Date.now(),
      data,
      source,
      metadata: {}
    };
  }

  getStats(): {
    totalRules: number;
    enabledRules: number;
    totalTriggers: number;
    actionTypes: string[];
  } {
    return {
      totalRules: this.rules.size,
      enabledRules: Array.from(this.rules.values()).filter(r => r.enabled).length,
      totalTriggers: Array.from(this.rules.values()).reduce((sum, r) => sum + r.triggerCount, 0),
      actionTypes: Array.from(this.actionExecutors.keys())
    };
  }
}
