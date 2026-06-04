'use client';

import { useParams, useRouter } from 'next/navigation';
import {
  History,
  ChevronLeft,
  Clock,
  User,
  ArrowLeftRight,
  Loader2,
  FileText,
  RotateCcw,
  Download,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
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
import { formatTimeAgo } from '@/lib/utils';
import { useState } from 'react';

export default function VersionDetailPage() {
  const params = useParams<{
    spaceId: string;
    documentId: string;
    versionId: string;
  }>();
  const router = useRouter();
  const { toast } = useToast();
  const spaceId = params?.spaceId as string;
  const documentId = params?.documentId as string;
  const versionId = params?.versionId as string;

  const [showRestoreDialog, setShowRestoreDialog] = useState(false);

  const { data: version, isLoading } = trpc.document.getVersion.useQuery(
    { documentId, version: parseInt(versionId, 10) },
    { enabled: !!versionId && !!documentId }
  );

  const { data: doc } = trpc.document.getById.useQuery(
    { id: documentId },
    { enabled: !!documentId }
  );

  const { data: userRole } = trpc.space.getUserRole.useQuery(
    { spaceId },
    { enabled: !!spaceId }
  );

  const canEdit =
    userRole?.role === 'OWNER' ||
    userRole?.role === 'ADMIN' ||
    userRole?.role === 'EDITOR';

  const handleBack = () => {
    router.push(
      `/spaces/${spaceId}/documents/${documentId}/versions`
    );
  };

  const handleBackToDocument = () => {
    router.push(`/spaces/${spaceId}/documents/${documentId}`);
  };

  const handleCompareWithCurrent = () => {
    router.push(
      `/spaces/${spaceId}/documents/${documentId}/compare?v1=${versionId}&v2=current`
    );
  };

  const handleRestore = () => {
    toast({
      title: '功能开发中',
      description: '版本回滚功能即将上线',
    });
    setShowRestoreDialog(false);
  };

  const handleDownload = () => {
    if (!version) return;
    const blob = new Blob([version.content || ''], { type: 'text/markdown' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${version.title || 'document'}-v${version.version}.md`;
    a.click();
    URL.revokeObjectURL(url);
    toast({
      title: '下载成功',
      description: '文件已下载',
      variant: 'success',
    });
  };

  if (isLoading) {
    return (
      <div className="p-8 max-w-4xl mx-auto">
        <div className="flex items-center gap-4 mb-8">
          <Skeleton className="h-9 w-9 rounded-md" />
          <div>
            <Skeleton className="h-8 w-48 mb-2" />
            <Skeleton className="h-4 w-64" />
          </div>
        </div>
        <Skeleton className="h-16 w-full rounded-lg mb-6" />
        <div className="space-y-3">
          <Skeleton className="h-6 w-3/4" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-5/6" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-2/3" />
        </div>
      </div>
    );
  }

  if (!version) {
    return (
      <div className="p-8">
        <Alert variant="destructive">
          <AlertTitle>版本不存在</AlertTitle>
          <AlertDescription>
            您访问的版本不存在或已被删除。
          </AlertDescription>
        </Alert>
        <Button className="mt-4" onClick={handleBack}>
          <ChevronLeft className="mr-2 h-4 w-4" />
          返回版本列表
        </Button>
      </div>
    );
  }

  return (
    <div className="p-8 max-w-4xl mx-auto">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-8">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="icon" onClick={handleBack}>
            <ChevronLeft className="h-5 w-5" />
          </Button>
          <div className="p-2 rounded-lg bg-primary/10">
            <History className="h-6 w-6 text-primary" />
          </div>
          <div>
            <h1 className="text-2xl font-bold">
              版本 {version.version}
            </h1>
            <p className="text-muted-foreground">
              {version.title || doc?.title || '无标题文档'}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={handleBackToDocument}>
            <FileText className="mr-2 h-4 w-4" />
            查看当前版本
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={handleCompareWithCurrent}
          >
            <ArrowLeftRight className="mr-2 h-4 w-4" />
            对比当前版本
          </Button>
          <Button variant="outline" size="sm" onClick={handleDownload}>
            <Download className="mr-2 h-4 w-4" />
            下载
          </Button>
          {canEdit && (
            <Dialog
              open={showRestoreDialog}
              onOpenChange={setShowRestoreDialog}
            >
              <DialogTrigger asChild>
                <Button size="sm">
                  <RotateCcw className="mr-2 h-4 w-4" />
                  恢复此版本
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>确认恢复版本</DialogTitle>
                  <DialogDescription>
                    确定要将文档恢复到版本 {version.version} 吗？
                    <br />
                    当前版本的内容将被替换，但仍会保存在历史记录中。
                  </DialogDescription>
                </DialogHeader>
                <DialogFooter>
                  <Button
                    variant="outline"
                    onClick={() => setShowRestoreDialog(false)}
                  >
                    取消
                  </Button>
                  <Button onClick={handleRestore}>
                    确认恢复
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>
          )}
        </div>
      </div>

      <Card className="mb-6">
        <CardContent className="p-4">
          <div className="flex flex-wrap items-center gap-4 text-sm">
            <div className="flex items-center gap-2">
              <Badge variant="outline">版本 {version.version}</Badge>
            </div>
            <div className="flex items-center gap-1.5 text-muted-foreground">
              <User className="h-4 w-4" />
              {version.createdBy?.name || '未知作者'}
            </div>
            <div className="flex items-center gap-1.5 text-muted-foreground">
              <Clock className="h-4 w-4" />
              {formatTimeAgo(version.createdAt)}
            </div>
            {(version.changeSummary || version.message) && (
              <div className="w-full sm:w-auto sm:col-span-full text-muted-foreground">
                <span className="font-medium text-foreground">备注：</span>
                {version.changeSummary || version.message}
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-8">
          <article className="prose prose-lg max-w-none dark:prose-invert">
            <h1 className="text-3xl font-bold mb-6">
              {version.title || '无标题'}
            </h1>
            <div
              dangerouslySetInnerHTML={{
                __html: version.content || '<p>暂无内容</p>',
              }}
            />
          </article>
        </CardContent>
      </Card>
    </div>
  );
}
