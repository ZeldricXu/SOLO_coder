'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { LoadingState } from '@/components/common/LoadingState';

interface ProtectedRouteProps {
  children: React.ReactNode;
  isAuthenticated: boolean;
  isLoading?: boolean;
  redirectTo?: string;
  fallback?: React.ReactNode;
}

function ProtectedRoute({
  children,
  isAuthenticated,
  isLoading = false,
  redirectTo = '/login',
  fallback,
}: ProtectedRouteProps) {
  const router = useRouter();

  React.useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      router.push(redirectTo);
    }
  }, [isAuthenticated, isLoading, redirectTo, router]);

  if (isLoading) {
    return <LoadingState text="正在验证身份..." />;
  }

  if (!isAuthenticated) {
    return fallback || null;
  }

  return <>{children}</>;
}

export { ProtectedRoute };
