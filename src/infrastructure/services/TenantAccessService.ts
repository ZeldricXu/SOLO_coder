import { ITenantAccessPort } from '../../application/shared/ports/ITenantAccessPort';
import { UniqueEntityID } from '../../domain/shared/value-objects/UniqueEntityID';
import { TenantIsolationError } from '../../domain/shared/errors/DomainError';
import { injectable } from 'tsyringe';

@injectable()
export class TenantAccessService implements ITenantAccessPort {
  verifyAccess(currentTenantId: string, targetTenantId: string, traceId?: string): void {
    const currentId = UniqueEntityID.create(currentTenantId);
    const targetId = UniqueEntityID.create(targetTenantId);

    if (!currentId.equals(targetId)) {
      throw new TenantIsolationError('Tenant isolation violation', {
        currentTenantId,
        targetTenantId,
        traceId
      });
    }
  }

  verifyAccessForEntity(
    currentTenantId: string,
    entityTenantId: string,
    entityType: string,
    entityId: string,
    traceId?: string
  ): void {
    const currentId = UniqueEntityID.create(currentTenantId);
    const entityIdObj = UniqueEntityID.create(entityTenantId);

    if (!currentId.equals(entityIdObj)) {
      throw new TenantIsolationError(`Access denied to ${entityType}`, {
        currentTenantId,
        entityTenantId,
        entityType,
        entityId,
        traceId
      });
    }
  }
}
