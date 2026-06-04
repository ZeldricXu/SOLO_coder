import { FastifyRequest, FastifyReply } from 'fastify';
import { PreferenceManager } from '../preferences/PreferenceManager';
import { logger } from '../utils/logger';

export async function getUserPreferences(
  request: FastifyRequest<{
    Params: { user_id: string };
    Headers: { 'x-tenant-id': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const manager = PreferenceManager.getInstance();
    const prefs = await manager.getUserPreferences(tenantId, request.params.user_id);

    return reply.send(prefs);
  } catch (error: any) {
    logger.error('Failed to get user preferences', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function updateChannelPreference(
  request: FastifyRequest<{
    Params: { user_id: string };
    Body: {
      channel: string;
      notification_type: string;
      opted_in: boolean;
    };
    Headers: { 'x-tenant-id': string; 'x-actor': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    const actor = request.headers['x-actor'] || 'system';
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const manager = PreferenceManager.getInstance();
    await manager.updateChannelPreference(
      tenantId,
      request.params.user_id,
      request.body.channel as any,
      request.body.notification_type as any,
      request.body.opted_in,
      actor
    );

    return reply.send({ success: true });
  } catch (error: any) {
    logger.error('Failed to update channel preference', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function updateDoNotDisturb(
  request: FastifyRequest<{
    Params: { user_id: string };
    Body: {
      enabled: boolean;
      start_time: string;
      end_time: string;
      timezone: string;
      allow_urgent: boolean;
    };
    Headers: { 'x-tenant-id': string; 'x-actor': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    const actor = request.headers['x-actor'] || 'system';
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const manager = PreferenceManager.getInstance();
    await manager.updateDoNotDisturb(
      tenantId,
      request.params.user_id,
      request.body as any,
      actor
    );

    return reply.send({ success: true });
  } catch (error: any) {
    logger.error('Failed to update DND settings', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}
