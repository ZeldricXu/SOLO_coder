'use client';

import React, { useState } from 'react';
import { MessageSquare, Plus, Filter, X, ChevronRight } from 'lucide-react';
import * as Tabs from '@radix-ui/react-tabs';
import type { CommentWithRelations, CommentPosition, CommentFilterType } from '@/lib/types/comment';
import { CommentThread } from './CommentThread';
import { CreateCommentDialog } from './CreateCommentDialog';
import { useComments } from '@/hooks/useComments';
import { cn } from '@/lib/utils';

interface CommentSidebarProps {
  documentId: string;
  versionId?: string;
  currentUserId: string;
  canResolve: boolean;
  isOpen: boolean;
  onClose: () => void;
  onNavigate?: (position: CommentPosition) => void;
  selectedPosition?: CommentPosition | null;
}

export function CommentSidebar({
  documentId,
  versionId,
  currentUserId,
  canResolve,
  isOpen,
  onClose,
  onNavigate,
  selectedPosition,
}: CommentSidebarProps) {
  const [filter, setFilter] = useState<CommentFilterType>('all');
  const [isCreateDialogOpen, setIsCreateDialogOpen] = useState(false);
  const [activeTab, setActiveTab] = useState('all');

  const { data, isLoading, error } = useComments({
    documentId,
    versionId,
    includeResolved: filter === 'all' || filter === 'resolved',
    page: 1,
    pageSize: 100,
  });

  const filteredComments = React.useMemo(() => {
    if (!data?.items) return [];

    let comments = data.items;

    if (filter === 'unresolved') {
      comments = comments.filter((c) => !c.isResolved);
    } else if (filter === 'resolved') {
      comments = comments.filter((c) => c.isResolved);
    }

    return comments;
  }, [data?.items, filter]);

  const unresolvedCount = React.useMemo(() => {
    return data?.items?.filter((c) => !c.isResolved).length ?? 0;
  }, [data?.items]);

  const resolvedCount = React.useMemo(() => {
    return data?.items?.filter((c) => c.isResolved).length ?? 0;
  }, [data?.items]);

  if (!isOpen) return null;

  return (
    <div className="flex flex-col h-full bg-white border-l border-gray-200 w-96">
      <div className="flex items-center justify-between p-4 border-b border-gray-200">
        <div className="flex items-center gap-2">
          <MessageSquare className="w-5 h-5 text-gray-600" />
          <h2 className="font-semibold text-gray-900">评论</h2>
          <span className="text-sm text-gray-500">
            ({unresolvedCount} 未解决)
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setIsCreateDialogOpen(true)}
            className="p-2 text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
            title="新建评论"
          >
            <Plus className="w-5 h-5" />
          </button>
          <button
            onClick={onClose}
            className="p-2 text-gray-500 hover:bg-gray-100 rounded-lg transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>
      </div>

      <Tabs.Root value={activeTab} onValueChange={setActiveTab} className="flex flex-col flex-1 overflow-hidden">
        <Tabs.List className="flex border-b border-gray-200 px-2">
          <Tabs.Trigger
            value="all"
            className={cn(
              'px-4 py-2 text-sm font-medium border-b-2 transition-colors',
              activeTab === 'all'
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            )}
            onClick={() => setFilter('all')}
          >
            全部 ({data?.total ?? 0})
          </Tabs.Trigger>
          <Tabs.Trigger
            value="unresolved"
            className={cn(
              'px-4 py-2 text-sm font-medium border-b-2 transition-colors',
              activeTab === 'unresolved'
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            )}
            onClick={() => setFilter('unresolved')}
          >
            未解决 ({unresolvedCount})
          </Tabs.Trigger>
          <Tabs.Trigger
            value="resolved"
            className={cn(
              'px-4 py-2 text-sm font-medium border-b-2 transition-colors',
              activeTab === 'resolved'
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            )}
            onClick={() => setFilter('resolved')}
          >
            已解决 ({resolvedCount})
          </Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value={activeTab} className="flex-1 overflow-y-auto p-4">
          {isLoading ? (
            <div className="flex items-center justify-center py-12">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
            </div>
          ) : error ? (
            <div className="text-center py-12 text-red-500">
              加载评论失败，请稍后重试
            </div>
          ) : filteredComments.length === 0 ? (
            <div className="text-center py-12">
              <MessageSquare className="w-12 h-12 text-gray-300 mx-auto mb-3" />
              <p className="text-gray-500">暂无评论</p>
              <button
                onClick={() => setIsCreateDialogOpen(true)}
                className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700"
              >
                添加第一条评论
              </button>
            </div>
          ) : (
            <div className="space-y-4">
              {filteredComments.map((comment) => (
                <div
                  key={comment.id}
                  className="group cursor-pointer"
                  onClick={() => {
                    if (comment.position && onNavigate) {
                      onNavigate(comment.position as unknown as CommentPosition);
                    }
                  }}
                >
                  <div className="flex items-center gap-1 mb-1 text-xs text-gray-500 opacity-0 group-hover:opacity-100 transition-opacity">
                    <ChevronRight className="w-3 h-3" />
                    <span>点击跳转到位置</span>
                  </div>
                  <CommentThread
                    comment={comment as unknown as CommentWithRelations}
                    currentUserId={currentUserId}
                    canResolve={canResolve}
                    onNavigate={onNavigate}
                  />
                </div>
              ))}
            </div>
          )}
        </Tabs.Content>
      </Tabs.Root>

      <CreateCommentDialog
        isOpen={isCreateDialogOpen}
        onClose={() => setIsCreateDialogOpen(false)}
        documentId={documentId}
        versionId={versionId}
        selectedPosition={selectedPosition}
      />
    </div>
  );
}
