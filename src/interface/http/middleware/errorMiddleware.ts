import { Request, Response, NextFunction } from 'express';
import { randomUUID } from 'crypto';
import { DomainError } from '../../../domain/shared/errors/DomainError';

export const errorMiddleware = (
  err: Error,
  req: Request,
  res: Response,
  next: NextFunction
): void => {
  const traceId = res.getHeader('x-trace-id') as string || randomUUID();

  if (err instanceof DomainError) {
    const statusCode = getStatusCodeForDomainError(err.code);
    res.status(statusCode).json({
      code: statusCode,
      error: err.name,
      message: err.message,
      details: err.details,
      traceId
    });
    return;
  }

  console.error('Unhandled error:', err);
  res.status(500).json({
    code: 500,
    error: 'INTERNAL_ERROR',
    message: 'Internal server error',
    traceId,
    ...(process.env.NODE_ENV === 'development' ? { stack: err.stack } : {})
  });
};

const getStatusCodeForDomainError = (code: string): number => {
  const statusMap: Record<string, number> = {
    NOT_FOUND: 404,
    VALIDATION_ERROR: 422,
    CONFLICT: 409,
    BUSINESS_RULE_VIOLATION: 422,
    TENANT_ISOLATION_VIOLATION: 403,
    QUOTA_EXCEEDED: 429,
    NO_MATCHING_AGENT: 422,
    SLA_BREACH: 422,
    PROCESS_VALIDATION_ERROR: 422
  };
  return statusMap[code] || 500;
};
