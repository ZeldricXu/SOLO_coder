import { BaseRepository } from './BaseRepository';
import type Database from 'better-sqlite3';

export class SettingsRepository extends BaseRepository {
  constructor(db: Database.Database) {
    super(db);
  }

  get(key: string): string | null {
    const row = this.prepare('SELECT value FROM settings WHERE key = ?')
      .get(key) as { value: string } | undefined;
    return row?.value || null;
  }

  set(key: string, value: string): void {
    this.prepare(`
      INSERT INTO settings (key, value)
      VALUES (?, ?)
      ON CONFLICT(key) DO UPDATE SET value = excluded.value
    `).run(key, value);
  }

  delete(key: string): void {
    this.prepare('DELETE FROM settings WHERE key = ?').run(key);
  }

  getAll(): Record<string, string> {
    const rows = this.prepare('SELECT key, value FROM settings').all() as { key: string; value: string }[];
    const result: Record<string, string> = {};
    for (const row of rows) {
      result[row.key] = row.value;
    }
    return result;
  }

  has(key: string): boolean {
    const row = this.prepare('SELECT 1 FROM settings WHERE key = ?')
      .get(key) as { '1': number } | undefined;
    return !!row;
  }
}
