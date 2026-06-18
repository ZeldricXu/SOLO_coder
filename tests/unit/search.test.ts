jest.mock('flexsearch', () => require('../mocks/flexsearchMock').default);

import { SearchService } from '../../src/main/services/searchService';
import { createMockNote, generateBatchNotes, generateLargeNote } from '../__fixtures__/testFixtures';
import { measurePerformance, waitFor } from '../utils/testUtils';
import type { Note, SearchOptions } from '@shared/types';

describe('Search Service - Initialization', () => {
  afterEach(() => {
    jest.clearAllMocks();
  });

  it('should initialize search index with notes', () => {
    const notes = [
      createMockNote({ id: '1', title: 'JavaScript 基础', content: 'JavaScript is a programming language.', tags: ['javascript'] }),
      createMockNote({ id: '2', title: 'React 入门', content: 'React is a UI library.', tags: ['react', 'javascript'] }),
      createMockNote({ id: '3', title: 'TypeScript 指南', content: 'TypeScript adds types to JavaScript.', tags: ['typescript'] }),
    ];
    
    expect(() => {
      SearchService.init(notes);
    }).not.toThrow();
  });

  it('should initialize with empty array', () => {
    expect(() => {
      SearchService.init([]);
    }).not.toThrow();
  });

  it('should rebuild index correctly', () => {
    const initialNotes = [
      createMockNote({ id: '1', title: 'Initial Note', content: 'Initial content.' }),
    ];
    
    SearchService.init(initialNotes);
    
    const newNotes = [
      createMockNote({ id: '1', title: 'Updated Note', content: 'Updated content.' }),
      createMockNote({ id: '2', title: 'New Note', content: 'New content.' }),
    ];
    
    SearchService.rebuildIndex(newNotes);
    
    const results = SearchService.query('Updated');
    expect(results.length).toBeGreaterThan(0);
  });
});

describe('Search Service - Basic Queries', () => {
  const testNotes: Note[] = [
    createMockNote({
      id: 'js-1',
      title: 'JavaScript 异步编程',
      content: 'JavaScript 通过 Promise 和 async/await 实现异步编程。回调函数是早期的异步处理方式。',
      tags: ['javascript', 'async'],
    }),
    createMockNote({
      id: 'js-2',
      title: 'JavaScript 设计模式',
      content: '常见的设计模式包括单例模式、工厂模式、观察者模式等。',
      tags: ['javascript', 'design-pattern'],
    }),
    createMockNote({
      id: 'react-1',
      title: 'React Hooks 详解',
      content: 'React Hooks 包括 useState、useEffect、useContext 等。',
      tags: ['react', 'hooks'],
    }),
    createMockNote({
      id: 'ts-1',
      title: 'TypeScript 类型系统',
      content: 'TypeScript 提供了强大的类型系统，包括泛型、联合类型、交叉类型等。',
      tags: ['typescript', 'types'],
    }),
    createMockNote({
      id: 'py-1',
      title: 'Python 数据科学',
      content: 'Python 在数据科学领域应用广泛，使用 NumPy、Pandas、Scikit-learn 等库。',
      tags: ['python', 'data-science'],
    }),
  ];

  beforeEach(() => {
    SearchService.init(testNotes);
  });

  it('should search by title', () => {
    const results = SearchService.query('JavaScript');
    expect(results.length).toBeGreaterThan(0);
    expect(results[0].title).toContain('JavaScript');
  });

  it('should search by content', () => {
    const results = SearchService.query('Promise');
    expect(results.length).toBe(1);
    expect(results[0].id).toBe('js-1');
  });

  it('should search by tags', () => {
    const results = SearchService.query('async');
    expect(results.length).toBeGreaterThan(0);
    expect(results.some(r => r.id === 'js-1')).toBe(true);
  });

  it('should return empty array for no matches', () => {
    const results = SearchService.query('nonexistent-keyword-xyz');
    expect(results).toEqual([]);
  });

  it('should return empty array for empty query', () => {
    const results = SearchService.query('');
    expect(results).toEqual([]);
  });

  it('should return empty array for whitespace-only query', () => {
    const results = SearchService.query('   ');
    expect(results).toEqual([]);
  });

  it('should be case insensitive', () => {
    const results1 = SearchService.query('JAVASCRIPT');
    const results2 = SearchService.query('javascript');
    
    expect(results1.map(r => r.id).sort()).toEqual(results2.map(r => r.id).sort());
  });

  it('should support partial matches', () => {
    const results = SearchService.query('Java');
    expect(results.some(r => r.id.includes('js'))).toBe(true);
  });

  it('should rank title matches higher than content matches', () => {
    const results = SearchService.query('JavaScript');
    
    const titleMatches = results.filter(r => r.title.toLowerCase().includes('javascript'));
    const contentOnly = results.filter(r => !r.title.toLowerCase().includes('javascript'));
    
    for (const titleMatch of titleMatches) {
      for (const contentMatch of contentOnly) {
        expect(titleMatch.score).toBeGreaterThanOrEqual(contentMatch.score);
      }
    }
  });

  it('should rank exact matches higher', () => {
    const results = SearchService.query('JavaScript 异步编程');
    expect(results[0].id).toBe('js-1');
  });
});

