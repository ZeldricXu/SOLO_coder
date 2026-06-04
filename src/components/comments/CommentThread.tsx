'use client';

import React, { useState } from 'react';
import {
  MessageSquare,
  Check,
  RotateCcw,
  Edit2,
  Trash2,
  Send,
  MoreHorizontal,
} from 'lucide-react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import type { CommentWithRelations, CommentPosition } from '@/lib/types/comment';
import {
  useUpdateComment,
  useResolveComment,
  useUnresolveComment,
  useDeleteComment,
  useCreateComment,
} from '@/hooks/useComments';
import { cn, formatTimeAgo, getInitials } from '@/lib/utils';

interface CommentThreadProps {
  comment: CommentWithRelations;
  currentUserId: string;
  canResolve: boolean;
  onNavigate?: (position: CommentPosition) => void;
}

export function CommentThread({
  comment,
  currentUserId,
  canResolve,
  onNavigate,
}: CommentThreadProps) {
  const [isReplying, setIsReplying] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [editContent, setEditContent] = useState(comment.content);
  const [replyContent, setReplyContent] = useState('');
  const [showMenu, setShowMenu] = useState(false);

  const updateComment = useUpdateComment();
  const resolveComment = useResolveComment();
  const unresolveComment = useUnresolveComment();
  const deleteComment = useDeleteComment();
  const createComment = useCreateComment();

  const isAuthor = comment.authorId === currentUserId;

  const handleUpdate = () => {
    if (!editContent.trim()) return;
    updateComment.mutate(
      { id: comment.id, content: editContent },
      {
        onSuccess: () => {
          setIsEditing(false);
        },
      }
    );
  };

  const handleResolve = () => {
    resolveComment.mutate({ id: comment.id, documentId: comment.documentId });
  };

  const handleUnresolve = () => {
    unresolveComment.mutate({ id: comment.id, documentId: comment.documentId });
  };

  const handleDelete = () => {
    if (confirm('确定要删除这条评论吗？')) {
      deleteComment.mutate({ id: comment.id, documentId: comment.documentId });
    }
  };

  const handleReply = () => {
    if (!replyContent.trim()) return;
    createComment.mutate(
      {
        documentId: comment.documentId,
        versionId: comment.versionId ?? undefined,
        content: replyContent,
        parentId: comment.id,
      },
      {
        onSuccess: () => {
          setReplyContent('');
          setIsReplying(false);
        },
      }
    );
  };

  const handleNavigate = () => {
    if (comment.position && onNavigate) {
      onNavigate(comment.position as unknown as CommentPosition);
    }
  };

  return (
    <div
      className={cn(
        'rounded-lg border p-4 transition-all',
        comment.isResolved
          ? 'bg-gray-50 border-gray-200 opacity-75'
          : 'bg-white border-gray-200 hover:border-blue-300'
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-3 flex-1">
          <div className="flex-shrink-0">
            {comment.author.avatar ? (
              <img
                src={comment.author.avatar}
                alt={comment.author.name}
                className="w-8 h-8 rounded-full"
              />
            ) : (
              <div className="w-8 h-8 rounded-full bg-blue-500 text-white flex items-center justify-center text-sm font-medium">
                {getInitials(comment.author.name)}
              </div>
            )}
          </div>

          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1">
              <span className="font-medium text-gray-900">
                {comment.author.name}
              </span>
              <span className="text-xs text-gray-500">
                {formatTimeAgo(comment.createdAt)}
              </span>
              {comment.isResolved && (
                <span className="text-xs bg-green-100 text-green-800 px-2 py-0.5 rounded-full">
                  已解决
                </span>
              )}
              {comment.position && onNavigate && (
                <button
                  onClick={handleNavigate}
                  className="text-xs text-blue-600 hover:text-blue-800 hover:underline"
                >
                  跳转到位置
                </button>
              )}
            </div>

            {comment.position && (
              <div
                className="mb-2 p-2 bg-yellow-50 border-l-2 border-yellow-400 text-sm text-gray-700 cursor-pointer hover:bg-yellow-100"
                onClick={handleNavigate}
              >
                <MessageSquare className="w-3 h-3 inline mr-1" />
                <span className="line-clamp-2">
                  {(comment.position as unknown as CommentPosition).text}
                </span>
              </div>
            )}

            {isEditing ? (
              <div className="space-y-2">
                <textarea
                  value={editContent}
                  onChange={(e) => setEditContent(e.target.value)}
                  className="w-full p-2 border rounded-md resize-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  rows={3}
                />
                <div className="flex gap-2">
                  <button
                    onClick={handleUpdate}
                    disabled={updateComment.isPending || !editContent.trim()}
                    className="px-3 py-1.5 bg-blue-600 text-white rounded-md text-sm hover:bg-blue-700 disabled:opacity-50"
                  >
                    保存
                  </button>
                  <button
                    onClick={() => {
                      setIsEditing(false);
                      setEditContent(comment.content);
                    }}
                    className="px-3 py-1.5 border rounded-md text-sm hover:bg-gray-50"
                  >
                    取消
                  </button>
                </div>
              </div>
            ) : (
              <p className="text-gray-700 whitespace-pre-wrap">
                {comment.content}
              </p>
            )}

            <div className="flex items-center gap-4 mt-3">
              <button
                onClick={() => setIsReplying(!isReplying)}
                className="text-sm text-gray-500 hover:text-blue-600 flex items-center gap-1"
              >
                <MessageSquare className="w-4 h-4" />
                回复
              </button>

              {canResolve && (
                <>
                  {comment.isResolved ? (
                    <button
                      onClick={handleUnresolve}
                      disabled={unresolveComment.isPending}
                      className="text-sm text-gray-500 hover:text-orange-600 flex items-center gap-1"
                    >
                      <RotateCcw className="w-4 h-4" />
                      重新打开
                    </button>
                  ) : (
                    <button
                      onClick={handleResolve}
                      disabled={resolveComment.isPending}
                      className="text-sm text-gray-500 hover:text-green-600 flex items-center gap-1"
                    >
                      <Check className="w-4 h-4" />
                      解决
                    </button>
                  )}
                </>
              )}

              {(isAuthor || canResolve) && (
                <DropdownMenu.Root open={showMenu} onOpenChange={setShowMenu}>
                  <DropdownMenu.Trigger asChild>
                    <button className="text-sm text-gray-500 hover:text-gray-700 p-1 rounded hover:bg-gray-100">
                      <MoreHorizontal className="w-4 h-4" />
                    </button>
                  </DropdownMenu.Trigger>
                  <DropdownMenu.Portal>
                    <DropdownMenu.Content
                      className="bg-white rounded-md shadow-lg border p-1 min-w-[120px] z-50"
                      side="bottom"
                      align="end"
                    >
                      {isAuthor && (
                        <DropdownMenu.Item
                          onClick={() => setIsEditing(true)}
                          className="flex items-center gap-2 px-3 py-2 text-sm text-gray-700 hover:bg-gray-100 rounded cursor-pointer"
                        >
                          <Edit2 className="w-4 h-4" />
                          编辑
                        </DropdownMenu.Item>
                      )}
                      {(isAuthor || canResolve) && (
                        <DropdownMenu.Item
                          onClick={handleDelete}
                          className="flex items-center gap-2 px-3 py-2 text-sm text-red-600 hover:bg-red-50 rounded cursor-pointer"
                        >
                          <Trash2 className="w-4 h-4" />
                          删除
                        </DropdownMenu.Item>
                      )}
                    </DropdownMenu.Content>
                  </DropdownMenu.Portal>
                </DropdownMenu.Root>
              )}
            </div>

            {isReplying && (
              <div className="mt-3 space-y-2">
                <textarea
                  value={replyContent}
                  onChange={(e) => setReplyContent(e.target.value)}
                  placeholder="写下你的回复..."
                  className="w-full p-2 border rounded-md resize-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  rows={2}
                />
                <div className="flex gap-2">
                  <button
                    onClick={handleReply}
                    disabled={createComment.isPending || !replyContent.trim()}
                    className="px-3 py-1.5 bg-blue-600 text-white rounded-md text-sm hover:bg-blue-700 disabled:opacity-50 flex items-center gap-1"
                  >
                    <Send className="w-3 h-3" />
                    回复
                  </button>
                  <button
                    onClick={() => {
                      setIsReplying(false);
                      setReplyContent('');
                    }}
                    className="px-3 py-1.5 border rounded-md text-sm hover:bg-gray-50"
                  >
                    取消
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {comment.children && comment.children.length > 0 && (
        <div className="mt-4 ml-10 space-y-4">
          {comment.children.map((reply) => (
            <CommentThread
              key={reply.id}
              comment={reply as unknown as CommentWithRelations}
              currentUserId={currentUserId}
              canResolve={canResolve}
              onNavigate={onNavigate}
            />
          ))}
        </div>
      )}
    </div>
  );
}
