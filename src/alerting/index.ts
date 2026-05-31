import { z } from 'zod';
import cron from 'node-cron';
import { EventEmitter, generateId, parseDuration } from '../utils';
import logger from '../utils/logger';
import { AlertRule, AlertCondition, AlertNotification, AnomalyDetectionConfig } from '../types';
import metricsService from '../metrics';
import anomalyDetector from '../anomaly-detection';

const AlertRuleSchema = z.object({
  rule_id: z.string(),
  name: z.string(),
  description: z.string().optional(),
  enabled: z.boolean(),
  condition: z.object({
    type: z.enum(['threshold', 'burn_rate', 'anomaly', 'expression']),
    metric: z.string().optional(),
    threshold: z.number().optional(),
    operator: z.enum(['lt', 'lte', 'gt', 'gte', 'eq', 'neq']).optional(),
    duration: z.string().optional(),
    burn_rate_threshold: z.number().optional(),
    slo_id: z.string().optional(),
    expression: z.string().optional(),
    anomaly_config: z
      .object({
        algorithm: z.enum(['static_threshold', 'moving_average', 'exponential_smoothing', 'z_score', 'isolation_forest']),
        lookback_period: z.string(),
        sensitivity: z.number(),
        baseline_params: z.record(z.any()).optional(),
      })
      .optional(),
  }),
  notification_channels: z.array(z.string()),
  evaluation_interval: z.string(),
  labels: z.record(z.string()).optional(),
});

interface AlertingEvents {
  'alert.fired': AlertNotification;
  'alert.resolved': { notification_id: string; rule_id: string; timestamp: string };
  'rule.evaluated': { rule_id: string; result: boolean; timestamp: string };
  'notification.sent': { notification_id: string; channel: string };
  'notification.failed': { notification_id: string; channel: string; error: string };
}

export interface NotificationChannel {
  name: string;
  send(notification: AlertNotification): Promise<boolean>;
}

export class ConsoleNotificationChannel implements NotificationChannel {
  name = 'console';

  async send(notification: AlertNotification): Promise<boolean> {
    const level = notification.severity === 'critical' ? 'error' : notification.severity === 'warning' ? 'warn' : 'info';
    logger[level](`[ALERT] ${notification.title}: ${notification.message}`, {
      rule_id: notification.rule_id,
      severity: notification.severity,
    });
    return true;
  }
}

export class WebhookNotificationChannel implements NotificationChannel {
  name: string;
  private webhookUrl: string;

  constructor(name: string, webhookUrl: string) {
    this.name = name;
    this.webhookUrl = webhookUrl;
  }

  async send(notification: AlertNotification): Promise<boolean> {
    try {
      const response = await fetch(this.webhookUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(notification),
      });
      return response.ok;
    } catch (error) {
      logger.error(`Webhook notification failed for channel ${this.name}:`, error);
      return false;
    }
  }
}

export class AlertEngine extends EventEmitter<AlertingEvents> {
  private rules: Map<string, AlertRule>;
  private notificationChannels: Map<string, NotificationChannel>;
  private activeAlerts: Map<string, AlertNotification>;
  private cronJobs: Map<string, cron.ScheduledTask>;
  private stateStore: Map<string, { lastFired: number; consecutiveFailures: number; values: number[] }>;

  constructor() {
    super();
    this.rules = new Map();
    this.notificationChannels = new Map();
    this.activeAlerts = new Map();
    this.cronJobs = new Map();
    this.stateStore = new Map();

    this.registerNotificationChannel(new ConsoleNotificationChannel());
  }

  registerNotificationChannel(channel: NotificationChannel): void {
    this.notificationChannels.set(channel.name, channel);
    logger.info(`Registered notification channel: ${channel.name}`);
  }

  unregisterNotificationChannel(name: string): boolean {
    return this.notificationChannels.delete(name);
  }

  addRule(rule: AlertRule): boolean {
    const validation = AlertRuleSchema.safeParse(rule);
    if (!validation.success) {
      logger.error('Invalid alert rule format:', validation.error);
      return false;
    }

    this.rules.set(rule.rule_id, rule);
    logger.info(`Added alert rule: ${rule.rule_id}`);

    if (rule.enabled) {
      this.scheduleRuleEvaluation(rule);
    }

    return true;
  }

  updateRule(rule: AlertRule): boolean {
    if (!this.rules.has(rule.rule_id)) {
      return false;
    }

    this.unscheduleRuleEvaluation(rule.rule_id);
    const success = this.addRule(rule);
    return success;
  }

  deleteRule(ruleId: string): boolean {
    this.unscheduleRuleEvaluation(ruleId);
    return this.rules.delete(ruleId);
  }

  getRule(ruleId: string): AlertRule | undefined {
    return this.rules.get(ruleId);
  }

  getAllRules(): AlertRule[] {
    return Array.from(this.rules.values());
  }

  enableRule(ruleId: string): boolean {
    const rule = this.rules.get(ruleId);
    if (!rule) return false;

    rule.enabled = true;
    this.scheduleRuleEvaluation(rule);
    logger.info(`Enabled alert rule: ${ruleId}`);
    return true;
  }

