import { Request, Response, NextFunction } from 'express';
import { v4 as uuidv4 } from 'uuid';
import * as fs from 'fs-extra';
import * as path from 'path';
import * as yaml from 'yaml';
import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { currentTimestamp } from '../../utils/helpers';
import { configManager } from '../config';

export interface RequestLog {
  traceId: string;
  parentSpanId?: string;
  spanId: string;
  method: string;
  path: string;
  query: Record<string, any>;
  statusCode: number;
  latency: number;
  clientIp: string;
  userAgent: string;
  timestamp: string;
  error?: string;
  tags: Record<string, string>;
}

export interface TraceContext {
  traceId: string;
  spanId: string;
  parentSpanId?: string;
  serviceName: string;
  tags: Record<string, string>;
}

export interface TracingConfig {
  enabled: boolean;
  sampleRate: number;
  logHeaders: boolean;
  logBody: boolean;
  maxBodySize: number;
}

export interface GatewayRoute {
  path: string;
  method: string;
  target: string;
  timeout: number;
  retry: number;
  enabled: boolean;
  rateLimit?: {
    requestsPerMinute: number;
    burst: number;
  };
}

export interface RateLimitState {
  [key: string]: {
    count: number;
    windowStart: number;
  };
}

export interface ConfigVersion {
  version: number;
  config: TracingConfig;
  routes: GatewayRoute[];
  appliedAt: string;
  appliedBy: string;
}

export interface ConfigChangeEvent {
  oldConfig: TracingConfig;
  newConfig: TracingConfig;
  oldRoutes: GatewayRoute[];
  newRoutes: GatewayRoute[];
  timestamp: string;
}

declare global {
  namespace Express {
    interface Request {
      traceContext?: TraceContext;
      startTime?: number;
      rawBody?: string;
    }
  }
}

export class ApiGateway {
  private config: TracingConfig;
  private routes: GatewayRoute[] = [];
  private requestLogs: RequestLog[] = [];
  private maxLogs: number = 10000;
  private configVersions: ConfigVersion[] = [];
  private currentVersion: number = 1;
  private configFilePath: string;
  private fileWatcher?: fs.FSWatcher;
  private rateLimitState: RateLimitState = {};
  private rateLimitCleanupTimer?: NodeJS.Timeout;

  constructor(config?: Partial<TracingConfig>) {
    this.config = {
      enabled: true,
      sampleRate: 1.0,
      logHeaders: true,
      logBody: false,
      maxBodySize: 1024,
      ...config,
    };

    this.configFilePath = configManager.getParameter(
      'gateway.configPath',
      'gateway',
      './config/gateway.yaml',
    );

    this.initializeConfig().then(() => {
      this.startConfigWatcher();
      this.startRateLimitCleanup();
    });

    this.configVersions.push({
      version: this.currentVersion,
      config: { ...this.config },
      routes: [...this.routes],
      appliedAt: currentTimestamp(),
      appliedBy: 'system',
    });

    logger.info('ApiGateway initialized', { config: this.config, configFilePath: this.configFilePath });
  }

  private async initializeConfig(): Promise<void> {
    try {
      const loadedConfig = await this.loadConfigFromFile();
      if (loadedConfig) {
        this.config = { ...this.config, ...loadedConfig.tracing };
        if (loadedConfig.routes) {
          this.routes = loadedConfig.routes;
        }
        logger.info('Gateway config loaded from file', { path: this.configFilePath });
      }
    } catch (error) {
      logger.warn('Failed to load gateway config from file, using defaults', { error });
    }
  }

  private async loadConfigFromFile(): Promise<{ tracing?: Partial<TracingConfig>; routes?: GatewayRoute[] } | null> {
    const absolutePath = path.resolve(this.configFilePath);
    if (!await fs.pathExists(absolutePath)) {
      return null;
    }

    const content = await fs.readFile(absolutePath, 'utf-8');
    let parsed: any;

    if (absolutePath.endsWith('.yaml') || absolutePath.endsWith('.yml')) {
      parsed = yaml.parse(content);
    } else if (absolutePath.endsWith('.json')) {
      parsed = JSON.parse(content);
    } else {
      return null;
    }

    return parsed;
  }

