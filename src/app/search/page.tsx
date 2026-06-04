'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { useState, useMemo, useEffect } from 'react';
import {
  Search,
  Filter,
  Clock,
  FileText,
  FolderOpen,
  Tag,
  Calendar,
  SortAsc,
  SortDesc,
  X,
  ChevronRight,
  Highlighter,
  BookOpen,
  Loader2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo, truncateText, cn } from '@/lib/utils';
import Link from 'next/link';
import { useDebounce } from '@/hooks/useDebounce';
import type { Space, Tag as TagType, Document, User as UserType } from '@prisma/client';
import type { SpaceWithOwner } from '@/lib/types/space';

type SortBy = 'relevance' | 'updatedAt' | 'createdAt';

interface SearchResultDocument extends Document {
  createdBy: Pick<UserType, 'id' | 'name' | 'avatar'> | null;
  author?: Pick<UserType, 'id' | 'name' | 'avatar'> | null;
  tags: TagType[];
  space: Pick<Space, 'id' | 'name' | 'icon'>;
}

type SortOrder = 'asc' | 'desc';
type TimeRange = 'all' | 'today' | 'week' | 'month' | 'year';

export default function SearchPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { toast } = useToast();

  const initialQuery = searchParams.get('q') || '';
  const initialSpaceId = searchParams.get('spaceId') || '';
  const initialTag = searchParams.get('tag') || '';

  const [query, setQuery] = useState(initialQuery);
  const [sortBy, setSortBy] = useState<SortBy>('relevance');
  const [sortOrder, setSortOrder] = useState<SortOrder>('desc');
  const [selectedSpaces, setSelectedSpaces] = useState<string[]>(
    initialSpaceId ? [initialSpaceId] : []
  );
  const [selectedTags, setSelectedTags] = useState<string[]>(
    initialTag ? [initialTag] : []
  );
  const [timeRange, setTimeRange] = useState<TimeRange>('all');
  const [selectedSources, setSelectedSources] = useState<string[]>([]);
  const [showSidebar, setShowSidebar] = useState(true);

  const debouncedQuery = useDebounce(query, 300);

  const getDateFromTimeRange = (range: TimeRange): Date | undefined => {
    const now = new Date();
    switch (range) {
      case 'today':
        return new Date(now.setHours(0, 0, 0, 0));
      case 'week':
        return new Date(now.setDate(now.getDate() - 7));
      case 'month':
        return new Date(now.setMonth(now.getMonth() - 1));
      case 'year':
        return new Date(now.setFullYear(now.getFullYear() - 1));
      default:
        return undefined;
    }
  };

  const dateFrom = getDateFromTimeRange(timeRange);

  const { data: results, isLoading, refetch } = trpc.search.search.useQuery(
    {
      query: debouncedQuery,
      spaceId: selectedSpaces.length > 0 ? selectedSpaces[0] : undefined,
      tagIds: selectedTags.length > 0 ? selectedTags : undefined,
      dateFrom,
      source: selectedSources.length > 0 ? (selectedSources[0] as any) : undefined,
      pageSize: 50,
    },
    {
      enabled: !!debouncedQuery,
    }
  );

  const { data: spaces } = trpc.space.list.useQuery({ pageSize: 100 });
  const { data: allTags } = trpc.tag.listAll.useQuery({ pageSize: 100 });

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    refetch();
  };

  const toggleSpace = (spaceId: string) => {
    setSelectedSpaces((prev) =>
      prev.includes(spaceId)
        ? prev.filter((s) => s !== spaceId)
        : [...prev, spaceId]
    );
  };

  const toggleTag = (tagId: string) => {
    setSelectedTags((prev) =>
      prev.includes(tagId) ? prev.filter((t) => t !== tagId) : [...prev, tagId]
    );
  };

  const toggleSource = (source: string) => {
    setSelectedSources((prev) =>
      prev.includes(source)
        ? prev.filter((s) => s !== source)
        : [...prev, source]
    );
  };

  const clearFilters = () => {
    setSelectedSpaces([]);
    setSelectedTags([]);
    setSelectedSources([]);
    setTimeRange('all');
    setSortBy('relevance');
    setSortOrder('desc');
  };

  const activeFiltersCount =
    selectedSpaces.length +
    selectedTags.length +
    selectedSources.length +
    (timeRange !== 'all' ? 1 : 0);

  const sourceOptions = [
    { value: 'MANUAL', label: '手动创建' },
    { value: 'IMPORTED', label: '导入' },
    { value: 'SYNCED', label: '同步' },
    { value: 'API', label: 'API' },
  ];

  const timeRangeOptions = [
    { value: 'all', label: '全部时间' },
    { value: 'today', label: '今天' },
    { value: 'week', label: '本周' },
    { value: 'month', label: '本月' },
    { value: 'year', label: '今年' },
  ];

  const spacesList = spaces?.items || [];
  const tagsList = allTags?.items || [];

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b sticky top-0 bg-background z-30">
        <div className="container mx-auto px-4 py-4">
          <form onSubmit={handleSearch} className="flex gap-3">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-5 w-5 text-muted-foreground" />
              <Input
                placeholder="搜索文档、标签、内容..."
                className="pl-10 h-11 text-base"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                autoFocus
              />
              {query && (
                <button
                  type="button"
                  onClick={() => setQuery('')}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  <X className="h-4 w-4" />
                </button>
              )}
            </div>
            <Button type="submit" size="lg" disabled={isLoading}>
              {isLoading ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : (
                <Search className="mr-2 h-4 w-4" />
              )}
              搜索
            </Button>
            <Button
              type="button"
              variant="outline"
              size="icon"
              className="lg:hidden"
              onClick={() => setShowSidebar(!showSidebar)}
            >
              <Filter className="h-4 w-4" />
            </Button>
          </form>
        </div>
      </header>

      <div className="container mx-auto px-4 py-6">
        <div className="flex gap-6">
          {/* Sidebar Filters */}
          <aside
            className={cn(
              'w-72 shrink-0 transition-all',
              !showSidebar && 'hidden lg:block'
            )}
          >
            <div className="sticky top-24 space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="font-semibold flex items-center gap-2">
                  <Filter className="h-4 w-4" />
                  筛选
                  {activeFiltersCount > 0 && (
                    <Badge variant="secondary" className="text-xs">
                      {activeFiltersCount}
                    </Badge>
                  )}
                </h3>
                {activeFiltersCount > 0 && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={clearFilters}
                    className="h-7 px-2 text-xs"
                  >
                    清除全部
                  </Button>
                )}
              </div>

              <Card>
                <CardContent className="p-0">
                  <Accordion type="multiple" defaultValue={['spaces', 'tags', 'time', 'source']}>
                    <AccordionItem value="spaces">
                      <AccordionTrigger className="px-4 py-3 hover:no-underline">
                        <span className="flex items-center gap-2">
                          <FolderOpen className="h-4 w-4" />
                          空间
                        </span>
                      </AccordionTrigger>
                      <AccordionContent className="px-4 pb-3">
                        <div className="space-y-2">
                          {spacesList.map((space: SpaceWithOwner) => (
                            <div
                              key={space.id}
                              className="flex items-center gap-2"
                            >
                              <Checkbox
                                id={`space-${space.id}`}
                                checked={selectedSpaces.includes(space.id)}
                                onCheckedChange={() => toggleSpace(space.id)}
                              />
                              <Label
                                htmlFor={`space-${space.id}`}
                                className="text-sm font-normal cursor-pointer flex-1 truncate"
                              >
                                {space.name}
                              </Label>
                            </div>
                          ))}
                        </div>
                      </AccordionContent>
                    </AccordionItem>

                    <AccordionItem value="tags">
                      <AccordionTrigger className="px-4 py-3 hover:no-underline">
                        <span className="flex items-center gap-2">
                          <Tag className="h-4 w-4" />
                          标签
                        </span>
                      </AccordionTrigger>
                      <AccordionContent className="px-4 pb-3">
                        <div className="flex flex-wrap gap-2">
                          {tagsList.slice(0, 20).map((tag: TagType) => (
                            <button
                              key={tag.id}
                              onClick={() => toggleTag(tag.id)}
                              className={cn(
                                'px-2 py-1 rounded-full text-xs border transition-colors',
                                selectedTags.includes(tag.id)
                                  ? 'bg-primary text-primary-foreground border-primary'
                                  : 'bg-background hover:bg-muted border-border'
                              )}
                              style={{
                                borderColor: selectedTags.includes(tag.id)
                                  ? undefined
                                  : tag.color || undefined,
                                color: selectedTags.includes(tag.id)
                                  ? undefined
                                  : tag.color || undefined,
                              }}
                            >
                              #{tag.name}
                            </button>
                          ))}
                        </div>
                      </AccordionContent>
                    </AccordionItem>

                    <AccordionItem value="time">
                      <AccordionTrigger className="px-4 py-3 hover:no-underline">
                        <span className="flex items-center gap-2">
                          <Calendar className="h-4 w-4" />
                          时间
                        </span>
                      </AccordionTrigger>
                      <AccordionContent className="px-4 pb-3">
                        <Select
                          value={timeRange}
                          onValueChange={(v) => setTimeRange(v as TimeRange)}
                        >
                          <SelectTrigger>
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {timeRangeOptions.map((option) => (
                              <SelectItem key={option.value} value={option.value}>
                                {option.label}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </AccordionContent>
                    </AccordionItem>

                    <AccordionItem value="source">
                      <AccordionTrigger className="px-4 py-3 hover:no-underline">
                        <span className="flex items-center gap-2">
                          <FileText className="h-4 w-4" />
                          来源
                        </span>
                      </AccordionTrigger>
                      <AccordionContent className="px-4 pb-3">
                        <div className="space-y-2">
                          {sourceOptions.map((source) => (
                            <div
                              key={source.value}
                              className="flex items-center gap-2"
                            >
                              <Checkbox
                                id={`source-${source.value}`}
                                checked={selectedSources.includes(source.value)}
                                onCheckedChange={() => toggleSource(source.value)}
                              />
                              <Label
                                htmlFor={`source-${source.value}`}
                                className="text-sm font-normal cursor-pointer"
                              >
                                {source.label}
                              </Label>
                            </div>
                          ))}
                        </div>
                      </AccordionContent>
                    </AccordionItem>
                  </Accordion>
                </CardContent>
              </Card>

              <Card>
                <CardContent className="p-4">
                  <Label className="text-sm font-medium mb-2 block">
                    排序方式
                  </Label>
                  <div className="flex gap-2">
                    <Select
                      value={sortBy}
                      onValueChange={(v) => setSortBy(v as SortBy)}
                    >
                      <SelectTrigger className="flex-1">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="relevance">相关度</SelectItem>
                        <SelectItem value="updatedAt">更新时间</SelectItem>
                        <SelectItem value="createdAt">创建时间</SelectItem>
                      </SelectContent>
                    </Select>
                    <Button
                      variant="outline"
                      size="icon"
                      onClick={() =>
                        setSortOrder(sortOrder === 'asc' ? 'desc' : 'asc')
                      }
                    >
                      {sortOrder === 'asc' ? (
                        <SortAsc className="h-4 w-4" />
                      ) : (
                        <SortDesc className="h-4 w-4" />
                      )}
                    </Button>
                  </div>
                </CardContent>
              </Card>
            </div>
          </aside>

          {/* Results */}
          <main className="flex-1 min-w-0">
            {query && (
              <div className="mb-4 flex items-center justify-between">
                <p className="text-sm text-muted-foreground">
                  {isLoading
                    ? '搜索中...'
                    : `找到 ${results?.total || 0} 个结果`}
                </p>
              </div>
            )}

            {!query ? (
              <Card>
                <CardContent className="p-12 text-center">
                  <Search className="h-12 w-12 mx-auto mb-4 text-muted-foreground opacity-50" />
                  <h3 className="text-lg font-semibold mb-2">搜索文档</h3>
                  <p className="text-muted-foreground mb-4">
                    输入关键词开始搜索您的知识库
                  </p>
                  <div className="flex flex-wrap justify-center gap-2">
                    {['产品文档', '技术规范', '会议记录', '项目计划'].map((suggestion) => (
                      <Button
                        key={suggestion}
                        variant="outline"
                        size="sm"
                        onClick={() => setQuery(suggestion)}
                      >
                        {suggestion}
                      </Button>
                    ))}
                  </div>
                </CardContent>
              </Card>
            ) : isLoading ? (
              <div className="space-y-4">
                {[1, 2, 3, 4, 5].map((i) => (
                  <Card key={i}>
                    <CardContent className="p-4">
                      <Skeleton className="h-5 w-3/4 mb-2" />
                      <Skeleton className="h-4 w-full mb-2" />
                      <Skeleton className="h-4 w-5/6 mb-3" />
                      <div className="flex gap-2">
                        <Skeleton className="h-6 w-16 rounded-full" />
                        <Skeleton className="h-6 w-20 rounded-full" />
                      </div>
                    </CardContent>
                  </Card>
                ))}
              </div>
            ) : results?.items.length === 0 ? (
              <Card>
                <CardContent className="p-12 text-center">
                  <BookOpen className="h-12 w-12 mx-auto mb-4 text-muted-foreground opacity-50" />
                  <h3 className="text-lg font-semibold mb-2">未找到结果</h3>
                  <p className="text-muted-foreground mb-4">
                    尝试使用不同的关键词或调整筛选条件
                  </p>
                  <Button variant="outline" onClick={clearFilters}>
                    清除筛选条件
                  </Button>
                </CardContent>
              </Card>
            ) : (
              <div className="space-y-4">
                {results?.items.map((doc: any) => (
                  <Link
                    key={doc.id}
                    href={`/spaces/${doc.spaceId}/documents/${doc.id}`}
                    className="block"
                  >
                    <Card className="hover:border-primary hover:shadow-md transition-all">
                      <CardContent className="p-4">
                        <div className="flex items-start gap-3">
                          <div className="p-2 rounded bg-muted mt-0.5">
                            <FileText className="h-4 w-4 text-muted-foreground" />
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2 mb-1">
                              <h4 className="font-medium truncate">
                                {doc.title || '无标题文档'}
                              </h4>
                            </div>
                            <p className="text-sm text-muted-foreground line-clamp-2 mb-3">
                              {truncateText(doc.content || '', 200)}
                            </p>
                            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                              {doc.space && (
                                <Badge variant="outline" className="text-xs">
                                  {doc.space.name}
                                </Badge>
                              )}
                              {doc.tags?.slice(0, 3).map((tag: TagType) => (
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
                              <Separator
                                orientation="vertical"
                                className="h-3"
                              />
                              <span>
                                {doc.createdBy?.name || '未知'}
                              </span>
                              <Separator
                                orientation="vertical"
                                className="h-3"
                              />
                              <span>
                                {formatTimeAgo(doc.updatedAt)}
                              </span>
                            </div>
                          </div>
                          <ChevronRight className="h-5 w-5 text-muted-foreground shrink-0 mt-2" />
                        </div>
                      </CardContent>
                    </Card>
                  </Link>
                ))}
              </div>
            )}
          </main>
        </div>
      </div>
    </div>
  );
}
