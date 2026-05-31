import { Request, Response, NextFunction } from 'express';
import { v4 as uuidv4 } from 'uuid';
import { errorHandler } from '../errors';
import { AppError } from '../errors';

export interface RequestContext extends Request {
  traceId: string;
  tenantId?: string;
  userId?: string;
  startTime: Date;
}

export const traceIdMiddleware = (req: Request, _res: Response, next: NextFunction): void => {
  const ctx = req as RequestContext;
  ctx.traceId = (req.headers['x-trace-id'] as string) || uuidv4();
  ctx.startTime = new Date();
  next();
};

export const tenantContextMiddleware = (req: Request, _res: Response, next: NextFunction): void => {
  const ctx = req as RequestContext;
  const tenantId = req.headers['x-tenant-id'] as string;
  if (tenantId) {
    ctx.tenantId = tenantId;
  }
  next();
};

export const requireTenant = (req: Request, _res: Response, next: NextFunction): void => {
  const ctx = req as RequestContext;
  if (!ctx.tenantId) {
    throw new AppError('Tenant ID is required', 400, undefined, ctx.traceId);
  }
  next();
};

export const requestLogger = (req: Request, res: Response, next: NextFunction): void => {
  const ctx = req as RequestContext;
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.path} - Trace: ${ctx.traceId}`);
  res.on('finish', () => {
    const duration = Date.now() - ctx.startTime.getTime();
    console.log(`[${new Date().toISOString()}] ${req.method} ${req.path} - ${res.statusCode} - ${duration}ms`);
  });
  next();
};

export const errorMiddleware = (
  err: Error,
  req: Request,
  res: Response,
  _next: NextFunction
): void => {
  const ctx = req as RequestContext;
  const errorResponse = errorHandler(err, ctx.traceId);
  
  res.status(errorResponse.code).json(errorResponse);
};

export const notFoundMiddleware = (req: Request, res: Response): void => {
  const ctx = req as RequestContext;
  res.status(404).json({
    code: 404,
    message: 'Resource not found',
    error: 'NotFoundError',
    traceId: ctx.traceId
  });
};

export const validateRequest = <T>(
  schema: { parse: (data: unknown) => T }
) => (req: Request, _res: Response, next: NextFunction): void => {
  try {
    schema.parse(req.body);
    next();
  } catch (err) {
    const ctx = req as RequestContext;
    if (err instanceof Error) {
      throw new AppError('Validation failed', 422, { error: err.message }, ctx.traceId);
    }
    throw err;
  }
};

export const validateQuery = <T>(
  schema: { parse: (data: unknown) => T }
) => (req: Request, _res: Response, next: NextFunction): void => {
  try {
    schema.parse(req.query);
    next();
  } catch (err) {
    const ctx = req as RequestContext;
    if (err instanceof Error) {
      throw new AppError('Validation failed', 422, { error: err.message }, ctx.traceId);
    }
    throw err;
  }
};

export const validateParams = <T>(
  schema: { parse: (data: unknown) => T }
) => (req: Request, _res: Response, next: NextFunction): void => {
  try {
    schema.parse(req.params);
    next();
  } catch (err) {
    const ctx = req as RequestContext;
    if (err instanceof Error) {
      throw new AppError('Validation failed', 422, { error: err.message }, ctx.traceId);
    }
    throw err;
  }
};

export const asyncHandler = (
  fn: (req: Request, res: Response, next: NextFunction) => Promise<void>
) => (req: Request, res: Response, next: NextFunction): void => {
  Promise.resolve(fn(req, res, next)).catch(next);
};

export const responseTimeHeader = (_req: Request, res: Response, next: NextFunction): void => {
  const start = Date.now();
  res.on('finish', () => {
    const duration = Date.now() - start;
    res.setHeader('X-Response-Time', `${duration}ms`);
  });
  next();
};

export const securityHeaders = (req: Request, res: Response, next: NextFunction): void => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('X-XSS-Protection', '1; mode=block');
  res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  next();
};
