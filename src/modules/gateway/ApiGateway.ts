import { Request, Response, NextFunction, RequestHandler } from 'express';
import { AuthService } from './AuthService';
import { MultiTenantRateLimiter, RateLimitConfig } from './RateLimiter';
import { JwtPayload, AuthMiddlewareOptions, PermissionCheck } from '../../types/auth';
import { ApiResponse, ErrorDetail } from '../../types/core';
import {
  AuthenticationError,
  AuthorizationError,
  RateLimitError,
  ValidationError,
  NotFoundError,
  isAppError,
  AppError,
  TokenExpiredError,
  TokenInvalidError,
  PermissionDeniedError
} from '../../common/errors';
import { getCurrentTimestamp } from '../../common/utils';
import Redis from 'ioredis';

interface ErrorContext {
  requestId: string;
  path: string;
  method: string;
  timestamp: string;
}

interface EnhancedApiResponse extends ApiResponse {
  requestId?: string;
  path?: string;
  method?: string;
  timestamp?: string;
  errorCode?: string;
  errorCategory?: ErrorCategory;
  documentationUrl?: string;
  suggestion?: string;
}

type ErrorCategory =
  | 'AUTHENTICATION'
  | 'AUTHORIZATION'
  | 'RATE_LIMIT'
  | 'VALIDATION'
  | 'NOT_FOUND'
  | 'INTERNAL'
  | 'EXTERNAL'
  | 'BUSINESS';

declare global {
  namespace Express {
    interface Request {
      user?: JwtPayload;
      rateLimitInfo?: {
        limit: number;
        remaining: number;
        resetTime: number;
      };
      requestId?: string;
    }
  }
}

export class ApiGateway {
  private authService: AuthService;
  private rateLimiter: MultiTenantRateLimiter;
  private options: AuthMiddlewareOptions;

  constructor(
    jwtSecret: string,
    rateLimitConfig: RateLimitConfig,
    options: AuthMiddlewareOptions = {},
    redisClient?: Redis
  ) {
    this.authService = new AuthService(jwtSecret);
    this.rateLimiter = new MultiTenantRateLimiter(rateLimitConfig, redisClient);
    this.options = {
      excludePaths: [],
      requireAllRoles: false,
      ...options
    };
  }

  getAuthService(): AuthService {
    return this.authService;
  }

  getRateLimiter(): MultiTenantRateLimiter {
    return this.rateLimiter;
  }

  authenticate(): RequestHandler {
    return async (req: Request, res: Response, next: NextFunction) => {
      try {
        if (this.options.excludePaths?.includes(req.path)) {
          return next();
        }

        const authHeader = req.headers.authorization;
        if (!authHeader || !authHeader.startsWith('Bearer ')) {
          throw new AuthenticationError('缺少认证令牌', {
            hint: '请在请求头中携带 Bearer token',
            documentationUrl: '/docs/api/authentication'
          });
        }

        const token = authHeader.substring(7);

        try {
          const payload = this.authService.verifyToken(token);
          req.user = payload;
        } catch (verifyError) {
          if (verifyError instanceof TokenExpiredError) {
            throw new TokenExpiredError('令牌已过期', {
              expiredAt: verifyError.details?.expiredAt,
              suggestion: '请使用 refresh token 获取新的访问令牌',
              documentationUrl: '/docs/api/token-refresh'
            });
          }
          if (verifyError instanceof TokenInvalidError) {
            throw new TokenInvalidError('令牌无效', {
              hint: '请检查令牌是否正确或重新登录',
              documentationUrl: '/docs/api/authentication'
            });
          }
          throw new AuthenticationError('令牌验证失败', {
            cause: verifyError instanceof Error ? verifyError.message : String(verifyError)
          });
        }

        next();
      } catch (error) {
        this.handleError(error, res, this.extractErrorContext(req));
      }
    };
  }

