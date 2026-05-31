import { v4 as uuidv4 } from 'uuid';
import moment from 'moment';

export function generateId(prefix = 'id'): string {
  return `${prefix}_${uuidv4().replace(/-/g, '').substring(0, 12)}`;
}

export function getCurrentTimestamp(): string {
  return moment().toISOString();
}

export function getCurrentTimeMs(): number {
  return Date.now();
}

export function parseDuration(duration: string): number {
  const match = duration.match(/^(\d+)([smhdw])$/);
  if (!match) {
    throw new Error(`Invalid duration format: ${duration}`);
  }
  const value = parseInt(match[1], 10);
  const unit = match[2];
  const multipliers: Record<string, number> = {
    s: 1000,
    m: 60 * 1000,
    h: 60 * 60 * 1000,
    d: 24 * 60 * 60 * 1000,
    w: 7 * 24 * 60 * 60 * 1000,
  };
  return value * (multipliers[unit] || 1000);
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60 * 1000) return `${Math.floor(ms / 1000)}s`;
  if (ms < 60 * 60 * 1000) return `${Math.floor(ms / (60 * 1000))}m`;
  if (ms < 24 * 60 * 60 * 1000) return `${Math.floor(ms / (60 * 60 * 1000))}h`;
  return `${Math.floor(ms / (24 * 60 * 60 * 1000))}d`;
}

export function calculatePercentile(values: number[], percentile: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.ceil((percentile / 100) * sorted.length);
  return sorted[index - 1] || sorted[sorted.length - 1];
}

export function average(values: number[]): number {
  if (values.length === 0) return 0;
  return values.reduce((sum, val) => sum + val, 0) / values.length;
}

export function sum(values: number[]): number {
  return values.reduce((acc, val) => acc + val, 0);
}

export function min(values: number[]): number {
  if (values.length === 0) return 0;
  return Math.min(...values);
}

export function max(values: number[]): number {
  if (values.length === 0) return 0;
  return Math.max(...values);
}

export function standardDeviation(values: number[]): number {
  if (values.length < 2) return 0;
  const avg = average(values);
  const squareDiffs = values.map((value) => Math.pow(value - avg, 2));
  const avgSquareDiff = average(squareDiffs);
  return Math.sqrt(avgSquareDiff);
}

export function exponentialMovingAverage(values: number[], alpha: number = 0.3): number[] {
  if (values.length === 0) return [];
  const ema: number[] = [values[0]];
  for (let i = 1; i < values.length; i++) {
    ema.push(alpha * values[i] + (1 - alpha) * ema[i - 1]);
  }
  return ema;
}

export function movingAverage(values: number[], windowSize: number): number[] {
  const result: number[] = [];
  for (let i = 0; i < values.length; i++) {
    const windowStart = Math.max(0, i - windowSize + 1);
    const window = values.slice(windowStart, i + 1);
    result.push(average(window));
  }
  return result;
}

export function deepClone<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj));
}

export function isObject(value: any): value is Record<string, any> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function mergeDeep(target: Record<string, any>, source: Record<string, any>): Record<string, any> {
  const output = { ...target };
  if (isObject(target) && isObject(source)) {
    Object.keys(source).forEach((key) => {
      if (isObject(source[key])) {
        if (!(key in target)) {
          Object.assign(output, { [key]: source[key] });
        } else {
          output[key] = mergeDeep(target[key], source[key]);
        }
      } else {
        Object.assign(output, { [key]: source[key] });
      }
    });
  }
  return output;
}

export function retry<T>(
  fn: () => Promise<T>,
  retries: number = 3,
  delay: number = 1000,
  backoff: number = 2
): Promise<T> {
  return new Promise((resolve, reject) => {
    const attempt = (attemptNumber: number) => {
      fn()
        .then(resolve)
        .catch((error) => {
          if (attemptNumber < retries) {
            const waitTime = delay * Math.pow(backoff, attemptNumber);
            setTimeout(() => attempt(attemptNumber + 1), waitTime);
          } else {
            reject(error);
          }
        });
    };
    attempt(0);
  });
}

export class AsyncQueue<T> {
  private queue: T[] = [];
  private waiting: Array<(value: T) => void> = [];

  enqueue(item: T): void {
    if (this.waiting.length > 0) {
      const resolve = this.waiting.shift()!;
      resolve(item);
    } else {
      this.queue.push(item);
    }
  }

  dequeue(): Promise<T> {
    if (this.queue.length > 0) {
      return Promise.resolve(this.queue.shift()!);
    }
    return new Promise((resolve) => {
      this.waiting.push(resolve);
    });
  }

  size(): number {
    return this.queue.length;
  }

  isEmpty(): boolean {
    return this.queue.length === 0;
  }
}

export class EventEmitter<T = Record<string, any>> {
  private listeners: Map<keyof T, Set<(data: any) => void>> = new Map();

  on<K extends keyof T>(event: K, listener: (data: T[K]) => void): void {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event)!.add(listener);
  }

  off<K extends keyof T>(event: K, listener: (data: T[K]) => void): void {
    this.listeners.get(event)?.delete(listener);
  }

  emit<K extends keyof T>(event: K, data: T[K]): void {
    this.listeners.get(event)?.forEach((listener) => listener(data));
  }

  once<K extends keyof T>(event: K, listener: (data: T[K]) => void): void {
    const onceWrapper = (data: T[K]) => {
      listener(data);
      this.off(event, onceWrapper);
    };
    this.on(event, onceWrapper);
  }
}
