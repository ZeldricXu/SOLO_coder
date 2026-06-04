'use client';

import React, { useState, useMemo, useCallback } from 'react';
import {
  SplitSquareVertical,
  AlignLeft,
  ChevronUp,
  ChevronDown,
  Settings,
  Download,
  Maximize2,
} from 'lucide-react';
import type { VersionDiff, DiffChunk, DiffViewMode, DiffStats } from '@/lib/types/version';
import { DiffLine } from './DiffLine';
import { formatDiffStat, getLineChanges } from '@/lib/diff/utils';
import { chunksToUnifiedDiff } from '@/lib/diff/DiffParser';
import { cn, formatTimeAgo } from '@/lib/utils';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';

interface VersionDiffViewerProps {
  diff: {
    versionFrom: {
      id: string;
      version: number;
      title: string;
      createdAt: Date;
      createdById: string;
      createdBy?: {
        id: string;
        name: string;
        email: string;
        avatar?: string | null;
      };
    };
    versionTo: {
      id: string;
      version: number;
      title: string;
      createdAt: Date;
      createdById: string;
      createdBy?: {
        id: string;
        name: string;
        email: string;
        avatar?: string | null;
      };
    };
    contentDiff: any[];
    titleDiff: any[];
    stats?: DiffStats;
    additions?: number;
    deletions?: number;
    changes?: number;
  };
  className?: string;
  viewMode?: DiffViewMode;
  defaultViewMode?: DiffViewMode;
  showOnlyChanges?: boolean;
  oldVersion?: string;
  newVersion?: string;
  onClose?: () => void;
  onRollback?: (targetVersion: number) => void;
  onViewModeChange?: (mode: DiffViewMode) => void;
}

