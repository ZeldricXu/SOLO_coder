import { v4 as uuidv4 } from 'uuid';

export function generateId(prefix: string = ''): string {
  return `${prefix}${uuidv4().replace(/-/g, '').slice(0, 24)}`;
}

export function getCurrentTimestamp(): string {
  return new Date().toISOString();
}

export function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export function withTimeout<T>(promise: Promise<T>, timeoutMs: number, errorMessage?: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error(errorMessage || `操作超时 (${timeoutMs}ms)`)), timeoutMs)
    )
  ]);
}

export function deepClone<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj));
}

export function retryAsync<T>(
  fn: () => Promise<T>,
  retries: number = 3,
  delay: number = 1000
): Promise<T> {
  return fn().catch(error => {
    if (retries <= 0) throw error;
    return sleep(delay).then(() => retryAsync(fn, retries - 1, delay * 2));
  });
}

export class Semaphore {
  private permits: number;
  private waiters: Array<() => void> = [];

  constructor(permits: number) {
    this.permits = permits;
  }

  async acquire(): Promise<void> {
    if (this.permits > 0) {
      this.permits--;
      return;
    }
    return new Promise(resolve => {
      this.waiters.push(resolve);
    });
  }

  release(): void {
    this.permits++;
    const next = this.waiters.shift();
    if (next) {
      this.permits--;
      next();
    }
  }

  get availablePermits(): number {
    return this.permits;
  }

  get queueLength(): number {
    return this.waiters.length;
  }
}
