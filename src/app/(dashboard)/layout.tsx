'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import {
  Home,
  Search,
  FolderOpen,
  FileCheck,
  Settings,
  Bell,
  User,
  Menu,
  X,
  LogOut,
  UserCircle,
  ChevronRight,
  BookOpen,
  RefreshCw,
} from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';

interface DashboardLayoutProps {
  children: React.ReactNode;
}

const navigation = [
  {
    name: '首页',
    href: '/',
    icon: Home,
  },
  {
    name: '我的空间',
    href: '/spaces',
    icon: FolderOpen,
  },
  {
    name: '全局搜索',
    href: '/search',
    icon: Search,
  },
  {
    name: '审阅中心',
    href: '/reviews',
    icon: FileCheck,
    badge: 3,
  },
  {
    name: '同步配置',
    href: '/settings/sync',
    icon: RefreshCw,
  },
];

const settingsNavigation = [
  {
    name: '个人资料',
    href: '/settings/profile',
    icon: UserCircle,
  },
  {
    name: '偏好设置',
    href: '/settings/preferences',
    icon: Settings,
  },
];

export default function DashboardLayout({ children }: DashboardLayoutProps) {
  const pathname = usePathname();
  const router = useRouter();
  const { toast } = useToast();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const { data: user, isLoading: userLoading } =
    trpc.auth.getCurrentUser.useQuery();

  const { data: spacesData, isLoading: spacesLoading } =
    trpc.space.list.useQuery({ pageSize: 100 });

  const spaces = spacesData?.items || [];

  const { data: receivedReviews } = trpc.review.list.useQuery({
    asReviewer: true,
    status: 'PENDING',
  });

  const pendingReviewsCount = receivedReviews?.items?.length || 0;

  const handleLogout = () => {
    toast({
      title: '已退出登录',
      description: '期待您的再次访问',
    });
    router.push('/login');
    router.refresh();
  };

  return (
    <div className="min-h-screen bg-background">
      {/* Mobile sidebar */}
      <div
        className={cn(
          'fixed inset-0 z-50 lg:hidden',
          sidebarOpen ? 'block' : 'hidden'
        )}
      >
        <div
          className="fixed inset-0 bg-black/50"
          onClick={() => setSidebarOpen(false)}
        />
        <div className="fixed inset-y-0 left-0 z-50 w-72 bg-sidebar border-r">
          <div className="flex items-center justify-between h-14 px-4 border-b">
            <Link href="/" className="flex items-center gap-2">
              <div className="p-1.5 rounded-lg bg-primary text-primary-foreground">
                <BookOpen className="h-5 w-5" />
              </div>
              <span className="font-bold">Knowledge Hub</span>
            </Link>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setSidebarOpen(false)}
            >
              <X className="h-5 w-5" />
            </Button>
          </div>
          <div className="flex flex-col h-[calc(100%-3.5rem)] overflow-y-auto">
            <SidebarContent
              pathname={pathname}
              spaces={spaces}
              spacesLoading={spacesLoading}
              pendingReviewsCount={pendingReviewsCount}
              onNavigate={() => setSidebarOpen(false)}
            />
          </div>
        </div>
      </div>

      {/* Desktop sidebar */}
      <div className="hidden lg:flex lg:w-72 lg:flex-col lg:fixed lg:inset-y-0 lg:border-r lg:bg-sidebar">
        <div className="flex items-center gap-2 h-14 px-4 border-b">
          <div className="p-1.5 rounded-lg bg-primary text-primary-foreground">
            <BookOpen className="h-5 w-5" />
          </div>
          <span className="font-bold">Knowledge Hub</span>
        </div>
        <div className="flex flex-col flex-1 overflow-y-auto">
          <SidebarContent
            pathname={pathname}
            spaces={spaces}
            spacesLoading={spacesLoading}
            pendingReviewsCount={pendingReviewsCount}
          />
        </div>
      </div>

      {/* Main content */}
      <div className="lg:pl-72">
        {/* Header */}
        <header className="sticky top-0 z-40 flex items-center justify-between h-14 px-4 border-b bg-background">
          <div className="flex items-center gap-4">
            <Button
              variant="ghost"
              size="icon"
              className="lg:hidden"
              onClick={() => setSidebarOpen(true)}
            >
              <Menu className="h-5 w-5" />
            </Button>
            <h1 className="font-semibold truncate">
              {getPageTitle(pathname)}
            </h1>
          </div>

          <div className="flex items-center gap-2">
            <Link
              href="/search"
              className="hidden md:flex items-center gap-2 px-3 py-1.5 rounded-md text-sm text-muted-foreground hover:bg-muted transition-colors"
            >
              <Search className="h-4 w-4" />
              <span>搜索...</span>
            </Link>

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" className="relative">
                  <Bell className="h-5 w-5" />
                  {pendingReviewsCount > 0 && (
                    <Badge className="absolute -top-1 -right-1 h-5 w-5 p-0 flex items-center justify-center text-xs">
                      {pendingReviewsCount}
                    </Badge>
                  )}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-80">
                <DropdownMenuLabel>通知</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {pendingReviewsCount > 0 ? (
                  <div className="p-2">
                    <Link
                      href="/reviews"
                      className="flex items-center gap-3 p-2 rounded-md hover:bg-muted transition-colors"
                    >
                      <div className="p-2 rounded-full bg-yellow-100 dark:bg-yellow-900/30">
                        <FileCheck className="h-4 w-4 text-yellow-600" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-medium truncate">
                          有待处理的审阅请求
                        </p>
                        <p className="text-xs text-muted-foreground">
                          {pendingReviewsCount} 个审阅请求等待您处理
                        </p>
                      </div>
                    </Link>
                  </div>
                ) : (
                  <div className="p-8 text-center text-sm text-muted-foreground">
                    暂无新通知
                  </div>
                )}
              </DropdownMenuContent>
            </DropdownMenu>

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" className="gap-2 h-9 px-2">
                  {userLoading ? (
                    <Skeleton className="h-8 w-8 rounded-full" />
                  ) : (
                    <Avatar className="h-8 w-8">
                      <AvatarImage
                        src={user?.avatar || ''}
                        alt={user?.name}
                      />
                      <AvatarFallback>
                        {user?.name?.charAt(0) || 'U'}
                      </AvatarFallback>
                    </Avatar>
                  )}
                  <span className="hidden sm:inline text-sm">
                    {user?.name}
                  </span>
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>
                  <div className="flex flex-col">
                    <span className="font-medium">{user?.name}</span>
                    <span className="text-xs text-muted-foreground font-normal">
                      {user?.email}
                    </span>
                  </div>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                {settingsNavigation.map((item) => (
                  <DropdownMenuItem
                    key={item.href}
                    onClick={() => router.push(item.href)}
                  >
                    <item.icon className="mr-2 h-4 w-4" />
                    {item.name}
                  </DropdownMenuItem>
                ))}
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  onClick={handleLogout}
                  className="text-destructive focus:text-destructive"
                >
                  <LogOut className="mr-2 h-4 w-4" />
                  退出登录
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>

        {/* Page content */}
        <main>{children}</main>
      </div>
    </div>
  );
}

