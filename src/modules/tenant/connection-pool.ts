import { Pool, PoolConfig } from 'pg';
import { drizzle, PostgresJsDatabase } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import { PrismaClient } from '@prisma/client';
import { config } from '@config/index';
import { logger } from '@utils/logger';
import { tenantAsyncContext } from './tenant-async-context';

interface TenantConnection {
  pool: Pool;
  drizzle: PostgresJsDatabase;
  prisma: PrismaClient;
  createdAt: number;
  lastUsedAt: number;
}

class ConnectionPoolManager {
  private tenantConnections: Map<string, TenantConnection> = new Map();
  private platformPool: Pool | null = null;
  private platformDrizzle: PostgresJsDatabase | null = null;
  private platformPrisma: PrismaClient | null = null;
  private maxIdleTime = 30 * 60 * 1000;
  private cleanupInterval: NodeJS.Timeout | null = null;

  constructor() {
    this.startCleanupJob();
  }

  getPlatformPool(): Pool {
    if (!this.platformPool) {
      this.platformPool = this.createPool(config.databaseUrl, 'platform');
    }
    return this.platformPool;
  }

  getPlatformDrizzle(): PostgresJsDatabase {
    if (!this.platformDrizzle) {
      const sql = postgres(config.databaseUrl, { prepare: false });
      this.platformDrizzle = drizzle(sql);
    }
    return this.platformDrizzle;
  }

  getPlatformPrisma(): PrismaClient {
    if (!this.platformPrisma) {
      this.platformPrisma = new PrismaClient({
        log: config.logLevel === 'debug' ? ['query', 'info', 'warn', 'error'] : ['error'],
      });
    }
    return this.platformPrisma;
  }

  getTenantPool(tenantId: string, dbSchema: string): Pool {
    this.validateTenantContext(tenantId, dbSchema);
    return this.getOrCreateTenantConnection(tenantId, dbSchema).pool;
  }

  getTenantDrizzle(tenantId: string, dbSchema: string): PostgresJsDatabase {
    this.validateTenantContext(tenantId, dbSchema);
    return this.getOrCreateTenantConnection(tenantId, dbSchema).drizzle;
  }

  getTenantPrisma(tenantId: string, dbSchema: string): PrismaClient {
    this.validateTenantContext(tenantId, dbSchema);
    return this.getOrCreateTenantConnection(tenantId, dbSchema).prisma;
  }

  private validateTenantContext(tenantId: string, dbSchema: string): void {
    const contextTenantId = tenantAsyncContext.getTenantId();
    const contextDbSchema = tenantAsyncContext.getDbSchema();
    const requestId = tenantAsyncContext.getRequestId();

    if (contextTenantId && contextTenantId !== tenantId) {
      const error = new Error(
        `TENANT CONTEXT MISMATCH: Expected tenant ${contextTenantId}, but requested tenant ${tenantId}`
      );
      error.name = 'TenantContextMismatchError';
      logger.error(
        {
          error,
          expectedTenantId: contextTenantId,
          requestedTenantId: tenantId,
          requestId,
          contextDbSchema,
          requestedDbSchema: dbSchema,
        },
        'CRITICAL SECURITY ALERT: Tenant context mismatch detected'
      );
      throw error;
    }

    if (contextDbSchema && contextDbSchema !== dbSchema) {
      const error = new Error(
        `DB SCHEMA MISMATCH: Expected schema ${contextDbSchema}, but requested schema ${dbSchema}`
      );
      error.name = 'DbSchemaMismatchError';
      logger.error(
        {
          error,
          expectedDbSchema: contextDbSchema,
          requestedDbSchema: dbSchema,
          tenantId,
          requestId,
        },
        'CRITICAL SECURITY ALERT: DB schema mismatch detected'
      );
      throw error;
    }
  }

  private getOrCreateTenantConnection(tenantId: string, dbSchema: string): TenantConnection {
    let connection = this.tenantConnections.get(tenantId);

    if (!connection) {
      logger.info(`Creating new database connection for tenant: ${tenantId}`);

      const url = this.buildTenantDatabaseUrl(tenantId, dbSchema);
      const pool = this.createPool(url, `tenant:${tenantId}`);

      const searchPathUrl = this.buildTenantDatabaseUrlWithSchema(tenantId, dbSchema);
      const sql = postgres(searchPathUrl, { prepare: false });
      const drizzleDb = drizzle(sql);

      const prisma = new PrismaClient({
        datasources: {
          db: {
            url: searchPathUrl,
          },
        },
        log: config.logLevel === 'debug' ? ['query', 'info', 'warn', 'error'] : ['error'],
      });

      connection = {
        pool,
        drizzle: drizzleDb,
        prisma,
        createdAt: Date.now(),
        lastUsedAt: Date.now(),
      };

      this.tenantConnections.set(tenantId, connection);
    } else {
      connection.lastUsedAt = Date.now();
    }

    return connection;
  }

  private buildTenantDatabaseUrl(tenantId: string, dbSchema: string): string {
    return config.tenantDatabaseUrlTemplate
      .replace('{tenantId}', tenantId)
      .replace('{dbSchema}', dbSchema);
  }

  private buildTenantDatabaseUrlWithSchema(tenantId: string, dbSchema: string): string {
    const baseUrl = this.buildTenantDatabaseUrl(tenantId, dbSchema);
    const separator = baseUrl.includes('?') ? '&' : '?';
    return `${baseUrl}${separator}search_path=${dbSchema},public`;
  }

  private createPool(url: string, name: string): Pool {
    const poolConfig: PoolConfig = {
      connectionString: url,
      max: 20,
      min: 2,
      idleTimeoutMillis: 30000,
      connectionTimeoutMillis: 5000,
      application_name: `cms-api-${name}`,
    };

    const pool = new Pool(poolConfig);

    pool.on('error', (err) => {
      logger.error({ err, pool: name }, 'PostgreSQL pool error');
    });

    pool.on('connect', () => {
      logger.debug({ pool: name }, 'New database connection established');
    });

    return pool;
  }

  private startCleanupJob(): void {
    this.cleanupInterval = setInterval(() => {
      this.cleanupIdleConnections();
    }, 5 * 60 * 1000);
  }

  private cleanupIdleConnections(): void {
    const now = Date.now();
    const idleThreshold = now - this.maxIdleTime;

    for (const [tenantId, connection] of this.tenantConnections) {
      if (connection.lastUsedAt < idleThreshold) {
        logger.info(`Cleaning up idle connection for tenant: ${tenantId}`);
        this.closeTenantConnection(tenantId);
      }
    }
  }

  async closeTenantConnection(tenantId: string): Promise<void> {
    const connection = this.tenantConnections.get(tenantId);
    if (connection) {
      await connection.pool.end();
      await connection.prisma.$disconnect();
      this.tenantConnections.delete(tenantId);
      logger.info(`Closed connection for tenant: ${tenantId}`);
    }
  }

  async closeAll(): Promise<void> {
    logger.info('Closing all database connections');

    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
    }

    if (this.platformPool) {
      await this.platformPool.end();
      this.platformPool = null;
    }

    if (this.platformPrisma) {
      await this.platformPrisma.$disconnect();
      this.platformPrisma = null;
    }

    for (const tenantId of this.tenantConnections.keys()) {
      await this.closeTenantConnection(tenantId);
    }
  }
}

export const connectionPool = new ConnectionPoolManager();
