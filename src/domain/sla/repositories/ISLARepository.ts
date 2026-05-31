import { IRepository } from '../../shared/repositories/IRepository';
import { SLAPolicy } from '../entities/SLAPolicy';
import { SLAInstance } from '../entities/SLAInstance';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';

export interface ISLAPolicyRepository extends IRepository<SLAPolicy> {
  findByTicketTypeAndPriority(
    tenantId: string,
    ticketType: string,
    priority: string
  ): Promise<SLAPolicy | null>;
  findDefaultPolicy(tenantId: string): Promise<SLAPolicy | null>;
  findByTenant(tenantId: string): Promise<SLAPolicy[]>;
}

export interface ISLAInstanceRepository extends IRepository<SLAInstance> {
  findByTicketId(ticketId: UniqueEntityID, tenantId: string): Promise<SLAInstance | null>;
  findActiveInstances(tenantId: string): Promise<SLAInstance[]>;
  findWarningInstances(tenantId: string): Promise<SLAInstance[]>;
  findBreachedInstances(tenantId: string): Promise<SLAInstance[]>;
  countByStatus(tenantId: string, status: string): Promise<number>;
}
