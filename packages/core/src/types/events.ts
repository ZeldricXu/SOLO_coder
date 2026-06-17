import type { ID, Faction } from './common';
import type { CubeCoords } from './grid';
import type { DamageInstance, HealInstance, TurnPhase } from './combat';

export type EventType = 
  | 'GAME_START'
  | 'GAME_END'
  | 'TURN_START'
  | 'TURN_END'
  | 'PHASE_CHANGE'
  | 'UNIT_MOVE'
  | 'UNIT_ATTACK'
  | 'UNIT_CAST_SKILL'
  | 'UNIT_DEATH'
  | 'UNIT_SPAWN'
  | 'UNIT_STATUS_APPLIED'
  | 'UNIT_STATUS_REMOVED'
  | 'UNIT_STATUS_TICK'
  | 'DAMAGE_DEALT'
  | 'HEAL_APPLIED'
  | 'TERRAIN_CHANGED'
  | 'OBJECT_SPAWNED'
  | 'OBJECT_REMOVED'
  | 'DELAYED_SKILL_START'
  | 'DELAYED_SKILL_COMPLETE'
  | 'DELAYED_SKILL_INTERRUPTED'
  | 'INTERRUPT_TRIGGERED'
  | 'PASSIVE_TRIGGERED'
  | 'AURA_APPLIED'
  | 'AURA_REMOVED'
  | 'VISIBILITY_CHANGED'
  | 'VICTORY'
  | 'DEFEAT'
  | 'CUSTOM';

export interface GameEvent {
  id: ID;
  type: EventType;
  timestamp: number;
  turnNumber: number;
  data: Record<string, unknown>;
  metadata: {
    source?: ID;
    target?: ID;
    faction?: Faction;
    position?: CubeCoords;
  };
  version: number;
}

export interface EventStoreConfig {
  maxEvents: number;
  enableCompression: boolean;
  enableSnapshots: boolean;
  snapshotInterval: number;
  persistenceAdapter?: 'memory' | 'localStorage' | 'database';
}

export interface GameStateSnapshot {
  id: ID;
  eventId: ID;
  eventIndex: number;
  turnNumber: number;
  state: unknown;
  timestamp: number;
  checksum: string;
}

export interface ReplaySession {
  id: ID;
  events: GameEvent[];
  snapshots: GameStateSnapshot[];
  startTime: number;
  endTime?: number;
  metadata: Record<string, unknown>;
  currentEventIndex: number;
  isPlaying: boolean;
  playbackSpeed: number;
}

export interface UndoStack {
  events: GameEvent[];
  snapshots: GameStateSnapshot[];
  currentIndex: number;
  maxSize: number;
}

export interface EventFilter {
  types?: EventType[];
  sources?: ID[];
  targets?: ID[];
  factions?: Faction[];
  turnRange?: [number, number];
  timestampRange?: [number, number];
  customFilter?: (event: GameEvent) => boolean;
}

export interface EventSubscriber {
  id: ID;
  filter: EventFilter;
  callback: (event: GameEvent) => void;
  once: boolean;
  priority: number;
}

export interface MoveEventData {
  unitId: ID;
  from: CubeCoords;
  to: CubeCoords;
  path: CubeCoords[];
  moveCost: number;
}

export interface AttackEventData {
  attackerId: ID;
  defenderId: ID;
  skillId?: ID;
  isHit: boolean;
  isCrit: boolean;
  damage: DamageInstance;
}

export interface SkillEventData {
  casterId: ID;
  skillId: ID;
  targetCoords?: CubeCoords;
  targetUnitId?: ID;
  isDelayed: boolean;
  delayTurns?: number;
}

export interface StatusEffectEventData {
  unitId: ID;
  effectId: ID;
  effectType: string;
  duration: number;
  remainingDuration: number;
  sourceId: ID;
  isApplied: boolean;
  tickData?: {
    damage?: number;
    heal?: number;
  };
}

export interface DeathEventData {
  unitId: ID;
  killerId?: ID;
  position: CubeCoords;
  damage?: DamageInstance;
  effectsOnDeath: string[];
}

export interface TerrainEventData {
  coords: CubeCoords;
  oldTerrain: string;
  newTerrain: string;
  source?: ID;
}

export interface DelayedSkillEventData {
  skillId: ID;
  casterId: ID;
  targetCoords?: CubeCoords;
  targetUnitId?: ID;
  turnsRemaining?: number;
  totalTurns?: number;
  interruptedBy?: ID;
}

export interface VictoryCondition {
  id: ID;
  type: 'eliminate' | 'capture' | 'survive' | 'turnLimit' | 'custom';
  description: string;
  targetFaction: Faction;
  params: Record<string, unknown>;
  isCompleted: boolean;
  progress: number;
  targetProgress: number;
}

export interface LevelConfig {
  id: ID;
  name: string;
  description: string;
  mapId: ID;
  factions: Record<Faction, {
    units: ID[];
    startingPositions: CubeCoords[];
    aiProfile?: ID;
  }>;
  victoryConditions: VictoryCondition[];
  defeatConditions: VictoryCondition[];
  reinforcements?: Array<{
    turn: number;
    faction: Faction;
    unitIds: ID[];
    positions: CubeCoords[];
  }>;
  environmentalEffects?: Array<{
    type: string;
    data: Record<string, unknown>;
    trigger: string;
  }>;
  turnLimit?: number;
  startingTurn: number;
}
