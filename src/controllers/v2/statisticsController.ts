import { FastifyRequest, FastifyReply } from 'fastify';
import { DeliveryTracker } from '../../tracking/DeliveryTracker';
import { DeliveryQueryFilter, ChannelType, NotificationType, DeliveryStatus } from '../../types';
import { logger } from '../../utils/logger';

const tracker = DeliveryTracker.getInstance();

export const getDeliveryStatistics = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const tenantId = request.headers['x-tenant-id'] as string;
    const query = request.query as any;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const { start_time, end_time, channels, notification_types, providers, statuses } = query;

    if (!start_time || !end_time) {
      return reply.status(400).send({ error: 'start_time and end_time are required' });
    }

    const filter: DeliveryQueryFilter = {
      tenant_id: tenantId,
      start_time: new Date(start_time),
      end_time: new Date(end_time),
      channels: channels ? (Array.isArray(channels) ? channels : [channels]) as ChannelType[] : undefined,
      notification_types: notification_types ? (Array.isArray(notification_types) ? notification_types : [notification_types]) as NotificationType[] : undefined,
      providers: providers ? (Array.isArray(providers) ? providers : [providers]) : undefined,
      statuses: statuses ? (Array.isArray(statuses) ? statuses : [statuses]) as DeliveryStatus[] : undefined,
    };

    const stats = await tracker.getDeliveryStatistics(filter);
    return reply.send(stats);
  } catch (err: any) {
    logger.error('Error getting delivery statistics', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const getGroupedStatistics = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const tenantId = request.headers['x-tenant-id'] as string;
    const query = request.query as any;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const { start_time, end_time, channels, notification_types, providers, statuses, group_by } = query;

    if (!start_time || !end_time) {
      return reply.status(400).send({ error: 'start_time and end_time are required' });
    }

    if (!group_by) {
      return reply.status(400).send({ error: 'group_by is required' });
    }

    const validGroupBy = ['channel', 'notification_type', 'provider', 'status'];
    const groupArray = Array.isArray(group_by) ? group_by : [group_by];
    
    for (const gb of groupArray) {
      if (!validGroupBy.includes(gb)) {
        return reply.status(400).send({ error: `Invalid group_by value: ${gb}. Valid values: ${validGroupBy.join(', ')}` });
      }
    }

    const filter: DeliveryQueryFilter = {
      tenant_id: tenantId,
      start_time: new Date(start_time),
      end_time: new Date(end_time),
      channels: channels ? (Array.isArray(channels) ? channels : [channels]) as ChannelType[] : undefined,
      notification_types: notification_types ? (Array.isArray(notification_types) ? notification_types : [notification_types]) as NotificationType[] : undefined,
      providers: providers ? (Array.isArray(providers) ? providers : [providers]) : undefined,
      statuses: statuses ? (Array.isArray(statuses) ? statuses : [statuses]) as DeliveryStatus[] : undefined,
      group_by: groupArray as ('channel' | 'notification_type' | 'provider' | 'status')[],
    };

    const stats = await tracker.getGroupedStatistics(filter);
    return reply.send(stats);
  } catch (err: any) {
    logger.error('Error getting grouped statistics', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const getLatencyPercentiles = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const tenantId = request.headers['x-tenant-id'] as string;
    const query = request.query as any;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const { start_time, end_time, channels, notification_types, providers, percentiles } = query;

    if (!start_time || !end_time) {
      return reply.status(400).send({ error: 'start_time and end_time are required' });
    }

    const filter: DeliveryQueryFilter = {
      tenant_id: tenantId,
      start_time: new Date(start_time),
      end_time: new Date(end_time),
      channels: channels ? (Array.isArray(channels) ? channels : [channels]) as ChannelType[] : undefined,
      notification_types: notification_types ? (Array.isArray(notification_types) ? notification_types : [notification_types]) as NotificationType[] : undefined,
      providers: providers ? (Array.isArray(providers) ? providers : [providers]) : undefined,
    };

    const percentileArray = percentiles 
      ? (Array.isArray(percentiles) ? percentiles : [percentiles]).map((p: string) => parseInt(p))
      : [50, 75, 90, 95, 99];

    const stats = await tracker.getLatencyPercentiles(filter, percentileArray);
    return reply.send(stats);
  } catch (err: any) {
    logger.error('Error getting latency percentiles', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const getDailyTrend = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const tenantId = request.headers['x-tenant-id'] as string;
    const query = request.query as any;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const { start_time, end_time, channels, notification_types, providers, statuses } = query;

    if (!start_time || !end_time) {
      return reply.status(400).send({ error: 'start_time and end_time are required' });
    }

    const filter: DeliveryQueryFilter = {
      tenant_id: tenantId,
      start_time: new Date(start_time),
      end_time: new Date(end_time),
      channels: channels ? (Array.isArray(channels) ? channels : [channels]) as ChannelType[] : undefined,
      notification_types: notification_types ? (Array.isArray(notification_types) ? notification_types : [notification_types]) as NotificationType[] : undefined,
      providers: providers ? (Array.isArray(providers) ? providers : [providers]) : undefined,
      statuses: statuses ? (Array.isArray(statuses) ? statuses : [statuses]) as DeliveryStatus[] : undefined,
    };

    const stats = await tracker.getDailyTrend(filter);
    return reply.send(stats);
  } catch (err: any) {
    logger.error('Error getting daily trend', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const getDeliveryRateOverview = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const tenantId = request.headers['x-tenant-id'] as string;
    const query = request.query as any;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const { days = '7' } = query;
    const numDays = parseInt(days);
    
    const endTime = new Date();
    const startTime = new Date();
    startTime.setDate(startTime.getDate() - numDays);

    const filter: DeliveryQueryFilter = {
      tenant_id: tenantId,
      start_time: startTime,
      end_time: endTime,
    };

    const [stats, channelStats, dailyTrend] = await Promise.all([
      tracker.getDeliveryStatistics(filter),
      tracker.getGroupedStatistics({ ...filter, group_by: ['channel'] }),
      tracker.getDailyTrend(filter),
    ]);

    return reply.send({
      overview: {
        total_sent: stats.total_sent,
        delivery_rate: stats.delivery_rate,
        open_rate: stats.open_rate,
        click_rate: stats.click_rate,
        failure_rate: stats.failure_rate,
        period: `${numDays}d`,
      },
      by_channel: channelStats,
      daily_trend: dailyTrend,
      latency_p99_by_channel: stats.channel_stats.map((c) => ({
        channel: c.channel,
        p99_latency_ms: c.p99_latency_ms,
        over_5s: c.p99_latency_ms > 5000,
      })),
      top_failures: stats.failure_reasons.slice(0, 5),
    });
  } catch (err: any) {
    logger.error('Error getting delivery rate overview', err);
    return reply.status(500).send({ error: err.message });
  }
};
