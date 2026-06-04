'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  ChevronDown,
  ChevronRight,
  FileText,
  Folder,
  Plus,
  Search,
  Settings,
  Users,
  Home,
  BookOpen,
  Clock,
  Trash2,
} from 'lucide-react';
import { useState } from 'react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { trpc } from '@/app/providers/TRPCProvider';
import type { SpaceWithOwner } from '@/lib/types/space';

interface DocumentTreeItem {
  id: string;
  title: string;
  children?: DocumentTreeItem[];
  isFolder?: boolean;
  updatedAt?: Date;
}

interface SpaceLayoutSidebarProps {
  space: SpaceWithOwner;
  spaceId: string;
}

export function SpaceLayoutSidebar({ space, spaceId }: SpaceLayoutSidebarProps) {
  const pathname = usePathname();
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedFolders, setExpandedFolders] = useState<Set<string>>(
    new Set(['root'])
  );

  const { data: documents } = trpc.document.list.useQuery(
    { spaceId, pageSize: 100 },
    { enabled: !!spaceId }
  );

  const toggleFolder = (folderId: string) => {
    setExpandedFolders((prev) => {
      const next = new Set(prev);
      if (next.has(folderId)) {
        next.delete(folderId);
      } else {
        next.add(folderId);
      }
      return next;
    });
  };

  const navItems = [
    {
      label: '首页',
      href: '/',
      icon: Home,
    },
    {
      label: '空间概览',
      href: `/spaces/${spaceId}`,
      icon: BookOpen,
      exact: true,
    },
    {
      label: '文档',
      href: `/spaces/${spaceId}/documents`,
      icon: FileText,
    },
    {
      label: '最近更新',
      href: `/spaces/${spaceId}?filter=recent`,
      icon: Clock,
    },
    {
      label: '回收站',
      href: `/spaces/${spaceId}?filter=trash`,
      icon: Trash2,
    },
  ];

  const filteredDocs = documents?.items.filter((doc) =>
    doc.title?.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const renderDocumentTree = (items: DocumentTreeItem[], level = 0) => {
    return items.map((item) => (
      <div key={item.id}>
        {item.isFolder ? (
          <div>
            <button
              onClick={() => toggleFolder(item.id)}
              className="w-full flex items-center gap-2 px-3 py-2 text-sm rounded-md hover:bg-muted transition-colors text-left"
              style={{ paddingLeft: `${level * 12 + 12}px` }}
            >
              {expandedFolders.has(item.id) ? (
                <ChevronDown className="h-4 w-4 text-muted-foreground" />
              ) : (
                <ChevronRight className="h-4 w-4 text-muted-foreground" />
              )}
              <Folder className="h-4 w-4 text-yellow-500" />
              <span className="truncate">{item.title}</span>
            </button>
            {expandedFolders.has(item.id) && item.children && (
              <div>{renderDocumentTree(item.children, level + 1)}</div>
            )}
          </div>
        ) : (
          <Link
            href={`/spaces/${spaceId}/documents/${item.id}`}
            className={cn(
              'flex items-center gap-2 px-3 py-2 text-sm rounded-md hover:bg-muted transition-colors',
              pathname === `/spaces/${spaceId}/documents/${item.id}` &&
                'bg-muted font-medium',
              level > 0 && `ml-${level * 3}`
            )}
            style={{ paddingLeft: `${level * 12 + 12}px` }}
          >
            <FileText className="h-4 w-4 text-muted-foreground" />
            <span className="truncate">{item.title || '无标题'}</span>
          </Link>
        )}
      </div>
    ));
  };

  const treeItems: DocumentTreeItem[] =
    filteredDocs?.map((doc) => ({
      id: doc.id,
      title: doc.title || '无标题文档',
      updatedAt: doc.updatedAt,
    })) || [];

  return (
    <aside className="w-72 border-r bg-sidebar flex flex-col h-screen sticky top-0">
      <div className="p-4 border-b">
        <Link
          href={`/spaces/${spaceId}`}
          className="flex items-center gap-3 mb-4"
        >
          <div
            className="w-10 h-10 rounded-lg flex items-center justify-center text-white font-bold"
            style={{ backgroundColor: space.color || '#6366f1' }}
          >
            {space.icon || space.name.charAt(0).toUpperCase()}
          </div>
          <div className="flex-1 min-w-0">
            <h2 className="font-semibold truncate">{space.name}</h2>
            <p className="text-xs text-muted-foreground truncate">
              {space.description || '暂无描述'}
            </p>
          </div>
        </Link>

        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="搜索文档..."
            className="pl-9 h-9 text-sm"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
      </div>

      <nav className="flex-1 overflow-y-auto p-2 space-y-1">
        <div className="px-2 py-1.5">
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1">
            导航
          </p>
          {navItems.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                'flex items-center gap-3 px-3 py-2 text-sm rounded-md hover:bg-muted transition-colors',
                (item.exact
                  ? pathname === item.href
                  : pathname?.startsWith(item.href)) &&
                  'bg-muted font-medium'
              )}
            >
              <item.icon className="h-4 w-4 text-muted-foreground" />
              <span>{item.label}</span>
            </Link>
          ))}
        </div>

        <div className="px-2 py-1.5">
          <div className="flex items-center justify-between mb-1">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              文档
            </p>
            <Button
              variant="ghost"
              size="icon"
              className="h-6 w-6"
              onClick={() => {}}
            >
              <Plus className="h-3.5 w-3.5" />
            </Button>
          </div>
          <div className="space-y-0.5">
            {treeItems.length > 0 ? (
              renderDocumentTree(treeItems)
            ) : (
              <div className="px-3 py-4 text-center">
                <p className="text-sm text-muted-foreground">暂无文档</p>
                <Button
                  variant="ghost"
                  size="sm"
                  className="mt-2 h-8 text-xs"
                  onClick={() => {}}
                >
                  <Plus className="h-3.5 w-3.5 mr-1" />
                  创建第一个文档
                </Button>
              </div>
            )}
          </div>
        </div>
      </nav>

      <div className="border-t p-2">
        <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider px-3 mb-1">
          设置
        </p>
        <Link
          href={`/spaces/${spaceId}/settings`}
          className={cn(
            'flex items-center gap-3 px-3 py-2 text-sm rounded-md hover:bg-muted transition-colors',
            pathname?.startsWith(`/spaces/${spaceId}/settings`) &&
              'bg-muted font-medium'
          )}
        >
          <Settings className="h-4 w-4 text-muted-foreground" />
          <span>空间设置</span>
        </Link>
        <Link
          href={`/spaces/${spaceId}/members`}
          className={cn(
            'flex items-center gap-3 px-3 py-2 text-sm rounded-md hover:bg-muted transition-colors',
            pathname?.startsWith(`/spaces/${spaceId}/members`) &&
              'bg-muted font-medium'
          )}
        >
          <Users className="h-4 w-4 text-muted-foreground" />
          <span>成员管理</span>
        </Link>
      </div>
    </aside>
  );
}