  private startConfigWatcher(): void {
    const absolutePath = path.resolve(this.configFilePath);
    const configDir = path.dirname(absolutePath);
    
    if (!fs.pathExistsSync(configDir)) {
      return;
    }

    this.fileWatcher = fs.watch(configDir, (eventType, filename) => {
      if (filename && (filename === path.basename(absolutePath))) {
        logger.debug('Config file change detected', { eventType, filename });
        this.reloadConfig().catch(error => {
          logger.error('Failed to reload config', { error });
        });
      }
    });

    logger.debug('Config file watcher started', { path: absolutePath });
  }

  private async reloadConfig(): Promise<void> {
    const loadedConfig = await this.loadConfigFromFile();
    if (!loadedConfig) return;

    const oldConfig = { ...this.config };
    const oldRoutes = [...this.routes];

    if (loadedConfig.tracing) {
      this.config = { ...this.config, ...loadedConfig.tracing };
    }
    if (loadedConfig.routes) {
      this.routes = loadedConfig.routes;
    }

    this.currentVersion++;
    this.configVersions.push({
      version: this.currentVersion,
      config: { ...this.config },
      routes: [...this.routes],
      appliedAt: currentTimestamp(),
      appliedBy: 'file_watcher',
    });

    if (this.configVersions.length > 50) {
      this.configVersions = this.configVersions.slice(-50);
    }

    const changeEvent: ConfigChangeEvent = {
      oldConfig,
      newConfig: { ...this.config },
      oldRoutes,
      newRoutes: [...this.routes],
      timestamp: currentTimestamp(),
    };

    eventBus.emit('gateway.config.updated', changeEvent);
    logger.info('Gateway config reloaded', { version: this.currentVersion });
  }

  private startRateLimitCleanup(): void {
    this.rateLimitCleanupTimer = setInterval(() => {
      const now = Date.now();
      const windowMs = 60 * 1000;
      
      for (const key of Object.keys(this.rateLimitState)) {
        if (now - this.rateLimitState[key].windowStart > windowMs) {
          delete this.rateLimitState[key];
        }
      }
    }, 60000);
  }

  private checkRateLimit(route: GatewayRoute, clientIp: string): boolean {
    if (!route.rateLimit) return true;

    const key = `${route.path}:${route.method}:${clientIp}`;
    const now = Date.now();
    const windowMs = 60 * 1000;

    if (!this.rateLimitState[key] || now - this.rateLimitState[key].windowStart > windowMs) {
      this.rateLimitState[key] = {
        count: 1,
        windowStart: now,
      };
      return true;
    }

    this.rateLimitState[key].count++;
    
    if (this.rateLimitState[key].count > route.rateLimit.requestsPerMinute) {
      return false;
    }

    return true;
  }

  middleware(): (req: Request, res: Response, next: NextFunction) => void {
    return (req: Request, res: Response, next: NextFunction) => {
      req.startTime = Date.now();
      
      if (!this.config.enabled) {
        return next();
      }

      const route = this.findMatchingRoute(req.path, req.method);
      if (route && !route.enabled) {
        return res.status(404).json({
          code: 404,
          message: 'Route is disabled',
        });
      }

      if (route && route.rateLimit) {
        const clientIp = this.getClientIp(req);
        if (!this.checkRateLimit(route, clientIp)) {
          logger.warn('Rate limit exceeded', { path: req.path, clientIp });
          return res.status(429).json({
            code: 429,
            message: 'Too many requests',
            retryAfter: 60,
          });
        }
      }

      const traceId = req.headers['x-trace-id'] as string || uuidv4();
      const parentSpanId = req.headers['x-span-id'] as string;
      const spanId = uuidv4();

      req.traceContext = {
        traceId,
        spanId,
        parentSpanId,
        serviceName: 'data-transform-core',
        tags: {},
      };

      res.setHeader('X-Trace-Id', traceId);
      res.setHeader('X-Span-Id', spanId);
      res.setHeader('X-Config-Version', String(this.currentVersion));

      const sampleDecision = Math.random() < this.config.sampleRate;
      if (!sampleDecision) {
        return next();
      }

      if (this.config.logHeaders) {
        req.traceContext.tags['headers'] = JSON.stringify(req.headers);
      }

      if (this.config.logBody && req.body) {
        const bodyStr = typeof req.body === 'string' ? req.body : JSON.stringify(req.body);
        if (bodyStr.length <= this.config.maxBodySize) {
          req.traceContext.tags['body'] = bodyStr;
        } else {
          req.traceContext.tags['body'] = bodyStr.substring(0, this.config.maxBodySize) + '...';
        }
      }

      res.on('finish', () => {
        this.logRequest(req, res);
      });

      res.on('close', () => {
        if (!res.headersSent) {
          this.logRequest(req, res, 'Connection closed');
        }
      });

      next();
    };
  }

