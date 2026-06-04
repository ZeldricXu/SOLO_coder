import { BaseRepository } from './BaseRepository';
import type Database from 'better-sqlite3';

export interface SearchIndexRecord {
  id: string;
  flexsearchIndex: Buffer | null;
  lastUpdated: Date;
}

export class SearchIndexRepository extends BaseRepository {
  constructor(db: Database.Database) {
    super(db);
  }

  save(indexData: Buffer): void {
    const now = new Date().toISOString();
    this.prepare(`
      INSERT INTO search_index (id, flexsearch_index, last_updated)
      VALUES ('main', ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        flexsearch_index = excluded.flexsearch_index,
        last_updated = excluded.last_updated
    `).run(indexData, now);
  }

  load(): Buffer | null {
    const row = this.prepare('SELECT flexsearch_index FROM search_index WHERE id = ?')
      .get('main') as { flexsearch_index: Buffer } | undefined;
    return row?.flexsearch_index || null;
  }

  getRecord(): SearchIndexRecord | null {
    const row = this.prepare('SELECT * FROM search_index WHERE id = ?')
      .get('main') as any | undefined;
    if (!row) return null;

    return {
      id: row.id,
      flexsearchIndex: row.flexsearch_index,
      lastUpdated: new Date(row.last_updated),
    };
  }

  getLastUpdated(): Date | null {
    const row = this.prepare('SELECT last_updated FROM search_index WHERE id = ?')
      .get('main') as { last_updated: string } | undefined;
    return row ? new Date(row.last_updated) : null;
  }

  clear(): void {
    this.prepare('DELETE FROM search_index WHERE id = ?').run('main');
  }

  touch(): void {
    const now = new Date().toISOString();
    this.prepare(`
      UPDATE search_index SET last_updated = ? WHERE id = 'main'
    `).run(now);
  }
}
