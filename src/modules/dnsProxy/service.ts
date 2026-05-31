import { PrismaClient, DnsUpstream as DbUpstream, DnsCache as DbCache } from '@prisma/client';
import { generateUpstreamId } from '../../utils/idGenerator';
import { NotFoundError } from '../../utils/errors';
import type { CreateUpstreamRequest, DnsUpstream, DnsQueryRequest, DnsQueryResult, DnsRecord, DnsCacheConfig } from './types';
import type { PaginationParams, PaginatedResult } from '../../types';
import logger from '../../utils/logger';
import NodeCache from 'node-cache';

const prisma = new PrismaClient();
const memoryCache = new NodeCache({ stdTTL: 300, checkperiod: 60 });

let cacheConfig: DnsCacheConfig = {
  enabled: true,
  defaultTTL: 300,
  maxCacheSize: 10000,
  negativeTTL: 30,
};

const toUpstream = (db: DbUpstream): DnsUpstream => ({
  upstreamId: db.upstreamId,
  name: db.name,
  addresses: db.addresses,
  priority: db.priority,
  enabled: db.enabled,
  createdAt: db.createdAt,
  updatedAt: db.updatedAt,
});

const getCacheKey = (domain: string, type: string): string => `dns:${domain}:${type}`;

export const createUpstream = async (data: CreateUpstreamRequest): Promise<DnsUpstream> => {
  const upstream = await prisma.dnsUpstream.create({
    data: {
      upstreamId: generateUpstreamId(),
      name: data.name,
      addresses: data.addresses,
      priority: data.priority,
      enabled: data.enabled,
    },
  });
  logger.info({ upstreamId: upstream.upstreamId }, 'DNS upstream created');
  return toUpstream(upstream);
};

export const getUpstream = async (upstreamId: string): Promise<DnsUpstream> => {
  const upstream = await prisma.dnsUpstream.findUnique({ where: { upstreamId } });
  if (!upstream) throw new NotFoundError(`DNS upstream ${upstreamId} not found`);
  return toUpstream(upstream);
};

