'use client';

import { useParams, useRouter } from 'next/navigation';
import {
  History,
  ChevronLeft,
  Clock,
  User,
  GitCompare,
  ArrowLeftRight,
  Loader2,
  FileText,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo } from '@/lib/utils';
import { VersionHistory } from '@/components/versions/VersionHistory';

export default function DocumentVersionsPage() {
  const params = useParams<{ spaceId: string; documentId: string }>();
  const router = useRouter();
  const { toast } = useToast();
  const spaceId = params?.spaceId as string;
  const documentId = params?.documentId as string;

  const { data: document, isLoading: docLoading } =
    trpc.document.getById.useQuery(
      { id: documentId },
      { enabled: !!documentId }
    );

  const { data: versions, isLoading: versionsLoading } =
    trpc.document.listVersions.useQuery(
      { documentId, pageSize: 50 },
      { enabled: !!documentId }
    );

  const handleBack = () => {
    router.push(`/spaces/${spaceId}/documents/${documentId}`);
  };

  const handleCompare = (v1: number, v2: number) => {
    router.push(
      `/spaces/${spaceId}/documents/${documentId}/compare?v1=${v1}&v2=${v2}`
    );
  };

  const handleViewVersion = (versionId: string) => {
    router.push(
      `/spaces/${spaceId}/documents/${documentId}/versions/${versionId}`
    );
  };

  const handleRestore = (versionId: string) => {
    toast({
      title: '功能开发中',
      description: '版本回滚功能即将上线',
    });
  };

  if (docLoading || versionsLoading) {
    return (
      <div className="p-8 max-w-4xl mx-auto">
        <div className="flex items-center gap-4 mb-8">
          <Skeleton className="h-9 w-9 rounded-md" />
          <div>
            <Skeleton className="h-8 w-48 mb-2" />
            <Skeleton className="h-4 w-64" />
          </div>
        </div>
        <div className="space-y-4">
          {[1, 2, 3, 4, 5].map((i) => (
            <Skeleton key={i} className="h-24 rounded-lg" />
          ))}
        </div>
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
            <h1 className="text-2xl font-bold">版本历史</h1>
            <p className="text-muted-foreground">
              {document?.title || '无标题文档'}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="outline">
            共 {versions?.items.length || 0} 个版本
          </Badge>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-lg">历史版本</CardTitle>
        </CardHeader>
        <CardContent>
          <VersionHistory
            documentId={documentId}
            onCompare={handleCompare}
            onRestore={handleRestore}
            onVersionSelect={(version) => handleViewVersion(version.id)}
          />
        </CardContent>
      </Card>

      <div className="mt-6 p-4 rounded-lg bg-muted/50">
        <h3 className="font-medium mb-2 flex items-center gap-2">
          <GitCompare className="h-4 w-4" />
          关于版本控制
        </h3>
        <p className="text-sm text-muted-foreground">
          每次保存文档时，系统会自动创建一个新版本。您可以查看历史版本、对比不同版本之间的差异，或回滚到指定版本。
          版本历史永久保存，方便您追踪文档的变更过程。
        </p>
      </div>
    </div>
  );
}
