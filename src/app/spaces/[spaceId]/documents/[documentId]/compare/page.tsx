'use client';

import { useParams, useRouter, useSearchParams } from 'next/navigation';
import {
  GitCompare,
  ChevronLeft,
  Clock,
  User,
  ArrowLeftRight,
  Loader2,
  FileText,
  ChevronDown,
  ChevronUp,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo } from '@/lib/utils';
import { useState, useMemo } from 'react';
import { VersionDiffViewer } from '@/components/versions/VersionDiffViewer';
import type { Version } from '@/lib/types/version';

export default function CompareVersionsPage() {
  const params = useParams<{ spaceId: string; documentId: string }>();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { toast } = useToast();
  const spaceId = params?.spaceId as string;
  const documentId = params?.documentId as string;

  const [v1, setV1] = useState(searchParams.get('v1') || '');
  const [v2, setV2] = useState(searchParams.get('v2') || 'current');
  const [viewMode, setViewMode] = useState<'split' | 'unified' | 'inline'>('split');
  const [showOnlyChanges, setShowOnlyChanges] = useState(false);

  const { data: document, isLoading: docLoading } =
    trpc.document.getById.useQuery(
      { id: documentId },
      { enabled: !!documentId }
    );

  const { data: versions, isLoading: versionsLoading } =
    trpc.document.listVersions.useQuery(
      { documentId, pageSize: 100 },
      { enabled: !!documentId }
    );

  const { data: diff, isLoading: diffLoading } = trpc.document.compareVersions.useQuery(
    { documentId, versionFrom: parseInt(v1), versionTo: v2 === 'current' ? 0 : parseInt(v2) },
    { enabled: !!v1 && (!!v2 || v2 === 'current') }
  );

  const handleBack = () => {
    router.push(`/spaces/${spaceId}/documents/${documentId}`);
  };

  const handleSwap = () => {
    const temp = v1;
    setV1(v2);
    setV2(temp);
  };

  const versionOptions = useMemo(() => {
    const options = versions?.items.map((v: Version) => ({
      id: v.id,
      label: `版本 ${v.version} - ${formatTimeAgo(v.createdAt)}`,
      version: v.version,
    })) || [];
    return [
      { id: 'current', label: '当前版本', version: 'current' },
      ...options,
    ];
  }, [versions]);

  const selectedV1Info = versionOptions.find((v) => v.id === v1);
  const selectedV2Info = versionOptions.find((v) => v.id === v2);

  if (docLoading || versionsLoading) {
    return (
      <div className="p-8 max-w-6xl mx-auto">
        <div className="flex items-center gap-4 mb-8">
          <Skeleton className="h-9 w-9 rounded-md" />
          <div>
            <Skeleton className="h-8 w-48 mb-2" />
            <Skeleton className="h-4 w-64" />
          </div>
        </div>
        <Skeleton className="h-16 w-full rounded-lg mb-6" />
        <div className="grid grid-cols-2 gap-4">
          <Skeleton className="h-96 rounded-lg" />
          <Skeleton className="h-96 rounded-lg" />
        </div>
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
          <div className="p-2 rounded-lg bg-primary/10">
            <GitCompare className="h-6 w-6 text-primary" />
          </div>
          <div>
            <h1 className="text-2xl font-bold">版本对比</h1>
            <p className="text-muted-foreground">
              {document?.title || '无标题文档'}
            </p>
          </div>
        </div>
      </div>

      <Card className="mb-6">
        <CardContent className="p-4">
          <div className="flex flex-col lg:flex-row lg:items-center gap-4">
            <div className="flex-1">
              <label className="text-sm font-medium mb-1.5 block">
                旧版本
              </label>
              <Select value={v1} onValueChange={setV1}>
                <SelectTrigger>
                  <SelectValue placeholder="选择要对比的旧版本" />
                </SelectTrigger>
                <SelectContent>
                  {versionOptions
                    .filter((v) => v.id !== v2)
                    .map((v) => (
                      <SelectItem key={v.id} value={v.id}>
                        {v.label}
                      </SelectItem>
                    ))}
                </SelectContent>
              </Select>
            </div>

            <div className="flex items-end justify-center">
              <Button
                variant="outline"
                size="icon"
                onClick={handleSwap}
                className="mb-0.5"
              >
                <ArrowLeftRight className="h-4 w-4" />
              </Button>
            </div>

            <div className="flex-1">
              <label className="text-sm font-medium mb-1.5 block">
                新版本
              </label>
              <Select value={v2} onValueChange={setV2}>
                <SelectTrigger>
                  <SelectValue placeholder="选择要对比的新版本" />
                </SelectTrigger>
                <SelectContent>
                  {versionOptions
                    .filter((v) => v.id !== v1)
                    .map((v) => (
                      <SelectItem key={v.id} value={v.id}>
                        {v.label}
                      </SelectItem>
                    ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          {selectedV1Info && selectedV2Info && (
            <div className="flex flex-wrap items-center gap-4 mt-4 pt-4 border-t text-sm">
              <div className="flex items-center gap-2">
                <Badge variant="outline" className="bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-300 border-red-200 dark:border-red-800">
                  {selectedV1Info.version === 'current' ? '当前' : `v${selectedV1Info.version}`}
                </Badge>
                <span className="text-muted-foreground">
                  →
                </span>
                <Badge variant="outline" className="bg-green-50 text-green-700 dark:bg-green-900/20 dark:text-green-300 border-green-200 dark:border-green-800">
                  {selectedV2Info.version === 'current' ? '当前' : `v${selectedV2Info.version}`}
                </Badge>
              </div>
              {diff && (
                <div className="flex items-center gap-3 ml-auto">
                  <Badge variant="secondary">
                    {(diff as any).additions ?? 0} 处添加
                  </Badge>
                  <Badge variant="destructive">
                    {(diff as any).deletions ?? 0} 处删除
                  </Badge>
                  <Badge variant="outline">
                    {(diff as any).changes ?? 0} 处变更
                  </Badge>
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {v1 && v2 ? (
        <>
          <div className="flex items-center justify-between mb-4">
            <Tabs
              value={viewMode}
              onValueChange={(v) => setViewMode(v as typeof viewMode)}
            >
              <TabsList>
                <TabsTrigger value="split">分栏视图</TabsTrigger>
                <TabsTrigger value="inline">行内视图</TabsTrigger>
                <TabsTrigger value="unified">统一视图</TabsTrigger>
              </TabsList>
            </Tabs>

            <div className="flex items-center gap-2">
              <Button
                variant={showOnlyChanges ? 'default' : 'outline'}
                size="sm"
                onClick={() => setShowOnlyChanges(!showOnlyChanges)}
              >
                {showOnlyChanges ? (
                  <ChevronUp className="mr-2 h-4 w-4" />
                ) : (
                  <ChevronDown className="mr-2 h-4 w-4" />
                )}
                {showOnlyChanges ? '显示全部' : '仅显示变更'}
              </Button>
            </div>
          </div>

          {diffLoading ? (
            <Card>
              <CardContent className="p-8">
                <div className="flex flex-col items-center justify-center text-muted-foreground">
                  <Loader2 className="h-8 w-8 animate-spin mb-4" />
                  <p>正在对比版本...</p>
                </div>
              </CardContent>
            </Card>
          ) : diff ? (
            <VersionDiffViewer
              diff={diff}
              viewMode={viewMode}
              showOnlyChanges={showOnlyChanges}
              oldVersion={selectedV1Info?.version?.toString() || '旧版本'}
              newVersion={selectedV2Info?.version?.toString() || '新版本'}
            />
          ) : null}
        </>
      ) : (
        <Card>
          <CardContent className="p-12 text-center">
            <GitCompare className="h-12 w-12 mx-auto mb-4 text-muted-foreground opacity-50" />
            <h3 className="text-lg font-semibold mb-2">选择版本进行对比</h3>
            <p className="text-muted-foreground">
              请从上方选择两个版本进行对比
            </p>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
