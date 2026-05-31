import { TicketPriorityType } from '../../../domain/ticket/value-objects/TicketPriority';
import { TicketStatusType } from '../../../domain/ticket/value-objects/TicketStatus';

export interface TicketDTO {
  id: string;
  title: string;
  description: string;
  type: string;
  priority: TicketPriorityType;
  status: TicketStatusType;
  tenantId: string;
  agentId?: string | null;
  requiredSkills: Array<{
    skillId: string;
    minLevel: number;
    required: boolean;
  }>;
  createdAt: Date;
  updatedAt: Date;
  resolvedAt?: Date | null;
  closedAt?: Date | null;
}

export interface CreateTicketDTO {
  title: string;
  description: string;
  type: string;
  priority?: TicketPriorityType;
  requiredSkills: Array<{
    skillId: string;
    minLevel: number;
    required?: boolean;
  }>;
}

export interface UpdateTicketDTO {
  title?: string;
  description?: string;
  type?: string;
  priority?: TicketPriorityType;
  status?: TicketStatusType;
}

export interface AssignTicketDTO {
  agentId?: string;
  autoAssign?: boolean;
}

export interface SkillMatchResultDTO {
  agentId: string;
  agentName: string;
  score: number;
  skillScore: number;
  loadScore: number;
  loadFactor: number;
  skillMatches: Array<{
    skillId: string;
    skillName: string;
    requiredLevel: number;
    agentLevel: number;
    match: boolean;
  }>;
}

export interface TicketAssignmentResultDTO {
  assignment: {
    id: string;
    ticketId: string;
    agentId: string;
    reason: string;
    score?: number;
    status: string;
    createdAt: Date;
  };
  ticket: TicketDTO;
  matchResults: SkillMatchResultDTO[];
}

export interface TicketFilterDTO {
  status?: TicketStatusType;
  priority?: TicketPriorityType;
  agentId?: string;
  type?: string;
  createdAtFrom?: Date;
  createdAtTo?: Date;
}
