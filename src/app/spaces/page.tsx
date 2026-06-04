'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { SpaceList } from '@/components/spaces/SpaceList';
import { CreateSpaceDialog } from '@/components/spaces/CreateSpaceDialog';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import type { SpaceWithOwner, PaginatedSpaces } from '@/lib/types/space';
import type { CreateSpaceInput, SpaceBasic } from '@/lib/types/space';

interface SpacePageResult extends PaginatedSpaces {}

export default function SpacesPage() {
  const router = useRouter();
  const { toast } = useToast();
  const [isCreateDialogOpen, setIsCreateDialogOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [page, setPage] = useState(1);

  const { data: spaces, isLoading, fetchNextPage, hasNextPage } =
    trpc.space.list.useInfiniteQuery(
      {
        pageSize: 20,
        search: searchQuery || undefined,
      },
      {
        getNextPageParam: (lastPage: SpacePageResult) =>
          lastPage.page < lastPage.totalPages ? lastPage.page + 1 : undefined,
      }
    );

  const createSpaceMutation = trpc.space.create.useMutation({
    onSuccess: (newSpace: SpaceBasic) => {
      toast({
        title: '创建成功',
        description: `空间 "${newSpace.name}" 已创建`,
        variant: 'success',
      });
      setIsCreateDialogOpen(false);
      router.push(`/spaces/${newSpace.id}`);
    },
    onError: (error: unknown) => {
      const errorMessage = error instanceof Error ? error.message : '创建失败';
      toast({
        title: '创建失败',
        description: errorMessage,
        variant: 'destructive',
      });
    },
  });

  const handleSpaceClick = (space: SpaceWithOwner) => {
    router.push(`/spaces/${space.id}`);
  };

  const handleCreateSpace = (input: CreateSpaceInput) => {
    createSpaceMutation.mutate(input);
  };

  const handleSearch = (query: string) => {
    setSearchQuery(query);
    setPage(1);
  };

  const handleLoadMore = () => {
    if (hasNextPage) {
      fetchNextPage();
    }
  };

  const paginatedSpaces = spaces
    ? {
        items: spaces.pages.flatMap((page: SpacePageResult) => page.items),
        total: spaces.pages[0]?.total ?? 0,
        page: spaces.pages.length,
        pageSize: 20,
        totalPages: spaces.pages[0]?.totalPages ?? 0,
      }
    : null;

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="container mx-auto px-4 py-6">
          <div>
            <h1 className="text-3xl font-bold">空间管理</h1>
            <p className="text-muted-foreground mt-1">
              管理您的所有知识空间，创建和组织团队文档
            </p>
          </div>
        </div>
      </header>

      <main className="container mx-auto px-4 py-8">
        <SpaceList
          spaces={paginatedSpaces}
          isLoading={isLoading}
          onSpaceClick={handleSpaceClick}
          onCreateClick={() => setIsCreateDialogOpen(true)}
          onSearch={handleSearch}
          onLoadMore={handleLoadMore}
        />
      </main>

      <CreateSpaceDialog
        open={isCreateDialogOpen}
        onOpenChange={setIsCreateDialogOpen}
        onCreate={handleCreateSpace}
        isLoading={createSpaceMutation.isLoading}
      />
    </div>
  );
}
