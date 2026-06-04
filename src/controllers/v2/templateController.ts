import { FastifyRequest, FastifyReply } from 'fastify';
import { TemplateEngine } from '../../templates/TemplateEngine';
import { Template, NotificationType } from '../../types';
import { logger } from '../../utils/logger';

const engine = TemplateEngine.getInstance();

export const listTemplates = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const tenantId = request.headers['x-tenant-id'] as string;
    const { include_system_defaults } = request.query as { include_system_defaults?: string };

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const templates = await engine.listTemplates(
      tenantId,
      include_system_defaults !== 'false'
    );

    return reply.send(templates);
  } catch (err: any) {
    logger.error('Error listing templates', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const getTemplate = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { notification_type, locale } = request.params as { notification_type: NotificationType; locale: string };
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const template = await engine.getTemplate(tenantId, notification_type, locale);
    if (!template) {
      return reply.status(404).send({ error: 'Template not found' });
    }

    return reply.send(template);
  } catch (err: any) {
    logger.error('Error getting template', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const createTenantTemplate = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const body = request.body as any;
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const { notification_type, locale, name, subject_template, body_template, html_template, variables } = body;

    if (!notification_type || !locale || !name || !body_template) {
      return reply.status(400).send({ error: 'notification_type, locale, name, and body_template are required' });
    }

    const template = await engine.createTenantTemplate(tenantId, {
      notification_type,
      locale,
      name,
      subject_template,
      body_template,
      html_template,
      variables: variables || [],
    });

    return reply.status(201).send(template);
  } catch (err: any) {
    logger.error('Error creating tenant template', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const updateTenantTemplate = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { id } = request.params as { id: string };
    const body = request.body as any;
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const template = await engine.updateTenantTemplate(tenantId, id, body);
    if (!template) {
      return reply.status(404).send({ error: 'Template not found or not a tenant template' });
    }

    return reply.send(template);
  } catch (err: any) {
    logger.error('Error updating tenant template', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const deleteTenantTemplate = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { id } = request.params as { id: string };
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const deleted = await engine.deleteTenantTemplate(tenantId, id);
    if (!deleted) {
      return reply.status(404).send({ error: 'Template not found or not a tenant template' });
    }

    return reply.send({ success: true });
  } catch (err: any) {
    logger.error('Error deleting tenant template', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const resetTemplateToDefault = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const { notification_type, locale } = request.params as { notification_type: NotificationType; locale: string };
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const reset = await engine.resetTenantTemplateToDefault(tenantId, notification_type, locale);
    if (!reset) {
      return reply.status(404).send({ error: 'No tenant template found for this notification_type and locale' });
    }

    return reply.send({ success: true, message: 'Template reset to system default' });
  } catch (err: any) {
    logger.error('Error resetting template to default', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const createSystemTemplate = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const body = request.body as any;

    const { notification_type, locale, name, subject_template, body_template, html_template, variables } = body;

    if (!notification_type || !locale || !name || !body_template) {
      return reply.status(400).send({ error: 'notification_type, locale, name, and body_template are required' });
    }

    const isAdmin = (request as any).user?.role === 'admin';
    if (!isAdmin) {
      return reply.status(403).send({ error: 'Admin role required to create system templates' });
    }

    const template = await engine.createSystemTemplate({
      notification_type,
      locale,
      name,
      subject_template,
      body_template,
      html_template,
      variables: variables || [],
    });

    return reply.status(201).send(template);
  } catch (err: any) {
    logger.error('Error creating system template', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const renderTemplate = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const body = request.body as any;
    const tenantId = request.headers['x-tenant-id'] as string;

    if (!tenantId) {
      return reply.status(400).send({ error: 'x-tenant-id header is required' });
    }

    const { notification_type, template_id, variables, locale } = body;

    if (!notification_type || !variables) {
      return reply.status(400).send({ error: 'notification_type and variables are required' });
    }

    let content;
    if (template_id) {
      content = await engine.renderTemplateById(
        tenantId,
        notification_type,
        template_id,
        variables,
        locale || 'en'
      );
    } else {
      content = await engine.render(
        tenantId,
        notification_type,
        variables,
        locale || 'en'
      );
    }

    if (!content) {
      return reply.status(404).send({ error: 'Template not found' });
    }

    return reply.send(content);
  } catch (err: any) {
    logger.error('Error rendering template', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const previewTemplate = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const body = request.body as any;

    const { subject_template, body_template, html_template, variables } = body;

    if (!body_template || !variables) {
      return reply.status(400).send({ error: 'body_template and variables are required' });
    }

    const content = await engine.preview(
      { subject_template, body_template, html_template },
      variables
    );

    return reply.send(content);
  } catch (err: any) {
    logger.error('Error previewing template', err);
    return reply.status(500).send({ error: err.message });
  }
};

export const clearCache = async (request: FastifyRequest, reply: FastifyReply) => {
  try {
    const isAdmin = (request as any).user?.role === 'admin';
    if (!isAdmin) {
      return reply.status(403).send({ error: 'Admin role required to clear template cache' });
    }

    engine.clearCache();
    return reply.send({ success: true, message: 'Template cache cleared' });
  } catch (err: any) {
    logger.error('Error clearing template cache', err);
    return reply.status(500).send({ error: err.message });
  }
};
