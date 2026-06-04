import { FastifyRequest, FastifyReply } from 'fastify';
import { OrchestrationEngine } from '../../orchestration/OrchestrationEngine';
import { OrchestrationSequence, OrchestrationStep } from '../../types';
import { logger } from '../../utils/logger';

const engine = OrchestrationEngine.getInstance();

export const createSequence = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const body = request.body as any;
    const tenantId = request.headers['x-tenant-id'] as string;
    const createdBy = (request as any).user?.id || 'system';

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const { name, description, steps, trigger_type, trigger_event } = body;

    if (!name || !steps || !Array.isArray(steps) || steps.length === 0) {
      return reply.status(400).send({ error: 'name and steps array are required' });
    }

    const validatedSteps = validateSteps(steps);
    const sequence = await engine.createSequence(
      tenantId,
      name,
      validatedSteps,
      createdBy,
      description,
      trigger_type || 'manual',
      trigger_event
    );

    return reply.status(201).send(sequence);
  } catch (err: any) {
    logger.error('Error creating orchestration sequence', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const getSequence = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { id } = request.params as { id: string };
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const sequence = await engine.getSequence(tenantId, id);
    if (!sequence) {
      return reply.status(404).send({ error: 'Sequence not found' });
    }

    return reply.send(sequence);
  } catch (err: any) {
    logger.error('Error getting orchestration sequence', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const listSequences = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const tenantId = request.headers['x-tenant-id'] as string;
    const { include_disabled } = request.query as { include_disabled?: string };

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const sequences = await engine.listSequences(tenantId, include_disabled === 'true');
    return reply.send(sequences);
  } catch (err: any) {
    logger.error('Error listing orchestration sequences', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const updateSequence = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { id } = request.params as { id: string };
    const body = request.body as any;
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    if (body.steps) {
      body.steps = validateSteps(body.steps);
    }

    const sequence = await engine.updateSequence(tenantId, id, body);
    if (!sequence) {
      return reply.status(404).send({ error: 'Sequence not found' });
    }

    return reply.send(sequence);
  } catch (err: any) {
    logger.error('Error updating orchestration sequence', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const deleteSequence = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { id } = request.params as { id: string };
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const deleted = await engine.deleteSequence(tenantId, id);
    if (!deleted) {
      return reply.status(404).send({ error: 'Sequence not found' });
    }

    return reply.send({ success: true });
  } catch (err: any) {
    logger.error('Error deleting orchestration sequence', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const startSequence = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { id } = request.params as { id: string };
    const body = request.body as any;
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const { recipient, template_variables, metadata } = body;

    if (!recipient) {
      return reply.status(400).send({ error: 'recipient is required' });
    }

    const instance = await engine.startSequence(
      tenantId,
      id,
      recipient,
      template_variables,
      metadata
    );

    return reply.status(202).send(instance);
  } catch (err: any) {
    logger.error('Error starting orchestration sequence', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const getInstance = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { id } = request.params as { id: string };
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const instance = await engine.getInstance(tenantId, id);
    if (!instance) {
      return reply.status(404).send({ error: 'Instance not found' });
    }

    return reply.send(instance);
  } catch (err: any) {
    logger.error('Error getting orchestration instance', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const getInstanceExecutions = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { id } = request.params as { id: string };
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const executions = await engine.getInstanceExecutions(id);
    return reply.send(executions);
  } catch (err: any) {
    logger.error('Error getting orchestration instance executions', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const cancelInstance = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { id } = request.params as { id: string };
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const cancelled = await engine.cancelInstance(tenantId, id);
    if (!cancelled) {
      return reply.status(404).send({ error: 'Instance not found or not running' });
    }

    return reply.send({ success: true, message: 'Instance cancelled' });
  } catch (err: any) {
    logger.error('Error cancelling orchestration instance', err);
    return reply.status(500).send({ error: err.message });
  }
};

function validateSteps(steps: any[]): OrchestrationStep[] {
  const channelTypes = ['email', 'sms', 'push', 'slack', 'wechat', 'feishu', 'webhook'];
  const notificationTypes = ['transactional', 'marketing', 'security', 'system', 'password_reset', 'account_verification'];

  return steps.map((step, index) => {
    if (!step.id) {
      step.id = `step-${index + 1}`;
    }
    if (step.order === undefined) {
      step.order = index;
    }
    if (!step.name) {
      step.name = `Step ${index + 1}`;
    }
    if (!step.channel || !channelTypes.includes(step.channel)) {
      throw new Error(`Step ${index + 1}: invalid or missing channel`);
    }
    if (!step.notification_type || !notificationTypes.includes(step.notification_type)) {
      throw new Error(`Step ${index + 1}: invalid or missing notification_type`);
    }
    if (step.terminate_on_success === undefined) {
      step.terminate_on_success = true;
    }
    return step as OrchestrationStep;
  });
}
