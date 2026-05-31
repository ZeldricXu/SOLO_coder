import { Tenant } from '../entities/Tenant';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';
import { TenantIsolationError } from '../../shared/errors/DomainError';

export interface ITenantAccessService {
  verifyAccess(
    currentTenantId: UniqueEntityID,
    targetTenantId: UniqueEntityID,
    traceId?: string
  ): void;
  verifyAccessForEntity(
    currentTenantId: UniqueEntityID,
    entityTenantId: UniqueEntityID,
    entityType: string,
    entityId: string,
    traceId?: string
  ): void;
}

export class TenantAccessService implements ITenantAccessService {
  verifyAccess(
    currentTenantId: UniqueEntityID,
    targetTenantId: UniqueEntityID,
    traceId?: string
  ): void {
    if (!currentTenantId.equals(targetTenantId)) {
      throw new TenantIsolationError('Tenant isolation violation', {
        currentTenantId: currentTenantId.value,
        targetTenantId: targetTenantId.value,
        traceId
      });
    }
  }

  verifyAccessForEntity(
    currentTenantId: UniqueEntityID,
    entityTenantId: UniqueEntityID,
    entityType: string,
    entityId: string,
    traceId?: string
  ): void {
    if (!currentTenantId.equals(entityTenantId)) {
      throw new TenantIsolationError(`Access denied to ${entityType}`, {
        currentTenantId: currentTenantId.value,
        entityTenantId: entityTenantId.value,
        entityType,
        entityId,
        traceId
      });
    }
  }
}
