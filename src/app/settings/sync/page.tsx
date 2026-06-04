'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  RefreshCw,
  Plus,
  Settings,
  Clock,
  CheckCircle2,
  AlertCircle,
  XCircle,
  ChevronRight,
  Database,
  FileText,
  Github,
  Globe,
  MoreHorizontal,
  Play,
  Pause,
  Trash2,
  Edit,
  Loader2,
  BookOpen,
  FileCode,
  MessageSquare,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Separator } from '@/components/ui/separator';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo, cn } from '@/lib/utils';

type SyncStatus = 'IDLE' | 'SYNCING' | 'SUCCESS' | 'FAILED';
type SyncSourceType = 'FEISHU' | 'NOTION' | 'CONFLUENCE' | 'GITHUB';

interface SyncSource {
  id: string;
  type: SyncSourceType;
  name: string;
  status: SyncStatus;
  lastSyncAt?: Date;
  nextSyncAt?: Date;
  enabled: boolean;
  config: Record<string, any>;
  stats: {
    totalDocuments: number;
    syncedDocuments: number;
    failedDocuments: number;
  };
}

interface SyncLog {
  id: string;
  sourceId: string;
  sourceName: string;
  type: SyncSourceType;
  status: SyncStatus;
  message: string;
  createdAt: Date;
}

const SOURCE_CONFIGS: Record<
  SyncSourceType,
  {
    name: string;
    icon: any;
    description: string;
    color: string;
    bgColor: string;
  }
> = {
  FEISHU: {
    name: '飞书文档',
    icon: BookOpen,
    description: '同步飞书知识库和文档',
    color: 'text-blue-600',
    bgColor: 'bg-blue-50 dark:bg-blue-900/20',
  },
  NOTION: {
    name: 'Notion',
    icon: FileText,
    description: '同步 Notion 页面和数据库',
    color: 'text-gray-800 dark:text-gray-200',
    bgColor: 'bg-gray-100 dark:bg-gray-800',
  },
  CONFLUENCE: {
    name: 'Confluence',
    icon: FileCode,
    description: '同步 Confluence 空间和页面',
    color: 'text-blue-500',
    bgColor: 'bg-blue-50 dark:bg-blue-900/20',
  },
  GITHUB: {
    name: 'GitHub Wiki',
    icon: Github,
    description: '同步 GitHub 仓库 Wiki',
    color: 'text-gray-800 dark:text-gray-200',
    bgColor: 'bg-gray-100 dark:bg-gray-800',
  },
};

const MOCK_SOURCES: SyncSource[] = [
  {
    id: '1',
    type: 'FEISHU',
    name: '产品知识库',
    status: 'SUCCESS',
    lastSyncAt: new Date(Date.now() - 3600000),
    nextSyncAt: new Date(Date.now() + 3600000),
    enabled: true,
    config: {},
    stats: {
      totalDocuments: 156,
      syncedDocuments: 156,
      failedDocuments: 0,
    },
  },
  {
    id: '2',
    type: 'NOTION',
    name: '技术文档',
    status: 'SYNCING',
    lastSyncAt: new Date(Date.now() - 7200000),
    nextSyncAt: new Date(Date.now() + 7200000),
    enabled: true,
    config: {},
    stats: {
      totalDocuments: 89,
      syncedDocuments: 45,
      failedDocuments: 0,
    },
  },
  {
    id: '3',
    type: 'CONFLUENCE',
    name: '团队 Wiki',
    status: 'FAILED',
    lastSyncAt: new Date(Date.now() - 86400000),
    nextSyncAt: new Date(Date.now() + 3600000),
    enabled: true,
    config: {},
    stats: {
      totalDocuments: 234,
      syncedDocuments: 230,
      failedDocuments: 4,
    },
  },
  {
    id: '4',
    type: 'GITHUB',
    name: '开发文档',
    status: 'IDLE',
    enabled: false,
    config: {},
    stats: {
      totalDocuments: 67,
      syncedDocuments: 67,
      failedDocuments: 0,
    },
  },
];

