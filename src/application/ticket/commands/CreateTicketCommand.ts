import { CommandBase } from '../../shared/dto/CommonDTO';
import { CreateTicketDTO } from '../dto/TicketDTO';
import { TicketPriorityType } from '../../../domain/ticket/value-objects/TicketPriority';
import { TicketStatusType } from '../../../domain/ticket/value-objects/TicketStatus';

export interface CreateTicketCommand extends CommandBase {
  data: CreateTicketDTO;
}

export interface CreateTicketResult {
  ticketId: string;
  title: string;
  status: TicketStatusType;
  priority: TicketPriorityType;
}

export const CREATE_TICKET_USE_CASE = Symbol('CreateTicketUseCase');

export interface ICreateTicketUseCase {
  execute(command: CreateTicketCommand): Promise<CreateTicketResult>;
}
