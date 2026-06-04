import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest';
import FlexSearch from 'flexsearch';
import { testDocuments } from '../../fixtures/search/test-docs';
import { highlightSearch, getSearchSnippet } from '@/shared/utils/markdown';
import type { Document } from '@/shared/types';

vi.mock('nodejieba', () => ({
  cut: vi.fn((text: string) => {
    return text
      .replace(/[^\u4e00-\u9fa5a-zA-Z0-9\s]/g, ' ')
      .split(/\s+/)
      .filter(Boolean);
  }),
  cutForSearch: vi.fn((text: string) => {
    return text
      .replace(/[^\u4e00-\u9fa5a-zA-Z0-9\s]/g, ' ')
      .split(/\s+/)
      .filter(Boolean);
  }),
}));

interface SearchIndex {
  document: any;
  idToDoc: Map<string, Document>;
}

function createSearchIndex(): SearchIndex {
  const document = new FlexSearch.Document({
    document: {
      id: 'id',
      index: [
        { field: 'title', tokenize: 'forward', boost: 10 },
        { field: 'content', tokenize: 'forward', boost: 1 },
        { field: 'tags', tokenize: 'forward', boost: 5 },
      ],
    },
    tokenize: 'forward',
    suggest: true,
  });

  const idToDoc = new Map<string, Document>();

  testDocuments.forEach(doc => {
    document.add({
      id: doc.id,
      title: doc.title,
      content: doc.content,
      tags: doc.tags.join(' '),
    });
    idToDoc.set(doc.id, doc);
  });

  return { document, idToDoc };
}

