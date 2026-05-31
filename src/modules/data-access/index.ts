import * as fs from 'fs-extra';
import * as path from 'path';
import { EventEmitter } from 'events';
import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { generateId, currentTimestamp } from '../../utils/helpers';

export interface SchemaField {
  name: string;
  type: 'string' | 'number' | 'boolean' | 'object' | 'array' | 'date';
  required: boolean;
  default?: any;
  description?: string;
}

export interface SchemaDefinition {
  id: string;
  name: string;
  version: number;
  fields: SchemaField[];
  indexes: Array<{ fields: string[]; unique?: boolean }>;
  createdAt: string;
  description?: string;
}

export interface Migration {
  id: string;
  name: string;
  version: number;
  fromVersion: number;
  toVersion: number;
  script: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  appliedAt?: string;
  error?: string;
  createdAt: string;
}

export interface MigrationContext {
  data: any[];
  schema: SchemaDefinition;
}

export interface DataAccessConfig {
  dataDir: string;
  migrationsDir: string;
  schemasDir: string;
}

export interface QueryOptions {
  filter?: Record<string, any>;
  sort?: { field: string; order: 'asc' | 'desc' };
  limit?: number;
  offset?: number;
}

export interface DataRecord {
  id: string;
  schemaVersion: number;
  data: Record<string, any>;
  createdAt: string;
  updatedAt: string;
}

export class DataAccessLayer extends EventEmitter {
  private config: DataAccessConfig;
  private schemas: Map<string, SchemaDefinition> = new Map();
  private migrations: Map<string, Migration> = new Map();
  private dataStore: Map<string, Map<string, DataRecord>> = new Map();
  private appliedVersions: Map<string, number> = new Map();

  constructor(config?: Partial<DataAccessConfig>) {
    super();
    this.config = {
      dataDir: process.env.DATA_DIR || './data',
      migrationsDir: './migrations',
      schemasDir: './schemas',
      ...config,
    };
    this.initialize();
    logger.info('DataAccessLayer initialized', { config: this.config });
  }

  private async initialize(): Promise<void> {
    await fs.ensureDir(this.config.dataDir);
    await fs.ensureDir(this.config.migrationsDir);
    await fs.ensureDir(this.config.schemasDir);
    await this.loadSchemas();
    await this.loadMigrations();
  }

  private async loadSchemas(): Promise<void> {
    try {
      const files = await fs.readdir(this.config.schemasDir);
      for (const file of files) {
        if (file.endsWith('.json')) {
          const content = await fs.readFile(
            path.join(this.config.schemasDir, file),
            'utf-8',
          );
          const schema = JSON.parse(content) as SchemaDefinition;
          this.schemas.set(schema.name, schema);
          this.dataStore.set(schema.name, new Map());
          this.appliedVersions.set(schema.name, schema.version);
        }
      }
    } catch (error) {
      logger.warn('No existing schemas found', { error });
    }
  }

  private async loadMigrations(): Promise<void> {
    try {
      const files = await fs.readdir(this.config.migrationsDir);
      for (const file of files) {
        if (file.endsWith('.json')) {
          const content = await fs.readFile(
            path.join(this.config.migrationsDir, file),
            'utf-8',
          );
          const migration = JSON.parse(content) as Migration;
          this.migrations.set(migration.id, migration);
        }
      }
    } catch (error) {
      logger.warn('No existing migrations found', { error });
    }
  }

  createSchema(definition: Omit<SchemaDefinition, 'id' | 'createdAt'>): SchemaDefinition {
    const id = generateId('sch_');
    const schema: SchemaDefinition = {
      ...definition,
      id,
      createdAt: currentTimestamp(),
    };
    this.schemas.set(schema.name, schema);
    this.dataStore.set(schema.name, new Map());
    this.appliedVersions.set(schema.name, schema.version);
    this.saveSchema(schema);
    logger.info('Schema created', { id, name: schema.name, version: schema.version });
    eventBus.emit('schema.created', schema);
    return schema;
  }

  private async saveSchema(schema: SchemaDefinition): Promise<void> {
    const filePath = path.join(this.config.schemasDir, `${schema.name}.json`);
    await fs.writeFile(filePath, JSON.stringify(schema, null, 2));
  }

