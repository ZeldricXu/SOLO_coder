import { getConfig } from '../../../config/AppConfig';

let prismaInstance: any = null;

export const createPrismaClient = (): any => {
  const config = getConfig();

  prismaInstance = {
    $connect: async () => {},
    $disconnect: async () => {},
    $transaction: async (fn: (tx: any) => Promise<any>) => {
      const tx = prismaInstance;
      return await fn(tx);
    },
    ticket: {
      findUnique: async () => null,
      findMany: async () => [],
      count: async () => 0,
      create: async (data: any) => ({ ...data.data, id: crypto.randomUUID(), createdAt: new Date(), updatedAt: new Date() }),
      update: async (data: any) => ({ ...data.data, updatedAt: new Date() }),
      delete: async () => {}
    },
    ticketSkillRequirement: {
      findUnique: async () => null,
      findMany: async () => [],
      create: async (data: any) => ({ ...data.data, id: crypto.randomUUID() }),
      deleteMany: async () => ({ count: 0 })
    },
    agent: {
      findUnique: async () => null,
      findMany: async () => [],
      count: async () => 0,
      create: async (data: any) => ({ ...data.data, id: crypto.randomUUID(), createdAt: new Date(), updatedAt: new Date() }),
      update: async (data: any) => ({ ...data.data, updatedAt: new Date() }),
      delete: async () => {}
    },
    skillAssessment: {
      findUnique: async () => null,
      findMany: async () => [],
      create: async (data: any) => ({ ...data.data, id: crypto.randomUUID() }),
      deleteMany: async () => ({ count: 0 })
    },
    tenant: {
      findUnique: async () => null,
      findMany: async () => [],
      count: async () => 0,
      create: async (data: any) => ({ ...data.data, id: crypto.randomUUID(), createdAt: new Date(), updatedAt: new Date() }),
      update: async (data: any) => ({ ...data.data, updatedAt: new Date() }),
      delete: async () => {}
    }
  };

  return prismaInstance;
};

export const getPrismaClient = (): any => {
  if (!prismaInstance) {
    return createPrismaClient();
  }
  return prismaInstance;
};

export const disconnectPrisma = async (): Promise<void> => {
  if (prismaInstance) {
    await prismaInstance.$disconnect();
    prismaInstance = null;
  }
};

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
