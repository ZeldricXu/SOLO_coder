import { v4 as uuidv4 } from 'uuid';
import { AlertRule, AlertState, AlertCondition } from '../types';
import { ProcessingPipeline } from '../core';
import { MetricsQueryService } from '../metrics';
import { NotificationRouter } from '../notification';

export interface AlertEvaluationResult {
  ruleId: string;
  state: AlertState['state'];
  value: number;
  threshold: number;
  violated: boolean;
  timestamp: string;
  labels: Record<string, string>;
}

export class AlertRuleParser {
  static parse(rule: Partial<AlertRule>): AlertRule {
    if (!rule.name || !rule.metric || !rule.condition) {
      throw new Error('Alert rule requires name, metric, and condition');
    }

    return {
      id: rule.id || uuidv4(),
      name: rule.name,
      metric: rule.metric,
      condition: rule.condition,
      threshold: rule.threshold ?? rule.condition.threshold,
      duration: rule.duration || 60000,
      severity: rule.severity || 'warning',
      notificationChannels: rule.notificationChannels || [],
      enabled: rule.enabled ?? true,
      labels: rule.labels || {},
    };
  }

  static validate(rule: AlertRule): boolean {
    if (!rule.name.trim()) return false;
    if (!rule.metric.trim()) return false;
    if (!['gt', 'lt', 'gte', 'lte', 'eq', 'neq'].includes(rule.condition.operator)) return false;
    if (typeof rule.condition.threshold !== 'number') return false;
    if (rule.duration < 0) return false;
    return true;
  }
}

export class AlertConditionEvaluator {
  static evaluate(value: number, condition: AlertCondition): boolean {
    switch (condition.operator) {
      case 'gt':
        return value > condition.threshold;
      case 'lt':
        return value < condition.threshold;
      case 'gte':
        return value >= condition.threshold;
      case 'lte':
        return value <= condition.threshold;
      case 'eq':
        return value === condition.threshold;
      case 'neq':
        return value !== condition.threshold;
      default:
        return false;
    }
  }

  static getOperatorSymbol(operator: AlertCondition['operator']): string {
    const symbols: Record<AlertCondition['operator'], string> = {
      gt: '>',
      lt: '<',
      gte: '>=',
      lte: '<=',
      eq: '==',
      neq: '!=',
    };
    return symbols[operator];
  }
}

export class AlertRuleManager {
  private rules: Map<string, AlertRule> = new Map();

  addRule(rule: AlertRule): void {
    if (!AlertRuleParser.validate(rule)) {
      throw new Error('Invalid alert rule');
    }
    this.rules.set(rule.id, rule);
  }

  removeRule(id: string): boolean {
    return this.rules.delete(id);
  }

  getRule(id: string): AlertRule | undefined {
    return this.rules.get(id);
  }

  getRules(): AlertRule[] {
    return Array.from(this.rules.values());
  }

  getEnabledRules(): AlertRule[] {
    return this.getRules().filter(r => r.enabled);
  }

  updateRule(id: string, updates: Partial<AlertRule>): AlertRule {
    const existing = this.rules.get(id);
    if (!existing) {
      throw new Error(`Rule not found: ${id}`);
    }
    const updated = { ...existing, ...updates };
    if (!AlertRuleParser.validate(updated)) {
      throw new Error('Invalid alert rule updates');
    }
    this.rules.set(id, updated);
    return updated;
  }
}

export class AlertStateManager {
  private states: Map<string, AlertState> = new Map();
  private pendingStates: Map<string, { startTime: number; value: number; count: number }> = new Map();

  getState(ruleId: string): AlertState | undefined {
    return this.states.get(ruleId);
  }

  getStates(): AlertState[] {
    return Array.from(this.states.values());
  }

  updateState(
    ruleId: string,
    state: AlertState['state'],
    value: number,
    labels: Record<string, string> = {},
    annotations: Record<string, string> = {}
  ): AlertState {
    const existing = this.states.get(ruleId);
    const now = new Date().toISOString();

    const alertState: AlertState = {
      ruleId,
      state,
      startedAt: existing?.startedAt || now,
      value,
      labels,
      annotations,
    };

    this.states.set(ruleId, alertState);
    return alertState;
  }