function SidebarContent({
  pathname,
  spaces,
  spacesLoading,
  pendingReviewsCount,
  onNavigate,
}: {
  pathname: string;
  spaces: any;
  spacesLoading: boolean;
  pendingReviewsCount: number;
  onNavigate?: () => void;
}) {
  const router = useRouter();

  return (
    <nav className="flex-1 p-2 space-y-1">
      {navigation.map((item) => {
        const isActive =
          item.href === '/'
            ? pathname === '/'
            : pathname?.startsWith(item.href);
        return (
          <Link
            key={item.href}
            href={item.href}
            onClick={onNavigate}
            className={cn(
              'flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium transition-colors',
              isActive
                ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                : 'text-sidebar-foreground hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground'
            )}
          >
            <item.icon className="h-5 w-5" />
            <span className="flex-1">{item.name}</span>
            {item.name === '审阅中心' && pendingReviewsCount > 0 && (
              <Badge className="h-5 px-1.5 text-xs">
                {pendingReviewsCount}
              </Badge>
            )}
          </Link>
        );
      })}

      <div className="mt-6">
        <div className="px-3 mb-2">
          <p className="text-xs font-semibold text-sidebar-foreground/60 uppercase tracking-wider">
            我的空间
          </p>
        </div>
        {spacesLoading ? (
          <div className="space-y-1 px-3">
            {[1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-8 w-full rounded-md" />
            ))}
          </div>
        ) : (
          <div className="space-y-0.5">
            {spaces?.slice(0, 8).map((space: any) => {
              const isActive = pathname?.includes(`/spaces/${space.id}`);
              return (
                <Link
                  key={space.id}
                  href={`/spaces/${space.id}`}
                  onClick={onNavigate}
                  className={cn(
                    'flex items-center gap-3 px-3 py-2 rounded-md text-sm transition-colors group',
                    isActive
                      ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                      : 'text-sidebar-foreground hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground'
                  )}
                >
                  <div
                    className="w-6 h-6 rounded flex items-center justify-center text-white text-xs font-medium shrink-0"
                    style={{ backgroundColor: space.color || '#6366f1' }}
                  >
                    {space.icon || space.name.charAt(0).toUpperCase()}
                  </div>
                  <span className="flex-1 truncate">{space.name}</span>
                  <ChevronRight className="h-4 w-4 opacity-0 group-hover:opacity-100 transition-opacity" />
                </Link>
              );
            })}
            {spaces && spaces.length > 8 && (
              <button
                onClick={() => {
                  router.push('/spaces');
                  onNavigate?.();
                }}
                className="w-full flex items-center gap-3 px-3 py-2 rounded-md text-sm text-muted-foreground hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground transition-colors"
              >
                <span className="w-6 h-6 rounded flex items-center justify-center bg-muted text-xs">
                  +{spaces.length - 8}
                </span>
                <span>查看全部空间</span>
              </button>
            )}
          </div>
        )}
      </div>
    </nav>
  );
}

function getPageTitle(pathname: string | null): string {
  if (!pathname) return 'Knowledge Hub';

  if (pathname === '/') return '首页';
  if (pathname === '/spaces') return '我的空间';
  if (pathname.startsWith('/spaces/') && pathname.includes('/documents/')) {
    return '文档详情';
  }
  if (pathname.startsWith('/spaces/') && pathname.includes('/settings')) {
    return '空间设置';
  }
  if (pathname.startsWith('/spaces/') && pathname.includes('/members')) {
    return '成员管理';
  }
  if (pathname.startsWith('/spaces/') && pathname.includes('/documents')) {
    return '文档列表';
  }
  if (pathname.startsWith('/spaces/')) return '空间详情';
  if (pathname === '/search') return '全局搜索';
  if (pathname.startsWith('/reviews/')) return '审阅详情';
  if (pathname === '/reviews') return '审阅中心';
  if (pathname === '/settings/sync') return '同步配置';
  if (pathname === '/settings/profile') return '个人资料';
  if (pathname === '/settings/preferences') return '偏好设置';
  if (pathname.startsWith('/share/')) return '分享内容';
  if (pathname === '/login') return '登录';
  if (pathname === '/register') return '注册';

  return 'Knowledge Hub';
}
