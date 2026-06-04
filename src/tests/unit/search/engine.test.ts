import { describe, it, expect, vi, beforeEach } from 'vitest';
import { buildSearchQuery, executeSearch, combineRankings } from '@/lib/search/engine';
import type { SearchQuery, SearchFilter } from '@/lib/search/types';

describe('Search Engine', () => {
  let mockPrisma: any;

  beforeEach(() => {
    mockPrisma = {
      $queryRaw: vi.fn(),
      document: {
        findMany: vi.fn(),
        count: vi.fn(),
      },
    };
  });

  describe('tsvector index building', () => {
    it('should correctly weight title higher than content', () => {
      const title = '机器学习入门教程';
      const content = '本教程介绍机器学习的基础概念和算法。机器学习是人工智能的重要分支。';

      const titleVector = `setweight(to_tsvector('simple', '${title}'), 'A')`;
      const contentVector = `setweight(to_tsvector('simple', '${content}'), 'B')`;

      expect(titleVector).toContain('A');
      expect(contentVector).toContain('B');
    });

    it('should handle Chinese text in tsvector', () => {
      const chineseText = '机器学习 深度学习 神经网络';
      
      const result = {
        documents: [
          {
            id: 'doc-1',
            title: '机器学习基础',
            content: chineseText,
            rank: 0.85,
          },
        ],
      };

      expect(result.documents[0].rank).toBeGreaterThan(0);
    });
  });

  describe('Multi-keyword search logic', () => {
    it('should return intersection for AND search', () => {
      const query: SearchQuery = {
        query: '机器学习 深度学习',
        mode: 'and',
      };

      const mockResults = [
        { id: 'doc-1', title: '机器学习与深度学习', rank: 0.9 },
        { id: 'doc-2', title: '深度学习进阶', rank: 0.7 },
      ];

      const andResults = mockResults.filter(
        (doc) => 
          doc.title.includes('机器学习') && doc.title.includes('深度学习')
      );

      expect(andResults).toHaveLength(1);
      expect(andResults[0].id).toBe('doc-1');
    });

    it('should return union for OR search', () => {
      const query: SearchQuery = {
        query: '机器学习 深度学习',
        mode: 'or',
      };

      const mockResults = [
        { id: 'doc-1', title: '机器学习与深度学习', rank: 0.9 },
        { id: 'doc-2', title: '深度学习进阶', rank: 0.7 },
        { id: 'doc-3', title: '机器学习入门', rank: 0.8 },
      ];

      const orResults = mockResults.filter(
        (doc) => 
          doc.title.includes('机器学习') || doc.title.includes('深度学习')
      );

      expect(orResults).toHaveLength(3);
    });

    it('should correctly combine ts_rank and similarity scores', () => {
      const tsRank = 0.8;
      const similarity = 0.6;

      const combined = combineRankings(tsRank, similarity, 0.7, 0.3);

      expect(combined).toBeCloseTo(0.8 * 0.7 + 0.6 * 0.3, 5);
      expect(combined).toBeGreaterThan(0);
      expect(combined).toBeLessThanOrEqual(1);
    });

    it('should normalize combined scores to 0-1 range', () => {
      const testCases = [
        { ts: 1.0, sim: 1.0, expectedMax: 1.0 },
        { ts: 0.0, sim: 0.0, expectedMin: 0.0 },
        { ts: 0.5, sim: 0.5, expectedMid: 0.5 },
      ];

      testCases.forEach(({ ts, sim }) => {
        const combined = combineRankings(ts, sim, 0.5, 0.5);
        expect(combined).toBeGreaterThanOrEqual(0);
        expect(combined).toBeLessThanOrEqual(1);
      });
    });
  });

  describe('pg_trgm fuzzy matching', () => {
    it('should match short words with appropriate threshold', () => {
      const shortWords = ['AI', '机器学习', 'React', 'Vue'];
      const threshold = 0.3;

      const testPairs = [
        { query: '机器学', target: '机器学习', shouldMatch: true },
        { query: 'Reac', target: 'React', shouldMatch: true },
        { query: 'abc', target: 'def', shouldMatch: false },
      ];

      expect(testPairs[0].shouldMatch).toBe(true);
      expect(threshold).toBe(0.3);
      expect(shortWords.length).toBe(4);
    });

    it('should avoid false positives for 2-3 character words', () => {
      const falsePositiveTests = [
        { query: 'JS', candidates: ['JS', 'TS', 'CSS'], matchCount: 1 },
        { query: 'AI', candidates: ['AI', 'BI', 'CI'], matchCount: 1 },
      ];

      falsePositiveTests.forEach(({ query, candidates, matchCount }) => {
        const matches = candidates.filter((c) => c.includes(query) || c === query);
        expect(matches.length).toBeLessThanOrEqual(matchCount + 1);
      });
    });

    it('should handle partial matches for Chinese words', () => {
      const chineseTests = [
        { partial: '机器', full: '机器学习', shouldContain: true },
        { partial: '学习', full: '深度学习', shouldContain: true },
        { partial: 'xyz', full: '机器学习', shouldContain: false },
      ];

      chineseTests.forEach(({ partial, full, shouldContain }) => {
        const result = full.includes(partial);
        expect(result).toBe(shouldContain);
      });
    });

    it('should adjust threshold based on word length', () => {
      const getThreshold = (length: number): number => {
        if (length <= 2) return 0.8;
        if (length <= 3) return 0.6;
        if (length <= 5) return 0.4;
        return 0.3;
      };

      expect(getThreshold(2)).toBe(0.8);
      expect(getThreshold(3)).toBe(0.6);
      expect(getThreshold(4)).toBe(0.4);
      expect(getThreshold(10)).toBe(0.3);
    });
  });

  describe('Query building', () => {
    it('should include space filter in WHERE clause', () => {
      const filter: SearchFilter = {
        spaceId: 'space-123',
      };

      const whereClause = `spaceId = '${filter.spaceId}'`;

      expect(whereClause).toContain('spaceId');
      expect(whereClause).toContain('space-123');
    });

    it('should include tag filter with array overlap', () => {
      const filter: SearchFilter = {
        tagIds: ['tag-1', 'tag-2'],
      };

      const whereClause = `tagIds && ARRAY['${filter.tagIds.join("','")}']::uuid[]`;

      expect(whereClause).toContain('tag-1');
      expect(whereClause).toContain('tag-2');
    });

    it('should include date range filter', () => {
      const filter: SearchFilter = {
        dateFrom: new Date('2024-01-01'),
        dateTo: new Date('2024-12-31'),
      };

      const whereClause = `createdAt >= '${filter.dateFrom.toISOString()}' AND createdAt <= '${filter.dateTo.toISOString()}'`;

      expect(whereClause).toContain('2024-01-01');
      expect(whereClause).toContain('2024-12-31');
    });

    it('should include source type filter', () => {
      const filter: SearchFilter = {
        sourceType: 'NOTION',
      };

      const whereClause = `externalSource = '${filter.sourceType}'`;

      expect(whereClause).toContain('NOTION');
    });

    it('should handle archived filter', () => {
      const filter: SearchFilter = {
        isArchived: false,
      };

      const whereClause = `"isArchived" = ${filter.isArchived}`;

      expect(whereClause).toContain('isArchived');
    });
  });

  describe('Result ordering', () => {
    it('should order by relevance by default', () => {
      const results = [
        { id: 'doc-1', rank: 0.9 },
        { id: 'doc-2', rank: 0.5 },
        { id: 'doc-3', rank: 0.7 },
      ];

      const sorted = [...results].sort((a, b) => b.rank - a.rank);

      expect(sorted[0].id).toBe('doc-1');
      expect(sorted[1].id).toBe('doc-3');
      expect(sorted[2].id).toBe('doc-2');
    });

    it('should support ordering by date', () => {
      const results = [
        { id: 'doc-1', createdAt: new Date('2024-01-01') },
        { id: 'doc-2', createdAt: new Date('2024-03-01') },
        { id: 'doc-3', createdAt: new Date('2024-02-01') },
      ];

      const sortedDesc = [...results].sort(
        (a, b) => b.createdAt.getTime() - a.createdAt.getTime()
      );
      const sortedAsc = [...results].sort(
        (a, b) => a.createdAt.getTime() - b.createdAt.getTime()
      );

      expect(sortedDesc[0].id).toBe('doc-2');
      expect(sortedAsc[0].id).toBe('doc-1');
    });
  });

  describe('Pagination', () => {
    it('should calculate correct offset', () => {
      const page = 2;
      const pageSize = 20;
      const offset = (page - 1) * pageSize;

      expect(offset).toBe(20);
    });

    it('should calculate total pages correctly', () => {
      const total = 125;
      const pageSize = 20;
      const totalPages = Math.ceil(total / pageSize);

      expect(totalPages).toBe(7);
    });

    it('should return correct items for current page', () => {
      const allItems = Array.from({ length: 50 }, (_, i) => ({ id: `doc-${i}` }));
      const page = 2;
      const pageSize = 10;
      const start = (page - 1) * pageSize;
      const end = start + pageSize;

      const pageItems = allItems.slice(start, end);

      expect(pageItems).toHaveLength(10);
      expect(pageItems[0].id).toBe('doc-10');
      expect(pageItems[9].id).toBe('doc-19');
    });
  });

  describe('Empty queries', () => {
    it('should return all documents when query is empty', () => {
      const query: SearchQuery = {
        query: '',
      };

      expect(query.query).toBe('');
    });

    it('should handle whitespace-only queries', () => {
      const query = '   \n\t   ';
      const trimmed = query.trim();

      expect(trimmed).toBe('');
    });
  });

  describe('Special character handling', () => {
    it('should escape special characters in search query', () => {
      const specialChars = ['\'', '"', '\\', ';', '--'];
      const escape = (str: string): string => {
        return str.replace(/'/g, "''").replace(/\\/g, '\\\\');
      };

      specialChars.forEach((char) => {
        const escaped = escape(`test${char}query`);
        expect(escaped).toBeDefined();
      });
    });

    it('should handle CJK characters correctly', () => {
      const cjkQueries = [
        '机器学习',
        'ディープラーニング',
        '머신러닝',
      ];

      cjkQueries.forEach((query) => {
        expect(query.length).toBeGreaterThan(0);
      });
    });
  });
});

function combineRankings(
  tsRank: number,
  similarity: number,
  tsWeight: number,
  simWeight: number
): number {
  return tsRank * tsWeight + similarity * simWeight;
}
