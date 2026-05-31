import NodeCache from 'node-cache';
import { PoolClient, QueryResult, QueryResultRow } from 'pg';
import { ConnectionPool, QueryOptions } from './ConnectionPool';
import { DatabaseError } from '../../common/errors';

export interface QueryOptimizerConfig {
  cacheTTL?: number;
  cacheMaxKeys?: number;
  slowQueryThresholdMs?: number;
  enableQueryPlanCache?: boolean;
}

export interface QueryPlan {
  query: string;
  tables: string[];
  hasIndex: boolean;
  estimatedCost?: number;
  suggestions: string[];
}

export interface CachedQueryResult {
  rows: QueryResultRow[];
  rowCount: number;
  cachedAt: number;
  ttl: number;
}

export interface QueryStats {
  totalQueries: number;
  cacheHits: number;
  cacheMisses: number;
  slowQueries: number;
  avgExecutionTime: number;
  queriesByTable: Record<string, number>;
}

export class QueryOptimizer {
  private pool: ConnectionPool;
  private cache: NodeCache;
  private config: Required<QueryOptimizerConfig>;
  private queryPlanCache: Map<string, QueryPlan>;
  private stats: QueryStats;

  constructor(pool: ConnectionPool, config: QueryOptimizerConfig = {}) {
    this.pool = pool;
    this.config = {
      cacheTTL: config.cacheTTL ?? 300,
      cacheMaxKeys: config.cacheMaxKeys ?? 1000,
      slowQueryThresholdMs: config.slowQueryThresholdMs ?? 1000,
      enableQueryPlanCache: config.enableQueryPlanCache ?? true
    };

    this.cache = new NodeCache({
      stdTTL: this.config.cacheTTL,
      maxKeys: this.config.cacheMaxKeys,
      checkperiod: 60
    });

    this.queryPlanCache = new Map();

    this.stats = {
      totalQueries: 0,
      cacheHits: 0,
      cacheMisses: 0,
      slowQueries: 0,
      avgExecutionTime: 0,
      queriesByTable: {}
    };
  }

  async optimizedQuery<T extends QueryResultRow = any>(
    text: string,
    params?: unknown[],
    options: QueryOptions & { useCache?: boolean; cacheTTL?: number } = {}
  ): Promise<QueryResult<T>> {
    const { useCache = false, cacheTTL, ...queryOptions } = options;

    const cacheKey = this.generateCacheKey(text, params);

    if (useCache) {
      const cached = this.cache.get<CachedQueryResult>(cacheKey);
      if (cached) {
        this.stats.cacheHits++;
        return {
          rows: cached.rows as T[],
          rowCount: cached.rowCount,
          command: 'SELECT',
          oid: 0,
          fields: []
        };
      }
      this.stats.cacheMisses++;
    }

    const startTime = Date.now();

    try {
      const result = await this.pool.query<T>(text, params, queryOptions);

      const executionTime = Date.now() - startTime;
      this.trackQueryStats(text, executionTime);

      if (useCache && result.rows.length > 0) {
        this.cache.set(cacheKey, {
          rows: result.rows,
          rowCount: result.rowCount || 0,
          cachedAt: Date.now(),
          ttl: cacheTTL || this.config.cacheTTL
        }, cacheTTL || this.config.cacheTTL);
      }

      if (executionTime > this.config.slowQueryThresholdMs) {
        this.handleSlowQuery(text, executionTime);
      }

      return result;
    } catch (error) {
      throw error;
    }
  }

  private generateCacheKey(query: string, params?: unknown[]): string {
    return `query:${query}:${params ? JSON.stringify(params) : ''}`;
  }

  private trackQueryStats(query: string, executionTime: number): void {
    this.stats.totalQueries++;

    const tables = this.extractTableNames(query);
    for (const table of tables) {
      this.stats.queriesByTable[table] = (this.stats.queriesByTable[table] || 0) + 1;
    }

    const totalTime = this.stats.avgExecutionTime * (this.stats.totalQueries - 1) + executionTime;
    this.stats.avgExecutionTime = totalTime / this.stats.totalQueries;
  }

  private extractTableNames(query: string): string[] {
    const tables: string[] = [];

    const fromRegex = /FROM\s+([a-zA-Z_][a-zA-Z0-9_]*)/gi;
    const joinRegex = /JOIN\s+([a-zA-Z_][a-zA-Z0-9_]*)/gi;

    let match;
    while ((match = fromRegex.exec(query)) !== null) {
      tables.push(match[1].toLowerCase());
    }
    while ((match = joinRegex.exec(query)) !== null) {
      tables.push(match[1].toLowerCase());
    }

    return [...new Set(tables)];
  }

  private async handleSlowQuery(query: string, executionTime: number): Promise<void> {
    this.stats.slowQueries++;
    console.warn(`慢查询检测 (${executionTime}ms):`, query);

    const plan = await this.analyzeQuery(query);
    if (plan.suggestions.length > 0) {
      console.warn('查询优化建议:', plan.suggestions);
    }
  }