  getSchema(name: string): SchemaDefinition | undefined {
    return this.schemas.get(name);
  }

  listSchemas(): SchemaDefinition[] {
    return Array.from(this.schemas.values());
  }

  createMigration(
    migration: Omit<Migration, 'id' | 'status' | 'createdAt'>,
  ): Migration {
    const id = generateId('mig_');
    const newMigration: Migration = {
      ...migration,
      id,
      status: 'pending',
      createdAt: currentTimestamp(),
    };
    this.migrations.set(id, newMigration);
    this.saveMigration(newMigration);
    logger.info('Migration created', { id, name: migration.name, version: migration.version });
    eventBus.emit('migration.created', newMigration);
    return newMigration;
  }

  private async saveMigration(migration: Migration): Promise<void> {
    const filePath = path.join(this.config.migrationsDir, `${migration.name}.json`);
    await fs.writeFile(filePath, JSON.stringify(migration, null, 2));
  }

  async runMigration(migrationId: string): Promise<Migration> {
    const migration = this.migrations.get(migrationId);
    if (!migration) {
      throw new Error(`Migration ${migrationId} not found`);
    }
    if (migration.status === 'completed') {
      return migration;
    }

    migration.status = 'running';
    this.migrations.set(migrationId, migration);
    this.saveMigration(migration);

    try {
      const schemaName = migration.name.split('_v')[0];
      const schema = this.schemas.get(schemaName);
      if (!schema) {
        throw new Error(`Schema ${schemaName} not found`);
      }

      const context: MigrationContext = {
        data: Array.from(this.dataStore.get(schemaName)?.values() || []).map(r => r.data),
        schema,
      };

      await this.executeMigrationScript(migration.script, context);

      migration.status = 'completed';
      migration.appliedAt = currentTimestamp();
      this.appliedVersions.set(schemaName, migration.toVersion);
      
      logger.info('Migration completed', { migrationId, name: migration.name });
      eventBus.emit('migration.completed', migration);
    } catch (error: any) {
      migration.status = 'failed';
      migration.error = error.message;
      logger.error('Migration failed', { migrationId, error: error.message });
      eventBus.emit('migration.failed', { migration, error: error.message });
    }

    this.migrations.set(migrationId, migration);
    this.saveMigration(migration);
    return migration;
  }

  private async executeMigrationScript(script: string, context: MigrationContext): Promise<void> {
    try {
      const fn = new Function('context', script);
      await fn(context);
      
      const schemaName = context.schema.name;
      const store = this.dataStore.get(schemaName);
      if (store) {
        for (const data of context.data) {
          const existing = Array.from(store.values()).find(r => r.data.id === data.id);
          if (existing) {
            existing.data = data;
            existing.updatedAt = currentTimestamp();
            existing.schemaVersion = context.schema.version;
          }
        }
      }
    } catch (error) {
      throw new Error(`Migration script execution failed: ${error}`);
    }
  }

  async runPendingMigrations(): Promise<Migration[]> {
    const pending = Array.from(this.migrations.values())
      .filter(m => m.status === 'pending')
      .sort((a, b) => a.version - b.version);

    const results: Migration[] = [];
    for (const migration of pending) {
      const result = await this.runMigration(migration.id);
      results.push(result);
      if (result.status === 'failed') {
        break;
      }
    }
    return results;
  }

  getMigration(id: string): Migration | undefined {
    return this.migrations.get(id);
  }

  listMigrations(): Migration[] {
    return Array.from(this.migrations.values());
  }

  async insert(schemaName: string, data: Record<string, any>): Promise<DataRecord> {
    const schema = this.schemas.get(schemaName);
    if (!schema) {
      throw new Error(`Schema ${schemaName} not found`);
    }

    this.validateData(data, schema);

    const record: DataRecord = {
      id: data.id || generateId('rec_'),
      schemaVersion: schema.version,
      data: { ...data, id: data.id || generateId('rec_') },
      createdAt: currentTimestamp(),
      updatedAt: currentTimestamp(),
    };

    const store = this.dataStore.get(schemaName)!;
    store.set(record.id, record);

    logger.debug('Data inserted', { schemaName, id: record.id });
    eventBus.emit('data.inserted', { schemaName, record });

    return record;
  }

