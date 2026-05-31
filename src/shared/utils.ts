import { v4 as uuidv4 } from 'uuid';

export const generateId = (prefix: string = 'id'): string => {
  return `${prefix}_${uuidv4().replace(/-/g, '').slice(0, 8)}`;
};

export const nowISO = (): string => {
  return new Date().toISOString();
};

export const sleep = (ms: number): Promise<void> => {
  return new Promise((resolve) => setTimeout(resolve, ms));
};

export const retry = async <T>(
  fn: () => Promise<T>,
  retries: number = 3,
  delay: number = 1000,
  backoff: number = 2
): Promise<T> => {
  let lastError: Error;
  for (let i = 0; i < retries; i++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error as Error;
      if (i < retries - 1) {
        await sleep(delay * Math.pow(backoff, i));
      }
    }
  }
  throw lastError!;
};

export const withTimeout = <T>(promise: Promise<T>, ms: number, message?: string): Promise<T> => {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error(message || `Operation timed out after ${ms}ms`));
    }, ms);
    promise
      .then((value) => {
        clearTimeout(timeout);
        resolve(value);
      })
      .catch((error) => {
        clearTimeout(timeout);
        reject(error);
      });
  });
};

export const calculatePercentiles = (values: number[], percentiles: number[]): Record<number, number> => {
  if (values.length === 0) {
    return percentiles.reduce((acc, p) => ({ ...acc, [p]: 0 }), {});
  }
  const sorted = [...values].sort((a, b) => a - b);
  const result: Record<number, number> = {};
  for (const p of percentiles) {
    const index = Math.ceil((p / 100) * sorted.length) - 1;
    result[p] = sorted[Math.max(0, Math.min(index, sorted.length - 1))];
  }
  return result;
};

export const safeJsonParse = <T>(str: string, fallback: T): T => {
  try {
    return JSON.parse(str) as T;
  } catch {
    return fallback;
  }
};

export const deepClone = <T>(obj: T): T => {
  return JSON.parse(JSON.stringify(obj));
};

export const mergeDeep = <T extends Record<string, unknown>>(target: T, source: Partial<T>): T => {
  const result = { ...target };
  for (const key in source) {
    if (source.hasOwnProperty(key)) {
      const targetValue = result[key];
      const sourceValue = source[key];
      if (
        typeof targetValue === 'object' &&
        targetValue !== null &&
        typeof sourceValue === 'object' &&
        sourceValue !== null &&
        !Array.isArray(targetValue) &&
        !Array.isArray(sourceValue)
      ) {
        (result as Record<string, unknown>)[key] = mergeDeep(
          targetValue as Record<string, unknown>,
          sourceValue as Record<string, unknown>
        );
      } else if (sourceValue !== undefined) {
        (result as Record<string, unknown>)[key] = sourceValue;
      }
    }
  }
  return result;
};

export const formatBytes = (bytes: number): string => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`;
};

export const calculateDrift = (baseline: number[], current: number[]): number => {
  if (baseline.length === 0 || current.length === 0) return 0;
  const mean1 = baseline.reduce((a, b) => a + b, 0) / baseline.length;
  const mean2 = current.reduce((a, b) => a + b, 0) / current.length;
  const std1 = Math.sqrt(baseline.reduce((a, b) => a + Math.pow(b - mean1, 2), 0) / baseline.length);
  const std2 = Math.sqrt(current.reduce((a, b) => a + Math.pow(b - mean2, 2), 0) / current.length);
  const drift = Math.abs(mean1 - mean2) / Math.sqrt((std1 * std1 + std2 * std2) / 2 + 1e-10);
  return isFinite(drift) ? drift : 0;
};
