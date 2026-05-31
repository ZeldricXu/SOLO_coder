import { IRepository, IPaginationParams, IPaginatedResult } from '../../shared/repositories/IRepository';
import { Tenant } from '../entities/Tenant';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';

export interface ITenantRepository extends IRepository<Tenant> {
  findByEmail(email: string): Promise<Tenant | null>;
  findByName(name: string): Promise<Tenant | null>;
  findAllPaginated(params: IPaginationParams & { status?: string }): Promise<IPaginatedResult<Tenant>>;
  existsByEmail(email: string): Promise<boolean>;
}

export interface ITenantConfigRepository {
  getConfig(tenantId: UniqueEntityID, namespace: string): Promise<Record<string, unknown>>;
  setConfig(tenantId: UniqueEntityID, namespace: string, config: Record<string, unknown>): Promise<void>;
  getConfigValue(tenantId: UniqueEntityID, namespace: string, key: string): Promise<unknown>;
}
