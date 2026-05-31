import logger from './logger';

export class ResourcePool<T> {
  private pool: T[] = [];
  private waiting: Array<(resource: T) => void> = [];
  private maxSize: number;
  private factory: () => Promise<T>;
  private destroyer?: (resource: T) => Promise<void>;

  constructor(
    maxSize: number,
    factory: () => Promise<T>,
    destroyer?: (resource: T) => Promise<void>,
  ) {
    this.maxSize = maxSize;
    this.factory = factory;
    this.destroyer = destroyer;
  }

  async acquire(): Promise<T> {
    if (this.pool.length > 0) {
      return this.pool.shift()!;
    }
    if (this.pool.length + this.waiting.length < this.maxSize) {
      const resource = await this.factory();
      return resource;
    }
    return new Promise<T>((resolve) => {
      this.waiting.push(resolve);
    });
  }

  release(resource: T): void {
    if (this.waiting.length > 0) {
      const resolve = this.waiting.shift()!;
      resolve(resource);
    } else {
      this.pool.push(resource);
    }
  }

  async drain(): Promise<void> {
    if (this.destroyer) {
      for (const resource of this.pool) {
        try {
          await this.destroyer(resource);
        } catch (error) {
          logger.error('Error destroying resource', { error });
        }
      }
    }
    this.pool = [];
  }

  available(): number {
    return this.pool.length;
  }

  waitingCount(): number {
    return this.waiting.length;
  }
}
