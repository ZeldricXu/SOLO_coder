import { Request, Response, NextFunction } from 'express';
import type {
  IAuthService,
  IRateLimiter,
  IRequestContext,
} from '@ports/index';
import { generateTraceId } from '@utils/index';
import { rootLogger } from '@modules/logging';
import type { UserPrincipal } from '@apptypes/index';

declare global {
  namespace Express {
    interface Request {
      context: IRequestContext;
      principal?: UserPrincipal;
    }
  }
}

const AUTH_HEADER_PREFIX = 'Bearer ';
const RATE_LIMIT_HEADER_MAX = 100;

interface ErrorResponse {
  code: number;
  message: string;
}

export const createRequestContext = (
  req: Request,
  _res: Response,
  next: NextFunction,
): void => {
  req.context = {
    traceId: extractTraceId(req),
    spanId: generateTraceId().slice(0, 16),
    principal: null,
    startTime: Date.now(),
    attributes: {},
  };
  next();
};

export const createAuthMiddleware = (authService: IAuthService) => {
  return async (
    req: Request,
    res: Response,
    next: NextFunction,
  ): Promise<void> => {
    const token = extractBearerToken(req);
    if (!token) {
      sendErrorResponse(res, 401, 'Missing or invalid authorization header');
      return;
    }

    const principal = await authService.authenticate(token);
    if (!principal) {
      sendErrorResponse(res, 401, 'Invalid or expired token');
      return;
    }

    req.principal = principal;
    req.context.principal = principal;
    next();
  };
};

export const createPermissionMiddleware = (
  authService: IAuthService,
  permission: string,
) => {
  return (req: Request, res: Response, next: NextFunction): void => {
    if (!req.principal) {
      sendErrorResponse(res, 401, 'Authentication required');
      return;
    }

    if (!authService.authorize(req.principal, permission)) {
      sendErrorResponse(res, 403, 'Insufficient permissions');
      return;
    }

    next();
  };
};

export const createRateLimitMiddleware = (rateLimiter: IRateLimiter) => {
  return async (
    req: Request,
    res: Response,
    next: NextFunction,
  ): Promise<void> => {
    const key = buildRateLimitKey(req);
    const result = await rateLimiter.checkLimit(key);

    setRateLimitHeaders(res, result);

    if (!result.allowed) {
      setRetryAfterHeader(res, result.resetTime);
      sendErrorResponse(
        res,
        429,
        'Rate limit exceeded. Please try again later.',
      );
      return;
    }

    next();
  };
};

export const requestLogger = (
  req: Request,
  res: Response,
  next: NextFunction,
): void => {
  const start = Date.now();

  res.on('finish', () => {
    logRequest(req, res, start);
  });

  next();
};

export const errorHandler = (
  err: Error,
  req: Request,
  res: Response,
  _next: NextFunction,
): void => {
  rootLogger.error('Unhandled error', {
    trace_id: req.context?.traceId,
    error: err.message,
    stack: err.stack,
    path: req.path,
    method: req.method,
  });

  sendErrorResponse(res, 500, 'Internal server error');
};

function extractTraceId(req: Request): string {
  return (req.headers['x-trace-id'] as string) || generateTraceId();
}

function extractBearerToken(req: Request): string | null {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith(AUTH_HEADER_PREFIX)) {
    return null;
  }
  return authHeader.slice(AUTH_HEADER_PREFIX.length);
}

function buildRateLimitKey(req: Request): string {
  const clientId =
    (req.headers['x-client-id'] as string) ||
    req.principal?.user_id ||
    req.ip ||
    'unknown';
  return `${clientId}:${req.method}:${req.path}`;
}

function setRateLimitHeaders(
  res: Response,
  result: { remaining: number; resetTime: number },
): void {
  res.setHeader('X-RateLimit-Limit', String(RATE_LIMIT_HEADER_MAX));
  res.setHeader('X-RateLimit-Remaining', String(result.remaining));
  res.setHeader('X-RateLimit-Reset', String(Math.ceil(result.resetTime / 1000)));
}

function setRetryAfterHeader(res: Response, resetTime: number): void {
  const retryAfter = Math.ceil((resetTime - Date.now()) / 1000);
  res.setHeader('Retry-After', String(retryAfter));
}

function sendErrorResponse(
  res: Response,
  code: number,
  message: string,
): void {
  const response: ErrorResponse = { code, message };
  res.status(code).json(response);
}

function logRequest(req: Request, res: Response, start: number): void {
  const duration = Date.now() - start;
  const logger = rootLogger.child({
    trace_id: req.context?.traceId,
    span_id: req.context?.spanId,
  });

  const logData = {
    method: req.method,
    path: req.path,
    status_code: res.statusCode,
    duration_ms: duration,
    user_id: req.principal?.user_id,
    client_ip: req.ip,
    user_agent: req.headers['user-agent'],
  };

  const level = getLogLevel(res.statusCode);
  logger[level]('Request completed', logData);
}

function getLogLevel(statusCode: number): 'error' | 'warn' | 'info' {
  if (statusCode >= 500) return 'error';
  if (statusCode >= 400) return 'warn';
  return 'info';
}
