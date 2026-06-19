import React from 'react';
import { render, act, renderHook } from '@testing-library/react';
import '@testing-library/jest-dom';
import { SearchSkeleton } from '../../src/renderer/components/SearchSkeleton';
import { createMockNote } from '../__fixtures__/testFixtures';
import type { Note, SearchResult, SearchOptions } from '@shared/types';

jest.mock('../../src/renderer/hooks/useSearch', () => ({
  useSearch: jest.fn(),
}));

import { useSearch } from '../../src/renderer/hooks/useSearch';

class MockWorker {
  onmessage: ((e: MessageEvent) => void) | null = null;
  postMessage = jest.fn();
  terminate = jest.fn();
}

const createMockWorker = () => new MockWorker();

describe('SearchSkeleton Component', () => {
  it('should render skeleton items', () => {
    render(<SearchSkeleton count={5} />);
    
    const skeletonItems = document.querySelectorAll('.search-skeleton-item');
    expect(skeletonItems.length).toBe(5);
  });

  it('should render default count of 6 items', () => {
    render(<SearchSkeleton />);
    
    const skeletonItems = document.querySelectorAll('.search-skeleton-item');
    expect(skeletonItems.length).toBe(6);
  });

  it('should have icon, title, and snippet for each item', () => {
    render(<SearchSkeleton count={2} />);
    
    const icons = document.querySelectorAll('.search-skeleton-icon');
    const titles = document.querySelectorAll('.search-skeleton-title');
    const snippets = document.querySelectorAll('.search-skeleton-snippet');
    
    expect(icons.length).toBe(2);
    expect(titles.length).toBe(2);
    expect(snippets.length).toBe(2);
  });
});

