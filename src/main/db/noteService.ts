import { getDatabase } from './index';
import type { Note, NoteLink } from '../../shared/types';
import { randomUUID } from 'crypto';

export const NoteService = {
  getAll(): Note[] {
    const db = getDatabase();
    const rows = db.prepare('SELECT * FROM notes ORDER BY updated_at DESC').all();
    return rows.map(row => mapRowToNote(row));
  },

  getById(id: string): Note | null {
    const db = getDatabase();
    const row = db.prepare('SELECT * FROM notes WHERE id = ?').get(id);
    return row ? mapRowToNote(row) : null;
  },

  getByPath(path: string): Note | null {
    const db = getDatabase();
    const row = db.prepare('SELECT * FROM notes WHERE path = ?').get(path);
    return row ? mapRowToNote(row) : null;
  },

  create(note: Partial<Note> & { content: string; path: string }): Note {
    const db = getDatabase();
    const id = note.id || randomUUID();
    const now = Date.now();
    const title = note.title || extractTitle(note.content) || 'Untitled';
    const tags = JSON.stringify(note.tags || []);
    const frontmatter = JSON.stringify(note.frontmatter || {});
    
    const stmt = db.prepare(`
      INSERT INTO notes (id, title, path, content, tags, frontmatter, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `);
    stmt.run(id, title, note.path, note.content, tags, frontmatter, now, now);
    
    return this.getById(id)!;
  },

  update(id: string, updates: Partial<Note>): Note | null {
    const db = getDatabase();
    const existing = this.getById(id);
    if (!existing) return null;
    
    const fields: string[] = [];
    const values: any[] = [];
    
    if (updates.title !== undefined) {
      fields.push('title = ?');
      values.push(updates.title);
    }
    if (updates.path !== undefined) {
      fields.push('path = ?');
      values.push(updates.path);
    }
    if (updates.content !== undefined) {
      fields.push('content = ?');
      values.push(updates.content);
    }
    if (updates.tags !== undefined) {
      fields.push('tags = ?');
      values.push(JSON.stringify(updates.tags));
    }
    if (updates.frontmatter !== undefined) {
      fields.push('frontmatter = ?');
      values.push(JSON.stringify(updates.frontmatter));
    }
    
    if (fields.length === 0) return existing;
    
    fields.push('updated_at = ?');
    values.push(Date.now());
    values.push(id);
    
    const stmt = db.prepare(`UPDATE notes SET ${fields.join(', ')} WHERE id = ?`);
    stmt.run(...values);
    
    return this.getById(id);
  },

  delete(id: string): boolean {
    const db = getDatabase();
    const result = db.prepare('DELETE FROM notes WHERE id = ?').run(id);
    return result.changes > 0;
  },

  deleteByPath(path: string): boolean {
    const db = getDatabase();
    const result = db.prepare('DELETE FROM notes WHERE path = ?').run(path);
    return result.changes > 0;
  },

  saveContent(id: string, content: string): boolean {
    const db = getDatabase();
    const title = extractTitle(content);
    const result = db.prepare(
      'UPDATE notes SET content = ?, title = ?, updated_at = ? WHERE id = ?'
    ).run(content, title, Date.now(), id);
    return result.changes > 0;
  },
};

function mapRowToNote(row: any): Note {
  return {
    id: row.id,
    title: row.title,
    path: row.path,
    content: row.content || '',
    tags: JSON.parse(row.tags || '[]'),
    frontmatter: JSON.parse(row.frontmatter || '{}'),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

function extractTitle(content: string): string {
  const match = content.match(/^#\s+(.+)$/m);
  if (match) return match[1].trim();
  const firstLine = content.split('\n').find(line => line.trim().length > 0);
  return firstLine ? firstLine.trim().slice(0, 100) : 'Untitled';
}
