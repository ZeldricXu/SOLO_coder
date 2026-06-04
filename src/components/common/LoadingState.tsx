'use client';

import { cn } from '@/lib/utils';
import { Spinner } from '@/components/ui/spinner';

interface LoadingStateProps extends React.HTMLAttributes<HTMLDivElement> {
  text?: string;
  size?: 'sm' | 'md' | 'lg';
}

function LoadingState({
  text = '加载中...',
  size = 'md',
  className,
  ...props
}: LoadingStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center p-8',
        className
      )}
      {...props}
    >
      <Spinner size={size} className="text-primary" />
      {text && (
        <p className="mt-4 text-sm text-muted-foreground">{text}</p>
      )}
    </div>
  );
}

export { LoadingState };