export const listUpstreams = async (params: PaginationParams, enabledOnly?: boolean): Promise<PaginatedResult<DnsUpstream>> => {
  const where = enabledOnly ? { enabled: true } : {};
  const [items, total] = await Promise.all([
    prisma.dnsUpstream.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: [{ priority: 'asc' }, { createdAt: 'desc' }],
    }),
    prisma.dnsUpstream.count({ where }),
  ]);
  return {
    items: items.map(toUpstream),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const updateUpstream = async (upstreamId: string, data: Partial<CreateUpstreamRequest>): Promise<DnsUpstream> => {
  const upstream = await prisma.dnsUpstream.update({
    where: { upstreamId },
    data: {
      ...(data.name && { name: data.name }),
      ...(data.addresses && { addresses: data.addresses }),
      ...(data.priority !== undefined && { priority: data.priority }),
      ...(data.enabled !== undefined && { enabled: data.enabled }),
    },
  });
  logger.info({ upstreamId }, 'DNS upstream updated');
  return toUpstream(upstream);
};

export const deleteUpstream = async (upstreamId: string): Promise<void> => {
  await prisma.dnsUpstream.delete({ where: { upstreamId } });
  logger.info({ upstreamId }, 'DNS upstream deleted');
};

export const getEnabledUpstreams = async (): Promise<DnsUpstream[]> => {
  const upstreams = await prisma.dnsUpstream.findMany({
    where: { enabled: true },
    orderBy: [{ priority: 'asc' }, { createdAt: 'asc' }],
  });
  return upstreams.map(toUpstream);
};

const getFromMemoryCache = (key: string): DnsRecord[] | undefined => {
  if (!cacheConfig.enabled) return undefined;
  const cached = memoryCache.get<DnsRecord[]>(key);
  if (cached) {
    logger.debug({ key }, 'DNS cache hit (memory)');
    return cached;
  }
  return undefined;
};

const setMemoryCache = (key: string, records: DnsRecord[], ttl: number): void => {
  if (!cacheConfig.enabled) return;
  memoryCache.set(key, records, ttl);
};

const getFromDbCache = async (key: string): Promise<DnsRecord[] | undefined> => {
  if (!cacheConfig.enabled) return undefined;
  const now = new Date();
  const cache = await prisma.dnsCache.findFirst({
    where: { key, expiresAt: { gt: now } },
  });
  if (cache) {
    logger.debug({ key }, 'DNS cache hit (db)');
    const records = cache.records as DnsRecord[];
    setMemoryCache(key, records, Math.max(0, Math.floor((cache.expiresAt.getTime() - now.getTime()) / 1000)));
    return records;
  }
  return undefined;
};

const setDbCache = async (key: string, records: DnsRecord[], ttl: number): Promise<void> => {
  if (!cacheConfig.enabled || records.length === 0) return;
  const expiresAt = new Date(Date.now() + ttl * 1000);
  await prisma.dnsCache.upsert({
    where: { key },
    create: {
      key,
      records,
      ttl,
      expiresAt,
    },
    update: {
      records,
      ttl,
      expiresAt,
    },
  });
};

export const resolveDns = async (query: DnsQueryRequest): Promise<DnsQueryResult> => {
  const startTime = Date.now();
  const cacheKey = getCacheKey(query.domain, query.type);

  if (!query.skipCache) {
    const memoryCached = getFromMemoryCache(cacheKey);
    if (memoryCached) {
      return {
        query: { domain: query.domain, type: query.type },
        answers: memoryCached,
        authority: [],
        additional: [],
        fromCache: true,
        latencyMs: Date.now() - startTime,
        timestamp: new Date(),
      };
    }

    const dbCached = await getFromDbCache(cacheKey);
    if (dbCached) {
      return {
        query: { domain: query.domain, type: query.type },
        answers: dbCached,
        authority: [],
        additional: [],
        fromCache: true,
        latencyMs: Date.now() - startTime,
        timestamp: new Date(),
      };
    }
  }

  const upstreams = await getEnabledUpstreams();
  if (upstreams.length === 0) {
    throw new NotFoundError('No enabled DNS upstreams configured');
  }

  logger.info({ domain: query.domain, type: query.type }, 'Resolving DNS query');

  const answers: DnsRecord[] = [];
  let usedUpstream: string | undefined;

  for (const upstream of upstreams) {
    try {
      const result = await queryUpstream(upstream, query.domain, query.type);
      answers.push(...result.answers);
      usedUpstream = upstream.upstreamId;
      if (answers.length > 0) break;
    } catch (error) {
      logger.warn({ upstreamId: upstream.upstreamId, error }, 'DNS upstream query failed, trying next');
    }
  }

  if (answers.length > 0) {
    const minTTL = Math.min(...answers.map(r => r.ttl), cacheConfig.defaultTTL);
    setMemoryCache(cacheKey, answers, minTTL);
    await setDbCache(cacheKey, answers, minTTL);
  }

  return {
    query: { domain: query.domain, type: query.type },
    answers,
    authority: [],
    additional: [],
    fromCache: false,
    upstream: usedUpstream,
    latencyMs: Date.now() - startTime,
    timestamp: new Date(),
  };
};

const queryUpstream = async (upstream: DnsUpstream, domain: string, type: string): Promise<{ answers: DnsRecord[] }> => {
  const dns2 = await import('dns2');
  const client = new dns2.DNS({
    dns: upstream.addresses,
  });

  const response = await client.resolve(domain, type);
  const answers: DnsRecord[] = response.answers.map((a: { name: string; type: number; ttl: number; address?: string; domain?: string }) => ({
    name: a.name,
    type: dns2.Packet.TYPE[a.type] || 'A',
    ttl: a.ttl,
    data: a.address || a.domain || '',
  }));

  return { answers };
};

export const clearCache = async (domain?: string, type?: string): Promise<number> => {
  if (domain && type) {
    const key = getCacheKey(domain, type);
    memoryCache.del(key);
    const deleted = await prisma.dnsCache.deleteMany({ where: { key } });
    return deleted.count;
  }
  memoryCache.flushAll();
  const deleted = await prisma.dnsCache.deleteMany();
  logger.info({ count: deleted.count }, 'DNS cache cleared');
  return deleted.count;
};

export const getCacheConfig = (): DnsCacheConfig => ({ ...cacheConfig });

export const updateCacheConfig = (config: Partial<DnsCacheConfig>): DnsCacheConfig => {
  cacheConfig = { ...cacheConfig, ...config };
  logger.info({ config }, 'DNS cache config updated');
  return cacheConfig;
};

export default {
  createUpstream,
  getUpstream,
  listUpstreams,
  updateUpstream,
  deleteUpstream,
  getEnabledUpstreams,
  resolveDns,
  clearCache,
  getCacheConfig,
  updateCacheConfig,
};
