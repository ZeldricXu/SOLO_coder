import { v4 as uuidv4 } from 'uuid';
import { Authenticator } from './authenticator';
import { RateLimiter } from './rate-limiter';
import { CircuitBreaker } from './circuit-breaker';
import { GatewayConfig, Route, GatewayRequest, GatewayResponse } from './types';
import { BaseService } from '../common/base-service';
import { createErrorResult, fromError } from '../common/errors';

export class ApiGateway extends BaseService {
  private readonly routes: Map<string, Route> = new Map();
  private readonly authenticator: Authenticator;
  private readonly rateLimiter: RateLimiter;
  private readonly circuitBreaker: CircuitBreaker;
  private readonly config: GatewayConfig;

  constructor(config: GatewayConfig) {
    super('ApiGateway');
    this.config = config;
    this.authenticator = new Authenticator(config.jwtSecret, config.jwtExpiresIn);
    this.rateLimiter = new RateLimiter(config.rateLimit, config.redisUrl);
    this.circuitBreaker = new CircuitBreaker({
      failureThreshold: config.circuitBreakerThreshold,
      recoveryTimeout: config.circuitBreakerTimeout,
    });
  }

  registerRoute(route: Route): void {
    this.assertNotDestroyed();
    const key = this.getRouteKey(route.method, route.path);
    this.routes.set(key, route);
  }

  async handleRequest(req: {
    method: string;
    path: string;
    headers: Record<string, string>;
    query?: Record<string, string>;
    params?: Record<string, string>;
    body?: unknown;
  }): Promise<GatewayResponse> {
    this.assertNotDestroyed();

    const request = this.createGatewayRequest(req);

    try {
      const route = this.findMatchingRoute(request.method, request.path);
      if (!route) {
        return this.createErrorResponse(404, 'Route not found');
      }

      if (route.params) {
        request.params = { ...request.params, ...route.params };
      }

      if (route.authRequired) {
        const authResult = await this.authenticator.authenticateRequest(request.headers['authorization']);
        if (!authResult.success) {
          return this.createErrorResponse(401, authResult.error || 'Unauthorized');
        }
        request.auth = authResult.auth!;

        if (route.roles && !this.authenticator.checkRoles(request.auth, route.roles)) {
          return this.createErrorResponse(403, 'Insufficient roles');
        }

        if (route.permissions && !this.authenticator.checkPermissions(request.auth, route.permissions)) {
          return this.createErrorResponse(403, 'Insufficient permissions');
        }
      }

      const clientId = this.getClientIdentifier(request);
      const rateLimitResult = await this.rateLimiter.checkLimit(clientId);
      if (!rateLimitResult.allowed) {
        return this.createResponse(429, {
          error: 'Rate limit exceeded',
          retryAfter: rateLimitResult.info.resetTime,
        });
      }

      return this.executeWithCircuitBreaker(request, route);
    } catch (error) {
      const errorResult = fromError(error);
      return this.createErrorResponse(500, errorResult.error || 'Internal server error');
    }
  }

  private createGatewayRequest(req: {
    method: string;
    path: string;
    headers: Record<string, string>;
    query?: Record<string, string>;
    params?: Record<string, string>;
    body?: unknown;
  }): GatewayRequest {
    return {
      id: uuidv4(),
      method: req.method,
      path: req.path,
      headers: req.headers,
      query: req.query || {},
      params: req.params || {},
      body: req.body,
      timestamp: Date.now(),
      traceId: uuidv4(),
    };
  }

  private getRouteKey(method: string, path: string): string {
    return `${method}:${path}`;
  }

  private findMatchingRoute(method: string, path: string): Route | undefined {
    const exactKey = this.getRouteKey(method, path);
    if (this.routes.has(exactKey)) {
      return this.routes.get(exactKey);
    }

    for (const [key, route] of this.routes.entries()) {
      const colonIndex = key.indexOf(':');
      const routeMethod = key.substring(0, colonIndex);
      const routePath = key.substring(colonIndex + 1);
      if (routeMethod !== method) continue;

      const matchResult = this.matchRoutePattern(routePath, path);
      if (matchResult.matched) {
        return { ...route, params: matchResult.params };
      }
    }

    return undefined;
  }

  private matchRoutePattern(
    routePath: string,
    requestPath: string
  ): { matched: boolean; params: Record<string, string> } {
    const routeParts = routePath.split('/');
    const pathParts = requestPath.split('/');

    if (routeParts.length !== pathParts.length) {
      return { matched: false, params: {} };
    }

    const params: Record<string, string> = {};

    for (let i = 0; i < routeParts.length; i++) {
      if (routeParts[i].startsWith(':')) {
        params[routeParts[i].slice(1)] = pathParts[i];
      } else if (routeParts[i] !== pathParts[i]) {
        return { matched: false, params: {} };
      }
    }

    return { matched: true, params };
  }

  private getClientIdentifier(request: GatewayRequest): string {
    return (
      request.headers['x-forwarded-for'] ||
      request.headers['x-client-id'] ||
      request.auth?.user.id ||
      'anonymous'
    );
  }

  private async executeWithCircuitBreaker(
    request: GatewayRequest,
    route: Route
  ): Promise<GatewayResponse> {
    if (!this.config.enableCircuitBreaker) {
      return route.handler(request);
    }

    try {
      return await this.circuitBreaker.execute(() => route.handler(request));
    } catch (error) {
      if (error instanceof Error && error.message === 'Circuit breaker is open') {
        return this.createErrorResponse(503, 'Service temporarily unavailable');
      }
      throw error;
    }
  }

  private createResponse(statusCode: number, body: unknown): GatewayResponse {
    return {
      statusCode,
      headers: {
        'Content-Type': 'application/json',
      },
      body,
    };
  }

  private createErrorResponse(statusCode: number, error: string): GatewayResponse {
    return this.createResponse(statusCode, { error });
  }

  getAuthenticator(): Authenticator {
    return this.authenticator;
  }

  getRateLimiter(): RateLimiter {
    return this.rateLimiter;
  }

  getCircuitBreaker(): CircuitBreaker {
    return this.circuitBreaker;
  }

  override destroy(): void {
    this.authenticator.destroy();
    this.rateLimiter.destroy();
    super.destroy();
  }
}

export { Authenticator, RateLimiter, CircuitBreaker };
export * from './types';
