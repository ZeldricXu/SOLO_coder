'use client';

import { useParams, useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  Plus,
  Grid3X3,
  List,
  Users,
  FileText,
  Clock,
  Settings,
  Share2,
  MoreHorizontal,
  FolderOpen,
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
import { trpc } from '@/components/providers/TRPCProvider';
import { formatTimeAgo, truncateText } from '@/lib/utils';
import { Skeleton } from '@/components/ui/skeleton';
import Link from 'next/link';
import { cn } from '@/lib/utils';
import type { Document, Tag as TagType, User as UserType } from '@prisma/client';
import type { SpaceMember } from '@/lib/types/space';

interface SpaceDocument extends Document {
  author?: Pick<UserType, 'id' | 'name' | 'avatar'> | null;
  tags: Array<Pick<TagType, 'id' | 'name' | 'color'>>;
  isDeleted?: boolean;
}

type ViewMode = 'grid' | 'list';
type FilterType = 'all' | 'recent' | 'starred' | 'trash';

export default function SpaceDetailPage() {
  const params = useParams<{ spaceId: string }>();
  const router = useRouter();
  const spaceId = params?.spaceId as string;

  const [viewMode, setViewMode] = useState<ViewMode>('grid');
  const [filter, setFilter] = useState<FilterType>('all');

  const { data: space, isLoading: spaceLoading } =
    trpc.space.getById.useQuery({ id: spaceId });

  const { data: documents, isLoading: docsLoading } =
    trpc.document.list.useQuery(
      { spaceId, pageSize: 50 },
      { enabled: !!spaceId }
    );

  const { data: members, isLoading: membersLoading } =
    trpc.space.listMembers.useQuery(
      { spaceId },
      { enabled: !!spaceId }
    );

  const { data: recentActivity, isLoading: activityLoading } =
    trpc.document.getRecentlyUpdated.useQuery(
      { spaceId, limit: 10 },
      { enabled: !!spaceId }
    );

  const handleNewDocument = () => {
    router.push(`/spaces/${spaceId}/documents/new`);
  };

  const handleSettings = () => {
    router.push(`/spaces/${spaceId}/settings`);
  };

  const handleMembers = () => {
    router.push(`/spaces/${spaceId}/members`);
  };

  if (spaceLoading) {
    return (
      <div className="p-8">
        <div className="mb-8">
          <Skeleton className="h-8 w-64 mb-2" />
          <Skeleton className="h-4 w-96" />
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-8">
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-24 rounded-lg" />
          ))}
        </div>
        <Skeleton className="h-10 w-full mb-6" />
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <Skeleton key={i} className="h-32 rounded-lg" />
          ))}
        </div>
      </div>
    );
  }

  const filteredDocuments = documents?.items.filter((doc: SpaceDocument) => {
    if (filter === 'trash') return doc.isDeleted;
    if (filter === 'recent')
      return (
        new Date(doc.updatedAt).getTime() >
        Date.now() - 7 * 24 * 60 * 60 * 1000
      );
    return !doc.isDeleted;
  });

  const stats = [
    {
      label: '文档数量',
      value: documents?.items.filter((d: SpaceDocument) => !d.isDeleted).length || 0,
      icon: FileText,
      color: 'text-blue-500',
      bgColor: 'bg-blue-500/10',
    },
    {
      label: '成员数量',
      value: members?.length || 0,
      icon: Users,
      color: 'text-green-500',
      bgColor: 'bg-green-500/10',
    },
    {
      label: '最近更新',
      value: recentActivity?.length || 0,
      icon: Clock,
      color: 'text-purple-500',
      bgColor: 'bg-purple-500/10',
    },
  ];

  return (
    <div className="p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <div className="flex items-center gap-3 mb-2">
            <div
              className="w-12 h-12 rounded-xl flex items-center justify-center text-white text-xl font-bold"
              style={{ backgroundColor: space?.color || '#6366f1' }}
            >
              {space?.icon || space?.name.charAt(0).toUpperCase()}
            </div>
            <div>
              <h1 className="text-3xl font-bold">{space?.name}</h1>
              {space?.description && (
                <p className="text-muted-foreground mt-1">
                  {space.description}
                </p>
              )}
            </div>
          </div>
          <div className="flex items-center gap-2 mt-2">
            <Badge variant={space?.visibility === 'PUBLIC' ? 'outline' : 'secondary'}>
              {space?.visibility === 'PUBLIC' ? '公开' : '私有'}
            </Badge>
            <span className="text-sm text-muted-foreground">
              创建于 {formatTimeAgo(space?.createdAt)}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <Button onClick={handleNewDocument}>
            <Plus className="h-4 w-4 mr-2" />
            新建文档
          </Button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline">
                <MoreHorizontal className="h-4 w-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={handleMembers}>
                <Users className="h-4 w-4 mr-2" />
                成员管理
              </DropdownMenuItem>
              <DropdownMenuItem onClick={handleSettings}>
                <Settings className="h-4 w-4 mr-2" />
                空间设置
              </DropdownMenuItem>
              <DropdownMenuItem>
                <Share2 className="h-4 w-4 mr-2" />
                分享
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-8">
        {stats.map((stat) => (
          <Card key={stat.label}>
            <CardContent className="p-6">
              <div className="flex items-center gap-4">
                <div
                  className={`p-3 rounded-lg ${stat.bgColor}`}
                >
                  <stat.icon className={`h-6 w-6 ${stat.color}`} />
                </div>
                <div>
                  <p className="text-3xl font-bold">{stat.value}</p>
                  <p className="text-sm text-muted-foreground">{stat.label}</p>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Tabs defaultValue="documents" className="mb-8">
        <TabsList>
          <TabsTrigger value="documents">文档</TabsTrigger>
          <TabsTrigger value="activity">最近活动</TabsTrigger>
          <TabsTrigger value="members">成员</TabsTrigger>
        </TabsList>

        <TabsContent value="documents" className="mt-6">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
            <div className="flex items-center gap-2">
              {(['all', 'recent', 'trash'] as FilterType[]).map((f) => (
                <Button
                  key={f}
                  variant={filter === f ? 'default' : 'ghost'}
                  size="sm"
                  onClick={() => setFilter(f)}
                >
                  {f === 'all' && '全部'}
                  {f === 'recent' && '最近'}
                  {f === 'trash' && '回收站'}
                </Button>
              ))}
            </div>

            <div className="flex items-center gap-1 bg-muted rounded-md p-1">
              <button
                onClick={() => setViewMode('grid')}
                className={cn(
                  'p-2 rounded transition-colors',
                  viewMode === 'grid'
                    ? 'bg-background shadow-sm'
                    : 'hover:bg-background/50'
                )}
              >
                <Grid3X3 className="h-4 w-4" />
              </button>
              <button
                onClick={() => setViewMode('list')}
                className={cn(
                  'p-2 rounded transition-colors',
                  viewMode === 'list'
                    ? 'bg-background shadow-sm'
                    : 'hover:bg-background/50'
                )}
              >
                <List className="h-4 w-4" />
              </button>
            </div>
          </div>

          {docsLoading ? (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {[1, 2, 3, 4, 5, 6].map((i) => (
                <Skeleton key={i} className="h-32 rounded-lg" />
              ))}
            </div>
          ) : filteredDocuments?.length === 0 ? (
            <div className="text-center py-16">
              <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-muted mb-4">
                <FolderOpen className="h-8 w-8 text-muted-foreground" />
              </div>
              <h3 className="text-lg font-semibold mb-2">暂无文档</h3>
              <p className="text-muted-foreground mb-4">
                {filter === 'trash'
                  ? '回收站是空的'
                  : '创建您的第一个文档开始使用'}
              </p>
              {filter !== 'trash' && (
                <Button onClick={handleNewDocument}>
                  <Plus className="h-4 w-4 mr-2" />
                  新建文档
                </Button>
              )}
            </div>
          ) : (
            <div
              className={cn(
                'gap-4',
                viewMode === 'grid'
                  ? 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3'
                  : 'flex flex-col'
              )}
            >
              {filteredDocuments?.map((doc: SpaceDocument) => (
                <Link
                  key={doc.id}
                  href={`/spaces/${spaceId}/documents/${doc.id}`}
                  className={cn(
                    'group p-4 rounded-lg border hover:border-primary hover:shadow-md transition-all cursor-pointer',
                    viewMode === 'list' && 'flex items-center gap-4'
                  )}
                >
                  <div
                    className={cn(
                      'flex items-center gap-3',
                      viewMode === 'grid' && 'mb-3'
                    )}
                  >
                    <div className="p-2 rounded bg-muted">
                      <FileText className="h-5 w-5 text-muted-foreground" />
                    </div>
                    <div className={cn('flex-1 min-w-0', viewMode === 'grid' && '')}>
                      <h4 className="font-medium truncate">
                        {doc.title || '无标题文档'}
                      </h4>
                      {viewMode === 'list' && (
                        <p className="text-sm text-muted-foreground truncate">
                          {truncateText(doc.content || '', 100)}
                        </p>
                      )}
                    </div>
                  </div>
                  {viewMode === 'grid' && (
                    <p className="text-sm text-muted-foreground line-clamp-2 mb-3">
                      {truncateText(doc.content || '', 100)}
                    </p>
                  )}
                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span>
                      {doc.author?.name || '未知作者'}
                    </span>
                    <span>{formatTimeAgo(doc.updatedAt)}</span>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </TabsContent>

        <TabsContent value="activity" className="mt-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">最近活动</CardTitle>
            </CardHeader>
            <CardContent>
              {activityLoading ? (
                <div className="space-y-4">
                  {[1, 2, 3, 4, 5].map((i) => (
                    <Skeleton key={i} className="h-16 w-full rounded-lg" />
                  ))}
                </div>
              ) : recentActivity?.length === 0 ? (
                <div className="text-center py-8 text-muted-foreground">
                  暂无最近活动
                </div>
              ) : (
                <div className="space-y-4">
                  {recentActivity?.map((doc: SpaceDocument) => (
                    <Link
                      key={doc.id}
                      href={`/spaces/${spaceId}/documents/${doc.id}`}
                      className="flex items-center gap-4 p-3 rounded-lg hover:bg-muted transition-colors"
                    >
                      <div className="p-2 rounded bg-green-50 dark:bg-green-900/20">
                        <FileText className="h-5 w-5 text-green-600" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="font-medium truncate">
                          {doc.title || '无标题文档'}
                        </p>
                        <p className="text-sm text-muted-foreground">
                          由 {doc.author?.name || '未知'} 更新
                        </p>
                      </div>
                      <span className="text-xs text-muted-foreground">
                        {formatTimeAgo(doc.updatedAt)}
                      </span>
                    </Link>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="members" className="mt-6">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle className="text-lg">空间成员</CardTitle>
              <Button size="sm" onClick={handleMembers}>
                管理成员
              </Button>
            </CardHeader>
            <CardContent>
              {membersLoading ? (
                <div className="space-y-3">
                  {[1, 2, 3, 4].map((i) => (
                    <Skeleton key={i} className="h-12 w-full rounded-lg" />
                  ))}
                </div>
              ) : (
                <div className="space-y-3">
                  {members?.map((member: SpaceMember) => (
                    <div
                      key={member.user.id}
                      className="flex items-center gap-3 p-3 rounded-lg bg-muted/50"
                    >
                      <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                        {member.user.avatar ? (
                          <img
                            src={member.user.avatar}
                            alt={member.user.name}
                            className="w-10 h-10 rounded-full"
                          />
                        ) : (
                          <span className="font-medium">
                            {member.user.name.charAt(0).toUpperCase()}
                          </span>
                        )}
                      </div>
                      <div className="flex-1">
                        <p className="font-medium">{member.user.name}</p>
                        <p className="text-sm text-muted-foreground">
                          {member.user.email}
                        </p>
                      </div>
                      <Badge variant="secondary">{member.role}</Badge>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
