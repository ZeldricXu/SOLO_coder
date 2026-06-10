import { AsyncLocalStorage } from 'async_hooks';
import { randomUUID } from 'crypto';
import { logger } from '@utils/logger';

export interface TenantAsyncContext {
  requestId: string;
  tenantId: string;
  tenantCode: string;
  dbSchema: string;
  createdAt: number;
}

class TenantAsyncContextManager {
  private storage: AsyncLocalStorage<TenantAsyncContext>;

  constructor() {
    this.storage = new AsyncLocalStorage<TenantAsyncContext>();
  }

  run<T>(
    context: Omit<TenantAsyncContext, 'requestId' | 'createdAt'>,
    fn: () => Promise<T> | T
  ): Promise<T> {
    const fullContext: TenantAsyncContext = {
      ...context,
      requestId: randomUUID(),
      createdAt: Date.now(),
    };

    return new Promise((resolve, reject) => {
      this.storage.run(fullContext, () => {
        try {
          const result = fn();
          if (result instanceof Promise) {
            result.then(resolve).catch(reject);
          } else {
            resolve(result);
          }
        } catch (error) {
          reject(error);
        }
      });
    });
  }

  getContext(): TenantAsyncContext | undefined {
    return this.storage.getStore();
  }

  getTenantId(): string | undefined {
    return this.getContext()?.tenantId;
  }

  getDbSchema(): string | undefined {
    return this.getContext()?.dbSchema;
  }

  getRequestId(): string | undefined {
    return this.getContext()?.requestId;
  }

  assertTenantContext(expectedTenantId: string): boolean {
    const context = this.getContext();
    if (!context) {
      logger.error(
        { expectedTenantId },
        'Tenant context assertion failed: No async context found'
      );
      return false;
    }

    if (context.tenantId !== expectedTenantId) {
      logger.error(
        {
          expectedTenantId,
          actualTenantId: context.tenantId,
          requestId: context.requestId,
          contextAge: Date.now() - context.createdAt,
        },
        'TENANT CONTEXT MISMATCH DETECTED! Database connection cross-contamination risk.'
      );
      return false;
    }

    return true;
  }

  assertDbSchema(expectedDbSchema: string): boolean {
    const context = this.getContext();
    if (!context) {
      logger.error(
        { expectedDbSchema },
        'DB schema assertion failed: No async context found'
      );
      return false;
    }

    if (context.dbSchema !== expectedDbSchema) {
      logger.error(
        {
          expectedDbSchema,
          actualDbSchema: context.dbSchema,
          tenantId: context.tenantId,
          requestId: context.requestId,
        },
        'DB SCHEMA MISMATCH DETECTED! Potential cross-tenant data leakage.'
      );
      return false;
    }

    return true;
  }
}

export const tenantAsyncContext = new TenantAsyncContextManager();
