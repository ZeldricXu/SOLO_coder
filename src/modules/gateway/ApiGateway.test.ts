import { ApiGateway } from './ApiGateway';
import { RateLimiter } from './RateLimiter';
import { Request, Response, NextFunction } from 'express';
import { RateLimitError, AuthenticationError, AuthorizationError, TokenExpiredError, TokenInvalidError } from '../../common/errors';

describe('ApiGateway - 错误传递修复测试', () => {
  let gateway: ApiGateway;
  let mockRequest: Partial<Request>;
  let mockResponse: Partial<Response>;
  let mockNext: jest.Mocked<NextFunction>;
  let jsonResponse: jest.Mock;

  beforeEach(() => {
    gateway = new ApiGateway(
      'test-secret-key',
      { windowMs: 60000, maxRequests: 100 },
      { excludePaths: ['/health'] }
    );

    jsonResponse = jest.fn();
    mockResponse = {
      status: jest.fn().mockReturnThis(),
      json: jsonResponse,
      setHeader: jest.fn()
    };
    mockNext = jest.fn();
    mockRequest = {
      path: '/api/test',
      method: 'GET',
      headers: {},
      ip: '127.0.0.1'
    };
  });

  describe('错误上下文传递', () => {
    it('认证错误应包含完整的上下文信息', async () => {
      mockRequest.headers = {};

      const middleware = gateway.authenticate();
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      expect(mockResponse.status).toHaveBeenCalledWith(401);
      expect(jsonResponse).toHaveBeenCalledWith(
        expect.objectContaining({
          code: 401,
          requestId: expect.any(String),
          path: '/api/test',
          method: 'GET',
          timestamp: expect.any(String),
          errorCategory: 'AUTHENTICATION',
          documentationUrl: expect.any(String),
          suggestion: expect.any(String)
        })
      );
    });

    it('授权错误应包含完整的上下文信息', async () => {
      mockRequest.user = { roles: ['user'], permissions: [] };

      const middleware = gateway.authorize(['admin']);
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      expect(mockResponse.status).toHaveBeenCalledWith(403);
      expect(jsonResponse).toHaveBeenCalledWith(
        expect.objectContaining({
          code: 403,
          requestId: expect.any(String),
          path: '/api/test',
          method: 'GET',
          timestamp: expect.any(String),
          errorCategory: 'AUTHORIZATION',
          suggestion: expect.any(String)
        })
      );
    });

    it('限流错误应包含完整的上下文信息', async () => {
      const rateLimiter = gateway.getRateLimiter();
      const originalCheckLimit = rateLimiter.checkLimit.bind(rateLimiter);
      rateLimiter.checkLimit = jest.fn().mockResolvedValue({
        allowed: false,
        remaining: 0,
        resetAt: Date.now() + 60000
      });

      const middleware = gateway.rateLimit();
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      expect(mockResponse.status).toHaveBeenCalledWith(429);
      expect(jsonResponse).toHaveBeenCalledWith(
        expect.objectContaining({
          code: 429,
          requestId: expect.any(String),
          path: '/api/test',
          method: 'GET',
          timestamp: expect.any(String),
          errorCategory: 'RATE_LIMIT',
          suggestion: expect.any(String)
        })
      );

      rateLimiter.checkLimit = originalCheckLimit;
    });
  });

  describe('错误分类', () => {
    it('令牌过期错误应归类为 AUTHENTICATION', async () => {
      const authService = gateway.getAuthService();
      const originalVerifyToken = authService.verifyToken.bind(authService);
      authService.verifyToken = jest.fn().mockImplementation(() => {
        throw new TokenExpiredError('Token expired');
      });

      mockRequest.headers = { authorization: 'Bearer expired_token' };

      const middleware = gateway.authenticate();
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      expect(jsonResponse).toHaveBeenCalledWith(
        expect.objectContaining({
          errorCategory: 'AUTHENTICATION',
          errorCode: 'TOKEN_EXPIRED',
          suggestion: expect.any(String),
          documentationUrl: expect.any(String)
        })
      );

      authService.verifyToken = originalVerifyToken;
    });

    it('令牌无效错误应归类为 AUTHENTICATION', async () => {
      const authService = gateway.getAuthService();
      const originalVerifyToken = authService.verifyToken.bind(authService);
      authService.verifyToken = jest.fn().mockImplementation(() => {
        throw new TokenInvalidError('Invalid token');
      });

      mockRequest.headers = { authorization: 'Bearer invalid_token' };

      const middleware = gateway.authenticate();
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      expect(jsonResponse).toHaveBeenCalledWith(
        expect.objectContaining({
          errorCategory: 'AUTHENTICATION',
          errorCode: 'TOKEN_INVALID',
          suggestion: expect.any(String),
          documentationUrl: expect.any(String)
        })
      );

      authService.verifyToken = originalVerifyToken;
    });

    it('权限不足错误应归类为 AUTHORIZATION', async () => {
      mockRequest.user = { roles: ['user'], permissions: ['read'] };

      const middleware = gateway.authorize(undefined, ['write']);
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      expect(jsonResponse).toHaveBeenCalledWith(
        expect.objectContaining({
          errorCategory: 'AUTHORIZATION',
          errorCode: 'INSUFFICIENT_PERMISSIONS',
          suggestion: expect.any(String)
        })
      );
    });
  });

  describe('错误信息增强', () => {
    it('错误响应应包含可操作的建议', async () => {
      mockRequest.headers = {};

      const middleware = gateway.authenticate();
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      const response = jsonResponse.mock.calls[0][0];
      expect(response.suggestion).toBeDefined();
      expect(typeof response.suggestion).toBe('string');
      expect(response.suggestion.length).toBeGreaterThan(0);
    });

    it('错误响应应包含文档链接', async () => {
      mockRequest.headers = {};

      const middleware = gateway.authenticate();
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      const response = jsonResponse.mock.calls[0][0];
      expect(response.documentationUrl).toBeDefined();
      expect(response.documentationUrl).toMatch(/^\/docs\//);
    });
  });

  describe('请求ID追踪', () => {
    it('所有错误响应应包含唯一请求ID', async () => {
      mockRequest.headers = {};

      const middleware = gateway.authenticate();
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      const response = jsonResponse.mock.calls[0][0];
      expect(response.requestId).toBeDefined();
      expect(typeof response.requestId).toBe('string');
      expect(response.requestId.length).toBeGreaterThan(0);
    });
  });

  describe('向后兼容性', () => {
    it('应保持原有错误字段不变', async () => {
      mockRequest.headers = {};

      const middleware = gateway.authenticate();
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      const response = jsonResponse.mock.calls[0][0];
      expect(response.code).toBeDefined();
      expect(response.message).toBeDefined();
      expect(response.error).toBeDefined();
    });

    it('排除路径应正常工作', async () => {
      mockRequest.path = '/health';

      const middleware = gateway.authenticate();
      await middleware(
        mockRequest as Request,
        mockResponse as Response,
        mockNext
      );

      expect(mockNext).toHaveBeenCalled();
      expect(mockResponse.status).not.toHaveBeenCalled();
    });
  });
});
