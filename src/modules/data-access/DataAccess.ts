import { ConnectionPool, QueryOptions } from './ConnectionPool';
import { QueryOptimizer, QueryOptimizerConfig } from './QueryOptimizer';
import { DatabaseConfig } from '../../types/config';
import { QueryResult, QueryResultRow } from 'pg';
import { generateId, getCurrentTimestamp } from '../../common/utils';
import { NotFoundError } from '../../common/errors';

export interface BaseEntity {
  id: string;
  created_at: string;
  updated_at: string;
}

export interface Repository<T extends BaseEntity> {
  findById(id: string): Promise<T | undefined>;
  findAll(limit?: number, offset?: number): Promise<T[]>;
  create(entity: Omit<T, 'id' | 'created_at' | 'updated_at'>): Promise<T>;
  update(id: string, updates: Partial<Omit<T, 'id' | 'created_at' | 'updated_at'>>): Promise<T>;
  delete(id: string): Promise<void>;
  count(): Promise<number>;
}

export class BaseRepository<T extends BaseEntity> implements Repository<T> {
  protected pool: ConnectionPool;
  protected optimizer: QueryOptimizer;
  protected tableName: string;
  protected idPrefix: string;

  constructor(
    pool: ConnectionPool,
    tableName: string,
    idPrefix: string = 'ent',
    optimizerConfig?: QueryOptimizerConfig
  ) {
    this.pool = pool;
    this.tableName = tableName;
    this.idPrefix = idPrefix;
    this.optimizer = new QueryOptimizer(pool, optimizerConfig);
  }

  async findById(id: string): Promise<T | undefined> {
    const result = await this.optimizer.optimizedQuery(
      `SELECT * FROM ${this.tableName} WHERE id = $1`,
      [id],
      { useCache: true }
    );

    return result.rows[0] as T | undefined;
  }

  async findAll(limit: number = 100, offset: number = 0): Promise<T[]> {
    const result = await this.optimizer.optimizedQuery(
      `SELECT * FROM ${this.tableName} ORDER BY created_at DESC LIMIT $1 OFFSET $2`,
      [limit, offset],
      { useCache: true }
    );

    return result.rows as T[];
  }

  async findByField(field: string, value: unknown): Promise<T[]> {
    const result = await this.optimizer.optimizedQuery(
      `SELECT * FROM ${this.tableName} WHERE ${field} = $1`,
      [value],
      { useCache: true }
    );

    return result.rows as T[];
  }

  async create(entity: Omit<T, 'id' | 'created_at' | 'updated_at'>): Promise<T> {
    const now = getCurrentTimestamp();
    const id = generateId(this.idPrefix);

    const keys = Object.keys(entity);
    const values = Object.values(entity);
    const placeholders = keys.map((_, i) => `$${i + 3}`).join(', ');

    const query = `
      INSERT INTO ${this.tableName} (id, created_at, updated_at, ${keys.join(', ')})
      VALUES ($1, $2, ${placeholders})
      RETURNING *
    `;

    const result = await this.pool.query(query, [id, now, ...values]);

    this.optimizer.invalidateTableCache(this.tableName);

    return result.rows[0] as T;
  }

  async update(
    id: string,
    updates: Partial<Omit<T, 'id' | 'created_at' | 'updated_at'>>
  ): Promise<T> {
    const existing = await this.findById(id);
    if (!existing) {
      throw new NotFoundError(`实体不存在: ${id}`);
    }

    const now = getCurrentTimestamp();
    const keys = Object.keys(updates);
    const values = Object.values(updates);

    const setClauses = keys.map((key, i) => `${key} = $${i + 2}`).join(', ');

    const query = `
      UPDATE ${this.tableName}
      SET ${setClauses}, updated_at = $1
      WHERE id = $${keys.length + 2}
      RETURNING *
    `;

    const result = await this.pool.query(query, [now, ...values, id]);

    this.optimizer.invalidateTableCache(this.tableName);

    return result.rows[0] as T;
  }

  async delete(id: string): Promise<void> {
    const result = await this.pool.query(
      `DELETE FROM ${this.tableName} WHERE id = $1`,
      [id]
    );

    if (result.rowCount === 0) {
      throw new NotFoundError(`实体不存在: ${id}`);
    }

    this.optimizer.invalidateTableCache(this.tableName);
  }

