import { FastifyRequest, FastifyReply } from 'fastify';
import { TemplateEngine } from '../templates/TemplateEngine';
import { logger } from '../utils/logger';
import { z } from 'zod';

const TemplateSchema = z.object({
  notification_type: z.enum(['transactional', 'marketing', 'security', 'system', 'password_reset', 'account_verification']),
  locale: z.string().default('en'),
  name: z.string(),
  subject_template: z.string().optional(),
  body_template: z.string(),
  html_template: z.string().optional(),
  variables: z.array(z.string()).default([]),
});

export async function previewTemplate(
  request: FastifyRequest<{
    Body: {
      subject_template?: string;
      body_template: string;
      html_template?: string;
      variables: Record<string, any>;
    };
  }>,
  reply: FastifyReply
) {
  try {
    const templateEngine = TemplateEngine.getInstance();
    const result = await templateEngine.preview(request.body, request.body.variables);
    return reply.send({ result });
  } catch (error: any) {
    logger.error('Failed to preview template', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function createTemplate(
  request: FastifyRequest<{
    Body: z.infer<typeof TemplateSchema>;
    Headers: { 'x-tenant-id': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const validation = TemplateSchema.safeParse(request.body);
    if (!validation.success) {
      return reply.status(400).send({
        error: 'Invalid request',
        details: validation.error.errors,
      });
    }

    const templateEngine = TemplateEngine.getInstance();
    const template = await templateEngine.createTemplate(tenantId, validation.data as any);

    return reply.status(201).send(template);
  } catch (error: any) {
    logger.error('Failed to create template', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function getTemplate(
  request: FastifyRequest<{
    Params: { type: string; locale: string };
    Headers: { 'x-tenant-id': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const templateEngine = TemplateEngine.getInstance();
    const template = await templateEngine.getTemplate(
      tenantId,
      request.params.type as any,
      request.params.locale
    );

    if (!template) {
      return reply.status(404).send({ error: 'Template not found' });
    }

    return reply.send(template);
  } catch (error: any) {
    logger.error('Failed to get template', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}

export async function updateTemplate(
  request: FastifyRequest<{
    Params: { id: string };
    Body: Partial<z.infer<typeof TemplateSchema>>;
    Headers: { 'x-tenant-id': string };
  }>,
  reply: FastifyReply
) {
  try {
    const tenantId = request.headers['x-tenant-id'];
    if (!tenantId) {
      return reply.status(400).send({ error: 'Missing x-tenant-id header' });
    }

    const templateEngine = TemplateEngine.getInstance();
    const template = await templateEngine.updateTemplate(tenantId, request.params.id, request.body);

    if (!template) {
      return reply.status(404).send({ error: 'Template not found' });
    }

    return reply.send(template);
  } catch (error: any) {
    logger.error('Failed to update template', error);
    return reply.status(500).send({ error: 'Internal server error' });
  }
}
