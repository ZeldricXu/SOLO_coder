'use client';

import React, { useState } from 'react';
import {
  Eye,
  RotateCcw,
  GitCompare,
  Clock,
  User,
  FileText,
  X,
  Maximize2,
  Minimize2,
} from 'lucide-react';
import * as Dialog from '@radix-ui/react-dialog';
import type { Version } from '@/lib/types/version';
import { useVersionDetail } from '@/hooks/useVersions';
import { cn, formatTimeAgo, getInitials } from '@/lib/utils';

interface VersionPreviewProps {
  documentId: string;
  versionNumber: number;
  currentVersion?: number;
  onCompare?: (versionFrom: number, versionTo: number) => void;
  onRollback?: (version: Version) => void;
  onClose?: () => void;
  className?: string;
}

export function VersionPreview({
  documentId,
  versionNumber,
  currentVersion,
  onCompare,
  onRollback,
  onClose,
  className,
}: VersionPreviewProps) {
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [viewMode, setViewMode] = useState<'rendered' | 'raw'>('rendered');

  const { data: version, isLoading, isError } = useVersionDetail({
    documentId,
    version: versionNumber,
  });

  const isCurrent = currentVersion === versionNumber;

  if (isLoading) {
    return (
      <div className={cn('p-8 text-center', className)}>
        <div className="animate-spin w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full mx-auto mb-4" />
        <p className="text-gray-500">加载版本内容...</p>
      </div>
    );
  }

  if (isError || !version) {
    return (
      <div className={cn('p-8 text-center', className)}>
        <X className="w-12 h-12 text-red-500 mx-auto mb-4" />
        <p className="text-red-600">加载版本内容失败</p>
      </div>
    );
  }

  const renderContent = () => {
    if (viewMode === 'rendered' && version.contentHtml) {
      return (
        <div
          className="prose prose-sm max-w-none"
          dangerouslySetInnerHTML={{ __html: version.contentHtml }}
        />
      );
    }

    return (
      <pre className="whitespace-pre-wrap font-mono text-sm text-gray-800 bg-gray-50 p-4 rounded-lg border">
        {version.content}
      </pre>
    );
  };

  const content = (
    <div
      className={cn(
        'flex flex-col bg-white rounded-lg border overflow-hidden',
        isFullscreen
          ? 'fixed inset-0 z-50 rounded-none'
          : className
      )}
    >
      <div className="flex items-center justify-between px-4 py-3 border-b bg-gray-50">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-blue-100 rounded-lg">
            <Eye className="w-5 h-5 text-blue-600" />
          </div>
          <div>
            <h3 className="font-semibold text-gray-900 flex items-center gap-2">
              版本预览
              <span
                className={cn(
                  'px-2 py-0.5 text-xs font-semibold rounded',
                  isCurrent
                    ? 'bg-blue-500 text-white'
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
            </h3>
            <p className="text-sm text-gray-500 truncate max-w-md">
              {version.title}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex items-center bg-gray-100 rounded-md p-0.5">
            <button
              onClick={() => setViewMode('rendered')}
              className={cn(
                'px-3 py-1 text-xs rounded transition-colors',
                viewMode === 'rendered'
                  ? 'bg-white text-blue-600 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              )}
            >
              渲染视图
            </button>
            <button
              onClick={() => setViewMode('raw')}
              className={cn(
                'px-3 py-1 text-xs rounded transition-colors',
                viewMode === 'raw'
                  ? 'bg-white text-blue-600 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              )}
            >
              原始文本
            </button>
          </div>

          {onCompare && currentVersion !== undefined && !isCurrent && (
            <button
              onClick={() => onCompare(versionNumber, currentVersion)}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm bg-purple-50 text-purple-700 rounded-md hover:bg-purple-100 transition-colors"
            >
              <GitCompare className="w-4 h-4" />
              与当前版本对比
            </button>
          )}

          {onRollback && !isCurrent && (
            <button
              onClick={() => onRollback(version as Version)}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm bg-orange-50 text-orange-700 rounded-md hover:bg-orange-100 transition-colors"
            >
              <RotateCcw className="w-4 h-4" />
              恢复到此版本
            </button>
          )}

          <button
            onClick={() => setIsFullscreen(!isFullscreen)}
            className="p-1.5 hover:bg-gray-100 rounded text-gray-600 hover:text-gray-900"
            title={isFullscreen ? '退出全屏' : '全屏'}
          >
            {isFullscreen ? (
              <Minimize2 className="w-4 h-4" />
            ) : (
              <Maximize2 className="w-4 h-4" />
            )}
          </button>

          {onClose && (
            <button
              onClick={onClose}
              className="p-1.5 hover:bg-gray-100 rounded text-gray-600 hover:text-gray-900"
              title="关闭"
            >
              <X className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>

      <div className="px-4 py-3 border-b bg-gray-50/50">
        <div className="flex flex-wrap items-center gap-4 text-sm text-gray-500">
          <div className="flex items-center gap-1.5">
            <FileText className="w-4 h-4" />
            <span>标题：</span>
            <span className="font-medium text-gray-900">{version.title}</span>
          </div>
          <div className="flex items-center gap-1.5">
            <User className="w-4 h-4" />
            <span>作者：</span>
            <div className="flex items-center gap-1.5">
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
              <span className="font-medium text-gray-900">
                {version.createdBy?.name ?? '未知用户'}
              </span>
            </div>
          </div>
          <div className="flex items-center gap-1.5">
            <Clock className="w-4 h-4" />
            <span>创建时间：</span>
            <span className="font-medium text-gray-900">
              {formatTimeAgo(version.createdAt)}
            </span>
          </div>
          {version.message && (
            <div className="flex items-center gap-1.5">
              <span className="text-gray-500">说明：</span>
              <span className="font-medium text-gray-900">
                {version.message}
              </span>
            </div>
          )}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {version.content ? (
          renderContent()
        ) : (
          <div className="flex flex-col items-center justify-center h-full text-gray-500">
            <FileText className="w-12 h-12 text-gray-300 mb-4" />
            <p>此版本无内容</p>
          </div>
        )}
      </div>

      <div className="flex items-center justify-between px-4 py-2 border-t bg-gray-50 text-xs text-gray-500">
        <div>
          字数：{version.content?.length ?? 0}
        </div>
        <div>
          版本号：v{version.version}
        </div>
      </div>
    </div>
  );

  if (onClose) {
    return (
      <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 bg-black/50 z-40" />
          <Dialog.Content className="fixed inset-0 z-50 flex items-center justify-center p-4">
            {content}
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    );
  }

  return content;
}

interface VersionPreviewInlineProps {
  version: Version;
  currentVersion?: number;
  onCompare?: (versionFrom: number, versionTo: number) => void;
  onRollback?: (version: Version) => void;
  className?: string;
}

export function VersionPreviewInline({
  version,
  currentVersion,
  onCompare,
  onRollback,
  className,
}: VersionPreviewInlineProps) {
  const [viewMode, setViewMode] = useState<'rendered' | 'raw'>('rendered');

  const isCurrent = currentVersion === version.version;

  const renderContent = () => {
    if (viewMode === 'rendered' && version.contentHtml) {
      return (
        <div
          className="prose prose-sm max-w-none"
          dangerouslySetInnerHTML={{ __html: version.contentHtml }}
        />
      );
    }

    return (
      <pre className="whitespace-pre-wrap font-mono text-sm text-gray-800 bg-gray-50 p-4 rounded-lg border">
        {version.content}
      </pre>
    );
  };

  return (
    <div
      className={cn(
        'flex flex-col bg-white rounded-lg border overflow-hidden',
        className
      )}
    >
      <div className="flex items-center justify-between px-4 py-3 border-b bg-gray-50">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-blue-100 rounded-lg">
            <Eye className="w-5 h-5 text-blue-600" />
          </div>
          <div>
            <h3 className="font-semibold text-gray-900 flex items-center gap-2">
              版本内容
              <span
                className={cn(
                  'px-2 py-0.5 text-xs font-semibold rounded',
                  isCurrent
                    ? 'bg-blue-500 text-white'
                    : 'bg-gray-100 text-gray-700'
                )}
              >
                v{version.version}
              </span>
              {isCurrent && (
                <span className="text-xs text-blue-600 font-medium">
                  当前
                </span>
              )}
            </h3>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex items-center bg-gray-100 rounded-md p-0.5">
            <button
              onClick={() => setViewMode('rendered')}
              className={cn(
                'px-3 py-1 text-xs rounded transition-colors',
                viewMode === 'rendered'
                  ? 'bg-white text-blue-600 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              )}
            >
              渲染
            </button>
            <button
              onClick={() => setViewMode('raw')}
              className={cn(
                'px-3 py-1 text-xs rounded transition-colors',
                viewMode === 'raw'
                  ? 'bg-white text-blue-600 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              )}
            >
              原始
            </button>
          </div>

          {onCompare && currentVersion !== undefined && !isCurrent && (
            <button
              onClick={() => onCompare(version.version, currentVersion)}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm bg-purple-50 text-purple-700 rounded-md hover:bg-purple-100"
            >
              <GitCompare className="w-4 h-4" />
              对比
            </button>
          )}

          {onRollback && !isCurrent && (
            <button
              onClick={() => onRollback(version)}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm bg-orange-50 text-orange-700 rounded-md hover:bg-orange-100"
            >
              <RotateCcw className="w-4 h-4" />
              恢复
            </button>
          )}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {version.content ? (
          renderContent()
        ) : (
          <div className="flex flex-col items-center justify-center h-48 text-gray-500">
            <FileText className="w-12 h-12 text-gray-300 mb-4" />
            <p>此版本无内容</p>
          </div>
        )}
      </div>
    </div>
  );
}
