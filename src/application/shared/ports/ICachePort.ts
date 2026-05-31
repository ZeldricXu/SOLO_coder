export interface ICachePort {
  get<T>(key: string): Promise<T | null>;
  set<T>(key: string, value: T, ttlSeconds?: number): Promise<void>;
  del(key: string): Promise<void>;
  delPattern(pattern: string): Promise<void>;
  exists(key: string): Promise<boolean>;
  increment(key: string, amount?: number): Promise<number>;
  expire(key: string, ttlSeconds: number): Promise<void>;
}

export const CACHE_PORT = Symbol('ICachePort');

export const TTL = {
  SHORT: 60,
  MEDIUM: 300,
  LONG: 3600,
  DAY: 86400,
  WEEK: 604800
} as const;

export const generateCacheKey = (...parts: string[]): string => {
  return parts.filter(Boolean).join(':');
};
