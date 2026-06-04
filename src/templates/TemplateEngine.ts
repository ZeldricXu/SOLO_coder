import * as Handlebars from 'handlebars';
import { Template, NotificationType, ContentPayload, TemplateWithFallbackInfo } from '../types';
import { db } from '../db';
import { logger } from '../utils/logger';

const SYSTEM_TENANT_ID = 'system-default';

export class TemplateEngine {
  private static instance: TemplateEngine;
  private templateCache: Map<string, Handlebars.TemplateDelegate> = new Map();
  private queryCache: Map<string, { template: TemplateWithFallbackInfo | null; timestamp: number }> = new Map();
  private static QUERY_CACHE_TTL = 60000;

  private constructor() {
    this.registerHelpers();
  }

  public static getInstance(): TemplateEngine {
    if (!TemplateEngine.instance) {
      TemplateEngine.instance = new TemplateEngine();
    }
    return TemplateEngine.instance;
  }

  private registerHelpers(): void {
    Handlebars.registerHelper('if_eq', function(this: any, a: any, b: any, options: any) {
      return a === b ? options.fn(this) : options.inverse(this);
    });

    Handlebars.registerHelper('if_gt', function(this: any, a: any, b: any, options: any) {
      return a > b ? options.fn(this) : options.inverse(this);
    });

    Handlebars.registerHelper('if_lt', function(this: any, a: any, b: any, options: any) {
      return a < b ? options.fn(this) : options.inverse(this);
    });

    Handlebars.registerHelper('format_date', function(date: string | Date, formatOrOptions: any) {
      const format = typeof formatOrOptions === 'string' ? formatOrOptions : undefined;
      const d = new Date(date);
      return d.toLocaleDateString('zh-CN', { 
        year: 'numeric', 
        month: '2-digit', 
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      });
    });

    Handlebars.registerHelper('currency', function(amount: number, currencyOrOptions: any) {
      let currency = 'CNY';
      if (typeof currencyOrOptions === 'string') {
        currency = currencyOrOptions;
      }
      return new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format(amount);
    });

    Handlebars.registerHelper('truncate', function(text: string, lengthOrOptions: any) {
      let length = 50;
      if (typeof lengthOrOptions === 'number') {
        length = lengthOrOptions;
      }
      if (text.length <= length) return text;
      return text.substring(0, length) + '...';
    });
  }

  public async getTemplate(
    tenantId: string,
    notificationType: NotificationType,
    templateIdOrLocale?: string,
    locale?: string
  ): Promise<TemplateWithFallbackInfo | null> {
    if (templateIdOrLocale && templateIdOrLocale.length > 2 && !templateIdOrLocale.includes('-')) {
      return this.getTemplateById(tenantId, templateIdOrLocale);
    }

    const actualLocale = templateIdOrLocale || locale || 'en';
    const cacheKey = `${tenantId}:${notificationType}:${actualLocale}`;

    const cached = this.queryCache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < TemplateEngine.QUERY_CACHE_TTL) {
      return cached.template;
    }