describe('Search Service - Multi-dimensional Search', () => {
  const testNotes: Note[] = [
    createMockNote({
      id: 'n1',
      title: 'React 性能优化',
      content: 'React 性能优化包括 memo、useMemo、useCallback 等技巧。',
      tags: ['react', 'performance'],
    }),
    createMockNote({
      id: 'n2',
      title: 'Vue 性能调优',
      content: 'Vue 的性能优化与 React 类似，但有自己的特点。',
      tags: ['vue', 'performance'],
    }),
    createMockNote({
      id: 'n3',
      title: 'Web 性能监控',
      content: '性能监控是保证应用质量的重要手段。',
      tags: ['web', 'performance', 'monitoring'],
    }),
  ];

  beforeEach(() => {
    SearchService.init(testNotes);
  });

  it('should search by specific field - title only', () => {
    const options: SearchOptions = { fields: ['title'] };
    const results = SearchService.query('性能', options);
    
    expect(results.length).toBe(3);
    expect(results.every(r => r.title.includes('性能'))).toBe(true);
  });

  it('should search by specific field - content only', () => {
    const options: SearchOptions = { fields: ['content'] };
    const results = SearchService.query('memo', options);
    
    expect(results.length).toBe(1);
    expect(results[0].id).toBe('n1');
  });

  it('should search by specific field - tags only', () => {
    const options: SearchOptions = { fields: ['tags'] };
    const results = SearchService.query('performance', options);
    
    expect(results.length).toBe(3);
  });

  it('should combine multiple fields', () => {
    const options: SearchOptions = { fields: ['title', 'tags'] };
    const results = SearchService.query('React', options);
    
    expect(results.length).toBeGreaterThan(0);
    expect(results[0].id).toBe('n1');
  });

  it('should respect limit parameter', () => {
    const options: SearchOptions = { limit: 2 };
    const results = SearchService.query('性能', options);
    
    expect(results.length).toBeLessThanOrEqual(2);
  });

  it('should handle limit of zero', () => {
    const options: SearchOptions = { limit: 0 };
    const results = SearchService.query('性能', options);
    
    expect(results).toEqual([]);
  });

  it('should handle highlight option', () => {
    const options: SearchOptions = { highlight: true };
    const results = SearchService.query('React', options);
    
    expect(results[0].highlight).toBeDefined();
    expect(results[0].highlight?.title).toContain('React');
  });

  it('should disable highlighting when option is false', () => {
    const options: SearchOptions = { highlight: false };
    const results = SearchService.query('React', options);
    
    expect(results[0].highlight).toBeUndefined();
  });
});

