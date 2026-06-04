import FlexSearch from 'flexsearch';
import type { Document, SearchResult } from '@shared/types';
import { getSearchSnippet, highlightSearch } from '@shared/utils/markdown';
import { DatabaseService } from './DatabaseService';
import { FileService } from './FileService';

interface SearchDoc {
  id: string;
  title: string;
  content: string;
  tags: string[];
  updatedAt: number;
}

export class SearchService {
  private index: FlexSearch.Document<SearchDoc>;
  private db: DatabaseService;
  private fileService: FileService;
  private tokenize: (str: string) => string[];

  constructor(db: DatabaseService, fileService: FileService) {
    this.db = db;
    this.fileService = fileService;
    
    this.tokenize = (str: string): string[] => {
      try {
        const nodejieba = require('nodejieba');
        return nodejieba.cut(str);
      } catch {
        return str.split(/[\s,。，、;；:：]+/).filter(Boolean);
      }
    };

    this.index = new FlexSearch.Document({
      document: {
        id: 'id',
        index: [
          { field: 'title', tokenize: 'forward', boost: 10 },
          { field: 'content', tokenize: 'strict', boost: 1 },
          { field: 'tags', tokenize: 'forward', boost: 5 },
        ],
        tag: 'tags',
      },
      tokenize: this.tokenize,
      charset: 'latin:extra',
      optimize: true,
    });
  }

  async loadFromDB(): Promise<void> {
    const stored = this.db.loadSearchIndex();
    if (stored) {
      try {
        const json = stored.toString('utf-8');
        this.index.import(json as any);
        return;
      } catch (e) {
        console.warn('Failed to load search index, rebuilding:', e);
      }
    }
    await this.reindexAll();
  }

  async reindexAll(): Promise<void> {
    const docs = this.db.listDocuments({ limit: 10000 });
    
    for (const doc of docs) {
      try {
        const content = await this.fileService.readFile(doc.filePath);
        await this.indexDocument(doc, content);
      } catch (e) {
        console.error(`Failed to index ${doc.filePath}:`, e);
      }
    }
    
    await this.persistIndex();
  }

  async indexDocument(doc: Document, content: string): Promise<void> {
    const searchDoc: SearchDoc = {
      id: doc.id,
      title: doc.title,
      content,
      tags: doc.tags,
      updatedAt: new Date(doc.updatedAt).getTime(),
    };
    
    await this.index.add(searchDoc);
  }

  async removeDocument(docId: string): Promise<void> {
    await this.index.remove(docId);
    await this.persistIndex();
  }

  async search(query: string, options?: {
    tags?: string[];
    sortBy?: 'relevance' | 'date';
    limit?: number;
  }): Promise<SearchResult[]> {
    const { tags, sortBy = 'relevance', limit = 50 } = options || {};
    
    const searchOptions: any = {
      limit,
      enrich: true,
    };
    
    if (tags && tags.length > 0) {
      searchOptions.tag = tags;
    }
    
    const results = await this.index.search(query, searchOptions);
    
    const docScores = new Map<string, { score: number; field: string }>();
    
    for (const result of results) {
      for (const item of result.result as any[]) {
        const existing = docScores.get(item.id);
        const score = item.score || 1;
        if (!existing || score > existing.score) {
          docScores.set(item.id, { score, field: result.field });
        }
      }
    }
    
    let docIds = Array.from(docScores.keys());
    
    const docs = await Promise.all(
      docIds.map(id => this.db.getDocument(id))
    );
    
    let validDocs = docs.filter((d): d is Document & { tags: string[] } => d !== null);
    
    if (tags && tags.length > 0) {
      validDocs = validDocs.filter(d => 
        tags.some(t => d.tags.includes(t))
      );
    }
    
    const keywords = this.tokenize(query);
    
    let resultsWithMeta: SearchResult[] = await Promise.all(
      validDocs.map(async (doc) => {
        const scoreInfo = docScores.get(doc.id)!;
        let content = '';
        try {
          content = await this.fileService.readFile(doc.filePath);
        } catch {}
        
        const snippet = getSearchSnippet(content, keywords, 200);
        const highlightedSnippet = highlightSearch(snippet, keywords);
        const highlightedTitle = highlightSearch(doc.title, keywords);
        
        return {
          id: doc.id,
          title: highlightedTitle,
          filePath: doc.filePath,
          tags: doc.tags,
          updatedAt: doc.updatedAt,
          highlights: [highlightedSnippet],
          score: scoreInfo.score,
        };
      })
    );
    
    if (sortBy === 'date') {
      resultsWithMeta.sort((a, b) => 
        new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
      );
    } else {
      resultsWithMeta.sort((a, b) => b.score - a.score);
    }
    
    return resultsWithMeta.slice(0, limit);
  }

  private async persistIndex(): Promise<void> {
    const data = await this.index.export();
    const json = JSON.stringify(data);
    this.db.saveSearchIndex(Buffer.from(json, 'utf-8'));
  }

  async updateIndex(doc: Document, content: string): Promise<void> {
    await this.removeDocument(doc.id);
    await this.indexDocument(doc, content);
    await this.persistIndex();
  }
}
