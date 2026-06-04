'use client';

import { useState } from 'react';
import { Search, Plus, Grid3X3, List } from 'lucide-react';
import { Input } from '@radix-ui/react-input';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { SpaceCard } from './SpaceCard';
import type { SpaceWithOwner, PaginatedSpaces } from '@/lib/types/space';
import type { Role } from '@/lib/types/permission';

interface SpaceListProps {
  spaces: PaginatedSpaces | null;
  isLoading?: boolean;
  onSpaceClick: (space: SpaceWithOwner) => void;
  onCreateClick: () => void;
  onSearch?: (query: string) => void;
  onLoadMore?: () => void;
  userRoles?: Record<string, Role>;
}

type ViewMode = 'grid' | 'list';

export function SpaceList({
  spaces,
  isLoading = false,
  onSpaceClick,
  onCreateClick,
  onSearch,
  onLoadMore,
  userRoles = {},
}: SpaceListProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [viewMode, setViewMode] = useState<ViewMode>('grid');

  const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setSearchQuery(value);
    onSearch?.(value);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      onSearch?.(searchQuery);
    }
  };

  if (isLoading && !spaces) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div className="h-8 w-48 bg-muted rounded animate-pulse" />
          <div className="flex gap-2">
            <div className="h-9 w-9 bg-muted rounded animate-pulse" />
            <div className="h-9 w-9 bg-muted rounded animate-pulse" />
            <div className="h-9 w-24 bg-muted rounded animate-pulse" />
          </div>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {[...Array(6)].map((_, i) => (
            <div key={i} className="h-48 bg-muted rounded-xl animate-pulse" />
          ))}
        </div>
      </div>
    );
  }

  const items = spaces?.items ?? [];
  const total = spaces?.total ?? 0;

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">我的空间</h2>
          <p className="text-sm text-muted-foreground">
            共 {total} 个空间
          </p>
        </div>

        <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3 w-full sm:w-auto">
          <div className="relative flex-1 sm:w-72">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              type="search"
              placeholder="搜索空间..."
              value={searchQuery}
              onChange={handleSearchChange}
              onKeyDown={handleKeyDown}
              className="pl-9 w-full h-10 bg-background border rounded-md px-3 text-sm"
            />
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
              title="网格视图"
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
              title="列表视图"
            >
              <List className="h-4 w-4" />
            </button>
          </div>

          <Button
            onClick={onCreateClick}
            className="h-10 px-4 bg-primary text-primary-foreground hover:bg-primary/90 rounded-md inline-flex items-center justify-center gap-2 text-sm font-medium transition-colors"
          >
            <Plus className="h-4 w-4" />
            新建空间
          </Button>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <div className="h-20 w-20 rounded-full bg-muted flex items-center justify-center mb-4">
            <Grid3X3 className="h-10 w-10 text-muted-foreground" />
          </div>
          <h3 className="text-lg font-semibold mb-2">暂无空间</h3>
          <p className="text-sm text-muted-foreground mb-6 max-w-sm">
            {searchQuery
              ? '没有找到匹配的空间，试试其他关键词'
              : '创建你的第一个空间，开始团队协作'}
          </p>
          {!searchQuery && (
            <Button
              onClick={onCreateClick}
              className="h-10 px-4 bg-primary text-primary-foreground hover:bg-primary/90 rounded-md inline-flex items-center justify-center gap-2 text-sm font-medium transition-colors"
            >
              <Plus className="h-4 w-4" />
              新建空间
            </Button>
          )}
        </div>
      ) : (
        <>
          <div
            className={cn(
              'gap-6',
              viewMode === 'grid'
                ? 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3'
                : 'flex flex-col'
            )}
          >
            {items.map((space) => (
              <SpaceCard
                key={space.id}
                space={space}
                userRole={userRoles[space.id]}
                onClick={() => onSpaceClick(space)}
                className={cn(
                  viewMode === 'list' && 'flex-row items-center gap-6'
                )}
              />
            ))}
          </div>

          {spaces && spaces.page < spaces.totalPages && (
            <div className="flex justify-center pt-6">
              <Button
                onClick={onLoadMore}
                disabled={isLoading}
                variant="secondary"
                className="h-10 px-6 bg-secondary text-secondary-foreground hover:bg-secondary/80 rounded-md inline-flex items-center justify-center text-sm font-medium transition-colors"
              >
                {isLoading ? '加载中...' : '加载更多'}
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
