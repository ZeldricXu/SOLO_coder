import { container, DependencyContainer } from 'tsyringe';

import { ICachePort, CACHE_PORT } from '../application/shared/ports/ICachePort';
import { IEventBusPort, EVENT_BUS_PORT } from '../application/shared/ports/IEventBusPort';
import { IMetricsPort, METRICS_PORT } from '../application/shared/ports/IMetricsPort';
import { IUnitOfWorkFactory, UNIT_OF_WORK_FACTORY } from '../application/shared/ports/IUnitOfWorkPort';
import { ITenantAccessPort, TENANT_ACCESS_PORT } from '../application/shared/ports/ITenantAccessPort';

import { ITenantRepository } from '../domain/tenant/repositories/ITenantRepository';
import { ITicketRepository } from '../domain/ticket/repositories/ITicketRepository';
import { IAgentRepository } from '../domain/skill/repositories/ISkillRepository';

import { CREATE_TICKET_USE_CASE, ICreateTicketUseCase } from '../application/ticket/commands/CreateTicketCommand';
import { ASSIGN_TICKET_USE_CASE, IAssignTicketUseCase } from '../application/ticket/commands/AssignTicketCommand';
import { GET_TICKET_USE_CASE, IGetTicketUseCase } from '../application/ticket/queries/GetTicketQuery';
import { LIST_TICKETS_USE_CASE, IListTicketsUseCase } from '../application/ticket/queries/ListTicketsQuery';
import { FIND_MATCHING_AGENTS_USE_CASE, IFindMatchingAgentsUseCase } from '../application/ticket/queries/FindMatchingAgentsQuery';

import { RedisCacheAdapter } from '../infrastructure/persistence/cache/RedisCacheAdapter';
import { InMemoryCacheAdapter } from '../infrastructure/persistence/cache/InMemoryCacheAdapter';
import { InMemoryEventBus } from '../infrastructure/messaging/event-bus/InMemoryEventBus';
import { PrometheusMetricsAdapter } from '../infrastructure/monitoring/metrics/PrometheusMetricsAdapter';
import { PrismaUnitOfWorkFactory } from '../infrastructure/persistence/prisma/client/UnitOfWork';
import { TenantAccessService } from '../infrastructure/services/TenantAccessService';

import { TenantRepository } from '../infrastructure/persistence/prisma/repositories/TenantRepository';
import { TicketRepository } from '../infrastructure/persistence/prisma/repositories/TicketRepository';
import { AgentRepository } from '../infrastructure/persistence/prisma/repositories/AgentRepository';

import { CreateTicketHandler } from '../application/ticket/handlers/CreateTicketHandler';
import { AssignTicketHandler } from '../application/ticket/handlers/AssignTicketHandler';
import { FindMatchingAgentsHandler } from '../application/ticket/handlers/FindMatchingAgentsHandler';

import { TicketAssignmentDomainService } from '../domain/skill/services/TicketAssignmentService';

import { TicketController } from '../interface/http/controllers/TicketController';
import { TicketRoutes } from '../interface/http/routes/ticketRoutes';

import { getConfig } from '../infrastructure/config/AppConfig';

export const registerDependencies = (): void => {
  const config = getConfig();
  const isProduction = config.env === 'production';

  container.register<ICachePort>(CACHE_PORT, {
    useClass: isProduction ? RedisCacheAdapter : InMemoryCacheAdapter
  });

  container.register<IEventBusPort>(EVENT_BUS_PORT, {
    useClass: InMemoryEventBus
  });

  container.register<IMetricsPort>(METRICS_PORT, {
    useClass: PrometheusMetricsAdapter
  });

  container.register<IUnitOfWorkFactory>(UNIT_OF_WORK_FACTORY, {
    useClass: PrismaUnitOfWorkFactory
  });

  container.register<ITenantAccessPort>(TENANT_ACCESS_PORT, {
    useClass: TenantAccessService
  });

  container.register<ITenantRepository>('ITenantRepository', {
    useClass: TenantRepository
  });

  container.register<ITicketRepository>('ITicketRepository', {
    useClass: TicketRepository
  });

  container.register<IAgentRepository>('IAgentRepository', {
    useClass: AgentRepository
  });

  container.register<ICreateTicketUseCase>(CREATE_TICKET_USE_CASE, {
    useClass: CreateTicketHandler
  });

  container.register<IAssignTicketUseCase>(ASSIGN_TICKET_USE_CASE, {
    useClass: AssignTicketHandler
  });

  const getTicketHandlerFactory = (c: DependencyContainer) => ({
    execute: async (query: { tenantId: string; ticketId: string; traceId?: string }) => {
      const ticketRepo = c.resolve<ITicketRepository>('ITicketRepository');
      const tenantAccess = c.resolve<ITenantAccessPort>(TENANT_ACCESS_PORT);
      const { UniqueEntityID } = await import('../domain/shared/value-objects/UniqueEntityID');

      const ticket = await ticketRepo.findByIdWithDetails(
        UniqueEntityID.create(query.ticketId),
        query.tenantId
      );

      if (!ticket) {
        const { NotFoundError } = await import('../domain/shared/errors/DomainError');
        throw new NotFoundError('Ticket', query.ticketId);
      }

      tenantAccess.verifyAccessForEntity(
        query.tenantId,
        ticket.tenantId.value,
        'Ticket',
        query.ticketId,
        query.traceId
      );

      return {
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
      };
    }
  });

  container.register<IGetTicketUseCase>(GET_TICKET_USE_CASE, {
    useFactory: getTicketHandlerFactory
  });

  const listTicketsHandlerFactory = (c: DependencyContainer) => ({
    execute: async (query: { tenantId: string; traceId?: string; pagination: { page: number; pageSize: number }; filter?: any }) => {
      const ticketRepo = c.resolve<ITicketRepository>('ITicketRepository');
      const tenantAccess = c.resolve<ITenantAccessPort>(TENANT_ACCESS_PORT);

      tenantAccess.verifyAccess(query.tenantId, query.tenantId, query.traceId);

      const result = await ticketRepo.findByTenantPaginated(query.tenantId, {
        ...query.pagination,
        ...query.filter
      });

      return {
        ...result,
        items: result.items.map(ticket => ({
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
        }))
      };
    }
  });

  container.register<IListTicketsUseCase>(LIST_TICKETS_USE_CASE, {
    useFactory: listTicketsHandlerFactory
  });

  container.register<IFindMatchingAgentsUseCase>(FIND_MATCHING_AGENTS_USE_CASE, {
    useClass: FindMatchingAgentsHandler
  });

  container.register(TicketAssignmentDomainService, {
    useClass: TicketAssignmentDomainService
  });

  container.register(TicketController, { useClass: TicketController });
  container.register(TicketRoutes, { useClass: TicketRoutes });
};

export { container };
