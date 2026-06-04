import { 
  NotificationRequest, 
  ChannelType, 
  ChannelResult, 
  RoutingRule, 
  RoutingCondition,
  NotificationPriority,
  IChannelAdapter
} from '../types';
import { AdapterManager } from '../adapters/AdapterManager';
import { db } from '../db';
import { logger } from '../utils/logger';
import { v4 as uuidv4 } from 'uuid';

export class NotificationRouter {
  private adapterManager: AdapterManager;
  private static instance: NotificationRouter;

  private constructor() {
    this.adapterManager = AdapterManager.getInstance();
  }

  public static getInstance(): NotificationRouter {
    if (!NotificationRouter.instance) {
      NotificationRouter.instance = new NotificationRouter();
    }
    return NotificationRouter.instance;
  }

  public async route(notification: NotificationRequest): Promise<{
    delivery_id: string;
    channels: ChannelType[];
    results?: ChannelResult[];
  }> {
    const deliveryId = uuidv4();
    logger.info('Routing notification', { 
      deliveryId, 
      tenantId: notification.tenant_id,
      type: notification.notification_type 
    });

    const channels = await this.determineChannels(notification);
    logger.info('Determined channels', { deliveryId, channels });

    if (channels.length === 0) {
      logger.warn('No available channels for notification', { deliveryId });
      return { delivery_id: deliveryId, channels: [] };
    }

    if (notification.omnichannel && notification.priority === 'urgent') {
      const results = await this.sendOmnichannel(notification, channels);
      return { delivery_id: deliveryId, channels, results };
    }

    return { delivery_id: deliveryId, channels };
  }

  private async determineChannels(notification: NotificationRequest): Promise<ChannelType[]> {
    const rules = await this.getRoutingRules(notification.tenant_id);
    let channels: ChannelType[] = notification.channel_preference || [];
    let priority: NotificationPriority = notification.priority || 'medium';

    for (const rule of rules) {
      if (this.evaluateConditions(rule.conditions, notification)) {
        for (const action of rule.actions) {
          switch (action.type) {
            case 'set_channel':
              channels = action.params.channels || channels;
              break;
            case 'set_priority':
              priority = action.params.priority || priority;
              break;
            case 'ab_test':
              channels = this.applyABTest(channels, action.params);
              break;
            case 'gray_release':
              channels = this.applyGrayRelease(channels, action.params);
              break;
          }
        }
      }
    }

    notification.priority = priority;

    channels = await this.filterByAvailability(channels);
    channels = await this.filterByUserPreferences(notification, channels);

    return channels;
  }

  private async getRoutingRules(tenantId: string): Promise<RoutingRule[]> {
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          'SELECT * FROM routing_rules WHERE enabled = true ORDER BY priority DESC',
          []
        );
      });
      return result.rows || [];
    } catch (err) {
      logger.error('Failed to get routing rules', err);
      return [];
    }
  }

  private evaluateConditions(conditions: RoutingCondition[], notification: NotificationRequest): boolean {
    for (const condition of conditions) {
      const value = this.getNestedValue(notification, condition.field);
      if (!this.matchCondition(value, condition.operator, condition.value)) {
        return false;
      }
    }
    return true;
  }

  private getNestedValue(obj: any, path: string): any {
    return path.split('.').reduce((acc, part) => acc?.[part], obj);
  }

  private matchCondition(value: any, operator: string, target: any): boolean {
    switch (operator) {
      case 'eq': return value === target;
      case 'ne': return value !== target;
      case 'gt': return value > target;
      case 'lt': return value < target;
      case 'contains': return String(value).includes(String(target));
      case 'in': return Array.isArray(target) && target.includes(value);
      default: return false;
    }
  }

  private applyABTest(channels: ChannelType[], params: any): ChannelType[] {
    const ratio = params.ratio || 0.5;
    const testChannels = params.test_channels || [];
    const controlChannels = params.control_channels || channels;
    
    return Math.random() < ratio ? testChannels : controlChannels;
  }

  private applyGrayRelease(channels: ChannelType[], params: any): ChannelType[] {
    const percentage = params.percentage || 0;
    const newChannels = params.new_channels || [];
    
    if (Math.random() * 100 < percentage) {
      return newChannels;
    }
    return channels;
  }

  private async filterByAvailability(channels: ChannelType[]): Promise<ChannelType[]> {
    const available: ChannelType[] = [];
    
    for (const channel of channels) {
      const adapter = this.adapterManager.getAdapter(channel);
      if (adapter) {
        const status = await adapter.getStatus();
        if (status.available) {
          available.push(channel);
        }
      }
    }

    return available;
  }

  private async filterByUserPreferences(
    notification: NotificationRequest, 
    channels: ChannelType[]
  ): Promise<ChannelType[]> {
    if (!notification.recipient.user_id) {
      return channels;
    }

    try {
      const result = await db.withTenantContext(notification.tenant_id, async () => {
        return await db.query(
          'SELECT channel_preferences, do_not_disturb FROM user_preferences WHERE user_id = $1',
          [notification.recipient.user_id]
        );
      });

      if (result.rowCount === 0) {
        return channels;
      }

      const prefs = result.rows[0];
      const channelPrefs = prefs.channel_preferences || [];
      const dnd = prefs.do_not_disturb || {};

      if (this.isInDoNotDisturb(dnd) && notification.priority !== 'urgent') {
        logger.info('Notification suppressed due to DND', { 
          userId: notification.recipient.user_id 
        });
        return [];
      }

      return channels.filter(channel => {
        const pref = channelPrefs.find(
          (p: any) => p.channel === channel && p.notification_type === notification.notification_type
        );
        return pref ? pref.opted_in : true;
      });
    } catch (err) {
      logger.error('Failed to filter by user preferences', err);
      return channels;
    }
  }

  private isInDoNotDisturb(dnd: any): boolean {
    if (!dnd.enabled) return false;

    const now = new Date();
    const [startHour, startMin] = dnd.start_time.split(':').map(Number);
    const [endHour, endMin] = dnd.end_time.split(':').map(Number);

    const currentMinutes = now.getHours() * 60 + now.getMinutes();
    const startMinutes = startHour * 60 + startMin;
    const endMinutes = endHour * 60 + endMin;

    if (startMinutes <= endMinutes) {
      return currentMinutes >= startMinutes && currentMinutes <= endMinutes;
    } else {
      return currentMinutes >= startMinutes || currentMinutes <= endMinutes;
    }
  }

  private async sendOmnichannel(
    notification: NotificationRequest, 
    channels: ChannelType[]
  ): Promise<ChannelResult[]> {
    const promises: Promise<ChannelResult>[] = [];

    for (const channel of channels) {
      const adapter = this.adapterManager.getAdapter(channel);
      if (adapter) {
        promises.push(adapter.send(notification, notification.recipient));
      }
    }

    const results = await Promise.allSettled(promises);
    
    return results.map((result, index) => {
      if (result.status === 'fulfilled') {
        return result.value;
      }
      return {
        channel: channels[index],
        status: 'failed' as const,
        error: result.reason?.message || 'Unknown error',
      };
    });
  }
}