  disableRule(ruleId: string): boolean {
    const rule = this.rules.get(ruleId);
    if (!rule) return false;

    rule.enabled = false;
    this.unscheduleRuleEvaluation(ruleId);
    logger.info(`Disabled alert rule: ${ruleId}`);
    return true;
  }

  private scheduleRuleEvaluation(rule: AlertRule): void {
    const intervalMs = parseDuration(rule.evaluation_interval);
    const cronExpression = this.intervalToCron(intervalMs);

    try {
      const task = cron.schedule(cronExpression, () => {
        this.evaluateRule(rule.rule_id).catch((error) => {
          logger.error(`Failed to evaluate rule ${rule.rule_id}:`, error);
        });
      });
      this.cronJobs.set(rule.rule_id, task);
      logger.debug(`Scheduled evaluation for rule ${rule.rule_id}: ${cronExpression}`);
    } catch (error) {
      logger.error(`Failed to schedule rule ${rule.rule_id}:`, error);
    }
  }

  private unscheduleRuleEvaluation(ruleId: string): void {
    const task = this.cronJobs.get(ruleId);
    if (task) {
      task.stop();
      this.cronJobs.delete(ruleId);
      logger.debug(`Unscheduled evaluation for rule ${ruleId}`);
    }
  }

  private intervalToCron(intervalMs: number): string {
    if (intervalMs < 60000) {
      return `*/${Math.max(1, Math.floor(intervalMs / 1000))} * * * * *`;
    } else if (intervalMs < 3600000) {
      return `0 */${Math.max(1, Math.floor(intervalMs / 60000))} * * * *`;
    } else if (intervalMs < 86400000) {
      return `0 0 */${Math.max(1, Math.floor(intervalMs / 3600000))} * * *`;
    } else {
      return `0 0 0 */${Math.max(1, Math.floor(intervalMs / 86400000))} * *`;
    }
  }

  async evaluateRule(ruleId: string): Promise<boolean> {
    const rule = this.rules.get(ruleId);
    if (!rule || !rule.enabled) {
      return false;
    }

    try {
      const isTriggered = await this.evaluateCondition(rule.condition, ruleId);
      this.emit('rule.evaluated', { rule_id: ruleId, result: isTriggered, timestamp: new Date().toISOString() });

      const state = this.stateStore.get(ruleId) || { lastFired: 0, consecutiveFailures: 0, values: [] };

      if (isTriggered) {
        state.consecutiveFailures++;
        const durationMs = rule.condition.duration ? parseDuration(rule.condition.duration) : 0;
        const shouldFire = durationMs === 0 || Date.now() - state.lastFired > durationMs;

        if (shouldFire) {
          await this.fireAlert(rule);
          state.lastFired = Date.now();
        }
      } else {
        if (state.consecutiveFailures > 0) {
          await this.resolveAlert(rule);
        }
        state.consecutiveFailures = 0;
      }

      this.stateStore.set(ruleId, state);
      return isTriggered;
    } catch (error) {
      logger.error(`Error evaluating rule ${ruleId}:`, error);
      return false;
    }
  }

  private async evaluateCondition(condition: AlertCondition, ruleId: string): Promise<boolean> {
    switch (condition.type) {
      case 'threshold':
        return this.evaluateThresholdCondition(condition, ruleId);
      case 'anomaly':
        return this.evaluateAnomalyCondition(condition, ruleId);
      case 'burn_rate':
        return this.evaluateBurnRateCondition(condition, ruleId);
      case 'expression':
        return this.evaluateExpressionCondition(condition, ruleId);
      default:
        return false;
    }
  }

  private async evaluateThresholdCondition(condition: AlertCondition, ruleId: string): Promise<boolean> {
    if (!condition.metric || condition.threshold === undefined) return false;

    const now = Date.now();
    const lookback = condition.duration ? parseDuration(condition.duration) : 300000;
    const startTime = now - lookback;

    const points = await metricsService.getMetricValues(condition.metric, startTime, now);
    if (points.length === 0) return false;

    const values = points.map((p) => p.value);
    const latestValue = values[values.length - 1];

    const state = this.stateStore.get(ruleId) || { lastFired: 0, consecutiveFailures: 0, values: [] };
    state.values.push(latestValue);
    if (state.values.length > 100) state.values.shift();
    this.stateStore.set(ruleId, state);

    const operator = condition.operator || 'gt';
    return this.compareValues(latestValue, condition.threshold, operator);
  }

  private async evaluateAnomalyCondition(condition: AlertCondition, ruleId: string): Promise<boolean> {
    if (!condition.metric || !condition.anomaly_config) return false;

    const anomalyConfig: AnomalyDetectionConfig = condition.anomaly_config;
    const result = anomalyDetector.detect(condition.metric, anomalyConfig);

    return result?.is_anomaly ?? false;
  }

  private async evaluateBurnRateCondition(condition: AlertCondition, ruleId: string): Promise<boolean> {
    if (!condition.slo_id || condition.burn_rate_threshold === undefined) return false;

    const burnRate = await this.getSLOBurnRate(condition.slo_id);
    return burnRate > condition.burn_rate_threshold;
  }

