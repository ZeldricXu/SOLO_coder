import type { ID, Faction } from './common';
import type { CubeCoords } from './grid';
import type { DamageInstance, HealInstance, TurnPhase, Skill, StatusEffect } from './combat';

export type EventType = 
  | 'GAME_START'
  | 'GAME_END'
  | 'TURN_START'
  | 'TURN_END'
  | 'PHASE_CHANGE'
  | 'UNIT_MOVE'
  | 'UNIT_MOVE_START'
  | 'UNIT_MOVE_END'
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
  | 'ENTITY_INTERACTED'
  | 'ENTITY_DESTROYED'
  | 'ENTITY_ACTIVATED'
  | 'ENTITY_DEACTIVATED'
  | 'ENTITY_TELEPORTED'
  | 'SKILL_COOLDOWN_START'
  | 'SKILL_COOLDOWN_END'
  | 'DELAYED_SKILL_START'
  | 'DELAYED_SKILL_COMPLETE'
  | 'DELAYED_SKILL_INTERRUPTED'
  | 'INTERRUPT_TRIGGERED'
  | 'PASSIVE_TRIGGERED'
  | 'AURA_APPLIED'
  | 'AURA_REMOVED'
  | 'AURA_TICK'
  | 'VISIBILITY_CHANGED'
  | 'COMBAT_ROUND_START'
  | 'COMBAT_ROUND_END'
  | 'LEVEL_LOAD'
  | 'LEVEL_START'
  | 'LEVEL_COMPLETE'
  | 'LEVEL_FAIL'
  | 'LOG_MESSAGE'
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

export interface EventPayloadMap {
  GAME_START: Record<string, unknown>;
  GAME_END: Record<string, unknown>;
  TURN_START: { unitId: ID; turnNumber: number };
  TURN_END: { unitId: ID; turnNumber: number };
  PHASE_CHANGE: { oldPhase: TurnPhase; newPhase: TurnPhase; turnNumber: number };
  UNIT_MOVE: MoveEventData;
  UNIT_MOVE_START: { unitId: ID; from: CubeCoords; to: CubeCoords; path: CubeCoords[] };
  UNIT_MOVE_END: { unitId: ID; from: CubeCoords; to: CubeCoords; moveCost: number };
  UNIT_ATTACK: AttackEventData;
  UNIT_CAST_SKILL: SkillEventData;
  UNIT_DEATH: DeathEventData;
  UNIT_SPAWN: { unitId: ID; position: CubeCoords; faction: Faction };
  UNIT_STATUS_APPLIED: StatusEffectEventData;
  UNIT_STATUS_REMOVED: StatusEffectEventData;
  UNIT_STATUS_TICK: StatusEffectEventData;
  DAMAGE_DEALT: { sourceId: ID; targetId: ID; damage: DamageInstance };
  HEAL_APPLIED: { sourceId: ID; targetId: ID; heal: HealInstance };
  TERRAIN_CHANGED: TerrainEventData;
  OBJECT_SPAWNED: { objectId: ID; position: CubeCoords; type: string };
  OBJECT_REMOVED: { objectId: ID; position: CubeCoords };
  ENTITY_INTERACTED: { entityId: ID; interactorId: ID; position: CubeCoords };
  ENTITY_DESTROYED: { entityId: ID; destroyerId?: ID; position: CubeCoords };
  ENTITY_ACTIVATED: { entityId: ID; activatorId?: ID; position: CubeCoords };
  ENTITY_DEACTIVATED: { entityId: ID; deactivatorId?: ID; position: CubeCoords };
  ENTITY_TELEPORTED: { entityId: ID; from: CubeCoords; to: CubeCoords; portalId?: ID };
  SKILL_COOLDOWN_START: { unitId: ID; skillId: ID; cooldown: number };
  SKILL_COOLDOWN_END: { unitId: ID; skillId: ID };
  DELAYED_SKILL_START: DelayedSkillEventData;
  DELAYED_SKILL_COMPLETE: DelayedSkillEventData;
  DELAYED_SKILL_INTERRUPTED: DelayedSkillEventData;
  INTERRUPT_TRIGGERED: { interrupterId: ID; targetId: ID; reason: string };
  PASSIVE_TRIGGERED: { unitId: ID; skillId: ID; triggerType: string };
  AURA_APPLIED: { auraId: ID; sourceId: ID; targetId: ID; effect: StatusEffect };
  AURA_REMOVED: { auraId: ID; sourceId: ID; targetId: ID; effectId: ID };
  AURA_TICK: { auraId: ID; sourceId: ID; tickNumber: number };
  VISIBILITY_CHANGED: { tileCoords: CubeCoords; wasVisible: boolean; isVisible: boolean };
  COMBAT_ROUND_START: { roundNumber: number };
  COMBAT_ROUND_END: { roundNumber: number; summary?: Record<string, unknown> };
  LEVEL_LOAD: { levelId: ID; levelName: string };
  LEVEL_START: { levelId: ID; levelName: string };
  LEVEL_COMPLETE: { levelId: ID; turnNumber: number; stats?: Record<string, unknown> };
  LEVEL_FAIL: { levelId: ID; turnNumber: number; reason: string };
  LOG_MESSAGE: { level: 'info' | 'warn' | 'error' | 'debug'; message: string; data?: Record<string, unknown> };
  VICTORY: { winner: Faction; survivingUnits: ID[] };
  DEFEAT: { loser: Faction; deadUnits: ID[] };
  CUSTOM: Record<string, unknown>;
}

export type EventData<T extends EventType> = EventPayloadMap[T];
