import { QueryBase } from '../../shared/dto/CommonDTO';
import { TicketDTO } from '../dto/TicketDTO';

export interface ListTicketsQuery extends QueryBase {
  pagination: { page: number; pageSize: number };
  filter?: {
    status?: string;
    priority?: string;
    agentId?: string;
    type?: string;
    createdAtFrom?: Date;
    createdAtTo?: Date;
  };
}

export const LIST_TICKETS_USE_CASE = Symbol('ListTicketsUseCase');

export interface IListTicketsUseCase {
  execute(query: ListTicketsQuery): Promise<{
    items: TicketDTO[];
    total: number;
    page: number;
    pageSize: number;
    totalPages: number;
  }>;
}
