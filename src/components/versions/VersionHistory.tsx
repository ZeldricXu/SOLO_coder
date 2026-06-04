'use client';

import React, { useState, useMemo } from 'react';
import {
  Clock,
  User,
  GitCompare,
  RotateCcw,
  Eye,
  ChevronLeft,
  ChevronRight,
  CheckCircle2,
  XCircle,
  History,
} from 'lucide-react';
import type { Version, VersionListResult } from '@/lib/types/version';
import { useVersionList } from '@/hooks/useVersions';
import { cn, formatTimeAgo, getInitials } from '@/lib/utils';
import { formatDiffStat } from '@/lib/diff/utils';

interface VersionHistoryProps {
  documentId: string;
  currentVersion?: number;
  onVersionSelect?: (version: Version) => void;
  onCompare?: (versionFrom: number, versionTo: number) => void;
  onRollback?: (version: Version) => void;
  onRestore?: (versionId: string) => void;
  onPreview?: (version: Version) => void;
  onClose?: () => void;
  className?: string;
}

export function VersionHistory({
  documentId,
  currentVersion,
  onVersionSelect,
  onCompare,
  onRollback,
  onRestore,
  onPreview,
  onClose,
  className,
}: VersionHistoryProps) {
  const [page, setPage] = useState(1);
  const [pageSize] = useState(20);
  const [compareMode, setCompareMode] = useState(false);
  const [selectedForCompare, setSelectedForCompare] = useState<number[]>([]);
  const [sortBy, setSortBy] = useState<'version' | 'createdAt'>('version');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');

  const { data, isLoading, isError } = useVersionList({
    documentId,
    page,
    pageSize,
  });

  const versions = data?.items ?? [];
  const totalPages = data?.totalPages ?? 1;
  const total = data?.total ?? 0;

  const sortedVersions = useMemo(() => {
    const sorted = [...versions];
    sorted.sort((a, b) => {
      if (sortBy === 'version') {
        return sortOrder === 'desc'
          ? b.version - a.version
          : a.version - b.version;
      }
      return sortOrder === 'desc'
        ? new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
        : new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
    });
    return sorted;
  }, [versions, sortBy, sortOrder]);

  const handleCompareSelect = (version: number) => {
    if (!compareMode) return;

    setSelectedForCompare((prev) => {
      if (prev.includes(version)) {
        return prev.filter((v) => v !== version);
      }
      if (prev.length >= 2) {
        return [prev[1], version];
      }
      return [...prev, version];
    });
  };

  const handleCompare = () => {
    if (selectedForCompare.length === 2) {
      const [v1, v2] = selectedForCompare.sort((a, b) => a - b);
      onCompare?.(v1, v2);
      setCompareMode(false);
      setSelectedForCompare([]);
    }
  };

  const toggleSortOrder = () => {
    setSortOrder((prev) => (prev === 'desc' ? 'asc' : 'desc'));
  };

  if (isLoading) {
    return (
      <div className={cn('p-8 text-center', className)}>
        <div className="animate-spin w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full mx-auto mb-4" />
        <p className="text-gray-500">加载版本历史...</p>
      </div>
    );
  }

  if (isError) {
    return (
      <div className={cn('p-8 text-center', className)}>
        <XCircle className="w-12 h-12 text-red-500 mx-auto mb-4" />
        <p className="text-red-600 mb-4">加载版本历史失败</p>
        <button
          onClick={() => window.location.reload()}
          className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700"
        >
          重试
        </button>
      </div>
    );
  }

  return (
    <div className={cn('flex flex-col h-full bg-white rounded-lg border', className)}>
      <div className="p-4 border-b bg-gray-50">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <History className="w-5 h-5 text-gray-600" />
            <h3 className="font-semibold text-gray-900">版本历史</h3>
            <span className="text-sm text-gray-500">({total} 个版本)</span>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => {
                setCompareMode(!compareMode);
                setSelectedForCompare([]);
              }}
              className={cn(
                'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm transition-colors',
                compareMode
                  ? 'bg-blue-100 text-blue-700 border border-blue-300'
                  : 'bg-white border text-gray-700 hover:bg-gray-50'
              )}
            >
              <GitCompare className="w-4 h-4" />
              {compareMode ? '取消对比' : '对比版本'}
            </button>
          </div>
        </div>

        {compareMode && (
          <div className="mb-4 p-3 bg-blue-50 border border-blue-200 rounded-md">
            <div className="flex items-center justify-between">
              <div className="text-sm text-blue-700">
                已选择 {selectedForCompare.length}/2 个版本进行对比
                {selectedForCompare.length === 2 && (
                  <span className="ml-2 text-blue-600 font-medium">
                    (v{selectedForCompare[0]} → v{selectedForCompare[1]})
                  </span>
                )}
              </div>
              <button
                onClick={handleCompare}
                disabled={selectedForCompare.length !== 2}
                className="px-3 py-1.5 bg-blue-600 text-white text-sm rounded-md hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                开始对比
              </button>
            </div>
          </div>
        )}

        <div className="flex items-center gap-2">
          <span className="text-sm text-gray-500">排序：</span>
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as 'version' | 'createdAt')}
            className="px-2 py-1 text-sm border rounded-md bg-white"
          >
            <option value="version">版本号</option>
            <option value="createdAt">创建时间</option>
          </select>
          <button
            onClick={toggleSortOrder}
            className="p-1 hover:bg-gray-100 rounded"
            title={sortOrder === 'desc' ? '降序' : '升序'}
          >
            {sortOrder === 'desc' ? (
              <ChevronRight className="w-4 h-4 text-gray-500 rotate-90" />
            ) : (
              <ChevronLeft className="w-4 h-4 text-gray-500 -rotate-90" />
            )}
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        {sortedVersions.length === 0 ? (
          <div className="p-8 text-center text-gray-500">
            <Clock className="w-12 h-12 text-gray-300 mx-auto mb-4" />
            <p>暂无版本记录</p>
          </div>
        ) : (
          <div className="relative">
            <div className="absolute left-6 top-0 bottom-0 w-0.5 bg-gray-200" />

            <div className="space-y-1 p-4">
              {sortedVersions.map((version, index) => {
                const isCurrent = currentVersion === version.version;
                const isSelected = selectedForCompare.includes(version.version);
                const isFirst = index === 0;

                return (
                  <div
                    key={version.id}
                    className={cn(
                      'relative pl-12 pr-2 py-3 rounded-lg transition-all cursor-pointer group',
                      isCurrent && 'bg-blue-50 border border-blue-200',
                      !isCurrent && 'hover:bg-gray-50',
                      isSelected && 'ring-2 ring-blue-500 bg-blue-100',
                      compareMode && isSelected && 'cursor-pointer'
                    )}
                    onClick={() => {
                      if (compareMode) {
                        handleCompareSelect(version.version);
                      } else {
                        onVersionSelect?.(version);
                      }
                    }}
                  >
                    <div
                      className={cn(
                        'absolute left-4 top-4 w-4 h-4 rounded-full border-2 bg-white',
                        isCurrent
                          ? 'border-blue-500 bg-blue-500'
                          : 'border-gray-300 group-hover:border-blue-400',
                        isFirst && 'border-green-500 bg-green-500'
                      )}
                    >
                      {isCurrent && (
                        <CheckCircle2 className="w-3 h-3 text-white -translate-y-0.5 -translate-x-0.5" />
                      )}
                    </div>

                    <div className="flex items-start justify-between gap-4">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-1">
                          <span
                            className={cn(
                              'px-2 py-0.5 text-xs font-semibold rounded',
                              isCurrent
                                ? 'bg-blue-500 text-white'
                                : isFirst
                                  ? 'bg-green-100 text-green-700'
                                  : 'bg-gray-100 text-gray-700'
                            )}
                          >
                            v{version.version}
                          </span>
                          {isCurrent && (
                            <span className="text-xs text-blue-600 font-medium">
                              当前版本
                            </span>
                          )}
                          {isFirst && !isCurrent && (
                            <span className="text-xs text-green-600 font-medium">
                              最新
                            </span>
                          )}
                        </div>

                        <h4 className="font-medium text-gray-900 truncate mb-1">
                          {version.title}
                        </h4>

                        {version.message && (
                          <p className="text-sm text-gray-600 mb-2 line-clamp-2">
                            {version.message}
                          </p>
                        )}

                        <div className="flex items-center gap-4 text-xs text-gray-500">
                          <div className="flex items-center gap-1">
                            <User className="w-3 h-3" />
                            {version.createdBy?.avatar ? (
                              <img
                                src={version.createdBy.avatar}
                                alt={version.createdBy.name}
                                className="w-4 h-4 rounded-full"
                              />
                            ) : (
                              <div className="w-4 h-4 rounded-full bg-gray-200 flex items-center justify-center text-[10px] font-medium">
                                {getInitials(version.createdBy?.name ?? '?')}
                              </div>
                            )}
                            <span>{version.createdBy?.name ?? '未知用户'}</span>
                          </div>
                          <div className="flex items-center gap-1">
                            <Clock className="w-3 h-3" />
                            <span>{formatTimeAgo(version.createdAt)}</span>
                          </div>
                        </div>
                      </div>

                      {!compareMode && (
                        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                          {onPreview && (
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                onPreview(version);
                              }}
                              className="p-1.5 hover:bg-white rounded text-gray-500 hover:text-blue-600"
                              title="预览版本"
                            >
                              <Eye className="w-4 h-4" />
                            </button>
                          )}
                          {onRollback && !isCurrent && (
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                onRollback(version);
                              }}
                              className="p-1.5 hover:bg-white rounded text-gray-500 hover:text-orange-600"
                              title="回滚到此版本"
                            >
                              <RotateCcw className="w-4 h-4" />
                            </button>
                          )}
                          {onCompare && (
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                setCompareMode(true);
                                setSelectedForCompare([version.version]);
                              }}
                              className="p-1.5 hover:bg-white rounded text-gray-500 hover:text-purple-600"
                              title="选择对比"
                            >
                              <GitCompare className="w-4 h-4" />
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-between px-4 py-3 border-t bg-gray-50">
          <div className="text-sm text-gray-500">
            第 {page} / {totalPages} 页，共 {total} 个版本
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              disabled={page === 1}
              className="p-1.5 border rounded hover:bg-white disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <ChevronLeft className="w-4 h-4" />
            </button>
            <button
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              disabled={page === totalPages}
              className="p-1.5 border rounded hover:bg-white disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
