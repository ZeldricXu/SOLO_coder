import { ITicketRepository } from '../../../../domain/ticket/repositories/ITicketRepository';
import { Ticket } from '../../../../domain/ticket/entities/Ticket';
import { UniqueEntityID } from '../../../../domain/shared/value-objects/UniqueEntityID';
import { IPaginationParams, IPaginatedResult } from '../../../../domain/shared/repositories/IRepository';
import { TicketStatusType } from '../../../../domain/ticket/value-objects/TicketStatus';
import { getPrismaClient } from '../client/PrismaClient';
import { TicketMapper } from '../mappers/TicketMapper';
import { injectable } from 'tsyringe';

interface ITicketFilter {
  status?: string;
  priority?: string;
  agentId?: string;
  type?: string;
  createdAtFrom?: Date;
  createdAtTo?: Date;
}

@injectable()
export class TicketRepository implements ITicketRepository {
  private readonly prisma: any;

  constructor() {
    this.prisma = getPrismaClient();
  }

  async findById(id: UniqueEntityID, tenantId: string): Promise<Ticket | null> {
    const prismaTicket = await this.prisma.ticket.findUnique({
      where: { id: id.value },
      include: {
        requiredSkills: { include: { skill: true } }
      }
    });

    if (!prismaTicket || prismaTicket.tenantId !== tenantId) {
      return null;
    }

    return TicketMapper.toDomain(prismaTicket);
  }

  async findByIdWithDetails(id: UniqueEntityID, tenantId: string): Promise<Ticket | null> {
    return this.findById(id, tenantId);
  }

  async findAll(tenantId: string, options?: { skip?: number; take?: number }): Promise<Ticket[]> {
    const prismaTickets = await this.prisma.ticket.findMany({
      where: { tenantId },
      include: {
        requiredSkills: { include: { skill: true } }
      },
      skip: options?.skip,
      take: options?.take,
      orderBy: { createdAt: 'desc' }
    });

    return prismaTickets.map((t: any) => TicketMapper.toDomain(t));
  }

  async count(tenantId: string): Promise<number> {
    return this.prisma.ticket.count({
      where: { tenantId }
    });
  }

  async save(ticket: Ticket, tenantId: string): Promise<Ticket> {
    const ticketData = TicketMapper.toPersistence(ticket);

    const existing = await this.prisma.ticket.findUnique({
      where: { id: ticket.id.value }
    });

    let savedTicket;
    if (existing) {
      savedTicket = await this.prisma.ticket.update({
        where: { id: ticket.id.value },
        data: ticketData,
        include: { requiredSkills: { include: { skill: true } } }
      });

      await this.prisma.ticketSkillRequirement.deleteMany({
        where: { ticketId: ticket.id.value }
      });
    } else {
      savedTicket = await this.prisma.ticket.create({
        data: ticketData,
        include: { requiredSkills: { include: { skill: true } } }
      });
    }

    const skillRequirements = TicketMapper.toPersistenceSkillRequirements(ticket);
    for (const req of skillRequirements) {
      await this.prisma.ticketSkillRequirement.create({
        data: req
      });
    }

    return TicketMapper.toDomain({
      ...savedTicket,
      requiredSkills: skillRequirements.map(r => ({
        ...r,
        id: crypto.randomUUID(),
        createdAt: new Date(),
        updatedAt: new Date(),
        skill: { id: r.skillId, name: '' }
      }))
    });
  }

  async delete(id: UniqueEntityID, tenantId: string): Promise<void> {
    await this.prisma.ticketSkillRequirement.deleteMany({
      where: { ticketId: id.value }
    });
    await this.prisma.ticket.delete({
      where: { id: id.value }
    });
  }