describe('Search Service - CRUD Operations', () => {
  beforeEach(() => {
    SearchService.init([]);
  });

  it('should add note to index', () => {
    const note = createMockNote({ id: 'new', title: 'New Note', content: 'New content.' });
    
    SearchService.addNote(note);
    
    const results = SearchService.query('New');
    expect(results.length).toBe(1);
    expect(results[0].id).toBe('new');
  });

  it('should update note in index', () => {
    const note = createMockNote({ id: 'update', title: 'Original Title', content: 'Original content.' });
    
    SearchService.addNote(note);
    
    const updatedNote = { ...note, title: 'Updated Title', content: 'Updated content.' };
    SearchService.updateNote(updatedNote);
    
    const results = SearchService.query('Updated');
    expect(results[0].title).toBe('Updated Title');
    
    const oldResults = SearchService.query('Original');
    expect(oldResults.length).toBe(0);
  });

  it('should remove note from index', () => {
    const note = createMockNote({ id: 'remove', title: 'To Remove', content: 'Content to remove.' });
    
    SearchService.addNote(note);
    
    let results = SearchService.query('Remove');
    expect(results.length).toBe(1);
    
    SearchService.removeNote('remove');
    
    results = SearchService.query('Remove');
    expect(results.length).toBe(0);
  });

  it('should handle removing non-existent note', () => {
    expect(() => {
      SearchService.removeNote('nonexistent');
    }).not.toThrow();
  });

  it('should handle updating non-existent note', () => {
    const note = createMockNote({ id: 'nonexistent', title: 'Test' });
    
    expect(() => {
      SearchService.updateNote(note);
    }).not.toThrow();
  });
});

describe('Search Service - Context Highlight', () => {
  const testContent = `
# JavaScript 异步编程

这是一段很长的前言，介绍了 JavaScript 异步编程的历史背景和发展历程，从早期的回调函数到现代的 Promise 和 async/await，每一步都有着重要的技术意义。JavaScript 通过 Promise 和 async/await 实现异步编程，这是现代前端开发的核心技能之一。

回调函数是早期的异步处理方式，但容易导致回调地狱。

Promise 提供了更优雅的异步处理方式。
async/await 则让异步代码看起来像同步代码。

这是 JavaScript 异步编程的重要概念。
`;

  beforeEach(() => {
    SearchService.init([
      createMockNote({ id: 'highlight-test', title: 'JavaScript 异步编程', content: testContent, tags: ['javascript'] }),
    ]);
  });

  it('should include context snippet in highlight', () => {
    const results = SearchService.query('async/await', { highlight: true });
    
    expect(results[0].highlight?.content).toBeDefined();
    expect(results[0].highlight?.content).toContain('async/await');
    expect(results[0].highlight?.content).toContain('...');
  });

  it('should highlight matched text', () => {
    const results = SearchService.query('async', { highlight: true });
    
    expect(results[0].highlight?.content).toContain('[[HIGHLIGHT]]');
    expect(results[0].highlight?.content).toContain('[[/HIGHLIGHT]]');
  });

  it('should highlight in title', () => {
    const results = SearchService.query('JavaScript', { highlight: true });
    
    expect(results[0].highlight?.title).toContain('[[HIGHLIGHT]]JavaScript[[/HIGHLIGHT]]');
  });

  it('should handle multiple matches', () => {
    const results = SearchService.query('异步', { highlight: true });
    
    expect(results[0].highlight?.content).toBeDefined();
    expect((results[0].highlight?.content?.match(/\[\[HIGHLIGHT\]\]/g) || []).length).toBeGreaterThanOrEqual(1);
  });
});

