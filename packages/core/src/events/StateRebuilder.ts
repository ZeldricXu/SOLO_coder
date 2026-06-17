import {
  GameEvent,
  GameStateSnapshot,
  EventType,
  MoveEventData,
  AttackEventData,
  SkillEventData,
  StatusEffectEventData,
  DeathEventData,
  TerrainEventData,
  DelayedSkillEventData,
} from '../types';
import { createChecksum, deepClone, toJSON, fromJSON } from '../utils';

export type EventReducer<T = unknown> = (state: T, event: GameEvent) => T;

export class StateRebuilder {
  private reducers: Map<EventType, EventReducer> = new Map();
  private defaultReducer: EventReducer | null = null;

  constructor() {
    this.registerDefaultReducers();
  }

  private registerDefaultReducers(): void {
    this.registerReducer('UNIT_MOVE', this.reducerUnitMove.bind(this));
    this.registerReducer('UNIT_ATTACK', this.reducerUnitAttack.bind(this));
    this.registerReducer('UNIT_CAST_SKILL', this.reducerUnitCastSkill.bind(this));
    this.registerReducer('UNIT_DEATH', this.reducerUnitDeath.bind(this));
    this.registerReducer('UNIT_SPAWN', this.reducerUnitSpawn.bind(this));
    this.registerReducer('UNIT_STATUS_APPLIED', this.reducerUnitStatusApplied.bind(this));
    this.registerReducer('UNIT_STATUS_REMOVED', this.reducerUnitStatusRemoved.bind(this));
    this.registerReducer('UNIT_STATUS_TICK', this.reducerUnitStatusTick.bind(this));
    this.registerReducer('DAMAGE_DEALT', this.reducerDamageDealt.bind(this));
    this.registerReducer('HEAL_APPLIED', this.reducerHealApplied.bind(this));
    this.registerReducer('TERRAIN_CHANGED', this.reducerTerrainChanged.bind(this));
    this.registerReducer('TURN_START', this.reducerTurnStart.bind(this));
    this.registerReducer('TURN_END', this.reducerTurnEnd.bind(this));
    this.registerReducer('GAME_START', this.reducerGameStart.bind(this));
    this.registerReducer('GAME_END', this.reducerGameEnd.bind(this));
  }

  rebuild(
    events: GameEvent[],
    toIndex?: number,
    initialState: unknown = {},
    snapshots: GameStateSnapshot[] = []
  ): {
    state: unknown;
    lastEventIndex: number;
    snapshotUsed: GameStateSnapshot | null;
  } {
    const endIndex = toIndex !== undefined
      ? Math.min(toIndex, events.length - 1)
      : events.length - 1;

    if (endIndex < 0) {
      return {
        state: deepClone(initialState),
        lastEventIndex: -1,
        snapshotUsed: null,
      };
    }

    let currentState = deepClone(initialState);
    let startIdx = 0;
    let usedSnapshot: GameStateSnapshot | null = null;

    const applicableSnapshot = this.findBestSnapshot(snapshots, endIndex);
    if (applicableSnapshot) {
      currentState = deepClone(applicableSnapshot.state);
      startIdx = applicableSnapshot.eventIndex + 1;
      usedSnapshot = applicableSnapshot;
    }

    for (let i = startIdx; i <= endIndex; i++) {
      const event = events[i];
      currentState = this.applyEvent(currentState, event);
    }

    return {
      state: currentState,
      lastEventIndex: endIndex,
      snapshotUsed: usedSnapshot,
    };
  }

  private findBestSnapshot(
    snapshots: GameStateSnapshot[],
    targetIndex: number
  ): GameStateSnapshot | null {
    if (snapshots.length === 0) return null;

    let best: GameStateSnapshot | null = null;

    for (const snapshot of snapshots) {
      if (snapshot.eventIndex <= targetIndex) {
        if (!best || snapshot.eventIndex > best.eventIndex) {
          best = snapshot;
        }
      }
    }

    return best;
  }