  private async evaluateExpressionCondition(condition: AlertCondition, ruleId: string): Promise<boolean> {
    if (!condition.expression) return false;

    try {
      const result = eval(condition.expression);
      return Boolean(result);
    } catch (error) {
      logger.error(`Expression evaluation failed:`, error);
      return false;
    }
  }

  private compareValues(a: number, b: number, operator: string): boolean {
    switch (operator) {
      case 'gt':
        return a > b;
      case 'gte':
        return a >= b;
      case 'lt':
        return a < b;
      case 'lte':
        return a <= b;
      case 'eq':
        return a === b;
      case 'neq':
        return a !== b;
      default:
        return false;
    }
  }

  private async getSLOBurnRate(sloId: string): Promise<number> {
    const burnRateMetric = `slo_burn_rate_${sloId}`;
    const now = Date.now();
    const points = await metricsService.getMetricValues(burnRateMetric, now - 300000, now);
    if (points.length === 0) return 0;
    return points[points.length - 1].value;
  }

  private async fireAlert(rule: AlertRule): Promise<void> {
    const notification: AlertNotification = {
      notification_id: generateId('notif'),
      rule_id: rule.rule_id,
      severity: this.determineSeverity(rule),
      title: `Alert: ${rule.name}`,
      message: this.buildAlertMessage(rule),
      timestamp: new Date().toISOString(),
      labels: rule.labels,
      status: 'pending',
      channel: 'console',
    };

    this.activeAlerts.set(rule.rule_id, notification);
    this.emit('alert.fired', notification);

    for (const channelName of rule.notification_channels) {
      const channel = this.notificationChannels.get(channelName);
      if (channel) {
        try {
          notification.channel = channelName;
          const sent = await channel.send(notification);
          if (sent) {
            notification.status = 'sent';
            this.emit('notification.sent', { notification_id: notification.notification_id, channel: channelName });
          } else {
            notification.status = 'failed';
            this.emit('notification.failed', {
              notification_id: notification.notification_id,
              channel: channelName,
              error: 'Channel returned false',
            });
          }
        } catch (error) {
          notification.status = 'failed';
          this.emit('notification.failed', {
            notification_id: notification.notification_id,
            channel: channelName,
            error: (error as Error).message,
          });
        }
      }
    }
  }

  private async resolveAlert(rule: AlertRule): Promise<void> {
    const activeAlert = this.activeAlerts.get(rule.rule_id);
    if (activeAlert) {
      this.activeAlerts.delete(rule.rule_id);
      this.emit('alert.resolved', {
        notification_id: activeAlert.notification_id,
        rule_id: rule.rule_id,
        timestamp: new Date().toISOString(),
      });
    }
  }

  private determineSeverity(rule: AlertRule): 'critical' | 'warning' | 'info' {
    if (rule.condition.type === 'burn_rate' && (rule.condition.burn_rate_threshold ?? 0) >= 2) {
      return 'critical';
    }
    if (rule.condition.type === 'threshold' && rule.condition.threshold !== undefined) {
      if (rule.condition.operator === 'gt' && rule.condition.threshold > 0.9) return 'critical';
      if (rule.condition.operator === 'lt' && rule.condition.threshold < 0.1) return 'critical';
    }
    return rule.labels?.severity === 'critical' ? 'critical' : rule.labels?.severity === 'warning' ? 'warning' : 'info';
  }

  private buildAlertMessage(rule: AlertRule): string {
    const state = this.stateStore.get(rule.rule_id);
    const lastValue = state?.values?.[state.values.length - 1] ?? 0;

    switch (rule.condition.type) {
      case 'threshold':
        return `Metric ${rule.condition.metric} crossed threshold ${rule.condition.threshold} (current: ${lastValue.toFixed(4)})`;
      case 'anomaly':
        return `Anomaly detected for metric ${rule.condition.metric}`;
      case 'burn_rate':
        return `SLO ${rule.condition.slo_id} burn rate exceeded threshold ${rule.condition.burn_rate_threshold}`;
      case 'expression':
        return `Alert condition triggered: ${rule.condition.expression}`;
      default:
        return `Alert triggered for rule: ${rule.name}`;
    }
  }

  getActiveAlerts(): AlertNotification[] {
    return Array.from(this.activeAlerts.values());
  }

  getAlertHistory(ruleId?: string): AlertNotification[] {
    if (ruleId) {
      return Array.from(this.activeAlerts.values()).filter((a) => a.rule_id === ruleId);
    }
    return Array.from(this.activeAlerts.values());
  }

  async evaluateAllRules(): Promise<void> {
    for (const ruleId of this.rules.keys()) {
      await this.evaluateRule(ruleId);
    }
  }

  clear(): void {
    for (const task of this.cronJobs.values()) {
      task.stop();
    }
    this.rules.clear();
    this.notificationChannels.clear();
    this.activeAlerts.clear();
    this.cronJobs.clear();
    this.stateStore.clear();
  }
}

const alertEngine = new AlertEngine();

export default alertEngine;