export function VersionDiffViewer({
  diff,
  className,
  viewMode: controlledViewMode,
  defaultViewMode = 'split',
  showOnlyChanges = false,
  oldVersion,
  newVersion,
  onClose,
  onRollback,
  onViewModeChange,
}: VersionDiffViewerProps) {
  const [internalViewMode, setInternalViewMode] = useState<DiffViewMode>(defaultViewMode);
  const viewMode = controlledViewMode ?? internalViewMode;
  const setViewMode = (mode: DiffViewMode) => {
    setInternalViewMode(mode);
    onViewModeChange?.(mode);
  };
  const [showLineNumbers, setShowLineNumbers] = useState(true);
  const [showActions, setShowActions] = useState(true);
  const [ignoreWhitespace, setIgnoreWhitespace] = useState(false);
  const [currentDiffIndex, setCurrentDiffIndex] = useState(0);

  const changedLineNumbers = useMemo(() => {
    return getLineChanges(diff.contentDiff);
  }, [diff.contentDiff]);

  const allLines = useMemo(() => {
    return diff.contentDiff.flatMap((chunk) => chunk.lines);
  }, [diff.contentDiff]);

  const diffPositions = useMemo(() => {
    const positions: number[] = [];
    let globalIndex = 0;
    for (const chunk of diff.contentDiff) {
      for (let i = 0; i < chunk.lines.length; i++) {
        const line = chunk.lines[i];
        if (line.type !== 'unchanged') {
          positions.push(globalIndex);
        }
        globalIndex++;
      }
    }
    return positions;
  }, [diff.contentDiff]);

  const jumpToDiff = useCallback(
    (direction: 'prev' | 'next') => {
      if (diffPositions.length === 0) return;

      let newIndex: number;
      if (direction === 'next') {
        newIndex = (currentDiffIndex + 1) % diffPositions.length;
      } else {
        newIndex =
          (currentDiffIndex - 1 + diffPositions.length) % diffPositions.length;
      }

      setCurrentDiffIndex(newIndex);

      const targetLine = document.querySelector(
        `[data-diff-line="${diffPositions[newIndex]}"]`
      );
      if (targetLine) {
        targetLine.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    },
    [currentDiffIndex, diffPositions]
  );

  const handleDownloadDiff = () => {
    const unifiedDiff = chunksToUnifiedDiff(
      diff.contentDiff,
      `v${diff.versionFrom.version}`,
      `v${diff.versionTo.version}`
    );
    const blob = new Blob([unifiedDiff], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `v${diff.versionFrom.version}-v${diff.versionTo.version}.diff`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const renderTitleDiff = () => {
    return (
      <div className="p-4 bg-gray-50 border-b">
        <div className="text-sm text-gray-500 mb-1">标题变更</div>
        <div className="font-mono text-sm">
          {diff.titleDiff.map((change, index) => {
            if (change.added) {
              return (
                <span
                  key={index}
                  className="bg-green-200 text-green-900 px-0.5 rounded"
                >
                  {change.value}
                </span>
              );
            }
            if (change.removed) {
              return (
                <span
                  key={index}
                  className="bg-red-200 text-red-900 px-0.5 rounded line-through"
                >
                  {change.value}
                </span>
              );
            }
            return <span key={index}>{change.value}</span>;
          })}
        </div>
      </div>
    );
  };

  const renderChunk = (chunk: DiffChunk, chunkIndex: number) => {
    let globalLineIndex = 0;
    for (let i = 0; i < chunkIndex; i++) {
      globalLineIndex += diff.contentDiff[i].lines.length;
    }

    return (
      <React.Fragment key={chunkIndex}>
        <tr className="bg-blue-50">
          <td
            colSpan={viewMode === 'split' ? 3 : 3}
            className="px-3 py-1 text-xs text-blue-700 font-mono border-b border-blue-100"
          >
            @@ -{chunk.oldStart},{chunk.oldLines} +{chunk.newStart},
            {chunk.newLines} @@
          </td>
        </tr>
        {chunk.lines.map((line, lineIndex) => {
          const currentGlobalIndex = globalLineIndex + lineIndex;
          return (
            <tbody
              key={lineIndex}
              data-diff-line={currentGlobalIndex}
              className={cn(
                currentGlobalIndex === diffPositions[currentDiffIndex] &&
                  'ring-2 ring-blue-400 ring-inset'
              )}
            >
              <DiffLine
                line={line}
                viewMode={viewMode}
                showLineNumbers={showLineNumbers}
                showActions={showActions}
              />
            </tbody>
          );
        })}
      </React.Fragment>
    );
  };

  const renderSplitView = () => {
    return (
      <div className="overflow-auto">
        <table className="w-full border-collapse">
          <thead className="sticky top-0 z-10">
            <tr className="bg-gray-100">
              <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 border-b border-r w-24">
                <div className="flex items-center gap-2">
                  <span className="text-red-600">v{diff.versionFrom.version}</span>
                  <span className="text-gray-400">•</span>
                  <span>{formatTimeAgo(diff.versionFrom.createdAt)}</span>
                </div>
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 border-b border-r w-24">
                <div className="flex items-center gap-2">
                  <span className="text-green-600">v{diff.versionTo.version}</span>
                  <span className="text-gray-400">•</span>
                  <span>{formatTimeAgo(diff.versionTo.createdAt)}</span>
                </div>
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 border-b">
                内容
              </th>
            </tr>
          </thead>
          <tbody>
            {diff.contentDiff.length === 0 ? (
              <tr>
                <td colSpan={3} className="px-4 py-8 text-center text-gray-500">
                  两个版本内容完全相同，无差异
                </td>
              </tr>
            ) : (
              diff.contentDiff.map((chunk, index) => renderChunk(chunk, index))
            )}
          </tbody>
        </table>
      </div>
    );
  };

  const renderUnifiedView = () => {
    return (
      <div className="overflow-auto">
        <table className="w-full border-collapse">
          <thead className="sticky top-0 z-10">
            <tr className="bg-gray-100">
              <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 border-b border-r w-12">
                旧行
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 border-b border-r w-12">
                新行
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 border-b">
                <div className="flex items-center gap-2">
                  <span className="text-red-600">v{diff.versionFrom.version}</span>
                  <span>→</span>
                  <span className="text-green-600">v{diff.versionTo.version}</span>
                </div>
              </th>
            </tr>
          </thead>
          <tbody>
            {diff.contentDiff.length === 0 ? (
              <tr>
                <td colSpan={3} className="px-4 py-8 text-center text-gray-500">
                  两个版本内容完全相同，无差异
                </td>
              </tr>
            ) : (
              diff.contentDiff.map((chunk, index) => renderChunk(chunk, index))
            )}
          </tbody>
        </table>
      </div>
    );
  };

  return (
    <div
      className={cn(
        'flex flex-col h-full bg-white border rounded-lg overflow-hidden',
        className
      )}
    >
      <div className="flex items-center justify-between px-4 py-3 border-b bg-gray-50">
        <div className="flex items-center gap-4">
          <h3 className="font-semibold text-gray-900">版本差异对比</h3>
          <div className="text-sm text-gray-500">
            {diff.stats ? formatDiffStat(diff.stats) : `共 ${changedLineNumbers.length} 处变更`}
          </div>
          {changedLineNumbers.length > 0 && (
            <div className="text-xs text-gray-500">
              共 {changedLineNumbers.length} 处变更
            </div>
          )}
        </div>

        <div className="flex items-center gap-2">
          <div className="flex items-center bg-gray-100 rounded-md p-0.5">
            <button
              onClick={() => jumpToDiff('prev')}
              disabled={diffPositions.length === 0}
              className="p-1.5 hover:bg-white rounded text-gray-600 disabled:opacity-50 disabled:cursor-not-allowed"
              title="上一处差异"
            >
              <ChevronUp className="w-4 h-4" />
            </button>
            <span className="text-xs text-gray-500 px-1 min-w-[40px] text-center">
              {diffPositions.length > 0
                ? `${currentDiffIndex + 1}/${diffPositions.length}`
                : '0/0'}
            </span>
            <button
              onClick={() => jumpToDiff('next')}
              disabled={diffPositions.length === 0}
              className="p-1.5 hover:bg-white rounded text-gray-600 disabled:opacity-50 disabled:cursor-not-allowed"
              title="下一处差异"
            >
              <ChevronDown className="w-4 h-4" />
            </button>
          </div>

          <div className="flex items-center bg-gray-100 rounded-md p-0.5">
            <button
              onClick={() => setViewMode('split')}
              className={cn(
                'p-1.5 rounded transition-colors',
                viewMode === 'split'
                  ? 'bg-white text-blue-600 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              )}
              title="并排视图"
            >
              <SplitSquareVertical className="w-4 h-4" />
            </button>
            <button
              onClick={() => setViewMode('unified')}
              className={cn(
                'p-1.5 rounded transition-colors',
                viewMode === 'unified'
                  ? 'bg-white text-blue-600 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              )}
              title="统一视图"
            >
              <AlignLeft className="w-4 h-4" />
            </button>
          </div>

          <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild>
              <button
                className="p-1.5 hover:bg-gray-100 rounded text-gray-600 hover:text-gray-900"
                title="设置"
              >
                <Settings className="w-4 h-4" />
              </button>
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
              <DropdownMenu.Content
                className="bg-white rounded-md shadow-lg border p-2 min-w-[180px] z-50"
                side="bottom"
                align="end"
              >
                <DropdownMenu.CheckboxItem
                  checked={showLineNumbers}
                  onCheckedChange={(checked) => setShowLineNumbers(checked as boolean)}
                  className="flex items-center gap-2 px-3 py-2 text-sm text-gray-700 hover:bg-gray-100 rounded cursor-pointer"
                >
                  <span>显示行号</span>
                </DropdownMenu.CheckboxItem>
                <DropdownMenu.CheckboxItem
                  checked={showActions}
                  onCheckedChange={(checked) => setShowActions(checked as boolean)}
                  className="flex items-center gap-2 px-3 py-2 text-sm text-gray-700 hover:bg-gray-100 rounded cursor-pointer"
                >
                  <span>显示操作按钮</span>
                </DropdownMenu.CheckboxItem>
                <DropdownMenu.CheckboxItem
                  checked={ignoreWhitespace}
                  onCheckedChange={(checked) => setIgnoreWhitespace(checked as boolean)}
                  className="flex items-center gap-2 px-3 py-2 text-sm text-gray-700 hover:bg-gray-100 rounded cursor-pointer"
                >
                  <span>忽略空白字符</span>
                </DropdownMenu.CheckboxItem>
              </DropdownMenu.Content>
            </DropdownMenu.Portal>
          </DropdownMenu.Root>

          <button
            onClick={handleDownloadDiff}
            className="p-1.5 hover:bg-gray-100 rounded text-gray-600 hover:text-gray-900"
            title="下载 diff 文件"
          >
            <Download className="w-4 h-4" />
          </button>

          {onRollback && (
            <button
              onClick={() => onRollback(diff.versionFrom.version)}
              className="px-3 py-1.5 text-sm bg-orange-600 text-white rounded-md hover:bg-orange-700 transition-colors"
            >
              回滚到此版本
            </button>
          )}

          {onClose && (
            <button
              onClick={onClose}
              className="p-1.5 hover:bg-gray-100 rounded text-gray-600 hover:text-gray-900"
              title="关闭"
            >
              <Maximize2 className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>

      {diff.titleDiff.some((c) => c.added || c.removed) && renderTitleDiff()}

      <div className="flex-1 overflow-hidden">
        {viewMode === 'split' ? renderSplitView() : renderUnifiedView()}
      </div>

      <div className="flex items-center gap-4 px-4 py-2 border-t bg-gray-50 text-xs text-gray-500">
        <div className="flex items-center gap-1">
          <span className="w-3 h-3 bg-green-100 border border-green-300 rounded-sm" />
          <span>新增</span>
        </div>
        <div className="flex items-center gap-1">
          <span className="w-3 h-3 bg-red-100 border border-red-300 rounded-sm" />
          <span>删除</span>
        </div>
        <div className="flex items-center gap-1">
          <span className="w-3 h-3 bg-yellow-100 border border-yellow-300 rounded-sm" />
          <span>修改</span>
        </div>
        <div className="ml-auto">
          共 {allLines.length} 行
        </div>
      </div>
    </div>
  );
}
