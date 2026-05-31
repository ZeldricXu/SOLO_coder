export declare function generateId(prefix?: string): string;
export declare function getCurrentTimestamp(): string;
export declare function sleep(ms: number): Promise<void>;
export declare function withTimeout<T>(promise: Promise<T>, timeoutMs: number, errorMessage?: string): Promise<T>;
export declare function deepClone<T>(obj: T): T;
export declare function retryAsync<T>(fn: () => Promise<T>, retries?: number, delay?: number): Promise<T>;
export declare class Semaphore {
    private permits;
    private waiters;
    constructor(permits: number);
    acquire(): Promise<void>;
    release(): void;
    get availablePermits(): number;
    get queueLength(): number;
}
//# sourceMappingURL=utils.d.ts.map