  applyEvent<T = unknown>(state: T, event: GameEvent): T {
    const reducer = this.reducers.get(event.type) ?? this.defaultReducer;

    if (reducer) {
      try {
        return reducer(state as unknown, event) as T;
      } catch (e) {
        console.error(`Reducer error for event type ${event.type}:`, e);
        return deepClone(state);
      }
    }

    return this.defaultApply(state, event);
  }

  private defaultApply<T>(state: T, event: GameEvent): T {
    const cloned = deepClone(state) as Record<string, unknown>;

    if (!Array.isArray(cloned.eventLog)) {
      cloned.eventLog = [];
    }
    (cloned.eventLog as GameEvent[]).push({
      id: event.id,
      type: event.type,
      timestamp: event.timestamp,
      turnNumber: event.turnNumber,
      data: event.data,
      metadata: event.metadata,
      version: event.version,
    });

    cloned.lastEventId = event.id;
    cloned.lastEventType = event.type;
    cloned.currentTurn = event.turnNumber;

    return cloned as T;
  }

  registerReducer(type: EventType, reducer: EventReducer): void {
    this.reducers.set(type, reducer);
  }

  unregisterReducer(type: EventType): boolean {
    return this.reducers.delete(type);
  }

  setDefaultReducer(reducer: EventReducer | null): void {
    this.defaultReducer = reducer;
  }

  hasReducer(type: EventType): boolean {
    return this.reducers.has(type);
  }

  getRegisteredTypes(): EventType[] {
    return Array.from(this.reducers.keys()) as EventType[];
  }

  validateState(state: unknown, expectedChecksum?: string): {
    valid: boolean;
    actualChecksum: string;
    expectedChecksum?: string;
  } {
    const stateStr = JSON.stringify(state);
    const actualChecksum = createChecksum(stateStr);

    return {
      valid: expectedChecksum === undefined || actualChecksum === expectedChecksum,
      actualChecksum,
      expectedChecksum,
    };
  }

  validateSnapshot(snapshot: GameStateSnapshot): {
    valid: boolean;
    actualChecksum: string;
  } {
    const result = this.validateState(snapshot.state, snapshot.checksum);
    return {
      valid: result.valid,
      actualChecksum: result.actualChecksum,
    };
  }

  createSnapshot(state: unknown, lastEvent?: GameEvent): GameStateSnapshot {
    const stateStr = JSON.stringify(state);
    return {
      id: `snap-${createChecksum(stateStr + Date.now())}`,
      eventId: lastEvent?.id ?? '',
      eventIndex: -1,
      turnNumber: lastEvent?.turnNumber ?? 0,
      state: deepClone(state),
      timestamp: Date.now(),
      checksum: createChecksum(stateStr),
    };
  }

  private ensureUnitsMap(state: Record<string, unknown>): Record<string, unknown> {
    if (!state.units || typeof state.units !== 'object') {
      state.units = {};
    }
    return state.units as Record<string, unknown>;
  }

  private reducerUnitMove(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    const data = event.data as unknown as MoveEventData;
    const units = this.ensureUnitsMap(cloned);

    if (units[data.unitId]) {
      const unit = units[data.unitId] as Record<string, unknown>;
      unit.position = deepClone(data.to);
      unit.path = deepClone(data.path);
      unit.moveCost = data.moveCost;
    }

    return cloned;
  }

  private reducerUnitAttack(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    const data = event.data as unknown as AttackEventData;
    const units = this.ensureUnitsMap(cloned);

    if (units[data.defenderId] && data.isHit) {
      const defender = units[data.defenderId] as Record<string, unknown>;
      const currentHp = (defender.hp as number) ?? 0;
      defender.hp = Math.max(0, currentHp - (data.damage.finalDamage ?? data.damage.baseDamage ?? 0));
      defender.lastHitBy = data.attackerId;
      defender.lastHitCrit = data.isCrit;
    }

    if (!Array.isArray(cloned.combatLog)) {
      cloned.combatLog = [];
    }
    (cloned.combatLog as unknown[]).push({
      attackerId: data.attackerId,
      defenderId: data.defenderId,
      isHit: data.isHit,
      isCrit: data.isCrit,
      damage: data.damage,
      turnNumber: event.turnNumber,
    });

    return cloned;
  }

