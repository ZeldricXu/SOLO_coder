'use client';

import React, { useState, useRef, useEffect } from 'react';
import { X, Send, AtSign, User } from 'lucide-react';
import * as Dialog from '@radix-ui/react-dialog';
import type { CommentPosition, CreateCommentInput } from '@/lib/types/comment';
import { useCreateComment } from '@/hooks/useComments';
import { cn, extractMentions, getInitials } from '@/lib/utils';

interface CreateCommentDialogProps {
  isOpen: boolean;
  onClose: () => void;
  documentId: string;
  versionId?: string;
  selectedPosition?: CommentPosition | null;
  parentId?: string;
}

interface MentionUser {
  id: string;
  name: string;
  email: string;
  avatar?: string;
}

const mockUsers: MentionUser[] = [
  { id: '1', name: '张三', email: 'zhangsan@example.com' },
  { id: '2', name: '李四', email: 'lisi@example.com' },
  { id: '3', name: '王五', email: 'wangwu@example.com' },
  { id: '4', name: '赵六', email: 'zhaoliu@example.com' },
];

export function CreateCommentDialog({
  isOpen,
  onClose,
  documentId,
  versionId,
  selectedPosition,
  parentId,
}: CreateCommentDialogProps) {
  const [content, setContent] = useState('');
  const [showMentionList, setShowMentionList] = useState(false);
  const [mentionSearch, setMentionSearch] = useState('');
  const [mentionCursorPos, setMentionCursorPos] = useState({ top: 0, left: 0 });
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const mentionListRef = useRef<HTMLDivElement>(null);

  const createComment = useCreateComment();

  const filteredUsers = mockUsers.filter(
    (user) =>
      user.name.toLowerCase().includes(mentionSearch.toLowerCase()) ||
      user.email.toLowerCase().includes(mentionSearch.toLowerCase())
  );

  const handleTextareaChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.target.value;
    setContent(value);

    const cursorPos = e.target.selectionStart;
    const textBeforeCursor = value.slice(0, cursorPos);
    const lastAtIndex = textBeforeCursor.lastIndexOf('@');

    if (
      lastAtIndex !== -1 &&
      (lastAtIndex === 0 || /\s/.test(textBeforeCursor[lastAtIndex - 1]))
    ) {
      const searchText = textBeforeCursor.slice(lastAtIndex + 1);
      if (!searchText.includes(' ')) {
        setMentionSearch(searchText);
        setShowMentionList(true);

        if (textareaRef.current) {
          const lines = textBeforeCursor.split('\n');
          const currentLine = lines.length - 1;
          const lineHeight = 24;
          const charWidth = 8;

          setMentionCursorPos({
            top: currentLine * lineHeight + 60,
            left: (lines[currentLine]?.length || 0) * charWidth,
          });
        }
        return;
      }
    }

    setShowMentionList(false);
  };

  const handleMentionSelect = (user: MentionUser) => {
    if (!textareaRef.current) return;

    const cursorPos = textareaRef.current.selectionStart;
    const textBeforeCursor = content.slice(0, cursorPos);
    const lastAtIndex = textBeforeCursor.lastIndexOf('@');

    const newContent =
      content.slice(0, lastAtIndex) +
      `@[${user.name}](${user.id}) ` +
      content.slice(cursorPos);

    setContent(newContent);
    setShowMentionList(false);

    setTimeout(() => {
      if (textareaRef.current) {
        const newCursorPos =
          lastAtIndex + `@[${user.name}](${user.id}) `.length;
        textareaRef.current.selectionStart = newCursorPos;
        textareaRef.current.selectionEnd = newCursorPos;
        textareaRef.current.focus();
      }
    }, 0);
  };

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (
        mentionListRef.current &&
        !mentionListRef.current.contains(e.target as Node) &&
        textareaRef.current &&
        !textareaRef.current.contains(e.target as Node)
      ) {
        setShowMentionList(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    if (!content.trim()) return;

    const mentionedUserIds = extractMentions(content);

    const input: CreateCommentInput = {
      documentId,
      versionId,
      content,
      position: selectedPosition ?? undefined,
      parentId,
      mentionedUserIds,
    };

    createComment.mutate(input, {
      onSuccess: () => {
        setContent('');
        onClose();
      },
    });
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      handleSubmit(e);
    }
    if (e.key === 'Escape' && showMentionList) {
      e.preventDefault();
      setShowMentionList(false);
    }
  };

  return (
    <Dialog.Root open={isOpen} onOpenChange={onClose}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/50 z-50" />
        <Dialog.Content className="fixed top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-full max-w-lg bg-white rounded-lg shadow-xl z-50">
          <div className="flex items-center justify-between p-4 border-b border-gray-200">
            <Dialog.Title className="text-lg font-semibold text-gray-900">
              {parentId ? '回复评论' : '新建评论'}
            </Dialog.Title>
            <Dialog.Close className="p-1 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded">
              <X className="w-5 h-5" />
            </Dialog.Close>
          </div>

          <form onSubmit={handleSubmit} className="p-4">
            {selectedPosition && (
              <div className="mb-4 p-3 bg-yellow-50 border border-yellow-200 rounded-lg">
                <div className="text-xs font-medium text-yellow-800 mb-1">
                  选中文本:
                </div>
                <p className="text-sm text-yellow-900 line-clamp-2">
                  {selectedPosition.text}
                </p>
                <div className="text-xs text-yellow-700 mt-1">
                  位置: {selectedPosition.start} - {selectedPosition.end}
                </div>
              </div>
            )}

            <div className="relative">
              <textarea
                ref={textareaRef}
                value={content}
                onChange={handleTextareaChange}
                onKeyDown={handleKeyDown}
                placeholder="写下你的评论... 输入 @ 可以提及用户"
                className={cn(
                  'w-full p-3 border rounded-lg resize-none focus:ring-2 focus:ring-blue-500 focus:border-transparent min-h-[120px]',
                  createComment.isPending && 'opacity-50 cursor-not-allowed'
                )}
                disabled={createComment.isPending}
              />

              {showMentionList && (
                <div
                  ref={mentionListRef}
                  className="absolute bg-white border border-gray-200 rounded-lg shadow-lg z-10 max-h-48 overflow-y-auto w-64"
                  style={{ top: mentionCursorPos.top, left: mentionCursorPos.left }}
                >
                  {filteredUsers.length === 0 ? (
                    <div className="p-3 text-sm text-gray-500">未找到匹配的用户</div>
                  ) : (
                    filteredUsers.map((user) => (
                      <button
                        key={user.id}
                        type="button"
                        onClick={() => handleMentionSelect(user)}
                        className="w-full flex items-center gap-3 p-2 hover:bg-gray-50 text-left"
                      >
                        {user.avatar ? (
                          <img
                            src={user.avatar}
                            alt={user.name}
                            className="w-8 h-8 rounded-full"
                          />
                        ) : (
                          <div className="w-8 h-8 rounded-full bg-blue-500 text-white flex items-center justify-center text-sm font-medium">
                            {getInitials(user.name)}
                          </div>
                        )}
                        <div>
                          <div className="text-sm font-medium text-gray-900">
                            {user.name}
                          </div>
                          <div className="text-xs text-gray-500">
                            {user.email}
                          </div>
                        </div>
                      </button>
                    ))
                  )}
                </div>
              )}
            </div>

            <div className="flex items-center justify-between mt-4">
              <div className="flex items-center gap-2 text-sm text-gray-500">
                <button
                  type="button"
                  onClick={() => setShowMentionList(true)}
                  className="p-1 hover:bg-gray-100 rounded"
                  title="提及用户"
                >
                  <AtSign className="w-4 h-4" />
                </button>
                <span>Ctrl/Cmd + Enter 发送</span>
              </div>

              <div className="flex items-center gap-2">
                <Dialog.Close asChild>
                  <button
                    type="button"
                    className="px-4 py-2 border border-gray-300 rounded-lg text-sm text-gray-700 hover:bg-gray-50"
                    disabled={createComment.isPending}
                  >
                    取消
                  </button>
                </Dialog.Close>
                <button
                  type="submit"
                  disabled={!content.trim() || createComment.isPending}
                  className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                >
                  <Send className="w-4 h-4" />
                  {createComment.isPending ? '发送中...' : '发送'}
                </button>
              </div>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
