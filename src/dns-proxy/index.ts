import { DNSRecord, DNSRecordType } from '../types';
import { logger } from '../logging';
import NodeCache from 'node-cache';
import { PrometheusRegistry, createPrometheusRegistry } from './metrics';

export interface UpstreamDNS {
  id: string;
  name: string;
  address: string;
  port: number;
  priority: number;
  enabled: boolean;
  healthCheckUrl?: string;
}

export interface DNSQueryOptions {
  timeout?: number;
  useCache?: boolean;
  retries?: number;
}

export interface DNSQueryResult {
  records: DNSRecord[];
  source: 'cache' | 'upstream';
  responseTime: number;
  upstreamId?: string;
}

export type ResolutionStrategy = 'round_robin' | 'priority' | 'fastest' | 'geolocation';

export interface UpstreamHealth {
  upstreamId: string;
  healthy: boolean;
  lastCheck: string;
  latency: number;
  errorCount: number;
  successCount: number;
}

export interface DNSMetrics {
  totalQueries: number;
  cacheHits: number;
  cacheMisses: number;
  failedQueries: number;
  averageLatency: number;
  p50Latency: number;
  p95Latency: number;
  p99Latency: number;
  upstreamStats: Map<string, { queries: number; failures: number; avgLatency: number }>;
}

export class DNSProxy {
  private upstreams: UpstreamDNS[] = [];
  private cache: NodeCache;
  private strategy: ResolutionStrategy = 'priority';
  private registry: PrometheusRegistry;
  private latencies: number[] = [];
  private upstreamHealth: Map<string, UpstreamHealth> = new Map();
  private maxLatencySamples: number = 10000;

  constructor() {
    this.cache = new NodeCache({ stdTTL: 300, checkperiod: 600 });
    this.registry = createPrometheusRegistry();
    this.registerMetrics();
  }

  private registerMetrics(): void {
    this.registry.registerCounter(
      'dns_queries_total',
      'Total number of DNS queries processed'
    );
    this.registry.registerCounter(
      'dns_cache_hits_total',
      'Total number of DNS cache hits'
    );
    this.registry.registerCounter(
      'dns_cache_misses_total',
      'Total number of DNS cache misses'
    );
    this.registry.registerCounter(
      'dns_failed_queries_total',
      'Total number of failed DNS queries'
    );
    this.registry.registerGauge(
      'dns_upstreams_total',
      'Total number of configured DNS upstreams'
    );
    this.registry.registerGauge(
      'dns_cache_size',
      'Current number of entries in DNS cache'
    );
    this.registry.registerHistogram(
      'dns_query_duration_seconds',
      'Histogram of DNS query durations in seconds'
    );
    this.registry.registerHistogram(
      'dns_upstream_duration_seconds',
      'Histogram of DNS upstream query durations in seconds'
    );
  }

  addUpstream(upstream: Omit<UpstreamDNS, 'id'>): UpstreamDNS {
    const newUpstream: UpstreamDNS = { ...upstream, id: `dns_${Date.now()}_${Math.random().toString(36).substr(2, 9)}` };
    this.upstreams.push(newUpstream);
    this.upstreams.sort((a, b) => a.priority - b.priority);
    this.upstreamHealth.set(newUpstream.id, {
      upstreamId: newUpstream.id,
      healthy: true,
      lastCheck: new Date().toISOString(),
      latency: 0,
      errorCount: 0,
      successCount: 0
    });
    this.registry.setGauge('dns_upstreams_total', this.upstreams.length);
    logger.info('DNS upstream added', { id: newUpstream.id, address: newUpstream.address });
    return newUpstream;
  }

  removeUpstream(upstreamId: string): void {
    this.upstreams = this.upstreams.filter(u => u.id !== upstreamId);
    this.upstreamHealth.delete(upstreamId);
    this.registry.setGauge('dns_upstreams_total', this.upstreams.length);
  }

  setStrategy(strategy: ResolutionStrategy): void {
    this.strategy = strategy;
  }

