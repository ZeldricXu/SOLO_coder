'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { ChevronRight, type LucideIcon } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';

interface SidebarItem {
  title: string;
  href?: string;
  icon?: LucideIcon;
  children?: SidebarItem[];
}

interface SidebarProps extends React.HTMLAttributes<HTMLDivElement> {
  items: SidebarItem[];
}

function Sidebar({ items, className, ...props }: SidebarProps) {
  const pathname = usePathname();

  return (
    <div
      className={cn(
        'flex h-full w-64 flex-col border-r bg-sidebar p-4',
        className
      )}
      {...props}
    >
      <nav className="flex-1 space-y-1">
        {items.map((item) => (
          <SidebarNavItem
            key={item.title}
            item={item}
            pathname={pathname}
          />
        ))}
      </nav>
    </div>
  );
}

function SidebarNavItem({
  item,
  pathname,
  depth = 0,
}: {
  item: SidebarItem;
  pathname: string;
  depth?: number;
}) {
  const [isOpen, setIsOpen] = React.useState(false);

  if (item.children) {
    return (
      <Collapsible open={isOpen} onOpenChange={setIsOpen}>
        <CollapsibleTrigger asChild>
          <Button
            variant="ghost"
            className={cn(
              'w-full justify-between text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
              depth > 0 && 'pl-8'
            )}
          >
            <div className="flex items-center gap-2">
              {item.icon && <item.icon className="h-4 w-4" />}
              <span>{item.title}</span>
            </div>
            <ChevronRight
              className={cn(
                'h-4 w-4 transition-transform',
                isOpen && 'rotate-90'
              )}
            />
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          {item.children.map((child) => (
            <SidebarNavItem
              key={child.title}
              item={child}
              pathname={pathname}
              depth={depth + 1}
            />
          ))}
        </CollapsibleContent>
      </Collapsible>
    );
  }

  const isActive = item.href ? pathname === item.href : false;

  return (
    <Button
      asChild
      variant="ghost"
      className={cn(
        'w-full justify-start text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
        depth > 0 && 'pl-8',
        isActive &&
          'bg-sidebar-accent text-sidebar-accent-foreground font-medium'
      )}
    >
      <Link href={item.href || '#'}>
        {item.icon && <item.icon className="mr-2 h-4 w-4" />}
        {item.title}
      </Link>
    </Button>
  );
}

export { Sidebar, type SidebarItem };
