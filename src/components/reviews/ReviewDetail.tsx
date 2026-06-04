'use client';

import React, { useState } from 'react';
import {
  ArrowLeft,
  CheckCircle2,
  XCircle,
  Clock,
  AlertCircle,
  UserPlus,
  Send,
  FileText,
  MessageSquare,
} from 'lucide-react';
import * as Dialog from '@radix-ui/react-dialog';
import * as Select from '@radix-ui/react-select';
import type {
  ReviewWithRelations,
  ReviewerWithRelations,
  SubmitReviewInput,
  ReviewProgress,
  ReviewDecision,
} from '@/lib/types/review';
import type { CommentWithRelations, CommentPosition } from '@/lib/types/comment';
import {
  useReview,
  useSubmitReview,
  useAddReviewer,
  useReviewProgress,
} from '@/hooks/useReviews';
import { CommentThread } from '../comments/CommentThread';
import {
  cn,
  formatTimeAgo,
  getStatusColor,
  getStatusText,
  getInitials,
} from '@/lib/utils';

interface ReviewDetailProps {
  reviewId: string;
  currentUserId: string;
  canResolve: boolean;
  onBack: () => void;
  onNavigate?: (position: CommentPosition) => void;
}

export function ReviewDetail({
  reviewId,
  currentUserId,
  canResolve,
  onBack,
  onNavigate,
}: ReviewDetailProps) {
  const [showAddReviewer, setShowAddReviewer] = useState(false);
  const [selectedReviewer, setSelectedReviewer] = useState<string>('');
  const [decision, setDecision] = useState<ReviewDecision | null>(null);
  const [comment, setComment] = useState('');
  const [showSubmitDialog, setShowSubmitDialog] = useState(false);

  const { data: review, isLoading } = useReview(reviewId);
  const { data: progress } = useReviewProgress(reviewId);
  const submitReview = useSubmitReview();
  const addReviewer = useAddReviewer();

  const isReviewer = review?.reviewers.some((r) => r.userId === currentUserId);
  const isAuthor = review?.authorId === currentUserId;
  const currentReviewer = review?.reviewers.find((r) => r.userId === currentUserId);
  const hasSubmitted = currentReviewer?.status !== 'PENDING';

  const handleSubmit = () => {
    if (!decision || !comment.trim()) return;

    const input: SubmitReviewInput = {
      reviewId,
      decision,
      comment,
    };

    submitReview.mutate(input, {
      onSuccess: () => {
        setShowSubmitDialog(false);
        setDecision(null);
        setComment('');
      },
    });
  };

  const handleAddReviewer = () => {
    if (!selectedReviewer) return;

    addReviewer.mutate(
      {
        reviewId,
        userId: selectedReviewer,
      },
      {
        onSuccess: () => {
          setShowAddReviewer(false);
          setSelectedReviewer('');
        },
      }
    );
  };

  const renderStatusIcon = (status: string) => {
    switch (status) {
      case 'APPROVED':
        return <CheckCircle2 className="w-5 h-5 text-green-500" />;
      case 'CHANGES_REQUESTED':
        return <XCircle className="w-5 h-5 text-orange-500" />;
      case 'PENDING':
        return <Clock className="w-5 h-5 text-yellow-500" />;
      case 'REJECTED':
        return <AlertCircle className="w-5 h-5 text-red-500" />;
      default:
        return <AlertCircle className="w-5 h-5 text-gray-500" />;
    }
  };

  const renderProgressBar = (progress: ReviewProgress | undefined) => {
    if (!progress || progress.total === 0) return null;

    return (
      <div className="flex items-center gap-3">
        <div className="flex-1 h-3 bg-gray-200 rounded-full overflow-hidden flex">
          {progress.approved > 0 && (
            <div
              className="h-full bg-green-500"
              style={{ width: `${(progress.approved / progress.total) * 100}%` }}
            />
          )}
          {progress.changesRequested > 0 && (
            <div
              className="h-full bg-orange-500"
              style={{
                width: `${(progress.changesRequested / progress.total) * 100}%`,
              }}
            />
          )}
          {progress.pending > 0 && (
            <div
              className="h-full bg-yellow-500"
              style={{ width: `${(progress.pending / progress.total) * 100}%` }}
            />
          )}
        </div>
        <div className="text-sm text-gray-600 whitespace-nowrap">
          <span className="text-green-600 font-medium">{progress.approved}</span>
          <span className="text-gray-400">/</span>
          <span>{progress.total}</span> 已批准
        </div>
      </div>
    );
  };

  const mockUsers = [
    { id: '1', name: '张三', email: 'zhangsan@example.com' },
    { id: '2', name: '李四', email: 'lisi@example.com' },
    { id: '3', name: '王五', email: 'wangwu@example.com' },
  ];

  const availableReviewers = mockUsers.filter(
    (u) => !review?.reviewers.some((r) => r.userId === u.id) && u.id !== currentUserId
  );

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
      </div>
    );
  }

  if (!review) {
    return (
      <div className="text-center py-12 text-gray-500">
        审阅不存在或无权限访问
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto">
      <div className="flex items-center gap-4 mb-6">
        <button
          onClick={onBack}
          className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex-1">
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-semibold text-gray-900">
              {review.document.title}
            </h1>
            <span
              className={cn(
                'text-sm px-3 py-1 rounded-full',
                getStatusColor(review.status)
              )}
            >
              {getStatusText(review.status)}
            </span>
          </div>
          <div className="flex items-center gap-4 mt-1 text-sm text-gray-500">
            <span>版本 {review.version.version}</span>
            <span>发起者: {review.author.name}</span>
            <span>{formatTimeAgo(review.createdAt)}</span>
          </div>
        </div>

        {isReviewer && !hasSubmitted && (
          <button
            onClick={() => setShowSubmitDialog(true)}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 flex items-center gap-2"
          >
            <Send className="w-4 h-4" />
            提交审阅
          </button>
        )}
      </div>

      {review.comment && (
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-4 mb-6">
          <div className="text-sm font-medium text-gray-700 mb-1">审阅说明</div>
          <p className="text-gray-600">{review.comment}</p>
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-lg p-4 mb-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-semibold text-gray-900">审阅进度</h2>
          {isAuthor && (
            <button
              onClick={() => setShowAddReviewer(true)}
              className="text-sm text-blue-600 hover:text-blue-800 flex items-center gap-1"
            >
              <UserPlus className="w-4 h-4" />
              添加审阅人
            </button>
          )}
        </div>
        {renderProgressBar(progress)}
      </div>

      <div className="bg-white border border-gray-200 rounded-lg mb-6">
        <div className="p-4 border-b border-gray-200">
          <h2 className="font-semibold text-gray-900">审阅人</h2>
        </div>
        <div className="divide-y divide-gray-100">
          {review.reviewers.map((reviewer) => (
            <div
              key={reviewer.id}
              className="p-4 flex items-start gap-4"
            >
              <div className="flex-shrink-0">
                {reviewer.user.avatar ? (
                  <img
                    src={reviewer.user.avatar}
                    alt={reviewer.user.name}
                    className="w-10 h-10 rounded-full"
                  />
                ) : (
                  <div className="w-10 h-10 rounded-full bg-blue-500 text-white flex items-center justify-center font-medium">
                    {getInitials(reviewer.user.name)}
                  </div>
                )}
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="font-medium text-gray-900">
                    {reviewer.user.name}
                  </span>
                  {reviewer.userId === currentUserId && (
                    <span className="text-xs bg-blue-100 text-blue-800 px-2 py-0.5 rounded-full">
                      您
                    </span>
                  )}
                  <span
                    className={cn(
                      'text-xs px-2 py-0.5 rounded-full',
                      getStatusColor(reviewer.status)
                    )}
                  >
                    {getStatusText(reviewer.status)}
                  </span>
                </div>
                <div className="text-sm text-gray-500 mt-0.5">
                  {reviewer.user.email}
                </div>
                {reviewer.comment && (
                  <div className="mt-2 p-3 bg-gray-50 rounded-lg">
                    <p className="text-sm text-gray-700">{reviewer.comment}</p>
                    {reviewer.reviewedAt && (
                      <p className="text-xs text-gray-500 mt-1">
                        {formatTimeAgo(reviewer.reviewedAt)}
                      </p>
                    )}
                  </div>
                )}
              </div>
              <div className="flex-shrink-0">
                {renderStatusIcon(reviewer.status)}
              </div>
            </div>
          ))}
        </div>
      </div>

      {review.comments && review.comments.length > 0 && (
        <div className="bg-white border border-gray-200 rounded-lg">
          <div className="p-4 border-b border-gray-200 flex items-center gap-2">
            <MessageSquare className="w-5 h-5 text-gray-500" />
            <h2 className="font-semibold text-gray-900">评论 ({review.comments.length})</h2>
          </div>
          <div className="p-4 space-y-4">
            {review.comments.map((comment) => (
              <CommentThread
                key={comment.id}
                comment={comment as unknown as CommentWithRelations}
                currentUserId={currentUserId}
                canResolve={canResolve}
                onNavigate={onNavigate}
              />
            ))}
          </div>
        </div>
      )}

      <Dialog.Root open={showAddReviewer} onOpenChange={setShowAddReviewer}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 bg-black/50 z-50" />
          <Dialog.Content className="fixed top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-full max-w-md bg-white rounded-lg shadow-xl z-50">
            <div className="p-4 border-b border-gray-200">
              <Dialog.Title className="text-lg font-semibold">
                添加审阅人
              </Dialog.Title>
            </div>
            <div className="p-4">
              <label className="block text-sm font-medium text-gray-700 mb-2">
                选择用户
              </label>
              <Select.Root
                value={selectedReviewer}
                onValueChange={setSelectedReviewer}
              >
                <Select.Trigger className="w-full p-3 border border-gray-300 rounded-lg flex items-center justify-between">
                  <Select.Value placeholder="选择用户..." />
                  <Select.Icon>
                    <ArrowLeft className="w-4 h-4 rotate-270" />
                  </Select.Icon>
                </Select.Trigger>
                <Select.Portal>
                  <Select.Content className="bg-white border border-gray-200 rounded-lg shadow-lg z-50 max-h-60 overflow-y-auto">
                    <Select.Viewport>
                      {availableReviewers.map((user) => (
                        <Select.Item
                          key={user.id}
                          value={user.id}
                          className="p-3 hover:bg-gray-50 cursor-pointer flex items-center gap-3"
                        >
                          <div className="w-8 h-8 rounded-full bg-blue-500 text-white flex items-center justify-center text-sm font-medium">
                            {getInitials(user.name)}
                          </div>
                          <div>
                            <Select.ItemText className="text-sm font-medium">
                              {user.name}
                            </Select.ItemText>
                            <div className="text-xs text-gray-500">
                              {user.email}
                            </div>
                          </div>
                        </Select.Item>
                      ))}
                    </Select.Viewport>
                  </Select.Content>
                </Select.Portal>
              </Select.Root>
              <div className="flex justify-end gap-2 mt-4">
                <button
                  onClick={() => setShowAddReviewer(false)}
                  className="px-4 py-2 border border-gray-300 rounded-lg text-sm hover:bg-gray-50"
                >
                  取消
                </button>
                <button
                  onClick={handleAddReviewer}
                  disabled={!selectedReviewer || addReviewer.isPending}
                  className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50"
                >
                  添加
                </button>
              </div>
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>

      <Dialog.Root open={showSubmitDialog} onOpenChange={setShowSubmitDialog}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 bg-black/50 z-50" />
          <Dialog.Content className="fixed top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-full max-w-lg bg-white rounded-lg shadow-xl z-50">
            <div className="p-4 border-b border-gray-200">
              <Dialog.Title className="text-lg font-semibold">
                提交审阅意见
              </Dialog.Title>
            </div>
            <div className="p-4 space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-3">
                  您的决定
                </label>
                <div className="grid grid-cols-2 gap-3">
                  <button
                    type="button"
                    onClick={() => setDecision('APPROVED')}
                    className={cn(
                      'p-4 border-2 rounded-lg text-left transition-all',
                      decision === 'APPROVED'
                        ? 'border-green-500 bg-green-50'
                        : 'border-gray-200 hover:border-gray-300'
                    )}
                  >
                    <CheckCircle2
                      className={cn(
                        'w-8 h-8 mb-2',
                        decision === 'APPROVED'
                          ? 'text-green-500'
                          : 'text-gray-400'
                      )}
                    />
                    <div className="font-medium text-gray-900">批准</div>
                    <div className="text-sm text-gray-500">
                      文档可以合并
                    </div>
                  </button>
                  <button
                    type="button"
                    onClick={() => setDecision('CHANGES_REQUESTED')}
                    className={cn(
                      'p-4 border-2 rounded-lg text-left transition-all',
                      decision === 'CHANGES_REQUESTED'
                        ? 'border-orange-500 bg-orange-50'
                        : 'border-gray-200 hover:border-gray-300'
                    )}
                  >
                    <XCircle
                      className={cn(
                        'w-8 h-8 mb-2',
                        decision === 'CHANGES_REQUESTED'
                          ? 'text-orange-500'
                          : 'text-gray-400'
                      )}
                    />
                    <div className="font-medium text-gray-900">需要修改</div>
                    <div className="text-sm text-gray-500">
                      需要修改后才能批准
                    </div>
                  </button>
                </div>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  审阅意见
                </label>
                <textarea
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  placeholder="请输入您的审阅意见..."
                  className="w-full p-3 border border-gray-300 rounded-lg resize-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  rows={4}
                />
              </div>

              <div className="flex justify-end gap-2">
                <button
                  onClick={() => {
                    setShowSubmitDialog(false);
                    setDecision(null);
                    setComment('');
                  }}
                  className="px-4 py-2 border border-gray-300 rounded-lg text-sm hover:bg-gray-50"
                >
                  取消
                </button>
                <button
                  onClick={handleSubmit}
                  disabled={!decision || !comment.trim() || submitReview.isPending}
                  className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50 flex items-center gap-2"
                >
                  <Send className="w-4 h-4" />
                  提交
                </button>
              </div>
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}