  async query(domain: string, type: DNSRecordType = 'A', options: DNSQueryOptions = {}): Promise<DNSQueryResult> {
    const startTime = Date.now();
    const opts: DNSQueryOptions = { timeout: 5000, useCache: true, retries: 2, ...options };
    const cacheKey = `${domain}:${type}`;

    this.registry.incrementCounter('dns_queries_total');

    if (opts.useCache) {
      const cached = this.cache.get<DNSRecord[]>(cacheKey);
      if (cached) {
        this.registry.incrementCounter('dns_cache_hits_total');
        const duration = (Date.now() - startTime) / 1000;
        this.registry.observeHistogram('dns_query_duration_seconds', duration);
        this.recordLatency(duration);
        return { records: cached, source: 'cache', responseTime: Date.now() - startTime };
      }
      this.registry.incrementCounter('dns_cache_misses_total');
    }

    const records = await this.queryUpstreams(domain, type, opts);
    
    if (opts.useCache && records.length > 0) {
      const ttl = Math.min(...records.map(r => r.ttl));
      this.cache.set(cacheKey, records, ttl);
    }

    if (records.length === 0) {
      this.registry.incrementCounter('dns_failed_queries_total');
    }

    const duration = (Date.now() - startTime) / 1000;
    this.registry.observeHistogram('dns_query_duration_seconds', duration);
    this.recordLatency(duration);
    this.updateCacheSize();

    return { records, source: 'upstream', responseTime: Date.now() - startTime };
  }

  private async queryUpstreams(domain: string, type: DNSRecordType, options: DNSQueryOptions): Promise<DNSRecord[]> {
    const orderedUpstreams = this.getOrderedUpstreams();
    
    for (const upstream of orderedUpstreams) {
      if (!upstream.enabled) continue;
      
      const upstreamStart = Date.now();
      try {
        const records = await this.querySingleUpstream(upstream, domain, type, options.timeout!);
        
        const upstreamDuration = (Date.now() - upstreamStart) / 1000;
        this.registry.observeHistogram('dns_upstream_duration_seconds', upstreamDuration);
        this.recordUpstreamSuccess(upstream.id, upstreamDuration);
        
        if (records.length > 0) {
          return records;
        }
      } catch (error) {
        logger.warn('DNS upstream query failed', { upstream: upstream.address, error: (error as Error).message });
        this.recordUpstreamFailure(upstream.id);
      }
    }

    return [];
  }

  private async querySingleUpstream(upstream: UpstreamDNS, domain: string, type: DNSRecordType, timeout: number): Promise<DNSRecord[]> {
    await new Promise(resolve => setTimeout(resolve, 10));
    return [{ name: domain, type: 'A', value: '192.168.1.100', ttl: 300 }];
  }

  private getOrderedUpstreams(): UpstreamDNS[] {
    const enabled = this.upstreams.filter(u => u.enabled);
    switch (this.strategy) {
      case 'round_robin': return [...enabled].sort(() => Math.random() - 0.5);
      case 'priority': return [...enabled].sort((a, b) => a.priority - b.priority);
      case 'fastest':
        return [...enabled].sort((a, b) => {
          const healthA = this.upstreamHealth.get(a.id);
          const healthB = this.upstreamHealth.get(b.id);
          return (healthA?.latency || 9999) - (healthB?.latency || 9999);
        });
      default: return enabled;
    }
  }

  private recordLatency(durationSeconds: number): void {
    this.latencies.push(durationSeconds * 1000);
    if (this.latencies.length > this.maxLatencySamples) {
      this.latencies.shift();
    }
  }

  private recordUpstreamSuccess(upstreamId: string, latencySeconds: number): void {
    const health = this.upstreamHealth.get(upstreamId);
    if (health) {
      health.healthy = true;
      health.lastCheck = new Date().toISOString();
      health.latency = latencySeconds * 1000;
      health.successCount++;
    }
  }

  private recordUpstreamFailure(upstreamId: string): void {
    const health = this.upstreamHealth.get(upstreamId);
    if (health) {
      health.errorCount++;
      if (health.errorCount > 5) {
        health.healthy = false;
      }
    }
  }

