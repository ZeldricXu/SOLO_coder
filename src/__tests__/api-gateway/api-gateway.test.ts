import { ApiGateway } from '../../api-gateway';
import { Authenticator } from '../../api-gateway/authenticator';
import { RateLimiter } from '../../api-gateway/rate-limiter';
import { CircuitBreaker } from '../../api-gateway/circuit-breaker';

jest.mock('../../api-gateway/authenticator');
jest.mock('../../api-gateway/rate-limiter');
jest.mock('../../api-gateway/circuit-breaker');

describe('ApiGateway', () => {
  let gateway: ApiGateway;
  let mockAuthenticator: jest.Mocked<Authenticator>;
  let mockRateLimiter: jest.Mocked<RateLimiter>;
  let mockCircuitBreaker: jest.Mocked<CircuitBreaker>;

  beforeEach(() => {
    jest.clearAllMocks();

    mockAuthenticator = {
      authenticateRequest: jest.fn().mockResolvedValue({
        success: false,
        error: 'Unauthorized',
      }),
      checkRoles: jest.fn(),
      checkPermissions: jest.fn(),
      destroy: jest.fn(),
      on: jest.fn(),
      emit: jest.fn(),
    } as any;

    mockRateLimiter = {
      checkLimit: jest.fn(),
      resetLimit: jest.fn(),
      destroy: jest.fn(),
      on: jest.fn(),
      emit: jest.fn(),
    } as any;

    mockCircuitBreaker = {
      execute: jest.fn().mockImplementation((fn) => fn()),
      reset: jest.fn(),
      getState: jest.fn(),
      destroy: jest.fn(),
      on: jest.fn(),
      emit: jest.fn(),
    } as any;

    (Authenticator as unknown as jest.Mock).mockImplementation(() => mockAuthenticator);
    (RateLimiter as unknown as jest.Mock).mockImplementation(() => mockRateLimiter);
    (CircuitBreaker as unknown as jest.Mock).mockImplementation(() => mockCircuitBreaker);

    gateway = new ApiGateway({
      jwtSecret: 'test-secret',
      jwtExpiresIn: 3600,
      rateLimit: {
        windowMs: 60000,
        maxRequests: 100,
        keyPrefix: 'test:',
      },
      enableCircuitBreaker: true,
      circuitBreakerThreshold: 5,
      circuitBreakerTimeout: 30000,
    });
  });

  afterEach(() => {
    gateway?.destroy();
  });

  describe('Initialization', () => {
    it('should initialize with valid config', () => {
      expect(gateway).toBeDefined();
      expect(Authenticator).toHaveBeenCalledWith('test-secret', 3600);
      expect(RateLimiter).toHaveBeenCalledWith(
        { windowMs: 60000, maxRequests: 100, keyPrefix: 'test:' },
        undefined
      );
      expect(CircuitBreaker).toHaveBeenCalledWith({
        failureThreshold: 5,
        recoveryTimeout: 30000,
      });
    });

    it('should initialize with circuit breaker disabled', () => {
      const gw = new ApiGateway({
        jwtSecret: 'test-secret',
        jwtExpiresIn: 3600,
        rateLimit: {
          windowMs: 60000,
          maxRequests: 100,
          keyPrefix: 'test:',
        },
        enableCircuitBreaker: false,
        circuitBreakerThreshold: 5,
        circuitBreakerTimeout: 30000,
      });

      expect(gw).toBeDefined();
      gw.destroy();
    });
  });

  describe('Route Registration', () => {
    it('should register a route', () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: { data: 'test' },
      });

      gateway.registerRoute({
        method: 'GET',
        path: '/api/test',
        handler,
        authRequired: false,
      });

      const routes = (gateway as any).routes as Map<string, any>;
      expect(routes.size).toBe(1);
      expect(routes.has('GET:/api/test')).toBe(true);
    });

    it('should register multiple routes', () => {
      gateway.registerRoute({
        method: 'GET',
        path: '/api/users',
        handler: jest.fn(),
        authRequired: false,
      });

      gateway.registerRoute({
        method: 'POST',
        path: '/api/users',
        handler: jest.fn(),
        authRequired: true,
      });

      const routes = (gateway as any).routes as Map<string, any>;
      expect(routes.size).toBe(2);
    });

    it('should throw error when registering route after destroy', () => {
      gateway.destroy();
      expect(() =>
        gateway.registerRoute({
          method: 'GET',
          path: '/test',
          handler: jest.fn(),
          authRequired: false,
        })
      ).toThrow();
    });
  });

  describe('Route Matching', () => {
    it('should match exact routes', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: { data: 'exact' },
      });

      gateway.registerRoute({
        method: 'GET',
        path: '/health',
        handler,
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/health',
        headers: {},
      });

      expect(response.statusCode).toBe(200);
      expect(handler).toHaveBeenCalled();
    });

    it('should match routes with parameters', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: {},
      });

      gateway.registerRoute({
        method: 'GET',
        path: '/api/users/:id',
        handler,
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/users/123',
        headers: {},
      });

      expect(response.statusCode).toBe(200);
      expect(handler).toHaveBeenCalled();
      expect(handler.mock.calls[0][0].params.id).toBe('123');
    });

    it('should return 404 for non-existent route', async () => {
      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/non-existent',
        headers: {},
      });

      expect(response.statusCode).toBe(404);
      expect((response.body as any).error).toBe('Route not found');
    });

    it('should not match routes with different method', async () => {
      gateway.registerRoute({
        method: 'GET',
        path: '/api/test',
        handler: jest.fn(),
        authRequired: false,
      });

      const response = await gateway.handleRequest({
        method: 'POST',
        path: '/api/test',
        headers: {},
      });

      expect(response.statusCode).toBe(404);
    });
  });

  describe('Authentication', () => {
    it('should return 401 for missing auth header on protected route', async () => {
      gateway.registerRoute({
        method: 'GET',
        path: '/api/protected',
        handler: jest.fn(),
        authRequired: true,
      });

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/protected',
        headers: {},
      });

      expect(response.statusCode).toBe(401);
    });

    it('should return 401 for invalid token', async () => {
      gateway.registerRoute({
        method: 'GET',
        path: '/api/protected',
        handler: jest.fn(),
        authRequired: true,
      });

      mockAuthenticator.authenticateRequest.mockResolvedValue({
        success: false,
        error: 'Invalid token',
      });

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/protected',
        headers: { authorization: 'Bearer invalid' },
      });

      expect(response.statusCode).toBe(401);
      expect((response.body as any).error).toBe('Invalid token');
    });

    it('should allow access with valid token', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: { data: 'protected' },
      });

      gateway.registerRoute({
        method: 'GET',
        path: '/api/protected',
        handler,
        authRequired: true,
      });

      mockAuthenticator.authenticateRequest.mockResolvedValue({
        success: true,
        auth: {
          authenticated: true,
          user: {
            id: '1',
            username: 'test',
            email: 'test@example.com',
            roles: [],
            permissions: [],
          },
          token: 'valid-token',
        },
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/protected',
        headers: { authorization: 'Bearer valid-token' },
      });

      expect(response.statusCode).toBe(200);
      expect(handler).toHaveBeenCalled();
    });

    it('should allow access to public routes without auth', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: { data: 'public' },
      });

      gateway.registerRoute({
        method: 'GET',
        path: '/api/public',
        handler,
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/public',
        headers: {},
      });

      expect(response.statusCode).toBe(200);
      expect(mockAuthenticator.authenticateRequest).not.toHaveBeenCalled();
    });
  });

  describe('Authorization', () => {
    it('should return 403 for insufficient roles', async () => {
      gateway.registerRoute({
        method: 'GET',
        path: '/api/admin',
        handler: jest.fn(),
        authRequired: true,
        roles: ['admin'],
      });

      mockAuthenticator.authenticateRequest.mockResolvedValue({
        success: true,
        auth: {
          authenticated: true,
          user: {
            id: '1',
            username: 'user',
            email: 'user@example.com',
            roles: ['user'],
            permissions: [],
          },
          token: 'token',
        },
      });

      mockAuthenticator.checkRoles.mockReturnValue(false);

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/admin',
        headers: { authorization: 'Bearer token' },
      });

      expect(response.statusCode).toBe(403);
      expect((response.body as any).error).toBe('Insufficient roles');
    });

    it('should return 403 for insufficient permissions', async () => {
      gateway.registerRoute({
        method: 'POST',
        path: '/api/write',
        handler: jest.fn(),
        authRequired: true,
        permissions: ['write'],
      });

      mockAuthenticator.authenticateRequest.mockResolvedValue({
        success: true,
        auth: {
          authenticated: true,
          user: {
            id: '1',
            username: 'user',
            email: 'user@example.com',
            roles: [],
            permissions: ['read'],
          },
          token: 'token',
        },
      });

      mockAuthenticator.checkPermissions.mockReturnValue(false);

      const response = await gateway.handleRequest({
        method: 'POST',
        path: '/api/write',
        headers: { authorization: 'Bearer token' },
      });

      expect(response.statusCode).toBe(403);
      expect((response.body as any).error).toBe('Insufficient permissions');
    });

    it('should allow access with sufficient roles', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: { data: 'admin' },
      });

      gateway.registerRoute({
        method: 'GET',
        path: '/api/admin',
        handler,
        authRequired: true,
        roles: ['admin'],
      });

      mockAuthenticator.authenticateRequest.mockResolvedValue({
        success: true,
        auth: {
          authenticated: true,
          user: {
            id: '1',
            username: 'admin',
            email: 'admin@example.com',
            roles: ['admin'],
            permissions: [],
          },
          token: 'token',
        },
      });

      mockAuthenticator.checkRoles.mockReturnValue(true);
      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/admin',
        headers: { authorization: 'Bearer token' },
      });

      expect(response.statusCode).toBe(200);
      expect(handler).toHaveBeenCalled();
    });
  });

  describe('Rate Limiting', () => {
    it('should return 429 when rate limit exceeded', async () => {
      gateway.registerRoute({
        method: 'GET',
        path: '/api/rate-limited',
        handler: jest.fn(),
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: false,
        info: { remaining: 0, resetTime: 30, limit: 100 },
      });

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/rate-limited',
        headers: {},
      });

      expect(response.statusCode).toBe(429);
      expect((response.body as any).error).toBe('Rate limit exceeded');
      expect((response.body as any).retryAfter).toBe(30);
    });

    it('should pass request when within rate limit', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: { data: 'ok' },
      });

      gateway.registerRoute({
        method: 'GET',
        path: '/api/ok',
        handler,
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/ok',
        headers: {},
      });

      expect(response.statusCode).toBe(200);
      expect(handler).toHaveBeenCalled();
    });

    it('should use client identifier from headers', async () => {
      gateway.registerRoute({
        method: 'GET',
        path: '/api/client-id',
        handler: jest.fn(),
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      await gateway.handleRequest({
        method: 'GET',
        path: '/api/client-id',
        headers: {
          'x-forwarded-for': '192.168.1.1',
        },
      });

      expect(mockRateLimiter.checkLimit).toHaveBeenCalledWith('192.168.1.1');
    });
  });

  describe('Circuit Breaker', () => {
    it('should return 503 when circuit is open', async () => {
      gateway.registerRoute({
        method: 'GET',
        path: '/api/failing',
        handler: jest.fn(),
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      mockCircuitBreaker.execute.mockRejectedValue(new Error('Circuit breaker is open'));

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/failing',
        headers: {},
      });

      expect(response.statusCode).toBe(503);
      expect((response.body as any).error).toBe('Service temporarily unavailable');
    });

    it('should execute handler through circuit breaker', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: { data: 'ok' },
      });

      gateway.registerRoute({
        method: 'GET',
        path: '/api/through',
        handler,
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      mockCircuitBreaker.execute.mockImplementation((fn) => fn());

      const response = await gateway.handleRequest({
        method: 'GET',
        path: '/api/through',
        headers: {},
      });

      expect(response.statusCode).toBe(200);
      expect(mockCircuitBreaker.execute).toHaveBeenCalled();
    });

    it('should bypass circuit breaker when disabled', () => {
      const gw = new ApiGateway({
        jwtSecret: 'test-secret',
        jwtExpiresIn: 3600,
        rateLimit: {
          windowMs: 60000,
          maxRequests: 100,
          keyPrefix: 'test:',
        },
        enableCircuitBreaker: false,
        circuitBreakerThreshold: 5,
        circuitBreakerTimeout: 30000,
      });

      const cb = gw.getCircuitBreaker();
      expect(cb).toBeDefined();
      gw.destroy();
    });
  });

  describe('Error Handling', () => {
    it('should throw error when destroyed', async () => {
      gateway.destroy();
      await expect(
        gateway.handleRequest({
          method: 'GET',
          path: '/test',
          headers: {},
        })
      ).rejects.toThrow();
    });
  });

  describe('Edge Cases', () => {
    it('should handle empty path', async () => {
      const response = await gateway.handleRequest({
        method: 'GET',
        path: '',
        headers: {},
      });

      expect(response.statusCode).toBe(404);
    });

    it('should handle very long path', async () => {
      const longPath = '/api/' + 'x'.repeat(5000);
      const response = await gateway.handleRequest({
        method: 'GET',
        path: longPath,
        headers: {},
      });

      expect(response.statusCode).toBe(404);
    });

    it('should handle request with query params', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: {},
      });

      gateway.registerRoute({
        method: 'GET',
        path: '/api/search',
        handler,
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      mockCircuitBreaker.execute.mockImplementation((fn) => fn());

      await gateway.handleRequest({
        method: 'GET',
        path: '/api/search',
        headers: {},
        query: { q: 'test' },
      });

      expect(handler.mock.calls[0][0].query.q).toBe('test');
    });

    it('should handle request with body', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: {},
      });

      gateway.registerRoute({
        method: 'POST',
        path: '/api/data',
        handler,
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      mockCircuitBreaker.execute.mockImplementation((fn) => fn());

      const requestBody = { name: 'test', value: 123 };
      await gateway.handleRequest({
        method: 'POST',
        path: '/api/data',
        headers: {},
        body: requestBody,
      });

      expect(handler.mock.calls[0][0].body).toEqual(requestBody);
    });

    it('should handle null body', async () => {
      const handler = jest.fn().mockResolvedValue({
        statusCode: 200,
        headers: {},
        body: {},
      });

      gateway.registerRoute({
        method: 'POST',
        path: '/api/null',
        handler,
        authRequired: false,
      });

      mockRateLimiter.checkLimit.mockResolvedValue({
        allowed: true,
        info: { remaining: 99, resetTime: 60, limit: 100 },
      });

      mockCircuitBreaker.execute.mockImplementation((fn) => fn());

      await gateway.handleRequest({
        method: 'POST',
        path: '/api/null',
        headers: {},
        body: null,
      });

      expect(handler.mock.calls[0][0].body).toBeNull();
    });
  });

  describe('Component Accessors', () => {
    it('should return authenticator', () => {
      expect(gateway.getAuthenticator()).toBe(mockAuthenticator);
    });

    it('should return rate limiter', () => {
      expect(gateway.getRateLimiter()).toBe(mockRateLimiter);
    });

    it('should return circuit breaker', () => {
      expect(gateway.getCircuitBreaker()).toBe(mockCircuitBreaker);
    });
  });

  describe('Destroy', () => {
    it('should destroy all components', () => {
      gateway.destroy();

      expect(mockAuthenticator.destroy).toHaveBeenCalled();
      expect(mockRateLimiter.destroy).toHaveBeenCalled();
    });

    it('should prevent operations after destroy', () => {
      gateway.destroy();

      expect(() =>
        gateway.registerRoute({
          method: 'GET',
          path: '/test',
          handler: jest.fn(),
          authRequired: false,
        })
      ).toThrow();
    });
  });
});
