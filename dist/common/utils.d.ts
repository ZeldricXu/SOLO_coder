import { ChainId } from '../types';
export declare function generateId(prefix?: string): string;
export declare function formatDate(date?: Date): string;
export declare function now(): string;
export declare function sleep(ms: number): Promise<void>;
export declare function isAddress(address: string): boolean;
export declare function normalizeAddress(address: string): string;
export declare function asChainId(chainId: number): ChainId;
export declare function getErrorMessage(error: unknown): string;
export declare function hexToNumber(hex: string): number;
export declare function numberToHex(num: number | string): string;
export declare function weiToEther(wei: string | bigint): string;
export declare function etherToWei(ether: string): string;
export declare function withRetry<T>(fn: () => Promise<T>, options?: {
    retries?: number;
    delay?: number;
    onRetry?: (error: unknown, attempt: number) => void;
}): Promise<T>;
export declare function withTimeout<T>(fn: () => Promise<T>, timeout: number, timeoutMessage?: string): Promise<T>;
export declare function chunkArray<T>(array: T[], size: number): T[][];
export declare function calculateMedian(values: number[]): number;
export declare function calculatePercentile(values: number[], percentile: number): number;
export declare function deepClone<T>(obj: T): T;
export declare function omit<T extends Record<string, unknown>, K extends keyof T>(obj: T, keys: K[]): Omit<T, K>;
export declare function pick<T extends Record<string, unknown>, K extends keyof T>(obj: T, keys: K[]): Pick<T, K>;
export declare class MetricsCollector {
    private metrics;
    private logger;
    constructor();
    record(name: string, value: number): void;
    getStats(name: string, windowMs?: number): {
        count: number;
        avg: number;
        p50: number;
        p95: number;
        p99: number;
        min: number;
        max: number;
    };
    reset(name?: string): void;
}
export declare const metricsCollector: MetricsCollector;
//# sourceMappingURL=utils.d.ts.map