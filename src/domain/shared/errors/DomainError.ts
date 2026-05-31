export abstract class DomainError extends Error {
  public readonly code: string;
  public readonly details?: Record<string, unknown>;

  constructor(
    message: string,
    code: string = 'DOMAIN_ERROR',
    details?: Record<string, unknown>
  ) {
    super(message);
    this.name = this.constructor.name;
    this.code = code;
    this.details = details;
    Error.captureStackTrace(this, this.constructor);
  }
}

export class NotFoundError extends DomainError {
  constructor(entityName: string, id: string, details?: Record<string, unknown>) {
    super(`${entityName} not found`, 'NOT_FOUND', { entityName, id, ...details });
  }
}

export class ValidationError extends DomainError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'VALIDATION_ERROR', details);
  }
}

export class ConflictError extends DomainError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'CONFLICT', details);
  }
}

export class BusinessRuleViolationError extends DomainError {
  constructor(message: string, detailsOrRule?: string | Record<string, unknown>, details?: Record<string, unknown>) {
    if (typeof detailsOrRule === 'string') {
      super(message, 'BUSINESS_RULE_VIOLATION', { rule: detailsOrRule, ...details });
    } else {
      super(message, 'BUSINESS_RULE_VIOLATION', detailsOrRule);
    }
  }
}

export class TenantIsolationError extends DomainError {
  constructor(message: string, details?: Record<string, unknown>) {
    super(message, 'TENANT_ISOLATION_VIOLATION', details);
  }
}

export class QuotaExceededError extends DomainError {
  constructor(resource: string, used: number, limit: number, details?: Record<string, unknown>) {
    super(`Quota exceeded for ${resource}`, 'QUOTA_EXCEEDED', { resource, used, limit, ...details });
  }
}

export class NoMatchingAgentError extends DomainError {
  constructor(message: string = 'No matching agent found', details?: Record<string, unknown>) {
    super(message, 'NO_MATCHING_AGENT', details);
  }
}

export class SLABreachError extends DomainError {
  constructor(message: string, slaId: string, details?: Record<string, unknown>) {
    super(message, 'SLA_BREACH', { slaId, ...details });
  }
}

export class ProcessValidationError extends DomainError {
  constructor(message: string, errors: unknown[], details?: Record<string, unknown>) {
    super(message, 'PROCESS_VALIDATION_ERROR', { errors, ...details });
  }
}
