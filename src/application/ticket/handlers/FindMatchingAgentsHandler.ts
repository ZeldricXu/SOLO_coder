import { IFindMatchingAgentsUseCase, FindMatchingAgentsQuery } from '../queries/FindMatchingAgentsQuery';
import { SkillMatchResultDTO } from '../dto/TicketDTO';
import { ITicketRepository } from '../../../domain/ticket/repositories/ITicketRepository';
import { IAgentRepository } from '../../../domain/skill/repositories/ISkillRepository';
import { TicketAssignmentDomainService, getMatchingStrategy } from '../../../domain/skill/services/TicketAssignmentService';
import { UniqueEntityID } from '../../../domain/shared/value-objects/UniqueEntityID';
import { ICachePort, CACHE_PORT, generateCacheKey, TTL } from '../../shared/ports/ICachePort';
import { ITenantAccessPort, TENANT_ACCESS_PORT } from '../../shared/ports/ITenantAccessPort';
import { inject, injectable } from 'tsyringe';

@injectable()
export class FindMatchingAgentsHandler implements IFindMatchingAgentsUseCase {
  constructor(
    @inject('ITicketRepository') private readonly ticketRepository: ITicketRepository,
    @inject('IAgentRepository') private readonly agentRepository: IAgentRepository,
    @inject(CACHE_PORT) private readonly cache: ICachePort,
    @inject(TENANT_ACCESS_PORT) private readonly tenantAccess: ITenantAccessPort,
    private readonly assignmentService: TicketAssignmentDomainService
  ) {}

  async execute(query: FindMatchingAgentsQuery): Promise<SkillMatchResultDTO[]> {
    const { tenantId, ticketId, strategy = 'hybrid', threshold = 0.5, limit = 10, traceId } = query;
    const ticketUId = UniqueEntityID.create(ticketId);

    const cacheKey = generateCacheKey(
      'matching_agents',
      tenantId,
      ticketId,
      strategy,
      String(threshold),
      String(limit)
    );

    const cached = await this.cache.get<SkillMatchResultDTO[]>(cacheKey);
    if (cached) {
      return cached;
    }

    const ticket = await this.ticketRepository.findByIdWithDetails(ticketUId, tenantId);
    if (!ticket) {
      throw new Error('Ticket not found');
    }

    this.tenantAccess.verifyAccessForEntity(
      tenantId,
      ticket.tenantId.value,
      'Ticket',
      ticketId,
      traceId
    );

    const availableAgents = await this.agentRepository.findAvailableAgents(tenantId);
    const matchingStrategy = getMatchingStrategy(strategy);

    const results = this.assignmentService.findMatchingAgents(
      ticket,
      availableAgents,
      matchingStrategy,
      { threshold, limit }
    );

    await this.cache.set(cacheKey, results, TTL.SHORT);

    return results;
  }
}
