import { QueryBase } from '../../shared/dto/CommonDTO';
import { SkillMatchResultDTO } from '../dto/TicketDTO';

export interface FindMatchingAgentsQuery extends QueryBase {
  ticketId: string;
  strategy?: 'hybrid' | 'skill_first' | 'load_first';
  threshold?: number;
  limit?: number;
}

export const FIND_MATCHING_AGENTS_USE_CASE = Symbol('FindMatchingAgentsUseCase');

export interface IFindMatchingAgentsUseCase {
  execute(query: FindMatchingAgentsQuery): Promise<SkillMatchResultDTO[]>;
}
