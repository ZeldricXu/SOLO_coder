export class AppError extends Error {
  statusCode: number;
  code: string;
  details?: any;

  constructor(message: string, statusCode: number = 500, code: string = 'INTERNAL_ERROR', details?: any) {
    super(message);
    this.statusCode = statusCode;
    this.code = code;
    this.details = details;
    Error.captureStackTrace(this, this.constructor);
  }
}

export class ValidationError extends AppError {
  constructor(message: string, details?: any) {
    super(message, 422, 'VALIDATION_ERROR', details);
  }
}

export class NotFoundError extends AppError {
  constructor(message: string = 'Resource not found') {
    super(message, 404, 'NOT_FOUND');
  }
}

export class UnauthorizedError extends AppError {
  constructor(message: string = 'Unauthorized') {
    super(message, 401, 'UNAUTHORIZED');
  }
}

export class ForbiddenError extends AppError {
  constructor(message: string = 'Forbidden') {
    super(message, 403, 'FORBIDDEN');
  }
}

export class ConflictError extends AppError {
  constructor(message: string, details?: any) {
    super(message, 409, 'CONFLICT', details);
  }
}

export class TimeoutError extends AppError {
  constructor(message: string = 'Request timed out') {
    super(message, 504, 'TIMEOUT');
  }
}

export class ChainError extends AppError {
  constructor(message: string, chainId?: number) {
    super(message, 500, 'CHAIN_ERROR', { chainId });
  }
}

export class StorageError extends AppError {
  constructor(message: string, network?: string) {
    super(message, 500, 'STORAGE_ERROR', { network });
  }
}

export class WalletError extends AppError {
  constructor(message: string, details?: any) {
    super(message, 400, 'WALLET_ERROR', details);
  }
}

export class BridgeError extends AppError {
  constructor(message: string, details?: any) {
    super(message, 500, 'BRIDGE_ERROR', details);
  }
}

export class MultisigError extends AppError {
  constructor(message: string, details?: any) {
    super(message, 400, 'MULTISIG_ERROR', details);
  }
}
