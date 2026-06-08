import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Search, X, Download, Loader2, Grid3X3, Filter, ChevronLeft, ChevronRight, Layers } from 'lucide-react';
import type { SketchfabModel, SketchfabSearchParams } from '@/types/sketchfab';
import { SKETCHFAB_CATEGORIES } from '@/types/sketchfab';
import { sketchfabAPI } from '@/services/sketchfabAPI';
import { sketchfabLoader } from '@/services/sketchfabLoader';
import { useUIStore } from '@/store/useUIStore';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';

const PRESET_COLORS = ['#ff6b35', '#00d4ff', '#ffc107', '#4caf50', '#9c27b0'];

export const SketchfabBrowser: React.FC = () => {
  const { panels, setPanel } = useUIStore();
  const { addFurniture } = useFloorPlanStore();

  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('');
  const [sortBy, setSortBy] = useState<SketchfabSearchParams['sort_by']>('downloads');
  const [downloadableOnly, setDownloadableOnly] = useState(true);
  const [results, setResults] = useState<SketchfabModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [prevCursor, setPrevCursor] = useState<string | null>(null);
  const [selectedModel, setSelectedModel] = useState<SketchfabModel | null>(null);
  const [importing, setImporting] = useState(false);
  const [page, setPage] = useState(1);
  const searchTimeoutRef = useRef<number | null>(null);

  const fetchModels = useCallback(
    async (cursor?: string) => {
      setLoading(true);
      setError(null);
      try {
        const params: SketchfabSearchParams = {
          q: query || undefined,
          categories: category || undefined,
          downloadable: downloadableOnly,
          sort_by: sortBy,
          per_page: 12,
        };
        if (cursor) params.cursor = cursor;

        const response = await sketchfabAPI.searchModels(params);
        setResults(response.results);
        setNextCursor(response.cursors.next || null);
        setPrevCursor(response.cursors.previous || null);
      } catch (err: any) {
        setError(err.message || '搜索失败，请稍后重试');
        setResults([]);
      } finally {
        setLoading(false);
      }
    },
    [query, category, downloadableOnly, sortBy]
  );

  useEffect(() => {
    if (panels.sketchfabBrowser) {
      fetchModels();
    }
  }, [panels.sketchfabBrowser]);

  useEffect(() => {
    if (searchTimeoutRef.current) {
      window.clearTimeout(searchTimeoutRef.current);
    }
    searchTimeoutRef.current = window.setTimeout(() => {
      setPage(1);
      fetchModels();
    }, 400);
    return () => {
      if (searchTimeoutRef.current) window.clearTimeout(searchTimeoutRef.current);
    };
  }, [query, category, downloadableOnly, sortBy, fetchModels]);

  const handleImport = async (model: SketchfabModel) => {
    setImporting(true);
    setSelectedModel(model);
    try {
      const result = await sketchfabLoader.loadFromSketchfab(model.uid, {
        autoScale: true,
        targetSize: 1.5,
        centerModel: true,
      });

      const size = result.scaledSize;
      addFurniture({
        modelId: `sketchfab:${model.uid}`,
        name: model.name,
        category: model.categories?.[0]?.name || 'sketchfab',
        position: { x: 2, y: 0, z: 2 },
        rotation: 0,
        scale: 1,
      });

      setPanel('sketchfabBrowser', false);
    } catch (err: any) {
      setError(err.message || '模型导入失败');
    } finally {
      setImporting(false);
      setSelectedModel(null);
    }
  };

  const formatNumber = (n: number) => {
    if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
    return String(n);
  };

  const getThumbnailUrl = (model: SketchfabModel) => {
    const images = model.thumbnails?.images;
    if (!images || images.length === 0) return '';
    const sorted = [...images].sort((a, b) => b.size - a.size);
    return sorted[0]?.url || '';
  };

  if (!panels.sketchfabBrowser) return null;

  return (
    <div className="w-96 bg-neutral-800 border-l border-neutral-700 flex flex-col animate-slide-in">
      <div className="flex items-center justify-between px-4 py-3 border-b border-neutral-700">
        <div className="flex items-center gap-2">
          <Grid3X3 size={18} className="text-accent-primary" />
          <h2 className="text-sm font-semibold text-white">Sketchfab 在线模型库</h2>
        </div>
        <button
          onClick={() => setPanel('sketchfabBrowser', false)}
          className="text-neutral-400 hover:text-white transition-colors"
        >
          <X size={16} />
        </button>
      </div>

      <div className="p-3 border-b border-neutral-700 space-y-3">
        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-500" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索3D模型..."
            className="w-full pl-9 pr-3 py-2 bg-neutral-700 border border-neutral-600 rounded text-white text-sm placeholder-neutral-500 focus:outline-none focus:border-accent-primary"
          />
        </div>

        <div className="flex gap-2">
          <div className="flex-1">
            <select
              value={category}
              onChange={(e) => {
                setCategory(e.target.value);
                setPage(1);
              }}
              className="w-full px-3 py-1.5 bg-neutral-700 border border-neutral-600 rounded text-white text-sm focus:outline-none focus:border-accent-primary"
            >
              {SKETCHFAB_CATEGORIES.map((c) => (
                <option key={c.slug} value={c.slug}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as SketchfabSearchParams['sort_by'])}
            className="px-3 py-1.5 bg-neutral-700 border border-neutral-600 rounded text-white text-sm focus:outline-none focus:border-accent-primary"
          >
            <option value="downloads">下载量</option>
            <option value="likes">点赞</option>
            <option value="views">浏览</option>
            <option value="recent">最新</option>
            <option value="relevance">相关度</option>
          </select>
        </div>

        <label className="flex items-center gap-2 text-xs text-neutral-300">
          <input
            type="checkbox"
            checked={downloadableOnly}
            onChange={(e) => setDownloadableOnly(e.target.checked)}
            className="accent-accent-primary"
          />
          仅显示可下载模型
        </label>
      </div>

      {error && (
        <div className="mx-3 mt-3 p-2 bg-red-500/20 border border-red-500/30 rounded text-xs text-red-400">
          {error}
        </div>
      )}

      <div className="flex-1 overflow-y-auto p-3">
        {loading ? (
          <div className="flex flex-col items-center justify-center py-20">
            <Loader2 size={24} className="text-accent-primary animate-spin mb-2" />
            <p className="text-sm text-neutral-400">正在搜索模型...</p>
          </div>
        ) : results.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-center">
            <Layers size={32} className="text-neutral-600 mb-2" />
            <p className="text-sm text-neutral-500">未找到匹配的模型</p>
            <p className="text-xs text-neutral-600 mt-1">试试修改搜索关键词或分类</p>
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-2">
            {results.map((model, idx) => (
              <div
                key={model.uid}
                className="group bg-neutral-700 rounded overflow-hidden hover:bg-neutral-600 transition-colors cursor-pointer"
                onClick={() => handleImport(model)}
              >
                <div className="aspect-square bg-neutral-900 relative overflow-hidden">
                  <img
                    src={getThumbnailUrl(model)}
                    alt={model.name}
                    className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-200"
                    onError={(e) => {
                      (e.target as HTMLImageElement).style.background =
                        PRESET_COLORS[idx % PRESET_COLORS.length];
                    }}
                  />
                  {selectedModel?.uid === model.uid && importing && (
                    <div className="absolute inset-0 bg-black/60 flex items-center justify-center">
                      <Loader2 size={20} className="text-white animate-spin" />
                    </div>
                  )}
                  {!model.isDownloadable && (
                    <div className="absolute top-1 right-1 px-1.5 py-0.5 bg-black/70 rounded text-[10px] text-neutral-400">
                      不可下载
                    </div>
                  )}
                </div>
                <div className="p-2">
                  <p className="text-xs text-white font-medium truncate" title={model.name}>
                    {model.name}
                  </p>
                  <div className="flex items-center justify-between mt-1">
                    <p className="text-[10px] text-neutral-500 truncate">
                      {model.user.displayName}
                    </p>
                    <div className="flex items-center gap-1 text-[10px] text-neutral-500">
                      <Download size={10} />
                      {formatNumber(model.viewCount)}
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="flex items-center justify-between px-3 py-2 border-t border-neutral-700">
        <button
          onClick={() => {
            if (prevCursor) {
              setPage((p) => p - 1);
              fetchModels(prevCursor);
            }
          }}
          disabled={!prevCursor || loading}
          className="flex items-center gap-1 px-3 py-1 bg-neutral-700 text-neutral-300 rounded text-xs hover:bg-neutral-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <ChevronLeft size={14} />
          上一页
        </button>
        <span className="text-xs text-neutral-500">第 {page} 页</span>
        <button
          onClick={() => {
            if (nextCursor) {
              setPage((p) => p + 1);
              fetchModels(nextCursor);
            }
          }}
          disabled={!nextCursor || loading}
          className="flex items-center gap-1 px-3 py-1 bg-neutral-700 text-neutral-300 rounded text-xs hover:bg-neutral-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          下一页
          <ChevronRight size={14} />
        </button>
      </div>
    </div>
  );
};
