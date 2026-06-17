import { PrismaClient } from '@prisma/client';
import { env } from './env';
import { logger } from './logger';

const prisma = new PrismaClient({
  log: [
    { emit: 'event', level: 'query' },
    { emit: 'event', level: 'error' },
    { emit: 'event', level: 'warn' },
  ],
});

prisma.$on('query', (e) => {
  if (env.NODE_ENV === 'development') {
    logger.debug({ duration: e.duration + 'ms', query: e.query }, 'Database query');
  }
});

prisma.$on('error', (e) => {
  logger.error({ error: e.message, target: e.target }, 'Database error');
});

prisma.$on('warn', (e) => {
  logger.warn({ message: e.message, target: e.target }, 'Database warning');
});

let prismaReplica: PrismaClient | undefined;

if (env.DATABASE_READ_REPLICA_URL) {
  prismaReplica = new PrismaClient({
    datasources: {
      db: {
        url: env.DATABASE_READ_REPLICA_URL,
      },
    },
    log: [
      { emit: 'event', level: 'query' },
      { emit: 'event', level: 'error' },
      { emit: 'event', level: 'warn' },
    ],
  });

  prismaReplica.$on('query', (e) => {
    if (env.NODE_ENV === 'development') {
      logger.debug({ duration: e.duration + 'ms', query: e.query }, 'Read replica database query');
    }
  });

  prismaReplica.$on('error', (e) => {
    logger.error({ error: e.message, target: e.target }, 'Read replica database error');
  });

  prismaReplica.$on('warn', (e) => {
    logger.warn({ message: e.message, target: e.target }, 'Read replica database warning');
  });
}

export function getReadPrisma(): PrismaClient {
  return prismaReplica || prisma;
}

export async function connectDatabase(): Promise<void> {
  try {
    await prisma.$connect();
    logger.info('Database connected successfully');

    if (prismaReplica) {
      await prismaReplica.$connect();
      logger.info('Read replica database connected successfully');
    }
  } catch (error) {
    logger.error({ error }, 'Failed to connect to database');
    throw error;
  }
}

export async function disconnectDatabase(): Promise<void> {
  try {
    await prisma.$disconnect();
    logger.info('Database disconnected successfully');

    if (prismaReplica) {
      await prismaReplica.$disconnect();
      logger.info('Read replica database disconnected successfully');
    }
  } catch (error) {
    logger.error({ error }, 'Failed to disconnect from database');
  }
}

export { prisma, prismaReplica };
