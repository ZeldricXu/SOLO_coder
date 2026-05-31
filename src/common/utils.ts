import { v4 as uuidv4 } from 'uuid';
import retry from 'async-retry';
import { DEFAULT_RETRY_ATTEMPTS, DEFAULT_RETRY_DELAY } from '../config';
import { LoggerContext } from './logger';
import { ChainId } from '../types';

export function generateId(prefix: string = 'id'): string {
  return `${prefix}_${uuidv4().replace(/-/g, '').substring(0, 12)}`;
}

export function formatDate(date: Date = new Date()): string {
  return date.toISOString();
}

export function now(): string {
  return formatDate(new Date());
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function isAddress(address: string): boolean {
  return /^0x[a-fA-F0-9]{40}$/.test(address);
}

export function normalizeAddress(address: string): string {
  return address.toLowerCase();
}

export function asChainId(chainId: number): ChainId {
  return chainId as ChainId;
}

export function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

export function hexToNumber(hex: string): number {
  return parseInt(hex, 16);
}

export function numberToHex(num: number | string): string {
  return `0x${BigInt(num).toString(16)}`;
}

export function weiToEther(wei: string | bigint): string {
  const value = typeof wei === 'string' ? BigInt(wei) : wei;
  return (value / BigInt(10 ** 18)).toString();
}

export function etherToWei(ether: string): string {
  return (BigInt(parseFloat(ether) * 10 ** 18)).toString();
}

export async function withRetry<T>(
  fn: () => Promise<T>,
  options: {
    retries?: number;
    delay?: number;
    onRetry?: (error: unknown, attempt: number) => void;
  } = {}
): Promise<T> {
  const { retries = DEFAULT_RETRY_ATTEMPTS, delay = DEFAULT_RETRY_DELAY, onRetry } = options;
  return retry(
    async (bail, attempt) => {
      try {
        return await fn();
      } catch (error) {
        if (attempt > retries) {
          bail(error as Error);
        }
        if (onRetry) {
          onRetry(error as Error, attempt);
        }
        throw error;
      }
    },
    {
      retries,
      minTimeout: delay,
      factor: 2,
    }
  );
}

export async function withTimeout<T>(
  fn: () => Promise<T>,
  timeout: number,
  timeoutMessage: string = 'Operation timed out'
): Promise<T> {
  return Promise.race([
    fn(),
    new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error(timeoutMessage)), timeout)
    ),
  ]);
}

export function chunkArray<T>(array: T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let i = 0; i < array.length; i += size) {
    chunks.push(array.slice(i, i + size));
  }
  return chunks;
}

export function calculateMedian(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 !== 0 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

export function calculatePercentile(values: number[], percentile: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.ceil((percentile / 100) * sorted.length) - 1;
  return sorted[Math.max(0, index)];
}

export function deepClone<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj));
}

export function omit<T extends Record<string, unknown>, K extends keyof T>(
  obj: T,
  keys: K[]
): Omit<T, K> {
  const result = { ...obj };
  keys.forEach((key) => delete result[key]);
  return result;
}

export function pick<T extends Record<string, unknown>, K extends keyof T>(
  obj: T,
  keys: K[]
): Pick<T, K> {
  const result = {} as Pick<T, K>;
  keys.forEach((key) => {
    if (key in obj) {
      result[key] = obj[key];
    }
  });
  return result;
}

export class MetricsCollector {
  private metrics: Map<string, { values: number[]; timestamps: number[] }>;
  private logger: LoggerContext;

  constructor() {
    this.metrics = new Map();
    this.logger = new LoggerContext({ module: 'MetricsCollector' });
  }

  record(name: string, value: number): void {
    if (!this.metrics.has(name)) {
      this.metrics.set(name, { values: [], timestamps: [] });
    }
    const metric = this.metrics.get(name)!;
    metric.values.push(value);
    metric.timestamps.push(Date.now());
    if (metric.values.length > 10000) {
      metric.values.shift();
      metric.timestamps.shift();
    }
  }

  getStats(name: string, windowMs: number = 3600000): {
    count: number;
    avg: number;
    p50: number;
    p95: number;
    p99: number;
    min: number;
    max: number;
  } {
    const metric = this.metrics.get(name);
    if (!metric || metric.values.length === 0) {
      return { count: 0, avg: 0, p50: 0, p95: 0, p99: 0, min: 0, max: 0 };
    }
    const cutoff = Date.now() - windowMs;
    const values = metric.values.filter((_, i) => metric.timestamps[i] >= cutoff);
    if (values.length === 0) {
      return { count: 0, avg: 0, p50: 0, p95: 0, p99: 0, min: 0, max: 0 };
    }
    const sorted = [...values].sort((a, b) => a - b);
    return {
      count: values.length,
      avg: values.reduce((a, b) => a + b, 0) / values.length,
      p50: calculatePercentile(values, 50),
      p95: calculatePercentile(values, 95),
      p99: calculatePercentile(values, 99),
      min: sorted[0],
      max: sorted[sorted.length - 1],
    };
  }

  reset(name?: string): void {
    if (name) {
      this.metrics.delete(name);
    } else {
      this.metrics.clear();
    }
  }
}

export const metricsCollector = new MetricsCollector();
