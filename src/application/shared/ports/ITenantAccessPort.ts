import { UniqueEntityID } from '../../../domain/shared/value-objects/UniqueEntityID';

export interface ITenantAccessPort {
  verifyAccess(
    currentTenantId: string,
    targetTenantId: string,
    traceId?: string
  ): void;
  verifyAccessForEntity(
    currentTenantId: string,
    entityTenantId: string,
    entityType: string,
    entityId: string,
    traceId?: string
  ): void;
}

export const TENANT_ACCESS_PORT = Symbol('ITenantAccessPort');