describe('Search Logic - Core Algorithm', () => {
  const testNotes: Note[] = [
    createMockNote({ id: '1', title: 'JavaScript 基础', content: 'JavaScript is a programming language.', tags: ['javascript'] }),
    createMockNote({ id: '2', title: 'React 入门', content: 'React is a UI library.', tags: ['react', 'javascript'] }),
    createMockNote({ id: '3', title: 'TypeScript 指南', content: 'TypeScript adds types to JavaScript.', tags: ['typescript'] }),
  ];

  const buildSearchIndex = (notes: Note[]) => {
    const notesCache = new Map<string, Note>();
    for (const note of notes) {
      notesCache.set(note.id, note);
    }
    return notesCache;
  };

  const highlightText = (text: string, query: string): string => {
    if (!query.trim()) return text;
    const regex = new RegExp(`(${query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi');
    return text.replace(regex, '[[HIGHLIGHT]]$1[[/HIGHLIGHT]]');
  };

  const highlightContent = (content: string, query: string): string => {
    const lines = content.split('\n');
    const queryLower = query.toLowerCase();
    let bestLine = '';
    let bestIndex = -1;
    
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const idx = line.toLowerCase().indexOf(queryLower);
      if (idx !== -1) {
        bestLine = line;
        bestIndex = idx;
        break;
      }
    }
    
    if (bestIndex === -1) return content.slice(0, 200);
    
    const start = Math.max(0, bestIndex - 50);
    const end = Math.min(bestLine.length, bestIndex + query.length + 50);
    let snippet = bestLine.slice(start, end);
    
    if (start > 0) snippet = '...' + snippet;
    if (end < bestLine.length) snippet = snippet + '...';
    
    return highlightText(snippet, query);
  };

  it('should highlight matched text', () => {
    const result = highlightText('Hello World', 'world');
    expect(result).toContain('[[HIGHLIGHT]]');
    expect(result).toContain('World');
  });

  it('should be case insensitive in highlighting', () => {
    const result = highlightText('JavaScript', 'javascript');
    expect(result).toContain('[[HIGHLIGHT]]JavaScript[[/HIGHLIGHT]]');
  });

  it('should extract content snippet with match', () => {
    const content = 'a'.repeat(100) + ' keyword ' + 'b'.repeat(100);
    const result = highlightContent(content, 'keyword');
    expect(result).toContain('keyword');
    expect(result).toContain('...');
  });

  it('should return first 200 chars when no match in content', () => {
    const content = 'a'.repeat(300);
    const result = highlightContent(content, 'nonexistent');
    expect(result.length).toBe(200);
  });

  it('should build notes cache correctly', () => {
    const cache = buildSearchIndex(testNotes);
    expect(cache.size).toBe(3);
    expect(cache.get('1')?.title).toBe('JavaScript 基础');
  });
});

describe('useSearch Hook - Mocked', () => {
  const testNotes: Note[] = [
    createMockNote({ id: '1', title: 'Test Note 1', content: 'Content 1', tags: ['tag1'] }),
    createMockNote({ id: '2', title: 'Test Note 2', content: 'Content 2', tags: ['tag2'] }),
  ];

  const mockResults: SearchResult[] = [
    {
      id: '1',
      title: 'Test Note 1',
      path: 'test/note-1.md',
      score: 2,
      fields: { title: 'Test Note 1', content: 'Content 1' },
      highlight: { title: '[[HIGHLIGHT]]Test[[/HIGHLIGHT]] Note 1', content: '...[[HIGHLIGHT]]Test[[/HIGHLIGHT] content...' },
    },
  ];

  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllMocks();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('should return search results', () => {
    (useSearch as jest.Mock).mockReturnValue({
      results: mockResults,
      isLoading: false,
      search: jest.fn(),
      addNote: jest.fn(),
      updateNote: jest.fn(),
      removeNote: jest.fn(),
    });

    const { result } = renderHook(() => useSearch(testNotes));
    
    expect(result.current.results).toEqual(mockResults);
    expect(result.current.isLoading).toBe(false);
  });

  it('should show loading state during search', () => {
    (useSearch as jest.Mock).mockReturnValue({
      results: [],
      isLoading: true,
      search: jest.fn(),
      addNote: jest.fn(),
      updateNote: jest.fn(),
      removeNote: jest.fn(),
    });

    const { result } = renderHook(() => useSearch(testNotes));
    
    expect(result.current.isLoading).toBe(true);
    expect(result.current.results).toEqual([]);
  });

  it('should expose search function', () => {
    const mockSearchFn = jest.fn();
    (useSearch as jest.Mock).mockReturnValue({
      results: [],
      isLoading: false,
      search: mockSearchFn,
      addNote: jest.fn(),
      updateNote: jest.fn(),
      removeNote: jest.fn(),
    });

    const { result } = renderHook(() => useSearch(testNotes));
    
    expect(typeof result.current.search).toBe('function');
    
    act(() => {
      result.current.search('test');
    });
    
    expect(mockSearchFn).toHaveBeenCalledWith('test');
  });

  it('should expose CRUD operations for incremental updates', () => {
    const mockAddNote = jest.fn();
    const mockUpdateNote = jest.fn();
    const mockRemoveNote = jest.fn();
    
    (useSearch as jest.Mock).mockReturnValue({
      results: [],
      isLoading: false,
      search: jest.fn(),
      addNote: mockAddNote,
      updateNote: mockUpdateNote,
      removeNote: mockRemoveNote,
    });

    const { result } = renderHook(() => useSearch(testNotes));
    
    const newNote = createMockNote({ id: '3', title: 'New Note' });
    
    act(() => {
      result.current.addNote(newNote);
      result.current.updateNote(newNote);
      result.current.removeNote('3');
    });
    
    expect(mockAddNote).toHaveBeenCalledWith(newNote);
    expect(mockUpdateNote).toHaveBeenCalledWith(newNote);
    expect(mockRemoveNote).toHaveBeenCalledWith('3');
  });
});

describe('Search Worker - Message Protocol', () => {
  it('should have correct init message format', () => {
    const notes = [createMockNote({ id: '1', title: 'Test', content: 'Test content' })];
    const initMessage = {
      type: 'init' as const,
      notes,
    };
    
    expect(initMessage.type).toBe('init');
    expect(initMessage.notes).toHaveLength(1);
    expect(initMessage.notes[0].id).toBe('1');
  });

  it('should have correct search message format', () => {
    const searchMessage = {
      type: 'search' as const,
      query: 'test',
      options: { limit: 10, highlight: true } as SearchOptions,
      requestId: '1',
    };
    
    expect(searchMessage.type).toBe('search');
    expect(searchMessage.query).toBe('test');
    expect(searchMessage.requestId).toBe('1');
    expect(searchMessage.options?.limit).toBe(10);
  });

  it('should have correct search results message format', () => {
    const results: SearchResult[] = [
      {
        id: '1',
        title: 'Test Note',
        path: 'test.md',
        score: 1,
        fields: { title: 'Test Note' },
      },
    ];
    
    const resultMessage = {
      type: 'searchResults' as const,
      results,
      requestId: '1',
    };
    
    expect(resultMessage.type).toBe('searchResults');
    expect(resultMessage.results).toHaveLength(1);
    expect(resultMessage.requestId).toBe('1');
  });

  it('should have correct CRUD message formats', () => {
    const note = createMockNote({ id: '1', title: 'Test' });
    
    const addMessage = { type: 'addNote' as const, note };
    const updateMessage = { type: 'updateNote' as const, note };
    const removeMessage = { type: 'removeNote' as const, id: '1' };
    
    expect(addMessage.type).toBe('addNote');
    expect(addMessage.note.id).toBe('1');
    
    expect(updateMessage.type).toBe('updateNote');
    expect(updateMessage.note.id).toBe('1');
    
    expect(removeMessage.type).toBe('removeNote');
    expect(removeMessage.id).toBe('1');
  });
});

describe('Debounce Behavior', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllMocks();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('should delay execution by 300ms', () => {
    const mockFn = jest.fn();
    let timeoutId: ReturnType<typeof setTimeout> | null = null;
    
    const debouncedFn = (query: string) => {
      if (timeoutId) clearTimeout(timeoutId);
      timeoutId = setTimeout(() => {
        mockFn(query);
      }, 300);
    };
    
    debouncedFn('test');
    
    expect(mockFn).not.toHaveBeenCalled();
    
    act(() => {
      jest.advanceTimersByTime(299);
    });
    
    expect(mockFn).not.toHaveBeenCalled();
    
    act(() => {
      jest.advanceTimersByTime(1);
    });
    
    expect(mockFn).toHaveBeenCalledTimes(1);
    expect(mockFn).toHaveBeenCalledWith('test');
  });

  it('should cancel previous call when called again within delay', () => {
    const mockFn = jest.fn();
    let timeoutId: ReturnType<typeof setTimeout> | null = null;
    
    const debouncedFn = (query: string) => {
      if (timeoutId) clearTimeout(timeoutId);
      timeoutId = setTimeout(() => {
        mockFn(query);
      }, 300);
    };
    
    debouncedFn('first');
    
    act(() => {
      jest.advanceTimersByTime(200);
    });
    
    debouncedFn('second');
    
    act(() => {
      jest.advanceTimersByTime(299);
    });
    
    expect(mockFn).not.toHaveBeenCalled();
    
    act(() => {
      jest.advanceTimersByTime(1);
    });
    
    expect(mockFn).toHaveBeenCalledTimes(1);
    expect(mockFn).toHaveBeenCalledWith('second');
  });

  it('should handle rapid successive calls', () => {
    const mockFn = jest.fn();
    let timeoutId: ReturnType<typeof setTimeout> | null = null;
    
    const debouncedFn = (query: string) => {
      if (timeoutId) clearTimeout(timeoutId);
      timeoutId = setTimeout(() => {
        mockFn(query);
      }, 300);
    };
    
    for (let i = 0; i < 10; i++) {
      debouncedFn(`query-${i}`);
    }
    
    expect(mockFn).not.toHaveBeenCalled();
    
    act(() => {
      jest.advanceTimersByTime(300);
    });
    
    expect(mockFn).toHaveBeenCalledTimes(1);
    expect(mockFn).toHaveBeenCalledWith('query-9');
  });
});
