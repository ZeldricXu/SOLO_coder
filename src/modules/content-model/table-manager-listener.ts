import { tableManager } from './table-manager';
import { schemaEvents } from './schema-events';
import { connectionPool } from '../tenant/connection-pool';
import { schemaValidator } from './schema-validator';
import { logger } from '@utils/logger';
import { Prisma } from '@prisma/client';

export class TableManagerListener {
  private prisma = connectionPool.getPlatformPrisma();
  private isListening = false;

  start(): void {
    if (this.isListening) return;

    schemaEvents.on('schema.created', this.handleSchemaCreated.bind(this));
    schemaEvents.on('schema.updated', this.handleSchemaUpdated.bind(this));
    schemaEvents.on('schema.deleted', this.handleSchemaDeleted.bind(this));
    schemaEvents.on('schema.migrate-content', this.handleMigrateContent.bind(this));

    this.isListening = true;
    logger.info('Table manager event listeners started');
  }

  stop(): void {
    if (!this.isListening) return;

    schemaEvents.off('schema.created', this.handleSchemaCreated.bind(this));
    schemaEvents.off('schema.updated', this.handleSchemaUpdated.bind(this));
    schemaEvents.off('schema.deleted', this.handleSchemaDeleted.bind(this));
    schemaEvents.off('schema.migrate-content', this.handleMigrateContent.bind(this));

    this.isListening = false;
    logger.info('Table manager event listeners stopped');
  }

  private async handleSchemaCreated(event: import('./schema-events').SchemaCreatedEvent): Promise<void> {
    logger.info(
      { tenantId: event.tenantId, modelId: event.modelId, tableName: event.tableName },
      'Processing schema.created event'
    );

    try {
      const pool = connectionPool.getTenantPool(event.tenantId, event.dbSchema);
      await tableManager.createContentTable(pool, event.dbSchema, event.tableName, event.schema);

      logger.info(
        { tenantId: event.tenantId, modelId: event.modelId },
        'Content table created successfully'
      );
    } catch (error) {
      logger.error(
        { error, tenantId: event.tenantId, modelId: event.modelId },
        'Failed to create content table from schema event'
      );
    }
  }

  private async handleSchemaUpdated(event: import('./schema-events').SchemaUpdatedEvent): Promise<void> {
    logger.info(
      { tenantId: event.tenantId, modelId: event.modelId, tableName: event.tableName },
      'Processing schema.updated event'
    );

    try {
      const pool = connectionPool.getTenantPool(event.tenantId, event.dbSchema);
      const alterResult = await tableManager.alterContentTable(
        pool,
        event.dbSchema,
        event.tableName,
        event.oldSchema,
        event.newSchema
      );

      if (alterResult.applied) {
        await tableManager.recordMigration(
          pool,
          event.dbSchema,
          `v${event.version}`,
          `Schema update for ${event.tableName}`
        );

        logger.info(
          { tenantId: event.tenantId, modelId: event.modelId, changes: alterResult.changes },
          'Content table altered successfully'
        );
      }
    } catch (error) {
      logger.error(
        { error, tenantId: event.tenantId, modelId: event.modelId },
        'Failed to alter content table from schema event'
      );
    }
  }

  private async handleSchemaDeleted(event: import('./schema-events').SchemaDeletedEvent): Promise<void> {
    logger.info(
      { tenantId: event.tenantId, modelId: event.modelId, tableName: event.tableName },
      'Processing schema.deleted event'
    );

    if (!event.dbSchema) {
      logger.warn(
        { tenantId: event.tenantId, modelId: event.modelId },
        'No dbSchema provided for schema.deleted event, skipping table drop'
      );
      return;
    }

    try {
      const pool = connectionPool.getTenantPool(event.tenantId, event.dbSchema);
      await tableManager.dropContentTable(pool, event.dbSchema, event.tableName);

      logger.info(
        { tenantId: event.tenantId, modelId: event.modelId },
        'Content table dropped successfully'
      );
    } catch (error) {
      logger.error(
        { error, tenantId: event.tenantId, modelId: event.modelId },
        'Failed to drop content table from schema event'
      );
    }
  }

  private async handleMigrateContent(
    event: import('./schema-events').SchemaMigrateContentEvent
  ): Promise<void> {
    logger.info(
      { tenantId: event.tenantId, modelId: event.modelId, version: event.version },
      'Processing schema.migrate-content event'
    );

    try {
      const entries = await this.prisma.contentEntry.findMany({
        where: {
          tenantId: event.tenantId,
          modelId: event.modelId,
          deletedAt: null,
        },
        select: { id: true, data: true, publishedData: true },
      });

      let migratedCount = 0;
      for (const entry of entries) {
        const migrateResult = schemaValidator.migrateContent(
          entry.data as Record<string, unknown>,
          event.oldSchema,
          event.newSchema
        );

        await this.prisma.contentEntry.update({
          where: { id: entry.id },
          data: {
            data: migrateResult.content as unknown as Prisma.JsonValue,
            updatedAt: new Date(),
          },
        });

        if (entry.publishedData) {
          const publishedMigrateResult = schemaValidator.migrateContent(
            entry.publishedData as Record<string, unknown>,
            event.oldSchema,
            event.newSchema
          );
          await this.prisma.contentEntry.update({
            where: { id: entry.id },
            data: {
              publishedData: publishedMigrateResult.content as unknown as Prisma.JsonValue,
            },
          });
        }

        migratedCount++;
      }

      logger.info(
        { tenantId: event.tenantId, modelId: event.modelId, migratedCount, total: entries.length },
        'Content migration completed'
      );
    } catch (error) {
      logger.error(
        { error, tenantId: event.tenantId, modelId: event.modelId },
        'Failed to migrate content from schema event'
      );
    }
  }
}

export const tableManagerListener = new TableManagerListener();
