'use client';

import Link from 'next/link';
import {
  BookOpen,
  Clock,
  TrendingUp,
  Hash,
  Plus,
  Search,
  FileText,
  FolderOpen,
  Settings,
  Users,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { trpc } from '@/components/providers/TRPCProvider';
import { formatTimeAgo, truncateText } from '@/lib/utils';
import { useRouter } from 'next/navigation';
import { useMemo, useState } from 'react';

export default function DashboardPage() {
  const router = useRouter();
  const [searchQuery, setSearchQuery] = useState('');

  const { data: recentData } = trpc.document.list.useQuery({
    pageSize: 5,
    sortBy: 'createdAt',
  });
  const { data: updatedData } = trpc.document.list.useQuery({
    pageSize: 5,
    sortBy: 'updatedAt',
  });
  const { data: popularTags } = trpc.tag.listAll.useQuery({ pageSize: 20 });
  const { data: spacesData } = trpc.space.list.useQuery({ pageSize: 10 });
  const { data: recommendationsData } =
    trpc.recommendation.getForUser.useQuery({
      limit: 4,
    });

  const recommendations = recommendationsData?.items || [];

  const recentDocuments = recentData?.items || [];
  const updatedDocuments = updatedData?.items || [];
  const spaces = spacesData?.items || [];

  const quickActions = useMemo(
    () => [
      {
        icon: FileText,
        label: '新建文档',
        href: '#',
        onClick: () => router.push('/spaces'),
      },
      {
        icon: FolderOpen,
        label: '我的空间',
        href: '/spaces',
      },
      {
        icon: Search,
        label: '全局搜索',
        href: '/search',
      },
      {
        icon: Settings,
        label: '同步配置',
        href: '/settings/sync',
      },
    ],
    [router]
  );

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="container mx-auto px-4 py-6">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-3xl font-bold">欢迎回来 👋</h1>
              <p className="text-muted-foreground mt-1">
                今天也要高效地管理您的知识资产
              </p>
            </div>
            <div className="flex items-center gap-3">
              <div className="relative w-80">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="搜索文档、标签..."
                  className="pl-10"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && searchQuery) {
                      router.push(`/search?q=${encodeURIComponent(searchQuery)}`);
                    }
                  }}
                />
              </div>
              <Button onClick={() => router.push('/spaces')}>
                <Plus className="h-4 w-4" />
                新建空间
              </Button>
            </div>
          </div>
        </div>
      </header>

      <main className="container mx-auto px-4 py-8">
        <section className="mb-8">
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
            {quickActions.map((action) => (
              <Link
                key={action.label}
                href={action.href}
                onClick={action.onClick}
                className="group"
              >
                <Card className="h-full transition-all hover:shadow-md hover:border-primary cursor-pointer">
                  <CardContent className="p-6">
                    <div className="flex items-center gap-4">
                      <div className="p-3 rounded-lg bg-primary/10 text-primary group-hover:bg-primary group-hover:text-primary-foreground transition-colors">
                        <action.icon className="h-6 w-6" />
                      </div>
                      <span className="font-medium">{action.label}</span>
                    </div>
                  </CardContent>
                </Card>
              </Link>
            ))}
          </div>
        </section>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          <div className="lg:col-span-2 space-y-8">
            <Card>
              <CardHeader className="flex flex-row items-center justify-between">
                <CardTitle className="flex items-center gap-2">
                  <Clock className="h-5 w-5 text-primary" />
                  最近访问
                </CardTitle>
                <Button variant="ghost" size="sm" asChild>
                  <Link href="/spaces">查看全部</Link>
                </Button>
              </CardHeader>
              <CardContent>
                <div className="space-y-3">
                  {recentDocuments?.slice(0, 5).map((doc: any) => (
                    <Link
                      key={doc.id}
                      href={`/spaces/${doc.spaceId}/documents/${doc.id}`}
                      className="block p-3 rounded-lg hover:bg-muted transition-colors"
                    >
                      <div className="flex items-start gap-3">
                        <div className="p-2 rounded bg-muted">
                          <FileText className="h-4 w-4 text-muted-foreground" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <h4 className="font-medium truncate">
                            {doc.title || '无标题文档'}
                          </h4>
                          <p className="text-sm text-muted-foreground truncate">
                            {truncateText(doc.content || '', 80)}
                          </p>
                          <p className="text-xs text-muted-foreground mt-1">
                            {formatTimeAgo(doc.updatedAt)}
                          </p>
                        </div>
                      </div>
                    </Link>
                  ))}
                  {(!recentDocuments || recentDocuments.length === 0) && (
                    <div className="text-center py-8 text-muted-foreground">
                      <BookOpen className="h-12 w-12 mx-auto mb-3 opacity-50" />
                      <p>还没有访问过文档</p>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader className="flex flex-row items-center justify-between">
                <CardTitle className="flex items-center gap-2">
                  <TrendingUp className="h-5 w-5 text-green-500" />
                  最近更新
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-3">
                  {updatedDocuments?.slice(0, 5).map((doc: any) => (
                    <Link
                      key={doc.id}
                      href={`/spaces/${doc.spaceId}/documents/${doc.id}`}
                      className="block p-3 rounded-lg hover:bg-muted transition-colors"
                    >
                      <div className="flex items-start justify-between">
                        <div className="flex items-start gap-3">
                          <div className="p-2 rounded bg-green-50 dark:bg-green-900/20">
                            <FileText className="h-4 w-4 text-green-600" />
                          </div>
                          <div>
                            <h4 className="font-medium">
                              {doc.title || '无标题文档'}
                            </h4>
                            <p className="text-sm text-muted-foreground">
                              {doc.space?.name || '未知空间'}
                            </p>
                          </div>
                        </div>
                        <span className="text-xs text-muted-foreground">
                          {formatTimeAgo(doc.updatedAt)}
                        </span>
                      </div>
                    </Link>
                  ))}
                  {(!updatedDocuments || updatedDocuments.length === 0) && (
                    <div className="text-center py-8 text-muted-foreground">
                      <p>暂无更新的文档</p>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>

            {recommendations.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <TrendingUp className="h-5 w-5 text-purple-500" />
                    为你推荐
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {recommendations.slice(0, 4).map((rec: any) => (
                      <Link
                        key={rec.id}
                        href={`/spaces/${rec.spaceId}/documents/${rec.id}`}
                        className="p-4 rounded-lg border hover:border-primary transition-colors"
                      >
                        <h4 className="font-medium mb-1">
                          {rec.title || '无标题文档'}
                        </h4>
                        <p className="text-sm text-muted-foreground line-clamp-2">
                          {truncateText(rec.content || '', 100)}
                        </p>
                        <div className="flex items-center gap-2 mt-3">
                          <Badge variant="secondary" className="text-xs">
                            相关度 {(rec.relevanceScore * 100).toFixed(0)}%
                          </Badge>
                        </div>
                      </Link>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}
          </div>

          <div className="space-y-8">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Hash className="h-5 w-5 text-blue-500" />
                  热门标签
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="flex flex-wrap gap-2">
                  {popularTags?.items?.slice(0, 20).map((tag: any) => (
                    <Link
                      key={tag.id}
                      href={`/search?tag=${encodeURIComponent(tag.name)}`}
                    >
                      <Badge
                        variant="outline"
                        className="hover:bg-primary hover:text-primary-foreground transition-colors cursor-pointer"
                      >
                        #{tag.name}
                        <span className="ml-1 text-xs opacity-60">
                          ({tag._count?.documents || 0})
                        </span>
                      </Badge>
                    </Link>
                  ))}
                  {(!popularTags?.items || popularTags.items.length === 0) && (
                    <p className="text-sm text-muted-foreground">暂无标签</p>
                  )}
                </div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader className="flex flex-row items-center justify-between">
                <CardTitle className="flex items-center gap-2">
                  <Users className="h-5 w-5 text-orange-500" />
                  我的空间
                </CardTitle>
                <Button variant="ghost" size="sm" asChild>
                  <Link href="/spaces">管理</Link>
                </Button>
              </CardHeader>
              <CardContent>
                <div className="space-y-2">
                  {spaces?.slice(0, 6).map((space: any) => (
                    <Link
                      key={space.id}
                      href={`/spaces/${space.id}`}
                      className="flex items-center gap-3 p-2 rounded-lg hover:bg-muted transition-colors"
                    >
                      <div
                        className="w-8 h-8 rounded flex items-center justify-center text-white text-sm font-medium"
                        style={{ backgroundColor: space.color || '#6366f1' }}
                      >
                        {space.name.charAt(0).toUpperCase()}
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="font-medium truncate">{space.name}</p>
                        <p className="text-xs text-muted-foreground">
                          {space._count?.documents || 0} 个文档
                        </p>
                      </div>
                    </Link>
                  ))}
                  {(!spaces || spaces.length === 0) && (
                    <div className="text-center py-4 text-muted-foreground">
                      <p className="text-sm">还没有空间</p>
                      <Button
                        variant="link"
                        size="sm"
                        onClick={() => router.push('/spaces')}
                      >
                        创建第一个空间
                      </Button>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      </main>
    </div>
  );
}