const MOCK_LOGS = [
  {
    id: '1',
    sourceId: '1',
    sourceName: '产品知识库',
    type: 'FEISHU',
    status: 'SUCCESS',
    message: '同步完成，共同步 156 篇文档',
    createdAt: new Date(Date.now() - 3600000),
  },
  {
    id: '2',
    sourceId: '2',
    sourceName: '技术文档',
    type: 'NOTION',
    status: 'SYNCING',
    message: '正在同步，已完成 45/89 篇文档',
    createdAt: new Date(Date.now() - 1800000),
  },
  {
    id: '3',
    sourceId: '3',
    sourceName: '团队 Wiki',
    type: 'CONFLUENCE',
    status: 'FAILED',
    message: '同步失败：4 篇文档同步失败，请检查连接配置',
    createdAt: new Date(Date.now() - 86400000),
  },
];

export default function SyncSettingsPage() {
  const router = useRouter();
  const { toast } = useToast();

  const [sources, setSources] = useState<SyncSource[]>(MOCK_SOURCES);
  const [logs] = useState<SyncLog[]>(MOCK_LOGS as SyncLog[]);
  const [showAddDialog, setShowAddDialog] = useState(false);
  const [selectedSourceType, setSelectedSourceType] = useState<SyncSourceType | null>(null);
  const [syncingIds, setSyncingIds] = useState<Set<string>>(new Set());

  const [formData, setFormData] = useState({
    name: '',
    apiKey: '',
    baseUrl: '',
    spaceId: '',
    syncInterval: '3600',
  });

  const handleTriggerSync = async (sourceId: string) => {
    setSyncingIds((prev) => new Set(prev).add(sourceId));
    setSources((prev) =>
      prev.map((s) =>
        s.id === sourceId ? { ...s, status: 'SYNCING' as SyncStatus } : s
      )
    );

    toast({
      title: '同步已启动',
      description: '正在同步数据源...',
    });

    await new Promise((resolve) => setTimeout(resolve, 2000));

    setSyncingIds((prev) => {
      const next = new Set(prev);
      next.delete(sourceId);
      return next;
    });
    setSources((prev) =>
      prev.map((s) =>
        s.id === sourceId ? { ...s, status: 'SUCCESS' as SyncStatus, lastSyncAt: new Date() } : s
      )
    );

    toast({
      title: '同步完成',
      description: '数据源已成功同步',
      variant: 'success',
    });
  };

  const handleToggleSource = (sourceId: string) => {
    setSources((prev) =>
      prev.map((s) =>
        s.id === sourceId ? { ...s, enabled: !s.enabled } : s
      )
    );
    toast({
      title: sources.find((s) => s.id === sourceId)?.enabled
        ? '数据源已禁用'
        : '数据源已启用',
      variant: 'success',
    });
  };

  const handleDeleteSource = (sourceId: string) => {
    setSources((prev) => prev.filter((s) => s.id !== sourceId));
    toast({
      title: '数据源已删除',
      variant: 'success',
    });
  };

  const handleAddSource = () => {
    if (!selectedSourceType || !formData.name || !formData.apiKey) {
      toast({
        title: '请填写完整信息',
        variant: 'destructive',
      });
      return;
    }

    const newSource: SyncSource = {
      id: Date.now().toString(),
      type: selectedSourceType,
      name: formData.name,
      status: 'IDLE',
      enabled: true,
      config: {
        apiKey: formData.apiKey,
        baseUrl: formData.baseUrl,
        spaceId: formData.spaceId,
      },
      stats: {
        totalDocuments: 0,
        syncedDocuments: 0,
        failedDocuments: 0,
      },
    };

    setSources((prev) => [...prev, newSource]);
    setShowAddDialog(false);
    setSelectedSourceType(null);
    setFormData({
      name: '',
      apiKey: '',
      baseUrl: '',
      spaceId: '',
      syncInterval: '3600',
    });
    toast({
      title: '数据源已添加',
      description: '新数据源已成功添加',
      variant: 'success',
    });
  };

  const getStatusIcon = (status: SyncStatus) => {
    switch (status) {
      case 'SUCCESS':
        return <CheckCircle2 className="h-5 w-5 text-green-500" />;
      case 'FAILED':
        return <XCircle className="h-5 w-5 text-red-500" />;
      case 'SYNCING':
        return <Loader2 className="h-5 w-5 text-blue-500 animate-spin" />;
      default:
        return <Clock className="h-5 w-5 text-muted-foreground" />;
    }
  };

  const getStatusText = (status: SyncStatus) => {
    switch (status) {
      case 'SUCCESS':
        return '已同步';
      case 'FAILED':
        return '同步失败';
      case 'SYNCING':
        return '同步中';
      default:
        return '待同步';
    }
  };

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="container mx-auto px-4 py-6">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="p-2 rounded-lg bg-primary/10">
                <RefreshCw className="h-6 w-6 text-primary" />
              </div>
              <div>
                <h1 className="text-3xl font-bold">同步配置</h1>
                <p className="text-muted-foreground mt-1">
                  管理多源数据同步，统一管理您的知识资产
                </p>
              </div>
            </div>
            <Dialog open={showAddDialog} onOpenChange={setShowAddDialog}>
              <DialogTrigger asChild>
                <Button>
                  <Plus className="mr-2 h-4 w-4" />
                  添加数据源
                </Button>
              </DialogTrigger>
              <DialogContent className="max-w-2xl">
                <DialogHeader>
                  <DialogTitle>添加数据源</DialogTitle>
                  <DialogDescription>
                    选择要同步的数据源类型并配置连接信息
                  </DialogDescription>
                </DialogHeader>

                {!selectedSourceType ? (
                  <div className="grid grid-cols-2 gap-4 py-4">
                    {(Object.keys(SOURCE_CONFIGS) as SyncSourceType[]).map(
                      (type) => {
                        const config = SOURCE_CONFIGS[type];
                        const Icon = config.icon;
                        return (
                          <button
                            key={type}
                            onClick={() => setSelectedSourceType(type)}
                            className="flex flex-col items-center gap-3 p-6 rounded-lg border-2 border-border hover:border-primary transition-colors text-left"
                          >
                            <div
                              className={`p-3 rounded-lg ${config.bgColor}`}
                            >
                              <Icon className={`h-8 w-8 ${config.color}`} />
                            </div>
                            <div className="text-center">
                              <p className="font-medium">{config.name}</p>
                              <p className="text-xs text-muted-foreground mt-1">
                                {config.description}
                              </p>
                            </div>
                          </button>
                        );
                      }
                    )}
                  </div>
                ) : (
                  <div className="space-y-4 py-4">
                    <div className="flex items-center gap-3 p-3 rounded-lg bg-muted">
                      <div
                        className={`p-2 rounded-lg ${SOURCE_CONFIGS[selectedSourceType].bgColor}`}
                      >
                        {(() => {
                          const Icon = SOURCE_CONFIGS[selectedSourceType].icon;
                          return (
                            <Icon
                              className={`h-5 w-5 ${SOURCE_CONFIGS[selectedSourceType].color}`}
                            />
                          );
                        })()}
                      </div>
                      <div>
                        <p className="font-medium">
                          {SOURCE_CONFIGS[selectedSourceType].name}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          {SOURCE_CONFIGS[selectedSourceType].description}
                        </p>
                      </div>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="ml-auto"
                        onClick={() => setSelectedSourceType(null)}
                      >
                        更改
                      </Button>
                    </div>

                    <div className="space-y-3">
                      <div>
                        <Label htmlFor="name">数据源名称</Label>
                        <Input
                          id="name"
                          placeholder="输入数据源名称"
                          value={formData.name}
                          onChange={(e) =>
                            setFormData({ ...formData, name: e.target.value })
                          }
                          className="mt-1.5"
                        />
                      </div>
                      <div>
                        <Label htmlFor="apiKey">API Key / Token</Label>
                        <Input
                          id="apiKey"
                          type="password"
                          placeholder="输入 API Key 或 Token"
                          value={formData.apiKey}
                          onChange={(e) =>
                            setFormData({ ...formData, apiKey: e.target.value })
                          }
                          className="mt-1.5"
                        />
                      </div>
                      {(selectedSourceType === 'CONFLUENCE' ||
                        selectedSourceType === 'FEISHU') && (
                        <div>
                          <Label htmlFor="baseUrl">基础 URL</Label>
                          <Input
                            id="baseUrl"
                            placeholder="https://your-domain.atlassian.net/wiki"
                            value={formData.baseUrl}
                            onChange={(e) =>
                              setFormData({
                                ...formData,
                                baseUrl: e.target.value,
                              })
                            }
                            className="mt-1.5"
                          />
                        </div>
                      )}
                      {(selectedSourceType === 'CONFLUENCE' ||
                        selectedSourceType === 'NOTION') && (
                        <div>
                          <Label htmlFor="spaceId">
                            空间 ID / 数据库 ID
                          </Label>
                          <Input
                            id="spaceId"
                            placeholder="输入空间或数据库 ID"
                            value={formData.spaceId}
                            onChange={(e) =>
                              setFormData({
                                ...formData,
                                spaceId: e.target.value,
                              })
                            }
                            className="mt-1.5"
                          />
                        </div>
                      )}
                      <div>
                        <Label htmlFor="interval">同步间隔</Label>
                        <Select
                          value={formData.syncInterval}
                          onValueChange={(v) =>
                            setFormData({ ...formData, syncInterval: v })
                          }
                        >
                          <SelectTrigger className="mt-1.5">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="900">每 15 分钟</SelectItem>
                            <SelectItem value="1800">每 30 分钟</SelectItem>
                            <SelectItem value="3600">每小时</SelectItem>
                            <SelectItem value="7200">每 2 小时</SelectItem>
                            <SelectItem value="86400">每天</SelectItem>
                          </SelectContent>
                        </Select>
                      </div>
                    </div>
                  </div>
                )}

                <DialogFooter>
                  <Button
                    variant="outline"
                    onClick={() => {
                      setShowAddDialog(false);
                      setSelectedSourceType(null);
                    }}
                  >
                    取消
                  </Button>
                  {selectedSourceType && (
                    <Button onClick={handleAddSource}>添加数据源</Button>
                  )}
                </DialogFooter>
              </DialogContent>
            </Dialog>
          </div>
        </div>
      </header>

      <main className="container mx-auto px-4 py-8">
        <Tabs defaultValue="sources" className="w-full">
          <TabsList className="mb-6">
            <TabsTrigger value="sources" className="gap-2">
              <Database className="h-4 w-4" />
              数据源
            </TabsTrigger>
            <TabsTrigger value="logs" className="gap-2">
              <Clock className="h-4 w-4" />
              同步日志
            </TabsTrigger>
          </TabsList>

          <TabsContent value="sources">
            {sources.length === 0 ? (
              <Card>
                <CardContent className="p-12 text-center">
                  <Globe className="h-12 w-12 mx-auto mb-4 text-muted-foreground opacity-50" />
                  <h3 className="text-lg font-semibold mb-2">暂无数据源</h3>
                  <p className="text-muted-foreground mb-4">
                    添加您的第一个数据源开始同步
                  </p>
                  <Button onClick={() => setShowAddDialog(true)}>
                    <Plus className="mr-2 h-4 w-4" />
                    添加数据源
                  </Button>
                </CardContent>
              </Card>
            ) : (
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                {sources.map((source) => {
                  const config = SOURCE_CONFIGS[source.type];
                  const Icon = config.icon;
                  const isSyncing =
                    syncingIds.has(source.id) || source.status === 'SYNCING';

                  return (
                    <Card key={source.id}>
                      <CardContent className="p-6">
                        <div className="flex items-start justify-between mb-4">
                          <div className="flex items-center gap-3">
                            <div className={`p-3 rounded-lg ${config.bgColor}`}>
                              <Icon className={`h-6 w-6 ${config.color}`} />
                            </div>
                            <div>
                              <h3 className="font-semibold">{source.name}</h3>
                              <p className="text-sm text-muted-foreground">
                                {config.name}
                              </p>
                            </div>
                          </div>
                          <div className="flex items-center gap-2">
                            <Switch
                              checked={source.enabled}
                              onCheckedChange={() => handleToggleSource(source.id)}
                              disabled={isSyncing}
                            />
                            <DropdownMenu>
                              <DropdownMenuTrigger asChild>
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  disabled={isSyncing}
                                >
                                  <MoreHorizontal className="h-4 w-4" />
                                </Button>
                              </DropdownMenuTrigger>
                              <DropdownMenuContent align="end">
                                <DropdownMenuItem
                                  onClick={() =>
                                    router.push(
                                      `/settings/sync/${source.id}`
                                    )
                                  }
                                >
                                  <Edit className="mr-2 h-4 w-4" />
                                  编辑配置
                                </DropdownMenuItem>
                                <DropdownMenuItem
                                  onClick={() => handleTriggerSync(source.id)}
                                  disabled={isSyncing}
                                >
                                  {isSyncing ? (
                                    <>
                                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                                      同步中...
                                    </>
                                  ) : (
                                    <>
                                      <Play className="mr-2 h-4 w-4" />
                                      立即同步
                                    </>
                                  )}
                                </DropdownMenuItem>
                                <DropdownMenuItem
                                  className="text-destructive focus:text-destructive"
                                  onClick={() => handleDeleteSource(source.id)}
                                  disabled={isSyncing}
                                >
                                  <Trash2 className="mr-2 h-4 w-4" />
                                  删除
                                </DropdownMenuItem>
                              </DropdownMenuContent>
                            </DropdownMenu>
                          </div>
                        </div>

                        <div className="flex items-center gap-4 mb-4 text-sm">
                          <div className="flex items-center gap-1.5">
                            {getStatusIcon(source.status)}
                            <span
                              className={cn(
                                source.status === 'FAILED' &&
                                  'text-destructive'
                              )}
                            >
                              {getStatusText(source.status)}
                            </span>
                          </div>
                          {source.lastSyncAt && (
                            <span className="text-muted-foreground">
                              上次同步: {formatTimeAgo(source.lastSyncAt)}
                            </span>
                          )}
                        </div>

                        <div className="grid grid-cols-3 gap-2 text-center text-sm">
                          <div className="p-2 rounded bg-muted">
                            <p className="font-semibold text-lg">
                              {source.stats.totalDocuments}
                            </p>
                            <p className="text-xs text-muted-foreground">
                              总文档
                            </p>
                          </div>
                          <div className="p-2 rounded bg-green-50 dark:bg-green-900/20">
                            <p className="font-semibold text-lg text-green-600">
                              {source.stats.syncedDocuments}
                            </p>
                            <p className="text-xs text-muted-foreground">
                              已同步
                            </p>
                          </div>
                          <div
                            className={cn(
                              'p-2 rounded',
                              source.stats.failedDocuments > 0
                                ? 'bg-red-50 dark:bg-red-900/20'
                                : 'bg-muted'
                            )}
                          >
                            <p
                              className={cn(
                                'font-semibold text-lg',
                                source.stats.failedDocuments > 0
                                  ? 'text-red-600'
                                  : ''
                              )}
                            >
                              {source.stats.failedDocuments}
                            </p>
                            <p className="text-xs text-muted-foreground">
                              失败
                            </p>
                          </div>
                        </div>

                        {source.status === 'FAILED' && (
                          <Alert variant="destructive" className="mt-4">
                            <AlertCircle className="h-4 w-4" />
                            <AlertTitle>同步失败</AlertTitle>
                            <AlertDescription>
                              有 {source.stats.failedDocuments} 篇文档同步失败，请检查连接配置。
                            </AlertDescription>
                          </Alert>
                        )}

                        {source.status === 'SYNCING' && (
                          <div className="mt-4">
                            <div className="flex items-center justify-between text-sm mb-2">
                              <span className="text-muted-foreground">
                                同步进度
                              </span>
                              <span>
                                {source.stats.syncedDocuments}/
                                {source.stats.totalDocuments}
                              </span>
                            </div>
                            <div className="h-2 bg-muted rounded-full overflow-hidden">
                              <div
                                className="h-full bg-primary rounded-full transition-all"
                                style={{
                                  width: `${(source.stats.syncedDocuments / source.stats.totalDocuments) * 100}%`,
                                }}
                              />
                            </div>
                          </div>
                        )}
                      </CardContent>
                    </Card>
                  );
                })}
              </div>
            )}
          </TabsContent>

          <TabsContent value="logs">
            <Card>
              <CardHeader className="flex flex-row items-center justify-between">
                <CardTitle className="text-lg">同步日志</CardTitle>
                <Button variant="outline" size="sm">
                  <RefreshCw className="mr-2 h-4 w-4" />
                  刷新
                </Button>
              </CardHeader>
              <CardContent>
                <div className="space-y-3">
                  {logs.map((log) => {
                    const config = SOURCE_CONFIGS[log.type];
                    const Icon = config.icon;
                    return (
                      <div
                        key={log.id}
                        className="flex items-start gap-3 p-3 rounded-lg hover:bg-muted transition-colors"
                      >
                        <div className={`p-2 rounded-lg ${config.bgColor} mt-0.5`}>
                          <Icon className={`h-4 w-4 ${config.color}`} />
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <span className="font-medium">{log.sourceName}</span>
                            {getStatusIcon(log.status as SyncStatus)}
                            <span className="text-xs text-muted-foreground">
                              {formatTimeAgo(log.createdAt)}
                            </span>
                          </div>
                          <p
                            className={cn(
                              'text-sm mt-1',
                              log.status === 'FAILED' && 'text-destructive'
                            )}
                          >
                            {log.message}
                          </p>
                        </div>
                        <ChevronRight className="h-5 w-5 text-muted-foreground shrink-0 mt-2" />
                      </div>
                    );
                  })}
                </div>
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </main>
    </div>
  );
}
