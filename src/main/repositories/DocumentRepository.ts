import { BaseRepository } from './BaseRepository';
import type { Document, Backlink } from '@shared/types';
import { generateDocId, generateHash, parseTitle, parseTags, parseWikiLinks, countWords, sanitizeFilename } from '@shared/utils/markdown';
import { ensureMarkdownExt, isPathSafe } from '@shared/utils/path';
import type Database from 'better-sqlite3';

export interface DocumentListOptions {
  limit?: number;
  offset?: number;
  tag?: string;
  sortBy?: 'updated_at' | 'created_at' | 'title';
  sortOrder?: 'ASC' | 'DESC';
}

export interface DocumentWithTags extends Document {
  tags: string[];
}

export class DocumentRepository extends BaseRepository {
  private repoPath: string;

  constructor(db: Database.Database, repoPath: string) {
    super(db);
    this.repoPath = repoPath;
  }

  private mapDbToDocument(dbDoc: any): Document {
    return {
      id: dbDoc.id,
      filename: dbDoc.filename,
      filePath: dbDoc.path,
      path: dbDoc.path,
      title: dbDoc.title,
      wordCount: dbDoc.word_count,
      hash: dbDoc.hash,
      createdAt: new Date(dbDoc.created_at),
      updatedAt: new Date(dbDoc.updated_at),
      tags: [],
    };
  }

  private validatePath(filePath: string): void {
    if (!isPathSafe(this.repoPath, filePath)) {
      throw new Error('Invalid path: path traversal detected');
    }
  }

  upsert(filePath: string, content: string): DocumentWithTags {
    this.validatePath(filePath);

    const normalizedPath = ensureMarkdownExt(filePath);
    const title = parseTitle(content, normalizedPath.split('/').pop()?.replace('.md', '') || 'Untitled');
    const hash = generateHash(content);
    const tags = parseTags(content);
    const wordCount = countWords(content);

    const existing = this.prepare('SELECT id FROM documents WHERE path = ?')
      .get(normalizedPath) as { id: string } | undefined;
    const docId = existing?.id || generateDocId();

    return this.transaction(() => {
      const now = new Date().toISOString();
      const stmt = this.prepare(`
        INSERT INTO documents (id, path, title, hash, word_count, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(path) DO UPDATE SET
          title = excluded.title,
          hash = excluded.hash,
          word_count = excluded.word_count,
          updated_at = excluded.updated_at
        RETURNING *
      `);

      const doc = stmt.get(docId, normalizedPath, title, hash, wordCount, now);
      const mappedDoc = this.mapDbToDocument(doc);
      this.updateTags(docId, tags);
      this.updateBacklinks(docId, content);

      return { ...mappedDoc, tags };
    });
  }

  getById(id: string): DocumentWithTags | null {
    const doc = this.prepare('SELECT * FROM documents WHERE id = ?').get(id);
    if (!doc) return null;

    const tags = this.prepare(`
      SELECT t.name FROM tags t
      JOIN document_tags dt ON t.id = dt.tag_id
      WHERE dt.document_id = ?
    `).all(id) as { name: string }[];

    const mappedDoc = this.mapDbToDocument(doc);
    return { ...mappedDoc, tags: tags.map(t => t.name) };
  }

  getByPath(filePath: string): DocumentWithTags | null {
    this.validatePath(filePath);
    const doc = this.prepare('SELECT * FROM documents WHERE path = ?')
      .get(filePath);
    if (!doc) return null;

    const tags = this.prepare(`
      SELECT t.name FROM tags t
      JOIN document_tags dt ON t.id = dt.tag_id
      WHERE dt.document_id = ?
    `).all(doc.id) as { name: string }[];

    const mappedDoc = this.mapDbToDocument(doc);
    return { ...mappedDoc, tags: tags.map(t => t.name) };
  }

  getByTitle(title: string): DocumentWithTags | null {
    const doc = this.prepare('SELECT * FROM documents WHERE title = ?')
      .get(title);
    if (!doc) return null;

    const tags = this.prepare(`
      SELECT t.name FROM tags t
      JOIN document_tags dt ON t.id = dt.tag_id
      WHERE dt.document_id = ?
    `).all(doc.id) as { name: string }[];

    const mappedDoc = this.mapDbToDocument(doc);
    return { ...mappedDoc, tags: tags.map(t => t.name) };
  }

  list(options: DocumentListOptions = {}): DocumentWithTags[] {
    const {
      limit = 50,
      offset = 0,
      tag,
      sortBy = 'updated_at',
      sortOrder = 'DESC',
    } = options;

    let sql = `SELECT DISTINCT d.* FROM documents d`;
    const params: any[] = [];

    if (tag) {
      sql += ` JOIN document_tags dt ON d.id = dt.document_id
               JOIN tags t ON dt.tag_id = t.id
               WHERE t.name = ?`;
      params.push(tag);
    }

    sql += ` ORDER BY d.${sortBy} ${sortOrder} LIMIT ? OFFSET ?`;
    params.push(limit, offset);

    const docs = this.prepare(sql).all(...params);

    return docs.map((doc: any) => {
      const tags = this.prepare(`
        SELECT t.name FROM tags t
        JOIN document_tags dt ON t.id = dt.tag_id
        WHERE dt.document_id = ?
      `).all(doc.id) as { name: string }[];

      const mappedDoc = this.mapDbToDocument(doc);
      return { ...mappedDoc, tags: tags.map(t => t.name) };
    });
  }

