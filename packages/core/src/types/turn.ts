import type { ID, Faction } from './common';
import type { TurnPhase } from './combat';

export interface TurnOrderEntry {
  unitId: ID;
  speed: number;
  tiebreaker: number;
  isActive: boolean;
  hasActed: boolean;
  delayCounter: number;
}

export interface TurnOrderConfig {
  speedSortOrder: 'desc' | 'asc';
  enableDelayAction: boolean;
  enableInterrupts: boolean;
  interruptPriorityBias: number;
}

export interface TurnPhaseHook {
  phase: TurnPhase | 'all';
  order: 'pre' | 'post';
  handler: (state: unknown) => void;
  priority: number;
  source: string;
}

export interface InterruptRequest {
  id: ID;
  sourceUnitId: ID;
  targetUnitId: ID;
  skillId: ID;
  priority: number;
  condition: (state: unknown) => boolean;
  isInserted: boolean;
  insertedAt: number;
}

export interface DelayAction {
  unitId: ID;
  delayUntil: number;
  reason: string;
  canBeInterrupted: boolean;
  resumeAction?: () => void;
}

export interface RoundSummary {
  roundNumber: number;
  actingUnits: ID[];
  kills: Map<Faction, number>;
  damageDealt: Map<Faction, number>;
  damageTaken: Map<Faction, number>;
  healingDone: Map<Faction, number>;
  events: unknown[];
}

export interface TurnManagerState {
  currentRound: number;
  currentPhase: TurnPhase;
  currentUnitId?: ID;
  turnOrder: TurnOrderEntry[];
  delayedActions: DelayAction[];
  interruptQueue: InterruptRequest[];
  turnHistory: RoundSummary[];
  config: TurnOrderConfig;
  paused: boolean;
  fastForward: boolean;
}
