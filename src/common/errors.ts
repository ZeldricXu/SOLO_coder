export class PlatformError extends Error {
  public readonly code: string;
  public readonly details?: Record<string, unknown>;

  constructor(message: string, code: string, details?: Record<string, unknown>) {
    super(message);
    this.name = 'PlatformError';
    this.code = code;
    this.details = details;
    Object.setPrototypeOf(this, PlatformError.prototype);
  }
}

export class ValidationError extends PlatformError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'VALIDATION_ERROR', details);
    this.name = 'ValidationError';
  }
}

export class NotFoundError extends PlatformError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'NOT_FOUND', details);
    this.name = 'NotFoundError';
  }
}

export class AuthenticationError extends PlatformError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'AUTHENTICATION_ERROR', details);
    this.name = 'AuthenticationError';
  }
}

export class AuthorizationError extends PlatformError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'AUTHORIZATION_ERROR', details);
    this.name = 'AuthorizationError';
  }
}

export class RateLimitError extends PlatformError {
  constructor(message: string, public readonly retryAfter: number, details?: Record<string, unknown>) {
    super(message, 'RATE_LIMIT_EXCEEDED', { ...details, retryAfter });
    this.name = 'RateLimitError';
  }
}

export class ServiceUnavailableError extends PlatformError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'SERVICE_UNAVAILABLE', details);
    this.name = 'ServiceUnavailableError';
  }
}

export interface Result<T = void> {
  success: boolean;
  data?: T;
  error?: string;
  errorCode?: string;
}

export function createSuccessResult<T>(data: T): Result<T> {
  return { success: true, data };
}

export function createErrorResult(error: string, errorCode?: string): Result<never> {
  return { success: false, error, errorCode };
}

export function fromError(error: unknown): Result<never> {
  if (error instanceof PlatformError) {
    return createErrorResult(error.message, error.code);
  }
  if (error instanceof Error) {
    return createErrorResult(error.message, 'INTERNAL_ERROR');
  }
  return createErrorResult('Unknown error', 'INTERNAL_ERROR');
}
