'use client';

import * as React from 'react';
import {
  QueryClient,
  QueryClientProvider,
  type QueryClientConfig,
} from '@tanstack/react-query';

interface QueryProviderProps {
  children: React.ReactNode;
  config?: QueryClientConfig;
}

const defaultConfig: QueryClientConfig = {
  defaultOptions: {
    queries: {
      staleTime: 60 * 1000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        if (error instanceof Error) {
          if (error.message.includes('401') || error.message.includes('403')) {
            return false;
          }
        }
        return failureCount < 3;
      },
    },
    mutations: {
      retry: false,
    },
  },
};

function QueryProvider({ children, config }: QueryProviderProps) {
  const [queryClient] = React.useState(
    () => new QueryClient({ ...defaultConfig, ...config })
  );

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

export { QueryProvider };
