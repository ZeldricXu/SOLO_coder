'use client';

import * as React from 'react';
import { cn } from '@/lib/utils';

interface AppLayoutProps extends React.HTMLAttributes<HTMLDivElement> {
  sidebar?: React.ReactNode;
  header?: React.ReactNode;
  children: React.ReactNode;
}

function AppLayout({
  sidebar,
  header,
  children,
  className,
  ...props
}: AppLayoutProps) {
  return (
    <div className={cn('flex h-screen bg-background', className)} {...props}>
      {sidebar && <aside className="hidden md:block">{sidebar}</aside>}
      <div className="flex flex-1 flex-col overflow-hidden">
        {header && <header className="border-b">{header}</header>}
        <main className="flex-1 overflow-y-auto">
          <div className="container mx-auto p-4 md:p-6">{children}</div>
        </main>
      </div>
    </div>
  );
}

export { AppLayout };
