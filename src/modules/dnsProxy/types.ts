import { z } from 'zod';

export const DnsUpstreamSchema = z.object({
  name: z.string().min(1).max(100),
  addresses: z.array(z.string().min(1)).min(1),
  priority: z.number().int().nonnegative().default(0),
  enabled: z.boolean().default(true),
});

export const DnsQuerySchema = z.object({
  domain: z.string().min(1),
  type: z.enum(['A', 'AAAA', 'CNAME', 'MX', 'TXT', 'SRV', 'NS', 'PTR']).default('A'),
  clientIp: z.string().optional(),
  skipCache: z.boolean().default(false),
});

export const DnsCacheConfigSchema = z.object({
  enabled: z.boolean().default(true),
  defaultTTL: z.number().int().positive().default(300),
  maxCacheSize: z.number().int().positive().default(10000),
  negativeTTL: z.number().int().nonnegative().default(30),
});

export type CreateUpstreamRequest = z.infer<typeof DnsUpstreamSchema>;
export type DnsQueryRequest = z.infer<typeof DnsQuerySchema>;
export type DnsCacheConfig = z.infer<typeof DnsCacheConfigSchema>;

export interface DnsUpstream {
  upstreamId: string;
  name: string;
  addresses: string[];
  priority: number;
  enabled: boolean;
  createdAt: Date;
  updatedAt: Date;
}

export interface DnsRecord {
  name: string;
  type: string;
  ttl: number;
  data: string;
}

export interface DnsQueryResult {
  query: {
    domain: string;
    type: string;
  };
  answers: DnsRecord[];
  authority: DnsRecord[];
  additional: DnsRecord[];
  fromCache: boolean;
  upstream?: string;
  latencyMs: number;
  timestamp: Date;
}

export interface DnsCacheEntry {
  id: string;
  key: string;
  records: DnsRecord[];
  ttl: number;
  expiresAt: Date;
  createdAt: Date;
}
