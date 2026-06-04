import { BaseRepository } from './BaseRepository';
import type { Tag } from '@shared/types';
import type Database from 'better-sqlite3';

export class TagRepository extends BaseRepository {
  constructor(db: Database.Database) {
    super(db);
  }

  list(): Tag[] {
    return this.prepare('SELECT * FROM tags ORDER BY document_count DESC').all() as Tag[];
  }

  getById(tagId: string): Tag | null {
    return this.prepare('SELECT * FROM tags WHERE id = ?').get(tagId) as Tag | undefined || null;
  }

  getByName(name: string): Tag | null {
    return this.prepare('SELECT * FROM tags WHERE name = ?').get(name) as Tag | undefined || null;
  }

  getTopTags(limit: number = 15): Tag[] {
    return this.prepare(
      'SELECT * FROM tags ORDER BY document_count DESC LIMIT ?'
    ).all(limit) as Tag[];
  }

  upsert(name: string): Tag {
    const tagId = `tag-${name.toLowerCase()}`;
    this.prepare(`
      INSERT INTO tags (id, name, document_count)
      VALUES (?, ?, 1)
      ON CONFLICT(name) DO UPDATE SET
        document_count = tags.document_count + 1
    `).run(tagId, name);

    return this.getById(tagId)!;
  }

  delete(tagId: string): void {
    this.prepare('DELETE FROM tags WHERE id = ?').run(tagId);
  }

  count(): number {
    return (this.prepare('SELECT COUNT(*) as count FROM tags').get() as { count: number }).count;
  }

  addDocumentTag(documentId: string, tagName: string): void {
    const tag = this.upsert(tagName);
    this.prepare(`
      INSERT OR IGNORE INTO document_tags (document_id, tag_id)
      VALUES (?, ?)
    `).run(documentId, tag.id);
  }

  removeDocumentTag(documentId: string, tagId: string): void {
    this.prepare('DELETE FROM document_tags WHERE document_id = ? AND tag_id = ?')
      .run(documentId, tagId);
  }

  getDocumentTags(documentId: string): string[] {
    const rows = this.prepare(`
      SELECT t.name FROM tags t
      JOIN document_tags dt ON t.id = dt.tag_id
      WHERE dt.document_id = ?
    `).all(documentId) as { name: string }[];

    return rows.map(r => r.name);
  }

  recalculateDocumentCounts(): void {
    this.prepare(`
      UPDATE tags SET document_count = (
        SELECT COUNT(*) FROM document_tags dt WHERE dt.tag_id = tags.id
      )
    `).run();
  }
}
