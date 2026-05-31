import { v4 as uuidv4 } from 'uuid';
import { SchemaMigration, DataTransferResult } from '../types';
import { ProcessingPipeline, RetryHandler } from '../core';
import { StorageManager } from '../storage';

export interface DataSource {
  id: string;
  type: 'database' | 'file' | 'api' | 'stream';
  config: Record<string, unknown>;
  connected: boolean;
}

export interface QueryOptions {
  limit?: number;
  offset?: number;
  orderBy?: string;
  orderDirection?: 'asc' | 'desc';
  filters?: Record<string, unknown>;
}

export interface PaginatedResult<T> {
  data: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export class SchemaMigrationManager {
  private migrations: Map<number, SchemaMigration> = new Map();
  private appliedVersions: Set<number> = new Set();
  private currentVersion: number = 0;

  registerMigration(migration: SchemaMigration): void {
    this.migrations.set(migration.version, migration);
  }

  registerMigrations(migrations: SchemaMigration[]): void {
    for (const migration of migrations) {
      this.registerMigration(migration);
    }
  }

  getMigrations(): SchemaMigration[] {
    return Array.from(this.migrations.values()).sort((a, b) => a.version - b.version);
  }

  getPendingMigrations(): SchemaMigration[] {
    return this.getMigrations().filter(m => !this.appliedVersions.has(m.version));
  }

  getAppliedMigrations(): SchemaMigration[] {
    return this.getMigrations().filter(m => this.appliedVersions.has(m.version));
  }

  getCurrentVersion(): number {
    return this.currentVersion;
  }

  async migrate(targetVersion?: number): Promise<SchemaMigration[]> {
    const pending = this.getPendingMigrations();
    const toApply = targetVersion !== undefined
      ? pending.filter(m => m.version <= targetVersion)
      : pending;

    const applied: SchemaMigration[] = [];
    for (const migration of toApply.sort((a, b) => a.version - b.version)) {
      await this.applyMigration(migration);
      applied.push(migration);
    }

    return applied;
  }

  async rollback(targetVersion: number): Promise<SchemaMigration[]> {
    const applied = this.getAppliedMigrations();
    const toRollback = applied.filter(m => m.version > targetVersion);
    const rolledBack: SchemaMigration[] = [];

    for (const migration of toRollback.sort((a, b) => b.version - a.version)) {
      await this.rollbackMigration(migration);
      rolledBack.push(migration);
    }

    return rolledBack;
  }

  private async applyMigration(migration: SchemaMigration): Promise<void> {
    console.log(`Applying migration v${migration.version}: ${migration.name}`);
    this.appliedVersions.add(migration.version);
    this.currentVersion = Math.max(this.currentVersion, migration.version);
    migration.appliedAt = new Date().toISOString();
  }

  private async rollbackMigration(migration: SchemaMigration): Promise<void> {
    console.log(`Rolling back migration v${migration.version}: ${migration.name}`);
    this.appliedVersions.delete(migration.version);
    const applied = this.getAppliedMigrations();
    this.currentVersion = applied.length > 0
      ? Math.max(...applied.map(m => m.version))
      : 0;
    delete migration.appliedAt;
  }
}

export class DataRepository<T> {
  private data: Map<string, T> = new Map();
  private entityName: string;

  constructor(entityName: string) {
    this.entityName = entityName;
  }

  create(entity: Omit<T, 'id'> & { id?: string }): T {
    const id = (entity as { id?: string }).id || uuidv4();
    const newEntity = { ...entity, id } as T;
    this.data.set(id, newEntity);
    return newEntity;
  }

  getById(id: string): T | undefined {
    return this.data.get(id);
  }

  update(id: string, updates: Partial<T>): T | null {
    const existing = this.data.get(id);
    if (!existing) return null;
    const updated = { ...existing, ...updates } as T;
    this.data.set(id, updated);
    return updated;
  }

  delete(id: string): boolean {
    return this.data.delete(id);
  }

