export declare const generateId: (prefix?: string) => string;
export declare const nowISO: () => string;
export declare const sleep: (ms: number) => Promise<void>;
export declare const retry: <T>(fn: () => Promise<T>, retries?: number, delay?: number, backoff?: number) => Promise<T>;
export declare const withTimeout: <T>(promise: Promise<T>, ms: number, message?: string) => Promise<T>;
export declare const calculatePercentiles: (values: number[], percentiles: number[]) => Record<number, number>;
export declare const safeJsonParse: <T>(str: string, fallback: T) => T;
export declare const deepClone: <T>(obj: T) => T;
export declare const mergeDeep: <T extends Record<string, unknown>>(target: T, source: Partial<T>) => T;
export declare const formatBytes: (bytes: number) => string;
export declare const calculateDrift: (baseline: number[], current: number[]) => number;
//# sourceMappingURL=utils.d.ts.map