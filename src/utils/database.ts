import { PrismaClient } from '@prisma/client';
import { config } from '../config';

let prisma: PrismaClient;

export const getPrismaClient = (): PrismaClient => {
  if (!prisma) {
    prisma = new PrismaClient({
      log: config.server.isDevelopment ? ['query', 'error', 'warn'] : ['error'],
    });
  }
  return prisma;
};

export const disconnectDatabase = async (): Promise<void> => {
  if (prisma) {
    await prisma.$disconnect();
  }
};

export default getPrismaClient;
