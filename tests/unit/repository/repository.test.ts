import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import Database from 'better-sqlite3';
import { DocumentRepository, TagRepository, SearchIndexRepository, SettingsRepository } from '@/main/repositories';
import path from 'path';
import os from 'os';
import fs from 'fs';

describe('Repository 层', () => {
  let db: Database.Database;
  let tempDbPath: string;
  let documentRepository: DocumentRepository;
  let tagRepository: TagRepository;
  let searchIndexRepository: SearchIndexRepository;
  let settingsRepository: SettingsRepository;

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
    `CREATE TABLE IF NOT EXISTS backlinks (
      id TEXT PRIMARY KEY,
      from_doc_id TEXT NOT NULL,
      to_doc_id TEXT NOT NULL,
      anchor_text TEXT NOT NULL,
      line_number INTEGER NOT NULL,
      FOREIGN KEY (from_doc_id) REFERENCES documents(id) ON DELETE CASCADE,
      FOREIGN KEY (to_doc_id) REFERENCES documents(id) ON DELETE CASCADE
    )`,
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

  const testRepoPath = path.join(os.tmpdir(), 'knowledgeforge-test-repo');

  beforeEach(() => {
    tempDbPath = path.join(os.tmpdir(), `test-db-${Date.now()}.db`);
    db = new Database(tempDbPath);
    db.pragma('foreign_keys = ON');

    for (const ddl of DDL_STATEMENTS) {
      db.exec(ddl);
    }

    if (!fs.existsSync(testRepoPath)) {
      fs.mkdirSync(testRepoPath, { recursive: true });
    }

    documentRepository = new DocumentRepository(db, testRepoPath);
    tagRepository = new TagRepository(db);
    searchIndexRepository = new SearchIndexRepository(db);
    settingsRepository = new SettingsRepository(db);
  });

  afterEach(() => {
    db.close();
    if (fs.existsSync(tempDbPath)) {
      fs.unlinkSync(tempDbPath);
    }
  });

  describe('DocumentRepository', () => {
    it('应该插入新文档', () => {
      const testPath = path.join(testRepoPath, 'test-doc.md');
      const content = '# Test Document\n\nThis is a test document.';

      const doc = documentRepository.upsert(testPath, content);

      expect(doc.id).toBeDefined();
      expect(doc.title).toBe('Test Document');
      expect(doc.path).toBe(testPath);
      expect(doc.wordCount).toBe(7);
      expect(doc.tags).toEqual([]);
    });

    it('应该更新现有文档', () => {
      const testPath = path.join(testRepoPath, 'test-doc.md');
      const content1 = '# Test Document\n\nContent v1.';
      const content2 = '# Test Document Updated\n\nContent v2.';

      const doc1 = documentRepository.upsert(testPath, content1);
      const doc2 = documentRepository.upsert(testPath, content2);

      expect(doc1.id).toBe(doc2.id);
      expect(doc2.title).toBe('Test Document Updated');
      expect(doc2.wordCount).toBe(5);
      expect(new Date(doc2.updatedAt).getTime()).toBeGreaterThanOrEqual(new Date(doc1.updatedAt).getTime());
    });

    it('应该正确解析文档中的标签', () => {
      const testPath = path.join(testRepoPath, 'test-doc.md');
      const content = '---\ntags: [typescript, electron]\n---\n\n# Test Document';

      const doc = documentRepository.upsert(testPath, content);

      expect(doc.tags).toEqual(expect.arrayContaining(['typescript', 'electron']));
      expect(doc.tags).toHaveLength(2);
    });

    it('应该通过ID获取文档', () => {
      const testPath = path.join(testRepoPath, 'test-doc.md');
      const content = '# Test Document';

      const inserted = documentRepository.upsert(testPath, content);
      const fetched = documentRepository.getById(inserted.id);

      expect(fetched).not.toBeNull();
      expect(fetched?.id).toBe(inserted.id);
      expect(fetched?.title).toBe('Test Document');
    });

    it('应该通过路径获取文档', () => {
      const testPath = path.join(testRepoPath, 'test-doc.md');
      const content = '# Test Document';

      const inserted = documentRepository.upsert(testPath, content);
      const fetched = documentRepository.getByPath(testPath);

      expect(fetched).not.toBeNull();
      expect(fetched?.id).toBe(inserted.id);
    });

    it('应该通过标题获取文档', () => {
      const testPath = path.join(testRepoPath, 'test-doc.md');
      const content = '# Unique Title';

      documentRepository.upsert(testPath, content);
      const fetched = documentRepository.getByTitle('Unique Title');

      expect(fetched).not.toBeNull();
      expect(fetched?.title).toBe('Unique Title');
    });

    it('应该列出文档', () => {
      for (let i = 0; i < 5; i++) {
        const testPath = path.join(testRepoPath, `doc-${i}.md`);
        const content = `# Document ${i}`;
        documentRepository.upsert(testPath, content);
      }

      const docs = documentRepository.list({ limit: 10 });
      expect(docs).toHaveLength(5);
    });

    it('应该支持分页', () => {
      for (let i = 0; i < 10; i++) {
        const testPath = path.join(testRepoPath, `doc-${i}.md`);
        const content = `# Document ${i}`;
        documentRepository.upsert(testPath, content);
      }

      const page1 = documentRepository.list({ limit: 5, offset: 0 });
      const page2 = documentRepository.list({ limit: 5, offset: 5 });

      expect(page1).toHaveLength(5);
      expect(page2).toHaveLength(5);
      expect(page1[0].id).not.toBe(page2[0].id);
    });

    it('应该按标签过滤文档', () => {
      const path1 = path.join(testRepoPath, 'doc-1.md');
      const content1 = '---\ntags: [javascript]\n---\n# JS Doc';
      documentRepository.upsert(path1, content1);

      const path2 = path.join(testRepoPath, 'doc-2.md');
      const content2 = '---\ntags: [typescript]\n---\n# TS Doc';
      documentRepository.upsert(path2, content2);

      const jsDocs = documentRepository.list({ tag: 'javascript' });
      expect(jsDocs).toHaveLength(1);
      expect(jsDocs[0].title).toBe('JS Doc');
    });

    it('应该更新文档', () => {
      const testPath = path.join(testRepoPath, 'test-doc.md');
      const content = '# Original Title';
      const inserted = documentRepository.upsert(testPath, content);

      const updated = documentRepository.update({ id: inserted.id, title: 'Updated Title' });

      expect(updated.title).toBe('Updated Title');
    });

    it('应该删除文档', () => {
      const testPath = path.join(testRepoPath, 'test-doc.md');
      const content = '# Test Document';
      const inserted = documentRepository.upsert(testPath, content);

      documentRepository.delete(inserted.id);
      const fetched = documentRepository.getById(inserted.id);

      expect(fetched).toBeNull();
    });

    it('应该搜索文档', () => {
      const path1 = path.join(testRepoPath, 'doc-1.md');
      documentRepository.upsert(path1, '# JavaScript Guide');

      const path2 = path.join(testRepoPath, 'doc-2.md');
      documentRepository.upsert(path2, '# Python Tutorial');

      const results = documentRepository.search('JavaScript');
      expect(results.length).toBeGreaterThan(0);
      expect(results[0].title).toContain('JavaScript');
    });

    it('应该统计文档数量', () => {
      for (let i = 0; i < 3; i++) {
        const testPath = path.join(testRepoPath, `doc-${i}.md`);
        documentRepository.upsert(testPath, `# Doc ${i}`);
      }

      expect(documentRepository.count()).toBe(3);
    });

    it('应该统计总字数', () => {
      const path1 = path.join(testRepoPath, 'doc-1.md');
      documentRepository.upsert(path1, '# One two three four five');

      const path2 = path.join(testRepoPath, 'doc-2.md');
      documentRepository.upsert(path2, '# Six seven eight');

      expect(documentRepository.sumWordCount()).toBe(8);
    });
  });

  describe('TagRepository', () => {
    it('应该列出所有标签', () => {
      const testPath = path.join(testRepoPath, 'doc.md');
      const content = '---\ntags: [tag1, tag2, tag3]\n---\n# Test';
      documentRepository.upsert(testPath, content);

      const tags = tagRepository.list();
      expect(tags.length).toBeGreaterThanOrEqual(3);
    });

    it('应该获取热门标签', () => {
      for (let i = 0; i < 5; i++) {
        const testPath = path.join(testRepoPath, `doc-${i}.md`);
        const content = `---\ntags: [common]\n---\n# Doc ${i}`;
        documentRepository.upsert(testPath, content);
      }

      const topTags = tagRepository.getTopTags(3);
      expect(topTags[0].name).toBe('common');
    });

    it('应该插入或更新标签', () => {
      const tag = tagRepository.upsert('test-tag');
      expect(tag.name).toBe('test-tag');
      expect(tag.document_count).toBe(1);

      const tag2 = tagRepository.upsert('test-tag');
      expect(tag2.document_count).toBe(2);
    });
  });

  describe('SearchIndexRepository', () => {
    it('应该保存和加载搜索索引', () => {
      const testData = Buffer.from('test index data');

      searchIndexRepository.save(testData);
      const loaded = searchIndexRepository.load();

      expect(loaded).not.toBeNull();
      expect(loaded?.equals(testData)).toBe(true);
    });

    it('应该获取索引记录', () => {
      const testData = Buffer.from('test data');
      searchIndexRepository.save(testData);

      const record = searchIndexRepository.getRecord();
      expect(record).not.toBeNull();
      expect(record?.id).toBe('main');
      expect(record?.flexsearchIndex).not.toBeNull();
    });

    it('应该获取最后更新时间', () => {
      const before = new Date();
      searchIndexRepository.save(Buffer.from('test'));
      const lastUpdated = searchIndexRepository.getLastUpdated();
      const after = new Date();

      expect(lastUpdated).not.toBeNull();
      expect(lastUpdated?.getTime()).toBeGreaterThanOrEqual(before.getTime());
      expect(lastUpdated?.getTime()).toBeLessThanOrEqual(after.getTime());
    });

    it('应该清除索引', () => {
      searchIndexRepository.save(Buffer.from('test'));
      searchIndexRepository.clear();
      const loaded = searchIndexRepository.load();

      expect(loaded).toBeNull();
    });
  });

  describe('SettingsRepository', () => {
    it('应该保存和读取设置', () => {
      settingsRepository.set('theme', 'dark');
      const value = settingsRepository.get('theme');

      expect(value).toBe('dark');
    });

    it('应该返回null对于不存在的设置', () => {
      const value = settingsRepository.get('nonexistent');
      expect(value).toBeNull();
    });

    it('应该获取所有设置', () => {
      settingsRepository.set('theme', 'dark');
      settingsRepository.set('language', 'zh-CN');

      const all = settingsRepository.getAll();
      expect(all).toEqual({ theme: 'dark', language: 'zh-CN' });
    });

    it('应该检查设置是否存在', () => {
      settingsRepository.set('exists', 'value');

      expect(settingsRepository.has('exists')).toBe(true);
      expect(settingsRepository.has('notexists')).toBe(false);
    });

    it('应该删除设置', () => {
      settingsRepository.set('to-delete', 'value');
      settingsRepository.delete('to-delete');

      expect(settingsRepository.get('to-delete')).toBeNull();
    });
  });

  describe('Repository 协作', () => {
    it('DocumentRepository 应该自动更新 TagRepository', () => {
      const testPath = path.join(testRepoPath, 'doc.md');
      const content = '---\ntags: [auto-tag]\n---\n# Test';

      documentRepository.upsert(testPath, content);

      const tags = tagRepository.list();
      const autoTag = tags.find(t => t.name === 'auto-tag');

      expect(autoTag).toBeDefined();
      expect(autoTag?.document_count).toBe(1);
    });

    it('删除文档时应该级联删除关联数据', () => {
      const testPath = path.join(testRepoPath, 'doc.md');
      const content = '---\ntags: [cascade-test]\n---\n# Test';

      const doc = documentRepository.upsert(testPath, content);
      documentRepository.delete(doc.id);

      const tagDocs = documentRepository.list({ tag: 'cascade-test' });
      expect(tagDocs).toHaveLength(0);
    });
  });
});