  async analyzeQuery(query: string): Promise<QueryPlan> {
    if (this.config.enableQueryPlanCache && this.queryPlanCache.has(query)) {
      return this.queryPlanCache.get(query)!;
    }

    const plan: QueryPlan = {
      query,
      tables: this.extractTableNames(query),
      hasIndex: true,
      suggestions: []
    };

    if (query.toUpperCase().includes('SELECT *')) {
      plan.suggestions.push('建议明确指定需要的字段，避免使用 SELECT *');
    }

    if (!query.toUpperCase().includes('WHERE') && query.toUpperCase().includes('SELECT')) {
      plan.suggestions.push('缺少 WHERE 条件，可能导致全表扫描');
    }

    if (query.toUpperCase().includes('LIKE') && query.includes('%')) {
      const likeIndex = query.toUpperCase().indexOf('LIKE');
      const afterLike = query.substring(likeIndex + 4).trim();
      if (afterLike.startsWith("'%") || afterLike.startsWith('"%')) {
        plan.suggestions.push('避免使用前导通配符的 LIKE 查询，这会导致索引失效');
      }
    }

    const orderByMatch = query.match(/ORDER\s+BY\s+([a-zA-Z0-9_,\s]+)/i);
    if (orderByMatch) {
      plan.suggestions.push(`考虑为 ORDER BY 字段添加索引: ${orderByMatch[1].trim()}`);
    }

    if (query.toUpperCase().includes('GROUP BY')) {
      plan.suggestions.push('GROUP BY 操作可能影响性能，考虑为分组字段添加索引');
    }

    if (query.toUpperCase().includes('DISTINCT')) {
      plan.suggestions.push('DISTINCT 会增加排序开销，考虑使用 EXISTS 或 GROUP BY 替代');
    }

    try {
      const explainResult = await this.pool.query(`EXPLAIN ${query}`);
      const explainText = explainResult.rows.map(r => Object.values(r)[0]).join('\n');

      if (explainText.toUpperCase().includes('SEQ SCAN')) {
        plan.hasIndex = false;
        plan.suggestions.push('检测到全表扫描，建议添加适当的索引');
      }

      const costMatch = explainText.match(/cost=([\d.]+)/i);
      if (costMatch) {
        plan.estimatedCost = parseFloat(costMatch[1]);
      }
    } catch (error) {
      console.warn('无法执行 EXPLAIN 分析:', error);
    }

    if (this.config.enableQueryPlanCache) {
      this.queryPlanCache.set(query, plan);
    }

    return plan;
  }

  async batchOptimize(queries: { text: string; params?: unknown[] }[]): Promise<QueryResult[]> {
    const optimized = queries.map(q => this.optimizeQuerySyntax(q.text));
    return this.pool.batchQuery(optimized.map((text, i) => ({
      text,
      params: queries[i].params
    })));
  }

  private optimizeQuerySyntax(query: string): string {
    let optimized = query.trim();

    if (/^\s*SELECT\s+/i.test(optimized)) {
      optimized = optimized.replace(/\s+/g, ' ');
    }

    return optimized;
  }

  invalidateCache(cacheKey?: string): void {
    if (cacheKey) {
      this.cache.del(cacheKey);
    } else {
      this.cache.flushAll();
    }
  }

  invalidateTableCache(tableName: string): void {
    const keys = this.cache.keys();
    for (const key of keys) {
      if (key.toLowerCase().includes(tableName.toLowerCase())) {
        this.cache.del(key);
      }
    }
  }

  getStats(): QueryStats {
    return { ...this.stats };
  }

  resetStats(): void {
    this.stats = {
      totalQueries: 0,
      cacheHits: 0,
      cacheMisses: 0,
      slowQueries: 0,
      avgExecutionTime: 0,
      queriesByTable: {}
    };
  }

  getPlanCacheSize(): number {
    return this.queryPlanCache.size;
  }

  clearPlanCache(): void {
    this.queryPlanCache.clear();
  }

  getCacheStats(): { keys: number; hits: number; misses: number; hitRate: number } {
    const stats = this.cache.getStats();
    const total = stats.hits + stats.misses;
    return {
      keys: this.cache.keys().length,
      hits: stats.hits,
      misses: stats.misses,
      hitRate: total > 0 ? stats.hits / total : 0
    };
  }

  async withRetry<T>(
    operation: () => Promise<T>,
    maxRetries: number = 3,
    backoffMs: number = 100
  ): Promise<T> {
    let lastError: unknown;

    for (let attempt = 0; attempt < maxRetries; attempt++) {
      try {
        return await operation();
      } catch (error) {
        lastError = error;

        if (this.isTransientError(error)) {
          await new Promise(resolve => setTimeout(resolve, backoffMs * Math.pow(2, attempt)));
          continue;
        }

        throw error;
      }
    }

    throw lastError;
  }

  private isTransientError(error: unknown): boolean {
    if (error instanceof DatabaseError) {
      const message = error.message.toLowerCase();
      return message.includes('connection') ||
             message.includes('timeout') ||
             message.includes('deadlock') ||
             message.includes('too many connections');
    }
    return false;
  }

  destroy(): void {
    this.cache.flushAll();
    this.cache.close();
    this.queryPlanCache.clear();
  }
}
