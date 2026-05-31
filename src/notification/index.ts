import { v4 as uuidv4 } from 'uuid';
import { Notification, NotificationChannel, SuppressionRule } from '../types';
import { ProcessingPipeline } from '../core';

const PRIORITY_WEIGHTS: Record<Notification['priority'], number> = {
  low: 1,
  medium: 2,
  high: 3,
  critical: 4,
};

export interface NotificationResult {
  notificationId: string;
  delivered: boolean;
  suppressed: boolean;
  channels: string[];
  errors: string[];
}

export interface ChannelSender {
  send(notification: Notification, channel: NotificationChannel): Promise<boolean>;
}

export interface PriorityStrategy {
  id: string;
  name: string;
  getPriority(notification: Notification): Notification['priority'];
  compare(a: Notification, b: Notification): number;
}

export interface SuppressionStrategy {
  id: string;
  name: string;
  shouldSuppress(notification: Notification, context: SuppressionContext): { suppressed: boolean; reason?: string };
  recordSuppression(notification: Notification, rule: SuppressionRule): void;
}

export interface RoutingStrategy {
  id: string;
  name: string;
  getEligibleChannels(
    notification: Notification,
    channels: NotificationChannel[]
  ): NotificationChannel[];
}

export interface SuppressionContext {
  rules: SuppressionRule[];
  activeSuppressions: Map<string, { count: number; expiresAt: number }>;
  now: number;
}

export interface NotificationModule {
  channelManager: ChannelManager;
  suppressionManager: PluggableSuppressionManager;
  router: PluggableNotificationRouter;
  batcher: NotificationBatcher;
  strategyRegistry: NotificationStrategyRegistry;
}

export class EmailSender implements ChannelSender {
  async send(notification: Notification, channel: NotificationChannel): Promise<boolean> {
    const config = channel.config as { to?: string[]; from?: string };
    console.log(`[EMAIL] Sending notification to ${config.to?.join(', ') || 'unknown'}: ${notification.title}`);
    return true;
  }
}

export class SlackSender implements ChannelSender {
  async send(notification: Notification, channel: NotificationChannel): Promise<boolean> {
    const config = channel.config as { webhookUrl?: string };
    console.log(`[SLACK] Sending notification to ${config.webhookUrl || 'unknown'}: ${notification.title}`);
    return true;
  }
}

export class WebhookSender implements ChannelSender {
  async send(notification: Notification, channel: NotificationChannel): Promise<boolean> {
    const config = channel.config as { url?: string };
    console.log(`[WEBHOOK] Sending notification to ${config.url || 'unknown'}: ${notification.title}`);
    return true;
  }
}

export class SMSSender implements ChannelSender {
  async send(notification: Notification, channel: NotificationChannel): Promise<boolean> {
    const config = channel.config as { phone?: string };
    console.log(`[SMS] Sending notification to ${config.phone || 'unknown'}: ${notification.title}`);
    return true;
  }
}

export class DefaultPriorityStrategy implements PriorityStrategy {
  id = 'default-priority';
  name = 'Default Priority Strategy';

  getPriority(notification: Notification): Notification['priority'] {
    return notification.priority;
  }

  compare(a: Notification, b: Notification): number {
    return PRIORITY_WEIGHTS[b.priority] - PRIORITY_WEIGHTS[a.priority];
  }
}

export class TypeBasedPriorityStrategy implements PriorityStrategy {
  id = 'type-based-priority';
  name = 'Type-Based Priority Strategy';

  private typePriorityMap: Record<Notification['type'], Notification['priority']> = {
    critical: 'critical',
    alert: 'high',
    warning: 'medium',
    info: 'low',
  };

  getPriority(notification: Notification): Notification['priority'] {
    return this.typePriorityMap[notification.type] || notification.priority;
  }

  compare(a: Notification, b: Notification): number {
    const priorityA = this.getPriority(a);
    const priorityB = this.getPriority(b);
    return PRIORITY_WEIGHTS[priorityB] - PRIORITY_WEIGHTS[priorityA];
  }
}

export class ThrottledPriorityStrategy implements PriorityStrategy {
  id = 'throttled-priority';
  name = 'Throttled Priority Strategy';

  private sourceCount: Map<string, { count: number; windowStart: number }> = new Map();
  private windowMs = 60000;
  private threshold = 10;

