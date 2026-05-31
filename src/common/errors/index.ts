export abstract class BaseError extends Error {
  constructor(
    message: string,
    public readonly code: number,
    public readonly details?: Record<string, unknown>
  ) {
    super(message);
    this.name = this.constructor.name;
    Error.captureStackTrace(this, this.constructor);
  }
}

export class ValidationError extends BaseError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 422, details);
  }
}

export class NotFoundError extends BaseError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 404, details);
  }
}

export class ConflictError extends BaseError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 409, details);
  }
}

export class TimeoutError extends BaseError {
  constructor(message: string = '上游服务响应超时', details?: Record<string, unknown>) {
    super(message, 504, details);
  }
}

export class UnauthorizedError extends BaseError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 401, details);
  }
}

export class ForbiddenError extends BaseError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 403, details);
  }
}

export class ResourceExhaustedError extends BaseError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 429, details);
  }
}

export class InternalError extends BaseError {
  constructor(message: string = '内部处理错误', details?: Record<string, unknown>) {
    super(message, 500, details);
  }
}
