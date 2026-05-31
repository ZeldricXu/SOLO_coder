export abstract class BaseError extends Error {
  abstract readonly code: string;
  abstract readonly statusCode: number;

  constructor(message: string) {
    super(message);
    this.name = this.constructor.name;
    Error.captureStackTrace(this, this.constructor);
  }

  toJSON() {
    return {
      code: this.code,
      message: this.message,
      statusCode: this.statusCode,
    };
  }
}

export class ValidationError extends BaseError {
  readonly code = 'VALIDATION_ERROR';
  readonly statusCode = 422;

  constructor(public readonly details: Record<string, string[]>, message = 'Validation failed') {
    super(message);
  }
}

export class NotFoundError extends BaseError {
  readonly code = 'NOT_FOUND';
  readonly statusCode = 404;

  constructor(resource: string, id: string) {
    super(`${resource} with id ${id} not found`);
  }
}

export class ConflictError extends BaseError {
  readonly code = 'CONFLICT';
  readonly statusCode = 409;

  constructor(message: string) {
    super(message);
  }
}

export class TimeoutError extends BaseError {
  readonly code = 'TIMEOUT';
  readonly statusCode = 504;

  constructor(operation: string) {
    super(`${operation} timed out`);
  }
}

export class InsufficientBalanceError extends BaseError {
  readonly code = 'INSUFFICIENT_BALANCE';
  readonly statusCode = 400;

  constructor(address: string, required: bigint, actual: bigint) {
    super(`Insufficient balance for ${address}: required ${required}, actual ${actual}`);
  }
}

export class ChainInteractionError extends BaseError {
  readonly code = 'CHAIN_INTERACTION_ERROR';
  readonly statusCode = 502;

  constructor(chainId: number, message: string, public readonly originalError?: Error) {
    super(`Chain ${chainId} error: ${message}`);
  }
}

export class SignatureVerificationError extends BaseError {
  readonly code = 'SIGNATURE_VERIFICATION_ERROR';
  readonly statusCode = 400;

  constructor(message: string) {
    super(message);
  }
}