  async update(
    schemaName: string,
    id: string,
    updates: Record<string, any>,
  ): Promise<DataRecord | undefined> {
    const store = this.dataStore.get(schemaName);
    if (!store) return undefined;

    const record = store.get(id);
    if (!record) return undefined;

    const schema = this.schemas.get(schemaName)!;
    const updatedData = { ...record.data, ...updates };
    this.validateData(updatedData, schema);

    record.data = updatedData;
    record.updatedAt = currentTimestamp();
    store.set(id, record);

    logger.debug('Data updated', { schemaName, id });
    eventBus.emit('data.updated', { schemaName, record });

    return record;
  }

  async delete(schemaName: string, id: string): Promise<boolean> {
    const store = this.dataStore.get(schemaName);
    if (!store) return false;

    const deleted = store.delete(id);
    if (deleted) {
      logger.debug('Data deleted', { schemaName, id });
      eventBus.emit('data.deleted', { schemaName, id });
    }
    return deleted;
  }

  findById(schemaName: string, id: string): DataRecord | undefined {
    return this.dataStore.get(schemaName)?.get(id);
  }

  query(schemaName: string, options: QueryOptions = {}): DataRecord[] {
    const store = this.dataStore.get(schemaName);
    if (!store) return [];

    let results = Array.from(store.values());

    if (options.filter) {
      results = results.filter(record => {
        for (const [key, value] of Object.entries(options.filter!)) {
          if (record.data[key] !== value) return false;
        }
        return true;
      });
    }

    if (options.sort) {
      results.sort((a, b) => {
        const aVal = a.data[options.sort!.field];
        const bVal = b.data[options.sort!.field];
        if (aVal < bVal) return options.sort!.order === 'asc' ? -1 : 1;
        if (aVal > bVal) return options.sort!.order === 'asc' ? 1 : -1;
        return 0;
      });
    }

    if (options.offset) {
      results = results.slice(options.offset);
    }
    if (options.limit) {
      results = results.slice(0, options.limit);
    }

    return results;
  }

  count(schemaName: string, filter?: Record<string, any>): number {
    const store = this.dataStore.get(schemaName);
    if (!store) return 0;

    if (!filter) return store.size;

    return Array.from(store.values()).filter(record => {
      for (const [key, value] of Object.entries(filter!)) {
        if (record.data[key] !== value) return false;
      }
      return true;
    }).length;
  }

  private validateData(data: Record<string, any>, schema: SchemaDefinition): void {
    for (const field of schema.fields) {
      if (field.required && !(field.name in data)) {
        throw new Error(`Required field missing: ${field.name}`);
      }

      if (field.name in data) {
        const value = data[field.name];
        const isValid = this.validateType(value, field.type);
        if (!isValid) {
          throw new Error(
            `Invalid type for field ${field.name}: expected ${field.type}, got ${typeof value}`,
          );
        }
      }
    }
  }

  private validateType(value: any, type: SchemaField['type']): boolean {
    switch (type) {
      case 'string':
        return typeof value === 'string';
      case 'number':
        return typeof value === 'number';
      case 'boolean':
        return typeof value === 'boolean';
      case 'object':
        return typeof value === 'object' && value !== null;
      case 'array':
        return Array.isArray(value);
      case 'date':
        return value instanceof Date || !isNaN(Date.parse(value));
      default:
        return true;
    }
  }

  getAppliedVersion(schemaName: string): number {
    return this.appliedVersions.get(schemaName) || 0;
  }

  getMigrationHistory(schemaName?: string): Migration[] {
    let migrations = Array.from(this.migrations.values());
    if (schemaName) {
      migrations = migrations.filter(m => m.name.startsWith(schemaName));
    }
    return migrations.sort((a, b) => a.version - b.version);
  }

  needsMigration(schemaName: string): boolean {
    const schema = this.schemas.get(schemaName);
    const applied = this.appliedVersions.get(schemaName) || 0;
    return schema ? schema.version > applied : false;
  }
}

export const dataAccess = new DataAccessLayer();