  checkPending(
    ruleId: string,
    duration: number,
    value: number,
    violated: boolean
  ): { shouldFire: boolean; isPending: boolean } {
    const key = ruleId;

    if (!violated) {
      this.pendingStates.delete(key);
      return { shouldFire: false, isPending: false };
    }

    const now = Date.now();
    const pending = this.pendingStates.get(key);

    if (!pending) {
      this.pendingStates.set(key, { startTime: now, value, count: 1 });
      return { shouldFire: false, isPending: true };
    }

    pending.value = value;
    pending.count++;

    const elapsed = now - pending.startTime;
    if (elapsed >= duration) {
      this.pendingStates.delete(key);
      return { shouldFire: true, isPending: false };
    }

    return { shouldFire: false, isPending: true };
  }

  resolveState(ruleId: string, value: number): AlertState | null {
    const existing = this.states.get(ruleId);
    if (!existing || existing.state === 'resolved') {
      return null;
    }

    const resolved: AlertState = {
      ...existing,
      state: 'resolved',
      value,
      annotations: {
        ...existing.annotations,
        resolvedAt: new Date().toISOString(),
      },
    };

    this.states.set(ruleId, resolved);
    return resolved;
  }
}

export class AlertEvaluator {
  private ruleManager: AlertRuleManager;
  private stateManager: AlertStateManager;
  private metricsQuery: MetricsQueryService;
  private notificationRouter: NotificationRouter;
  private evaluationInterval: number = 60000;
  private evaluationTimer: NodeJS.Timeout | null = null;

  constructor(
    ruleManager: AlertRuleManager,
    stateManager: AlertStateManager,
    metricsQuery: MetricsQueryService,
    notificationRouter: NotificationRouter
  ) {
    this.ruleManager = ruleManager;
    this.stateManager = stateManager;
    this.metricsQuery = metricsQuery;
    this.notificationRouter = notificationRouter;
  }

  async evaluateRule(rule: AlertRule): Promise<AlertEvaluationResult | null> {
    const now = Date.now();
    const startTime = now - rule.duration * 2;

    const timeSeries = await this.metricsQuery.queryRaw(
      rule.metric,
      rule.labels,
      startTime,
      now
    );

    if (timeSeries.points.length === 0) {
      return null;
    }

    const latestValue = timeSeries.points[timeSeries.points.length - 1].value;
    const violated = AlertConditionEvaluator.evaluate(latestValue, rule.condition);

    return {
      ruleId: rule.id,
      state: violated ? 'firing' : 'resolved',
      value: latestValue,
      threshold: rule.condition.threshold,
      violated,
      timestamp: new Date().toISOString(),
      labels: rule.labels,
    };
  }

  async evaluateAllRules(): Promise<AlertEvaluationResult[]> {
    const results: AlertEvaluationResult[] = [];
    const rules = this.ruleManager.getEnabledRules();

    for (const rule of rules) {
      const result = await this.evaluateRule(rule);
      if (result) {
        results.push(result);
        await this.handleEvaluationResult(rule, result);
      }
    }

    return results;
  }

  private async handleEvaluationResult(rule: AlertRule, result: AlertEvaluationResult): Promise<void> {
    const pendingCheck = this.stateManager.checkPending(
      rule.id,
      rule.duration,
      result.value,
      result.violated
    );

    if (pendingCheck.isPending) {
      this.stateManager.updateState(rule.id, 'pending', result.value, rule.labels, {
        pendingSince: new Date().toISOString(),
      });
      return;
    }

    if (pendingCheck.shouldFire) {
      this.stateManager.updateState(rule.id, 'firing', result.value, rule.labels, {
        firingSince: new Date().toISOString(),
        threshold: String(rule.condition.threshold),
        operator: AlertConditionEvaluator.getOperatorSymbol(rule.condition.operator),
      });

      await this.sendNotification(rule, result);
    } else if (!result.violated) {
      const resolved = this.stateManager.resolveState(rule.id, result.value);
      if (resolved) {
        await this.sendResolvedNotification(rule, resolved);
      }
    }
  }