  getPriority(notification: Notification): Notification['priority'] {
    const now = Date.now();
    const sourceKey = notification.source || 'unknown';
    const sourceData = this.sourceCount.get(sourceKey) || { count: 0, windowStart: now };

    if (now - sourceData.windowStart > this.windowMs) {
      sourceData.count = 0;
      sourceData.windowStart = now;
    }

    sourceData.count++;
    this.sourceCount.set(sourceKey, sourceData);

    if (sourceData.count > this.threshold) {
      if (notification.priority === 'critical') return 'high';
      if (notification.priority === 'high') return 'medium';
      if (notification.priority === 'medium') return 'low';
    }

    return notification.priority;
  }

  compare(a: Notification, b: Notification): number {
    const priorityA = this.getPriority(a);
    const priorityB = this.getPriority(b);
    return PRIORITY_WEIGHTS[priorityB] - PRIORITY_WEIGHTS[priorityA];
  }
}

export class RuleBasedSuppressionStrategy implements SuppressionStrategy {
  id = 'rule-based-suppression';
  name = 'Rule-Based Suppression Strategy';

  shouldSuppress(notification: Notification, context: SuppressionContext): { suppressed: boolean; reason?: string } {
    for (const rule of context.rules.filter(r => r.enabled)) {
      if (this.matchRule(notification, rule)) {
        const key = this.getSuppressionKey(notification, rule);
        const suppression = context.activeSuppressions.get(key);

        if (suppression && suppression.expiresAt > context.now) {
          if (suppression.count < rule.maxSuppressions) {
            suppression.count++;
            return { suppressed: true, reason: `Suppressed by rule: ${rule.name}` };
          }
        } else {
          context.activeSuppressions.set(key, {
            count: 1,
            expiresAt: context.now + rule.duration,
          });
        }
      }
    }

    return { suppressed: false };
  }

  recordSuppression(notification: Notification, rule: SuppressionRule): void {
  }

  private matchRule(notification: Notification, rule: SuppressionRule): boolean {
    const matcher = rule.matcher;

    if (matcher.priority && notification.priority !== matcher.priority) {
      return false;
    }

    if (matcher.source && notification.source !== matcher.source) {
      return false;
    }

    if (matcher.tags && matcher.tags.length > 0) {
      const hasMatchingTag = matcher.tags.some(tag => notification.tags.includes(tag));
      if (!hasMatchingTag) {
        return false;
      }
    }

    return true;
  }

  private getSuppressionKey(notification: Notification, rule: SuppressionRule): string {
    const parts = [rule.id];
    if (rule.matcher.source) {
      parts.push(notification.source);
    }
    if (rule.matcher.tags) {
      const matchingTags = rule.matcher.tags.filter(t => notification.tags.includes(t));
      parts.push(matchingTags.join(','));
    }
    return parts.join('|');
  }
}

export class RateLimitSuppressionStrategy implements SuppressionStrategy {
  id = 'rate-limit-suppression';
  name = 'Rate Limit Suppression Strategy';

  private notificationCount: Map<string, { count: number; windowStart: number }> = new Map();
  private windowMs = 60000;
  private maxPerWindow = 100;

  shouldSuppress(notification: Notification, context: SuppressionContext): { suppressed: boolean; reason?: string } {
    const now = context.now;
    const key = `rate_${notification.source || 'global'}`;
    const countData = this.notificationCount.get(key) || { count: 0, windowStart: now };

    if (now - countData.windowStart > this.windowMs) {
      countData.count = 0;
      countData.windowStart = now;
    }

    countData.count++;
    this.notificationCount.set(key, countData);

    if (countData.count > this.maxPerWindow) {
      return { suppressed: true, reason: `Rate limited: ${countData.count}/${this.maxPerWindow} per minute` };
    }

    return { suppressed: false };
  }

  recordSuppression(notification: Notification, rule: SuppressionRule): void {
  }

  setMaxPerWindow(max: number): void {
    this.maxPerWindow = max;
  }

  setWindowMs(windowMs: number): void {
    this.windowMs = windowMs;
  }
}

export class DefaultRoutingStrategy implements RoutingStrategy {
  id = 'default-routing';
  name = 'Default Routing Strategy';

  getEligibleChannels(
    notification: Notification,
    channels: NotificationChannel[]
  ): NotificationChannel[] {
    return channels.filter(channel => {
      if (!channel.enabled) return false;
      return PRIORITY_WEIGHTS[notification.priority] >= PRIORITY_WEIGHTS[channel.priorityThreshold];
    });
  }
}

