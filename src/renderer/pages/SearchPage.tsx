import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { SearchResult, IPCResponse, Document } from '@shared/types';
import { formatRelative } from '@shared/utils/date';
import { useDebounce } from '../hooks/useDebounce';
import { useAppStore } from '../stores/appStore';

interface SearchFilter {
  tags: string[];
  dateRange: {
    start: string | null;
    end: string | null;
  };
  sortBy: 'relevance' | 'date' | 'title';
}

interface ParsedQuery {
  rawQuery: string;
  titleTerms: string[];
  tagTerms: string[];
  exactPhrases: string[];
  freeTerms: string[];
}

const HISTORY_KEY = 'knowledgeforge_search_history';
const MAX_HISTORY = 20;

function parseSearchQuery(input: string): ParsedQuery {
  const result: ParsedQuery = {
    rawQuery: input,
    titleTerms: [],
    tagTerms: [],
    exactPhrases: [],
    freeTerms: [],
  };

  const exactPhraseRegex = /"([^"]+)"/g;
  let match;
  let remaining = input;
  const exactPhrases: string[] = [];

  while ((match = exactPhraseRegex.exec(input)) !== null) {
    exactPhrases.push(match[1]);
    remaining = remaining.replace(match[0], '');
  }

  result.exactPhrases = exactPhrases;

  const tokens = remaining.split(/\s+/).filter(Boolean);

  for (const token of tokens) {
    if (token.startsWith('title:')) {
      const term = token.slice(6).trim();
      if (term) result.titleTerms.push(term);
    } else if (token.startsWith('tag:')) {
      const term = token.slice(4).trim();
      if (term) result.tagTerms.push(term);
    } else if (token) {
      result.freeTerms.push(token);
    }
  }

  return result;
}

function buildSearchQuery(parsed: ParsedQuery): string {
  const parts: string[] = [];
  parts.push(...parsed.freeTerms);
  parts.push(...parsed.titleTerms);
  parts.push(...parsed.tagTerms);
  parts.push(...parsed.exactPhrases);
  return parts.join(' ');
}

function loadSearchHistory(): string[] {
  try {
    const stored = localStorage.getItem(HISTORY_KEY);
    return stored ? JSON.parse(stored) : [];
  } catch {
    return [];
  }
}

function saveSearchHistory(history: string[]): void {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history.slice(0, MAX_HISTORY)));
  } catch {
    // ignore
  }
}

