import { Ticket } from '../entities/Ticket';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';
import { IRepository, IPaginationParams, IPaginatedResult } from '../../shared/repositories/IRepository';

export interface ITicketRepository extends IRepository<Ticket, UniqueEntityID> {
  findByIdWithDetails(id: UniqueEntityID, tenantId: string): Promise<Ticket | null>;
  findByTenantPaginated(
    tenantId: string,
    params: IPaginationParams & {
      status?: string;
      priority?: string;
      agentId?: string;
      type?: string;
      createdAtFrom?: Date;
      createdAtTo?: Date;
    }
  ): Promise<IPaginatedResult<Ticket>>;
  findByStatus(tenantId: string, status: string): Promise<Ticket[]>;
  findByPriority(tenantId: string, priority: string): Promise<Ticket[]>;
  findByAgent(tenantId: string, agentId: string): Promise<Ticket[]>;
  findUnassignedTickets(tenantId: string, limit?: number): Promise<Ticket[]>;
  findByType(tenantId: string, type: string): Promise<Ticket[]>;
  countByStatus(tenantId: string, status: string): Promise<number>;
  countByPriority(tenantId: string, priority: string): Promise<number>;
  countByAgent(tenantId: string, agentId: string): Promise<number>;
  findBySkillRequirement(tenantId: string, skillId: string, limit?: number): Promise<Ticket[]>;
  findOpenTicketsWithoutAgent(tenantId: string, limit?: number): Promise<Ticket[]>;
}
