import EventEmitter from 'eventemitter3';

export interface ServiceLifecycle {
  initialize?(): Promise<void>;
  start?(): Promise<void>;
  stop?(): Promise<void>;
  destroy(): void;
}

export abstract class BaseService extends EventEmitter implements ServiceLifecycle {
  protected readonly serviceName: string;
  protected timers: Set<NodeJS.Timeout> = new Set();
  protected initialized: boolean = false;
  protected destroyed: boolean = false;

  constructor(serviceName: string) {
    super();
    this.serviceName = serviceName;
  }

  protected addTimer(timer: NodeJS.Timeout): void {
    this.timers.add(timer);
  }

  protected clearTimers(): void {
    for (const timer of this.timers) {
      clearInterval(timer);
      clearTimeout(timer);
    }
    this.timers.clear();
  }

  protected assertNotDestroyed(): void {
    if (this.destroyed) {
      throw new Error(`${this.serviceName} has been destroyed`);
    }
  }

  protected emitError(error: unknown, context?: Record<string, unknown>): void {
    this.emit('error', error, context);
  }

  destroy(): void {
    if (this.destroyed) return;
    this.clearTimers();
    this.removeAllListeners();
    this.destroyed = true;
  }
}

export abstract class AsyncBaseService extends BaseService {
  private initializationPromise?: Promise<void>;

  constructor(serviceName: string) {
    super(serviceName);
  }

  async initialize(): Promise<void> {
    if (this.initialized) return;
    if (this.initializationPromise) return this.initializationPromise;

    this.initializationPromise = this.onInitialize()
      .then(() => {
        this.initialized = true;
      })
      .finally(() => {
        this.initializationPromise = undefined;
      });

    return this.initializationPromise;
  }

  protected abstract onInitialize(): Promise<void>;
}