  findAll(options: QueryOptions = {}): PaginatedResult<T> {
    let data = Array.from(this.data.values());

    if (options.filters) {
      data = data.filter(item => this.matchFilters(item, options.filters!));
    }

    if (options.orderBy) {
      data.sort((a, b) => {
        const aVal = (a as Record<string, unknown>)[options.orderBy!];
        const bVal = (b as Record<string, unknown>)[options.orderBy!];
        const direction = options.orderDirection === 'desc' ? -1 : 1;
        return aVal < bVal ? -direction : aVal > bVal ? direction : 0;
      });
    }

    const total = data.length;
    const pageSize = options.limit || total;
    const page = options.offset ? Math.floor(options.offset / pageSize) + 1 : 1;
    const start = options.offset || 0;
    const end = start + pageSize;

    return {
      data: data.slice(start, end),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  findOne(options: QueryOptions = {}): T | undefined {
    const result = this.findAll({ ...options, limit: 1 });
    return result.data[0];
  }

  count(options: QueryOptions = {}): number {
    let data = Array.from(this.data.values());
    if (options.filters) {
      data = data.filter(item => this.matchFilters(item, options.filters!));
    }
    return data.length;
  }

  bulkCreate(entities: Omit<T, 'id'>[]): T[] {
    return entities.map(e => this.create(e));
  }

  bulkUpdate(ids: string[], updates: Partial<T>): (T | null)[] {
    return ids.map(id => this.update(id, updates));
  }

  bulkDelete(ids: string[]): number {
    return ids.filter(id => this.delete(id)).length;
  }

  private matchFilters(item: T, filters: Record<string, unknown>): boolean {
    for (const [key, value] of Object.entries(filters)) {
      const itemValue = (item as Record<string, unknown>)[key];
      if (typeof value === 'object' && value !== null) {
        const filter = value as Record<string, unknown>;
        if ('$in' in filter && Array.isArray(filter.$in)) {
          if (!filter.$in.includes(itemValue)) return false;
        }
        if ('$gt' in filter && typeof itemValue === 'number' && typeof filter.$gt === 'number') {
          if (itemValue <= filter.$gt) return false;
        }
        if ('$lt' in filter && typeof itemValue === 'number' && typeof filter.$lt === 'number') {
          if (itemValue >= filter.$lt) return false;
        }
      } else if (itemValue !== value) {
        return false;
      }
    }
    return true;
  }
}

export class DataTransferManager {
  private sources: Map<string, DataSource> = new Map();
  private storageManager: StorageManager;

  constructor(storageManager: StorageManager) {
    this.storageManager = storageManager;
  }

  registerSource(source: DataSource): void {
    this.sources.set(source.id, source);
  }

  unregisterSource(id: string): boolean {
    return this.sources.delete(id);
  }

  getSources(): DataSource[] {
    return Array.from(this.sources.values());
  }

  async transfer(
    sourceId: string,
    destKey: string,
    data: unknown
  ): Promise<DataTransferResult> {
    try {
      const buffer = Buffer.from(JSON.stringify(data));
      await this.storageManager.put(destKey, buffer, {
        'content-type': 'application/json',
        'source': sourceId,
      });

      return {
        success: true,
        recordsTransferred: Array.isArray(data) ? data.length : 1,
        errors: [],
      };
    } catch (error) {
      return {
        success: false,
        recordsTransferred: 0,
        errors: [(error as Error).message],
      };
    }
  }

  async batchTransfer(
    transfers: { sourceId: string; destKey: string; data: unknown }[]
  ): Promise<DataTransferResult> {
    const results = await Promise.allSettled(
      transfers.map(t => this.transfer(t.sourceId, t.destKey, t.data))
    );

    let recordsTransferred = 0;
    const errors: string[] = [];

    for (const result of results) {
      if (result.status === 'fulfilled') {
        recordsTransferred += result.value.recordsTransferred;
        errors.push(...result.value.errors);
      } else {
        errors.push(result.reason.message);
      }
    }

    return {
      success: errors.length === 0,
      recordsTransferred,
      errors,
    };
  }

  async exportData(key: string): Promise<unknown | null> {
    const result = await this.storageManager.get(key);
    if (!result) return null;
    try {
      return JSON.parse(result.data.toString('utf8'));
    } catch {
      return result.data;
    }
  }
}

export class WriteAheadLog {
  private log: { id: string; operation: string; data: unknown; timestamp: string }[] = [];
  private maxSize: number = 10000;
  private flushed: Set<string> = new Set();

  write(operation: string, data: unknown): string {
    const entry = {
      id: uuidv4(),
      operation,
      data,
      timestamp: new Date().toISOString(),
    };
    this.log.push(entry);
    if (this.log.length > this.maxSize) {
      this.log = this.log.slice(-this.maxSize);
    }
    return entry.id;
  }

  markFlushed(id: string): void {
    this.flushed.add(id);
    this.cleanupFlushed();
  }

  getUnflushed(): typeof this.log {
    return this.log.filter(e => !this.flushed.has(e.id));
  }

  replay(callback: (entry: typeof this.log[0]) => Promise<void>): Promise<void> {
    return RetryHandler.withRetry(async () => {
      const unflushed = this.getUnflushed();
      for (const entry of unflushed) {
        await callback(entry);
        this.markFlushed(entry.id);
      }
    });
  }

  private cleanupFlushed(): void {
    if (this.flushed.size > this.maxSize) {
      const ids = Array.from(this.flushed);
      const toRemove = ids.slice(0, Math.floor(ids.length / 2));
      for (const id of toRemove) {
        this.flushed.delete(id);
      }
      this.log = this.log.filter(e => !this.flushed.has(e.id));
    }
  }

  getLog(): typeof this.log {
    return [...this.log];
  }
}

export class QueryBuilder<T> {
  private filters: Record<string, unknown> = {};
  private sortField?: string;
  private sortDirection: 'asc' | 'desc' = 'asc';
  private limitValue?: number;
  private offsetValue: number = 0;
  private repository: DataRepository<T>;

  constructor(repository: DataRepository<T>) {
    this.repository = repository;
  }

  where(field: string, operator: string, value: unknown): QueryBuilder<T> {
    if (operator === '=') {
      this.filters[field] = value;
    } else if (operator === 'in') {
      this.filters[field] = { $in: value };
    } else if (operator === '>') {
      this.filters[field] = { $gt: value };
    } else if (operator === '<') {
      this.filters[field] = { $lt: value };
    }
    return this;
  }

  orderBy(field: string, direction: 'asc' | 'desc' = 'asc'): QueryBuilder<T> {
    this.sortField = field;
    this.sortDirection = direction;
    return this;
  }

  limit(limit: number): QueryBuilder<T> {
    this.limitValue = limit;
    return this;
  }

  offset(offset: number): QueryBuilder<T> {
    this.offsetValue = offset;
    return this;
  }

  async execute(): Promise<PaginatedResult<T>> {
    return this.repository.findAll({
      filters: this.filters,
      orderBy: this.sortField,
      orderDirection: this.sortDirection,
      limit: this.limitValue,
      offset: this.offsetValue,
    });
  }

  async first(): Promise<T | undefined> {
    const result = await this.limit(1).execute();
    return result.data[0];
  }

  async count(): Promise<number> {
    return this.repository.count({ filters: this.filters });
  }
}

export function createDataAccessModule(storageManager: StorageManager): {
  migrationManager: SchemaMigrationManager;
  transferManager: DataTransferManager;
  wal: WriteAheadLog;
  createRepository: <T>(entityName: string) => DataRepository<T>;
  createQueryBuilder: <T>(repository: DataRepository<T>) => QueryBuilder<T>;
} {
  const migrationManager = new SchemaMigrationManager();
  const transferManager = new DataTransferManager(storageManager);
  const wal = new WriteAheadLog();

  const createRepository = <T>(entityName: string) => new DataRepository<T>(entityName);
  const createQueryBuilder = <T>(repository: DataRepository<T>) => new QueryBuilder<T>(repository);

  return {
    migrationManager,
    transferManager,
    wal,
    createRepository,
    createQueryBuilder,
  };
}