  private findMatchingRoute(path: string, method: string): GatewayRoute | undefined {
    return this.routes.find(route => {
      if (route.method.toUpperCase() !== method.toUpperCase()) return false;
      
      if (route.path.includes(':')) {
        const routeParts = route.path.split('/');
        const pathParts = path.split('/');
        
        if (routeParts.length !== pathParts.length) return false;
        
        for (let i = 0; i < routeParts.length; i++) {
          if (routeParts[i].startsWith(':')) continue;
          if (routeParts[i] !== pathParts[i]) return false;
        }
        return true;
      }
      
      return route.path === path;
    });
  }

  private logRequest(req: Request, res: Response, error?: string): void {
    const latency = Date.now() - (req.startTime || Date.now());
    const traceContext = req.traceContext!;

    const log: RequestLog = {
      traceId: traceContext.traceId,
      parentSpanId: traceContext.parentSpanId,
      spanId: traceContext.spanId,
      method: req.method,
      path: req.path,
      query: req.query as Record<string, any>,
      statusCode: res.statusCode,
      latency,
      clientIp: this.getClientIp(req),
      userAgent: req.get('User-Agent') || '',
      timestamp: currentTimestamp(),
      error,
      tags: traceContext.tags,
    };

    this.requestLogs.push(log);
    if (this.requestLogs.length > this.maxLogs) {
      this.requestLogs.shift();
    }

    this.emitTrace(log);

    if (res.statusCode >= 500 || error) {
      logger.error('Request failed', log);
    } else if (res.statusCode >= 400) {
      logger.warn('Request completed with warning', log);
    } else {
      logger.info('Request completed', {
        traceId: log.traceId,
        method: log.method,
        path: log.path,
        status: log.statusCode,
        latency: log.latency,
      });
    }
  }

  private getClientIp(req: Request): string {
    const forwarded = req.headers['x-forwarded-for'];
    if (typeof forwarded === 'string') {
      return forwarded.split(',')[0].trim();
    }
    if (Array.isArray(forwarded)) {
      return forwarded[0];
    }
    return req.ip || req.connection.remoteAddress || '';
  }

  private emitTrace(log: RequestLog): void {
    eventBus.emit('trace.request', log);
    
    if (log.latency > 1000) {
      eventBus.emit('trace.slow_request', log);
    }
  }

  getTrace(traceId: string): RequestLog[] {
    return this.requestLogs.filter(log => log.traceId === traceId);
  }

  getRecentLogs(limit: number = 100): RequestLog[] {
    return this.requestLogs.slice(-limit);
  }

  getMetrics() {
    const totalRequests = this.requestLogs.length;
    const errorCount = this.requestLogs.filter(l => l.statusCode >= 500).length;
    const totalLatency = this.requestLogs.reduce((sum, l) => sum + l.latency, 0);

    return {
      totalRequests,
      errorRate: totalRequests > 0 ? errorCount / totalRequests : 0,
      averageLatency: totalRequests > 0 ? totalLatency / totalRequests : 0,
      p99Latency: this.calculatePercentile(99),
      p95Latency: this.calculatePercentile(95),
      p50Latency: this.calculatePercentile(50),
      configVersion: this.currentVersion,
      activeRoutes: this.routes.filter(r => r.enabled).length,
    };
  }

  private calculatePercentile(percentile: number): number {
    if (this.requestLogs.length === 0) return 0;
    
    const latencies = [...this.requestLogs]
      .map(l => l.latency)
      .sort((a, b) => a - b);
    
    const index = Math.ceil((percentile / 100) * latencies.length) - 1;
    return latencies[index] || 0;
  }

  clearLogs(): void {
    this.requestLogs = [];
    logger.info('Request logs cleared');
  }