export class MultiChannelRoutingStrategy implements RoutingStrategy {
  id = 'multi-channel-routing';
  name = 'Multi-Channel Routing Strategy';

  private escalationDelay: number = 300000;

  getEligibleChannels(
    notification: Notification,
    channels: NotificationChannel[]
  ): NotificationChannel[] {
    const eligible = channels.filter(channel => {
      if (!channel.enabled) return false;
      return PRIORITY_WEIGHTS[notification.priority] >= PRIORITY_WEIGHTS[channel.priorityThreshold];
    });

    if (notification.priority === 'critical') {
      return eligible;
    }

    return eligible.slice(0, 1);
  }

  setEscalationDelay(delayMs: number): void {
    this.escalationDelay = delayMs;
  }
}

export class ChannelManager {
  private channels: Map<string, NotificationChannel> = new Map();
  private senders: Map<NotificationChannel['type'], ChannelSender> = new Map();

  constructor() {
    this.senders.set('email', new EmailSender());
    this.senders.set('slack', new SlackSender());
    this.senders.set('webhook', new WebhookSender());
    this.senders.set('sms', new SMSSender());
  }

  addChannel(channel: NotificationChannel): void {
    this.channels.set(channel.id, channel);
  }

  removeChannel(id: string): boolean {
    return this.channels.delete(id);
  }

  getChannels(): NotificationChannel[] {
    return Array.from(this.channels.values());
  }

  getChannel(id: string): NotificationChannel | undefined {
    return this.channels.get(id);
  }

  getEligibleChannels(priority: Notification['priority']): NotificationChannel[] {
    return this.getChannels().filter(channel => {
      if (!channel.enabled) return false;
      return PRIORITY_WEIGHTS[priority] >= PRIORITY_WEIGHTS[channel.priorityThreshold];
    });
  }

  async sendToChannel(notification: Notification, channel: NotificationChannel): Promise<boolean> {
    const sender = this.senders.get(channel.type);
    if (!sender) {
      throw new Error(`No sender found for channel type: ${channel.type}`);
    }
    return sender.send(notification, channel);
  }

  registerSender(type: string, sender: ChannelSender): void {
    this.senders.set(type as NotificationChannel['type'], sender);
  }
}

export class NotificationStrategyRegistry {
  private priorityStrategies: Map<string, PriorityStrategy> = new Map();
  private suppressionStrategies: Map<string, SuppressionStrategy> = new Map();
  private routingStrategies: Map<string, RoutingStrategy> = new Map();
  private activePriorityStrategyId: string;
  private activeSuppressionStrategyId: string;
  private activeRoutingStrategyId: string;

  constructor() {
    this.registerPriorityStrategy(new DefaultPriorityStrategy());
    this.registerPriorityStrategy(new TypeBasedPriorityStrategy());
    this.registerPriorityStrategy(new ThrottledPriorityStrategy());
    this.registerSuppressionStrategy(new RuleBasedSuppressionStrategy());
    this.registerSuppressionStrategy(new RateLimitSuppressionStrategy());
    this.registerRoutingStrategy(new DefaultRoutingStrategy());
    this.registerRoutingStrategy(new MultiChannelRoutingStrategy());

    this.activePriorityStrategyId = 'default-priority';
    this.activeSuppressionStrategyId = 'rule-based-suppression';
    this.activeRoutingStrategyId = 'default-routing';
  }

  registerPriorityStrategy(strategy: PriorityStrategy): void {
    this.priorityStrategies.set(strategy.id, strategy);
  }

  registerSuppressionStrategy(strategy: SuppressionStrategy): void {
    this.suppressionStrategies.set(strategy.id, strategy);
  }

  registerRoutingStrategy(strategy: RoutingStrategy): void {
    this.routingStrategies.set(strategy.id, strategy);
  }

  unregisterPriorityStrategy(id: string): boolean {
    if (id === this.activePriorityStrategyId) return false;
    return this.priorityStrategies.delete(id);
  }

  unregisterSuppressionStrategy(id: string): boolean {
    if (id === this.activeSuppressionStrategyId) return false;
    return this.suppressionStrategies.delete(id);
  }

  unregisterRoutingStrategy(id: string): boolean {
    if (id === this.activeRoutingStrategyId) return false;
    return this.routingStrategies.delete(id);
  }

  getActivePriorityStrategy(): PriorityStrategy {
    return this.priorityStrategies.get(this.activePriorityStrategyId)!;
  }

