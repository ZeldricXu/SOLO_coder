import { v4 as uuidv4 } from 'uuid';

export const generateId = (prefix: string = ''): string => {
  return `${prefix}${uuidv4().replace(/-/g, '').slice(0, 24)}`;
};

export const generateTraceId = (): string => {
  return uuidv4().replace(/-/g, '');
};

export const nowISO = (): string => {
  return new Date().toISOString();
};

export const nowEpoch = (): number => {
  return Date.now();
};

export const sleep = (ms: number): Promise<void> => {
  return new Promise((resolve) => setTimeout(resolve, ms));
};

export const retryAsync = async <T>(
  fn: () => Promise<T>,
  maxRetries: number,
  delayMs: number,
  shouldRetry?: (error: Error) => boolean
): Promise<T> => {
  let lastError: Error;
  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error as Error;
      if (shouldRetry && !shouldRetry(lastError)) {
        throw lastError;
      }
      if (attempt < maxRetries - 1) {
        await sleep(delayMs * Math.pow(2, attempt));
      }
    }
  }
  throw lastError!;
};

export const safeJsonParse = <T>(str: string, fallback: T): T => {
  try {
    return JSON.parse(str) as T;
  } catch {
    return fallback;
  }
};

export const safeJsonStringify = (obj: unknown, space?: number): string => {
  try {
    return JSON.stringify(obj, null, space);
  } catch {
    return '{}';
  }
};

export const deepClone = <T>(obj: T): T => {
  return JSON.parse(JSON.stringify(obj)) as T;
};

export const chunkArray = <T>(arr: T[], size: number): T[][] => {
  const chunks: T[][] = [];
  for (let i = 0; i < arr.length; i += size) {
    chunks.push(arr.slice(i, i + size));
  }
  return chunks;
};

export const objectToQueryString = (params: Record<string, string | number | boolean>): string => {
  return Object.entries(params)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
};

export const validateEmail = (email: string): boolean => {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return emailRegex.test(email);
};

export const calculateP99 = (values: number[]): number => {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.ceil(0.99 * sorted.length) - 1;
  return sorted[Math.max(0, index)];
};

export const calculateP95 = (values: number[]): number => {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.ceil(0.95 * sorted.length) - 1;
  return sorted[Math.max(0, index)];
};

export const calculateAvg = (values: number[]): number => {
  if (values.length === 0) return 0;
  return values.reduce((sum, val) => sum + val, 0) / values.length;
};

export const formatBytes = (bytes: number): string => {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = bytes;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }
  return `${size.toFixed(2)} ${units[unitIndex]}`;
};

export const getCallerInfo = (): { file: string; line: number; function: string } => {
  const err = new Error();
  const stack = err.stack?.split('\n') || [];
  const callerLine = stack[3] || '';
  const match = callerLine.match(/at (.+) \((.+):(\d+):(\d+)\)/) || callerLine.match(/at (.+):(\d+):(\d+)/);
  if (match) {
    return {
      function: match[1] || 'anonymous',
      file: match[2] || 'unknown',
      line: parseInt(match[3], 10) || 0,
    };
  }
  return { file: 'unknown', line: 0, function: 'anonymous' };
};