describe('Search Service - Edge Cases', () => {
  it('should handle Chinese queries', () => {
    const notes = [
      createMockNote({ id: 'c1', title: '深度学习入门', content: '深度学习是机器学习的一个分支。' }),
      createMockNote({ id: 'c2', title: '机器学习基础', content: '机器学习包括监督学习和无监督学习。' }),
    ];
    
    SearchService.init(notes);
    
    const results = SearchService.query('学习');
    expect(results.length).toBe(2);
  });

  it('should handle special characters in query', () => {
    const notes = [
      createMockNote({ id: 's1', title: 'C++ 编程', content: 'C++ is a systems language.' }),
      createMockNote({ id: 's2', title: 'C# 开发', content: 'C# is used for .NET development.' }),
    ];
    
    SearchService.init(notes);
    
    expect(() => {
      SearchService.query('C++');
      SearchService.query('C#');
      SearchService.query('test?');
      SearchService.query('test*');
    }).not.toThrow();
  });

  it('should handle very long queries', () => {
    const longQuery = 'a'.repeat(1000);
    
    expect(() => {
      SearchService.query(longQuery);
    }).not.toThrow();
  });

  it('should handle unicode characters', () => {
    const notes = [
      createMockNote({ id: 'u1', title: 'Emoji Test 🌟', content: 'This note has emoji 🎉 and unicode 你好.' }),
    ];
    
    SearchService.init(notes);
    
    const results = SearchService.query('你好');
    expect(results.length).toBe(1);
  });

  it('should handle duplicate content gracefully', () => {
    const note1 = createMockNote({ id: 'dup1', title: 'Duplicate', content: 'Same content.' });
    const note2 = createMockNote({ id: 'dup2', title: 'Duplicate', content: 'Same content.' });
    
    SearchService.init([note1, note2]);
    
    const results = SearchService.query('Duplicate');
    expect(results.length).toBe(2);
    expect(results.map(r => r.id).sort()).toEqual(['dup1', 'dup2']);
  });

  it('should handle empty note content', () => {
    const note = createMockNote({ id: 'empty', title: 'Empty Content', content: '' });
    
    SearchService.init([note]);
    
    const results = SearchService.query('Empty');
    expect(results.length).toBe(1);
    expect(results[0].id).toBe('empty');
  });

  it('should handle notes with only whitespace', () => {
    const note = createMockNote({ id: 'whitespace', title: '   ', content: '   ' });
    
    expect(() => {
      SearchService.init([note]);
    }).not.toThrow();
  });
});

describe('Search Service - Performance', () => {
  jest.setTimeout(30000);

  it('should index 1000 notes under 1 second', async () => {
    const notes = generateBatchNotes(1000);
    
    const { totalTime } = await measurePerformance(() => {
      SearchService.init(notes);
    });
    
    expect(totalTime).toBeLessThan(1000);
  });

  it('should query 1000 notes with sub-100ms response', async () => {
    const notes = generateBatchNotes(1000);
    SearchService.init(notes);
    
    const queries = ['note', 'work', 'study', 'tag-1', 'tag-5'];
    const times: number[] = [];
    
    for (const query of queries) {
      const { totalTime } = await measurePerformance(() => {
        SearchService.query(query);
      });
      times.push(totalTime);
    }
    
    const avgTime = times.reduce((a, b) => a + b, 0) / times.length;
    expect(avgTime).toBeLessThan(100);
  });

  it('should handle 100,000 word note content', async () => {
    const largeContent = generateLargeNote(100000);
    const note = createMockNote({ id: 'large', title: 'Large Note', content: largeContent });
    
    const { totalTime: indexTime } = await measurePerformance(() => {
      SearchService.init([note]);
    });
    
    expect(indexTime).toBeLessThan(2000);
    
    const { totalTime: queryTime } = await measurePerformance(() => {
      SearchService.query('知识');
    });
    
    expect(queryTime).toBeLessThan(100);
    
    const results = SearchService.query('知识');
    expect(results.length).toBe(1);
  });

  it('should maintain performance with frequent updates', async () => {
    const notes = generateBatchNotes(500);
    SearchService.init(notes);
    
    const { totalTime } = await measurePerformance(async () => {
      for (let i = 0; i < 100; i++) {
        const note = notes[i % notes.length];
        SearchService.updateNote({
          ...note,
          title: `${note.title} (Updated ${i})`,
        });
      }
    });
    
    expect(totalTime).toBeLessThan(2000);
    
    const results = SearchService.query('Updated');
    expect(results.length).toBeGreaterThan(0);
  });

  it('should not block on concurrent queries', async () => {
    const notes = generateBatchNotes(1000);
    SearchService.init(notes);
    
    const queries = Array.from({ length: 20 }, (_, i) => `query ${i}`);
    
    const startTime = Date.now();
    const promises = queries.map(q => 
      new Promise(resolve => {
        setTimeout(() => {
          resolve(SearchService.query(q));
        }, Math.random() * 10);
      })
    );
    
    await Promise.all(promises);
    const totalTime = Date.now() - startTime;
    
    expect(totalTime).toBeLessThan(500);
  });
});

