import { QueryBase } from '../../shared/dto/CommonDTO';
import { TicketDTO } from '../dto/TicketDTO';

export interface GetTicketQuery extends QueryBase {
  ticketId: string;
}

export const GET_TICKET_USE_CASE = Symbol('GetTicketUseCase');

export interface IGetTicketUseCase {
  execute(query: GetTicketQuery): Promise<TicketDTO>;
}
