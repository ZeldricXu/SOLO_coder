import { ICreateTicketUseCase, CreateTicketCommand, CreateTicketResult } from '../commands/CreateTicketCommand';
import { ITicketRepository } from '../../../domain/ticket/repositories/ITicketRepository';
import { ITenantRepository } from '../../../domain/tenant/repositories/ITenantRepository';
import { Ticket } from '../../../domain/ticket/entities/Ticket';
import { SkillRequirement } from '../../../domain/ticket/value-objects/SkillRequirement';
import { TicketType } from '../../../domain/ticket/value-objects/TicketType';
import { TicketPriority } from '../../../domain/ticket/value-objects/TicketPriority';
import { UniqueEntityID } from '../../../domain/shared/value-objects/UniqueEntityID';
import { IUnitOfWorkPort, UNIT_OF_WORK_FACTORY, IUnitOfWorkFactory } from '../../shared/ports/IUnitOfWorkPort';
import { ICachePort, CACHE_PORT, generateCacheKey, TTL } from '../../shared/ports/ICachePort';
import { IEventBusPort, EVENT_BUS_PORT } from '../../shared/ports/IEventBusPort';
import { IMetricsPort, METRICS_PORT } from '../../shared/ports/IMetricsPort';
import { ITenantAccessPort, TENANT_ACCESS_PORT } from '../../shared/ports/ITenantAccessPort';
import { inject, injectable } from 'tsyringe';

@injectable()
export class CreateTicketHandler implements ICreateTicketUseCase {
  constructor(
    @inject(UNIT_OF_WORK_FACTORY) private readonly uowFactory: IUnitOfWorkFactory,
    @inject('ITicketRepository') private readonly ticketRepository: ITicketRepository,
    @inject('ITenantRepository') private readonly tenantRepository: ITenantRepository,
    @inject(EVENT_BUS_PORT) private readonly eventBus: IEventBusPort,
    @inject(METRICS_PORT) private readonly metrics: IMetricsPort,
    @inject(CACHE_PORT) private readonly cache: ICachePort,
    @inject(TENANT_ACCESS_PORT) private readonly tenantAccess: ITenantAccessPort
  ) {}

  async execute(command: CreateTicketCommand): Promise<CreateTicketResult> {
    const { tenantId, data, traceId } = command;
    const tenantUId = UniqueEntityID.create(tenantId);

    const tenant = await this.tenantRepository.findById(tenantUId, tenantId);
    if (!tenant) {
      throw new Error('Tenant not found');
    }

    this.tenantAccess.verifyAccess(tenantId, tenantId, traceId);

    const uow = this.uowFactory.create();

    return uow.execute(async () => {
      const requiredSkills = data.requiredSkills.map(req =>
        SkillRequirement.create(req.skillId, req.minLevel, req.required ?? true)
      );

      const ticket = Ticket.create({
        title: data.title,
        description: data.description,
        type: TicketType.create(data.type),
        priority: TicketPriority.create(data.priority || 'medium'),
        tenantId: tenantUId,
        requiredSkills
      });

      const savedTicket = await this.ticketRepository.save(ticket, tenantId);

      await this.eventBus.publishAggregateEvents(savedTicket);
      await this.cache.del(generateCacheKey('tickets', tenantId));

      this.metrics.increment('tickets_created_total', 1, {
        priority: data.priority || 'medium',
        type: data.type,
        tenantId
      });

      return {
        ticketId: savedTicket.id.value,
        title: savedTicket.title,
        status: savedTicket.status.value,
        priority: savedTicket.priority.value
      };
    });
  }
}
