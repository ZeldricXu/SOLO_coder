import { IAssignTicketUseCase, AssignTicketCommand } from '../commands/AssignTicketCommand';
import { TicketAssignmentResultDTO, SkillMatchResultDTO } from '../dto/TicketDTO';
import { ITicketRepository } from '../../../domain/ticket/repositories/ITicketRepository';
import { IAgentRepository } from '../../../domain/skill/repositories/ISkillRepository';
import { TicketAssignmentDomainService, getMatchingStrategy } from '../../../domain/skill/services/TicketAssignmentService';
import { UniqueEntityID } from '../../../domain/shared/value-objects/UniqueEntityID';
import { IUnitOfWorkFactory, UNIT_OF_WORK_FACTORY } from '../../shared/ports/IUnitOfWorkPort';
import { ICachePort, CACHE_PORT, generateCacheKey, TTL } from '../../shared/ports/ICachePort';
import { IEventBusPort, EVENT_BUS_PORT } from '../../shared/ports/IEventBusPort';
import { IMetricsPort, METRICS_PORT } from '../../shared/ports/IMetricsPort';
import { ITenantAccessPort, TENANT_ACCESS_PORT } from '../../shared/ports/ITenantAccessPort';
import { inject, injectable } from 'tsyringe';

@injectable()
export class AssignTicketHandler implements IAssignTicketUseCase {
  constructor(
    @inject(UNIT_OF_WORK_FACTORY) private readonly uowFactory: IUnitOfWorkFactory,
    @inject('ITicketRepository') private readonly ticketRepository: ITicketRepository,
    @inject('IAgentRepository') private readonly agentRepository: IAgentRepository,
    @inject(EVENT_BUS_PORT) private readonly eventBus: IEventBusPort,
    @inject(METRICS_PORT) private readonly metrics: IMetricsPort,
    @inject(CACHE_PORT) private readonly cache: ICachePort,
    @inject(TENANT_ACCESS_PORT) private readonly tenantAccess: ITenantAccessPort,
    private readonly assignmentService: TicketAssignmentDomainService
  ) {}

  async execute(command: AssignTicketCommand): Promise<TicketAssignmentResultDTO> {
    const { tenantId, ticketId, data, strategy = 'hybrid', threshold, traceId } = command;
    const ticketUId = UniqueEntityID.create(ticketId);

    const uow = this.uowFactory.create();

    return uow.execute(async () => {
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

      let targetAgentId: UniqueEntityID | null = null;
      let matchResults: SkillMatchResultDTO[] = [];
      let reason = 'manual';

      if (!data.agentId && (data.autoAssign ?? true)) {
        const availableAgents = await this.agentRepository.findAvailableAgents(tenantId);
        const matchingStrategy = getMatchingStrategy(strategy);

        matchResults = this.assignmentService.findMatchingAgents(
          ticket,
          availableAgents,
          matchingStrategy,
          { threshold, limit: 10 }
        );

        if (matchResults.length === 0) {
          throw new Error('No matching agents found');
        }

        const bestMatch = matchResults[0];
        targetAgentId = UniqueEntityID.create(bestMatch.agentId);
        reason = `auto_assign:score=${bestMatch.score.toFixed(3)}:strategy=${strategy}`;
      } else if (data.agentId) {
        targetAgentId = UniqueEntityID.create(data.agentId);
      }

      if (!targetAgentId) {
        throw new Error('No agent specified and auto-assignment disabled');
      }

      const agent = await this.agentRepository.findById(targetAgentId, tenantId);
      if (!agent) {
        throw new Error('Agent not found');
      }

      const matchingStrategy = getMatchingStrategy(strategy);
      const validation = this.assignmentService.validateAssignment(
        ticket,
        agent,
        matchingStrategy,
        threshold
      );

      if (!validation.valid) {
        throw new Error(`Assignment validation failed: ${validation.reason}`);
      }

      const matchResult = matchingStrategy.calculateScore(agent, ticket);
      if (matchResults.length === 0) {
        matchResults = [matchResult];
      }

      ticket.assignTo(targetAgentId, reason, matchResult.score);
      agent.assignWork();

      await this.ticketRepository.save(ticket, tenantId);
      await this.agentRepository.save(agent, tenantId);

      await this.eventBus.publishAggregateEvents(ticket);
      await this.eventBus.publishAggregateEvents(agent);

      await this.cache.del(generateCacheKey('tickets', tenantId));
      await this.cache.del(generateCacheKey('agents', tenantId));

      this.metrics.increment('tickets_assigned_total', 1, { reason, tenantId });
      this.metrics.histogram('assignment_score', matchResult.score, { tenantId });

      return {
        assignment: {
          id: crypto.randomUUID(),
          ticketId: ticket.id.value,
          agentId: targetAgentId.value,
          reason,
          score: matchResult.score,
          status: 'active',
          createdAt: new Date()
        },
        ticket: {
          id: ticket.id.value,
          title: ticket.title,
          description: ticket.description,
          type: ticket.type.value,
          priority: ticket.priority.value,
          status: ticket.status.value,
          tenantId: ticket.tenantId.value,
          agentId: ticket.agentId?.value,
          requiredSkills: ticket.requiredSkills.map(req => ({
            skillId: req.skillId.value,
            minLevel: req.minLevel,
            required: req.required
          })),
          createdAt: ticket.createdAt,
          updatedAt: ticket.updatedAt,
          resolvedAt: ticket.resolvedAt,
          closedAt: ticket.closedAt
        },
        matchResults: matchResults.slice(0, 5)
      };
    });
  }
}
