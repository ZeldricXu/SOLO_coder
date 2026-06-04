'use client';

import * as React from 'react';
import { FileText, Search, PlusCircle, type LucideIcon } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';

interface EmptyStateProps extends React.HTMLAttributes<HTMLDivElement> {
  icon?: React.ReactNode | keyof typeof iconMap;
  title: string;
  description?: string;
  action?: {
    label: string;
    onClick: () => void;
  };
}

const iconMap = {
  file: FileText,
  search: Search,
  plus: PlusCircle,
};

function EmptyState({
  icon,
  title,
  description,
  action,
  className,
  ...props
}: EmptyStateProps) {
  const IconComponent: LucideIcon | React.ReactNode =
    typeof icon === 'string'
      ? iconMap[icon as keyof typeof iconMap] || FileText
      : icon || FileText;

  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center rounded-lg border border-dashed p-8 text-center',
        className
      )}
      {...props}
    >
      <div className="flex h-20 w-20 items-center justify-center rounded-full bg-muted">
        {React.isValidElement(IconComponent) ? (
          IconComponent
        ) : typeof IconComponent === 'function' ? (
          <IconComponent className="h-10 w-10 text-muted-foreground" />
        ) : (
          <FileText className="h-10 w-10 text-muted-foreground" />
        )}
      </div>
      <h3 className="mt-6 text-lg font-semibold">{title}</h3>
      {description && (
        <p className="mt-2 max-w-sm text-sm text-muted-foreground">
          {description}
        </p>
      )}
      {action && (
        <Button className="mt-6" onClick={action.onClick}>
          {action.label}
        </Button>
      )}
    </div>
  );
}

export { EmptyState };
