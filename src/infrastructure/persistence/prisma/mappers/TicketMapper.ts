import { Ticket, TicketProps } from '../../../../domain/ticket/entities/Ticket';
import { TicketStatus, TicketStatusType } from '../../../../domain/ticket/value-objects/TicketStatus';
import { TicketPriority, TicketPriorityType } from '../../../../domain/ticket/value-objects/TicketPriority';
import { TicketType } from '../../../../domain/ticket/value-objects/TicketType';
import { SkillRequirement } from '../../../../domain/ticket/value-objects/SkillRequirement';
import { UniqueEntityID } from '../../../../domain/shared/value-objects/UniqueEntityID';

export class TicketMapper {
  static toDomain(prismaTicket: any): Ticket {
    const requiredSkills = (prismaTicket.requiredSkills || []).map((req: any) =>
      SkillRequirement.create(req.skillId, req.minLevel, req.required)
    );

    return Ticket.create({
      id: prismaTicket.id,
      title: prismaTicket.title,
      description: prismaTicket.description,
      type: TicketType.create(prismaTicket.type),
      priority: TicketPriority.create(prismaTicket.priority as TicketPriorityType),
      status: TicketStatus.create(prismaTicket.status as TicketStatusType),
      tenantId: UniqueEntityID.create(prismaTicket.tenantId),
      agentId: prismaTicket.agentId ? UniqueEntityID.create(prismaTicket.agentId) : undefined,
      requiredSkills,
      createdAt: prismaTicket.createdAt,
      updatedAt: prismaTicket.updatedAt,
      resolvedAt: prismaTicket.resolvedAt || undefined,
      closedAt: prismaTicket.closedAt || undefined
    });
  }

  static toPersistence(ticket: Ticket): {
    id: string;
    title: string;
    description: string;
    type: string;
    priority: string;
    status: string;
    tenantId: string;
    agentId: string | null;
    createdAt: Date;
    updatedAt: Date;
    resolvedAt: Date | null;
    closedAt: Date | null;
  } {
    return {
      id: ticket.id.value,
      title: ticket.title,
      description: ticket.description,
      type: ticket.type.value,
      priority: ticket.priority.value,
      status: ticket.status.value,
      tenantId: ticket.tenantId.value,
      agentId: ticket.agentId?.value || null,
      createdAt: ticket.createdAt,
      updatedAt: ticket.updatedAt,
      resolvedAt: ticket.resolvedAt || null,
      closedAt: ticket.closedAt || null
    };
  }

  static toPersistenceSkillRequirements(ticket: Ticket): Array<{
    ticketId: string;
    skillId: string;
    minLevel: number;
    required: boolean;
  }> {
    return ticket.requiredSkills.map(req => ({
      ticketId: ticket.id.value,
      skillId: req.skillId.value,
      minLevel: req.minLevel,
      required: req.required
    }));
  }
}