  authorize(requiredRoles?: string[], requiredPermissions?: string[]): RequestHandler {
    return (req: Request, res: Response, next: NextFunction) => {
      try {
        if (!req.user) {
          throw new AuthenticationError('用户未认证', {
            hint: '请先进行身份认证',
            documentationUrl: '/docs/api/authentication'
          });
        }

        if (requiredRoles && requiredRoles.length > 0) {
          const hasRequiredRoles = this.options.requireAllRoles
            ? requiredRoles.every(role => req.user!.roles.includes(role))
            : requiredRoles.some(role => req.user!.roles.includes(role));

          if (!hasRequiredRoles) {
            throw new AuthorizationError('缺少必要的角色权限', {
              requiredRoles,
              userRoles: req.user.roles,
              suggestion: '请联系管理员分配相应的角色权限',
              documentationUrl: '/docs/api/authorization'
            });
          }
        }

        if (requiredPermissions && requiredPermissions.length > 0) {
          const hasRequiredPermissions = requiredPermissions.every(perm =>
            req.user!.permissions.includes(perm)
          );

          if (!hasRequiredPermissions) {
            throw new PermissionDeniedError('缺少必要的权限', {
              requiredPermissions,
              userPermissions: req.user.permissions,
              suggestion: '请联系管理员分配相应的操作权限',
              documentationUrl: '/docs/api/authorization'
            });
          }
        }

        next();
      } catch (error) {
        this.handleError(error, res, this.extractErrorContext(req));
      }
    };
  }

  requirePermission(permissionCheck: PermissionCheck): RequestHandler {
    return (req: Request, res: Response, next: NextFunction) => {
      try {
        if (!req.user) {
          throw new AuthenticationError('用户未认证', {
            hint: '请先进行身份认证',
            documentationUrl: '/docs/api/authentication'
          });
        }

        try {
          this.authService.checkPermission(req.user, permissionCheck);
          next();
        } catch (error) {
          if (error instanceof AuthorizationError) {
            throw new PermissionDeniedError('权限检查未通过', {
              resource: permissionCheck.resource,
              action: permissionCheck.action,
              suggestion: '请确认您拥有该资源的访问权限',
              documentationUrl: '/docs/api/authorization'
            });
          }
          throw error;
        }
      } catch (error) {
        this.handleError(error, res, this.extractErrorContext(req));
      }
    };
  }

  rateLimit(limitType: 'tenant' | 'user' | 'ip' | 'endpoint' = 'tenant'): RequestHandler {
    return async (req: Request, res: Response, next: NextFunction) => {
      try {
        const tenantId = req.user?.tenantId || 'default';

        try {
          let rateLimitInfo;
          switch (limitType) {
            case 'tenant':
              rateLimitInfo = await this.rateLimiter.checkTenantLimit(tenantId);
              break;
            case 'user':
              if (!req.user?.sub) {
                throw new AuthenticationError('用户未认证', {
                  hint: '用户级限流需要用户认证',
                  documentationUrl: '/docs/api/rate-limiting'
                });
              }
              rateLimitInfo = await this.rateLimiter.checkUserLimit(tenantId, req.user.sub);
              break;
            case 'ip':
              const ip = req.ip || req.connection.remoteAddress || 'unknown';
              rateLimitInfo = await this.rateLimiter.checkIpLimit(tenantId, ip);
              break;
            case 'endpoint':
              rateLimitInfo = await this.rateLimiter.checkEndpointLimit(tenantId, req.path);
              break;
          }

          req.rateLimitInfo = {
            limit: rateLimitInfo.limit,
            remaining: rateLimitInfo.remaining,
            resetTime: rateLimitInfo.resetTime
          };

          res.setHeader('X-RateLimit-Limit', rateLimitInfo.limit.toString());
          res.setHeader('X-RateLimit-Remaining', rateLimitInfo.remaining.toString());
          res.setHeader('X-RateLimit-Reset', Math.floor(rateLimitInfo.resetTime / 1000).toString());

          next();
        } catch (error) {
          if (error instanceof RateLimitError) {
            const retryAfter = Math.ceil((error.details?.resetTime as number - Date.now()) / 1000);
            res.setHeader('Retry-After', retryAfter.toString());
            throw new RateLimitError('请求频率超限', {
              limit: error.details?.limit,
              remaining: 0,
              resetTime: error.details?.resetTime,
              retryAfter: `${retryAfter}秒`,
              suggestion: '请稍后重试或升级您的套餐配额',
              documentationUrl: '/docs/api/rate-limiting'
            });
          }
          throw error;
        }
      } catch (error) {
        this.handleError(error, res, this.extractErrorContext(req));
      }
    };
  }

  combine(middlewares: RequestHandler[]): RequestHandler[] {
    return middlewares;
  }

  private extractErrorContext(req: Request): ErrorContext {
    return {
      requestId: req.requestId || (req as any).id || 'unknown',
      path: req.path,
      method: req.method,
      timestamp: getCurrentTimestamp()
    };
  }

  private categorizeError(error: unknown): ErrorCategory {
    if (error instanceof AuthenticationError || error instanceof TokenExpiredError || error instanceof TokenInvalidError) {
      return 'AUTHENTICATION';
    }
    if (error instanceof AuthorizationError || error instanceof PermissionDeniedError) {
      return 'AUTHORIZATION';
    }
    if (error instanceof RateLimitError) {
      return 'RATE_LIMIT';
    }
    if (error instanceof ValidationError) {
      return 'VALIDATION';
    }
    if (error instanceof NotFoundError) {
      return 'NOT_FOUND';
    }
    return 'INTERNAL';
  }