    try {
      const tenantResult = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          `SELECT * FROM templates 
           WHERE tenant_id = $1 AND notification_type = $2 AND locale = $3 AND is_system_default = false
           LIMIT 1`,
          [tenantId, notificationType, actualLocale]
        );
      });

      if (tenantResult.rowCount > 0) {
        const result = { ...tenantResult.rows[0], fallback_from_system: false };
        this.queryCache.set(cacheKey, { template: result, timestamp: Date.now() });
        return result;
      }

      if (actualLocale !== 'en') {
        const tenantEnResult = await db.withTenantContext(tenantId, async () => {
          return await db.query(
            `SELECT * FROM templates 
             WHERE tenant_id = $1 AND notification_type = $2 AND locale = 'en' AND is_system_default = false
             LIMIT 1`,
            [tenantId, notificationType]
          );
        });

        if (tenantEnResult.rowCount > 0) {
          const result = { ...tenantEnResult.rows[0], fallback_from_system: false };
          this.queryCache.set(cacheKey, { template: result, timestamp: Date.now() });
          return result;
        }
      }

      const systemResult = await db.query(
        `SELECT * FROM templates 
         WHERE tenant_id = $1 AND notification_type = $2 AND locale = $3 AND is_system_default = true
         LIMIT 1`,
        [SYSTEM_TENANT_ID, notificationType, actualLocale]
      );

      if (systemResult.rowCount > 0) {
        const result = { 
          ...systemResult.rows[0], 
          fallback_from_system: true,
          system_template_id: systemResult.rows[0].id
        };
        this.queryCache.set(cacheKey, { template: result, timestamp: Date.now() });
        return result;
      }

      if (actualLocale !== 'en') {
        const systemEnResult = await db.query(
          `SELECT * FROM templates 
           WHERE tenant_id = $1 AND notification_type = $2 AND locale = 'en' AND is_system_default = true
           LIMIT 1`,
          [SYSTEM_TENANT_ID, notificationType]
        );

        if (systemEnResult.rowCount > 0) {
          const result = { 
            ...systemEnResult.rows[0], 
            fallback_from_system: true,
            system_template_id: systemEnResult.rows[0].id
          };
          this.queryCache.set(cacheKey, { template: result, timestamp: Date.now() });
          return result;
        }
      }

      this.queryCache.set(cacheKey, { template: null, timestamp: Date.now() });
      return null;
    } catch (err) {
      logger.error('Failed to get template', err);
      throw err;
    }
  }

  private async getTemplateById(tenantId: string, templateId: string): Promise<TemplateWithFallbackInfo | null> {
    try {
      const tenantResult = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          `SELECT * FROM templates 
           WHERE id = $1 AND tenant_id = $2 AND is_system_default = false
           LIMIT 1`,
          [templateId, tenantId]
        );
      });

      if (tenantResult.rowCount > 0) {
        return { ...tenantResult.rows[0], fallback_from_system: false };
      }

      const systemResult = await db.query(
        `SELECT * FROM templates 
         WHERE id = $1 AND tenant_id = $2 AND is_system_default = true
         LIMIT 1`,
        [templateId, SYSTEM_TENANT_ID]
      );

      if (systemResult.rowCount > 0) {
        return { 
          ...systemResult.rows[0], 
          fallback_from_system: true,
          system_template_id: systemResult.rows[0].id
        };
      }

      return null;
    } catch (err) {
      logger.error('Failed to get template by ID', err);
      throw err;
    }
  }

  public async renderTemplate(
    template: Template,
    variables: Record<string, any>
  ): Promise<ContentPayload> {
    const cacheKey = `${template.id}-${template.updated_at.getTime()}`;
    
    let subjectTemplate = this.templateCache.get(`${cacheKey}-subject`);
    let bodyTemplate = this.templateCache.get(`${cacheKey}-body`);
    let htmlTemplate = this.templateCache.get(`${cacheKey}-html`);

    if (!bodyTemplate) {
      if (template.subject_template) {
        subjectTemplate = Handlebars.compile(template.subject_template);
        this.templateCache.set(`${cacheKey}-subject`, subjectTemplate);
      }

      bodyTemplate = Handlebars.compile(template.body_template);
      this.templateCache.set(`${cacheKey}-body`, bodyTemplate);

      if (template.html_template) {
        htmlTemplate = Handlebars.compile(template.html_template);
        this.templateCache.set(`${cacheKey}-html`, htmlTemplate);
      }
    }

    const mergedVars = this.mergeWithDefaults(variables, template.variables as string[]);

    return {
      subject: subjectTemplate ? subjectTemplate(mergedVars) : undefined,
      body: bodyTemplate(mergedVars),
      html: htmlTemplate ? htmlTemplate(mergedVars) : undefined,
    };
  }

  private mergeWithDefaults(
    variables: Record<string, any>,
    declaredVars: string[]
  ): Record<string, any> {
    const result: Record<string, any> = { ...variables };
    
    for (const varName of declaredVars) {
      if (result[varName] === undefined) {
        result[varName] = '';
      }
    }

    return result;
  }

  public async render(
    tenantId: string,
    notificationType: NotificationType,
    variables: Record<string, any>,
    locale: string = 'en'
  ): Promise<ContentPayload | null> {
    const template = await this.getTemplate(tenantId, notificationType, locale);
    if (!template) {
      logger.warn('Template not found', { tenantId, notificationType, locale });
      return null;
    }

    return this.renderTemplate(template, variables);
  }

  public async renderTemplateById(
    tenantId: string,
    notificationType: NotificationType,
    templateId: string,
    variables: Record<string, any>,
    locale: string = 'en'
  ): Promise<ContentPayload | null> {
    const template = await this.getTemplate(tenantId, notificationType, templateId, locale);
    if (!template) {
      logger.warn('Template not found by ID', { tenantId, templateId });
      return null;
    }

    return this.renderTemplate(template, variables);
  }

  public async createTenantTemplate(
    tenantId: string,
    template: Omit<Template, 'id' | 'tenant_id' | 'is_system_default' | 'created_at' | 'updated_at'>
  ): Promise<Template> {
    const result = await db.withTenantContext(tenantId, async () => {
      return await db.query(
        `INSERT INTO templates 
         (tenant_id, notification_type, locale, name, subject_template, body_template, html_template, variables, is_system_default)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, false)
         ON CONFLICT (tenant_id, notification_type, locale, is_system_default) 
         DO UPDATE SET
           name = EXCLUDED.name,
           subject_template = EXCLUDED.subject_template,
           body_template = EXCLUDED.body_template,
           html_template = EXCLUDED.html_template,
           variables = EXCLUDED.variables,
           updated_at = NOW()
         RETURNING *`,
        [
          tenantId,
          template.notification_type,
          template.locale,
          template.name,
          template.subject_template,
          template.body_template,
          template.html_template,
          template.variables,
        ]
      );
    });

    this.clearCacheForTemplate(tenantId, template.notification_type, template.locale, false);
    return result.rows[0];
  }

  public async createSystemTemplate(
    template: Omit<Template, 'id' | 'tenant_id' | 'is_system_default' | 'created_at' | 'updated_at'>
  ): Promise<Template> {
    const result = await db.query(
      `INSERT INTO templates 
       (tenant_id, notification_type, locale, name, subject_template, body_template, html_template, variables, is_system_default)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, true)
       ON CONFLICT (tenant_id, notification_type, locale, is_system_default) 
       DO UPDATE SET
         name = EXCLUDED.name,
         subject_template = EXCLUDED.subject_template,
         body_template = EXCLUDED.body_template,
         html_template = EXCLUDED.html_template,
         variables = EXCLUDED.variables,
         updated_at = NOW()
       RETURNING *`,
      [
        SYSTEM_TENANT_ID,
        template.notification_type,
        template.locale,
        template.name,
        template.subject_template,
        template.body_template,
        template.html_template,
        template.variables,
      ]
    );

    this.clearCacheForTemplate(SYSTEM_TENANT_ID, template.notification_type, template.locale, true);
    return result.rows[0];
  }

  public async updateTenantTemplate(
    tenantId: string,
    templateId: string,
    updates: Partial<Template>
  ): Promise<Template | null> {
    const result = await db.withTenantContext(tenantId, async () => {
      return await db.query(
        `UPDATE templates 
         SET name = COALESCE($1, name),
             subject_template = COALESCE($2, subject_template),
             body_template = COALESCE($3, body_template),
             html_template = COALESCE($4, html_template),
             variables = COALESCE($5, variables),
             updated_at = NOW()
         WHERE id = $6 AND tenant_id = $7 AND is_system_default = false
         RETURNING *`,
        [
          updates.name,
          updates.subject_template,
          updates.body_template,
          updates.html_template,
          updates.variables,
          templateId,
          tenantId,
        ]
      );
    });

    if (result.rowCount > 0) {
      this.clearCache();
    }

    return result.rowCount > 0 ? result.rows[0] : null;
  }

  public async deleteTenantTemplate(tenantId: string, templateId: string): Promise<boolean> {
    const result = await db.withTenantContext(tenantId, async () => {
      return await db.query(
        `DELETE FROM templates WHERE id = $1 AND tenant_id = $2 AND is_system_default = false`,
        [templateId, tenantId]
      );
    });

    if (result.rowCount > 0) {
      this.clearCache();
    }

    return result.rowCount > 0;
  }

  public async listTemplates(
    tenantId: string,
    includeSystemDefaults: boolean = true
  ): Promise<Array<Template & { fallback_from_system?: boolean; overrides_system?: boolean }>> {
    const tenantTemplates = await db.withTenantContext(tenantId, async () => {
      return await db.query(
        `SELECT * FROM templates WHERE tenant_id = $1 AND is_system_default = false ORDER BY notification_type, locale`,
        [tenantId]
      );
    });

    const systemTemplates = includeSystemDefaults ? await db.query(
      `SELECT * FROM templates WHERE tenant_id = $1 AND is_system_default = true ORDER BY notification_type, locale`,
      [SYSTEM_TENANT_ID]
    ) : { rows: [] };

    const tenantMap = new Map<string, Template>();
    tenantTemplates.rows.forEach((t: Template) => {
      tenantMap.set(`${t.notification_type}-${t.locale}`, t);
    });

    const result: Array<Template & { fallback_from_system?: boolean; overrides_system?: boolean }> = [];

    tenantTemplates.rows.forEach((t: Template) => {
      const hasSystemOverride = systemTemplates.rows.some(
        (st: Template) => st.notification_type === t.notification_type && st.locale === t.locale
      );
      result.push({ ...t, overrides_system: hasSystemOverride });
    });

    if (includeSystemDefaults) {
      systemTemplates.rows.forEach((st: Template) => {
        const key = `${st.notification_type}-${st.locale}`;
        if (!tenantMap.has(key)) {
          result.push({ ...st, fallback_from_system: true });
        }
      });
    }

    return result.sort((a, b) => {
      if (a.notification_type !== b.notification_type) {
        return a.notification_type.localeCompare(b.notification_type);
      }
      return a.locale.localeCompare(b.locale);
    });
  }

  public async resetTenantTemplateToDefault(
    tenantId: string,
    notificationType: NotificationType,
    locale: string
  ): Promise<boolean> {
    const result = await db.withTenantContext(tenantId, async () => {
      return await db.query(
        `DELETE FROM templates 
         WHERE tenant_id = $1 AND notification_type = $2 AND locale = $3 AND is_system_default = false`,
        [tenantId, notificationType, locale]
      );
    });

    if (result.rowCount > 0) {
      this.clearCache();
    }

    return result.rowCount > 0;
  }

  private clearCacheForTemplate(
    tenantId: string,
    notificationType: NotificationType,
    locale: string,
    isSystem: boolean
  ): void {
    const prefix = isSystem ? 'system' : tenantId;
    const keysToDelete: string[] = [];
    
    for (const key of this.templateCache.keys()) {
      if (key.includes(`${prefix}-${notificationType}-${locale}`)) {
        keysToDelete.push(key);
      }
    }

    keysToDelete.forEach((key) => this.templateCache.delete(key));

    const queryCacheKey = `${tenantId}:${notificationType}:${locale}`;
    this.queryCache.delete(queryCacheKey);

    logger.info('Template cache cleared for specific template', {
      tenantId,
      notificationType,
      locale,
      isSystem,
    });
  }

  public async preview(
    templateData: {
      subject_template?: string;
      body_template: string;
      html_template?: string;
    },
    variables: Record<string, any>
  ): Promise<ContentPayload> {
    const subjectTemplate = templateData.subject_template 
      ? Handlebars.compile(templateData.subject_template) 
      : null;
    
    const bodyTemplate = Handlebars.compile(templateData.body_template);
    
    const htmlTemplate = templateData.html_template 
      ? Handlebars.compile(templateData.html_template) 
      : null;

    return {
      subject: subjectTemplate ? subjectTemplate(variables) : undefined,
      body: bodyTemplate(variables),
      html: htmlTemplate ? htmlTemplate(variables) : undefined,
    };
  }

  public async createTemplate(
    tenantId: string,
    template: Omit<Template, 'id' | 'tenant_id' | 'created_at' | 'updated_at'>
  ): Promise<Template> {
    const result = await db.withTenantContext(tenantId, async () => {
      return await db.query(
        `INSERT INTO templates 
         (tenant_id, notification_type, locale, name, subject_template, body_template, html_template, variables)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
         RETURNING *`,
        [
          tenantId,
          template.notification_type,
          template.locale,
          template.name,
          template.subject_template,
          template.body_template,
          template.html_template,
          template.variables,
        ]
      );
    });

    return result.rows[0];
  }

  public async updateTemplate(
    tenantId: string,
    templateId: string,
    updates: Partial<Template>
  ): Promise<Template | null> {
    const result = await db.withTenantContext(tenantId, async () => {
      return await db.query(
        `UPDATE templates 
         SET name = COALESCE($1, name),
             subject_template = COALESCE($2, subject_template),
             body_template = COALESCE($3, body_template),
             html_template = COALESCE($4, html_template),
             variables = COALESCE($5, variables),
             updated_at = NOW()
         WHERE id = $6
         RETURNING *`,
        [
          updates.name,
          updates.subject_template,
          updates.body_template,
          updates.html_template,
          updates.variables,
          templateId,
        ]
      );
    });

    return result.rowCount > 0 ? result.rows[0] : null;
  }

  public clearCache(): void {
    this.templateCache.clear();
    this.queryCache.clear();
    logger.info('Template cache cleared');
  }
}
