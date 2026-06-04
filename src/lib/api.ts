import { httpBatchLink, loggerLink } from '@trpc/client';
import { createTRPCNext } from '@trpc/next';
import { createTRPCReact } from '@trpc/react-query';
import { callProcedure, TRPCError } from '@trpc/server';
import { observable } from '@trpc/server/observable';
import superjson from 'superjson';
import type { AppRouter } from '@/server/routers/_app';
import { createTRPCContext } from '@/server/trpc';
import { headers as nextHeaders } from 'next/headers';
import { cache } from 'react';
import { cookies } from 'next/headers';

const getBaseUrl = () => {
  if (typeof window !== 'undefined') return '';
  if (process.env.VERCEL_URL) return `https://${process.env.VERCEL_URL}`;
  return `http://localhost:${process.env.PORT ?? 3000}`;
};

export const api = createTRPCNext<AppRouter>({
  config({ ctx }) {
    return {
      transformer: superjson,
      links: [
        loggerLink({
          enabled: (opts) =>
            process.env.NODE_ENV === 'development' ||
            (opts.direction === 'down' && opts.result instanceof Error),
        }),
        httpBatchLink({
          url: `${getBaseUrl()}/api/trpc`,
          headers() {
            if (ctx?.req) {
              return {
                cookie: ctx.req.headers.cookie,
                'x-ssr': '1',
              };
            }
            return {};
          },
        }),
      ],
      queryClientConfig: {
        defaultOptions: {
          queries: {
            staleTime: 30 * 1000,
            refetchOnWindowFocus: false,
          },
        },
      },
    };
  },
  ssr: true,
  responseMeta({ clientErrors }) {
    if (clientErrors.length) {
      return {
        status: clientErrors[0].data?.httpStatus ?? 500,
      };
    }
    return {};
  },
});

export const trpcReact = createTRPCReact<AppRouter>();

export const trpcClient = trpcReact.createClient({
  links: [
    loggerLink({
      enabled: (opts) =>
        process.env.NODE_ENV === 'development' ||
        (opts.direction === 'down' && opts.result instanceof Error),
    }),
    httpBatchLink({
      url: `${getBaseUrl()}/api/trpc`,
      transformer: superjson,
    }),
  ],
});

export const serverClient = trpcReact.createClient({
  links: [
    loggerLink({
      enabled: (opts) =>
        process.env.NODE_ENV === 'development' ||
        (opts.direction === 'down' && opts.result instanceof Error),
    }),
    httpBatchLink({
      url: `${getBaseUrl()}/api/trpc`,
      transformer: superjson,
    }),
  ],
});

const createContext = cache(async () => {
  const cookieStore = cookies();
  const authToken = cookieStore.get('auth-token')?.value ?? '';

  const headers = new Headers();
  headers.set('cookie', `auth-token=${authToken}`);

  return createTRPCContext({
    req: new Request(`${getBaseUrl()}/api/trpc`, {
      headers,
    }),
    resHeaders: new Headers(),
  });
});

export const serverCaller = {
  auth: {
    getCurrentUser: cache(async () => {
      const ctx = await createContext();
      if (!ctx.user) {
        return null;
      }
      return ctx.user;
    }),
  },
  async query(path: string, input?: unknown) {
    const ctx = await createContext();
    const appRouter = (await import('@/server/routers/_app')).appRouter;
    
    try {
      const result = await callProcedure({
        procedures: appRouter._def.procedures,
        path,
        rawInput: input,
        ctx,
        type: 'query',
      });
      return result;
    } catch (error) {
      if (error instanceof TRPCError) {
        throw error;
      }
      throw new TRPCError({
        code: 'INTERNAL_SERVER_ERROR',
        message: 'An unexpected error occurred',
      });
    }
  },
  async mutation(path: string, input?: unknown) {
    const ctx = await createContext();
    const appRouter = (await import('@/server/routers/_app')).appRouter;
    
    try {
      const result = await callProcedure({
        procedures: appRouter._def.procedures,
        path,
        rawInput: input,
        ctx,
        type: 'mutation',
      });
      return result;
    } catch (error) {
      if (error instanceof TRPCError) {
        throw error;
      }
      throw new TRPCError({
        code: 'INTERNAL_SERVER_ERROR',
        message: 'An unexpected error occurred',
      });
    }
  },
};

export type RouterInputs = {
  [K in keyof AppRouter['_def']['procedures']]: AppRouter['_def']['procedures'][K] extends {
    _def: { inputs: infer I };
  }
    ? I
    : never;
};

export type RouterOutputs = {
  [K in keyof AppRouter['_def']['procedures']]: AppRouter['_def']['procedures'][K] extends {
    _def: { outputs: infer O };
  }
    ? O
    : never;
};
