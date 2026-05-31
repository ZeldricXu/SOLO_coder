import { RouteConfig, Protocol } from '../types';
import { logger } from '../logging';
import { v4 as uuidv4 } from 'uuid';
import axios from 'axios';

export interface GatewayRequest {
  id: string;
  method: string;
  path: string;
  headers: Record<string, string>;
  body?: any;
  query: Record<string, string>;
  timestamp: string;
}

export interface GatewayResponse {
  statusCode: number;
  headers: Record<string, string>;
  body: any;
  duration: number;
  fromCache?: boolean;
}

export interface RateLimitConfig {
  requestsPerSecond: number;
  burstSize: number;
}

export interface GatewayMetrics {
  totalRequests: number;
  successCount: number;
  errorCount: number;
  averageLatency: number;
  p95Latency: number;
  p99Latency: number;
}

export class APIGateway {
  private routes: Map<string, RouteConfig> = new Map();
  private rateLimits: Map<string, RateLimitConfig> = new Map();
  private requestCounts: Map<string, number[]> = new Map();
  private metrics: GatewayMetrics = {
    totalRequests: 0,
    successCount: 0,
    errorCount: 0,
    averageLatency: 0,
    p95Latency: 0,
    p99Latency: 0
  };
  private latencies: number[] = [];

  addRoute(route: RouteConfig): void {
    const routeKey = `${route.method}:${route.path}`;
    this.routes.set(routeKey, route);
    logger.info('Route added', { path: route.path, method: route.method, target: route.target });
  }

  removeRoute(method: string, path: string): void {
    const routeKey = `${method}:${path}`;
    this.routes.delete(routeKey);
  }

  setRateLimit(routeKey: string, config: RateLimitConfig): void {
    this.rateLimits.set(routeKey, config);
  }

  async routeRequest(request: Omit<GatewayRequest, 'id' | 'timestamp'>): Promise<GatewayResponse> {
    const startTime = Date.now();
    const gatewayRequest: GatewayRequest = {
      ...request,
      id: `req_${uuidv4()}`,
      timestamp: new Date().toISOString()
    };

    this.metrics.totalRequests++;
    logger.info('Routing request', { requestId: gatewayRequest.id, path: gatewayRequest.path, method: gatewayRequest.method });

    try {
      const route = this.findMatchingRoute(gatewayRequest.method, gatewayRequest.path);
      if (!route) {
        return this.createErrorResponse(404, 'Route not found', startTime);
      }

      const rateLimited = this.checkRateLimit(route);
      if (rateLimited) {
        return this.createErrorResponse(429, 'Rate limit exceeded', startTime);
      }

      const response = await this.forwardRequest(gatewayRequest, route);
      this.metrics.successCount++;
      this.recordLatency(Date.now() - startTime);
      return response;
    } catch (error) {
      this.metrics.errorCount++;
      logger.error('Request routing failed', error as Error, { requestId: gatewayRequest.id });
      return this.createErrorResponse(500, 'Internal server error', startTime);
    }
  }

  private findMatchingRoute(method: string, path: string): RouteConfig | undefined {
    const exactKey = `${method}:${path}`;
    if (this.routes.has(exactKey)) {
      return this.routes.get(exactKey);
    }

    for (const [key, route] of this.routes) {
      const [routeMethod, routePath] = key.split(':');
      if (routeMethod === method && this.matchPath(routePath, path)) {
        return route;
      }
    }

    return undefined;
  }

  private matchPath(routePath: string, requestPath: string): boolean {
    const routeParts = routePath.split('/');
    const requestParts = requestPath.split('/');

    if (routeParts.length !== requestParts.length) {
      return false;
    }

    for (let i = 0; i < routeParts.length; i++) {
      if (routeParts[i].startsWith(':')) {
        continue;
      }
      if (routeParts[i] !== requestParts[i]) {
        return false;
      }
    }

    return true;
  }

  private checkRateLimit(route: RouteConfig): boolean {
    const routeKey = `${route.method}:${route.path}`;
    const rateLimit = this.rateLimits.get(routeKey);
    if (!rateLimit) return false;

    const now = Date.now();
    const counts = this.requestCounts.get(routeKey) || [];
    const recentCounts = counts.filter(t => now - t < 1000);
    
    if (recentCounts.length >= rateLimit.requestsPerSecond) {
      return true;
    }

    recentCounts.push(now);
    this.requestCounts.set(routeKey, recentCounts);
    return false;
  }

  private async forwardRequest(request: GatewayRequest, route: RouteConfig): Promise<GatewayResponse> {
    const startTime = Date.now();
    const timeout = route.timeout || 30000;

    try {
      const targetUrl = `${route.target}${request.path}`;
      const response = await axios({
        method: request.method.toLowerCase(),
        url: targetUrl,
        headers: request.headers,
        data: request.body,
        params: request.query,
        timeout: timeout
      });

      return {
        statusCode: response.status,
        headers: response.headers as Record<string, string>,
        body: response.data,
        duration: Date.now() - startTime
      };
    } catch (error) {
      if ((error as any).code === 'ECONNABORTED') {
        return this.createErrorResponse(504, 'Gateway timeout', startTime);
      }
      throw error;
    }
  }

  private createErrorResponse(statusCode: number, message: string, startTime: number): GatewayResponse {
    return {
      statusCode,
      headers: { 'Content-Type': 'application/json' },
      body: { error: message },
      duration: Date.now() - startTime
    };
  }

  private recordLatency(latency: number): void {
    this.latencies.push(latency);
    if (this.latencies.length > 1000) {
      this.latencies.shift();
    }
    
    this.metrics.averageLatency = this.latencies.reduce((a, b) => a + b, 0) / this.latencies.length;
    
    const sorted = [...this.latencies].sort((a, b) => a - b);
    this.metrics.p95Latency = sorted[Math.floor(sorted.length * 0.95)] || 0;
    this.metrics.p99Latency = sorted[Math.floor(sorted.length * 0.99)] || 0;
  }

  convertProtocol(request: GatewayRequest, targetProtocol: Protocol): any {
    switch (targetProtocol) {
      case 'http':
        return request;
      case 'grpc':
        return this.toGrpc(request);
      case 'mqtt':
        return this.toMqtt(request);
      case 'tcp':
        return this.toTcp(request);
      default:
        return request;
    }
  }

  private toGrpc(request: GatewayRequest): any {
    return {
      service: request.path.split('/')[1],
      method: request.path.split('/')[2],
      payload: request.body
    };
  }

  private toMqtt(request: GatewayRequest): any {
    return {
      topic: request.path,
      payload: JSON.stringify(request.body),
      qos: 1
    };
  }

  private toTcp(request: GatewayRequest): any {
    return {
      data: JSON.stringify(request),
      encoding: 'utf8'
    };
  }

  getMetrics(): GatewayMetrics {
    return { ...this.metrics };
  }

  getRoutes(): RouteConfig[] {
    return Array.from(this.routes.values());
  }
}

export const createAPIGateway = (): APIGateway => new APIGateway();