  async count(): Promise<number> {
    const result = await this.optimizer.optimizedQuery(
      `SELECT COUNT(*) as count FROM ${this.tableName}`,
      [],
      { useCache: true }
    );

    return parseInt(result.rows[0].count, 10);
  }

  async exists(id: string): Promise<boolean> {
    const result = await this.optimizer.optimizedQuery(
      `SELECT 1 FROM ${this.tableName} WHERE id = $1`,
      [id],
      { useCache: true }
    );

    return result.rows.length > 0;
  }

  async batchCreate(entities: Omit<T, 'id' | 'created_at' | 'updated_at'>[]): Promise<T[]> {
    if (entities.length === 0) return [];

    const now = getCurrentTimestamp();
    const keys = Object.keys(entities[0]);
    const placeholders: string[] = [];
    const values: unknown[] = [];

    entities.forEach((entity, i) => {
      const id = generateId(this.idPrefix);
      const entityValues = [id, now, now, ...keys.map(k => entity[k as keyof typeof entity])];
      values.push(...entityValues);

      const startIndex = i * (keys.length + 3) + 1;
      const entityPlaceholders = keys.map((_, j) => `$${startIndex + 2 + j}`);
      placeholders.push(`($${startIndex}, $${startIndex + 1}, $${startIndex + 2}, ${entityPlaceholders.join(', ')})`);
    });

    const query = `
      INSERT INTO ${this.tableName} (id, created_at, updated_at, ${keys.join(', ')})
      VALUES ${placeholders.join(', ')}
      RETURNING *
    `;

    const result = await this.pool.query(query, values);

    this.optimizer.invalidateTableCache(this.tableName);

    return result.rows as T[];
  }

  async batchUpdate(ids: string[], updates: Partial<Omit<T, 'id' | 'created_at' | 'updated_at'>>): Promise<T[]> {
    if (ids.length === 0) return [];

    const now = getCurrentTimestamp();
    const keys = Object.keys(updates);
    const values = Object.values(updates);

    const setClauses = keys.map((key, i) => `${key} = $${i + 2}`).join(', ');

    const query = `
      UPDATE ${this.tableName}
      SET ${setClauses}, updated_at = $1
      WHERE id = ANY($${keys.length + 2}::text[])
      RETURNING *
    `;

    const result = await this.pool.query(query, [now, ...values, ids]);

    this.optimizer.invalidateTableCache(this.tableName);

    return result.rows as T[];
  }

  async batchDelete(ids: string[]): Promise<void> {
    if (ids.length === 0) return;

    await this.pool.query(
      `DELETE FROM ${this.tableName} WHERE id = ANY($1::text[])`,
      [ids]
    );

    this.optimizer.invalidateTableCache(this.tableName);
  }

  getOptimizer(): QueryOptimizer {
    return this.optimizer;
  }

  getPool(): ConnectionPool {
    return this.pool;
  }
}

export class DataAccess {
  private pool: ConnectionPool;
  private repositories: Map<string, BaseRepository<any>>;

  constructor(config: DatabaseConfig) {
    this.pool = new ConnectionPool(config);
    this.repositories = new Map();
  }

  getRepository<T extends BaseEntity>(
    tableName: string,
    idPrefix: string = 'ent'
  ): BaseRepository<T> {
    const key = `${tableName}_${idPrefix}`;

    if (!this.repositories.has(key)) {
      const repository = new BaseRepository<T>(this.pool, tableName, idPrefix);
      this.repositories.set(key, repository);
    }

    return this.repositories.get(key)!;
  }

  getPool(): ConnectionPool {
    return this.pool;
  }

  async query<T extends QueryResultRow = any>(
    text: string,
    params?: unknown[],
    options: QueryOptions & { useCache?: boolean; cacheTTL?: number } = {}
  ): Promise<QueryResult<T>> {
    const { useCache, cacheTTL, ...queryOptions } = options;

    if (useCache) {
      const optimizer = new QueryOptimizer(this.pool);
      return optimizer.optimizedQuery(text, params, options);
    }

    return this.pool.query<T>(text, params, queryOptions);
  }

  async transaction<T>(
    callback: (client: any) => Promise<T>
  ): Promise<T> {
    return this.pool.transaction(callback);
  }

  async checkHealth(): Promise<boolean> {
    return this.pool.checkHealth();
  }

  getStats() {
    return this.pool.getStats();
  }

  async close(): Promise<void> {
    for (const repo of this.repositories.values()) {
      repo.getOptimizer().destroy();
    }
    this.repositories.clear();
    await this.pool.end();
  }
}