  getCurrentConfig(): TracingConfig {
    return { ...this.config };
  }

  getRoutes(): GatewayRoute[] {
    return [...this.routes];
  }

  getConfigVersions(): ConfigVersion[] {
    return [...this.configVersions];
  }

  getCurrentVersion(): number {
    return this.currentVersion;
  }

  async updateConfig(newConfig: Partial<TracingConfig>, appliedBy: string = 'admin'): Promise<TracingConfig> {
    const oldConfig = { ...this.config };
    this.config = { ...this.config, ...newConfig };

    this.currentVersion++;
    this.configVersions.push({
      version: this.currentVersion,
      config: { ...this.config },
      routes: [...this.routes],
      appliedAt: currentTimestamp(),
      appliedBy,
    });

    if (this.configVersions.length > 50) {
      this.configVersions = this.configVersions.slice(-50);
    }

    eventBus.emit('gateway.config.updated', {
      oldConfig,
      newConfig: { ...this.config },
      oldRoutes: [...this.routes],
      newRoutes: [...this.routes],
      timestamp: currentTimestamp(),
    });

    logger.info('Gateway config updated', { version: this.currentVersion, appliedBy });
    return { ...this.config };
  }

  async updateRoutes(newRoutes: GatewayRoute[], appliedBy: string = 'admin'): Promise<GatewayRoute[]> {
    const oldRoutes = [...this.routes];
    this.routes = newRoutes;

    this.currentVersion++;
    this.configVersions.push({
      version: this.currentVersion,
      config: { ...this.config },
      routes: [...this.routes],
      appliedAt: currentTimestamp(),
      appliedBy,
    });

    if (this.configVersions.length > 50) {
      this.configVersions = this.configVersions.slice(-50);
    }

    eventBus.emit('gateway.routes.updated', {
      oldRoutes,
      newRoutes: [...this.routes],
      timestamp: currentTimestamp(),
    });

    logger.info('Gateway routes updated', { version: this.currentVersion, appliedBy, routeCount: newRoutes.length });
    return [...this.routes];
  }

  async rollbackToVersion(version: number): Promise<boolean> {
    const targetVersion = this.configVersions.find(v => v.version === version);
    if (!targetVersion) {
      logger.warn('Config version not found for rollback', { version });
      return false;
    }

    const oldConfig = { ...this.config };
    const oldRoutes = [...this.routes];

    this.config = { ...targetVersion.config };
    this.routes = [...targetVersion.routes];

    this.currentVersion++;
    this.configVersions.push({
      version: this.currentVersion,
      config: { ...this.config },
      routes: [...this.routes],
      appliedAt: currentTimestamp(),
      appliedBy: `rollback_to_v${version}`,
    });

    eventBus.emit('gateway.config.rolledback', {
      fromVersion: this.currentVersion - 1,
      toVersion: version,
      timestamp: currentTimestamp(),
    });

    logger.info('Gateway config rolled back', { fromVersion: this.currentVersion - 1, toVersion: version });
    return true;
  }

  async addRoute(route: GatewayRoute, appliedBy: string = 'admin'): Promise<GatewayRoute> {
    const existingIndex = this.routes.findIndex(
      r => r.path === route.path && r.method === route.method,
    );

    if (existingIndex >= 0) {
      this.routes[existingIndex] = route;
    } else {
      this.routes.push(route);
    }

    await this.updateRoutes(this.routes, appliedBy);
    return route;
  }

  async removeRoute(path: string, method: string, appliedBy: string = 'admin'): Promise<boolean> {
    const initialLength = this.routes.length;
    this.routes = this.routes.filter(
      r => !(r.path === path && r.method === method),
    );
    
    if (this.routes.length !== initialLength) {
      await this.updateRoutes(this.routes, appliedBy);
      return true;
    }
    return false;
  }

  onConfigChange(handler: (event: ConfigChangeEvent) => void): void {
    eventBus.on('gateway.config.updated', handler);
  }

  stop(): void {
    if (this.fileWatcher) {
      this.fileWatcher.close();
    }
    if (this.rateLimitCleanupTimer) {
      clearInterval(this.rateLimitCleanupTimer);
    }
    logger.info('ApiGateway stopped');
  }
}

export const apiGateway = new ApiGateway();