  private reducerUnitCastSkill(state: unknown, event: GameEvent): unknown {
    const cloned = deepClone(state) as Record<string, unknown>;
    const data = event.data as unknown as SkillEventData;
    const units = this.ensureUnitsMap(cloned);

    if (units[data.casterId]) {
      const caster = units[data.casterId] as Record<string, unknown>;
      caster.lastSkillCast = data.skillId;
      caster.lastSkillTarget = data.targetUnitId ?? data.targetCoords;
    }

    return cloned;
  }

  private reducerUnitDeath(state: unknown, event: GameEvent): unknown {
    const cloned = deepClone(state) as Record<string, unknown>;
    const data = event.data as unknown as DeathEventData;
    const units = this.ensureUnitsMap(cloned);

    if (units[data.unitId]) {
      const unit = units[data.unitId] as Record<string, unknown>;
      unit.isAlive = false;
      unit.hp = 0;
      unit.deathPosition = deepClone(data.position);
      unit.killedBy = data.killerId;
      unit.deathTurn = event.turnNumber;
    }

    return cloned;
  }

  private reducerUnitSpawn(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    const units = this.ensureUnitsMap(cloned);
    const data = event.data as Record<string, unknown>;
    const unitId = data.unitId as string;

    if (!units[unitId]) {
      const maxHp = (data.maxHp as number) ?? (data.hp as number) ?? 100;
      const hp = (data.hp as number) ?? maxHp;
      units[unitId] = {
        id: unitId,
        isAlive: true,
        spawnTurn: event.turnNumber,
        position: event.metadata.position ? deepClone(event.metadata.position) : undefined,
        faction: event.metadata.faction,
        maxHp,
        hp,
        ...data,
      };
      (units[unitId] as Record<string, unknown>).maxHp = maxHp;
      (units[unitId] as Record<string, unknown>).hp = hp;
    }

    return cloned;
  }

  private reducerUnitStatusApplied(state: unknown, event: GameEvent): unknown {
    const cloned = deepClone(state) as Record<string, unknown>;
    const data = event.data as unknown as StatusEffectEventData;
    const units = this.ensureUnitsMap(cloned);

    if (units[data.unitId]) {
      const unit = units[data.unitId] as Record<string, unknown>;
      if (!Array.isArray(unit.statusEffects)) {
        unit.statusEffects = [];
      }
      const effects = unit.statusEffects as Array<Record<string, unknown>>;
      const existingIdx = effects.findIndex((e) => e.effectId === data.effectId);

      if (existingIdx >= 0) {
        effects[existingIdx] = {
          ...effects[existingIdx],
          duration: data.duration,
          remainingDuration: data.remainingDuration,
          refreshedTurn: event.turnNumber,
        };
      } else {
        effects.push({
          effectId: data.effectId,
          effectType: data.effectType,
          duration: data.duration,
          remainingDuration: data.remainingDuration,
          sourceId: data.sourceId,
          appliedTurn: event.turnNumber,
        });
      }
    }

    return cloned;
  }

  private reducerUnitStatusRemoved(state: unknown, event: GameEvent): unknown {
    const cloned = deepClone(state) as Record<string, unknown>;
    const data = event.data as unknown as StatusEffectEventData;
    const units = this.ensureUnitsMap(cloned);

    if (units[data.unitId]) {
      const unit = units[data.unitId] as Record<string, unknown>;
      if (Array.isArray(unit.statusEffects)) {
        unit.statusEffects = (unit.statusEffects as Array<Record<string, unknown>>).filter(
          (e) => e.effectId !== data.effectId
        );
      }
    }

    return cloned;
  }

  private reducerUnitStatusTick(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    const data = event.data as unknown as StatusEffectEventData;
    const units = this.ensureUnitsMap(cloned);

    if (units[data.unitId]) {
      const unit = units[data.unitId] as Record<string, unknown>;
      if (Array.isArray(unit.statusEffects)) {
        const effect = (unit.statusEffects as Array<Record<string, unknown>>).find(
          (e) => e.effectId === data.effectId
        );
        if (effect) {
          effect.remainingDuration = data.remainingDuration;
          if (data.tickData?.damage) {
            unit.hp = Math.max(0, ((unit.hp as number) ?? 0) - data.tickData.damage);
          }
          if (data.tickData?.heal) {
            const maxHp = (unit.maxHp as number) ?? Infinity;
            unit.hp = Math.min(maxHp, ((unit.hp as number) ?? 0) + data.tickData.heal);
          }
        }
      }
    }

    return cloned;
  }

