'use client';

import { useParams } from 'next/navigation';
import { SpaceLayoutSidebar } from '@/components/spaces/SpaceLayoutSidebar';
import { SpaceHeader } from '@/components/spaces/SpaceHeader';
import { trpc } from '@/components/providers/TRPCProvider';
import { Skeleton } from '@/components/ui/skeleton';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import Link from 'next/link';
import { Home, AlertTriangle } from 'lucide-react';

export default function SpaceLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const params = useParams<{ spaceId: string }>();
  const spaceId = params?.spaceId as string;

  const { data: space, isLoading, error } = trpc.space.getById.useQuery(
    { id: spaceId },
    { enabled: !!spaceId }
  );

  if (isLoading) {
    return (
      <div className="min-h-screen bg-background flex">
        <div className="w-72 border-r bg-sidebar p-4 space-y-4">
          <Skeleton className="h-10 w-full rounded-lg" />
          <Skeleton className="h-9 w-full rounded-md" />
          <div className="space-y-2 pt-4">
            {[1, 2, 3, 4, 5].map((i) => (
              <Skeleton key={i} className="h-8 w-full rounded-md" />
            ))}
          </div>
        </div>
        <div className="flex-1">
          <Skeleton className="h-14 w-full" />
          <div className="p-8">
            <Skeleton className="h-8 w-48 mb-4" />
            <Skeleton className="h-4 w-full mb-2" />
            <Skeleton className="h-4 w-3/4" />
          </div>
        </div>
      </div>
    );
  }

  if (error || !space) {
    return (
      <div className="min-h-screen bg-background flex items-center justify-center p-4">
        <div className="max-w-md w-full">
          <Alert variant="destructive">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle>空间不存在</AlertTitle>
            <AlertDescription className="mt-2">
              {error?.message || '您访问的空间不存在或已被删除。'}
            </AlertDescription>
          </Alert>
          <div className="mt-4 flex justify-center">
            <Button asChild>
              <Link href="/spaces">
                <Home className="mr-2 h-4 w-4" />
                返回空间列表
              </Link>
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background flex">
      <SpaceLayoutSidebar space={space} spaceId={spaceId} />
      <div className="flex-1 flex flex-col min-w-0">
        <SpaceHeader spaceId={spaceId} spaceName={space.name} />
        <main className="flex-1 overflow-auto">{children}</main>
      </div>
    </div>
  );
}
