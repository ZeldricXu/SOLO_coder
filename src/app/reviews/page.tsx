'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  FileCheck,
  FileX,
  Clock,
  User,
  ChevronRight,
  Send,
  Inbox,
  Loader2,
  MessageSquare,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo } from '@/lib/utils';
import Link from 'next/link';
import { getStatusColor, getStatusText } from '@/lib/utils';
import type { ReviewStatus } from '@prisma/client';

export default function ReviewsPage() {
  const router = useRouter();
  const { toast } = useToast();

  const [tab, setTab] = useState<'received' | 'sent'>('received');
  const [statusFilter, setStatusFilter] = useState<ReviewStatus | 'all'>('all');

  const { data: receivedData, isLoading: receivedLoading } =
    trpc.review.list.useQuery(
      {
        asReviewer: true,
        status: statusFilter === 'all' ? undefined : statusFilter,
      },
      { enabled: tab === 'received' }
    );

  const { data: sentData, isLoading: sentLoading } =
    trpc.review.list.useQuery(
      {
        asCreator: true,
        status: statusFilter === 'all' ? undefined : statusFilter,
      },
      { enabled: tab === 'sent' }
    );

  const isLoading = tab === 'received' ? receivedLoading : sentLoading;
  const reviews = tab === 'received' ? receivedData?.items : sentData?.items;

  const handleApprove = (reviewId: string) => {
    toast({
      title: '已批准',
      description: '审阅已批准',
      variant: 'success',
    });
  };

  const handleReject = (reviewId: string) => {
    toast({
      title: '已拒绝',
      description: '审阅已拒绝',
      variant: 'destructive',
    });
  };

  if (isLoading) {
    return (
      <div className="p-8 max-w-4xl mx-auto">
        <div className="flex items-center justify-between mb-8">
          <div>
            <Skeleton className="h-8 w-32 mb-2" />
            <Skeleton className="h-4 w-64" />
          </div>
          <Skeleton className="h-10 w-32" />
        </div>
        <Skeleton className="h-10 w-full mb-6" />
        <div className="space-y-4">
          {[1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-24 rounded-lg" />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="container mx-auto px-4 py-6">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div>
              <h1 className="text-3xl font-bold">审阅中心</h1>
              <p className="text-muted-foreground mt-1">
                管理和处理文档审阅请求
              </p>
            </div>
            <Button onClick={() => router.push('/reviews/new')}>
              <Send className="mr-2 h-4 w-4" />
              发起审阅
            </Button>
          </div>
        </div>
      </header>

      <main className="container mx-auto px-4 py-8">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
          <Tabs
            value={tab}
            onValueChange={(v) => setTab(v as 'received' | 'sent')}
            className="w-full sm:w-auto"
          >
            <TabsList>
              <TabsTrigger value="received" className="gap-2">
                <Inbox className="h-4 w-4" />
                我收到的
                {receivedData?.items && receivedData.items.length > 0 && (
                  <Badge variant="secondary" className="ml-1 h-5 px-1.5 text-xs">
                    {receivedData.items.filter((r: any) => r.status === 'PENDING').length}
                  </Badge>
                )}
              </TabsTrigger>
              <TabsTrigger value="sent" className="gap-2">
                <Send className="h-4 w-4" />
                我发起的
              </TabsTrigger>
            </TabsList>
          </Tabs>

          <Select
            value={statusFilter}
            onValueChange={(v) => setStatusFilter(v as ReviewStatus | 'all')}
          >
            <SelectTrigger className="w-full sm:w-40">
              <SelectValue placeholder="筛选状态" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部状态</SelectItem>
              <SelectItem value="PENDING">待处理</SelectItem>
              <SelectItem value="APPROVED">已批准</SelectItem>
              <SelectItem value="CHANGES_REQUESTED">需要修改</SelectItem>
              <SelectItem value="REJECTED">已拒绝</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {reviews?.length === 0 ? (
          <Card>
            <CardContent className="p-12 text-center">
              <FileCheck className="h-12 w-12 mx-auto mb-4 text-muted-foreground opacity-50" />
              <h3 className="text-lg font-semibold mb-2">暂无审阅</h3>
              <p className="text-muted-foreground">
                {tab === 'received'
                  ? '您暂时没有收到审阅请求'
                  : '您暂时没有发起的审阅'}
              </p>
            </CardContent>
          </Card>
        ) : (
          <div className="space-y-4">
            {reviews?.map((review: any) => (
              <Card
                key={review.id}
                className="hover:border-primary transition-colors"
              >
                <CardContent className="p-4">
                  <div className="flex items-start gap-4">
                    <div
                      className={`p-2 rounded-lg mt-0.5 ${
                        review.status === 'PENDING'
                          ? 'bg-yellow-50 dark:bg-yellow-900/20'
                          : review.status === 'APPROVED'
                          ? 'bg-green-50 dark:bg-green-900/20'
                          : review.status === 'REJECTED'
                          ? 'bg-red-50 dark:bg-red-900/20'
                          : 'bg-orange-50 dark:bg-orange-900/20'
                      }`}
                    >
                      {review.status === 'PENDING' && (
                        <Clock className="h-5 w-5 text-yellow-600" />
                      )}
                      {review.status === 'APPROVED' && (
                        <FileCheck className="h-5 w-5 text-green-600" />
                      )}
                      {review.status === 'REJECTED' && (
                        <FileX className="h-5 w-5 text-red-600" />
                      )}
                      {review.status === 'CHANGES_REQUESTED' && (
                        <MessageSquare className="h-5 w-5 text-orange-600" />
                      )}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-start justify-between gap-4">
                        <div className="flex-1 min-w-0">
                          <Link
                            href={`/reviews/${review.id}`}
                            className="hover:underline"
                          >
                            <h4 className="font-medium truncate">
                              {review.title}
                            </h4>
                          </Link>
                          <p className="text-sm text-muted-foreground mt-1 line-clamp-1">
                            {review.document?.title || '无标题文档'}
                          </p>
                        </div>
                        <Badge
                          className={`shrink-0 ${getStatusColor(review.status)}`}
                        >
                          {getStatusText(review.status)}
                        </Badge>
                      </div>

                      <div className="flex flex-wrap items-center gap-3 mt-3 text-xs text-muted-foreground">
                        <div className="flex items-center gap-1">
                          <User className="h-3.5 w-3.5" />
                          {tab === 'received'
                            ? `发起人: ${review.requester?.name || '未知'}`
                            : `审阅人: ${review.reviewer?.name || '未知'}`}
                        </div>
                        <div className="flex items-center gap-1">
                          <Clock className="h-3.5 w-3.5" />
                          {formatTimeAgo(review.createdAt)}
                        </div>
                        {review.deadline && (
                          <div className="flex items-center gap-1">
                            <Clock className="h-3.5 w-3.5" />
                            截止: {formatTimeAgo(review.deadline)}
                          </div>
                        )}
                      </div>

                      {tab === 'received' &&
                        review.status === 'PENDING' && (
                          <div className="flex gap-2 mt-4">
                            <Button
                              size="sm"
                              onClick={() => handleApprove(review.id)}
                            >
                              <FileCheck className="mr-1.5 h-4 w-4" />
                              批准
                            </Button>
                            <Button
                              size="sm"
                              variant="destructive"
                              onClick={() => handleReject(review.id)}
                            >
                              <FileX className="mr-1.5 h-4 w-4" />
                              拒绝
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              asChild
                            >
                              <Link href={`/reviews/${review.id}`}>
                                <MessageSquare className="mr-1.5 h-4 w-4" />
                                评论
                              </Link>
                            </Button>
                          </div>
                        )}
                    </div>
                    <ChevronRight className="h-5 w-5 text-muted-foreground shrink-0 mt-4" />
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}
