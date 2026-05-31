import { GatewayConfig, GatewayMetrics, ProxyRequest, Route } from './types';
import { Router } from './router';
import { HttpRequestHandler } from './handlers/RequestHandler';
import { IRequestHandler, IGatewayMiddleware } from './interfaces';
import { logger } from '../utils/common';
import express, { Express, Request, Response, NextFunction } from 'express';

export class APIGateway {
  private app: Express;
  private config: GatewayConfig;
  private router: Router;
  private handlers: IRequestHandler[] = [];
  private server: any;
  private metrics: GatewayMetrics;
  private requestTimestamps: number[] = [];
  private latencies: number[] = [];
  private errorCount = 0;
  private requestCount = 0;
  private activeConnections = 0;

  constructor(config: Partial<GatewayConfig> = {}) {
    this.config = {
      port: 8080,
      host: '0.0.0.0',
      routes: [],
      globalTimeoutMs: 60000,
      ...config,
    } as GatewayConfig;

    this.router = new Router();
    this.app = express();
    this.metrics = this.initializeMetrics();

    this.registerHandler(new HttpRequestHandler());

    this.setupMiddleware();
    this.loadRoutes();
  }

  private initializeMetrics(): GatewayMetrics {
    return {
      totalRequests: 0,
      activeConnections: 0,
      errorRate: 0,
      averageLatencyMs: 0,
      p95LatencyMs: 0,
      p99LatencyMs: 0,
      requestsPerSecond: 0,
      bytesTransferred: 0,
    };
  }

  private setupMiddleware(): void {
    this.app.use(express.json());
    this.app.use(express.urlencoded({ extended: true }));

    if (this.config.cors?.enabled) {
      this.app.use((req: Request, res: Response, next: NextFunction) => {
        const cors = this.config.cors!;
        res.header('Access-Control-Allow-Origin', cors.origins.join(','));
        res.header('Access-Control-Allow-Methods', cors.methods.join(','));
        res.header('Access-Control-Allow-Headers', cors.headers.join(','));
        if (req.method === 'OPTIONS') {
          return res.sendStatus(200);
        }
        next();
      });
    }

    this.app.use((req: Request, res: Response, next: NextFunction) => {
      this.activeConnections++;
      this.metrics.activeConnections = this.activeConnections;
      res.on('finish', () => {
        this.activeConnections--;
        this.metrics.activeConnections = this.activeConnections;
      });
      next();
    });
  }

  private loadRoutes(): void {
    for (const route of this.config.routes) {
      this.router.addRoute(route);
    }

    this.app.all('*', async (req: Request, res: Response) => {
      await this.handleRequest(req, res);
    });
  }

  registerHandler(handler: IRequestHandler): void {
    this.handlers.push(handler);
    logger.info(`Request handler registered`, { protocols: handler.constructor.name });
  }

  useMiddleware(middleware: IGatewayMiddleware): void {
    for (const handler of this.handlers) {
      if ('use' in handler && typeof (handler as any).use) {
        (handler as any).use(middleware);
      }
    }
  }

  private getHandlerForRoute(route: Route): IRequestHandler | undefined {
    return this.handlers.find(h => h.canHandle(route));
  }

  private async handleRequest(req: Request, res: Response): Promise<void> {
    const startTime = Date.now();
    this.requestCount++;
    this.metrics.totalRequests = this.requestCount;

    try {
      const match = this.router.matchRoute(req.method, req.path);

      if (!match) {
        res.status(404).json({
          error: 'Not Found',
          message: `No route found for ${req.method} ${req.path}`,
        });
        return;
      }

      const { route } = match;

      if (!route.enabled) {
        res.status(503).json({
          error: 'Service Unavailable',
          message: 'Route is disabled',
        });
        return;
      }

      const handler = this.getHandlerForRoute(route);
      if (!handler) {
        res.status(501).json({
          error: 'Not Implemented',
          message: `No handler found for protocol ${route.protocol}`,
        });
        return;
      }

      const proxyRequest: ProxyRequest = {
        method: req.method,
        path: req.path,
        headers: req.headers as Record<string, string>,
        body: req.body,
        query: req.query as Record<string, string>,
      };

      const proxyResponse = await handler.handle(proxyRequest, route);

      const latency = Date.now() - startTime;
      this.recordLatency(latency);

      if (proxyResponse.status >= 400) {
        this.errorCount++;
      }

      this.metrics.errorRate = this.errorCount / this.requestCount;

      for (const [key, value] of Object.entries(proxyResponse.headers)) {
        res.setHeader(key, value);
      }

      res.status(proxyResponse.status).json(proxyResponse.body);

    } catch (error) {
      logger.error(`Gateway error`, {
        error: error instanceof Error ? error.message : 'Unknown error',
        path: req.path,
        method: req.method,
      });

      res.status(500).json({
        error: 'Internal Server Error',
        message: error instanceof Error ? error.message : 'Unknown error',
      });
    }
  }

  private recordLatency(latencyMs: number): void {
    this.latencies.push(latencyMs);
    if (this.latencies.length > 10000) {
      this.latencies.shift();
    }

    const sorted = [...this.latencies].sort((a, b) => a - b);
    this.metrics.averageLatencyMs = sorted.reduce((a, b) => a + b, 0) / sorted.length;
    this.metrics.p95LatencyMs = sorted[Math.floor(sorted.length * 0.95)] || 0;
    this.metrics.p99LatencyMs = sorted[Math.floor(sorted.length * 0.99)] || 0;
  }

  addRoute(route: Omit<Route, 'id'>): Route {
    return this.router.addRoute(route);
  }

  removeRoute(id: string): boolean {
    return this.router.removeRoute(id);
  }

  listRoutes(): Route[] {
    return this.router.listRoutes();
  }

  getMetrics(): GatewayMetrics {
    const now = Date.now();
    this.requestTimestamps.push(now);
    const recent = this.requestTimestamps.filter(t => now - t < 60000);
    this.metrics.requestsPerSecond = recent.length / 60;

    return { ...this.metrics };
  }

  async start(): Promise<void> {
    return new Promise((resolve) => {
      this.server = this.app.listen(this.config.port, this.config.host, () => {
        logger.info(`API Gateway started`, {
          host: this.config.host,
          port: this.config.port,
          routes: this.router.listRoutes().length,
          handlers: this.handlers.length,
        });
        resolve();
      });
    });
  }

  async stop(): Promise<void> {
    if (this.server) {
      return new Promise((resolve, reject) => {
        this.server.close((err: Error) => {
          if (err) reject(err);
          else {
            logger.info(`API Gateway stopped`);
            resolve();
          }
        });
      });
    }
  }

  getApp(): Express {
    return this.app;
  }

  getHandlers(): IRequestHandler[] {
    return [...this.handlers];
  }
}

export default APIGateway;
