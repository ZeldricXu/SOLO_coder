import * as path from 'path'
import * as fs from 'fs'
import {
  RotationRecord,
  ValidationReport,
  DiffReport,
  NotificationMessage,
  ConfigValue,
  DiffItem,
  SyncResult,
} from '../types'

export type HistoryEvent =
  | { type: 'rotation'; data: RotationRecord }
  | { type: 'validation'; data: ValidationReport }
  | { type: 'diff'; data: DiffReport }
  | { type: 'notification'; data: NotificationMessage; results: { channelId: string; success: boolean; error?: string }[] }
  | { type: 'sync'; data: { item: { key: string; sourceEnvironment: string; targetEnvironments: string[] }; results: SyncResult[]; dryRun: boolean; operator: string } }

export class HistoryStorage {
  private dbPath: string
  private db: any = null

  constructor(dbPath: string) {
    this.dbPath = path.resolve(dbPath)
  }

  private async init(): Promise<void> {
    if (this.db) return

    const { default: Database } = await import('better-sqlite3')

    const dir = path.dirname(this.dbPath)
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true })
    }

    this.db = new Database(this.dbPath)
    this.db.pragma('journal_mode = WAL')
    this.db.pragma('foreign_keys = ON')

    this.createTables()
  }

  private createTables(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS rotation_history (
        id TEXT PRIMARY KEY,
        key TEXT NOT NULL,
        environment TEXT NOT NULL,
        source_type TEXT NOT NULL,
        timestamp INTEGER NOT NULL,
        operator TEXT NOT NULL,
        status TEXT NOT NULL,
        message TEXT
      );

      CREATE INDEX IF NOT EXISTS idx_rotation_env_key ON rotation_history(environment, key);
      CREATE INDEX IF NOT EXISTS idx_rotation_timestamp ON rotation_history(timestamp);

      CREATE TABLE IF NOT EXISTS validation_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        environment TEXT NOT NULL,
        valid INTEGER NOT NULL,
        error_count INTEGER NOT NULL,
        errors_json TEXT,
        timestamp INTEGER NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_validation_env ON validation_history(environment);
      CREATE INDEX IF NOT EXISTS idx_validation_timestamp ON validation_history(timestamp);

      CREATE TABLE IF NOT EXISTS diff_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        environment_a TEXT NOT NULL,
        environment_b TEXT NOT NULL,
        added_count INTEGER NOT NULL,
        removed_count INTEGER NOT NULL,
        changed_count INTEGER NOT NULL,
        diffs_json TEXT,
        timestamp INTEGER NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_diff_envs ON diff_history(environment_a, environment_b);
      CREATE INDEX IF NOT EXISTS idx_diff_timestamp ON diff_history(timestamp);

      CREATE TABLE IF NOT EXISTS notification_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        title TEXT NOT NULL,
        environment TEXT NOT NULL,
        operator TEXT NOT NULL,
        change_count INTEGER NOT NULL,
        results_json TEXT,
        timestamp INTEGER NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_notification_env ON notification_history(environment);
      CREATE INDEX IF NOT EXISTS idx_notification_timestamp ON notification_history(timestamp);

      CREATE TABLE IF NOT EXISTS sync_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        key_name TEXT NOT NULL,
        source_environment TEXT NOT NULL,
        target_environments_json TEXT NOT NULL,
        results_json TEXT NOT NULL,
        dry_run INTEGER NOT NULL,
        operator TEXT NOT NULL,
        timestamp INTEGER NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_sync_key ON sync_history(key_name);
      CREATE INDEX IF NOT EXISTS idx_sync_source_env ON sync_history(source_environment);
      CREATE INDEX IF NOT EXISTS idx_sync_timestamp ON sync_history(timestamp);

      CREATE TABLE IF NOT EXISTS key_value_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        environment TEXT NOT NULL,
        key_path TEXT NOT NULL,
        old_value_json TEXT,
        new_value_json TEXT,
        operator TEXT,
        change_type TEXT NOT NULL,
        commit_hash TEXT,
        timestamp INTEGER NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_kvh_env_key ON key_value_history(environment, key_path);
      CREATE INDEX IF NOT EXISTS idx_kvh_timestamp ON key_value_history(timestamp);
    `)
  }

  async recordRotation(record: RotationRecord): Promise<void> {
    await this.init()
    this.db.prepare(`
      INSERT OR REPLACE INTO rotation_history
      (id, key, environment, source_type, timestamp, operator, status, message)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      record.id,
      record.key,
      record.environment,
      record.sourceType,
      record.timestamp,
      record.operator,
      record.status,
      record.message || null
    )
  }

  async recordValidation(report: ValidationReport): Promise<void> {
    await this.init()
    this.db.prepare(`
      INSERT INTO validation_history
      (environment, valid, error_count, errors_json, timestamp)
      VALUES (?, ?, ?, ?, ?)
    `).run(
      report.environment,
      report.valid ? 1 : 0,
      report.errors.length,
      JSON.stringify(report.errors),
      report.timestamp
    )
  }

  async recordDiff(report: DiffReport): Promise<void> {
    await this.init()
    this.db.prepare(`
      INSERT INTO diff_history
      (environment_a, environment_b, added_count, removed_count, changed_count, diffs_json, timestamp)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(
      report.environmentA,
      report.environmentB,
      report.summary.added,
      report.summary.removed,
      report.summary.changed,
      JSON.stringify(report.diffs),
      report.timestamp
    )
  }

  async recordNotification(
    message: NotificationMessage,
    results: { channelId: string; success: boolean; error?: string }[]
  ): Promise<void> {
    await this.init()
    this.db.prepare(`
      INSERT INTO notification_history
      (title, environment, operator, change_count, results_json, timestamp)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(
      message.title,
      message.environment,
      message.operator,
      message.changes.length,
      JSON.stringify(results),
      message.timestamp
    )
  }

  async recordSync(
    item: { key: string; sourceEnvironment: string; targetEnvironments: string[] },
    results: SyncResult[],
    dryRun: boolean,
    operator: string
  ): Promise<void> {
    await this.init()
    this.db.prepare(`
      INSERT INTO sync_history
      (key_name, source_environment, target_environments_json, results_json, dry_run, operator, timestamp)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(
      item.key,
      item.sourceEnvironment,
      JSON.stringify(item.targetEnvironments),
      JSON.stringify(results),
      dryRun ? 1 : 0,
      operator,
      Date.now()
    )
  }

  async recordKeyValueChange(
    environment: string,
    keyPath: string,
    oldValue: ConfigValue | undefined,
    newValue: ConfigValue | undefined,
    operator: string,
    commitHash?: string
  ): Promise<void> {
    await this.init()

    let changeType: string
    if (oldValue === undefined && newValue !== undefined) changeType = 'added'
    else if (oldValue !== undefined && newValue === undefined) changeType = 'removed'
    else changeType = 'changed'

    this.db.prepare(`
      INSERT INTO key_value_history
      (environment, key_path, old_value_json, new_value_json, operator, change_type, commit_hash, timestamp)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      environment,
      keyPath,
      oldValue !== undefined ? JSON.stringify(oldValue) : null,
      newValue !== undefined ? JSON.stringify(newValue) : null,
      operator,
      changeType,
      commitHash || null,
      Date.now()
    )
  }

  async getRotationHistory(filters?: {
    environment?: string
    key?: string
    status?: string
    since?: number
    until?: number
    limit?: number
  }): Promise<RotationRecord[]> {
    await this.init()

    const conditions: string[] = []
    const params: unknown[] = []

    if (filters?.environment) { conditions.push('environment = ?'); params.push(filters.environment) }
    if (filters?.key) { conditions.push('key = ?'); params.push(filters.key) }
    if (filters?.status) { conditions.push('status = ?'); params.push(filters.status) }
    if (filters?.since) { conditions.push('timestamp >= ?'); params.push(filters.since) }
    if (filters?.until) { conditions.push('timestamp <= ?'); params.push(filters.until) }

    let sql = `
      SELECT id, key, environment, source_type, timestamp, operator, status, message
      FROM rotation_history
    `
    if (conditions.length > 0) sql += ' WHERE ' + conditions.join(' AND ')
    sql += ' ORDER BY timestamp DESC'
    if (filters?.limit) { sql += ' LIMIT ?'; params.push(filters.limit) }

    const rows = this.db.prepare(sql).all(...params)
    return rows.map((r: {
      id: string; key: string; environment: string; source_type: string; timestamp: number; operator: string; status: 'success' | 'failed'; message?: string
    }) => ({
      id: r.id,
      key: r.key,
      environment: r.environment,
      sourceType: r.source_type,
      timestamp: r.timestamp,
      operator: r.operator,
      status: r.status,
      message: r.message,
    }))
  }

  async getValidationHistory(filters?: {
    environment?: string
    since?: number
    until?: number
    limit?: number
    invalidOnly?: boolean
  }): Promise<ValidationReport[]> {
    await this.init()

    const conditions: string[] = []
    const params: unknown[] = []

    if (filters?.environment) { conditions.push('environment = ?'); params.push(filters.environment) }
    if (filters?.since) { conditions.push('timestamp >= ?'); params.push(filters.since) }
    if (filters?.until) { conditions.push('timestamp <= ?'); params.push(filters.until) }
    if (filters?.invalidOnly) { conditions.push('valid = 0') }

    let sql = `
      SELECT environment, valid, error_count, errors_json, timestamp
      FROM validation_history
    `
    if (conditions.length > 0) sql += ' WHERE ' + conditions.join(' AND ')
    sql += ' ORDER BY timestamp DESC'
    if (filters?.limit) { sql += ' LIMIT ?'; params.push(filters.limit) }

    const rows = this.db.prepare(sql).all(...params)
    return rows.map((r: {
      environment: string; valid: number; error_count: number; errors_json: string; timestamp: number
    }) => ({
      environment: r.environment,
      valid: r.valid === 1,
      errors: JSON.parse(r.errors_json || '[]'),
      timestamp: r.timestamp,
    }))
  }

  async getDiffHistory(filters?: {
    environmentA?: string
    environmentB?: string
    since?: number
    until?: number
    limit?: number
  }): Promise<DiffReport[]> {
    await this.init()

    const conditions: string[] = []
    const params: unknown[] = []

    if (filters?.environmentA) { conditions.push('environment_a = ?'); params.push(filters.environmentA) }
    if (filters?.environmentB) { conditions.push('environment_b = ?'); params.push(filters.environmentB) }
    if (filters?.since) { conditions.push('timestamp >= ?'); params.push(filters.since) }
    if (filters?.until) { conditions.push('timestamp <= ?'); params.push(filters.until) }

    let sql = `
      SELECT environment_a, environment_b, added_count, removed_count, changed_count, diffs_json, timestamp
      FROM diff_history
    `
    if (conditions.length > 0) sql += ' WHERE ' + conditions.join(' AND ')
    sql += ' ORDER BY timestamp DESC'
    if (filters?.limit) { sql += ' LIMIT ?'; params.push(filters.limit) }

    const rows = this.db.prepare(sql).all(...params)
    return rows.map((r: {
      environment_a: string; environment_b: string; added_count: number; removed_count: number; changed_count: number; diffs_json: string; timestamp: number
    }) => ({
      environmentA: r.environment_a,
      environmentB: r.environment_b,
      diffs: JSON.parse(r.diffs_json || '[]') as DiffItem[],
      summary: {
        added: r.added_count,
        removed: r.removed_count,
        changed: r.changed_count,
        total: r.added_count + r.removed_count + r.changed_count,
      },
      timestamp: r.timestamp,
    }))
  }

  async getKeyValueHistory(filters: {
    environment: string
    keyPath?: string
    since?: number
    until?: number
    limit?: number
  }): Promise<{
    environment: string
    keyPath: string
    oldValue: ConfigValue | undefined
    newValue: ConfigValue | undefined
    operator: string
    changeType: string
    commitHash: string | undefined
    timestamp: number
  }[]> {
    await this.init()

    const conditions: string[] = []
    const params: unknown[] = []

    conditions.push('environment = ?')
    params.push(filters.environment)

    if (filters.keyPath) { conditions.push('key_path = ?'); params.push(filters.keyPath) }
    if (filters.since) { conditions.push('timestamp >= ?'); params.push(filters.since) }
    if (filters.until) { conditions.push('timestamp <= ?'); params.push(filters.until) }

    let sql = `
      SELECT environment, key_path, old_value_json, new_value_json, operator, change_type, commit_hash, timestamp
      FROM key_value_history
    `
    sql += ' WHERE ' + conditions.join(' AND ')
    sql += ' ORDER BY timestamp DESC'
    if (filters.limit) { sql += ' LIMIT ?'; params.push(filters.limit) }

    const rows = this.db.prepare(sql).all(...params)
    return rows.map((r: {
      environment: string; key_path: string; old_value_json: string; new_value_json: string; operator: string; change_type: string; commit_hash: string; timestamp: number
    }) => ({
      environment: r.environment,
      keyPath: r.key_path,
      oldValue: r.old_value_json ? JSON.parse(r.old_value_json) : undefined,
      newValue: r.new_value_json ? JSON.parse(r.new_value_json) : undefined,
      operator: r.operator,
      changeType: r.change_type,
      commitHash: r.commit_hash || undefined,
      timestamp: r.timestamp,
    }))
  }

  close(): void {
    if (this.db) {
      this.db.close()
      this.db = null
    }
  }

  getDbPath(): string {
    return this.dbPath
  }
}
