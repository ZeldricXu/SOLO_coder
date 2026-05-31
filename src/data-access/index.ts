import { logger } from '../logging';
import NodeCache from 'node-cache';

export interface DatabaseConfig {
  host: string;
  port: number;
  database: string;
  user: string;
  password: string;
  connectionLimit?: number;
  idleTimeout?: number;
  maxRetries?: number;
}

export interface QueryOptions {
  timeout?: number;
  useCache?: boolean;
  cacheTTL?: number;
  maxRetries?: number;
}

export interface QueryResult<T = any> {
  rows: T[];
  rowCount: number;
  executionTime: number;
  fromCache?: boolean;
}

export interface ConnectionPoolStats {
  totalConnections: number;
  activeConnections: number;
  idleConnections: number;
  waitingRequests: number;
  totalQueries: number;
  cacheHits: number;
  cacheMisses: number;
}

class DatabaseConnection {
  id: string;
  createdAt: number;
  lastUsedAt: number;
  isActive: boolean;

  constructor(id: string) {
    this.id = id;
    this.createdAt = Date.now();
    this.lastUsedAt = Date.now();
    this.isActive = false;
  }

  release(): void {
    this.isActive = false;
    this.lastUsedAt = Date.now();
  }
}

export class ConnectionPool {
  private config: DatabaseConfig;
  private connections: DatabaseConnection[] = [];
  private waitingQueue: Array<(conn: DatabaseConnection) => void> = [];
  private queryCache: NodeCache;
  private stats: ConnectionPoolStats = {
    totalConnections: 0,
    activeConnections: 0,
    idleConnections: 0,
    waitingRequests: 0,
    totalQueries: 0,
    cacheHits: 0,
    cacheMisses: 0
  };
  private isInitialized = false;

  constructor(config: DatabaseConfig) {
    this.config = {
      connectionLimit: 10,
      idleTimeout: 30000,
      maxRetries: 3,
      ...config
    };
    this.queryCache = new NodeCache({ stdTTL: 60, checkperiod: 120 });
  }

  async initialize(): Promise<void> {
    if (this.isInitialized) return;
    
    const initialSize = Math.min(5, this.config.connectionLimit!);
    for (let i = 0; i < initialSize; i++) {
      this.createConnection();
    }
    
    this.isInitialized = true;
    logger.info(`Connection pool initialized with ${initialSize} connections`, {
      host: this.config.host,
      database: this.config.database
    });
  }

  private createConnection(): DatabaseConnection {
    const conn = new DatabaseConnection(`conn_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`);
    this.connections.push(conn);
    this.stats.totalConnections++;
    return conn;
  }

  async acquire(): Promise<DatabaseConnection> {
    const idleConn = this.connections.find(c => !c.isActive);
    if (idleConn) {
      idleConn.isActive = true;
      this.stats.activeConnections++;
      this.stats.idleConnections = this.connections.filter(c => !c.isActive).length;
      return idleConn;
    }

    if (this.connections.length < this.config.connectionLimit!) {
      const newConn = this.createConnection();
      newConn.isActive = true;
      this.stats.activeConnections++;
      return newConn;
    }

    this.stats.waitingRequests++;
    return new Promise<DatabaseConnection>((resolve) => {
      this.waitingQueue.push(resolve);
    });
  }

  release(connection: DatabaseConnection): void {
    connection.release();
    this.stats.activeConnections--;
    this.stats.idleConnections = this.connections.filter(c => !c.isActive).length;

    if (this.waitingQueue.length > 0) {
      const nextResolver = this.waitingQueue.shift()!;
      this.stats.waitingRequests--;
      connection.isActive = true;
      this.stats.activeConnections++;
      this.stats.idleConnections--;
      nextResolver(connection);
    }
  }

  async query<T = any>(sql: string, params: any[] = [], options: QueryOptions = {}): Promise<QueryResult<T>> {
    const startTime = Date.now();
    const opts: QueryOptions = {
      useCache: true,
      cacheTTL: 60,
      maxRetries: this.config.maxRetries,
      ...options
    };

    const cacheKey = `${sql}:${JSON.stringify(params)}`;
    
    if (opts.useCache) {
      const cached = this.queryCache.get<QueryResult<T>>(cacheKey);
      if (cached) {
        this.stats.cacheHits++;
        logger.debug('Query cache hit', { cacheKey });
        return { ...cached, fromCache: true };
      }
      this.stats.cacheMisses++;
    }

    this.stats.totalQueries++;
    const conn = await this.acquire();
    
    try {
      await new Promise(resolve => setTimeout(resolve, 10));
      
      const result: QueryResult<T> = {
        rows: [],
        rowCount: 0,
        executionTime: Date.now() - startTime
      };

      if (opts.useCache && result.rows.length > 0) {
        this.queryCache.set(cacheKey, result, opts.cacheTTL);
      }

      return result;
    } finally {
      this.release(conn);
    }
  }

  getStats(): ConnectionPoolStats {
    return { ...this.stats };
  }

  async close(): Promise<void> {
    this.queryCache.close();
    this.connections = [];
    logger.info('Connection pool closed');
  }
}

export class QueryOptimizer {
  static analyzeQuery(sql: string): { tables: string[]; hasFullScan: boolean; suggestion: string } {
    const tables: string[] = [];
    const fromMatch = sql.match(/FROM\s+(\w+)/gi);
    if (fromMatch) {
      fromMatch.forEach(m => {
        const table = m.replace(/FROM\s+/i, '');
        tables.push(table);
      });
    }

    const hasFullScan = /SELECT\s+\*/.test(sql) && !/WHERE/.test(sql);
    
    let suggestion = 'Query looks good';
    if (hasFullScan) {
      suggestion = 'Consider adding WHERE clause to avoid full table scan';
    }
    if (!/LIMIT/.test(sql) && /SELECT/.test(sql)) {
      suggestion = 'Consider adding LIMIT to paginate results';
    }

    return { tables, hasFullScan, suggestion };
  }
}

export const createConnectionPool = (config: DatabaseConfig): ConnectionPool => {
  return new ConnectionPool(config);
};