  private handleError(error: unknown, res: Response, context: ErrorContext): void {
    const category = this.categorizeError(error);

    if (isAppError(error)) {
      const response: EnhancedApiResponse = {
        code: error.statusCode,
        error: error.code,
        message: error.message,
        details: error.details,
        requestId: context.requestId,
        path: context.path,
        method: context.method,
        timestamp: context.timestamp,
        errorCode: error.code,
        errorCategory: category,
        documentationUrl: error.details?.documentationUrl as string | undefined,
        suggestion: error.details?.suggestion as string | undefined
      };
      res.status(error.statusCode).json(response);
      return;
    }

    const errorMessage = error instanceof Error ? error.message : String(error);
    const stack = error instanceof Error ? error.stack : undefined;

    const response: EnhancedApiResponse = {
      code: 500,
      error: 'INTERNAL_ERROR',
      message: '服务器内部错误',
      details: {
        hint: '请稍后重试或联系技术支持',
        suggestion: '如果问题持续存在，请提供 requestId 以便我们追踪问题',
        ...(process.env.NODE_ENV === 'development' && { error: errorMessage, stack: stack })
      },
      requestId: context.requestId,
      path: context.path,
      method: context.method,
      timestamp: context.timestamp,
      errorCode: 'INTERNAL_ERROR',
      errorCategory: 'INTERNAL',
      documentationUrl: '/docs/errors/internal-error'
    };

    res.status(500).json(response);
  }

  loginHandler(): RequestHandler {
    return async (req: Request, res: Response) => {
      try {
        const { username, password } = req.body;
        if (!username || !password) {
          throw new ValidationError('用户名和密码不能为空', {
            fields: {
              username: !username ? '用户名不能为空' : undefined,
              password: !password ? '密码不能为空' : undefined
            },
            suggestion: '请提供正确的用户名和密码',
            documentationUrl: '/docs/api/login'
          });
        }

        try {
          const tokens = await this.authService.authenticate({ username, password });
          const response: ApiResponse = {
            code: 200,
            data: tokens,
            message: '登录成功'
          };
          res.json(response);
        } catch (authError) {
          if (authError instanceof AuthenticationError) {
            throw new AuthenticationError('用户名或密码错误', {
              hint: '请检查用户名和密码是否正确',
              suggestion: '如果忘记密码，请使用找回密码功能',
              documentationUrl: '/docs/api/login'
            });
          }
          throw authError;
        }
      } catch (error) {
        this.handleError(error, res, this.extractErrorContext(req));
      }
    };
  }

  refreshTokenHandler(): RequestHandler {
    return async (req: Request, res: Response) => {
      try {
        const { refreshToken } = req.body;
        if (!refreshToken) {
          throw new ValidationError('刷新令牌不能为空', {
            hint: '请在请求体中携带 refreshToken',
            documentationUrl: '/docs/api/token-refresh'
          });
        }

        try {
          const tokens = await this.authService.refreshToken(refreshToken);
          const response: ApiResponse = {
            code: 200,
            data: tokens,
            message: '令牌刷新成功'
          };
          res.json(response);
        } catch (error) {
          if (error instanceof TokenExpiredError) {
            throw new TokenExpiredError('刷新令牌已过期', {
              suggestion: '请重新登录获取新的令牌',
              documentationUrl: '/docs/api/token-refresh'
            });
          }
          if (error instanceof TokenInvalidError) {
            throw new TokenInvalidError('刷新令牌无效', {
              suggestion: '请检查 refresh token 是否正确',
              documentationUrl: '/docs/api/token-refresh'
            });
          }
          throw error;
        }
      } catch (error) {
        this.handleError(error, res, this.extractErrorContext(req));
      }
    };
  }

  logoutHandler(): RequestHandler {
    return (req: Request, res: Response) => {
      try {
        const { refreshToken } = req.body;
        if (refreshToken) {
          try {
            this.authService.revokeRefreshToken(refreshToken);
          } catch (error) {
            throw new ValidationError('登出失败', {
              cause: error instanceof Error ? error.message : String(error)
            });
          }
        }

        const response: ApiResponse = {
          code: 200,
          message: '登出成功'
        };
        res.json(response);
      } catch (error) {
        this.handleError(error, res, this.extractErrorContext(req));
      }
    };
  }
}
