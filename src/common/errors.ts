export class BaseError extends Error {
  public readonly code: number;
  public readonly details?: unknown;

  constructor(message: string, code: number = 500, details?: unknown) {
    super(message);
    this.name = this.constructor.name;
    this.code = code;
    this.details = details;
    Error.captureStackTrace(this, this.constructor);
  }
}

export class ValidationError extends BaseError {
  constructor(message: string, details?: unknown) {
    super(message, 422, details);
  }
}

export class TimeoutError extends BaseError {
  constructor(message: string = '操作超时') {
    super(message, 504);
  }
}

export class NotFoundError extends BaseError {
  constructor(message: string = '资源不存在') {
    super(message, 404);
  }
}

export class UnauthorizedError extends BaseError {
  constructor(message: string = '未授权') {
    super(message, 401);
  }
}

export class ResourceExhaustedError extends BaseError {
  constructor(message: string = '资源耗尽') {
    super(message, 429);
  }
}
