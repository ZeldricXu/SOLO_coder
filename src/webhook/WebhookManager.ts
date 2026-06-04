import { WebhookEndpoint, RetryConfig } from '../types';
import { db } from '../db';
import { logger } from '../utils/logger';
import { v4 as uuidv4 } from 'uuid';
import * as crypto from 'crypto';

export class WebhookManager {
  private static instance: WebhookManager;

  private constructor() {}

  public static getInstance(): WebhookManager {
    if (!WebhookManager.instance) {
      WebhookManager.instance = new WebhookManager();
    }
    return WebhookManager.instance;
  }

  public async createEndpoint(
    tenantId: string,
    endpoint: Omit<WebhookEndpoint, 'id' | 'tenant_id' | 'created_at'>,
    actor: string
  ): Promise<WebhookEndpoint> {
    const id = uuidv4();
    
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          `INSERT INTO webhook_endpoints 
           (id, tenant_id, url, signing_secret, event_types, retry_config, enabled)
           VALUES ($1, $2, $3, $4, $5, $6, $7)
           RETURNING *`,
          [
            id,
            tenantId,
            endpoint.url,
            endpoint.signing_secret,
            endpoint.event_types,
            endpoint.retry_config,
            endpoint.enabled,
          ]
        );
      });

      await this.logAudit(tenantId, actor, 'create', 'webhook_endpoint', id, endpoint);
      
      return result.rows[0];
    } catch (err) {
      logger.error('Failed to create webhook endpoint', err);
      throw err;
    }
  }

  public async getEndpoints(tenantId: string): Promise<WebhookEndpoint[]> {
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query('SELECT * FROM webhook_endpoints ORDER BY created_at DESC', []);
      });
      return result.rows || [];
    } catch (err) {
      logger.error('Failed to get webhook endpoints', err);
      return [];
    }
  }

  public async getEndpoint(tenantId: string, endpointId: string): Promise<WebhookEndpoint | null> {
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query('SELECT * FROM webhook_endpoints WHERE id = $1', [endpointId]);
      });
      return result.rowCount > 0 ? result.rows[0] : null;
    } catch (err) {
      logger.error('Failed to get webhook endpoint', err);
      return null;
    }
  }

  public async updateEndpoint(
    tenantId: string,
    endpointId: string,
    updates: Partial<WebhookEndpoint>,
    actor: string
  ): Promise<WebhookEndpoint | null> {
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          `UPDATE webhook_endpoints 
           SET url = COALESCE($1, url),
               signing_secret = COALESCE($2, signing_secret),
               event_types = COALESCE($3, event_types),
               retry_config = COALESCE($4, retry_config),
               enabled = COALESCE($5, enabled),
               updated_at = NOW()
           WHERE id = $6
           RETURNING *`,
          [
            updates.url,
            updates.signing_secret,
            updates.event_types,
            updates.retry_config,
            updates.enabled,
            endpointId,
          ]
        );
      });

      if (result.rowCount > 0) {
        await this.logAudit(tenantId, actor, 'update', 'webhook_endpoint', endpointId, updates);
        return result.rows[0];
      }
      return null;
    } catch (err) {
      logger.error('Failed to update webhook endpoint', err);
      throw err;
    }
  }

  public async deleteEndpoint(
    tenantId: string,
    endpointId: string,
    actor: string
  ): Promise<void> {
    try {
      await db.withTenantContext(tenantId, async () => {
        await db.query('DELETE FROM webhook_endpoints WHERE id = $1', [endpointId]);
      });
      await this.logAudit(tenantId, actor, 'delete', 'webhook_endpoint', endpointId, {});
    } catch (err) {
      logger.error('Failed to delete webhook endpoint', err);
      throw err;
    }
  }

  public async getWebhookLogs(
    tenantId: string,
    endpointId: string,
    limit: number = 100,
    offset: number = 0
  ): Promise<any[]> {
    try {
      const result = await db.withTenantContext(tenantId, async () => {
        return await db.query(
          `SELECT * FROM webhook_logs 
           WHERE endpoint_id = $1
           ORDER BY created_at DESC
           LIMIT $2 OFFSET $3`,
          [endpointId, limit, offset]
        );
      });
      return result.rows || [];
    } catch (err) {
      logger.error('Failed to get webhook logs', err);
      return [];
    }
  }

  public async sendWebhook(
    tenantId: string,
    endpoint: WebhookEndpoint,
    eventType: string,
    payload: any
  ): Promise<void> {
    const maxRetries = endpoint.retry_config.max_retries || 3;
    let attempt = 0;

    while (attempt < maxRetries) {
      try {
        const signature = this.generateSignature(payload, endpoint.signing_secret);
        
        const response = await fetch(endpoint.url, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Webhook-Signature': signature,
            'X-Webhook-Timestamp': Date.now().toString(),
            'X-Webhook-Event': eventType,
          },
          body: JSON.stringify(payload),
        });

        const responseBody = await response.text();

        await this.logWebhookCall(
          tenantId,
          endpoint.id!,
          eventType,
          payload,
          attempt + 1,
          response.ok ? 'delivered' : 'failed',
          response.status,
          responseBody
        );

        if (response.ok) {
          logger.info('Webhook sent successfully', { endpoint: endpoint.url, eventType });
          return;
        }

        throw new Error(`HTTP ${response.status}: ${responseBody}`);
      } catch (error: any) {
        attempt++;
        logger.warn('Webhook attempt failed', {
          attempt,
          endpoint: endpoint.url,
          error: error.message,
        });

        if (attempt < maxRetries) {
          const delay = this.calculateBackoff(attempt, endpoint.retry_config);
          await this.sleep(delay);
        }
      }
    }

    logger.error('Webhook failed after all retries', { endpoint: endpoint.url });
  }

  private generateSignature(payload: any, secret: string): string {
    const hmac = crypto.createHmac('sha256', secret);
    hmac.update(JSON.stringify(payload));
    return `sha256=${hmac.digest('hex')}`;
  }

  private calculateBackoff(attempt: number, config: RetryConfig): number {
    const base = config.backoff_base || 1000;
    const multiplier = config.backoff_multiplier || 2;
    return base * Math.pow(multiplier, attempt - 1);
  }

  private async sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  private async logWebhookCall(
    tenantId: string,
    endpointId: string,
    eventType: string,
    requestBody: any,
    attempts: number,
    status: string,
    responseStatus?: number,
    responseBody?: string
  ): Promise<void> {
    try {
      await db.query(
        `INSERT INTO webhook_logs 
         (tenant_id, endpoint_id, event_type, request_body, response_status, response_body, attempts, status)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
        [tenantId, endpointId, eventType, requestBody, responseStatus, responseBody, attempts, status]
      );
    } catch (err) {
      logger.error('Failed to log webhook call', err);
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
