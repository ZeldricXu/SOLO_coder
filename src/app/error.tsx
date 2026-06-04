'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { AlertTriangle, Home, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('Application error:', error);
  }, [error]);

  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="container mx-auto px-4 text-center">
        <div className="max-w-md mx-auto">
          <div className="mb-8">
            <div className="inline-flex items-center justify-center w-24 h-24 rounded-full bg-destructive/10 mb-6">
              <AlertTriangle className="w-12 h-12 text-destructive" />
            </div>
            <h1 className="text-3xl font-bold mb-4">出错了</h1>
            <p className="text-muted-foreground mb-4">
              应用程序遇到了意外错误。请尝试刷新页面或返回首页。
            </p>
            {process.env.NODE_ENV === 'development' && (
              <div className="bg-muted rounded-lg p-4 mb-6 text-left">
                <p className="text-sm font-mono text-muted-foreground mb-2">
                  错误信息:
                </p>
                <p className="text-sm font-mono break-all">
                  {error.message || '未知错误'}
                </p>
                {error.digest && (
                  <p className="text-xs text-muted-foreground mt-2">
                    错误ID: {error.digest}
                  </p>
                )}
              </div>
            )}
          </div>
          <div className="flex flex-col sm:flex-row gap-4 justify-center">
            <Button onClick={reset}>
              <RefreshCw className="mr-2 h-4 w-4" />
              重试
            </Button>
            <Button variant="outline" asChild>
              <Link href="/">
                <Home className="mr-2 h-4 w-4" />
                返回首页
              </Link>
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