export const SearchPage: React.FC = () => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [searchTime, setSearchTime] = useState(0);
  const [showFilters, setShowFilters] = useState(false);
  const [selectedIndex, setSelectedIndex] = useState(-1);
  const [searchHistory, setSearchHistory] = useState<string[]>(loadSearchHistory());
  const [showHistory, setShowHistory] = useState(false);
  const [filters, setFilters] = useState<SearchFilter>({
    tags: [],
    dateRange: { start: null, end: null },
    sortBy: 'relevance',
  });

  const inputRef = useRef<HTMLInputElement>(null);
  const resultsRef = useRef<HTMLDivElement>(null);
  const documents = useAppStore((state) => state.documents);
  const tags = useAppStore((state) => state.tags);
  const setCurrentDocument = useAppStore((state) => state.setCurrentDocument);
  const setActiveTab = useAppStore((state) => state.setActiveTab);

  const debouncedQuery = useDebounce(query, 300);
  const parsedQuery = useMemo(() => parseSearchQuery(query), [query]);

  const getWordCount = useCallback(
    (docId: string): number => {
      const doc = documents.find((d) => d.id === docId);
      return doc?.wordCount || 0;
    },
    [documents]
  );

  const performSearch = useCallback(async () => {
    const searchQuery = buildSearchQuery(parsedQuery);
    if (!searchQuery.trim() && filters.tags.length === 0) {
      setResults([]);
      setSearchTime(0);
      return;
    }

    setIsSearching(true);
    const startTime = performance.now();

    try {
      const searchOptions: {
        tags?: string[];
        sortBy?: 'relevance' | 'date';
        limit?: number;
      } = {
        limit: 100,
      };

      const allTags = [...filters.tags, ...parsedQuery.tagTerms];
      if (allTags.length > 0) {
        searchOptions.tags = allTags;
      }

      if (filters.sortBy === 'date') {
        searchOptions.sortBy = 'date';
      } else {
        searchOptions.sortBy = 'relevance';
      }

      const response = await window.electron.ipc.invoke<IPCResponse<SearchResult[]>>(
        IPC_CHANNELS.SEARCH.QUERY,
        searchQuery,
        searchOptions
      );

      if (response.success) {
        let filteredResults = response.data;

        if (filters.dateRange.start || filters.dateRange.end) {
          filteredResults = filteredResults.filter((result) => {
            const updatedAt = new Date(result.updatedAt).getTime();
            if (filters.dateRange.start) {
              const startDate = new Date(filters.dateRange.start).getTime();
              if (updatedAt < startDate) return false;
            }
            if (filters.dateRange.end) {
              const endDate = new Date(filters.dateRange.end).getTime();
              endDate.setHours(23, 59, 59, 999);
              if (updatedAt > endDate) return false;
            }
            return true;
          });
        }

        if (parsedQuery.titleTerms.length > 0) {
          filteredResults = filteredResults.filter((result) => {
            const lowerTitle = result.title.toLowerCase();
            return parsedQuery.titleTerms.some((term) =>
              lowerTitle.includes(term.toLowerCase())
            );
          });
        }

        if (parsedQuery.exactPhrases.length > 0) {
          filteredResults = filteredResults.filter((result) => {
            const titleLower = result.title.toLowerCase();
            const highlights = result.highlights.join(' ').toLowerCase();
            return parsedQuery.exactPhrases.some(
              (phrase) =>
                titleLower.includes(phrase.toLowerCase()) ||
                highlights.includes(phrase.toLowerCase())
            );
          });
        }

        if (filters.sortBy === 'title') {
          filteredResults.sort((a, b) => a.title.localeCompare(b.title, 'zh-CN'));
        } else if (filters.sortBy === 'relevance') {
          filteredResults.sort((a, b) => {
            const titleMatchBonusA = parsedQuery.freeTerms.some((term) =>
              a.title.toLowerCase().includes(term.toLowerCase())
            )
              ? 100
              : 0;
            const titleMatchBonusB = parsedQuery.freeTerms.some((term) =>
              b.title.toLowerCase().includes(term.toLowerCase())
            )
              ? 100
              : 0;
            return b.score + titleMatchBonusB - (a.score + titleMatchBonusA);
          });
        }

        setResults(filteredResults);
        setSelectedIndex(filteredResults.length > 0 ? 0 : -1);
      } else {
        console.error('搜索失败:', response.error);
        setResults([]);
      }
    } catch (error) {
      console.error('搜索错误:', error);
      setResults([]);
    } finally {
      setSearchTime(performance.now() - startTime);
      setIsSearching(false);
    }
  }, [parsedQuery, filters]);

  useEffect(() => {
    performSearch();
  }, [debouncedQuery, filters]);

  const addToHistory = useCallback(
    (searchQuery: string) => {
      if (!searchQuery.trim()) return;
      const newHistory = [searchQuery, ...searchHistory.filter((h) => h !== searchQuery)].slice(
        0,
        MAX_HISTORY
      );
      setSearchHistory(newHistory);
      saveSearchHistory(newHistory);
    },
    [searchHistory]
  );

  const handleSearch = useCallback(() => {
    if (query.trim()) {
      addToHistory(query.trim());
    }
    setShowHistory(false);
  }, [query, addToHistory]);

  const handleResultClick = useCallback(
    async (result: SearchResult) => {
      addToHistory(query.trim());
      await setCurrentDocument(result.id);
      setActiveTab('editor');
    },
    [query, addToHistory, setCurrentDocument, setActiveTab]
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') {
        setQuery('');
        setSelectedIndex(-1);
        inputRef.current?.focus();
        return;
      }

      if (showHistory && searchHistory.length > 0) {
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          setSelectedIndex((prev) =>
            prev < searchHistory.length - 1 ? prev + 1 : 0
          );
          return;
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault();
          setSelectedIndex((prev) =>
            prev > 0 ? prev - 1 : searchHistory.length - 1
          );
          return;
        }
        if (e.key === 'Enter' && selectedIndex >= 0) {
          e.preventDefault();
          setQuery(searchHistory[selectedIndex]);
          setShowHistory(false);
          return;
        }
      }

      if (results.length > 0) {
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          setSelectedIndex((prev) =>
            prev < results.length - 1 ? prev + 1 : 0
          );
          return;
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault();
          setSelectedIndex((prev) =>
            prev > 0 ? prev - 1 : results.length - 1
          );
          return;
        }
        if (e.key === 'Enter' && selectedIndex >= 0) {
          e.preventDefault();
          handleResultClick(results[selectedIndex]);
          return;
        }
      }

      if (e.key === 'Enter' && !showHistory && results.length === 0) {
        handleSearch();
      }
    },
    [results, selectedIndex, showHistory, searchHistory, handleResultClick, handleSearch]
  );

  useEffect(() => {
    if (selectedIndex >= 0 && resultsRef.current) {
      const element = resultsRef.current.querySelector(
        `[data-result-index="${selectedIndex}"]`
      );
      if (element) {
        element.scrollIntoView({ block: 'nearest' });
      }
    }
  }, [selectedIndex]);

  const toggleTagFilter = (tag: string) => {
    setFilters((prev) => ({
      ...prev,
      tags: prev.tags.includes(tag)
        ? prev.tags.filter((t) => t !== tag)
        : [...prev.tags, tag],
    }));
  };

  const clearFilters = () => {
    setFilters({
      tags: [],
      dateRange: { start: null, end: null },
      sortBy: 'relevance',
    });
  };

  const clearHistory = () => {
    setSearchHistory([]);
    saveSearchHistory([]);
  };

  const handleHistoryClick = (historyItem: string) => {
    setQuery(historyItem);
    setShowHistory(false);
    inputRef.current?.focus();
  };

  const hasActiveFilters =
    filters.tags.length > 0 ||
    filters.dateRange.start !== null ||
    filters.dateRange.end !== null ||
    filters.sortBy !== 'relevance';

  return (
    <div className="h-full flex flex-col bg-white dark:bg-gray-900">
      <div className="p-4 border-b border-gray-200 dark:border-gray-700">
        <div className="max-w-4xl mx-auto">
          <div className="relative">
            <div className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400">
              <svg
                className="w-5 h-5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                />
              </svg>
            </div>
            <input
              ref={inputRef}
              type="text"
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setShowHistory(true);
                setSelectedIndex(-1);
              }}
              onKeyDown={handleKeyDown}
              onFocus={() => setShowHistory(true)}
              onBlur={() => setTimeout(() => setShowHistory(false), 200)}
              placeholder="搜索文档... 支持 title:标题 tag:标签 '精确匹配'"
              className="w-full pl-10 pr-20 py-3 text-base border border-gray-200 dark:border-gray-700 rounded-lg bg-gray-50 dark:bg-gray-800 text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              autoFocus
            />
            <div className="absolute right-3 top-1/2 -translate-y-1/2 flex items-center gap-2">
              {query && (
                <button
                  onClick={() => {
                    setQuery('');
                    setResults([]);
                    inputRef.current?.focus();
                  }}
                  className="btn-icon p-1 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
                  title="清除搜索"
                >
                  <svg
                    className="w-4 h-4"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </button>
              )}
              <button
                onClick={() => setShowFilters(!showFilters)}
                className={`btn-icon p-1 ${
                  showFilters || hasActiveFilters
                    ? 'text-blue-500'
                    : 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-300'
                }`}
                title="高级筛选"
              >
                <svg
                  className="w-4 h-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z"
                  />
                </svg>
              </button>
            </div>

            {showHistory && searchHistory.length > 0 && !query && (
              <div className="absolute top-full left-0 right-0 mt-1 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg shadow-lg z-50 overflow-hidden">
                <div className="px-3 py-2 text-xs text-gray-500 dark:text-gray-400 bg-gray-50 dark:bg-gray-900 flex justify-between items-center">
                  <span>搜索历史</span>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      clearHistory();
                    }}
                    className="text-blue-500 hover:text-blue-600"
                  >
                    清空
                  </button>
                </div>
                <div className="max-h-60 overflow-y-auto">
                  {searchHistory.map((item, index) => (
                    <div
                      key={index}
                      onClick={() => handleHistoryClick(item)}
                      className={`px-3 py-2 cursor-pointer flex items-center gap-2 ${
                        selectedIndex === index
                          ? 'bg-blue-50 dark:bg-blue-900/30'
                          : 'hover:bg-gray-50 dark:hover:bg-gray-700'
                      }`}
                    >
                      <svg
                        className="w-4 h-4 text-gray-400"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                        />
                      </svg>
                      <span className="text-sm truncate">{item}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>

          {(parsedQuery.titleTerms.length > 0 ||
            parsedQuery.tagTerms.length > 0 ||
            parsedQuery.exactPhrases.length > 0) && (
            <div className="mt-2 flex flex-wrap gap-2">
              {parsedQuery.titleTerms.map((term, i) => (
                <span key={`title-${i}`} className="badge badge-primary">
                  标题: {term}
                </span>
              ))}
              {parsedQuery.tagTerms.map((term, i) => (
                <span key={`tag-${i}`} className="badge badge-success">
                  标签: {term}
                </span>
              ))}
              {parsedQuery.exactPhrases.map((phrase, i) => (
                <span key={`exact-${i}`} className="badge badge-warning">
                  "{phrase}"
                </span>
              ))}
            </div>
          )}

          {showFilters && (
            <div className="mt-4 p-4 bg-gray-50 dark:bg-gray-800/50 rounded-lg border border-gray-200 dark:border-gray-700">
              <div className="flex items-center justify-between mb-3">
                <h3 className="font-medium text-gray-900 dark:text-gray-100">高级筛选</h3>
                {hasActiveFilters && (
                  <button
                    onClick={clearFilters}
                    className="text-sm text-blue-500 hover:text-blue-600"
                  >
                    清除所有
                  </button>
                )}
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                    标签筛选
                  </label>
                  <div className="flex flex-wrap gap-2">
                    {tags.length > 0 ? (
                      tags.map((tag) => (
                        <button
                          key={tag.id}
                          onClick={() => toggleTagFilter(tag.name)}
                          className={`badge cursor-pointer transition-colors ${
                            filters.tags.includes(tag.name)
                              ? 'badge-primary'
                              : 'bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-600'
                          }`}
                        >
                          #{tag.name}
                          <span className="ml-1 opacity-60">{tag.documentCount}</span>
                        </button>
                      ))
                    ) : (
                      <span className="text-sm text-gray-500 dark:text-gray-400">
                        暂无标签
                      </span>
                    )}
                  </div>
                </div>

                <div>
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                    时间范围
                  </label>
                  <div className="flex items-center gap-2">
                    <input
                      type="date"
                      value={filters.dateRange.start || ''}
                      onChange={(e) =>
                        setFilters((prev) => ({
                          ...prev,
                          dateRange: { ...prev.dateRange, start: e.target.value || null },
                        }))
                      }
                      className="input flex-1 text-sm"
                    />
                    <span className="text-gray-500">至</span>
                    <input
                      type="date"
                      value={filters.dateRange.end || ''}
                      onChange={(e) =>
                        setFilters((prev) => ({
                          ...prev,
                          dateRange: { ...prev.dateRange, end: e.target.value || null },
                        }))
                      }
                      className="input flex-1 text-sm"
                    />
                  </div>
                </div>

                <div className="md:col-span-2">
                  <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                    排序方式
                  </label>
                  <div className="flex gap-2">
                    {[
                      { value: 'relevance', label: '相关度' },
                      { value: 'date', label: '最近编辑' },
                      { value: 'title', label: '标题' },
                    ].map((option) => (
                      <button
                        key={option.value}
                        onClick={() =>
                          setFilters((prev) => ({
                            ...prev,
                            sortBy: option.value as SearchFilter['sortBy'],
                          }))
                        }
                        className={`btn ${
                          filters.sortBy === option.value
                            ? 'btn-primary'
                            : 'btn-secondary'
                        }`}
                      >
                        {option.label}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          )}

          <div className="mt-3 flex items-center justify-between text-sm">
            <div className="text-gray-500 dark:text-gray-400">
              {isSearching ? (
                <span className="flex items-center gap-1">
                  <svg
                    className="w-4 h-4 animate-spin"
                    fill="none"
                    viewBox="0 0 24 24"
                  >
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                    />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                    />
                  </svg>
                  搜索中...
                </span>
              ) : query || filters.tags.length > 0 ? (
                <span>
                  找到{' '}
                  <span className="font-medium text-gray-900 dark:text-gray-100">
                    {results.length}
                  </span>{' '}
                  个匹配结果
                  {searchTime > 0 && (
                    <span className="ml-1">({searchTime.toFixed(0)}ms)</span>
                  )}
                </span>
              ) : (
                <span>输入关键词开始搜索</span>
              )}
            </div>
            <div className="flex items-center gap-4 text-gray-400">
              <span className="flex items-center gap-1">
                <kbd className="px-1.5 py-0.5 text-xs bg-gray-100 dark:bg-gray-700 rounded">
                  ↑↓
                </kbd>
                导航
              </span>
              <span className="flex items-center gap-1">
                <kbd className="px-1.5 py-0.5 text-xs bg-gray-100 dark:bg-gray-700 rounded">
                  Enter
                </kbd>
                打开
              </span>
              <span className="flex items-center gap-1">
                <kbd className="px-1.5 py-0.5 text-xs bg-gray-100 dark:bg-gray-700 rounded">
                  Esc
                </kbd>
                清空
              </span>
            </div>
          </div>
        </div>
      </div>

      <div ref={resultsRef} className="flex-1 overflow-y-auto">
        <div className="max-w-4xl mx-auto p-4">
          {results.length > 0 ? (
            <div className="space-y-3">
              {results.map((result, index) => {
                const wordCount = getWordCount(result.id);
                return (
                  <div
                    key={result.id}
                    data-result-index={index}
                    onClick={() => handleResultClick(result)}
                    className={`card p-4 cursor-pointer transition-all ${
                      selectedIndex === index
                        ? 'border-blue-400 dark:border-blue-500 ring-2 ring-blue-100 dark:ring-blue-900/30'
                        : 'hover:border-blue-300 dark:hover:border-blue-600 hover:shadow-md'
                    }`}
                  >
                    <div className="flex items-start justify-between gap-4">
                      <div className="flex-1 min-w-0">
                        <h3
                          className="font-medium text-gray-900 dark:text-gray-100 mb-1 truncate"
                          dangerouslySetInnerHTML={{ __html: result.title }}
                        />
                        {result.highlights.length > 0 && (
                          <p
                            className="text-sm text-gray-600 dark:text-gray-400 line-clamp-2 mb-2"
                            dangerouslySetInnerHTML={{
                              __html: result.highlights[0],
                            }}
                          />
                        )}
                        <div className="flex items-center gap-3 text-xs text-gray-500 dark:text-gray-400">
                          <span className="flex items-center gap-1">
                            <svg
                              className="w-3.5 h-3.5"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                              />
                            </svg>
                            {formatRelative(result.updatedAt)}
                          </span>
                          <span className="flex items-center gap-1">
                            <svg
                              className="w-3.5 h-3.5"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                              />
                            </svg>
                            {wordCount.toLocaleString()} 字
                          </span>
                          {result.tags.length > 0 && (
                            <div className="flex gap-1">
                              {result.tags.slice(0, 3).map((tag) => (
                                <span
                                  key={tag}
                                  className="badge badge-primary text-[10px]"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    toggleTagFilter(tag);
                                  }}
                                >
                                  #{tag}
                                </span>
                              ))}
                              {result.tags.length > 3 && (
                                <span className="text-gray-400">
                                  +{result.tags.length - 3}
                                </span>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                      <div className="flex items-center gap-2 flex-shrink-0">
                        <span className="text-xs text-gray-400">
                          #{index + 1}
                        </span>
                        <svg
                          className="w-4 h-4 text-gray-400"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M9 5l7 7-7 7"
                          />
                        </svg>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : !isSearching && (query || filters.tags.length > 0) ? (
            <div className="text-center py-16">
              <div className="text-6xl mb-4">🔍</div>
              <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-2">
                未找到匹配的文档
              </h3>
              <p className="text-gray-500 dark:text-gray-400">
                尝试使用不同的关键词或调整筛选条件
              </p>
              {hasActiveFilters && (
                <button
                  onClick={clearFilters}
                  className="mt-4 btn btn-primary"
                >
                  清除筛选条件
                </button>
              )}
            </div>
          ) : (
            <div className="text-center py-16">
              <div className="text-6xl mb-4">📚</div>
              <h3 className="text-lg font-medium text-gray-900 dark:text-gray-100 mb-2">
                搜索您的知识库
              </h3>
              <p className="text-gray-500 dark:text-gray-400 mb-6">
                支持中英文混合搜索，快速定位所需内容
              </p>
              <div className="max-w-md mx-auto text-left bg-gray-50 dark:bg-gray-800/50 p-4 rounded-lg">
                <h4 className="font-medium text-gray-900 dark:text-gray-100 mb-3">
                  搜索语法
                </h4>
                <ul className="space-y-2 text-sm text-gray-600 dark:text-gray-400">
                  <li className="flex items-start gap-2">
                    <code className="px-1.5 py-0.5 bg-gray-200 dark:bg-gray-700 rounded text-xs flex-shrink-0">
                      title:关键词
                    </code>
                    <span>仅搜索文档标题</span>
                  </li>
                  <li className="flex items-start gap-2">
                    <code className="px-1.5 py-0.5 bg-gray-200 dark:bg-gray-700 rounded text-xs flex-shrink-0">
                      tag:标签名
                    </code>
                    <span>按标签筛选文档</span>
                  </li>
                  <li className="flex items-start gap-2">
                    <code className="px-1.5 py-0.5 bg-gray-200 dark:bg-gray-700 rounded text-xs flex-shrink-0">
                      "精确短语"
                    </code>
                    <span>精确匹配完整短语</span>
                  </li>
                </ul>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
