'use client';

import { useParams, useRouter, useSearchParams } from 'next/navigation';
import { useState, useMemo } from 'react';
import {
  Plus,
  Grid3X3,
  List,
  Search,
  Filter,
  Clock,
  User,
  Tag,
  ArrowUpDown,
  FileText,
  FolderOpen,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuCheckboxItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo, truncateText, cn } from '@/lib/utils';
import { Skeleton } from '@/components/ui/skeleton';
import Link from 'next/link';
import type { Tag as TagType, Document, User as UserType } from '@prisma/client';
import type { DocumentStatus } from '@/lib/types';

interface DocumentListItem extends Document {
  createdBy?: Pick<UserType, 'id' | 'name' | 'avatar'> | null;
  tags?: Array<Pick<TagType, 'id' | 'name' | 'color'>>;
  status?: DocumentStatus;
}

interface DocumentPageResult {
  items: DocumentListItem[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

type ViewMode = 'grid' | 'list';
type SortBy = 'updatedAt' | 'createdAt' | 'title';
type SortOrder = 'asc' | 'desc';

export default function DocumentsPage() {
  const params = useParams<{ spaceId: string }>();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { toast } = useToast();
  const spaceId = params?.spaceId as string;

  const [viewMode, setViewMode] = useState<ViewMode>(
    (searchParams.get('view') as ViewMode) || 'grid'
  );
  const [searchQuery, setSearchQuery] = useState(
    searchParams.get('search') || ''
  );
  const [sortBy, setSortBy] = useState<SortBy>(
    (searchParams.get('sortBy') as SortBy) || 'updatedAt'
  );
  const [sortOrder, setSortOrder] = useState<SortOrder>(
    (searchParams.get('sortOrder') as SortOrder) || 'desc'
  );
  const [statusFilter, setStatusFilter] = useState<DocumentStatus | 'all'>(
    (searchParams.get('status') as DocumentStatus) || 'all'
  );
  const [selectedTags, setSelectedTags] = useState<string[]>(
    searchParams.getAll('tag')
  );
  const [selectedAuthors, setSelectedAuthors] = useState<string[]>(
    searchParams.getAll('author')
  );

  const { data: documents, isLoading } =
    trpc.document.list.useQuery(
      {
        spaceId,
        pageSize: 100,
        search: searchQuery || undefined,
        sortBy,
        sortOrder,
        status: statusFilter === 'all' ? undefined : statusFilter,
        tagIds: selectedTags.length > 0 ? selectedTags : undefined,
      },
      {
        enabled: !!spaceId,
      }
    );

  const { data: tagsData } = trpc.tag.list.useQuery(
    { spaceId, pageSize: 100 },
    { enabled: !!spaceId }
  );

  const handleNewDocument = () => {
    router.push(`/spaces/${spaceId}/documents/new`);
  };

  const handleSearch = (value: string) => {
    setSearchQuery(value);
  };

  const toggleTag = (tagId: string) => {
    setSelectedTags((prev) =>
      prev.includes(tagId) ? prev.filter((t) => t !== tagId) : [...prev, tagId]
    );
  };

  const allDocuments = useMemo(
    () => documents?.items || [],
    [documents]
  );

  const authors = useMemo(() => {
    const uniqueAuthors = new Map<string, { id: string; name: string }>();
    allDocuments.forEach((doc: any) => {
      if (doc.createdBy) {
        uniqueAuthors.set(doc.createdBy.id, doc.createdBy);
      }
    });
    return Array.from(uniqueAuthors.values());
  }, [allDocuments]);

  const toggleAuthor = (authorId: string) => {
    setSelectedAuthors((prev) =>
      prev.includes(authorId)
        ? prev.filter((a) => a !== authorId)
        : [...prev, authorId]
    );
  };

  const filteredDocuments = useMemo(() => {
    return allDocuments.filter((doc: any) => {
      if (
        selectedAuthors.length > 0 &&
        !selectedAuthors.includes(doc.createdBy?.id || '')
      ) {
        return false;
      }
      return true;
    });
  }, [allDocuments, selectedAuthors]);

  const tags = tagsData?.items || [];

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
          <div>
            <Skeleton className="h-8 w-32 mb-2" />
            <Skeleton className="h-4 w-48" />
          </div>
          <Skeleton className="h-10 w-32" />
        </div>
        <div className="flex flex-wrap gap-3 mb-6">
          {[1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-10 w-32" />
          ))}
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <Skeleton key={i} className="h-32 rounded-lg" />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="p-8">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
        <div>
          <h1 className="text-2xl font-bold">文档</h1>
          <p className="text-muted-foreground">
            共 {documents?.total || 0} 个文档
          </p>
        </div>
        <Button onClick={handleNewDocument}>
          <Plus className="mr-2 h-4 w-4" />
          新建文档
        </Button>
      </div>

      <div className="flex flex-col lg:flex-row gap-4 mb-6">
        <div className="flex-1 relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="搜索文档标题、内容..."
            className="pl-10"
            value={searchQuery}
            onChange={(e) => handleSearch(e.target.value)}
          />
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <Tabs
            value={statusFilter}
            onValueChange={(v) => setStatusFilter(v as DocumentStatus | 'all')}
            className="w-auto"
          >
            <TabsList>
              <TabsTrigger value="all">全部</TabsTrigger>
              <TabsTrigger value="DRAFT">草稿</TabsTrigger>
              <TabsTrigger value="PUBLISHED">已发布</TabsTrigger>
              <TabsTrigger value="ARCHIVED">已归档</TabsTrigger>
            </TabsList>
          </Tabs>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm">
                <Tag className="mr-2 h-4 w-4" />
                标签
                {selectedTags.length > 0 && (
                  <Badge className="ml-2 h-5 px-1.5 text-xs">
                    {selectedTags.length}
                  </Badge>
                )}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent className="w-56">
              {tags?.length === 0 ? (
                <div className="p-2 text-sm text-muted-foreground text-center">
                  暂无标签
                </div>
              ) : (
                tags?.map((tag: TagType) => (
                  <DropdownMenuCheckboxItem
                    key={tag.id}
                    checked={selectedTags.includes(tag.id)}
                    onCheckedChange={() => toggleTag(tag.id)}
                  >
                    <div className="flex items-center gap-2">
                      <div
                        className="w-3 h-3 rounded-full"
                        style={{ backgroundColor: tag.color || '#6366f1' }}
                      />
                      {tag.name}
                    </div>
                  </DropdownMenuCheckboxItem>
                ))
              )}
            </DropdownMenuContent>
          </DropdownMenu>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="sm">
                <User className="mr-2 h-4 w-4" />
                作者
                {selectedAuthors.length > 0 && (
                  <Badge className="ml-2 h-5 px-1.5 text-xs">
                    {selectedAuthors.length}
                  </Badge>
                )}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent className="w-56">
              {authors.length === 0 ? (
                <div className="p-2 text-sm text-muted-foreground text-center">
                  暂无作者
                </div>
              ) : (
                  authors.map((author) => (
                    <DropdownMenuCheckboxItem
                      key={author.id}
                      checked={selectedAuthors.includes(author.id)}
                      onCheckedChange={() => toggleAuthor(author.id)}
                    >
                      {author.name}
                    </DropdownMenuCheckboxItem>
                  ))
                )}
            </DropdownMenuContent>
          </DropdownMenu>

          <Select
            value={`${sortBy}-${sortOrder}`}
            onValueChange={(value) => {
              const [by, order] = value.split('-');
              setSortBy(by as SortBy);
              setSortOrder(order as SortOrder);
            }}
          >
            <SelectTrigger className="w-40 h-9">
              <SelectValue placeholder="排序方式" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="updatedAt-desc">最近更新</SelectItem>
              <SelectItem value="updatedAt-asc">最早更新</SelectItem>
              <SelectItem value="createdAt-desc">最近创建</SelectItem>
              <SelectItem value="createdAt-asc">最早创建</SelectItem>
              <SelectItem value="title-asc">标题 A-Z</SelectItem>
              <SelectItem value="title-desc">标题 Z-A</SelectItem>
            </SelectContent>
          </Select>

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
      </div>

      {filteredDocuments.length === 0 ? (
        <div className="text-center py-16">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-muted mb-4">
            <FolderOpen className="h-8 w-8 text-muted-foreground" />
          </div>
          <h3 className="text-lg font-semibold mb-2">暂无文档</h3>
          <p className="text-muted-foreground mb-4">
            {searchQuery ||
            selectedTags.length > 0 ||
            selectedAuthors.length > 0 ||
            statusFilter !== 'all'
              ? '没有找到匹配的文档，试试其他筛选条件'
              : '创建您的第一个文档开始使用'}
          </p>
          {!(
            searchQuery ||
            selectedTags.length > 0 ||
            selectedAuthors.length > 0 ||
            statusFilter !== 'all'
          ) && (
            <Button onClick={handleNewDocument}>
              <Plus className="mr-2 h-4 w-4" />
              新建文档
            </Button>
          )}
        </div>
      ) : (
        <>
          <div
            className={cn(
              'gap-4',
              viewMode === 'grid'
                ? 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3'
                : 'flex flex-col'
            )}
          >
            {filteredDocuments.map((doc: any) => (
              <Link
                key={doc.id}
                href={`/spaces/${spaceId}/documents/${doc.id}`}
                className={cn(
                  'group p-4 rounded-lg border hover:border-primary hover:shadow-md transition-all cursor-pointer',
                  viewMode === 'list' && 'flex items-start gap-4'
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
                  <div className={cn('flex-1 min-w-0')}>
                    <div className="flex items-center gap-2">
                      <h4 className="font-medium truncate">
                        {doc.title || '无标题文档'}
                      </h4>
                      {doc.status === 'DRAFT' && (
                        <Badge variant="secondary" className="text-xs">
                          草稿
                        </Badge>
                      )}
                      {doc.status === 'ARCHIVED' && (
                        <Badge variant="outline" className="text-xs">
                          已归档
                        </Badge>
                      )}
                    </div>
                    {viewMode === 'list' && (
                      <p className="text-sm text-muted-foreground truncate mt-1">
                        {truncateText(doc.content || '', 150)}
                      </p>
                    )}
                  </div>
                </div>
                {viewMode === 'grid' && (
                  <p className="text-sm text-muted-foreground line-clamp-2 mb-3">
                    {truncateText(doc.content || '', 100)}
                  </p>
                )}
                {doc.tags && doc.tags.length > 0 && (
                  <div className="flex flex-wrap gap-1 mb-3">
                    {doc.tags.slice(0, 3).map((tag: any) => (
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
                    {doc.tags.length > 3 && (
                      <Badge variant="outline" className="text-xs">
                        +{doc.tags.length - 3}
                      </Badge>
                    )}
                  </div>
                )}
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <div className="flex items-center gap-1">
                    <User className="h-3 w-3" />
                    {doc.createdBy?.name || '未知'}
                  </div>
                  <div className="flex items-center gap-1">
                    <Clock className="h-3 w-3" />
                    {formatTimeAgo(doc.updatedAt)}
                  </div>
                </div>
              </Link>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
