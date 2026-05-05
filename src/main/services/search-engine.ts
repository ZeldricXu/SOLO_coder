import { Database } from 'better-sqlite3';
import { SearchResult, Note } from '../../shared/types';
import { DatabaseService } from './database';

export interface SearchQuery {
  keyword: string;
  tags?: string[];
  folder_id?: string;
  limit?: number;
}

export class SearchEngineService {
  private static instance: SearchEngineService;
  private dbService: DatabaseService | null = null;

  private constructor() {}

  public static getInstance(): SearchEngineService {
    if (!SearchEngineService.instance) {
      SearchEngineService.instance = new SearchEngineService();
    }
    return SearchEngineService.instance;
  }

  public initialize(dbService: DatabaseService): void {
    this.dbService = dbService;
  }

  private getDb(): Database {
    if (!this.dbService) {
      throw new Error('SearchEngineService not initialized');
    }
    return this.dbService.getDatabase();
  }

  public query(query: SearchQuery): SearchResult[] {
    const db = this.getDb();

    if (!query.keyword || query.keyword.trim() === '') {
      return [];
    }

    const keyword = this.prepareKeyword(query.keyword);
    const params: unknown[] = [keyword];

    let sql = `
      SELECT 
        n.note_id,
        n.title,
        n.content,
        n.updated_at,
        n.folder_id,
        (SELECT GROUP_CONCAT(t.name, ',') FROM note_tags nt JOIN tags t ON nt.tag_id = t.tag_id WHERE nt.note_id = n.note_id) as tags_string,
        fts.rank as score
      FROM notes n
      JOIN notes_fts(?) fts ON n.rowid = fts.rowid
      WHERE n.deleted_at IS NULL
    `;

    if (query.tags && query.tags.length > 0) {
      const tagPlaceholders = query.tags.map(() => '?').join(',');
      sql += `
        AND EXISTS (
          SELECT 1 FROM note_tags nt
          JOIN tags t ON nt.tag_id = t.tag_id
          WHERE nt.note_id = n.note_id AND t.name IN (${tagPlaceholders})
        )
      `;
      params.push(...query.tags);
    }

    if (query.folder_id) {
      sql += ' AND n.folder_id = ?';
      params.push(query.folder_id);
    } else {
      sql += ' AND n.folder_id IS NULL';
    }

    sql += ' ORDER BY fts.rank DESC';

    if (query.limit) {
      sql += ' LIMIT ?';
      params.push(query.limit);
    }

    const rows = db.prepare(sql).all(...params) as Array<{
      note_id: string;
      title: string;
      content: string;
      updated_at: string;
      folder_id: string | null;
      tags_string?: string;
      score: number;
    }>;

    return rows.map(row => this.mapToSearchResult(row, keyword));
  }

  private prepareKeyword(keyword: string): string {
    const trimmed = keyword.trim();
    const words = trimmed.split(/\s+/).filter(w => w.length > 0);
    
    if (words.length === 0) {
      return '';
    }

    const ftsKeywords = words.map(w => {
      const escaped = w.replace(/"/g, '""');
      return `"${escaped}"`;
    });

    return ftsKeywords.join(' AND ');
  }

  private mapToSearchResult(row: {
    note_id: string;
    title: string;
    content: string;
    updated_at: string;
    tags_string?: string;
    score: number;
  }, keyword: string): SearchResult {
    const tags = row.tags_string ? row.tags_string.split(',').filter(t => t.trim()) : [];

    return {
      note_id: row.note_id,
      title: row.title,
      preview: this.generatePreview(row.content, keyword),
      score: this.calculateScore(row.score, row.title, keyword),
      tags,
      updated_at: row.updated_at,
    };
  }

  private generatePreview(content: string, keyword: string): string {
    const plainContent = this.stripMarkdown(content);
    const normalizedKeyword = keyword.replace(/"/g, '').toLowerCase();
    const keywords = normalizedKeyword.split(/\s+and\s+/i).map(k => k.trim());

    let bestPosition = -1;
    let bestContextLength = 0;

    for (const kw of keywords) {
      const pos = plainContent.toLowerCase().indexOf(kw);
      if (pos !== -1 && (bestPosition === -1 || pos < bestPosition)) {
        bestPosition = pos;
        bestContextLength = kw.length;
      }
    }

    if (bestPosition === -1) {
      return plainContent.substring(0, 150) + (plainContent.length > 150 ? '...' : '');
    }

    const contextWindow = 60;
    const start = Math.max(0, bestPosition - contextWindow);
    const end = Math.min(plainContent.length, bestPosition + bestContextLength + contextWindow);

    let preview = plainContent.substring(start, end);

    if (start > 0) {
      preview = '...' + preview;
    }
    if (end < plainContent.length) {
      preview = preview + '...';
    }

    return preview;
  }

  private stripMarkdown(content: string): string {
    return content
      .replace(/^#{1,6}\s+/gm, '')
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/\*([^*]+)\*/g, '$1')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      .replace(/!\[[^\]]*\]\([^)]+\)/g, '')
      .replace(/^[-*+]\s+/gm, '')
      .replace(/^\d+\.\s+/gm, '')
      .replace(/^>\s+/gm, '')
      .replace(/^\s*[-=]{3,}\s*$/gm, '')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
  }

  private calculateScore(ftsScore: number, title: string, keyword: string): number {
    let score = -ftsScore;

    const normalizedKeyword = keyword.replace(/"/g, '').toLowerCase();
    const keywords = normalizedKeyword.split(/\s+and\s+/i);

    for (const kw of keywords) {
      if (title.toLowerCase().includes(kw.toLowerCase())) {
        score += 10;
      }
    }

    return score;
  }

  public rebuildIndex(): void {
    if (!this.dbService) {
      throw new Error('SearchEngineService not initialized');
    }
    this.dbService.rebuildFTSSearchIndex();
  }

  public searchNotesByKeyword(keyword: string, limit: number = 20): SearchResult[] {
    return this.query({
      keyword,
      limit,
    });
  }

  public searchNotesWithFilters(
    keyword: string,
    filters: { tags?: string[]; folder_id?: string }
  ): SearchResult[] {
    return this.query({
      keyword,
      tags: filters.tags,
      folder_id: filters.folder_id,
    });
  }
}
