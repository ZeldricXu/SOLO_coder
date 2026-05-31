import { Pool, PoolConfig, PoolClient, QueryResult, QueryResultRow } from 'pg';
import { DatabaseConfig } from '../../types/config';
import { DatabaseError, TimeoutError } from '../../common/errors';
import { withTimeout } from '../../common/utils';

export interface ConnectionPoolStats {
  totalCount: number;
  idleCount: number;
  waitingCount: number;
  activeCount: number;
  maxCount: number;
}

export interface QueryOptions {
  timeoutMs?: number;
  useMaster?: boolean;
}

export class ConnectionPool {
  private pool: Pool;
  private config: DatabaseConfig;
  private connectionCount: number = 0;

  constructor(config: DatabaseConfig) {
    this.config = config;

    const poolConfig: PoolConfig = {
      connectionString: config.url,
      min: config.poolMin,
      max: config.poolMax,
      idleTimeoutMillis: config.idleTimeout,
      connectionTimeoutMillis: config.connectionTimeout,
      statement_timeout: config.queryTimeout
    };

    this.pool = new Pool(poolConfig);

    this.pool.on('error', (err) => {
      console.error('数据库连接池错误:', err);
    });

    this.pool.on('connect', () => {
      this.connectionCount++;
    });

    this.pool.on('release', () => {
      this.connectionCount--;
    });
  }

  async acquireConnection(): Promise<PoolClient> {
    try {
      return await this.pool.connect();
    } catch (error) {
      throw new DatabaseError('获取数据库连接失败', { error });
    }
  }

  releaseConnection(client: PoolClient): void {
    client.release();
  }

  async query<T extends QueryResultRow = any>(
    text: string,
    params?: unknown[],
    options: QueryOptions = {}
  ): Promise<QueryResult<T>> {
    const timeoutMs = options.timeoutMs || this.config.queryTimeout;

    try {
      return await withTimeout(
        this.pool.query<T>(text, params),
        timeoutMs,
        `查询超时 (${timeoutMs}ms)`
      );
    } catch (error) {
      if (error instanceof TimeoutError) {
        throw error;
      }
      throw new DatabaseError('数据库查询执行失败', { error, query: text });
    }
  }

  async queryWithClient<T extends QueryResultRow = any>(
    client: PoolClient,
    text: string,
    params?: unknown[],
    options: QueryOptions = {}
  ): Promise<QueryResult<T>> {
    const timeoutMs = options.timeoutMs || this.config.queryTimeout;

    try {
      return await withTimeout(
        client.query<T>(text, params),
        timeoutMs,
        `查询超时 (${timeoutMs}ms)`
      );
    } catch (error) {
      if (error instanceof TimeoutError) {
        throw error;
      }
      throw new DatabaseError('数据库查询执行失败', { error, query: text });
    }
  }

  async transaction<T>(
    callback: (client: PoolClient) => Promise<T>
  ): Promise<T> {
    const client = await this.acquireConnection();

    try {
      await client.query('BEGIN');

      const result = await callback(client);

      await client.query('COMMIT');

      return result;
    } catch (error) {
      try {
        await client.query('ROLLBACK');
      } catch (rollbackError) {
        console.error('事务回滚失败:', rollbackError);
      }
      throw error;
    } finally {
      this.releaseConnection(client);
    }
  }

  async batchQuery<T extends QueryResultRow = any>(
    queries: { text: string; params?: unknown[] }[],
    options: QueryOptions = {}
  ): Promise<QueryResult<T>[]> {
    return this.transaction(async (client) => {
      const results: QueryResult<T>[] = [];

      for (const query of queries) {
        const result = await this.queryWithClient(client, query.text, query.params, options);
        results.push(result);
      }

      return results;
    });
  }

  getStats(): ConnectionPoolStats {
    return {
      totalCount: this.pool.totalCount,
      idleCount: this.pool.idleCount,
      waitingCount: this.pool.waitingCount,
      activeCount: this.pool.totalCount - this.pool.idleCount,
      maxCount: this.config.poolMax
    };
  }

  async checkHealth(): Promise<boolean> {
    try {
      const result = await this.query('SELECT 1 as health_check');
      return result.rows.length > 0 && result.rows[0].health_check === 1;
    } catch (error) {
      console.error('数据库健康检查失败:', error);
      return false;
    }
  }

  async end(): Promise<void> {
    await this.pool.end();
  }

  getPool(): Pool {
    return this.pool;
  }

  getConfig(): DatabaseConfig {
    return { ...this.config };
  }
}

export class MultiTenantConnectionPool {
  private pools: Map<string, ConnectionPool>;
  private defaultConfig: DatabaseConfig;

  constructor(defaultConfig: DatabaseConfig) {
    this.pools = new Map();
    this.defaultConfig = defaultConfig;
  }

  getPool(tenantId: string, customConfig?: Partial<DatabaseConfig>): ConnectionPool {
    const existing = this.pools.get(tenantId);
    if (existing) {
      return existing;
    }

    const config: DatabaseConfig = {
      ...this.defaultConfig,
      ...customConfig
    };

    const pool = new ConnectionPool(config);
    this.pools.set(tenantId, pool);
    return pool;
  }

  async removePool(tenantId: string): Promise<void> {
    const pool = this.pools.get(tenantId);
    if (pool) {
      await pool.end();
      this.pools.delete(tenantId);
    }
  }

  getPoolStats(tenantId: string): ConnectionPoolStats | undefined {
    return this.pools.get(tenantId)?.getStats();
  }

  getAllStats(): Map<string, ConnectionPoolStats> {
    const stats = new Map<string, ConnectionPoolStats>();
    for (const [tenantId, pool] of this.pools) {
      stats.set(tenantId, pool.getStats());
    }
    return stats;
  }

  async checkAllHealth(): Promise<Map<string, boolean>> {
    const results = new Map<string, boolean>();
    for (const [tenantId, pool] of this.pools) {
      results.set(tenantId, await pool.checkHealth());
    }
    return results;
  }

  async endAll(): Promise<void> {
    for (const pool of this.pools.values()) {
      await pool.end();
    }
    this.pools.clear();
  }
}
