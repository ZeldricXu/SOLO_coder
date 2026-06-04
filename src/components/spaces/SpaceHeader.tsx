'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  Search,
  Bell,
  User,
  LogOut,
  Settings,
  ChevronDown,
  Plus,
  Menu,
  Moon,
  Sun,
} from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { trpc } from '@/app/providers/TRPCProvider';
import { useToast } from '@/app/providers/ToastProvider';
import { useTheme } from '@/app/providers/ThemeProvider';
import { Skeleton } from '@/components/ui/skeleton';

interface SpaceHeaderProps {
  spaceId: string;
  spaceName?: string;
}

export function SpaceHeader({ spaceId, spaceName }: SpaceHeaderProps) {
  const router = useRouter();
  const { toast } = useToast();
  const { theme, setTheme } = useTheme();
  const [searchQuery, setSearchQuery] = useState('');
  const [sidebarOpen, setSidebarOpen] = useState(true);

  const { data: user, isLoading: userLoading } =
    trpc.auth.getCurrentUser.useQuery();

  const logoutMutation = trpc.auth.logout.useMutation({
    onSuccess: () => {
      toast({
        title: '已退出登录',
        description: '期待您的再次访问',
      });
      router.push('/login');
      router.refresh();
    },
    onError: (error) => {
      toast({
        title: '退出失败',
        description: error.message,
        variant: 'destructive',
      });
    },
  });

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (searchQuery.trim()) {
      router.push(
        `/search?q=${encodeURIComponent(searchQuery)}&spaceId=${spaceId}`
      );
    }
  };

  const handleNewDocument = () => {
    router.push(`/spaces/${spaceId}/documents/new`);
  };

  const handleLogout = () => {
    logoutMutation.mutate();
  };

  return (
    <header className="h-14 border-b bg-background flex items-center justify-between px-4 sticky top-0 z-40">
      <div className="flex items-center gap-4">
        <Button
          variant="ghost"
          size="icon"
          onClick={() => setSidebarOpen(!sidebarOpen)}
          className="lg:hidden"
        >
          <Menu className="h-5 w-5" />
        </Button>
        <Link href="/" className="flex items-center gap-2">
          <span className="font-bold text-lg hidden sm:inline">
            {spaceName || 'Knowledge Hub'}
          </span>
        </Link>
      </div>

      <div className="flex-1 max-w-xl mx-4">
        <form onSubmit={handleSearch}>
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="搜索此空间内的文档..."
              className="pl-10 h-9"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </form>
      </div>

      <div className="flex items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
        >
          {theme === 'dark' ? (
            <Sun className="h-5 w-5" />
          ) : (
            <Moon className="h-5 w-5" />
          )}
        </Button>

        <Button
          onClick={handleNewDocument}
          size="sm"
          className="hidden sm:inline-flex"
        >
          <Plus className="h-4 w-4 mr-1" />
          新建文档
        </Button>

        <Button variant="ghost" size="icon">
          <Bell className="h-5 w-5" />
        </Button>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" className="gap-2 h-9 px-2">
              {userLoading ? (
                <Skeleton className="h-8 w-8 rounded-full" />
              ) : (
                <Avatar className="h-8 w-8">
                  <AvatarImage src={user?.avatar || ''} alt={user?.name} />
                  <AvatarFallback>
                    {user?.name?.charAt(0) || 'U'}
                  </AvatarFallback>
                </Avatar>
              )}
              <ChevronDown className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            {user && (
              <>
                <DropdownMenuLabel>
                  <div className="flex flex-col">
                    <span className="font-medium">{user.name}</span>
                    <span className="text-xs text-muted-foreground">
                      {user.email}
                    </span>
                  </div>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  onClick={() => router.push('/settings/profile')}
                >
                  <User className="mr-2 h-4 w-4" />
                  个人资料
                </DropdownMenuItem>
                <DropdownMenuItem
                  onClick={() => router.push('/settings/preferences')}
                >
                  <Settings className="mr-2 h-4 w-4" />
                  偏好设置
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  onClick={handleLogout}
                  className="text-destructive focus:text-destructive"
                >
                  <LogOut className="mr-2 h-4 w-4" />
                  退出登录
                </DropdownMenuItem>
              </>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  );
}