  private async sendNotification(rule: AlertRule, result: AlertEvaluationResult): Promise<void> {
    const message = `Alert ${rule.severity.toUpperCase()}: ${rule.name} is firing\n` +
      `Metric: ${rule.metric}\n` +
      `Value: ${result.value} ${AlertConditionEvaluator.getOperatorSymbol(rule.condition.operator)} ${rule.condition.threshold}\n` +
      `Labels: ${JSON.stringify(rule.labels)}`;

    await this.notificationRouter.send({
      type: 'alert',
      priority: rule.severity === 'critical' ? 'critical' : 'high',
      title: `[${rule.severity.toUpperCase()}] ${rule.name}`,
      message,
      source: 'alerting',
      tags: ['alert', rule.severity, rule.metric],
    });
  }

  private async sendResolvedNotification(rule: AlertRule, state: AlertState): Promise<void> {
    const message = `Alert RESOLVED: ${rule.name}\n` +
      `Metric: ${rule.metric}\n` +
      `Current Value: ${state.value}\n` +
      `Duration: ${state.startedAt} to ${new Date().toISOString()}\n` +
      `Labels: ${JSON.stringify(rule.labels)}`;

    await this.notificationRouter.send({
      type: 'info',
      priority: 'medium',
      title: `[RESOLVED] ${rule.name}`,
      message,
      source: 'alerting',
      tags: ['alert', 'resolved', rule.metric],
    });
  }

  startAutoEvaluation(interval?: number): void {
    if (this.evaluationTimer) return;
    if (interval) {
      this.evaluationInterval = interval;
    }
    this.evaluationTimer = setInterval(() => this.evaluateAllRules(), this.evaluationInterval);
  }

  stopAutoEvaluation(): void {
    if (this.evaluationTimer) {
      clearInterval(this.evaluationTimer);
      this.evaluationTimer = null;
    }
  }
}

export class AlertPipeline {
  private ruleManager: AlertRuleManager;
  private stateManager: AlertStateManager;
  private evaluator: AlertEvaluator;
  private pipeline: ProcessingPipeline<Partial<AlertRule>, AlertRule>;

  constructor(
    ruleManager: AlertRuleManager,
    stateManager: AlertStateManager,
    evaluator: AlertEvaluator
  ) {
    this.ruleManager = ruleManager;
    this.stateManager = stateManager;
    this.evaluator = evaluator;
    this.pipeline = this.buildPipeline();
  }

  private buildPipeline(): ProcessingPipeline<Partial<AlertRule>, AlertRule> {
    return new ProcessingPipeline<Partial<AlertRule>, AlertRule>()
      .addStage({
        name: 'parsing',
        process: async (input) => AlertRuleParser.parse(input),
      })
      .addStage({
        name: 'validation',
        process: async (rule) => {
          if (!AlertRuleParser.validate(rule)) {
            throw new Error('Invalid alert rule');
          }
          return rule;
        },
      })
      .addStage({
        name: 'registration',
        process: async (rule) => {
          this.ruleManager.addRule(rule);
          return rule;
        },
      });
  }

  async createRule(rule: Partial<AlertRule>): Promise<AlertRule> {
    const result = await this.pipeline.execute(rule);
    if (!result.success || !result.data) {
      throw new Error(result.error || 'Failed to create alert rule');
    }
    return result.data;
  }

  async evaluate(): Promise<AlertEvaluationResult[]> {
    return this.evaluator.evaluateAllRules();
  }

  getFiringAlerts(): AlertState[] {
    return this.stateManager.getStates().filter(s => s.state === 'firing');
  }
}

export function createAlertingModule(
  metricsQuery: MetricsQueryService,
  notificationRouter: NotificationRouter
): {
  ruleManager: AlertRuleManager;
  stateManager: AlertStateManager;
  evaluator: AlertEvaluator;
  pipeline: AlertPipeline;
} {
  const ruleManager = new AlertRuleManager();
  const stateManager = new AlertStateManager();
  const evaluator = new AlertEvaluator(ruleManager, stateManager, metricsQuery, notificationRouter);
  const pipeline = new AlertPipeline(ruleManager, stateManager, evaluator);

  return {
    ruleManager,
    stateManager,
    evaluator,
    pipeline,
  };
}
