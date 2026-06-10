"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.HistoryStorage = void 0;
const path = __importStar(require("path"));
const fs = __importStar(require("fs"));
class HistoryStorage {
    dbPath;
    db = null;
    constructor(dbPath) {
        this.dbPath = path.resolve(dbPath);
    }
    async init() {
        if (this.db)
            return;
        const { default: Database } = await Promise.resolve().then(() => __importStar(require('better-sqlite3')));
        const dir = path.dirname(this.dbPath);
        if (!fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
        }
        this.db = new Database(this.dbPath);
        this.db.pragma('journal_mode = WAL');
        this.db.pragma('foreign_keys = ON');
        this.createTables();
    }
    createTables() {
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
    `);
    }
    async recordRotation(record) {
        await this.init();
        this.db.prepare(`
      INSERT OR REPLACE INTO rotation_history
      (id, key, environment, source_type, timestamp, operator, status, message)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run(record.id, record.key, record.environment, record.sourceType, record.timestamp, record.operator, record.status, record.message || null);
    }
    async recordValidation(report) {
        await this.init();
        this.db.prepare(`
      INSERT INTO validation_history
      (environment, valid, error_count, errors_json, timestamp)
      VALUES (?, ?, ?, ?, ?)
    `).run(report.environment, report.valid ? 1 : 0, report.errors.length, JSON.stringify(report.errors), report.timestamp);
    }
    async recordDiff(report) {
        await this.init();
        this.db.prepare(`
      INSERT INTO diff_history
      (environment_a, environment_b, added_count, removed_count, changed_count, diffs_json, timestamp)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(report.environmentA, report.environmentB, report.summary.added, report.summary.removed, report.summary.changed, JSON.stringify(report.diffs), report.timestamp);
    }
    async recordNotification(message, results) {
        await this.init();
        this.db.prepare(`
      INSERT INTO notification_history
      (title, environment, operator, change_count, results_json, timestamp)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(message.title, message.environment, message.operator, message.changes.length, JSON.stringify(results), message.timestamp);
    }
    async recordSync(item, results, dryRun, operator) {
        await this.init();
        this.db.prepare(`
      INSERT INTO sync_history
      (key_name, source_environment, target_environments_json, results_json, dry_run, operator, timestamp)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(item.key, item.sourceEnvironment, JSON.stringify(item.targetEnvironments), JSON.stringify(results), dryRun ? 1 : 0, operator, Date.now());
    }
    async recordKeyValueChange(environment, keyPath, oldValue, newValue, operator, commitHash) {
        await this.init();
        let changeType;
        if (oldValue === undefined && newValue !== undefined)
            changeType = 'added';
        else if (oldValue !== undefined && newValue === undefined)
            changeType = 'removed';
        else
            changeType = 'changed';
        this.db.prepare(`
      INSERT INTO key_value_history
      (environment, key_path, old_value_json, new_value_json, operator, change_type, commit_hash, timestamp)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run(environment, keyPath, oldValue !== undefined ? JSON.stringify(oldValue) : null, newValue !== undefined ? JSON.stringify(newValue) : null, operator, changeType, commitHash || null, Date.now());
    }
    async getRotationHistory(filters) {
        await this.init();
        const conditions = [];
        const params = [];
        if (filters?.environment) {
            conditions.push('environment = ?');
            params.push(filters.environment);
        }
        if (filters?.key) {
            conditions.push('key = ?');
            params.push(filters.key);
        }
        if (filters?.status) {
            conditions.push('status = ?');
            params.push(filters.status);
        }
        if (filters?.since) {
            conditions.push('timestamp >= ?');
            params.push(filters.since);
        }
        if (filters?.until) {
            conditions.push('timestamp <= ?');
            params.push(filters.until);
        }
        let sql = `
      SELECT id, key, environment, source_type, timestamp, operator, status, message
      FROM rotation_history
    `;
        if (conditions.length > 0)
            sql += ' WHERE ' + conditions.join(' AND ');
        sql += ' ORDER BY timestamp DESC';
        if (filters?.limit) {
            sql += ' LIMIT ?';
            params.push(filters.limit);
        }
        const rows = this.db.prepare(sql).all(...params);
        return rows.map((r) => ({
            id: r.id,
            key: r.key,
            environment: r.environment,
            sourceType: r.source_type,
            timestamp: r.timestamp,
            operator: r.operator,
            status: r.status,
            message: r.message,
        }));
    }
    async getValidationHistory(filters) {
        await this.init();
        const conditions = [];
        const params = [];
        if (filters?.environment) {
            conditions.push('environment = ?');
            params.push(filters.environment);
        }
        if (filters?.since) {
            conditions.push('timestamp >= ?');
            params.push(filters.since);
        }
        if (filters?.until) {
            conditions.push('timestamp <= ?');
            params.push(filters.until);
        }
        if (filters?.invalidOnly) {
            conditions.push('valid = 0');
        }
        let sql = `
      SELECT environment, valid, error_count, errors_json, timestamp
      FROM validation_history
    `;
        if (conditions.length > 0)
            sql += ' WHERE ' + conditions.join(' AND ');
        sql += ' ORDER BY timestamp DESC';
        if (filters?.limit) {
            sql += ' LIMIT ?';
            params.push(filters.limit);
        }
        const rows = this.db.prepare(sql).all(...params);
        return rows.map((r) => ({
            environment: r.environment,
            valid: r.valid === 1,
            errors: JSON.parse(r.errors_json || '[]'),
            timestamp: r.timestamp,
        }));
    }
    async getDiffHistory(filters) {
        await this.init();
        const conditions = [];
        const params = [];
        if (filters?.environmentA) {
            conditions.push('environment_a = ?');
            params.push(filters.environmentA);
        }
        if (filters?.environmentB) {
            conditions.push('environment_b = ?');
            params.push(filters.environmentB);
        }
        if (filters?.since) {
            conditions.push('timestamp >= ?');
            params.push(filters.since);
        }
        if (filters?.until) {
            conditions.push('timestamp <= ?');
            params.push(filters.until);
        }
        let sql = `
      SELECT environment_a, environment_b, added_count, removed_count, changed_count, diffs_json, timestamp
      FROM diff_history
    `;
        if (conditions.length > 0)
            sql += ' WHERE ' + conditions.join(' AND ');
        sql += ' ORDER BY timestamp DESC';
        if (filters?.limit) {
            sql += ' LIMIT ?';
            params.push(filters.limit);
        }
        const rows = this.db.prepare(sql).all(...params);
        return rows.map((r) => ({
            environmentA: r.environment_a,
            environmentB: r.environment_b,
            diffs: JSON.parse(r.diffs_json || '[]'),
            summary: {
                added: r.added_count,
                removed: r.removed_count,
                changed: r.changed_count,
                total: r.added_count + r.removed_count + r.changed_count,
            },
            timestamp: r.timestamp,
        }));
    }
    async getKeyValueHistory(filters) {
        await this.init();
        const conditions = [];
        const params = [];
        conditions.push('environment = ?');
        params.push(filters.environment);
        if (filters.keyPath) {
            conditions.push('key_path = ?');
            params.push(filters.keyPath);
        }
        if (filters.since) {
            conditions.push('timestamp >= ?');
            params.push(filters.since);
        }
        if (filters.until) {
            conditions.push('timestamp <= ?');
            params.push(filters.until);
        }
        let sql = `
      SELECT environment, key_path, old_value_json, new_value_json, operator, change_type, commit_hash, timestamp
      FROM key_value_history
    `;
        sql += ' WHERE ' + conditions.join(' AND ');
        sql += ' ORDER BY timestamp DESC';
        if (filters.limit) {
            sql += ' LIMIT ?';
            params.push(filters.limit);
        }
        const rows = this.db.prepare(sql).all(...params);
        return rows.map((r) => ({
            environment: r.environment,
            keyPath: r.key_path,
            oldValue: r.old_value_json ? JSON.parse(r.old_value_json) : undefined,
            newValue: r.new_value_json ? JSON.parse(r.new_value_json) : undefined,
            operator: r.operator,
            changeType: r.change_type,
            commitHash: r.commit_hash || undefined,
            timestamp: r.timestamp,
        }));
    }
    close() {
        if (this.db) {
            this.db.close();
            this.db = null;
        }
    }
    getDbPath() {
        return this.dbPath;
    }
}
exports.HistoryStorage = HistoryStorage;
//# sourceMappingURL=HistoryStorage.js.map