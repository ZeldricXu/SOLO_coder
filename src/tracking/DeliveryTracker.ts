import {
  DeliveryLog,
  DeliveryStatus,
  ChannelType,
  NotificationType,
  NotificationPriority,
  DeliveryStatistics,
  ChannelStatistics,
  LatencyDistribution,
  FailureReasonItem,
  DeliveryQueryFilter,
} from '../types';
import { db } from '../db';
import { logger } from '../utils/logger';
import { BatchDeliveryWriter, BufferedOperation } from './BatchDeliveryWriter';

const STATUS_PRIORITY: Record<DeliveryStatus, number> = {
  pending: 0,
  queued: 1,
  sent: 2,
  failed: 3,
  delivered: 4,
  read: 5,
  clicked: 6,
};

function getStatusPriority(status: DeliveryStatus): number {
  return STATUS_PRIORITY[status] ?? 0;
}

function getStatusPriorityCaseStatement(): string {
  const cases = Object.entries(STATUS_PRIORITY)
    .map(([status, priority]) => `WHEN '${status}' THEN ${priority}`)
    .join(' ');
  return `CASE status ${cases} ELSE 0 END`;
}

export class DeliveryTracker {
  private static instance: DeliveryTracker;
  private batchWriter: BatchDeliveryWriter;
  private useBatchWriter: boolean = true;

  private constructor() {
    this.batchWriter = BatchDeliveryWriter.getInstance();
    this.batchWriter.start();
  }

  public static getInstance(): DeliveryTracker {
    if (!DeliveryTracker.instance) {
      DeliveryTracker.instance = new DeliveryTracker();
    }
    return DeliveryTracker.instance;
  }

