'use client';

import React, { useState } from 'react';
import { X, Send, UserPlus, FileText, Check } from 'lucide-react';
import * as Dialog from '@radix-ui/react-dialog';
import * as Select from '@radix-ui/react-select';
import type { CreateReviewInput } from '@/lib/types/review';
import { useCreateReview } from '@/hooks/useReviews';
import { cn, getInitials } from '@/lib/utils';

interface CreateReviewDialogProps {
  isOpen: boolean;
  onClose: () => void;
  documentId?: string;
  spaceId?: string;
}

interface UserOption {
  id: string;
  name: string;
  email: string;
  avatar?: string;
}

interface VersionOption {
  id: string;
  version: number;
  title: string;
}

const mockUsers: UserOption[] = [
  { id: '1', name: '张三', email: 'zhangsan@example.com' },
  { id: '2', name: '李四', email: 'lisi@example.com' },
  { id: '3', name: '王五', email: 'wangwu@example.com' },
  { id: '4', name: '赵六', email: 'zhaoliu@example.com' },
];

const mockVersions: VersionOption[] = [
  { id: 'v1', version: 3, title: '最新版本' },
  { id: 'v2', version: 2, title: '上次修改' },
  { id: 'v3', version: 1, title: '初始版本' },
];

export function CreateReviewDialog({
  isOpen,
  onClose,
  documentId,
  spaceId,
}: CreateReviewDialogProps) {
  const [selectedVersion, setSelectedVersion] = useState<string>(mockVersions[0]?.id ?? '');
  const [selectedReviewers, setSelectedReviewers] = useState<string[]>([]);
  const [comment, setComment] = useState('');
  const [showReviewerDropdown, setShowReviewerDropdown] = useState(false);
  const [reviewerSearch, setReviewerSearch] = useState('');

  const createReview = useCreateReview();

  const filteredUsers = mockUsers.filter(
    (user) =>
      !selectedReviewers.includes(user.id) &&
      (user.name.toLowerCase().includes(reviewerSearch.toLowerCase()) ||
        user.email.toLowerCase().includes(reviewerSearch.toLowerCase()))
  );

  const toggleReviewer = (userId: string) => {
    setSelectedReviewers((prev) =>
      prev.includes(userId)
        ? prev.filter((id) => id !== userId)
        : [...prev, userId]
    );
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    if (!selectedVersion || selectedReviewers.length === 0 || !documentId) return;

    const input: CreateReviewInput = {
      documentId,
      versionId: selectedVersion,
      comment: comment || undefined,
      reviewerIds: selectedReviewers,
    };

    createReview.mutate(input, {
      onSuccess: () => {
        setSelectedVersion(mockVersions[0]?.id ?? '');
        setSelectedReviewers([]);
        setComment('');
        onClose();
      },
    });
  };

  const handleClose = () => {
    setSelectedVersion(mockVersions[0]?.id ?? '');
    setSelectedReviewers([]);
    setComment('');
    setReviewerSearch('');
    onClose();
  };

  return (
    <Dialog.Root open={isOpen} onOpenChange={handleClose}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/50 z-50" />
        <Dialog.Content className="fixed top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-full max-w-xl bg-white rounded-lg shadow-xl z-50 max-h-[90vh] overflow-y-auto">
          <div className="flex items-center justify-between p-4 border-b border-gray-200 sticky top-0 bg-white z-10">
            <Dialog.Title className="text-lg font-semibold text-gray-900">
              发起审阅
            </Dialog.Title>
            <Dialog.Close className="p-1 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded">
              <X className="w-5 h-5" />
            </Dialog.Close>
          </div>

          <form onSubmit={handleSubmit} className="p-4 space-y-6">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">
                选择文档版本 <span className="text-red-500">*</span>
              </label>
              <Select.Root
                value={selectedVersion}
                onValueChange={setSelectedVersion}
              >
                <Select.Trigger className="w-full p-3 border border-gray-300 rounded-lg flex items-center justify-between">
                  <Select.Value placeholder="选择版本..." />
                  <Select.Icon>
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
                        d="M19 9l-7 7-7-7"
                      />
                    </svg>
                  </Select.Icon>
                </Select.Trigger>
                <Select.Portal>
                  <Select.Content className="bg-white border border-gray-200 rounded-lg shadow-lg z-50 max-h-60 overflow-y-auto">
                    <Select.Viewport>
                      {mockVersions.map((version) => (
                        <Select.Item
                          key={version.id}
                          value={version.id}
                          className="p-3 hover:bg-gray-50 cursor-pointer flex items-center justify-between"
                        >
                          <div className="flex items-center gap-3">
                            <FileText className="w-5 h-5 text-gray-400" />
                            <div>
                              <Select.ItemText className="text-sm font-medium text-gray-900">
                                版本 {version.version}
                              </Select.ItemText>
                              <div className="text-xs text-gray-500">
                                {version.title}
                              </div>
                            </div>
                          </div>
                          {selectedVersion === version.id && (
                            <Check className="w-5 h-5 text-blue-600" />
                          )}
                        </Select.Item>
                      ))}
                    </Select.Viewport>
                  </Select.Content>
                </Select.Portal>
              </Select.Root>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">
                指定审阅人 <span className="text-red-500">*</span>
                <span className="text-gray-400 font-normal ml-2">
                  (已选择 {selectedReviewers.length} 人)
                </span>
              </label>

              <div className="relative">
                <div className="border border-gray-300 rounded-lg p-2 min-h-[44px]">
                  {selectedReviewers.length > 0 && (
                    <div className="flex flex-wrap gap-2 mb-2">
                      {selectedReviewers.map((userId) => {
                        const user = mockUsers.find((u) => u.id === userId);
                        if (!user) return null;
                        return (
                          <div
                            key={userId}
                            className="flex items-center gap-1.5 bg-blue-100 text-blue-800 px-2.5 py-1 rounded-full text-sm"
                          >
                            {user.avatar ? (
                              <img
                                src={user.avatar}
                                alt={user.name}
                                className="w-5 h-5 rounded-full"
                              />
                            ) : (
                              <div className="w-5 h-5 rounded-full bg-blue-500 text-white flex items-center justify-center text-xs font-medium">
                                {getInitials(user.name)}
                              </div>
                            )}
                            <span>{user.name}</span>
                            <button
                              type="button"
                              onClick={() => toggleReviewer(userId)}
                              className="ml-1 hover:text-blue-600"
                            >
                              <X className="w-3 h-3" />
                            </button>
                          </div>
                        );
                      })}
                    </div>
                  )}

                  <input
                    type="text"
                    value={reviewerSearch}
                    onChange={(e) => setReviewerSearch(e.target.value)}
                    onFocus={() => setShowReviewerDropdown(true)}
                    placeholder="搜索并添加审阅人..."
                    className="w-full p-1.5 outline-none text-sm"
                  />
                </div>

                {showReviewerDropdown && (
                  <div className="absolute top-full left-0 right-0 mt-1 bg-white border border-gray-200 rounded-lg shadow-lg z-20 max-h-48 overflow-y-auto">
                    {filteredUsers.length === 0 ? (
                      <div className="p-3 text-sm text-gray-500 text-center">
                        未找到匹配的用户
                      </div>
                    ) : (
                      filteredUsers.map((user) => (
                        <button
                          key={user.id}
                          type="button"
                          onClick={() => {
                            toggleReviewer(user.id);
                            setReviewerSearch('');
                          }}
                          className="w-full flex items-center gap-3 p-3 hover:bg-gray-50 text-left"
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
                          <div className="flex-1">
                            <div className="text-sm font-medium text-gray-900">
                              {user.name}
                            </div>
                            <div className="text-xs text-gray-500">
                              {user.email}
                            </div>
                          </div>
                          <UserPlus className="w-4 h-4 text-gray-400" />
                        </button>
                      ))
                    )}
                  </div>
                )}
              </div>

              {selectedReviewers.length === 0 && (
                <p className="mt-1 text-sm text-red-500">
                  请至少选择一位审阅人
                </p>
              )}
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">
                审阅说明
                <span className="text-gray-400 font-normal ml-1">(可选)</span>
              </label>
              <textarea
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                placeholder="请输入审阅说明，例如需要审阅的重点内容..."
                className="w-full p-3 border border-gray-300 rounded-lg resize-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                rows={4}
              />
            </div>

            <div className="flex items-center justify-end gap-3 pt-4 border-t border-gray-200">
              <Dialog.Close asChild>
                <button
                  type="button"
                  className="px-4 py-2 border border-gray-300 rounded-lg text-sm text-gray-700 hover:bg-gray-50"
                  disabled={createReview.isPending}
                >
                  取消
                </button>
              </Dialog.Close>
              <button
                type="submit"
                disabled={
                  !selectedVersion ||
                  selectedReviewers.length === 0 ||
                  createReview.isPending
                }
                className={cn(
                  'px-4 py-2 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2',
                  createReview.isPending
                    ? 'bg-blue-400 cursor-not-allowed'
                    : 'bg-blue-600'
                )}
              >
                <Send className="w-4 h-4" />
                {createReview.isPending ? '发送中...' : '发起审阅'}
              </button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
