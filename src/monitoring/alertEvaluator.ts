import { AlertRule, Alert, NotificationChannel, NotificationPayload } from './types';
import { MetricsCollector, metricsCollector } from './metricsCollector';
import { generateId, currentDateTime, logger, delay } from '../utils/common';
import axios from 'axios';

export class AlertEvaluator {
  private rules: Map<string, AlertRule> = new Map();
  private activeAlerts: Map<string, Alert> = new Map();
  private metricsCollector: MetricsCollector;
  private notificationChannels: Map<string, NotificationChannel> = new Map();
  private lastFiredTimes: Map<string, number> = new Map();

  constructor() {
    this.metricsCollector = metricsCollector;
  }

  addRule(rule: Omit<AlertRule, 'ruleId'>): AlertRule {
    const ruleId = generateId('alrt_');
    const fullRule: AlertRule = {
      ...rule,
      ruleId,
      lastFiredAt: null,
    } as AlertRule;

    this.rules.set(ruleId, fullRule);
    logger.info(`Alert rule added`, { ruleId, name: rule.name });
    return fullRule;
  }

  getRule(ruleId: string): AlertRule | undefined {
    return this.rules.get(ruleId);
  }

  updateRule(ruleId: string, updates: Partial<AlertRule>): AlertRule | undefined {
    const rule = this.rules.get(ruleId);
    if (!rule) return undefined;

    const updated: AlertRule = { ...rule, ...updates };
    this.rules.set(ruleId, updated);
    logger.info(`Alert rule updated`, { ruleId });
    return updated;
  }

  deleteRule(ruleId: string): boolean {
    return this.rules.delete(ruleId);
  }

  listRules(): AlertRule[] {
    return Array.from(this.rules.values());
  }

  registerNotificationChannel(channel: Omit<NotificationChannel, 'channelId'>): NotificationChannel {
    const channelId = generateId('ch_');
    const fullChannel: NotificationChannel = { ...channel, channelId } as NotificationChannel;
    this.notificationChannels.set(channelId, fullChannel);
    logger.info(`Notification channel registered`, { channelId, type: channel.type });
    return fullChannel;
  }

  getNotificationChannel(channelId: string): NotificationChannel | undefined {
    return this.notificationChannels.get(channelId);
  }

  listNotificationChannels(): NotificationChannel[] {
    return Array.from(this.notificationChannels.values());
  }

  async evaluateRules(): Promise<{ evaluated: number; fired: number; resolved: number }> {
    const now = Date.now();
    let fired = 0;
    let resolved = 0;

    for (const rule of this.rules.values()) {
      if (!rule.enabled) continue;

      const lastFired = this.lastFiredTimes.get(rule.ruleId) || 0;
      if (now - lastFired < rule.cooldownMs) continue;

      const aggregated = this.metricsCollector.aggregate({
        name: rule.metricName,
        labels: rule.labels,
      });

      if (!aggregated) continue;

      const currentValue = this.getValue(rule.condition.operator, aggregated);
      const isFiring = this.evaluateCondition(currentValue, rule.condition.operator, rule.condition.threshold);

      const existingAlert = Array.from(this.activeAlerts.values()).find(
        a => a.ruleId === rule.ruleId && !a.resolved
      );

      if (isFiring && !existingAlert) {
        const alert = await this.fireAlert(rule, currentValue);
        this.activeAlerts.set(alert.alertId, alert);
        this.lastFiredTimes.set(rule.ruleId, now);
        fired++;
      } else if (!isFiring && existingAlert) {
        existingAlert.resolved = true;
        existingAlert.resolvedAt = currentDateTime();
        resolved++;
        logger.info(`Alert resolved`, { alertId: existingAlert.alertId, rule: rule.name });
      }
    }

    logger.info(`Alert evaluation completed`, {
      evaluated: this.rules.size,
      fired,
      resolved,
      active: Array.from(this.activeAlerts.values()).filter(a => !a.resolved).length,
    });

    return { evaluated: this.rules.size, fired, resolved };
  }

  private getValue(operator: string, aggregated: any): number {
    if (operator === '>' || operator === '>=') {
      return aggregated.max;
    }
    if (operator === '<' || operator === '<=') {
      return aggregated.min;
    }
    return aggregated.avg;
  }

  private evaluateCondition(value: number, operator: string, threshold: number): boolean {
    switch (operator) {
      case '>': return value > threshold;
      case '<': return value < threshold;
      case '>=': return value >= threshold;
      case '<=': return value <= threshold;
      case '==': return value === threshold;
      case '!=': return value !== threshold;
      default: return false;
    }
  }

  private async fireAlert(rule: AlertRule, currentValue: number): Promise<Alert> {
    const alert: Alert = {
      alertId: generateId('alert_'),
      ruleId: rule.ruleId,
      name: rule.name,
      severity: rule.severity,
      message: `${rule.name}: ${rule.metricName} is ${currentValue} which ${rule.condition.operator} ${rule.condition.threshold}`,
      metricName: rule.metricName,
      currentValue,
      threshold: rule.condition.threshold,
      labels: rule.labels,
      firedAt: currentDateTime(),
      resolved: false,
      resolvedAt: null,
      notifications: [],
    };

    logger.warn(`Alert fired`, {
      alertId: alert.alertId,
      name: rule.name,
      severity: rule.severity,
      currentValue,
      threshold: rule.condition.threshold,
    });

    for (const channelId of rule.notificationChannels) {
      const notification: Alert['notifications'][number] = {
        channelId,
        status: 'pending',
        sentAt: null,
        error: undefined,
      };
      alert.notifications.push(notification);

      this.sendNotification(alert, rule, channelId)
        .then(() => {
          notification.status = 'sent';
          notification.sentAt = currentDateTime();
        })
        .catch((error) => {
          notification.status = 'failed';
          notification.error = error.message;
        });
    }

    rule.lastFiredAt = currentDateTime();

    return alert;
  }

  private async sendNotification(alert: Alert, rule: AlertRule, channelId: string): Promise<void> {
    const channel = this.notificationChannels.get(channelId);
    if (!channel || !channel.enabled) return;

    const payload: NotificationPayload = { alert, rule };

    try {
      switch (channel.type) {
        case 'webhook':
          await this.sendWebhook(channel, payload);
          break;
        case 'email':
          logger.info(`Email notification sent`, { to: channel.config.to, subject: alert.name });
          break;
        case 'slack':
          logger.info(`Slack notification sent`, { channel: channel.config.channel });
          break;
        default:
          logger.debug(`Notification sent via ${channel.type}`, { channelId });
      }
    } catch (error) {
      logger.error(`Failed to send notification`, {
        channelId,
        error: error instanceof Error ? error.message : 'Unknown error',
      });
      throw error;
    }
  }

  private async sendWebhook(channel: NotificationChannel, payload: NotificationPayload): Promise<void> {
    const url = channel.config.url as string;
    if (!url) throw new Error('Webhook URL not configured');

    await axios.post(url, payload, {
      timeout: 5000,
    });
  }

  getActiveAlerts(): Alert[] {
    return Array.from(this.activeAlerts.values()).filter(a => !a.resolved);
  }

  getAllAlerts(limit?: number): Alert[] {
    const alerts = Array.from(this.activeAlerts.values());
    if (limit) {
      return alerts.slice(-limit);
    }
    return alerts;
  }

  getAlert(alertId: string): Alert | undefined {
    return this.activeAlerts.get(alertId);
  }
}

export const alertEvaluator = new AlertEvaluator();
