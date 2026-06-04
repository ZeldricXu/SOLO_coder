import { UserPreferences, ChannelPreference, DoNotDisturbSettings, ChannelType, NotificationType } from '../types';
import { db } from '../db';
import { logger } from '../utils/logger';

export class PreferenceManager {
  private static instance: PreferenceManager;

  private constructor() {}

  public static getInstance(): PreferenceManager {
    if (!PreferenceManager.instance) {
      PreferenceManager.instance = new PreferenceManager();
    }
    return PreferenceManager.instance;
  }

  public async getUserPreferences(
    tenantId: string,
    userId: string
  ): Promise<UserPreferences | null> {
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          'SELECT * FROM user_preferences WHERE user_id = $1',
          [userId]
        );
      });

      if (result.rowCount === 0) {
        return this.getDefaultPreferences(tenantId, userId);
      }

      const row = result.rows[0];
      return {
        user_id: row.user_id,
        tenant_id: row.tenant_id,
        channel_preferences: row.channel_preferences || [],
        do_not_disturb: row.do_not_disturb || {
          enabled: false,
          start_time: '22:00',
          end_time: '08:00',
          timezone: 'Asia/Shanghai',
          allow_urgent: true,
        },
        updated_at: row.updated_at,
      };
    } catch (err) {
      logger.error('Failed to get user preferences', err);
      return this.getDefaultPreferences(tenantId, userId);
    }
  }

  private getDefaultPreferences(tenantId: string, userId: string): UserPreferences {
    return {
      user_id: userId,
      tenant_id: tenantId,
      channel_preferences: [],
      do_not_disturb: {
        enabled: false,
        start_time: '22:00',
        end_time: '08:00',
        timezone: 'Asia/Shanghai',
        allow_urgent: true,
      },
      updated_at: new Date(),
    };
  }

  public async updateChannelPreference(
    tenantId: string,
    userId: string,
    channel: ChannelType,
    notificationType: NotificationType,
    optedIn: boolean,
    actor: string
  ): Promise<void> {
    try {
      await db.withTenantContext(tenantId, async () => {
        const existing = await db.query(
          'SELECT channel_preferences FROM user_preferences WHERE user_id = $1',
          [userId]
        );

        let preferences: ChannelPreference[] = [];
        if (existing.rowCount > 0) {
          preferences = existing.rows[0].channel_preferences || [];
        }

        const index = preferences.findIndex(
          (p: ChannelPreference) =>
            p.channel === channel && p.notification_type === notificationType
        );

        if (index >= 0) {
          preferences[index].opted_in = optedIn;
        } else {
          preferences.push({ channel, notification_type: notificationType, opted_in: optedIn });
        }

        if (existing.rowCount > 0) {
          await db.query(
            'UPDATE user_preferences SET channel_preferences = $1, updated_at = NOW() WHERE user_id = $2',
            [preferences, userId]
          );
        } else {
          await db.query(
            'INSERT INTO user_preferences (tenant_id, user_id, channel_preferences) VALUES ($1, $2, $3)',
            [tenantId, userId, preferences]
          );
        }

        await this.logAudit(tenantId, actor, 'update', 'channel_preference', userId, { channel, notificationType, optedIn });
      });

      logger.info('Channel preference updated', { userId, channel, notificationType, optedIn });
    } catch (err) {
      logger.error('Failed to update channel preference', err);
      throw err;
    }
  }

  public async updateDoNotDisturb(
    tenantId: string,
    userId: string,
    dndSettings: DoNotDisturbSettings,
    actor: string
  ): Promise<void> {
    try {
      await db.withTenantContext(tenantId, async () => {
        const existing = await db.query(
          'SELECT id FROM user_preferences WHERE user_id = $1',
          [userId]
        );

        if (existing.rowCount > 0) {
          await db.query(
            'UPDATE user_preferences SET do_not_disturb = $1, updated_at = NOW() WHERE user_id = $2',
            [dndSettings, userId]
          );
        } else {
          await db.query(
            'INSERT INTO user_preferences (tenant_id, user_id, do_not_disturb) VALUES ($1, $2, $3)',
            [tenantId, userId, dndSettings]
          );
        }

        await this.logAudit(tenantId, actor, 'update', 'dnd_settings', userId, dndSettings);
      });

      logger.info('DND settings updated', { userId, dndSettings });
    } catch (err) {
      logger.error('Failed to update DND settings', err);
      throw err;
    }
  }

  public async isChannelAllowed(
    tenantId: string,
    userId: string,
    channel: ChannelType,
    notificationType: NotificationType,
    priority: string
  ): Promise<boolean> {
    const prefs = await this.getUserPreferences(tenantId, userId);
    if (!prefs) {
      return true;
    }
    
    const channelPref = prefs.channel_preferences.find(
      (p) => p.channel === channel && p.notification_type === notificationType
    );

    if (channelPref && !channelPref.opted_in) {
      return false;
    }

    if (prefs.do_not_disturb && prefs.do_not_disturb.enabled && priority !== 'urgent') {
      if (this.isInDoNotDisturb(prefs.do_not_disturb)) {
        return false;
      }
    }

    return true;
  }

  private isInDoNotDisturb(dnd: DoNotDisturbSettings): boolean {
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

  private async logAudit(
    tenantId: string,
    actor: string,
    action: string,
    resourceType: string,
    resourceId: string,
    changes: Record<string, any>
  ): Promise<void> {
    try {
      await db.query(
        `INSERT INTO audit_logs (tenant_id, actor, action, resource_type, resource_id, changes)
         VALUES ($1, $2, $3, $4, $5, $6)`,
        [tenantId, actor, action, resourceType, resourceId, changes]
      );
    } catch (err) {
      logger.error('Failed to log audit', err);
    }
  }
}
