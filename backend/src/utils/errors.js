class AppError extends Error {
  constructor(message, statusCode = 500, code = 'INTERNAL_ERROR') {
    super(message);
    this.statusCode = statusCode;
    this.code = code;
    this.isOperational = true;
    Error.captureStackTrace(this, this.constructor);
  }

  toJSON() {
    return {
      code: this.code,
      message: this.message,
      statusCode: this.statusCode
    };
  }
}

class ValidationError extends AppError {
  constructor(message, errors = [], code = 'VALIDATION_ERROR') {
    super(message, 400, code);
    this.errors = errors;
  }

  toJSON() {
    return {
      ...super.toJSON(),
      errors: this.errors
    };
  }
}

class TicketQuotaExhaustedError extends AppError {
  constructor(ticketId, ticketName, remainingQuota, alternatives = []) {
    super(`票务 ${ticketName || ticketId} 库存不足`, 400, 'TICKET_QUOTA_EXHAUSTED');
    this.ticketId = ticketId;
    this.ticketName = ticketName;
    this.remainingQuota = remainingQuota;
    this.alternatives = alternatives;
  }

  toJSON() {
    return {
      ...super.toJSON(),
      ticket_id: this.ticketId,
      ticket_name: this.ticketName,
      remaining_quota: this.remainingQuota,
      alternatives: this.alternatives
    };
  }
}

class OptimisticLockError extends AppError {
  constructor(message = '库存更新冲突，请重试') {
    super(message, 409, 'OPTIMISTIC_LOCK_CONFLICT');
    this.retryable = true;
  }
}

class ResourceNotFoundError extends AppError {
  constructor(resourceType, resourceId) {
    super(`${resourceType} 不存在`, 404, 'RESOURCE_NOT_FOUND');
    this.resourceType = resourceType;
    this.resourceId = resourceId;
  }
}

class UnauthorizedError extends AppError {
  constructor(message = '未授权访问') {
    super(message, 401, 'UNAUTHORIZED');
  }
}

class ForbiddenError extends AppError {
  constructor(message = '无权限执行此操作') {
    super(message, 403, 'FORBIDDEN');
  }
}

class QueueError extends AppError {
  constructor(message, queueName, originalError = null) {
    super(message, 500, 'QUEUE_ERROR');
    this.queueName = queueName;
    this.originalError = originalError;
  }
}

class ReportConfigParseError extends AppError {
  constructor(message, parsePath = null, originalValue = null) {
    super(message, 400, 'REPORT_CONFIG_PARSE_ERROR');
    this.parsePath = parsePath;
    this.originalValue = originalValue;
  }
}

module.exports = {
  AppError,
  ValidationError,
  TicketQuotaExhaustedError,
  OptimisticLockError,
  ResourceNotFoundError,
  UnauthorizedError,
  ForbiddenError,
  QueueError,
  ReportConfigParseError
};
