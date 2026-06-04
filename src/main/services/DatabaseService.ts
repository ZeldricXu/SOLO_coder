import Database from 'better-sqlite3';
import type { Document as DocumentType, Tag, Backlink, AppStats } from '@shared/types';
import { isPathSafe, ensureMarkdownExt } from '@shared/utils/path';
import { sanitizeFilename } from '@shared/utils/markdown';
import {
  DocumentRepository,
  TagRepository,
  SearchIndexRepository,
  SettingsRepository,
} from '../repositories';

const DDL_STATEMENTS = [
  `CREATE TABLE IF NOT EXISTS documents (
    id TEXT PRIMARY KEY,
    path TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    hash TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    word_count INTEGER DEFAULT 0
  )`,
  `CREATE INDEX IF NOT EXISTS idx_documents_updated_at ON documents(updated_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_documents_title ON documents(title)`,
  
  `CREATE TABLE IF NOT EXISTS tags (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    document_count INTEGER DEFAULT 0
  )`,
  
  `CREATE TABLE IF NOT EXISTS document_tags (
    document_id TEXT NOT NULL,
    tag_id TEXT NOT NULL,
    PRIMARY KEY (document_id, tag_id),
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
  )`,
  `CREATE INDEX IF NOT EXISTS idx_document_tags_tag_id ON document_tags(tag_id)`,
  
  `CREATE TABLE IF NOT EXISTS backlinks (
    id TEXT PRIMARY KEY,
    from_doc_id TEXT NOT NULL,
    to_doc_id TEXT NOT NULL,
    anchor_text TEXT NOT NULL,
    line_number INTEGER NOT NULL,
    FOREIGN KEY (from_doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (to_doc_id) REFERENCES documents(id) ON DELETE CASCADE
  )`,
  `CREATE INDEX IF NOT EXISTS idx_backlinks_to_doc ON backlinks(to_doc_id)`,
  `CREATE INDEX IF NOT EXISTS idx_backlinks_from_doc ON backlinks(from_doc_id)`,
  
  `CREATE TABLE IF NOT EXISTS search_index (
    id TEXT PRIMARY KEY DEFAULT 'main',
    flexsearch_index BLOB,
    last_updated DATETIME DEFAULT CURRENT_TIMESTAMP
  )`,
  
  `CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT
  )`,
];

export class DatabaseService {
  private db: Database.Database;
  private repoPath: string;
  private documentRepository: DocumentRepository;
  private tagRepository: TagRepository;
  private searchIndexRepository: SearchIndexRepository;
  private settingsRepository: SettingsRepository;

  constructor(dbPath: string, repoPath: string) {
    this.repoPath = repoPath;
    this.db = new Database(dbPath);
    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.initTables();
    
    this.documentRepository = new DocumentRepository(this.db, repoPath);
    this.tagRepository = new TagRepository(this.db);
    this.searchIndexRepository = new SearchIndexRepository(this.db);
    this.settingsRepository = new SettingsRepository(this.db);
  }

  private initTables(): void {
    const tx = this.db.transaction(() => {
      for (const ddl of DDL_STATEMENTS) {
        this.db.exec(ddl);
      }
    });
    tx();
  }

  private validatePath(filePath: string): void {
    if (!isPathSafe(this.repoPath, filePath)) {
      throw new Error('Invalid path: path traversal detected');
    }
  }

  upsertDocument(filePath: string, content: string): DocumentType {
    return this.documentRepository.upsert(filePath, content) as unknown as DocumentType;
  }

  getDocument(id: string): (DocumentType & { tags: string[] }) | null {
    return this.documentRepository.getById(id) as unknown as (DocumentType & { tags: string[] }) | null;
  }

  getDocumentByPath(filePath: string): (DocumentType & { tags: string[] }) | null {
    return this.documentRepository.getByPath(filePath) as unknown as (DocumentType & { tags: string[] }) | null;
  }

  listDocuments(options?: {
    limit?: number;
    offset?: number;
    tag?: string;
    sortBy?: 'updated_at' | 'created_at' | 'title';
    sortOrder?: 'ASC' | 'DESC';
  }): (DocumentType & { tags: string[] })[] {
    return this.documentRepository.list(options) as unknown as (DocumentType & { tags: string[] })[];
  }

  deleteDocument(id: string): void {
    this.documentRepository.delete(id);
  }

  searchDocuments(keyword: string): (DocumentType & { tags: string[] })[] {
    return this.documentRepository.search(keyword) as unknown as (DocumentType & { tags: string[] })[];
  }

  listTags(): Tag[] {
    return this.tagRepository.list();
  }

  getDocumentsByTag(tagName: string): (DocumentType & { tags: string[] })[] {
    return this.documentRepository.list({ tag: tagName }) as unknown as (DocumentType & { tags: string[] })[];
  }

  getBacklinksTo(docId: string): Array<Backlink & { fromDoc: DocumentType }> {
    return this.documentRepository.getBacklinksTo(docId) as unknown as Array<Backlink & { fromDoc: DocumentType }>;
  }

  getStats(): AppStats {
    const totalDocs = this.documentRepository.count();
    const totalWords = this.documentRepository.sumWordCount();
    const totalTags = this.tagRepository.count();
    
    const todayStart = new Date();
    todayStart.setHours(0, 0, 0, 0);
    const todayEdited = this.documentRepository.countEditedAfter(todayStart);
    
    const last7Days: number[] = [];
    for (let i = 6; i >= 0; i--) {
      const dayStart = new Date();
      dayStart.setDate(dayStart.getDate() - i);
      dayStart.setHours(0, 0, 0, 0);
      const dayEnd = new Date(dayStart);
      dayEnd.setHours(23, 59, 59, 999);
      
      const count = this.documentRepository.countEditedBetween(dayStart, dayEnd);
      last7Days.push(count);
    }
    
    const recentDocuments = this.documentRepository.list({ limit: 10 });
    const topTags = this.tagRepository.getTopTags(15);
    
    const totalLinks = this.documentRepository.countTotalLinks();
    const totalBacklinks = this.documentRepository.countTotalBacklinks();

    return {
      totalDocuments: totalDocs,
      totalWords,
      totalTags,
      totalLinks,
      totalBacklinks,
      todayEdited,
      last7DaysActivity: last7Days,
      recentDocuments,
      topTags,
    } as unknown as AppStats;
  }

  getSetting(key: string): string | null {
    return this.settingsRepository.get(key);
  }

  setSetting(key: string, value: string): void {
    this.settingsRepository.set(key, value);
  }

  saveSearchIndex(indexData: Buffer): void {
    this.searchIndexRepository.save(indexData);
  }

  loadSearchIndex(): Buffer | null {
    return this.searchIndexRepository.load();
  }

  close(): void {
    this.db.close();
  }
}
