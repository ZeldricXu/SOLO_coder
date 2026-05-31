import { v4 as uuidv4 } from 'uuid';

export function generateId(prefix: string = ''): string {
  return `${prefix}${uuidv4().replace(/-/g, '').slice(0, 12)}`;
}

export function currentTimestamp(): string {
  return new Date().toISOString();
}

export function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export function retry<T>(
  fn: () => Promise<T>,
  maxRetries: number = 3,
  delay: number = 1000,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const attempt = async (count: number) => {
      try {
        const result = await fn();
        resolve(result);
      } catch (error) {
        if (count < maxRetries) {
          setTimeout(() => attempt(count + 1), delay * Math.pow(2, count));
        } else {
          reject(error);
        }
      }
    };
    attempt(0);
  });
}