describe('Search Service - Result Scoring', () => {
  beforeEach(() => {
    SearchService.init([
      createMockNote({
        id: 'multi-field',
        title: 'JavaScript 完全指南',
        content: 'JavaScript is a versatile language.',
        tags: ['javascript', 'guide'],
      }),
      createMockNote({
        id: 'two-fields',
        title: 'JavaScript 入门',
        content: 'JavaScript is a great language. JavaScript is everywhere.',
        tags: ['programming'],
      }),
      createMockNote({
        id: 'one-field',
        title: '语言参考',
        content: 'General programming content.',
        tags: ['javascript', 'language'],
      }),
    ]);
  });

  it('should prioritize notes with more field matches', () => {
    const results = SearchService.query('JavaScript');
    expect(results[0].id).toBe('multi-field');
    expect(results[0].score).toBe(3);
  });

  it('should score notes with more matches higher', () => {
    const results = SearchService.query('JavaScript');
    const multiField = results.find(r => r.id === 'multi-field');
    const twoFields = results.find(r => r.id === 'two-fields');
    const oneField = results.find(r => r.id === 'one-field');
    
    expect(multiField?.score).toBe(3);
    expect(twoFields?.score).toBe(2);
    expect(oneField?.score).toBe(1);
  });

  it('should sort results by score descending', () => {
    const results = SearchService.query('JavaScript');
    for (let i = 1; i < results.length; i++) {
      expect(results[i - 1].score).toBeGreaterThanOrEqual(results[i].score);
    }
  });

  it('should include field information in results', () => {
    const results = SearchService.query('JavaScript');
    expect(results[0].fields).toBeDefined();
    expect(results[0].fields.title || results[0].fields.content).toBeDefined();
  });
});

describe('Search Service - Integration with File Sync', () => {
  let tempVault: any;

  beforeEach(() => {
    jest.resetModules();
    tempVault = {
      createFile: jest.fn(),
      cleanup: jest.fn(),
    };
  });

  it('should update search index when notes change', async () => {
    const initialNotes = [
      createMockNote({ id: '1', title: 'Old Title', content: 'Old content.' }),
    ];
    
    SearchService.init(initialNotes);
    
    const updatedNote = { ...initialNotes[0], title: 'New Title', content: 'New content.' };
    
    SearchService.updateNote(updatedNote);
    
    await waitFor(() => {
      const results = SearchService.query('New Title');
      return results.length > 0;
    }, 1000);
    
    const results = SearchService.query('New Title');
    expect(results.length).toBe(1);
    expect(results[0].id).toBe('1');
  });

  it('should maintain consistency between file system and search index', () => {
    const notes = generateBatchNotes(100);
    SearchService.init(notes);
    
    for (const note of notes) {
      const results = SearchService.query(note.title);
      const found = results.some(r => r.id === note.id);
      expect(found).toBe(true);
    }
  });

  it('should handle batch deletion gracefully', () => {
    const notes = generateBatchNotes(50);
    SearchService.init(notes);
    
    for (let i = 0; i < 25; i++) {
      SearchService.removeNote(notes[i].id);
    }
    
    const results = SearchService.query('note');
    expect(results.length).toBeGreaterThanOrEqual(25);
    expect(results.length).toBeLessThan(50);
  });
});
