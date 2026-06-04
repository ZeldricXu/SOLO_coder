'use client';

import React, { useState, useEffect } from 'react';
import {
  AlertTriangle,
  RotateCcw,
  X,
  Clock,
  User,
  GitCompare,
} from 'lucide-react';
import * as Dialog from '@radix-ui/react-dialog';
import type { Version, VersionDiff } from '@/lib/types/version';
import { useVersionCompare, useRollbackMutation } from '@/hooks/useVersions';
import { cn, formatTimeAgo, getInitials } from '@/lib/utils';
import { formatDiffStat } from '@/lib/diff/utils';

interface RollbackDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  documentId: string;
  targetVersion: Version;
  currentVersion: number;
  onSuccess?: (version: Version) => void;
}

export function RollbackDialog({
  open,
  onOpenChange,
  documentId,
  targetVersion,
  currentVersion,
  onSuccess,
}: RollbackDialogProps) {
  const [confirmText, setConfirmText] = useState('');
  const [showDiff, setShowDiff] = useState(false);

  const confirmRequiredText = `回滚到版本 ${targetVersion.version}`;

  const { data: diff, isLoading: diffLoading } = useVersionCompare(
    {
      documentId,
      versionFrom: targetVersion.version,
      versionTo: currentVersion,
    },
    {
      enabled: open && showDiff,
    }
  );

  const rollbackMutation = useRollbackMutation({
    onSuccess: (data) => {
      onSuccess?.(data as unknown as Version);
      onOpenChange(false);
      setConfirmText('');
      setShowDiff(false);
    },
  });

  useEffect(() => {
    if (!open) {
      setConfirmText('');
      setShowDiff(false);
    }
  }, [open]);

  const canConfirm = confirmText === confirmRequiredText && !rollbackMutation.isPending;

  const handleConfirm = () => {
    if (!canConfirm) return;
    rollbackMutation.mutate({
      documentId,
      version: targetVersion.version,
    });
  };

  const renderDiffPreview = (diffData: VersionDiff) => {
    return (
      <div className="mt-4 p-4 bg-gray-50 rounded-lg border">
        <div className="flex items-center justify-between mb-3">
          <h4 className="font-medium text-gray-900">变更摘要</h4>
          <div className="text-sm text-gray-500">
            {formatDiffStat(diffData.stats)}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4 mb-3">
          <div className="p-3 bg-red-50 rounded border border-red-200">
            <div className="text-xs text-red-600 font-medium mb-1">当前版本 v{currentVersion}</div>
            <div className="text-sm text-gray-700 font-mono truncate">
              {diffData.versionTo.title}
            </div>
          </div>
          <div className="p-3 bg-green-50 rounded border border-green-200">
            <div className="text-xs text-green-600 font-medium mb-1">目标版本 v{targetVersion.version}</div>
            <div className="text-sm text-gray-700 font-mono truncate">
              {diffData.versionFrom.title}
            </div>
          </div>
        </div>

        <div className="max-h-48 overflow-y-auto">
          <div className="space-y-1">
            {diffData.contentDiff.slice(0, 5).map((chunk, chunkIndex) => (
              <div key={chunkIndex} className="text-xs font-mono">
                <div className="text-blue-600 bg-blue-50 px-2 py-0.5">
                  @@ -{chunk.oldStart},{chunk.oldLines} +{chunk.newStart},{chunk.newLines} @@
                </div>
                {chunk.lines.slice(0, 10).map((line, lineIndex) => (
                  <div
                    key={lineIndex}
                    className={cn(
                      'px-2 py-0.5 whitespace-pre',
                      line.type === 'added' && 'bg-green-100 text-green-800',
                      line.type === 'removed' && 'bg-red-100 text-red-800',
                      line.type === 'unchanged' && 'text-gray-600'
                    )}
                  >
                    <span className="mr-2 select-none">
                      {line.type === 'added'
                        ? '+'
                        : line.type === 'removed'
                          ? '-'
                          : ' '}
                    </span>
                    {line.content.slice(0, 100)}
                    {line.content.length > 100 && '...'}
                  </div>
                ))}
                {chunk.lines.length > 10 && (
                  <div className="px-2 py-0.5 text-gray-400 italic">
                    ... 还有 {chunk.lines.length - 10} 行
                  </div>
                )}
              </div>
            ))}
            {diffData.contentDiff.length > 5 && (
              <div className="text-center text-gray-500 text-sm py-2">
                ... 还有 {diffData.contentDiff.length - 5} 个区块
              </div>
            )}
          </div>
        </div>
      </div>
    );
  };

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/50 z-50" />
        <Dialog.Content className="fixed top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-full max-w-lg bg-white rounded-lg shadow-xl z-50 overflow-hidden">
          <div className="flex items-center justify-between p-4 border-b">
            <div className="flex items-center gap-3">
              <div className="p-2 bg-orange-100 rounded-lg">
                <RotateCcw className="w-5 h-5 text-orange-600" />
              </div>
              <div>
                <Dialog.Title className="font-semibold text-gray-900">
                  回滚到历史版本
                </Dialog.Title>
                <Dialog.Description className="text-sm text-gray-500">
                  此操作将创建一个新版本，内容为选定的历史版本
                </Dialog.Description>
              </div>
            </div>
            <Dialog.Close className="p-1 hover:bg-gray-100 rounded">
              <X className="w-5 h-5 text-gray-500" />
            </Dialog.Close>
          </div>

          <div className="p-4">
            <div className="mb-4 p-4 bg-yellow-50 border border-yellow-200 rounded-lg">
              <div className="flex items-start gap-3">
                <AlertTriangle className="w-5 h-5 text-yellow-600 flex-shrink-0 mt-0.5" />
                <div className="text-sm text-yellow-800">
                  <p className="font-medium mb-1">重要提示</p>
                  <p>回滚操作不会删除历史版本，而是创建一个新的版本。您可以随时在版本历史中查看和恢复之前的内容。</p>
                </div>
              </div>
            </div>

            <div className="mb-4 p-4 bg-gray-50 rounded-lg border">
              <h4 className="font-medium text-gray-900 mb-3">目标版本信息</h4>
              <div className="grid grid-cols-2 gap-4 text-sm">
                <div>
                  <span className="text-gray-500">版本号：</span>
                  <span className="font-medium text-gray-900">v{targetVersion.version}</span>
                </div>
                <div>
                  <span className="text-gray-500">标题：</span>
                  <span className="font-medium text-gray-900 truncate block">
                    {targetVersion.title}
                  </span>
                </div>
                <div className="flex items-center gap-1">
                  <Clock className="w-3.5 h-3.5 text-gray-400" />
                  <span className="text-gray-500">创建时间：</span>
                  <span className="text-gray-900">
                    {formatTimeAgo(targetVersion.createdAt)}
                  </span>
                </div>
                <div className="flex items-center gap-1">
                  <User className="w-3.5 h-3.5 text-gray-400" />
                  <span className="text-gray-500">创建者：</span>
                  <div className="flex items-center gap-1.5">
                    {targetVersion.createdBy?.avatar ? (
                      <img
                        src={targetVersion.createdBy.avatar}
                        alt={targetVersion.createdBy.name}
                        className="w-4 h-4 rounded-full"
                      />
                    ) : (
                      <div className="w-4 h-4 rounded-full bg-gray-200 flex items-center justify-center text-[10px] font-medium">
                        {getInitials(targetVersion.createdBy?.name ?? '?')}
                      </div>
                    )}
                    <span className="text-gray-900">
                      {targetVersion.createdBy?.name ?? '未知用户'}
                    </span>
                  </div>
                </div>
              </div>
              {targetVersion.message && (
                <div className="mt-3 pt-3 border-t border-gray-200">
                  <span className="text-gray-500 text-sm">版本说明：</span>
                  <p className="text-gray-700 text-sm mt-1">{targetVersion.message}</p>
                </div>
              )}
            </div>

            <div className="mb-4">
              <button
                type="button"
                onClick={() => setShowDiff(!showDiff)}
                className="flex items-center gap-2 text-sm text-blue-600 hover:text-blue-800"
              >
                <GitCompare className="w-4 h-4" />
                {showDiff ? '隐藏变更预览' : '查看变更预览'}
              </button>
            </div>

            {showDiff && (
              diffLoading ? (
                <div className="mb-4 p-8 text-center bg-gray-50 rounded-lg border">
                  <div className="animate-spin w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full mx-auto mb-2" />
                  <p className="text-sm text-gray-500">加载变更详情...</p>
                </div>
              ) : diff ? (
                renderDiffPreview(diff as unknown as VersionDiff)
              ) : null
            )}

            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 mb-2">
                确认回滚
              </label>
              <p className="text-sm text-gray-500 mb-2">
                请输入以下内容以确认操作：
                <span className="font-mono font-medium text-gray-900 ml-1">
                  {confirmRequiredText}
                </span>
              </p>
              <input
                type="text"
                value={confirmText}
                onChange={(e) => setConfirmText(e.target.value)}
                placeholder={confirmRequiredText}
                className={cn(
                  'w-full px-3 py-2 border rounded-md text-sm font-mono',
                  'focus:ring-2 focus:ring-blue-500 focus:border-transparent',
                  canConfirm
                    ? 'border-green-300 bg-green-50'
                    : confirmText && confirmText !== confirmRequiredText
                      ? 'border-red-300 bg-red-50'
                      : 'border-gray-300'
                )}
              />
              {confirmText && confirmText !== confirmRequiredText && (
                <p className="mt-1 text-sm text-red-600">
                  输入内容不匹配，请重新输入
                </p>
              )}
            </div>
          </div>

          <div className="flex items-center justify-end gap-3 p-4 border-t bg-gray-50">
            <Dialog.Close asChild>
              <button
                type="button"
                disabled={rollbackMutation.isPending}
                className="px-4 py-2 border border-gray-300 rounded-md text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                取消
              </button>
            </Dialog.Close>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={!canConfirm}
              className={cn(
                'flex items-center gap-2 px-4 py-2 rounded-md text-sm font-medium transition-colors',
                canConfirm
                  ? 'bg-orange-600 text-white hover:bg-orange-700'
                  : 'bg-gray-300 text-gray-500 cursor-not-allowed'
              )}
            >
              {rollbackMutation.isPending ? (
                <>
                  <div className="animate-spin w-4 h-4 border-2 border-white border-t-transparent rounded-full" />
                  回滚中...
                </>
              ) : (
                <>
                  <RotateCcw className="w-4 h-4" />
                  确认回滚
                </>
              )}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