  getActiveSuppressionStrategy(): SuppressionStrategy {
    return this.suppressionStrategies.get(this.activeSuppressionStrategyId)!;
  }

  getActiveRoutingStrategy(): RoutingStrategy {
    return this.routingStrategies.get(this.activeRoutingStrategyId)!;
  }

  setActivePriorityStrategy(id: string): boolean {
    if (!this.priorityStrategies.has(id)) return false;
    this.activePriorityStrategyId = id;
    return true;
  }

  setActiveSuppressionStrategy(id: string): boolean {
    if (!this.suppressionStrategies.has(id)) return false;
    this.activeSuppressionStrategyId = id;
    return true;
  }

  setActiveRoutingStrategy(id: string): boolean {
    if (!this.routingStrategies.has(id)) return false;
    this.activeRoutingStrategyId = id;
    return true;
  }

  getPriorityStrategies(): PriorityStrategy[] {
    return Array.from(this.priorityStrategies.values());
  }

  getSuppressionStrategies(): SuppressionStrategy[] {
    return Array.from(this.suppressionStrategies.values());
  }

  getRoutingStrategies(): RoutingStrategy[] {
    return Array.from(this.routingStrategies.values());
  }

  listPriorityStrategies(): PriorityStrategy[] {
    return this.getPriorityStrategies();
  }

  listSuppressionStrategies(): SuppressionStrategy[] {
    return this.getSuppressionStrategies();
  }

  listRoutingStrategies(): RoutingStrategy[] {
    return this.getRoutingStrategies();
  }
}

export class PluggableSuppressionManager {
  private rules: Map<string, SuppressionRule> = new Map();
  private activeSuppressions: Map<string, { count: number; expiresAt: number }> = new Map();
  private strategyRegistry: NotificationStrategyRegistry;

  constructor(strategyRegistry: NotificationStrategyRegistry) {
    this.strategyRegistry = strategyRegistry;
  }

  addRule(rule: SuppressionRule): void {
    this.rules.set(rule.id, rule);
  }

  removeRule(id: string): boolean {
    return this.rules.delete(id);
  }

  getRules(): SuppressionRule[] {
    return Array.from(this.rules.values());
  }

  checkSuppression(notification: Notification): { suppressed: boolean; reason?: string } {
    const context: SuppressionContext = {
      rules: this.getRules(),
      activeSuppressions: this.activeSuppressions,
      now: Date.now(),
    };

    const strategy = this.strategyRegistry.getActiveSuppressionStrategy();
    const result = strategy.shouldSuppress(notification, context);

    this.cleanupExpired();
    return result;
  }

  private cleanupExpired(): void {
    const now = Date.now();
    for (const [key, suppression] of this.activeSuppressions.entries()) {
      if (suppression.expiresAt <= now) {
        this.activeSuppressions.delete(key);
      }
    }
  }

  getActiveSuppressions(): Map<string, { count: number; expiresAt: number }> {
    return new Map(this.activeSuppressions);
  }
}

export class PluggableNotificationRouter {
  private channelManager: ChannelManager;
  private suppressionManager: PluggableSuppressionManager;
  private strategyRegistry: NotificationStrategyRegistry;
  private pipeline: ProcessingPipeline<Notification, NotificationResult>;

  constructor(
    channelManager: ChannelManager,
    suppressionManager: PluggableSuppressionManager,
    strategyRegistry: NotificationStrategyRegistry
  ) {
    this.channelManager = channelManager;
    this.suppressionManager = suppressionManager;
    this.strategyRegistry = strategyRegistry;
    this.pipeline = this.buildPipeline();
  }

  private buildPipeline(): ProcessingPipeline<Notification, NotificationResult> {
    return new ProcessingPipeline<Notification, NotificationResult>()
      .addStage({
        name: 'validation',
        process: async (notification) => this.validateNotification(notification),
      })
      .addStage({
        name: 'priority_assignment',
        process: async (notification) => this.applyPriorityStrategy(notification),
      })
      .addStage({
        name: 'suppression_check',
        process: async (notification) => notification,
      })
      .addStage({
        name: 'delivery',
        process: async (notification) => this.deliverNotification(notification),
      });
  }

  private validateNotification(notification: Notification): Notification {
    if (!notification.title || !notification.message) {
      throw new Error('Notification title and message are required');
    }
    return notification;
  }

  private applyPriorityStrategy(notification: Notification): Notification {
    const strategy = this.strategyRegistry.getActivePriorityStrategy();
    const effectivePriority = strategy.getPriority(notification);
    return { ...notification, priority: effectivePriority };
  }

