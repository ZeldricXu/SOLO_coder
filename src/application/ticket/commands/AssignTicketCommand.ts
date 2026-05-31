import { CommandBase } from '../../shared/dto/CommonDTO';
import { AssignTicketDTO, TicketAssignmentResultDTO } from '../dto/TicketDTO';

export interface AssignTicketCommand extends CommandBase {
  ticketId: string;
  data: AssignTicketDTO;
  strategy?: 'hybrid' | 'skill_first' | 'load_first';
  threshold?: number;
}

export const ASSIGN_TICKET_USE_CASE = Symbol('AssignTicketUseCase');

export interface IAssignTicketUseCase {
  execute(command: AssignTicketCommand): Promise<TicketAssignmentResultDTO>;
}