  private updateCacheSize(): void {
    this.registry.setGauge('dns_cache_size', this.cache.getStats().keys);
  }

  async checkUpstreamHealth(upstreamId: string): Promise<UpstreamHealth | null> {
    const upstream = this.upstreams.find(u => u.id === upstreamId);
    if (!upstream) return null;

    const startTime = Date.now();
    try {
      await this.querySingleUpstream(upstream, 'example.com', 'A', 5000);
      const health = this.upstreamHealth.get(upstreamId)!;
      health.healthy = true;
      health.lastCheck = new Date().toISOString();
      health.latency = Date.now() - startTime;
      return health;
    } catch (error) {
      const health = this.upstreamHealth.get(upstreamId)!;
      health.healthy = false;
      health.lastCheck = new Date().toISOString();
      health.errorCount++;
      return health;
    }
  }

  async checkAllUpstreamsHealth(): Promise<Map<string, UpstreamHealth>> {
    const results = new Map<string, UpstreamHealth>();
    for (const upstream of this.upstreams) {
      const health = await this.checkUpstreamHealth(upstream.id);
      if (health) results.set(upstream.id, health);
    }
    return results;
  }

  clearCache(): void {
    this.cache.flushAll();
    this.updateCacheSize();
    logger.info('DNS cache cleared');
  }

  getMetrics(): DNSMetrics {
    const sorted = [...this.latencies].sort((a, b) => a - b);
    const avgLatency = sorted.length > 0 ? sorted.reduce((a, b) => a + b, 0) / sorted.length : 0;
    
    const upstreamStats = new Map<string, { queries: number; failures: number; avgLatency: number }>();
    for (const [id, health] of this.upstreamHealth) {
      const total = health.successCount + health.errorCount;
      upstreamStats.set(id, {
        queries: total,
        failures: health.errorCount,
        avgLatency: health.latency
      });
    }

    return {
      totalQueries: this.registry.getMetric('dns_queries_total')?.value || 0,
      cacheHits: this.registry.getMetric('dns_cache_hits_total')?.value || 0,
      cacheMisses: this.registry.getMetric('dns_cache_misses_total')?.value || 0,
      failedQueries: this.registry.getMetric('dns_failed_queries_total')?.value || 0,
      averageLatency: avgLatency,
      p50Latency: this.percentile(sorted, 50),
      p95Latency: this.percentile(sorted, 95),
      p99Latency: this.percentile(sorted, 99),
      upstreamStats
    };
  }

  private percentile(sorted: number[], p: number): number {
    if (sorted.length === 0) return 0;
    const index = Math.ceil((p / 100) * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
  }

  getPrometheusMetrics(): string {
    this.updateCacheSize();
    return this.registry.toPrometheusFormat();
  }

  getMetricsJSON(): Record<string, any> {
    return this.registry.toJSON();
  }

  getStats() {
    return {
      totalQueries: this.registry.getMetric('dns_queries_total')?.value || 0,
      cacheHits: this.registry.getMetric('dns_cache_hits_total')?.value || 0,
      cacheMisses: this.registry.getMetric('dns_cache_misses_total')?.value || 0,
      upstreams: this.upstreams.length,
      cacheSize: this.cache.getStats().keys
    };
  }

  getUpstreams(): UpstreamDNS[] {
    return [...this.upstreams];
  }

  getUpstreamHealth(upstreamId?: string): UpstreamHealth | UpstreamHealth[] | null {
    if (upstreamId) {
      return this.upstreamHealth.get(upstreamId) || null;
    }
    return Array.from(this.upstreamHealth.values());
  }

  resetMetrics(): void {
    this.registry.resetAll();
    this.latencies = [];
    this.upstreamHealth.forEach(h => {
      h.successCount = 0;
      h.errorCount = 0;
    });
    logger.info('DNS metrics reset');
  }
}

export const createDNSProxy = (): DNSProxy => new DNSProxy();

export { PrometheusRegistry, createPrometheusRegistry } from './metrics';
