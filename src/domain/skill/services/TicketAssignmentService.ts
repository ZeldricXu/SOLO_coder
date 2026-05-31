import { Ticket } from '../../ticket/entities/Ticket';
import { Agent } from '../entities/Agent';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';
import { NoMatchingAgentError } from '../../shared/errors/DomainError';

export interface SkillMatchResult {
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

export interface IMatchingStrategy {
  calculateScore(
    agent: Agent,
    ticket: Ticket,
    weights?: { skillWeight: number; loadWeight: number }
  ): SkillMatchResult;
}

export class HybridMatchingStrategy implements IMatchingStrategy {
  private readonly DEFAULT_SKILL_WEIGHT = 0.6;
  private readonly DEFAULT_LOAD_WEIGHT = 0.4;

  calculateScore(
    agent: Agent,
    ticket: Ticket,
    weights?: { skillWeight: number; loadWeight: number }
  ): SkillMatchResult {
    const skillWeight = weights?.skillWeight ?? this.DEFAULT_SKILL_WEIGHT;
    const loadWeight = weights?.skillWeight ?? this.DEFAULT_LOAD_WEIGHT;

    const requiredSkills = ticket.requiredSkills.map(req => ({
      skillId: req.skillId,
      minLevel: req.minLevel
    }));

    const skillScore = agent.calculateSkillMatchScore(requiredSkills);
    const loadScore = agent.load.getLoadScore();
    const loadFactor = agent.load.loadFactor;

    const skillMatches = ticket.requiredSkills.map(req => {
      const agentLevel = agent.getSkillLevel(req.skillId);
      return {
        skillId: req.skillId.value,
        skillName: 'Unknown',
        requiredLevel: req.minLevel,
        agentLevel,
        match: agentLevel >= req.minLevel
      };
    });

    const finalScore = (skillScore * skillWeight) + (loadScore * loadWeight);

    return {
      agentId: agent.id.value,
      agentName: agent.name,
      score: finalScore,
      skillScore,
      loadScore,
      loadFactor,
      skillMatches
    };
  }
}

export class SkillFirstMatchingStrategy implements IMatchingStrategy {
  calculateScore(agent: Agent, ticket: Ticket): SkillMatchResult {
    const strategy = new HybridMatchingStrategy();
    return strategy.calculateScore(agent, ticket, { skillWeight: 0.9, loadWeight: 0.1 });
  }
}

export class LoadFirstMatchingStrategy implements IMatchingStrategy {
  calculateScore(agent: Agent, ticket: Ticket): SkillMatchResult {
    const strategy = new HybridMatchingStrategy();
    return strategy.calculateScore(agent, ticket, { skillWeight: 0.1, loadWeight: 0.9 });
  }
}

export class TicketAssignmentDomainService {
  private readonly DEFAULT_THRESHOLD = 0.5;
  private readonly DEFAULT_LIMIT = 10;

  findMatchingAgents(
    ticket: Ticket,
    availableAgents: Agent[],
    strategy: IMatchingStrategy = new HybridMatchingStrategy(),
    options: { threshold?: number; limit?: number } = {}
  ): SkillMatchResult[] {
    const threshold = options.threshold ?? this.DEFAULT_THRESHOLD;
    const limit = options.limit ?? this.DEFAULT_LIMIT;

    if (availableAgents.length === 0) {
      throw new NoMatchingAgentError('No available agents found for this ticket', {
        ticketId: ticket.id.value
      });
    }

    const matchResults = availableAgents
      .map(agent => strategy.calculateScore(agent, ticket))
      .filter(result => result.score >= threshold)
      .sort((a, b) => b.score - a.score);

    if (matchResults.length === 0) {
      throw new NoMatchingAgentError('No agents meet the skill requirements for this ticket', {
        ticketId: ticket.id.value,
        threshold,
        checkedAgents: availableAgents.length
      });
    }

    return matchResults.slice(0, limit);
  }

  selectBestAgent(
    ticket: Ticket,
    availableAgents: Agent[],
    strategy: IMatchingStrategy = new HybridMatchingStrategy()
  ): SkillMatchResult {
    const matches = this.findMatchingAgents(ticket, availableAgents, strategy, { limit: 1 });
    return matches[0];
  }

  validateAssignment(
    ticket: Ticket,
    agent: Agent,
    strategy: IMatchingStrategy = new HybridMatchingStrategy(),
    threshold: number = 0.3
  ): { valid: boolean; score: number; reason?: string } {
    if (!agent.isAvailableForAssignment()) {
      return {
        valid: false,
        score: 0,
        reason: `Agent is not available (status: ${agent.status}, load: ${agent.load.currentLoad}/${agent.load.maxLoad})`
      };
    }

    const result = strategy.calculateScore(agent, ticket);

    if (result.score < threshold) {
      return {
        valid: false,
        score: result.score,
        reason: `Match score ${result.score.toFixed(3)} is below threshold ${threshold}`
      };
    }

    return {
      valid: true,
      score: result.score
    };
  }
}

export const getMatchingStrategy = (strategyName: string): IMatchingStrategy => {
  switch (strategyName) {
    case 'skill_first':
      return new SkillFirstMatchingStrategy();
    case 'load_first':
      return new LoadFirstMatchingStrategy();
    case 'hybrid':
    default:
      return new HybridMatchingStrategy();
  }
};