  private reducerDamageDealt(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    const data = event.data as unknown as AttackEventData;
    const units = this.ensureUnitsMap(cloned);

    if (units[data.defenderId]) {
      const defender = units[data.defenderId] as Record<string, unknown>;
      const currentHp = (defender.hp as number) ?? 0;
      defender.hp = Math.max(0, currentHp - (data.damage.finalDamage ?? data.damage.baseDamage ?? 0));
    }

    return cloned;
  }

  private reducerHealApplied(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    const data = event.data as Record<string, unknown>;
    const units = this.ensureUnitsMap(cloned);
    const targetId = data.targetId as string ?? event.metadata.target;

    if (targetId && units[targetId]) {
      const target = units[targetId] as Record<string, unknown>;
      const maxHp = (target.maxHp as number) ?? Infinity;
      const healAmount = (data.amount as number) ?? 0;
      target.hp = Math.min(maxHp, ((target.hp as number) ?? 0) + healAmount);
      target.lastHealedBy = event.metadata.source;
      target.lastHealAmount = healAmount;
    }

    return cloned;
  }

  private reducerTerrainChanged(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    const data = event.data as unknown as TerrainEventData;

    if (!cloned.terrainChanges || !Array.isArray(cloned.terrainChanges)) {
      cloned.terrainChanges = [];
    }
    (cloned.terrainChanges as unknown[]).push({
      coords: deepClone(data.coords),
      oldTerrain: data.oldTerrain,
      newTerrain: data.newTerrain,
      source: data.source,
      turnNumber: event.turnNumber,
    });

    if (!cloned.grid || typeof cloned.grid !== 'object') {
      cloned.grid = {};
    }
    const grid = cloned.grid as Record<string, unknown>;
    const coordKey = `${data.coords.q},${data.coords.r},${data.coords.s ?? 0}`;
    grid[coordKey] = {
      terrain: data.newTerrain,
      lastChangedTurn: event.turnNumber,
    };

    return cloned;
  }

  private reducerTurnStart(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    cloned.currentTurn = event.turnNumber;
    cloned.phase = 'start';
    cloned.turnStartEventId = event.id;
    return cloned;
  }

  private reducerTurnEnd(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    cloned.phase = 'end';
    cloned.turnEndEventId = event.id;
    if (!Array.isArray(cloned.completedTurns)) {
      cloned.completedTurns = [];
    }
    (cloned.completedTurns as number[]).push(event.turnNumber);
    return cloned;
  }

  private reducerGameStart(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    cloned.gameStarted = true;
    cloned.gameStartTime = event.timestamp;
    cloned.gameStartEventId = event.id;
    return cloned;
  }

  private reducerGameEnd(state: unknown, event: GameEvent): unknown {
    const cloned = this.defaultApply(state, event) as Record<string, unknown>;
    cloned.gameEnded = true;
    cloned.gameEndTime = event.timestamp;
    cloned.gameEndEventId = event.id;
    cloned.gameResult = event.type === 'VICTORY' ? 'victory' : event.type === 'DEFEAT' ? 'defeat' : 'draw';
    return cloned;
  }

  toJSON(): Record<string, unknown> {
    return {
      registeredTypes: Array.from(this.reducers.keys()),
      hasDefaultReducer: this.defaultReducer !== null,
    };
  }

  static fromJSON(_data: Record<string, unknown>): StateRebuilder {
    return new StateRebuilder();
  }

  serialize(): string {
    return toJSON(this.toJSON());
  }

  static deserialize(json: string): StateRebuilder {
    const data = fromJSON<Record<string, unknown>>(json);
    return StateRebuilder.fromJSON(data);
  }
}

export type {
  MoveEventData,
  AttackEventData,
  SkillEventData,
  StatusEffectEventData,
  DeathEventData,
  TerrainEventData,
  DelayedSkillEventData,
};
