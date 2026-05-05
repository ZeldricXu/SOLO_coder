import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Search, X, FileText, Folder, ArrowRight, Calendar, User } from 'lucide-react';
import { searchApi } from '../lib/api';

function SearchResult({ result, onSelect, onClose }) {
  const isDocument = !result.folder_id || result.type === 'document';

  const handleClick = () => {
    if (onSelect) {
      onSelect(result);
    }
    if (onClose) {
      onClose();
    }
  };

  return (
    <div
      onClick={handleClick}
      className="flex items-start gap-3 p-3 rounded-lg cursor-pointer hover:bg-slate-50 transition-colors group"
    >
      <div className={`p-2 rounded ${
        isDocument ? 'bg-blue-50' : 'bg-yellow-50'
      }`}>
        {isDocument ? (
          <FileText size={16} className="text-blue-600" />
        ) : (
          <Folder size={16} className="text-yellow-600" />
        )}
      </div>

      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <h4 className="text-sm font-medium text-slate-900 truncate">
            {result.title || result.name || 'Untitled'}
          </h4>
          {result.match_score && (
            <span className="text-xs text-slate-400">
              匹配度: {(result.match_score * 100).toFixed(0)}%
            </span>
          )}
        </div>

        {result.snippet && (
          <p
            className="text-xs text-slate-600 mt-1 line-clamp-2"
            dangerouslySetInnerHTML={{ __html: result.snippet }}
          />
        )}

        <div className="flex items-center gap-4 mt-2 text-xs text-slate-400">
          {result.last_edited_by && (
            <span className="flex items-center gap-1">
              <User size={12} />
              {result.last_edited_by}
            </span>
          )}
          {result.last_edited_at && (
            <span className="flex items-center gap-1">
              <Calendar size={12} />
              {new Date(result.last_edited_at).toLocaleDateString('zh-CN')}
            </span>
          )}
        </div>
      </div>

      <ArrowRight
        size={16}
        className="text-slate-300 group-hover:text-slate-500 opacity-0 group-hover:opacity-100 transition-all"
      />
    </div>
  );
}

function SearchComponent({ onSelectDocument, onSelectFolder, isOpen, onClose }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [history, setHistory] = useState([]);
  const inputRef = useRef(null);
  const timeoutRef = useRef(null);

  useEffect(() => {
    if (isOpen && inputRef.current) {
      inputRef.current.focus();
    }
  }, [isOpen]);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault();
        if (!isOpen) {
          onOpen?.();
        }
      }
      if (e.key === 'Escape' && isOpen) {
        onClose();
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose, onOpen]);

  const search = useCallback(async (searchQuery) => {
    if (!searchQuery.trim()) {
      setResults([]);
      return;
    }

    try {
      setLoading(true);
      const response = await searchApi.search(searchQuery);
      
      if (response.code === 200) {
        setResults(response.data?.results || []);
      }
    } catch (error) {
      console.error('Search failed:', error);
      setResults([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
    }

    if (query.trim()) {
      timeoutRef.current = setTimeout(() => {
        search(query);
      }, 300);
    } else {
      setResults([]);
    }

    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, [query, search]);

  const handleSelect = (result) => {
    if (result.type === 'folder' || result.folder_id && !result.doc_id) {
      if (onSelectFolder) {
        onSelectFolder(result);
      }
    } else {
      if (onSelectDocument) {
        onSelectDocument(result);
      }
    }
    
    setHistory(prev => {
      const filtered = prev.filter(h => h.doc_id !== result.doc_id && h.folder_id !== result.folder_id);
      return [result, ...filtered].slice(0, 5);
    });

    setQuery('');
    setResults([]);
  };

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center pt-24 bg-black/30"
      onClick={onClose}
    >
      <div
        className="w-full max-w-2xl bg-white rounded-xl shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-3 px-4 py-3 border-b border-slate-200">
          <Search size={20} className="text-slate-400" />
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索文档、文件夹... (Ctrl+K)"
            className="flex-1 outline-none text-slate-900 placeholder-slate-400"
          />
          {loading && (
            <div className="w-5 h-5 border-2 border-slate-200 border-t-primary-500 rounded-full animate-spin" />
          )}
          {query && (
            <button
              onClick={() => {
                setQuery('');
                setResults([]);
              }}
              className="p-1 rounded hover:bg-slate-100"
            >
              <X size={16} className="text-slate-400" />
            </button>
          )}
          <kbd className="hidden sm:inline-block px-2 py-1 text-xs text-slate-500 bg-slate-100 rounded">
            ESC
          </kbd>
        </div>

        <div className="max-h-96 overflow-auto">
          {!query && history.length > 0 ? (
            <div>
              <div className="px-4 py-2 text-xs font-medium text-slate-400 bg-slate-50">
                最近搜索
              </div>
              {history.map((item, index) => (
                <SearchResult
                  key={`history-${index}`}
                  result={item}
                  onSelect={handleSelect}
                  onClose={onClose}
                />
              ))}
            </div>
          ) : !query && history.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <Search size={48} className="text-slate-200 mb-4" />
              <p className="text-sm text-slate-500">输入关键词开始搜索</p>
              <p className="text-xs text-slate-400 mt-1">支持标题和内容全文搜索</p>
            </div>
          ) : results.length === 0 && !loading ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <Search size={48} className="text-slate-200 mb-4" />
              <p className="text-sm text-slate-500">未找到匹配的结果</p>
              <p className="text-xs text-slate-400 mt-1">请尝试其他关键词</p>
            </div>
          ) : (
            <div>
              {results.length > 0 && (
                <div className="px-4 py-2 text-xs font-medium text-slate-400 bg-slate-50">
                  找到 {results.length} 个结果
                </div>
              )}
              {results.map((result, index) => (
                <SearchResult
                  key={result.doc_id || result.folder_id || index}
                  result={result}
                  onSelect={handleSelect}
                  onClose={onClose}
                />
              ))}
            </div>
          )}
        </div>

        <div className="flex items-center justify-between px-4 py-2 border-t border-slate-200 bg-slate-50">
          <div className="flex items-center gap-4 text-xs text-slate-400">
            <span className="flex items-center gap-1">
              <kbd className="px-1.5 py-0.5 bg-white border border-slate-200 rounded text-slate-500">↑↓</kbd>
              选择
            </span>
            <span className="flex items-center gap-1">
              <kbd className="px-1.5 py-0.5 bg-white border border-slate-200 rounded text-slate-500">Enter</kbd>
              打开
            </span>
            <span className="flex items-center gap-1">
              <kbd className="px-1.5 py-0.5 bg-white border border-slate-200 rounded text-slate-500">ESC</kbd>
              关闭
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}

export default SearchComponent;
