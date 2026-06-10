import { EventEmitter } from 'events';
import { ContentSchema } from '@types/index';

export interface SchemaCreatedEvent {
  tenantId: string;
  dbSchema: string;
  modelId: string;
  tableName: string;
  schema: ContentSchema;
  version: number;
}

export interface SchemaUpdatedEvent {
  tenantId: string;
  dbSchema: string;
  modelId: string;
  tableName: string;
  oldSchema: ContentSchema;
  newSchema: ContentSchema;
  version: number;
}

export interface SchemaDeletedEvent {
  tenantId: string;
  dbSchema?: string;
  modelId: string;
  tableName: string;
}

export interface SchemaMigrateContentEvent {
  tenantId: string;
  modelId: string;
  oldSchema: ContentSchema;
  newSchema: ContentSchema;
  version: number;
}

export type SchemaEventType =
  | 'schema.created'
  | 'schema.updated'
  | 'schema.deleted'
  | 'schema.migrate-content';

export class SchemaEventBus extends EventEmitter {
  emit(event: 'schema.created', data: SchemaCreatedEvent): boolean;
  emit(event: 'schema.updated', data: SchemaUpdatedEvent): boolean;
  emit(event: 'schema.deleted', data: SchemaDeletedEvent): boolean;
  emit(event: 'schema.migrate-content', data: SchemaMigrateContentEvent): boolean;
  emit(event: SchemaEventType, data: unknown): boolean {
    return super.emit(event, data);
  }

  on(event: 'schema.created', listener: (data: SchemaCreatedEvent) => void): this;
  on(event: 'schema.updated', listener: (data: SchemaUpdatedEvent) => void): this;
  on(event: 'schema.deleted', listener: (data: SchemaDeletedEvent) => void): this;
  on(event: 'schema.migrate-content', listener: (data: SchemaMigrateContentEvent) => void): this;
  on(event: SchemaEventType, listener: (...args: unknown[]) => void): this {
    return super.on(event, listener);
  }

  once(event: 'schema.created', listener: (data: SchemaCreatedEvent) => void): this;
  once(event: 'schema.updated', listener: (data: SchemaUpdatedEvent) => void): this;
  once(event: 'schema.deleted', listener: (data: SchemaDeletedEvent) => void): this;
  once(event: 'schema.migrate-content', listener: (data: SchemaMigrateContentEvent) => void): this;
  once(event: SchemaEventType, listener: (...args: unknown[]) => void): this {
    return super.once(event, listener);
  }
}

export const schemaEvents = new SchemaEventBus();
