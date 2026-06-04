import { Pool } from 'pg';
import { config } from '../config';
import { logger } from '../utils/logger';

class Database {
  private pool: Pool;
  private static instance: Database;

  private constructor() {
    this.pool = new Pool({
      connectionString: config.database.url,
    });

    this.pool.on('error', (err) => {
      logger.error('Database pool error', err);
    });
  }

  public static getInstance(): Database {
    if (!Database.instance) {
      Database.instance = new Database();
    }
    return Database.instance;
  }

  public async query(text: string, params?: any[]): Promise<any> {
    const start = Date.now();
    try {
      const res = await this.pool.query(text, params);
      logger.debug('Query executed', {
        text: text.substring(0, 100),
        duration: Date.now() - start,
        rows: res.rowCount,
      });
      return res;
    } catch (err) {
      logger.error('Query failed', { text: text.substring(0, 100), error: err });
      throw err;
    }
  }

  public async setTenantContext(tenantId: string): Promise<void> {
    await this.query('SET app.current_tenant = $1', [tenantId]);
  }

  public async clearTenantContext(): Promise<void> {
    await this.query('RESET app.current_tenant');
  }

  public async withTenantContext<T>(tenantId: string, fn: () => Promise<T>): Promise<T> {
    await this.setTenantContext(tenantId);
    try {
      return await fn();
    } finally {
      await this.clearTenantContext();
    }
  }

  public async getClient() {
    return await this.pool.connect();
  }

  public async close(): Promise<void> {
    await this.pool.end();
  }
}

export const db = Database.getInstance();
