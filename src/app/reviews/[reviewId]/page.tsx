'use client';

import { useParams, useRouter } from 'next/navigation';
import {
  FileCheck,
  FileX,
  Clock,
  User,
  ChevronLeft,
  MessageSquare,
  Send,
  FileText,
  AlertTriangle,
  Loader2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Textarea } from '@/components/ui/textarea';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo, getStatusColor, getStatusText } from '@/lib/utils';
import { useState } from 'react';
import Link from 'next/link';
import { ReviewDetail as ReviewDetailComponent } from '@/components/reviews/ReviewDetail';
import type { CommentWithRelations } from '@/lib/types/comment';

export default function ReviewDetailPage() {
  const params = useParams<{ reviewId: string }>();
  const router = useRouter();
  const { toast } = useToast();
  const reviewId = params?.reviewId as string;

  const [comment, setComment] = useState('');
  const [showApproveDialog, setShowApproveDialog] = useState(false);
  const [showRejectDialog, setShowRejectDialog] = useState(false);
  const [showRequestChangesDialog, setShowRequestChangesDialog] = useState(false);

  const { data: review, isLoading } = trpc.review.getById.useQuery(
    { id: reviewId },
    { enabled: !!reviewId }
  );

  const { data: currentUser } = trpc.auth.getCurrentUser.useQuery();

  const isReviewer = review?.reviewers?.some(
    (r: any) => r.userId === currentUser?.id
  );
  const isRequester = currentUser?.id === review?.createdById;
  const canRespond =
    isReviewer && review?.status === 'PENDING';

  const handleBack = () => {
    router.push('/reviews');
  };

  const handleApprove = () => {
    toast({
      title: '已批准',
      description: '审阅已批准',
      variant: 'success',
    });
    setShowApproveDialog(false);
  };

  const handleReject = () => {
    toast({
      title: '已拒绝',
      description: '审阅已拒绝',
      variant: 'destructive',
    });
    setShowRejectDialog(false);
  };

  const handleRequestChanges = () => {
    toast({
      title: '已发送修改请求',
      description: '修改请求已发送给发起人',
      variant: 'success',
    });
    setShowRequestChangesDialog(false);
    setComment('');
  };

  const handleSubmitComment = () => {
    if (!comment.trim()) return;
    toast({
      title: '评论已发送',
      description: '您的评论已发送',
      variant: 'success',
    });
    setComment('');
  };

  if (isLoading) {
    return (
      <div className="p-8 max-w-4xl mx-auto">
        <div className="flex items-center gap-4 mb-8">
          <Skeleton className="h-9 w-9 rounded-md" />
          <div>
            <Skeleton className="h-8 w-64 mb-2" />
            <Skeleton className="h-4 w-48" />
          </div>
        </div>
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="lg:col-span-2 space-y-6">
            <Skeleton className="h-64 rounded-lg" />
            <Skeleton className="h-48 rounded-lg" />
          </div>
          <div className="space-y-6">
            <Skeleton className="h-32 rounded-lg" />
            <Skeleton className="h-48 rounded-lg" />
          </div>
        </div>
      </div>
    );
  }

  if (!review) {
    return (
      <div className="p-8">
        <Card className="max-w-md mx-auto">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-destructive">
              <AlertTriangle className="h-5 w-5" />
              审阅不存在
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-muted-foreground mb-4">
              您访问的审阅不存在或已被删除。
            </p>
            <Button onClick={handleBack}>
              <ChevronLeft className="mr-2 h-4 w-4" />
              返回审阅列表
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="p-8 max-w-6xl mx-auto">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-8">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="icon" onClick={handleBack}>
            <ChevronLeft className="h-5 w-5" />
          </Button>
          <div>
            <h1 className="text-2xl font-bold">{review.title}</h1>
            <p className="text-muted-foreground">
              {review.document?.title || '无标题文档'}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Badge className={getStatusColor(review.status)}>
            {getStatusText(review.status)}
          </Badge>
          {canRespond && (
            <>
              <Dialog
                open={showApproveDialog}
                onOpenChange={setShowApproveDialog}
              >
                <DialogTrigger asChild>
                  <Button size="sm">
                    <FileCheck className="mr-1.5 h-4 w-4" />
                    批准
                  </Button>
                </DialogTrigger>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle>确认批准</DialogTitle>
                    <DialogDescription>
                      确定要批准此文档吗？批准后文档将可以发布。
                    </DialogDescription>
                  </DialogHeader>
                  <DialogFooter>
                    <Button
                      variant="outline"
                      onClick={() => setShowApproveDialog(false)}
                    >
                      取消
                    </Button>
                    <Button onClick={handleApprove}>
                      确认批准
                    </Button>
                  </DialogFooter>
                </DialogContent>
              </Dialog>

              <Dialog
                open={showRequestChangesDialog}
                onOpenChange={setShowRequestChangesDialog}
              >
                <DialogTrigger asChild>
                  <Button size="sm" variant="outline">
                    <MessageSquare className="mr-1.5 h-4 w-4" />
                    请求修改
                  </Button>
                </DialogTrigger>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle>请求修改</DialogTitle>
                    <DialogDescription>
                      请描述需要修改的内容：
                    </DialogDescription>
                  </DialogHeader>
                  <div className="py-4">
                    <Textarea
                      placeholder="请详细描述需要修改的内容..."
                      value={comment}
                      onChange={(e) => setComment(e.target.value)}
                      rows={4}
                    />
                  </div>
                  <DialogFooter>
                    <Button
                      variant="outline"
                      onClick={() => setShowRequestChangesDialog(false)}
                    >
                      取消
                    </Button>
                    <Button onClick={handleRequestChanges}>
                      发送
                    </Button>
                  </DialogFooter>
                </DialogContent>
              </Dialog>

              <Dialog
                open={showRejectDialog}
                onOpenChange={setShowRejectDialog}
              >
                <DialogTrigger asChild>
                  <Button size="sm" variant="destructive">
                    <FileX className="mr-1.5 h-4 w-4" />
                    拒绝
                  </Button>
                </DialogTrigger>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle>确认拒绝</DialogTitle>
                    <DialogDescription>
                      确定要拒绝此文档吗？拒绝后需要重新提交审阅。
                    </DialogDescription>
                  </DialogHeader>
                  <DialogFooter>
                    <Button
                      variant="outline"
                      onClick={() => setShowRejectDialog(false)}
                    >
                      取消
                    </Button>
                    <Button variant="destructive" onClick={handleReject}>
                      确认拒绝
                    </Button>
                  </DialogFooter>
                </DialogContent>
              </Dialog>
            </>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-6">
          <ReviewDetailComponent
            reviewId={reviewId}
            currentUserId={currentUser?.id || ''}
            canResolve={isReviewer || isRequester}
            onBack={handleBack}
          />

          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <MessageSquare className="h-5 w-5" />
                评论
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex gap-3">
                <Avatar className="h-10 w-10">
                  <AvatarImage
                    src={currentUser?.avatar || ''}
                    alt={currentUser?.name}
                  />
                  <AvatarFallback>
                    {currentUser?.name?.charAt(0) || 'U'}
                  </AvatarFallback>
                </Avatar>
                <div className="flex-1 space-y-2">
                  <Textarea
                    placeholder="添加评论..."
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    rows={3}
                  />
                  <div className="flex justify-end">
                    <Button
                      size="sm"
                      onClick={handleSubmitComment}
                      disabled={!comment.trim()}
                    >
                      <Send className="mr-1.5 h-4 w-4" />
                      发送
                    </Button>
                  </div>
                </div>
              </div>

              <Separator />

              <div className="space-y-4">
                {review.comments?.length === 0 ? (
                  <div className="text-center py-8 text-muted-foreground">
                    <MessageSquare className="h-8 w-8 mx-auto mb-2 opacity-50" />
                    <p>暂无评论</p>
                  </div>
                ) : (
                  review.comments?.map((c: CommentWithRelations) => (
                    <div key={c.id} className="flex gap-3">
                      <Avatar className="h-10 w-10">
                        <AvatarImage
                          src={c.author?.avatar || ''}
                          alt={c.author?.name}
                        />
                        <AvatarFallback>
                          {c.author?.name?.charAt(0) || 'U'}
                        </AvatarFallback>
                      </Avatar>
                      <div className="flex-1">
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-sm">
                            {c.author?.name}
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {formatTimeAgo(c.createdAt)}
                          </span>
                        </div>
                        <p className="text-sm mt-1">{c.content}</p>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </CardContent>
          </Card>
        </div>

        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">审阅信息</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-lg bg-primary/10">
                  <User className="h-5 w-5 text-primary" />
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">发起人</p>
                  <p className="text-sm font-medium">
                    {review.createdBy?.name || '未知'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-lg bg-green-50 dark:bg-green-900/20">
                  <User className="h-5 w-5 text-green-600" />
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">审阅人</p>
                  <p className="text-sm font-medium">
                    {review.reviewers?.map((r: any) => r.user?.name).join(', ') || '未知'}
                  </p>
                </div>
              </div>
              <Separator />
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-lg bg-blue-50 dark:bg-blue-900/20">
                  <Clock className="h-5 w-5 text-blue-600" />
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">创建时间</p>
                  <p className="text-sm font-medium">
                    {formatTimeAgo(review.createdAt)}
                  </p>
                </div>
              </div>
              {review.deadline && (
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded-lg bg-orange-50 dark:bg-orange-900/20">
                    <Clock className="h-5 w-5 text-orange-600" />
                  </div>
                  <div>
                    <p className="text-xs text-muted-foreground">截止时间</p>
                    <p className="text-sm font-medium">
                      {formatTimeAgo(review.deadline)}
                    </p>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-sm">关联文档</CardTitle>
            </CardHeader>
            <CardContent>
              <Link
                href={`/spaces/${review.document?.spaceId}/documents/${review.documentId}`}
                className="flex items-center gap-3 p-3 rounded-lg hover:bg-muted transition-colors"
              >
                <div className="p-2 rounded bg-muted">
                  <FileText className="h-5 w-5 text-muted-foreground" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-sm truncate">
                    {review.document?.title || '无标题文档'}
                  </p>
                  <p className="text-xs text-muted-foreground truncate">
                    {review.document?.content?.slice(0, 50)}
                  </p>
                </div>
              </Link>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
