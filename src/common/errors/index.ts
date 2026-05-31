export class AppError extends Error {
  public readonly code: number;
  public readonly details?: Record<string, unknown>;
  public readonly traceId?: string;

  constructor(
    message: string,
    code: number = 500,
    details?: Record<string, unknown>,
    traceId?: string
  ) {
    super(message);
    this.name = this.constructor.name;
    this.code = code;
    this.details = details;
    this.traceId = traceId;
    Error.captureStackTrace(this, this.constructor);
  }
}

export class ValidationError extends AppError {
  constructor(message: string, details?: Record<string, unknown>, traceId?: string) {
    super(message, 422, details, traceId);
    this.name = 'ValidationError';
  }
}

export class NotFoundError extends AppError {
  constructor(message: string, details?: Record<string, unknown>, traceId?: string) {
    super(message, 404, details, traceId);
    this.name = 'NotFoundError';
  }
}

export class UnauthorizedError extends AppError {
  constructor(message: string = 'Unauthorized', details?: Record<string, unknown>, traceId?: string) {
    super(message, 401, details, traceId);
    this.name = 'UnauthorizedError';
  }
}

export class ForbiddenError extends AppError {
  constructor(message: string = 'Forbidden', details?: Record<string, unknown>, traceId?: string) {
    super(message, 403, details, traceId);
    this.name = 'ForbiddenError';
  }
}

export class ConflictError extends AppError {
  constructor(message: string, details?: Record<string, unknown>, traceId?: string) {
    super(message, 409, details, traceId);
    this.name = 'ConflictError';
  }
}

export class TimeoutError extends AppError {
  constructor(message: string = 'Request timed out', details?: Record<string, unknown>, traceId?: string) {
    super(message, 504, details, traceId);
    this.name = 'TimeoutError';
  }
}

export class QuotaExceededError extends AppError {
  constructor(message: string, details?: Record<string, unknown>, traceId?: string) {
    super(message, 429, details, traceId);
    this.name = 'QuotaExceededError';
  }
}

export class TenantIsolationError extends AppError {
  constructor(message: string, details?: Record<string, unknown>, traceId?: string) {
    super(message, 403, details, traceId);
    this.name = 'TenantIsolationError';
  }
}

export class NoMatchingAgentError extends AppError {
  constructor(message: string = 'No matching agent found', details?: Record<string, unknown>, traceId?: string) {
    super(message, 422, details, traceId);
    this.name = 'NoMatchingAgentError';
  }
}

export class ProcessValidationError extends AppError {
  constructor(message: string, details?: Record<string, unknown>, traceId?: string) {
    super(message, 422, details, traceId);
    this.name = 'ProcessValidationError';
  }
}

export class SLABreachError extends AppError {
  constructor(message: string, details?: Record<string, unknown>, traceId?: string) {
    super(message, 422, details, traceId);
    this.name = 'SLABreachError';
  }
}

export const errorHandler = (
  err: Error,
  traceId?: string
): { code: number; message: string; error: string; details?: Record<string, unknown>; traceId?: string } => {
  if (err instanceof AppError) {
    return {
      code: err.code,
      message: err.message,
      error: err.name,
      details: err.details,
      traceId: err.traceId || traceId
    };
  }

  return {
    code: 500,
    message: 'Internal server error',
    error: 'InternalError',
    details: process.env.NODE_ENV === 'development' ? { stack: err.stack } : undefined,
    traceId
  };
};
