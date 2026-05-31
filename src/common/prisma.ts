import { PrismaClient } from '@prisma/client';
import { getConfig } from './config';

let prisma: PrismaClient | null = null;

export const createPrismaClient = (): PrismaClient => {
  const config = getConfig();
  
  prisma = new PrismaClient({
    log: config.env === 'development' ? ['query', 'error', 'warn'] : ['error'],
    transactionOptions: {
      maxWait: 5000,
      timeout: 10000
    }
  });

  return prisma;
};

export const getPrismaClient = (): PrismaClient => {
  if (!prisma) {
    return createPrismaClient();
  }
  return prisma;
};

export const disconnectPrisma = async (): Promise<void> => {
  if (prisma) {
    await prisma.$disconnect();
    prisma = null;
  }
};

export const withTransaction = async <T>(
  fn: (tx: PrismaClient) => Promise<T>,
  maxRetries: number = 3
): Promise<T> => {
  const prisma = getPrismaClient();
  let lastError: Error | null = null;

  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      return await prisma.$transaction(fn);
    } catch (err) {
      lastError = err instanceof Error ? err : new Error(String(err));
      if (attempt < maxRetries - 1) {
        await new Promise(resolve => setTimeout(resolve, Math.pow(2, attempt) * 100));
      }
    }
  }

  throw lastError || new Error('Transaction failed after max retries');
};

export const tenantFilter = (tenantId: string) => ({ tenantId });

export const executeWithRetry = async <T>(
  fn: () => Promise<T>,
  maxRetries: number = 3,
  retryDelay: number = 100
): Promise<T> => {
  let lastError: Error | null = null;

  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastError = err instanceof Error ? err : new Error(String(err));
      if (attempt < maxRetries - 1) {
        await new Promise(resolve => setTimeout(resolve, retryDelay * Math.pow(2, attempt)));
      }
    }
  }

  throw lastError || new Error('Operation failed after max retries');
};
