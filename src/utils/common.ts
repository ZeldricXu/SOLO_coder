import { v4 as uuidv4 } from 'uuid';
import winston from 'winston';

export const generateId = (prefix: string = ''): string => {
  return `${prefix}${uuidv4().replace(/-/g, '').slice(0, 12)}`;
};

export const currentDateTime = (): string => {
  return new Date().toISOString();
};

const customLevels = {
  ...winston.config.npm.levels,
  http: 6,
};

export const logger = winston.createLogger({
  level: process.env.LOG_LEVEL || 'info',
  levels: customLevels,
  format: winston.format.combine(
    winston.format.timestamp(),
    winston.format.json()
  ),
  transports: [
    new winston.transports.Console(),
  ],
});

(logger as any).http = function (message: string, meta?: Record<string, unknown>) {
  logger.log('http', message, meta);
};

export const delay = (ms: number): Promise<void> => {
  return new Promise(resolve => setTimeout(resolve, ms));
};

export const retry = async <T>(
  fn: () => Promise<T>,
  retries: number = 3,
  delayMs: number = 1000
): Promise<T> => {
  try {
    return await fn();
  } catch (error) {
    if (retries <= 0) throw error;
    await delay(delayMs);
    return retry(fn, retries - 1, delayMs * 2);
  }
};

export const withTimeout = async <T>(
  promise: Promise<T>,
  timeoutMs: number,
  errorMessage: string = 'Operation timed out'
): Promise<T> => {
  let timeoutId: NodeJS.Timeout;
  const timeoutPromise = new Promise<T>((_, reject) => {
    timeoutId = setTimeout(() => {
      reject(new Error(errorMessage));
    }, timeoutMs);
  });

  const result = await Promise.race([promise, timeoutPromise]);
  clearTimeout(timeoutId!);
  return result;
};
