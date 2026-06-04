'use client';

import { useParams, useRouter } from 'next/navigation';
import { useState, useMemo } from 'react';
import {
  FileText,
  Clock,
  User,
  Tag,
  History,
  MessageSquare,
  MoreHorizontal,
  Edit,
  Trash2,
  Share2,
  ChevronLeft,
  ChevronRight,
  BookOpen,
  GitCompare,
  Star,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo, cn } from '@/lib/utils';
import { CollaborativeEditor } from '@/components/editor/CollaborativeEditor';
import { CommentSidebar } from '@/components/comments/CommentSidebar';
import { VersionHistory } from '@/components/versions/VersionHistory';
import Link from 'next/link';
import type { Tag as TagType, Document, User as UserType } from '@prisma/client';

interface RecommendedDocument {
  id: string;
  title: string;
  content?: string | null;
  spaceId: string;
  updatedAt: Date;
  createdBy: Pick<UserType, 'id' | 'name' | 'avatar'>;
  tags: TagType[];
  space: { id: string; name: string; icon: string | null };
  summary?: string | null;
  relevanceScore: number;
  score?: number;
  sharedTags: string[];
}

export default function DocumentDetailPage() {
  const params = useParams<{ spaceId: string; documentId: string }>();
  const router = useRouter();
  const { toast } = useToast();
  const spaceId = params?.spaceId as string;
  const documentId = params?.documentId as string;

  const [showComments, setShowComments] = useState(false);
  const [showVersions, setShowVersions] = useState(false);
  const [activeTab, setActiveTab] = useState<'editor' | 'preview'>('editor');
  const [isStarred, setIsStarred] = useState(false);

  const { data: document, isLoading, error } =
    trpc.document.getById.useQuery(
      { id: documentId },
      { enabled: !!documentId }
    ) as { data: import('@/lib/types/document').DocumentWithRelations | undefined; isLoading: boolean; error: unknown };

  const { data: recommendationsData } =
    trpc.recommendation.getRelatedDocuments.useQuery(
      { documentId, limit: 5 },
      { enabled: !!documentId }
    );

  const recommendations = recommendationsData?.items || [];

  const { data: userRole } = trpc.space.getUserRole.useQuery(
    { spaceId },
    { enabled: !!spaceId }
  );

  const { data: currentUser } = trpc.auth.getCurrentUser.useQuery();

  const canEdit = useMemo(() => {
    return (
      userRole?.role === 'OWNER' ||
      userRole?.role === 'ADMIN' ||
      userRole?.role === 'EDITOR'
    );
  }, [userRole]);

  const handleBack = () => {
    router.push(`/spaces/${spaceId}/documents`);
  };

  const handleDelete = () => {
    if (confirm('确定要删除这篇文档吗？')) {
      toast({
        title: '删除成功',
        description: '文档已移至回收站',
        variant: 'success',
      });
      router.push(`/spaces/${spaceId}/documents`);
    }
  };

  const handleShare = () => {
    toast({
      title: '链接已复制',
      description: '文档链接已复制到剪贴板',
    });
  };

  if (isLoading) {
    return (
      <div className="flex h-screen bg-background">
        <div className="flex-1 flex flex-col min-w-0">
          <div className="h-14 border-b flex items-center px-4 gap-4">
            <Skeleton className="h-9 w-9 rounded-md" />
            <Skeleton className="h-6 w-64" />
          </div>
          <div className="flex-1 p-8">
            <Skeleton className="h-10 w-3/4 mb-4" />
            <Skeleton className="h-4 w-1/2 mb-8" />
            <div className="space-y-3">
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-5/6" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-4/5" />
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (error || !document) {
    return (
      <div className="flex items-center justify-center h-screen">
        <Card className="w-full max-w-md mx-4">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-destructive">
              <FileText className="h-5 w-5" />
              文档不存在
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-muted-foreground mb-4">
              {error?.message || '您访问的文档不存在或已被删除。'}
            </p>
            <Button onClick={handleBack}>
              <ChevronLeft className="mr-2 h-4 w-4" />
              返回文档列表
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  const recommendationsList = recommendations;

  return (
    <TooltipProvider>
      <div className="flex h-screen bg-background">
        <div
          className={cn(
            'flex-1 flex flex-col min-w-0 transition-all',
            showComments && 'lg:mr-80'
          )}
        >
          <header className="h-14 border-b flex items-center justify-between px-4 gap-4 bg-background">
            <div className="flex items-center gap-4">
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={handleBack}
                  >
                    <ChevronLeft className="h-5 w-5" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>返回文档列表</TooltipContent>
              </Tooltip>
              <div className="flex items-center gap-2">
                <div className="p-1.5 rounded bg-primary/10">
                  <FileText className="h-4 w-4 text-primary" />
                </div>
                <div>
                  <h1 className="font-semibold text-sm truncate max-w-[300px]">
                    {document.title || '无标题文档'}
                  </h1>
                  <p className="text-xs text-muted-foreground">
                    {document.space?.name || '未知空间'}
                  </p>
                </div>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <Tabs
                value={activeTab}
                onValueChange={(v) => setActiveTab(v as 'editor' | 'preview')}
                className="hidden sm:block"
              >
                <TabsList className="h-8">
                  <TabsTrigger value="editor" className="h-7 px-3 text-xs">
                    <Edit className="h-3.5 w-3.5 mr-1" />
                    编辑
                  </TabsTrigger>
                  <TabsTrigger value="preview" className="h-7 px-3 text-xs">
                    <BookOpen className="h-3.5 w-3.5 mr-1" />
                    预览
                  </TabsTrigger>
                </TabsList>
              </Tabs>

              <Separator orientation="vertical" className="h-6" />

              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => setIsStarred(!isStarred)}
                    className={cn(isStarred && 'text-yellow-500')}
                  >
                    <Star
                      className={cn(
                        'h-5 w-5',
                        isStarred && 'fill-current'
                      )}
                    />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>
                  {isStarred ? '取消收藏' : '收藏文档'}
                </TooltipContent>
              </Tooltip>

              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => setShowComments(!showComments)}
                    className={cn(showComments && 'bg-muted')}
                  >
                    <MessageSquare className="h-5 w-5" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>评论</TooltipContent>
              </Tooltip>

              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => setShowVersions(!showVersions)}
                    className={cn(showVersions && 'bg-muted')}
                  >
                    <History className="h-5 w-5" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>版本历史</TooltipContent>
              </Tooltip>

              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="ghost" size="icon" onClick={handleShare}>
                    <Share2 className="h-5 w-5" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>分享</TooltipContent>
              </Tooltip>

              {canEdit && (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon">
                      <MoreHorizontal className="h-5 w-5" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem
                      onClick={() =>
                        router.push(
                          `/spaces/${spaceId}/documents/${documentId}/versions`
                        )
                      }
                    >
                      <History className="mr-2 h-4 w-4" />
                      版本历史
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      onClick={() =>
                        router.push(
                          `/spaces/${spaceId}/documents/${documentId}/compare`
                        )
                      }
                    >
                      <GitCompare className="mr-2 h-4 w-4" />
                      版本对比
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      className="text-destructive focus:text-destructive"
                      onClick={handleDelete}
                    >
                      <Trash2 className="mr-2 h-4 w-4" />
                      删除文档
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              )}
            </div>
          </header>

          <div className="flex items-center gap-4 px-6 py-3 border-b bg-muted/30 text-sm">
            <div className="flex items-center gap-1.5 text-muted-foreground">
              <User className="h-4 w-4" />
              <span>{document.createdBy?.name || '未知作者'}</span>
            </div>
            <Separator orientation="vertical" className="h-4" />
            <div className="flex items-center gap-1.5 text-muted-foreground">
              <Clock className="h-4 w-4" />
              <span>更新于 {formatTimeAgo(document.updatedAt)}</span>
            </div>
            {document.tags && document.tags.length > 0 && (
              <>
                <Separator orientation="vertical" className="h-4" />
                <div className="flex items-center gap-1.5">
                  <Tag className="h-4 w-4 text-muted-foreground" />
                  <div className="flex flex-wrap gap-1">
                    {document.tags.map((tag: TagType) => (
                      <Badge
                        key={tag.id}
                        variant="outline"
                        className="text-xs"
                        style={{
                          borderColor: tag.color || undefined,
                          color: tag.color || undefined,
                        }}
                      >
                        #{tag.name}
                      </Badge>
                    ))}
                  </div>
                </div>
              </>
            )}
          </div>

          <main className="flex-1 overflow-auto">
            {activeTab === 'editor' ? (
              <CollaborativeEditor
                documentId={documentId}
                initialContent={document.content ?? undefined}
                initialTitle={document.title}
                readOnly={!canEdit}
                userId={currentUser?.id || ''}
                userName={currentUser?.name || '匿名用户'}
                userAvatar={currentUser?.avatar ?? undefined}
              />
            ) : (
              <div className="max-w-4xl mx-auto p-8">
                <article
                  className="prose prose-lg max-w-none dark:prose-invert"
                  dangerouslySetInnerHTML={{
                    __html: document.content || '<p>暂无内容</p>',
                  }}
                />
              </div>
            )}
          </main>

          {recommendationsList.length > 0 && (
            <div className="border-t p-6 bg-muted/30">
              <h3 className="text-sm font-medium mb-4 flex items-center gap-2">
                <BookOpen className="h-4 w-4 text-primary" />
                相关文档推荐
              </h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                {recommendationsList.slice(0, 3).map((rec: any) => (
                  <Link
                    key={rec.id}
                    href={`/spaces/${rec.spaceId}/documents/${rec.id}`}
                    className="p-3 rounded-lg border hover:border-primary hover:bg-background transition-colors"
                  >
                    <h4 className="font-medium text-sm mb-1 truncate">
                      {rec.title || '无标题文档'}
                    </h4>
                    <p className="text-xs text-muted-foreground line-clamp-2">
                      {rec.summary ||
                        rec.content?.slice(0, 100)}
                    </p>
                    <div className="flex items-center justify-between mt-2 text-xs text-muted-foreground">
                      <span>
                        相关度 {(rec.relevanceScore * 100).toFixed(0)}%
                      </span>
                      <span>
                        {formatTimeAgo(rec.updatedAt)}
                      </span>
                    </div>
                  </Link>
                ))}
              </div>
            </div>
          )}
        </div>

        {showComments && (
          <div className="fixed right-0 top-0 h-full w-80 border-l bg-background z-30">
            <CommentSidebar
              documentId={documentId}
              currentUserId={currentUser?.id || ''}
              canResolve={canEdit}
              isOpen={showComments}
              onClose={() => setShowComments(false)}
            />
          </div>
        )}

        {showVersions && (
          <div className="fixed right-0 top-0 h-full w-96 border-l bg-background z-30">
            <VersionHistory
              documentId={documentId}
              onClose={() => setShowVersions(false)}
              onCompare={(v1, v2) =>
                router.push(
                  `/spaces/${spaceId}/documents/${documentId}/compare?v1=${v1}&v2=${v2}`
                )
              }
              onRestore={(versionId: string) =>
                router.push(
                  `/spaces/${spaceId}/documents/${documentId}/versions/${versionId}`
                )
              }
            />
          </div>
        )}
      </div>
    </TooltipProvider>
  );
}
