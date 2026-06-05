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

export async function connectDatabase(): Promise<void> {
  try {
    await prisma.$connect();
    logger.info('Database connected successfully');
  } catch (error) {
    logger.error({ error }, 'Failed to connect to database');
    throw error;
  }
}

export async function disconnectDatabase(): Promise<void> {
  try {
    await prisma.$disconnect();
    logger.info('Database disconnected successfully');
  } catch (error) {
    logger.error({ error }, 'Failed to disconnect from database');
  }
}

export { prisma };
