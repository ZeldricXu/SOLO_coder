import { Pool } from 'pg';
import { sql } from 'drizzle-orm';
import { ContentSchema, FieldType, ContentField } from '@types/index';
import { logger } from '@utils/logger';

const PG_TYPE_MAPPINGS: Record<FieldType, string> = {
  string: 'VARCHAR(255)',
  text: 'TEXT',
  integer: 'INTEGER',
  float: 'NUMERIC(15,6)',
  boolean: 'BOOLEAN',
  date: 'DATE',
  datetime: 'TIMESTAMP WITH TIME ZONE',
  json: 'JSONB',
  reference: 'UUID',
  file: 'JSONB',
  image: 'JSONB',
  richtext: 'TEXT',
  select: 'VARCHAR(255)',
  multiselect: 'JSONB',
};

export interface ColumnDefinition {
  name: string;
  type: string;
  nullable: boolean;
  unique?: boolean;
  default?: string;
}

export class TableManager {
  async createContentTable(
    pool: Pool,
    schemaName: string,
    tableName: string,
    schema: ContentSchema
  ): Promise<void> {
    const columns = this.buildColumnDefinitions(schema);
    const columnSql = columns.map(col => this.formatColumn(col)).join(',\n  ');

    const createTableSql = `
      CREATE TABLE IF NOT EXISTS "${schemaName}"."${tableName}" (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        tenant_id UUID NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT 'draft',
        ${columnSql},
        created_by UUID NOT NULL,
        updated_by UUID NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        deleted_at TIMESTAMP WITH TIME ZONE
      )
    `;

    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      await client.query(createTableSql);
      await this.createIndexes(client, schemaName, tableName, schema);
      await client.query(`
        CREATE INDEX IF NOT EXISTS "${tableName}_tenant_id_idx"
        ON "${schemaName}"."${tableName}" (tenant_id)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS "${tableName}_status_idx"
        ON "${schemaName}"."${tableName}" (status)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS "${tableName}_created_at_idx"
        ON "${schemaName}"."${tableName}" (created_at DESC)
      `);
      await client.query('COMMIT');

      logger.info(`Created content table: ${schemaName}.${tableName}`);
    } catch (error) {
      await client.query('ROLLBACK');
      logger.error({ error, schemaName, tableName }, 'Failed to create content table');
      throw error;
    } finally {
      client.release();
    }
  }

  async alterContentTable(
    pool: Pool,
    schemaName: string,
    tableName: string,
    oldSchema: ContentSchema,
    newSchema: ContentSchema
  ): Promise<{ applied: boolean; changes: string[] }> {
    const changes: string[] = [];
    const alterStatements: string[] = [];

    const oldFields = Object.keys(oldSchema.properties);
    const newFields = Object.keys(newSchema.properties);

    for (const field of newFields) {
      if (!oldFields.includes(field)) {
        const fieldDef = newSchema.properties[field];
        const colDef = this.buildColumnDefinition(field, fieldDef);
        alterStatements.push(`ADD COLUMN ${this.formatColumn(colDef)}`);
        changes.push(`Added column: ${field} (${fieldDef.type})`);
      }
    }

    for (const field of oldFields) {
      if (!newFields.includes(field)) {
        alterStatements.push(`DROP COLUMN IF EXISTS "${field}" CASCADE`);
        changes.push(`Dropped column: ${field}`);
      }
    }

    for (const field of newFields) {
      if (oldFields.includes(field)) {
        const oldField = oldSchema.properties[field];
        const newField = newSchema.properties[field];

        if (oldField.type !== newField.type) {
          const newType = PG_TYPE_MAPPINGS[newField.type];
          const usingClause = this.getUsingClause(field, oldField.type, newField.type);
          alterStatements.push(`ALTER COLUMN "${field}" TYPE ${newType}${usingClause}`);
          changes.push(`Changed column type: ${field} from ${oldField.type} to ${newField.type}`);
        }

        if (oldField.required !== newField.required) {
          if (newField.required) {
            alterStatements.push(`ALTER COLUMN "${field}" SET NOT NULL`);
            changes.push(`Set column NOT NULL: ${field}`);
          } else {
            alterStatements.push(`ALTER COLUMN "${field}" DROP NOT NULL`);
            changes.push(`Dropped column NOT NULL: ${field}`);
          }
        }

        if (oldField.unique !== newField.unique) {
          if (newField.unique) {
            alterStatements.push(`ADD CONSTRAINT "${tableName}_${field}_unique" UNIQUE ("${field}")`);
            changes.push(`Added unique constraint: ${field}`);
          } else {
            alterStatements.push(`DROP CONSTRAINT IF EXISTS "${tableName}_${field}_unique"`);
            changes.push(`Dropped unique constraint: ${field}`);
          }
        }
      }
    }

    if (alterStatements.length === 0) {
      return { applied: false, changes: [] };
    }

    const client = await pool.connect();
    try {
      await client.query('BEGIN');

      for (const stmt of alterStatements) {
        await client.query(`ALTER TABLE "${schemaName}"."${tableName}" ${stmt}`);
      }

      await this.rebuildIndexes(client, schemaName, tableName, oldSchema, newSchema);

      await client.query('COMMIT');

      logger.info({ schemaName, tableName, changes }, 'Altered content table');

      return { applied: true, changes };
    } catch (error) {
      await client.query('ROLLBACK');
      logger.error({ error, schemaName, tableName }, 'Failed to alter content table');
      throw error;
    } finally {
      client.release();
    }
  }

  private getUsingClause(field: string, oldType: FieldType, newType: FieldType): string {
    const typePairs: Record<string, string> = {
      'string->integer': ` USING "${field}"::integer`,
      'string->float': ` USING "${field}"::numeric`,
      'string->boolean': ` USING "${field}"::boolean`,
      'string->date': ` USING "${field}"::date`,
      'string->datetime': ` USING "${field}"::timestamp with time zone`,
      'string->json': ` USING "${field}"::jsonb`,
      'integer->string': ` USING "${field}"::varchar`,
      'float->string': ` USING "${field}"::varchar`,
      'boolean->string': ` USING "${field}"::varchar`,
      'date->string': ` USING "${field}"::varchar`,
      'datetime->string': ` USING "${field}"::varchar`,
    };

    const key = `${oldType}->${newType}`;
    return typePairs[key] || '';
  }

  private buildColumnDefinitions(schema: ContentSchema): ColumnDefinition[] {
    const columns: ColumnDefinition[] = [];

    for (const [name, field] of Object.entries(schema.properties)) {
      columns.push(this.buildColumnDefinition(name, field));
    }

    return columns;
  }

  private buildColumnDefinition(name: string, field: ContentField): ColumnDefinition {
    return {
      name,
      type: PG_TYPE_MAPPINGS[field.type] || 'TEXT',
      nullable: !field.required,
      unique: field.unique,
    };
  }

  private formatColumn(col: ColumnDefinition): string {
    let sql = `"${col.name}" ${col.type}`;
    if (!col.nullable) sql += ' NOT NULL';
    if (col.unique) sql += ' UNIQUE';
    if (col.default) sql += ` DEFAULT ${col.default}`;
    return sql;
  }

  private async createIndexes(
    client: Pool['_client'],
    schemaName: string,
    tableName: string,
    schema: ContentSchema
  ): Promise<void> {
    for (const [name, field] of Object.entries(schema.properties)) {
      if (field.indexed && !field.unique) {
        await client.query(`
          CREATE INDEX IF NOT EXISTS "${tableName}_${name}_idx"
          ON "${schemaName}"."${tableName}" ("${name}")
        `);
      }
    }
  }

  private async rebuildIndexes(
    client: Pool['_client'],
    schemaName: string,
    tableName: string,
    oldSchema: ContentSchema,
    newSchema: ContentSchema
  ): Promise<void> {
    const oldIndexed = new Set(
      Object.entries(oldSchema.properties)
        .filter(([, f]) => f.indexed || f.unique)
        .map(([name]) => name)
    );

    const newIndexed = new Set(
      Object.entries(newSchema.properties)
        .filter(([, f]) => f.indexed || f.unique)
        .map(([name]) => name)
    );

    for (const field of oldIndexed) {
      if (!newIndexed.has(field)) {
        await client.query(`
          DROP INDEX IF EXISTS "${schemaName}"."${tableName}_${field}_idx"
        `);
      }
    }

    for (const field of newIndexed) {
      if (!oldIndexed.has(field)) {
        await client.query(`
          CREATE INDEX IF NOT EXISTS "${tableName}_${field}_idx"
          ON "${schemaName}"."${tableName}" ("${field}")
        `);
      }
    }
  }

  async dropContentTable(
    pool: Pool,
    schemaName: string,
    tableName: string
  ): Promise<void> {
    const client = await pool.connect();
    try {
      await client.query(`
        DROP TABLE IF EXISTS "${schemaName}"."${tableName}" CASCADE
      `);
      logger.info(`Dropped content table: ${schemaName}.${tableName}`);
    } catch (error) {
      logger.error({ error, schemaName, tableName }, 'Failed to drop content table');
      throw error;
    } finally {
      client.release();
    }
  }

  async tableExists(
    pool: Pool,
    schemaName: string,
    tableName: string
  ): Promise<boolean> {
    const result = await pool.query(
      `SELECT EXISTS (
        SELECT FROM information_schema.tables
        WHERE table_schema = $1 AND table_name = $2
      )`,
      [schemaName, tableName]
    );
    return result.rows[0].exists;
  }

  async getTableColumns(
    pool: Pool,
    schemaName: string,
    tableName: string
  ): Promise<Array<{ name: string; type: string; nullable: boolean }>> {
    const result = await pool.query(
      `SELECT column_name, data_type, is_nullable
       FROM information_schema.columns
       WHERE table_schema = $1 AND table_name = $2
       ORDER BY ordinal_position`,
      [schemaName, tableName]
    );

    return result.rows.map(row => ({
      name: row.column_name,
      type: row.data_type,
      nullable: row.is_nullable === 'YES',
    }));
  }

  async recordMigration(
    pool: Pool,
    schemaName: string,
    version: string,
    description: string
  ): Promise<void> {
    await pool.query(
      `INSERT INTO "${schemaName}".schema_migrations (version, applied_at)
       VALUES ($1, $2)
       ON CONFLICT (version) DO NOTHING`,
      [version, new Date()]
    );

    logger.info({ schemaName, version, description }, 'Recorded migration');
  }
}

export const tableManager = new TableManager();
