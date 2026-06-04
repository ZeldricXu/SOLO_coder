'use client';

import React, { useState } from 'react';
import {
  FileText,
  Clock,
  CheckCircle2,
  XCircle,
  AlertCircle,
  ChevronRight,
  Plus,
  User,
  Eye,
} from 'lucide-react';
import * as Tabs from '@radix-ui/react-tabs';
import type { ReviewWithRelations, ReviewProgress } from '@/lib/types/review';
import { useReviews } from '@/hooks/useReviews';
import { cn, formatTimeAgo, getStatusColor, getStatusText, getInitials } from '@/lib/utils';
import { CreateReviewDialog } from './CreateReviewDialog';

interface ReviewListProps {
  spaceId?: string;
  documentId?: string;
  currentUserId: string;
  onSelectReview?: (reviewId: string) => void;
}

export function ReviewList({
  spaceId,
  documentId,
  currentUserId,
  onSelectReview,
}: ReviewListProps) {
  const [activeTab, setActiveTab] = useState<'created' | 'assigned'>('created');
  const [isCreateDialogOpen, setIsCreateDialogOpen] = useState(false);

  const { data: createdReviews, isLoading: loadingCreated } = useReviews({
    spaceId,
    documentId,
    asAuthor: true,
    page: 1,
    pageSize: 50,
  });

  const { data: assignedReviews, isLoading: loadingAssigned } = useReviews({
    spaceId,
    documentId,
    asReviewer: true,
    page: 1,
    pageSize: 50,
  });

  const getReviewProgress = (review: ReviewWithRelations): ReviewProgress => {
    const progress: ReviewProgress = {
      total: review.reviewers.length,
      approved: 0,
      changesRequested: 0,
      pending: 0,
    };

    review.reviewers.forEach((r) => {
      if (r.status === 'APPROVED') {
        progress.approved++;
      } else if (r.status === 'CHANGES_REQUESTED') {
        progress.changesRequested++;
      } else {
        progress.pending++;
      }
    });

    return progress;
  };

  const renderProgressBar = (progress: ReviewProgress) => {
    if (progress.total === 0) return null;

    return (
      <div className="flex items-center gap-2">
        <div className="flex-1 h-2 bg-gray-200 rounded-full overflow-hidden flex">
          {progress.approved > 0 && (
            <div
              className="h-full bg-green-500"
              style={{ width: `${(progress.approved / progress.total) * 100}%` }}
            />
          )}
          {progress.changesRequested > 0 && (
            <div
              className="h-full bg-orange-500"
              style={{ width: `${(progress.changesRequested / progress.total) * 100}%` }}
            />
          )}
          {progress.pending > 0 && (
            <div
              className="h-full bg-yellow-500"
              style={{ width: `${(progress.pending / progress.total) * 100}%` }}
            />
          )}
        </div>
        <span className="text-xs text-gray-500">
          {progress.approved}/{progress.total} 已批准
        </span>
      </div>
    );
  };

  const renderStatusIcon = (status: string) => {
    switch (status) {
      case 'APPROVED':
        return <CheckCircle2 className="w-4 h-4 text-green-500" />;
      case 'CHANGES_REQUESTED':
        return <XCircle className="w-4 h-4 text-orange-500" />;
      case 'PENDING':
        return <Clock className="w-4 h-4 text-yellow-500" />;
      case 'REJECTED':
        return <AlertCircle className="w-4 h-4 text-red-500" />;
      default:
        return <AlertCircle className="w-4 h-4 text-gray-500" />;
    }
  };

  const renderReviewCard = (review: ReviewWithRelations) => {
    const progress = getReviewProgress(review);

    return (
      <div
        key={review.id}
        onClick={() => onSelectReview?.(review.id)}
        className="p-4 border border-gray-200 rounded-lg hover:border-blue-300 hover:shadow-sm transition-all cursor-pointer"
      >
        <div className="flex items-start justify-between gap-3">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1">
              <FileText className="w-4 h-4 text-gray-400" />
              <h3 className="font-medium text-gray-900 truncate">
                {review.document.title}
              </h3>
              <span
                className={cn(
                  'text-xs px-2 py-0.5 rounded-full',
                  getStatusColor(review.status)
                )}
              >
                {getStatusText(review.status)}
              </span>
            </div>

            {review.comment && (
              <p className="text-sm text-gray-600 line-clamp-2 mb-2">
                {review.comment}
              </p>
            )}

            <div className="flex items-center gap-4 mb-2">
              <div className="flex items-center gap-1 text-xs text-gray-500">
                <User className="w-3 h-3" />
                <span>版本 {review.version.version}</span>
              </div>
              <div className="flex items-center gap-1 text-xs text-gray-500">
                <Eye className="w-3 h-3" />
                <span>{review.reviewers.length} 位审阅人</span>
              </div>
              <div className="flex items-center gap-1 text-xs text-gray-500">
                <Clock className="w-3 h-3" />
                <span>{formatTimeAgo(review.createdAt)}</span>
              </div>
            </div>

            {renderProgressBar(progress)}

            <div className="flex items-center gap-2 mt-3">
              <span className="text-xs text-gray-500">审阅人:</span>
              <div className="flex -space-x-2">
                {review.reviewers.slice(0, 3).map((reviewer) => (
                  <div
                    key={reviewer.id}
                    className="relative"
                    title={`${reviewer.user.name} - ${getStatusText(reviewer.status)}`}
                  >
                    {reviewer.user.avatar ? (
                      <img
                        src={reviewer.user.avatar}
                        alt={reviewer.user.name}
                        className="w-6 h-6 rounded-full border-2 border-white"
                      />
                    ) : (
                      <div className="w-6 h-6 rounded-full border-2 border-white bg-blue-500 text-white flex items-center justify-center text-xs font-medium">
                        {getInitials(reviewer.user.name)}
                      </div>
                    )}
                    <div className="absolute -bottom-0.5 -right-0.5">
                      {renderStatusIcon(reviewer.status)}
                    </div>
                  </div>
                ))}
                {review.reviewers.length > 3 && (
                  <div className="w-6 h-6 rounded-full border-2 border-white bg-gray-200 text-gray-600 flex items-center justify-center text-xs font-medium">
                    +{review.reviewers.length - 3}
                  </div>
                )}
              </div>
            </div>
          </div>

          <ChevronRight className="w-5 h-5 text-gray-400 flex-shrink-0" />
        </div>
      </div>
    );
  };

  const renderContent = (
    data: typeof createdReviews,
    isLoading: boolean,
    emptyText: string
  ) => {
    if (isLoading) {
      return (
        <div className="flex items-center justify-center py-12">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
        </div>
      );
    }

    if (!data?.items || data.items.length === 0) {
      return (
        <div className="text-center py-12">
          <FileText className="w-12 h-12 text-gray-300 mx-auto mb-3" />
          <p className="text-gray-500">{emptyText}</p>
          {activeTab === 'created' && (
            <button
              onClick={() => setIsCreateDialogOpen(true)}
              className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700"
            >
              发起审阅
            </button>
          )}
        </div>
      );
    }

    return (
      <div className="space-y-4">
        {data.items.map((review) => renderReviewCard(review as unknown as ReviewWithRelations))}
      </div>
    );
  };

  return (
    <div className="bg-white rounded-lg border border-gray-200">
      <div className="flex items-center justify-between p-4 border-b border-gray-200">
        <h2 className="text-lg font-semibold text-gray-900">审阅</h2>
        <button
          onClick={() => setIsCreateDialogOpen(true)}
          className="flex items-center gap-1 px-3 py-1.5 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700"
        >
          <Plus className="w-4 h-4" />
          发起审阅
        </button>
      </div>

      <Tabs.Root value={activeTab} onValueChange={(v) => setActiveTab(v as 'created' | 'assigned')}>
        <Tabs.List className="flex border-b border-gray-200 px-4">
          <Tabs.Trigger
            value="created"
            className={cn(
              'px-4 py-3 text-sm font-medium border-b-2 transition-colors',
              activeTab === 'created'
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            )}
          >
            我发起的 ({createdReviews?.total ?? 0})
          </Tabs.Trigger>
          <Tabs.Trigger
            value="assigned"
            className={cn(
              'px-4 py-3 text-sm font-medium border-b-2 transition-colors',
              activeTab === 'assigned'
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            )}
          >
            我收到的 ({assignedReviews?.total ?? 0})
          </Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value="created" className="p-4">
          {renderContent(createdReviews, loadingCreated, '暂无发起的审阅')}
        </Tabs.Content>

        <Tabs.Content value="assigned" className="p-4">
          {renderContent(assignedReviews, loadingAssigned, '暂无分配给您的审阅')}
        </Tabs.Content>
      </Tabs.Root>

      <CreateReviewDialog
        isOpen={isCreateDialogOpen}
        onClose={() => setIsCreateDialogOpen(false)}
        documentId={documentId}
        spaceId={spaceId}
      />
    </div>
  );
}