  private async deliverNotification(notification: Notification): Promise<NotificationResult> {
    const result: NotificationResult = {
      notificationId: notification.id,
      delivered: false,
      suppressed: false,
      channels: [],
      errors: [],
    };

    const suppressionCheck = this.suppressionManager.checkSuppression(notification);
    if (suppressionCheck.suppressed) {
      result.suppressed = true;
      result.errors.push(suppressionCheck.reason || 'Suppressed');
      return result;
    }

    const routingStrategy = this.strategyRegistry.getActiveRoutingStrategy();
    const channels = routingStrategy.getEligibleChannels(
      notification,
      this.channelManager.getChannels()
    );

    for (const channel of channels) {
      try {
        const success = await this.channelManager.sendToChannel(notification, channel);
        if (success) {
          result.channels.push(channel.id);
        }
      } catch (error) {
        result.errors.push(`Channel ${channel.id}: ${(error as Error).message}`);
      }
    }

    result.delivered = result.channels.length > 0;
    return result;
  }

  async send(notification: Partial<Notification>): Promise<NotificationResult> {
    const fullNotification: Notification = {
      id: notification.id || uuidv4(),
      type: notification.type || 'info',
      priority: notification.priority || 'low',
      title: notification.title || '',
      message: notification.message || '',
      source: notification.source || 'system',
      tags: notification.tags || [],
      createdAt: notification.createdAt || new Date().toISOString(),
    };

    const result = await this.pipeline.execute(fullNotification);
    if (!result.success || !result.data) {
      return {
        notificationId: fullNotification.id,
        delivered: false,
        suppressed: false,
        channels: [],
        errors: [result.error || 'Failed to process notification'],
      };
    }
    return result.data;
  }

  async broadcast(
    notifications: Partial<Notification>[]
  ): Promise<NotificationResult[]> {
    const fullNotifications = notifications.map(n => ({
      id: n.id || uuidv4(),
      type: n.type || 'info',
      priority: n.priority || 'low',
      title: n.title || '',
      message: n.message || '',
      source: n.source || 'system',
      tags: n.tags || [],
      createdAt: n.createdAt || new Date().toISOString(),
    } as Notification));

    const priorityStrategy = this.strategyRegistry.getActivePriorityStrategy();
    const sorted = [...fullNotifications].sort((a, b) => priorityStrategy.compare(a, b));

    return Promise.all(sorted.map(n => this.send(n)));
  }

  getStrategyRegistry(): NotificationStrategyRegistry {
    return this.strategyRegistry;
  }
}

export class NotificationBatcher {
  private batch: Notification[] = [];
  private maxBatchSize: number = 100;
  private maxWaitTime: number = 5000;
  private flushTimer: NodeJS.Timeout | null = null;
  private router: PluggableNotificationRouter;
  private onFlush: ((results: NotificationResult[]) => void) | null = null;

  constructor(router: PluggableNotificationRouter) {
    this.router = router;
  }

  setOnFlush(callback: (results: NotificationResult[]) => void): void {
    this.onFlush = callback;
  }

  add(notification: Notification): void {
    this.batch.push(notification);
    this.scheduleFlush();
    if (this.batch.length >= this.maxBatchSize) {
      this.flush();
    }
  }

  private scheduleFlush(): void {
    if (this.flushTimer) return;
    this.flushTimer = setTimeout(() => this.flush(), this.maxWaitTime);
  }

  async flush(): Promise<void> {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }

    if (this.batch.length === 0) return;

    const batch = [...this.batch];
    this.batch = [];

    const results = await this.router.broadcast(batch);
    if (this.onFlush) {
      this.onFlush(results);
    }
  }

  setMaxBatchSize(size: number): void {
    this.maxBatchSize = size;
  }

  setMaxWaitTime(ms: number): void {
    this.maxWaitTime = ms;
  }

  getBatchSize(): number {
    return this.batch.length;
  }
}

export function createNotificationModule(): NotificationModule {
  const strategyRegistry = new NotificationStrategyRegistry();
  const channelManager = new ChannelManager();
  const suppressionManager = new PluggableSuppressionManager(strategyRegistry);
  const router = new PluggableNotificationRouter(channelManager, suppressionManager, strategyRegistry);
  const batcher = new NotificationBatcher(router);

  return {
    channelManager,
    suppressionManager,
    router,
    batcher,
    strategyRegistry,
  };
}