  update(doc: Partial<Document> & { id: string }): DocumentWithTags {
    const existing = this.getById(doc.id);
    if (!existing) {
      throw new Error(`Document not found: ${doc.id}`);
    }

    const fields = [];
    const params: any[] = [];

    if (doc.title !== undefined) {
      fields.push('title = ?');
      params.push(doc.title);
    }
    if (doc.content !== undefined) {
      fields.push('hash = ?');
      params.push(generateHash(doc.content));
      fields.push('word_count = ?');
      params.push(countWords(doc.content));
    }
    const now = new Date().toISOString();
    fields.push('updated_at = ?');
    params.push(now, doc.id);

    this.prepare(`UPDATE documents SET ${fields.join(', ')} WHERE id = ?`)
      .run(...params);

    return this.getById(doc.id)!;
  }

  delete(id: string): void {
    this.prepare('DELETE FROM documents WHERE id = ?').run(id);
  }

  search(keyword: string): DocumentWithTags[] {
    const likeKeyword = `%${keyword}%`;
    const docs = this.prepare(`
      SELECT * FROM documents
      WHERE title LIKE ? OR id IN (
        SELECT dt.document_id FROM document_tags dt
        JOIN tags t ON dt.tag_id = t.id
        WHERE t.name LIKE ?
      )
      ORDER BY updated_at DESC
      LIMIT 50
    `).all(likeKeyword, likeKeyword);

    return docs.map((doc: any) => {
      const tags = this.prepare(`
        SELECT t.name FROM tags t
        JOIN document_tags dt ON t.id = dt.tag_id
        WHERE dt.document_id = ?
      `).all(doc.id) as { name: string }[];

      const mappedDoc = this.mapDbToDocument(doc);
      return { ...mappedDoc, tags: tags.map(t => t.name) };
    });
  }

  private updateTags(docId: string, tagNames: string[]): void {
    this.prepare('DELETE FROM document_tags WHERE document_id = ?').run(docId);

    for (const name of tagNames) {
      const tagId = `tag-${name.toLowerCase()}`;

      this.prepare(`
        INSERT INTO tags (id, name, document_count)
        VALUES (?, ?, 1)
        ON CONFLICT(name) DO UPDATE SET
          document_count = tags.document_count + 1
      `).run(tagId, name);

      this.prepare(`
        INSERT OR IGNORE INTO document_tags (document_id, tag_id)
        VALUES (?, ?)
      `).run(docId, tagId);
    }

    this.prepare(`
      UPDATE tags SET document_count = (
        SELECT COUNT(*) FROM document_tags dt WHERE dt.tag_id = tags.id
      )
    `).run();
  }

  private updateBacklinks(docId: string, content: string): void {
    this.prepare('DELETE FROM backlinks WHERE from_doc_id = ?').run(docId);

    const links = parseWikiLinks(content);
    const insertStmt = this.prepare(`
      INSERT INTO backlinks (id, from_doc_id, to_doc_id, anchor_text, line_number)
      VALUES (?, ?, ?, ?, ?)
    `);

    for (const link of links) {
      const targetDoc = this.prepare('SELECT id FROM documents WHERE title = ? OR path LIKE ?')
        .get(link.target, `%${sanitizeFilename(link.target)}.md`) as { id: string } | undefined;

      if (targetDoc) {
        insertStmt.run(
          `bl-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          docId,
          targetDoc.id,
          link.anchor,
          link.line
        );
      }
    }
  }

  getBacklinksTo(docId: string): Array<Backlink & { fromDoc: Document }> {
    const rows = this.prepare(`
      SELECT b.*, d.* FROM backlinks b
      JOIN documents d ON b.from_doc_id = d.id
      WHERE b.to_doc_id = ?
      ORDER BY d.updated_at DESC
    `).all(docId) as any[];

    return rows.map(row => ({
      id: row.id,
      fromDocId: row.from_doc_id,
      toDocId: row.to_doc_id,
      anchorText: row.anchor_text,
      lineNumber: row.line_number,
      fromDoc: {
        id: row.from_doc_id,
        filePath: row.path,
        filename: '',
        title: row.title,
        hash: row.hash,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        wordCount: row.word_count,
        tags: [],
        backlinks: [],
        outline: [],
      },
    })) as unknown as Array<Backlink & { fromDoc: Document }>;
  }

  count(): number {
    return (this.prepare('SELECT COUNT(*) as count FROM documents').get() as { count: number }).count;
  }

  sumWordCount(): number {
    return (this.prepare('SELECT COALESCE(SUM(word_count), 0) as sum FROM documents').get() as { sum: number }).sum;
  }

  countEditedAfter(date: Date): number {
    return (this.prepare(
      'SELECT COUNT(*) as count FROM documents WHERE updated_at >= ?'
    ).get(date.toISOString()) as { count: number }).count;
  }

  countEditedBetween(startDate: Date, endDate: Date): number {
    return (this.prepare(
      'SELECT COUNT(*) as count FROM documents WHERE updated_at >= ? AND updated_at <= ?'
    ).get(startDate.toISOString(), endDate.toISOString()) as { count: number }).count;
  }

  countTotalLinks(): number {
    return (this.prepare('SELECT COUNT(*) as count FROM backlinks').get() as { count: number }).count;
  }

  countTotalBacklinks(): number {
    return (this.prepare('SELECT COUNT(DISTINCT to_doc_id) as count FROM backlinks').get() as { count: number }).count;
  }
}