  public async createDeliveryLog(
    deliveryId: string,
    tenantId: string,
    notificationType: NotificationType,
    channel: ChannelType,
    provider: string,
    recipient: string,
    priority: NotificationPriority,
    metadata?: Record<string, any>
  ): Promise<void> {
    const operation: BufferedOperation = {
      type: 'insert',
      payload: {
        delivery_id: deliveryId,
        tenant_id: tenantId,
        notification_type: notificationType,
        channel,
        provider,
        recipient,
        status: 'queued' as DeliveryStatus,
        priority,
        metadata,
      },
      timestamp: Date.now(),
    };

    if (this.useBatchWriter) {
      try {
        await this.batchWriter.enqueue(operation);
        return;
      } catch (err) {
        logger.warn('Batch write failed, falling back to direct write', { error: err });
      }
    }

    try {
      await db.withTenantContext(tenantId, async () => {
        await db.query(
          `INSERT INTO delivery_logs 
           (delivery_id, tenant_id, notification_type, channel, provider, recipient, status, priority, metadata)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
          [
            deliveryId,
            tenantId,
            notificationType,
            channel,
            provider,
            recipient,
            'queued' as DeliveryStatus,
            priority,
            metadata,
          ]
        );
      });
    } catch (err) {
      logger.error('Failed to create delivery log', err);
    }
  }

  public async updateStatus(
    tenantId: string,
    deliveryId: string,
    channel: ChannelType,
    status: DeliveryStatus,
    messageId?: string,
    errorMessage?: string
  ): Promise<boolean> {
    const operation: BufferedOperation = {
      type: 'update_status',
      payload: {
        tenant_id: tenantId,
        delivery_id: deliveryId,
        channel,
        status,
        message_id: messageId,
        error_message: errorMessage,
      },
      timestamp: Date.now(),
    };

    if (this.useBatchWriter) {
      try {
        await this.batchWriter.enqueue(operation);
        return true;
      } catch (err) {
        logger.warn('Batch write failed, falling back to direct write', { error: err });
      }
    }

    try {
      const result = await db.withTenantContext(tenantId, async () => {
        const updateResult = await db.query(
          `UPDATE delivery_logs 
           SET status = $1, message_id = $2, error_message = $3, updated_at = NOW()
           WHERE delivery_id = $4 AND channel = $5
           AND (${getStatusPriorityCaseStatement()}) < $6`,
          [status, messageId, errorMessage, deliveryId, channel, getStatusPriority(status)]
        );
        return updateResult.rowCount > 0;
      });
      if (!result) {
        logger.debug('Status update skipped due to priority check', {
          deliveryId,
          channel,
          newStatus: status,
        });
      }
      return result;
    } catch (err) {
      logger.error('Failed to update delivery status', err);
      return false;
    }
  }

  public async handleCallback(
    tenantId: string,
    messageId: string,
    status: DeliveryStatus,
    metadata?: Record<string, any>
  ): Promise<boolean> {
    const operation: BufferedOperation = {
      type: 'update_callback',
      payload: {
        tenant_id: tenantId,
        message_id: messageId,
        status,
        metadata,
      },
      timestamp: Date.now(),
    };

    if (this.useBatchWriter) {
      try {
        await this.batchWriter.enqueue(operation);
        return true;
      } catch (err) {
        logger.warn('Batch write failed, falling back to direct write', { error: err });
      }
    }

    try {
      const result = await db.withTenantContext(tenantId, async () => {
        const updateResult = await db.query(
          `UPDATE delivery_logs 
           SET status = $1, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || $3::jsonb
           WHERE message_id = $2
           AND (${getStatusPriorityCaseStatement()}) < $4
           RETURNING *`,
          [status, messageId, metadata || {}, getStatusPriority(status)]
        );

        if (updateResult.rowCount > 0) {
          logger.info('Delivery status updated via callback', {
            messageId,
            status,
            deliveryId: updateResult.rows[0].delivery_id,
          });
          return true;
        } else {
          logger.debug('Callback status update skipped due to priority check', {
            messageId,
            status,
          });
          return false;
        }
      });
      return result;
    } catch (err) {
      logger.error('Failed to handle callback', err);
      return false;
    }
  }

  public async flush(): Promise<void> {
    await this.batchWriter.flush();
  }

  public async getByDeliveryId(
    tenantId: string,
    deliveryId: string
  ): Promise<DeliveryLog[]> {
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          'SELECT * FROM delivery_logs WHERE delivery_id = $1 ORDER BY created_at DESC',
          [deliveryId]
        );
      });
      return result.rows || [];
    } catch (err) {
      logger.error('Failed to get delivery logs by ID', err);
      return [];
    }
  }

  public async getByRecipient(
    tenantId: string,
    recipient: string,
    limit: number = 100,
    offset: number = 0
  ): Promise<DeliveryLog[]> {
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          'SELECT * FROM delivery_logs WHERE recipient = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3',
          [recipient, limit, offset]
        );
      });
      return result.rows || [];
    } catch (err) {
      logger.error('Failed to get delivery logs by recipient', err);
      return [];
    }
  }

  public async getByTimeRange(
    tenantId: string,
    startTime: Date,
    endTime: Date,
    filters?: {
      channel?: ChannelType;
      status?: DeliveryStatus;
      notification_type?: NotificationType;
    },
    limit: number = 100,
    offset: number = 0
  ): Promise<DeliveryLog[]> {
    try {
      const conditions: string[] = ['created_at >= $1', 'created_at <= $2'];
      const params: any[] = [startTime, endTime];
      let paramIndex = 3;

      if (filters?.channel) {
        conditions.push(`channel = $${paramIndex}`);
        params.push(filters.channel);
        paramIndex++;
      }

      if (filters?.status) {
        conditions.push(`status = $${paramIndex}`);
        params.push(filters.status);
        paramIndex++;
      }

      if (filters?.notification_type) {
        conditions.push(`notification_type = $${paramIndex}`);
        params.push(filters.notification_type);
        paramIndex++;
      }

      params.push(limit, offset);

      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          `SELECT * FROM delivery_logs 
           WHERE ${conditions.join(' AND ')}
           ORDER BY created_at DESC
           LIMIT $${paramIndex} OFFSET $${paramIndex + 1}`,
          params
        );
      });

      return result.rows || [];
    } catch (err) {
      logger.error('Failed to get delivery logs by time range', err);
      return [];
    }
  }

  public async getStats(
    tenantId: string,
    startTime: Date,
    endTime: Date
  ): Promise<any> {
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          `SELECT 
             channel,
             status,
             COUNT(*) as count
           FROM delivery_logs 
           WHERE created_at >= $1 AND created_at <= $2
           GROUP BY channel, status
           ORDER BY channel, status`,
          [startTime, endTime]
        );
      });

      return result.rows || [];
    } catch (err) {
      logger.error('Failed to get delivery stats', err);
      return [];
    }
  }

  public async getDeliveryStatistics(filter: DeliveryQueryFilter): Promise<DeliveryStatistics> {
    try {
      const stats = await db.withTenantContext(filter.tenant_id, async () => {
        const whereClause = this.buildWhereClause(filter);
        const params = this.buildQueryParams(filter);

        const overallResult = await db.query(
          `SELECT
             COUNT(*) AS total,
             COUNT(CASE WHEN status IN ('delivered', 'read', 'clicked') THEN 1 END) AS delivered,
             COUNT(CASE WHEN status = 'failed' THEN 1 END) AS failed,
             COUNT(CASE WHEN status = 'read' THEN 1 END) AS read_count,
             COUNT(CASE WHEN status = 'clicked' THEN 1 END) AS clicked
           FROM delivery_logs
           ${whereClause}`,
          params
        );

        const channelResult = await db.query(
          `SELECT
             channel,
             COUNT(*) AS total_sent,
             COUNT(CASE WHEN status IN ('delivered', 'read', 'clicked') THEN 1 END) AS total_delivered,
             COUNT(CASE WHEN status = 'failed' THEN 1 END) AS total_failed,
             AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) * 1000) AS avg_latency_ms,
             PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (updated_at - created_at)) * 1000) AS p50_latency_ms,
             PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (updated_at - created_at)) * 1000) AS p95_latency_ms,
             PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (updated_at - created_at)) * 1000) AS p99_latency_ms
           FROM delivery_logs
           ${whereClause}
           GROUP BY channel
           ORDER BY channel`,
          params
        );

        const latencyResult = await db.query(
          `SELECT
             COUNT(CASE WHEN EXTRACT(EPOCH FROM (updated_at - created_at)) < 1 THEN 1 END) AS under_1s,
             COUNT(CASE WHEN EXTRACT(EPOCH FROM (updated_at - created_at)) < 5 THEN 1 END) AS under_5s,
             COUNT(CASE WHEN EXTRACT(EPOCH FROM (updated_at - created_at)) < 30 THEN 1 END) AS under_30s,
             COUNT(CASE WHEN EXTRACT(EPOCH FROM (updated_at - created_at)) >= 30 THEN 1 END) AS over_30s
           FROM delivery_logs
           ${whereClause}
           AND status != 'queued'`,
          params
        );

        const failureReasonsResult = await db.query(
          `SELECT
             error_message AS reason,
             COUNT(*) AS count
           FROM delivery_logs
           ${whereClause}
           AND status = 'failed'
           AND error_message IS NOT NULL
           GROUP BY error_message
           ORDER BY count DESC
           LIMIT 10`,
          params
        );

        const overall = overallResult.rows[0] || {};
        const total = parseInt(overall.total) || 0;
        const delivered = parseInt(overall.delivered) || 0;
        const failed = parseInt(overall.failed) || 0;
        const read = parseInt(overall.read_count) || 0;
        const clicked = parseInt(overall.clicked) || 0;

        const channelStats: ChannelStatistics[] = channelResult.rows.map((row: any) => ({
          channel: row.channel,
          total_sent: parseInt(row.total_sent) || 0,
          total_delivered: parseInt(row.total_delivered) || 0,
          total_failed: parseInt(row.total_failed) || 0,
          delivery_rate: row.total_sent > 0 ? (parseInt(row.total_delivered) || 0) / parseInt(row.total_sent) : 0,
          avg_latency_ms: parseFloat(row.avg_latency_ms) || 0,
          p50_latency_ms: parseFloat(row.p50_latency_ms) || 0,
          p95_latency_ms: parseFloat(row.p95_latency_ms) || 0,
          p99_latency_ms: parseFloat(row.p99_latency_ms) || 0,
        }));

        const latencyRow = latencyResult.rows[0] || {};
        const latencyDist: LatencyDistribution = {
          under_1s: parseInt(latencyRow.under_1s) || 0,
          under_5s: parseInt(latencyRow.under_5s) || 0,
          under_30s: parseInt(latencyRow.under_30s) || 0,
          over_30s: parseInt(latencyRow.over_30s) || 0,
        };

        const totalFailures = failureReasonsResult.rows.reduce(
          (sum: number, row: any) => sum + (parseInt(row.count) || 0),
          0
        );
        const failureReasons: FailureReasonItem[] = failureReasonsResult.rows.map((row: any) => ({
          reason: row.reason,
          count: parseInt(row.count) || 0,
          percentage: totalFailures > 0 ? (parseInt(row.count) || 0) / totalFailures : 0,
        }));

        const result: DeliveryStatistics = {
          tenant_id: filter.tenant_id,
          time_range: {
            start: filter.start_time,
            end: filter.end_time,
          },
          filters: {
            channels: filter.channels,
            notification_types: filter.notification_types,
            providers: filter.providers,
          },
          total_sent: total,
          total_delivered: delivered,
          total_failed: failed,
          total_read: read,
          total_clicked: clicked,
          delivery_rate: total > 0 ? delivered / total : 0,
          open_rate: delivered > 0 ? read / delivered : 0,
          click_rate: delivered > 0 ? clicked / delivered : 0,
          failure_rate: total > 0 ? failed / total : 0,
          channel_stats: channelStats,
          latency_distribution: latencyDist,
          failure_reasons: failureReasons,
        };

        return result;
      });

      return stats;
    } catch (err) {
      logger.error('Failed to get delivery statistics', err);
      throw err;
    }
  }

  public async getGroupedStatistics(
    filter: DeliveryQueryFilter
  ): Promise<Record<string, any>[]> {
    try {
      if (!filter.group_by || filter.group_by.length === 0) {
        return [];
      }

      const results = await db.withTenantContext(filter.tenant_id, async () => {
        const groupFields = filter.group_by!.join(', ');
        const whereClause = this.buildWhereClause(filter);
        const params = this.buildQueryParams(filter);

        const result = await db.query(
          `SELECT
             ${groupFields},
             COUNT(*) AS total_sent,
             COUNT(CASE WHEN status IN ('delivered', 'read', 'clicked') THEN 1 END) AS total_delivered,
             COUNT(CASE WHEN status = 'failed' THEN 1 END) AS total_failed,
             COUNT(CASE WHEN status = 'read' THEN 1 END) AS total_read,
             COUNT(CASE WHEN status = 'clicked' THEN 1 END) AS total_clicked,
             ROUND(
               CASE WHEN COUNT(*) > 0 
                 THEN COUNT(CASE WHEN status IN ('delivered', 'read', 'clicked') THEN 1 END)::numeric / COUNT(*) * 100 
                 ELSE 0 
               END, 2
             ) AS delivery_rate_pct
           FROM delivery_logs
           ${whereClause}
           GROUP BY ${groupFields}
           ORDER BY ${groupFields}`,
          params
        );

        return result.rows;
      });

      return results;
    } catch (err) {
      logger.error('Failed to get grouped statistics', err);
      throw err;
    }
  }

  public async getLatencyPercentiles(
    filter: DeliveryQueryFilter,
    percentiles: number[] = [50, 75, 90, 95, 99]
  ): Promise<Record<number, number>> {
    try {
      const results = await db.withTenantContext(filter.tenant_id, async () => {
        const whereClause = this.buildWhereClause(filter);
        const params = this.buildQueryParams(filter);

        const percentileExpressions = percentiles
          .map(
            (p) =>
              `PERCENTILE_CONT(${p / 100}) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (updated_at - created_at)) * 1000) AS p${p}`
          )
          .join(', ');

        const result = await db.query(
          `SELECT ${percentileExpressions}
           FROM delivery_logs
           ${whereClause}
           AND updated_at IS NOT NULL
           AND status != 'queued'`,
          params
        );

        const row = result.rows[0] || {};
        const percentilesMap: Record<number, number> = {};
        percentiles.forEach((p) => {
          percentilesMap[p] = parseFloat(row[`p${p}`]) || 0;
        });

        return percentilesMap;
      });

      return results;
    } catch (err) {
      logger.error('Failed to get latency percentiles', err);
      throw err;
    }
  }

  public async getDailyTrend(
    filter: DeliveryQueryFilter
  ): Promise<Array<{ date: string; total_sent: number; delivered: number; failed: number; delivery_rate: number }>> {
    try {
      const results = await db.withTenantContext(filter.tenant_id, async () => {
        const whereClause = this.buildWhereClause(filter);
        const params = this.buildQueryParams(filter);

        const result = await db.query(
          `SELECT
             DATE(created_at) AS date,
             COUNT(*) AS total_sent,
             COUNT(CASE WHEN status IN ('delivered', 'read', 'clicked') THEN 1 END) AS delivered,
             COUNT(CASE WHEN status = 'failed' THEN 1 END) AS failed,
             ROUND(
               CASE WHEN COUNT(*) > 0 
                 THEN COUNT(CASE WHEN status IN ('delivered', 'read', 'clicked') THEN 1 END)::numeric / COUNT(*) * 100 
                 ELSE 0 
               END, 2
             ) AS delivery_rate_pct
           FROM delivery_logs
           ${whereClause}
           GROUP BY DATE(created_at)
           ORDER BY date ASC`,
          params
        );

        return result.rows.map((row: any) => ({
          date: row.date,
          total_sent: parseInt(row.total_sent) || 0,
          delivered: parseInt(row.delivered) || 0,
          failed: parseInt(row.failed) || 0,
          delivery_rate: parseFloat(row.delivery_rate_pct) || 0,
        }));
      });

      return results;
    } catch (err) {
      logger.error('Failed to get daily trend', err);
      throw err;
    }
  }

  private buildWhereClause(filter: DeliveryQueryFilter): string {
    const conditions: string[] = ['created_at >= $1', 'created_at <= $2'];
    let paramIndex = 3;

    if (filter.channels && filter.channels.length > 0) {
      conditions.push(`channel = ANY($${paramIndex})`);
      paramIndex++;
    }

    if (filter.notification_types && filter.notification_types.length > 0) {
      conditions.push(`notification_type = ANY($${paramIndex})`);
      paramIndex++;
    }

    if (filter.providers && filter.providers.length > 0) {
      conditions.push(`provider = ANY($${paramIndex})`);
      paramIndex++;
    }

    if (filter.statuses && filter.statuses.length > 0) {
      conditions.push(`status = ANY($${paramIndex})`);
      paramIndex++;
    }

    return `WHERE ${conditions.join(' AND ')}`;
  }

  private buildQueryParams(filter: DeliveryQueryFilter): any[] {
    const params: any[] = [filter.start_time, filter.end_time];

    if (filter.channels && filter.channels.length > 0) {
      params.push(filter.channels);
    }

    if (filter.notification_types && filter.notification_types.length > 0) {
      params.push(filter.notification_types);
    }

    if (filter.providers && filter.providers.length > 0) {
      params.push(filter.providers);
    }

    if (filter.statuses && filter.statuses.length > 0) {
      params.push(filter.statuses);
    }

    return params;
  }

  public async flush(): Promise<void> {
    await this.batchWriter.flush();
  }

  public setUseBatchWriter(use: boolean): void {
    this.useBatchWriter = use;
  }

  public async close(): Promise<void> {
    await this.batchWriter.stop();
  }
}
