'use client';

import { useParams, useRouter } from 'next/navigation';
import { useState, useMemo, useEffect } from 'react';
import {
  Share2,
  Lock,
  Unlock,
  Clock,
  User,
  FileText,
  ChevronLeft,
  Eye,
  Edit3,
  Copy,
  CheckCircle2,
  AlertTriangle,
  Loader2,
  BookOpen,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Separator } from '@/components/ui/separator';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo, copyToClipboard, cn } from '@/lib/utils';
import { CollaborativeEditor } from '@/components/editor/CollaborativeEditor';
import type { ValidateShareLinkResult } from '@/lib/types/space';
import type { Document, Space, User as UserType } from '@prisma/client';

interface SharedDocument extends Document {
  createdBy?: Pick<UserType, 'id' | 'name' | 'avatar'> | null;
  author?: Pick<UserType, 'id' | 'name' | 'avatar'> | null;
}

interface ShareLinkData {
  valid: boolean;
  document?: SharedDocument;
  space?: import('@/lib/types/space').SpaceBasic;
  role?: import('@/lib/types/permission').Role;
  requiresPassword: boolean;
  expiresAt?: Date | null;
  error?: string;
  token?: string;
}

type ShareMode = 'view' | 'edit';

export default function SharePage() {
  const params = useParams<{ token: string }>();
  const router = useRouter();
  const { toast } = useToast();
  const token = params?.token as string;

  const [password, setPassword] = useState('');
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [viewMode, setViewMode] = useState<'preview' | 'edit'>('preview');
  const [isVerifying, setIsVerifying] = useState(false);
  const [copied, setCopied] = useState(false);
  const [shareLink, setShareLink] = useState<ShareLinkData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const validateMutation = trpc.space.validateShareLink.useMutation({
    onSuccess: (data: any) => {
      setIsVerifying(false);
      setShareLink(data);
      if (data.valid) {
        setIsAuthenticated(true);
        setError(null);
      } else if (!data.requiresPassword) {
        setError(data.error || '链接无效');
      } else {
        setError(null);
      }
    },
    onError: (error: unknown) => {
      setIsVerifying(false);
      const errorMessage = error instanceof Error ? error.message : '验证失败';
      setError(errorMessage);
      toast({
        title: '验证失败',
        description: errorMessage,
        variant: 'destructive',
      });
    },
  });

  useEffect(() => {
    if (token) {
      setIsLoading(true);
      validateMutation.mutate({ token });
    }
  }, [token]);

  useEffect(() => {
    if (!validateMutation.isPending) {
      setIsLoading(false);
    }
  }, [validateMutation.isPending]);

  const handleVerify = () => {
    if (!password && shareLink?.requiresPassword) {
      toast({
        title: '请输入密码',
        description: '此分享链接需要密码才能访问',
        variant: 'destructive',
      });
      return;
    }
    setIsVerifying(true);
    validateMutation.mutate({ token, password });
  };

  const handleCopyLink = () => {
    copyToClipboard(window.location.href);
    setCopied(true);
    toast({
      title: '链接已复制',
      description: '分享链接已复制到剪贴板',
      variant: 'success',
    });
    setTimeout(() => setCopied(false), 2000);
  };

  const handleBack = () => {
    router.push('/');
  };

  const canEdit = useMemo(() => {
    return shareLink?.role === 'EDITOR' || shareLink?.role === 'ADMIN';
  }, [shareLink]);

  const document = shareLink?.document;
  const space = shareLink?.space;

  if (isLoading && !isAuthenticated) {
    return (
      <div className="min-h-screen bg-background flex items-center justify-center p-4">
        <Card className="w-full max-w-md">
          <CardContent className="p-8">
            <div className="flex flex-col items-center text-center">
              <Skeleton className="h-16 w-16 rounded-full mb-4" />
              <Skeleton className="h-6 w-48 mb-2" />
              <Skeleton className="h-4 w-64 mb-6" />
              <Skeleton className="h-10 w-full" />
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (error || (shareLink && !shareLink.valid && !shareLink.requiresPassword)) {
    return (
      <div className="min-h-screen bg-background flex items-center justify-center p-4">
        <Card className="w-full max-w-md">
          <CardContent className="p-8 text-center">
            <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-destructive/10 mb-4">
              <AlertTriangle className="w-8 h-8 text-destructive" />
            </div>
            <h2 className="text-2xl font-bold mb-2">链接已失效</h2>
            <p className="text-muted-foreground mb-6">
              {shareLink?.error ||
                error ||
                '此分享链接已过期或被撤销'}
            </p>
            <Button onClick={handleBack}>
              <ChevronLeft className="mr-2 h-4 w-4" />
              返回首页
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="min-h-screen bg-background flex items-center justify-center p-4">
        <Card className="w-full max-w-md">
          <CardContent className="p-8">
            <div className="flex flex-col items-center text-center mb-6">
              <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-primary/10 mb-4">
                <Share2 className="w-8 h-8 text-primary" />
              </div>
              <h2 className="text-2xl font-bold mb-2">访问分享链接</h2>
              <p className="text-muted-foreground">
                {shareLink?.requiresPassword
                  ? '此分享链接需要密码才能访问'
                  : '请验证以访问分享内容'}
              </p>
            </div>

            {shareLink?.requiresPassword && (
              <div className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="password">访问密码</Label>
                  <div className="relative">
                    <Lock className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      id="password"
                      type="password"
                      placeholder="请输入访问密码"
                      className="pl-10"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      onKeyDown={(e) =>
                        e.key === 'Enter' && handleVerify()
                      }
                    />
                  </div>
                </div>

                <Button
                  className="w-full"
                  onClick={handleVerify}
                  disabled={isVerifying}
                >
                  {isVerifying && (
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  )}
                  <Unlock className="mr-2 h-4 w-4" />
                  解锁访问
                </Button>
              </div>
            )}

            {!shareLink?.requiresPassword && (
              <Button
                className="w-full"
                onClick={() => setIsAuthenticated(true)}
              >
                <Eye className="mr-2 h-4 w-4" />
                查看内容
              </Button>
            )}

            <div className="mt-6 text-center">
              <Button
                variant="link"
                size="sm"
                onClick={handleBack}
                className="text-sm"
              >
                没有访问权限？返回首页
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!document && !space) {
    return (
      <div className="min-h-screen bg-background flex items-center justify-center p-4">
        <Card className="w-full max-w-md">
          <CardContent className="p-8 text-center">
            <Alert variant="destructive">
              <AlertTriangle className="h-4 w-4" />
              <AlertTitle>内容不存在</AlertTitle>
              <AlertDescription>
                分享的内容不存在或已被删除
              </AlertDescription>
            </Alert>
            <Button className="mt-4" onClick={handleBack}>
              <ChevronLeft className="mr-2 h-4 w-4" />
              返回首页
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background">
      <header className="h-14 border-b flex items-center justify-between px-4 bg-background sticky top-0 z-10">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="icon" onClick={handleBack}>
            <ChevronLeft className="h-5 w-5" />
          </Button>
          <div className="flex items-center gap-3">
            <div className="p-1.5 rounded bg-primary/10">
              <Share2 className="h-4 w-4 text-primary" />
            </div>
            <div>
              <h1 className="font-semibold text-sm truncate max-w-[200px]">
                {document?.title || space?.name || '分享内容'}
              </h1>
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <Badge
                  variant="outline"
                  className={cn(
                    'text-xs',
                    canEdit
                      ? 'border-green-500 text-green-600'
                      : 'border-blue-500 text-blue-600'
                  )}
                >
                  {canEdit ? (
                    <>
                      <Edit3 className="h-3 w-3 mr-1" />
                      可编辑
                    </>
                  ) : (
                    <>
                      <Eye className="h-3 w-3 mr-1" />
                      只读
                    </>
                  )}
                </Badge>
                {shareLink?.expiresAt && (
                  <span className="flex items-center gap-1">
                    <Clock className="h-3 w-3" />
                    有效期至 {formatTimeAgo(shareLink.expiresAt)}
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {canEdit && (
            <Tabs
              value={viewMode}
              onValueChange={(v) => setViewMode(v as typeof viewMode)}
              className="hidden sm:block"
            >
              <TabsList className="h-8">
                <TabsTrigger value="preview" className="h-7 px-3 text-xs">
                  <Eye className="h-3.5 w-3.5 mr-1" />
                  预览
                </TabsTrigger>
                <TabsTrigger value="edit" className="h-7 px-3 text-xs">
                  <Edit3 className="h-3.5 w-3.5 mr-1" />
                  编辑
                </TabsTrigger>
              </TabsList>
            </Tabs>
          )}
          <Button variant="outline" size="sm" onClick={handleCopyLink}>
            {copied ? (
              <>
                <CheckCircle2 className="mr-1.5 h-4 w-4 text-green-500" />
                已复制
              </>
            ) : (
              <>
                <Copy className="mr-1.5 h-4 w-4" />
                复制链接
              </>
            )}
          </Button>
        </div>
      </header>

      <main className="min-h-[calc(100vh-3.5rem)]">
        {document ? (
          <>
            {viewMode === 'preview' ? (
              <div className="max-w-4xl mx-auto p-8">
                <article className="prose prose-lg max-w-none dark:prose-invert">
                  <div className="flex items-center gap-4 mb-8 pb-6 border-b">
                    <div className="p-3 rounded-lg bg-primary/10">
                      <FileText className="h-8 w-8 text-primary" />
                    </div>
                    <div>
                      <h1 className="text-3xl font-bold mb-0">
                        {document.title || '无标题文档'}
                      </h1>
                      <div className="flex items-center gap-3 mt-2 text-sm text-muted-foreground">
                        <span className="flex items-center gap-1">
                          <User className="h-4 w-4" />
                          {document.createdBy?.name || '未知作者'}
                        </span>
                        <span className="flex items-center gap-1">
                          <Clock className="h-4 w-4" />
                          更新于 {formatTimeAgo(document.updatedAt)}
                        </span>
                      </div>
                    </div>
                  </div>
                  <div
                    dangerouslySetInnerHTML={{
                      __html: document.content || '<p>暂无内容</p>',
                    }}
                  />
                </article>
              </div>
            ) : (
              <CollaborativeEditor
                config={{
                  documentId: document.id,
                  userId: 'anonymous',
                  userName: '访客',
                  token: token,
                }}
                initialContent={document.content ?? undefined}
                readOnly={!canEdit}
              />
            )}
          </>
        ) : space ? (
          <div className="container mx-auto px-4 py-8">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-3">
                  <div
                    className="w-12 h-12 rounded-xl flex items-center justify-center text-white text-xl font-bold"
                    style={{ backgroundColor: space.color || '#6366f1' }}
                  >
                    {space.icon || space.name.charAt(0).toUpperCase()}
                  </div>
                  <div>
                    <h2 className="text-xl">{space.name}</h2>
                    <p className="text-sm text-muted-foreground font-normal">
                      {space.description || '暂无描述'}
                    </p>
                  </div>
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="text-center py-12">
                  <BookOpen className="h-12 w-12 mx-auto mb-4 text-muted-foreground opacity-50" />
                  <h3 className="text-lg font-semibold mb-2">空间内容</h3>
                  <p className="text-muted-foreground mb-6">
                    此功能正在开发中，敬请期待
                  </p>
                </div>
              </CardContent>
            </Card>
          </div>
        ) : null}
      </main>
    </div>
  );
}