  async findByTenantPaginated(
    tenantId: string,
    params: IPaginationParams & ITicketFilter
  ): Promise<IPaginatedResult<Ticket>> {
    const where: Record<string, unknown> = { tenantId };
    if (params.status) where.status = params.status;
    if (params.priority) where.priority = params.priority;
    if (params.agentId) where.agentId = params.agentId;
    if (params.type) where.type = params.type;
    if (params.createdAtFrom) where.createdAt = { ...(where.createdAt as object), gte: params.createdAtFrom };
    if (params.createdAtTo) where.createdAt = { ...(where.createdAt as object), lte: params.createdAtTo };

    const [total, prismaTickets] = await Promise.all([
      this.prisma.ticket.count({ where }),
      this.prisma.ticket.findMany({
        where,
        skip: (params.page - 1) * params.pageSize,
        take: params.pageSize,
        orderBy: { createdAt: 'desc' },
        include: { requiredSkills: { include: { skill: true } } }
      })
    ]);

    return {
      items: prismaTickets.map((t: any) => TicketMapper.toDomain(t)),
      total,
      page: params.page,
      pageSize: params.pageSize,
      totalPages: Math.ceil(total / params.pageSize)
    };
  }

  async findByStatus(tenantId: string, status: string): Promise<Ticket[]> {
    const prismaTickets = await this.prisma.ticket.findMany({
      where: { tenantId, status },
      include: { requiredSkills: { include: { skill: true } } }
    });

    return prismaTickets.map((t: any) => TicketMapper.toDomain(t));
  }

  async findByPriority(tenantId: string, priority: string): Promise<Ticket[]> {
    const prismaTickets = await this.prisma.ticket.findMany({
      where: { tenantId, priority },
      include: { requiredSkills: { include: { skill: true } } }
    });

    return prismaTickets.map((t: any) => TicketMapper.toDomain(t));
  }

  async findByAgent(tenantId: string, agentId: string): Promise<Ticket[]> {
    const prismaTickets = await this.prisma.ticket.findMany({
      where: { tenantId, agentId },
      include: { requiredSkills: { include: { skill: true } } }
    });

    return prismaTickets.map((t: any) => TicketMapper.toDomain(t));
  }

  async findUnassignedTickets(tenantId: string, limit?: number): Promise<Ticket[]> {
    const prismaTickets = await this.prisma.ticket.findMany({
      where: {
        tenantId,
        status: 'open',
        agentId: null
      },
      take: limit,
      include: { requiredSkills: { include: { skill: true } } }
    });

    return prismaTickets.map((t: any) => TicketMapper.toDomain(t));
  }

  async findByType(tenantId: string, type: string): Promise<Ticket[]> {
    const prismaTickets = await this.prisma.ticket.findMany({
      where: { tenantId, type },
      include: { requiredSkills: { include: { skill: true } } }
    });

    return prismaTickets.map((t: any) => TicketMapper.toDomain(t));
  }

  async countByStatus(tenantId: string, status: string): Promise<number> {
    return this.prisma.ticket.count({
      where: { tenantId, status }
    });
  }

  async countByPriority(tenantId: string, priority: string): Promise<number> {
    return this.prisma.ticket.count({
      where: { tenantId, priority }
    });
  }

  async countByAgent(tenantId: string, agentId: string): Promise<number> {
    return this.prisma.ticket.count({
      where: { tenantId, agentId }
    });
  }

  async findBySkillRequirement(tenantId: string, skillId: string, limit?: number): Promise<Ticket[]> {
    const prismaTickets = await this.prisma.ticket.findMany({
      where: {
        tenantId,
        requiredSkills: { some: { skillId } }
      },
      take: limit,
      include: { requiredSkills: { include: { skill: true } } }
    });

    return prismaTickets.map((t: any) => TicketMapper.toDomain(t));
  }

  async findOpenTicketsWithoutAgent(tenantId: string, limit?: number): Promise<Ticket[]> {
    return this.findUnassignedTickets(tenantId, limit);
  }

  async findTicketsNeedingAssignment(tenantId: string, limit?: number): Promise<Ticket[]> {
    return this.findUnassignedTickets(tenantId, limit);
  }
}