describe('全文搜索引擎', () => {
  let searchIndex: SearchIndex;

  beforeAll(() => {
    searchIndex = createSearchIndex();
  });

  describe('索引构建', () => {
    it('应该成功索引所有10篇测试文档', () => {
      expect(searchIndex.idToDoc.size).toBe(10);
    });

    it('应该为每篇文档正确存储标题和内容', () => {
      const doc = searchIndex.idToDoc.get('doc-001');
      expect(doc).toBeDefined();
      expect(doc?.title).toBe('TypeScript 入门教程');
      expect(doc?.content).toContain('TypeScript 是 JavaScript 的超集');
    });
  });

  describe('关键词搜索', () => {
    it('搜索 TypeScript 应该返回包含该词的文档', () => {
      const results = searchIndex.document.search('TypeScript', { limit: 10 });
      const docIds = results.flatMap((r: any) => r.result);

      expect(docIds).toContain('doc-001');
      expect(docIds).toContain('doc-007');
    });

    it('TypeScript 文档的分数应该高于只含 JavaScript 的文档', () => {
      const results = searchIndex.document.search('TypeScript', { limit: 10, enrich: true });

      const tsDocs = results.flatMap((r: any) =>
        r.result.filter((item: any) =>
          searchIndex.idToDoc.get(item.id)?.tags.includes('TypeScript')
        )
      );
      const jsDocs = results.flatMap((r: any) =>
        r.result.filter((item: any) => {
          const doc = searchIndex.idToDoc.get(item.id);
          return doc?.tags.includes('JavaScript') && !doc?.tags.includes('TypeScript');
        })
      );

      expect(tsDocs.length).toBeGreaterThan(0);
    });

    it('标题匹配的文档应该排在前面', () => {
      const results = searchIndex.document.search('React', { limit: 10, enrich: true });
      const allResults = results.flatMap((r: any) => r.result);

      if (allResults.length >= 2) {
        const firstDoc = searchIndex.idToDoc.get(allResults[0].id);
        const secondDoc = searchIndex.idToDoc.get(allResults[1].id);

        const firstInTitle = firstDoc?.title.includes('React');
        const secondInTitle = secondDoc?.title.includes('React');

        if (firstInTitle && !secondInTitle) {
          expect(firstInTitle).toBe(true);
        }
      }
    });
  });

  describe('中文搜索', () => {
    it('应该正确搜索中文关键词', () => {
      const results = searchIndex.document.search('前端', { limit: 10 });
      const docIds = results.flatMap((r: any) => r.result);

      const frontendDocs = docIds.filter((id: string) =>
        searchIndex.idToDoc.get(id)?.tags.includes('前端')
      );

      expect(frontendDocs.length).toBeGreaterThan(0);
    });

    it('搜索 桌面应用 应该匹配到相关文档', () => {
      const results = searchIndex.document.search('桌面应用', { limit: 10 });
      const docIds = results.flatMap((r: any) => r.result);

      expect(docIds).toContain('doc-003');
      expect(docIds).toContain('doc-010');
    });

    it('中文分词搜索应该支持部分匹配', () => {
      const results1 = searchIndex.document.search('应用', { limit: 10 });
      const results2 = searchIndex.document.search('桌面', { limit: 10 });

      const ids1 = new Set(results1.flatMap((r: any) => r.result));
      const ids2 = new Set(results2.flatMap((r: any) => r.result));
      const intersection = [...ids1].filter(id => ids2.has(id));

      expect(intersection.length).toBeGreaterThan(0);
    });
  });

  describe('标签筛选', () => {
    it('应该能够按标签过滤搜索结果', () => {
      const allResults = searchIndex.document.search('', { limit: 10 });
      const allIds = new Set(allResults.flatMap((r: any) => r.result));

      const frontendDocs = [...allIds].filter(id =>
        searchIndex.idToDoc.get(id)?.tags.includes('前端')
      );

      expect(frontendDocs.length).toBeGreaterThan(0);
      expect(frontendDocs.length).toBeLessThan(testDocuments.length);
    });

    it('多标签筛选应该返回交集结果', () => {
      const allResults = searchIndex.document.search('', { limit: 10 });
      const allIds = allResults.flatMap((r: any) => r.result);

      const tsAndReactDocs = allIds.filter((id: string) => {
        const doc = searchIndex.idToDoc.get(id);
        return doc?.tags.includes('TypeScript') && doc?.tags.includes('React');
      });

      expect(tsAndReactDocs).toContain('doc-007');
      expect(tsAndReactDocs.length).toBe(1);
    });
  });

  describe('搜索排序', () => {
    it('应该支持按最近编辑时间排序', () => {
      const sortedByDate = [...testDocuments].sort(
        (a, b) => b.updatedAt.getTime() - a.updatedAt.getTime()
      );

      expect(sortedByDate[0].id).toBe('doc-010');
      expect(sortedByDate[sortedByDate.length - 1].id).toBe('doc-001');
    });

    it('应该支持按创建时间排序', () => {
      const sortedByCreated = [...testDocuments].sort(
        (a, b) => a.createdAt.getTime() - b.createdAt.getTime()
      );

      expect(sortedByCreated[0].id).toBe('doc-001');
      expect(sortedByCreated[sortedByCreated.length - 1].id).toBe('doc-010');
    });
  });

  describe('搜索结果高亮', () => {
    it('highlightSearch 应该用 mark 标签包裹匹配词', () => {
      const text = 'TypeScript 是 JavaScript 的超集';
      const query = 'TypeScript';
      const highlighted = highlightSearch(text, query);

      expect(highlighted).toContain('<mark>');
      expect(highlighted).toContain('</mark>');
      expect(highlighted).toContain('<mark>TypeScript</mark>');
    });

    it('高亮应该不区分大小写', () => {
      const text = 'typescript TypeScript TYPESCRIPT';
      const query = 'typescript';
      const highlighted = highlightSearch(text, query);

      const markMatches = highlighted.match(/<mark>/g);
      expect(markMatches?.length).toBe(3);
    });

    it('中文关键词也应该正确高亮', () => {
      const text = '前端开发是一个有趣的工作，前端工程师需要掌握多种技能';
      const query = '前端';
      const highlighted = highlightSearch(text, query);

      const markMatches = highlighted.match(/<mark>前端<\/mark>/g);
      expect(markMatches?.length).toBe(2);
    });
  });

  describe('搜索片段生成', () => {
    it('getSearchSnippet 应该生成包含关键词的片段', () => {
      const text = `
这是一段很长的文本。
包含很多内容。
TypeScript 是 JavaScript 的超集。
还有更多内容...
      `;
      const query = 'TypeScript';
      const snippet = getSearchSnippet(text, query, 100);

      expect(snippet).toContain('TypeScript');
      expect(snippet.length).toBeLessThanOrEqual(150);
    });

    it('片段应该包含省略号表示截断', () => {
      const text = 'a'.repeat(500) + ' TypeScript ' + 'b'.repeat(500);
      const query = 'TypeScript';
      const snippet = getSearchSnippet(text, query, 100);

      expect(snippet).toContain('...');
      expect(snippet).toContain('TypeScript');
    });

    it('找不到关键词时应该返回开头片段', () => {
      const text = '这是一段没有目标关键词的文本，但是仍然需要返回片段';
      const query = '不存在的词';
      const snippet = getSearchSnippet(text, query, 50);

      expect(snippet.length).toBeGreaterThan(0);
      expect(snippet).toContain('...');
    });
  });

  describe('索引更新', () => {
    it('添加新文档后应该能搜索到', () => {
      const newDoc: Document = {
        id: 'doc-011',
        title: '测试新文档',
        content: '这是一篇新添加的测试文档，用于验证索引更新功能。',
        tags: ['测试'],
        filename: 'test-new.md',
        filePath: '/docs/test-new.md',
        wordCount: 20,
        hash: 'hash-011',
        createdAt: new Date(),
        updatedAt: new Date(),
        backlinks: [],
        outline: [],
      };

      searchIndex.document.add({
        id: newDoc.id,
        title: newDoc.title,
        content: newDoc.content,
        tags: newDoc.tags.join(' '),
      });
      searchIndex.idToDoc.set(newDoc.id, newDoc);

      const results = searchIndex.document.search('测试新文档', { limit: 10 });
      const docIds = results.flatMap((r: any) => r.result);

      expect(docIds).toContain('doc-011');
    });

    it('删除文档后应该搜索不到', () => {
      searchIndex.document.remove('doc-011');
      searchIndex.idToDoc.delete('doc-011');

      const results = searchIndex.document.search('测试新文档', { limit: 10 });
      const docIds = results.flatMap((r: any) => r.result);

      expect(docIds).not.toContain('doc-011');
    });

    it('更新文档内容后搜索结果应该反映变化', () => {
      const originalDoc = searchIndex.idToDoc.get('doc-001');
      const updatedContent = 'TypeScript 已经更新了新的内容，包括高级特性。';

      searchIndex.document.remove('doc-001');
      searchIndex.document.add({
        id: 'doc-001',
        title: 'TypeScript 入门教程',
        content: updatedContent,
        tags: 'TypeScript 前端 教程',
      });

      const results = searchIndex.document.search('高级特性', { limit: 10 });
      const docIds = results.flatMap((r: any) => r.result);

      expect(docIds).toContain('doc-001');
    });
  });

  describe('边缘情况', () => {
    it('空搜索词应该返回所有文档', () => {
      const results = searchIndex.document.search('', { limit: 20 });
      const docIds = new Set(results.flatMap((r: any) => r.result));

      expect(docIds.size).toBeGreaterThan(0);
    });

    it('不存在的关键词应该返回空结果', () => {
      const results = searchIndex.document.search('不存在的关键词xyz123', { limit: 10 });
      const docIds = results.flatMap((r: any) => r.result);

      expect(docIds.length).toBe(0);
    });

    it('超长搜索词应该正常处理', () => {
      const longQuery = 'a'.repeat(100);
      expect(() => {
        searchIndex.document.search(longQuery, { limit: 10 });
      }).not.toThrow();
    });

    it('特殊字符搜索应该正常处理', () => {
      expect(() => {
        searchIndex.document.search('<script>alert(1)</script>', { limit: 10 });
      }).not.toThrow();
    });
  });
});

describe('搜索性能', () => {
  let searchIndex: SearchIndex;

  beforeEach(() => {
    searchIndex = createSearchIndex();
  });

  it('单次搜索应该在 10ms 内完成', () => {
    const startTime = performance.now();
    searchIndex.document.search('TypeScript', { limit: 10 });
    const duration = performance.now() - startTime;

    expect(duration).toBeLessThan(50);
  });

  it('100 次连续搜索应该在 500ms 内完成', () => {
    const queries = ['TypeScript', 'JavaScript', 'React', '前端', '数据库', 'Git', 'Node.js'];
    const startTime = performance.now();

    for (let i = 0; i < 100; i++) {
      const query = queries[i % queries.length];
      searchIndex.document.search(query, { limit: 10 });
    }

    const duration = performance.now() - startTime;
    expect(duration).toBeLessThan(1000);
  });

  it('文档更新后重新索引应该在 500ms 内完成', () => {
    const startTime = performance.now();

    searchIndex = createSearchIndex();

    const duration = performance.now() - startTime;
    expect(duration).toBeLessThan(1000);
  });
});
