import type { ID } from '../types/common';
import type { TurnPhase } from '../types/combat';
import type {
  TurnManagerState,
  TurnOrderConfig,
  TurnOrderEntry,
  DelayAction,
  RoundSummary,
  InterruptRequest
} from '../types/turn';
import { InterruptSystem } from './InterruptSystem';
import { RoundSummaryGenerator } from './RoundSummaryGenerator';

export interface UnitWithSpeed {
  id: ID;
  speed: number;
  [key: string]: unknown;
}

export type TurnHookName =
  | 'onTurnStart'
  | 'onTurnEnd'
  | 'onRoundStart'
  | 'onRoundEnd'
  | 'onPhaseChange'
  | 'onUnitAct'
  | 'onActionDelay';

export type TurnHookHandler = (context: TurnHookContext) => void | Promise<void>;

export interface TurnHookContext {
  manager: TurnManager;
  unitId?: ID;
  phase?: TurnPhase;
  roundNumber: number;
  state: TurnManagerState;
  [key: string]: unknown;
}

export interface TurnHook {
  name: TurnHookName;
  handler: TurnHookHandler;
  priority: number;
  source: string;
}

export class TurnManager {
  private state: TurnManagerState;
  private currentTurnIndex: number;
  private hooks: Map<TurnHookName, TurnHook[]>;
  private units: Map<ID, UnitWithSpeed>;
  private interruptSystem: InterruptSystem;
  private summaryGenerator: RoundSummaryGenerator;
  private tiebreakerCounter: number;

  constructor(config: TurnOrderConfig, units: UnitWithSpeed[]) {
    this.units = new Map();
    for (const unit of units) {
      this.units.set(unit.id, unit);
    }

    this.hooks = new Map();
    this.tiebreakerCounter = 0;
    this.interruptSystem = new InterruptSystem(config.interruptPriorityBias || 0);
    this.summaryGenerator = new RoundSummaryGenerator(1);

    this.state = {
      currentRound: 1,
      currentPhase: 'start',
      currentUnitId: undefined,
      turnOrder: [],
      delayedActions: [],
      interruptQueue: [],
      turnHistory: [],
      config: { ...config },
      paused: false,
      fastForward: false
    };

    this.currentTurnIndex = -1;
    this.buildTurnOrder();
  }

  buildTurnOrder(): TurnOrderEntry[] {
    const unitsList = Array.from(this.units.values());

    this.tiebreakerCounter = 0;
    const entries: TurnOrderEntry[] = unitsList.map((unit) => {
      const tiebreaker = this.tiebreakerCounter++;
      return {
        unitId: unit.id,
        speed: unit.speed,
        tiebreaker,
        isActive: true,
        hasActed: false,
        delayCounter: 0
      };
    });

    const sign = this.state.config.speedSortOrder === 'asc' ? 1 : -1;

    entries.sort((a, b) => {
      if (a.speed !== b.speed) {
        return (a.speed - b.speed) * sign;
      }
      return a.tiebreaker - b.tiebreaker;
    });

    this.state.turnOrder = entries;
    this.currentTurnIndex = -1;
    return [...entries];
  }

  private async executeHooks(name: TurnHookName, context: TurnHookContext): Promise<void> {
    const hooks = this.hooks.get(name) || [];
    const sorted = [...hooks].sort((a, b) => a.priority - b.priority);

    for (const hook of sorted) {
      try {
        await hook.handler(context);
      } catch (e) {
        console.warn(`Hook ${name} from ${hook.source} failed:`, e);
      }
    }
  }

  addHook(name: TurnHookName, handler: TurnHookHandler, priority: number = 0, source: string = 'default'): void {
    if (!this.hooks.has(name)) {
      this.hooks.set(name, []);
    }
    this.hooks.get(name)!.push({ name, handler, priority, source });
  }

  removeHook(name: TurnHookName, source?: string): boolean {
    if (!this.hooks.has(name)) return false;
    if (!source) {
      this.hooks.delete(name);
      return true;
    }
    const hooks = this.hooks.get(name)!;
    const filtered = hooks.filter((h) => h.source !== source);
    if (filtered.length === 0) {
      this.hooks.delete(name);
    } else {
      this.hooks.set(name, filtered);
    }
    return filtered.length < hooks.length;
  }

  async startRound(): Promise<void> {
    this.state.currentRound = this.state.currentRound || 1;
    this.currentTurnIndex = -1;

    for (const entry of this.state.turnOrder) {
      entry.hasActed = false;
    }

    this.processDelayedActions();
    this.interruptSystem.clearExecuted();
    this.summaryGenerator.reset(this.state.currentRound);

    await this.executeHooks('onRoundStart', {
      manager: this,
      roundNumber: this.state.currentRound,
      state: this.getState()
    });

    await this.setPhase('start');
  }

  async nextUnit(): Promise<ID | null> {
    if (this.isRoundComplete()) {
      return null;
    }

    if (this.state.currentUnitId) {
      await this.endTurn();
    }

    this.currentTurnIndex++;
    let entry = this.state.turnOrder[this.currentTurnIndex];

    while (entry && (!entry.isActive || entry.hasActed || entry.delayCounter > 0)) {
      if (entry.delayCounter > 0) {
        entry.delayCounter--;
      }
      if (!entry.isActive || entry.hasActed) {
        this.currentTurnIndex++;
        entry = this.state.turnOrder[this.currentTurnIndex];
      } else {
        break;
      }
    }

    if (!entry) {
      return null;
    }

    this.state.currentUnitId = entry.unitId;
    this.summaryGenerator.addActingUnit(entry.unitId);

    if (this.state.config.enableInterrupts) {
      const context = this.createHookContext();
      const executed = this.interruptSystem.executeInterrupts(context, this.currentTurnIndex);
      if (executed.length > 0) {
        this.state.interruptQueue.push(...executed);
      }
    }

    await this.executeHooks('onTurnStart', {
      manager: this,
      unitId: entry.unitId,
      roundNumber: this.state.currentRound,
      state: this.getState()
    });

    await this.setPhase('action');

    await this.executeHooks('onUnitAct', {
      manager: this,
      unitId: entry.unitId,
      roundNumber: this.state.currentRound,
      state: this.getState()
    });

    return entry.unitId;
  }

  async endTurn(): Promise<void> {
    const unitId = this.state.currentUnitId;
    if (!unitId) return;

    await this.setPhase('end');

    await this.executeHooks('onTurnEnd', {
      manager: this,
      unitId,
      roundNumber: this.state.currentRound,
      state: this.getState()
    });

    const entry = this.state.turnOrder.find((e) => e.unitId === unitId);
    if (entry) {
      entry.hasActed = true;
    }

    this.state.currentUnitId = undefined;
  }

  async setPhase(phase: TurnPhase): Promise<void> {
    const oldPhase = this.state.currentPhase;
    if (oldPhase === phase) return;

    this.state.currentPhase = phase;

    await this.executeHooks('onPhaseChange', {
      manager: this,
      phase,
      roundNumber: this.state.currentRound,
      state: this.getState(),
      previousPhase: oldPhase
    });
  }

  delayAction(unitId: ID, delayTurns: number, reason: string = '', canBeInterrupted: boolean = true, resumeAction?: () => void): void {
    if (!this.state.config.enableDelayAction) return;

    const entry = this.state.turnOrder.find((e) => e.unitId === unitId);
    if (entry) {
      entry.delayCounter = delayTurns;
    }

    const delayAction: DelayAction = {
      unitId,
      delayUntil: this.state.currentRound + delayTurns,
      reason,
      canBeInterrupted,
      resumeAction
    };

    this.state.delayedActions.push(delayAction);

    const context = this.createHookContext();
    this.executeHooks('onActionDelay', {
      ...context,
      unitId,
      delayTurns,
      reason
    }).catch((e) => console.warn('onActionDelay hook failed:', e));
  }

  private processDelayedActions(): void {
    const currentRound = this.state.currentRound;
    const remaining: DelayAction[] = [];

    for (const action of this.state.delayedActions) {
      if (action.delayUntil <= currentRound) {
        if (action.resumeAction) {
          try {
            action.resumeAction();
          } catch (e) {
            console.warn('Resume action failed:', e);
          }
        }
      } else {
        remaining.push(action);
      }
    }

    this.state.delayedActions = remaining;
  }

  isRoundComplete(): boolean {
    return this.state.turnOrder.every(
      (entry) => !entry.isActive || entry.hasActed || entry.delayCounter > 0
    );
  }

  async completeRound(): Promise<RoundSummary> {
    while (this.state.currentUnitId || !this.isRoundComplete()) {
      if (this.state.currentUnitId) {
        await this.endTurn();
      }
      if (!this.isRoundComplete()) {
        await this.nextUnit();
      }
    }

    const summary = this.summaryGenerator.generateSummary();
    this.state.turnHistory.push(summary);

    await this.executeHooks('onRoundEnd', {
      manager: this,
      roundNumber: this.state.currentRound,
      state: this.getState(),
      summary
    });

    this.state.currentRound++;
    return summary;
  }

  getTurnOrder(): TurnOrderEntry[] {
    return [...this.state.turnOrder];
  }

  getCurrentUnit(): ID | undefined {
    return this.state.currentUnitId;
  }

  getCurrentPhase(): TurnPhase {
    return this.state.currentPhase;
  }

  getCurrentRound(): number {
    return this.state.currentRound;
  }

  getState(): TurnManagerState {
    return {
      ...this.state,
      turnOrder: this.state.turnOrder.map((e) => ({ ...e })),
      delayedActions: this.state.delayedActions.map((d) => ({ ...d })),
      turnHistory: this.state.turnHistory.map((h) => ({
        ...h,
        kills: new Map(h.kills),
        damageDealt: new Map(h.damageDealt),
        damageTaken: new Map(h.damageTaken),
        healingDone: new Map(h.healingDone),
        events: [...h.events]
      }))
    };
  }

  getInterruptSystem(): InterruptSystem {
    return this.interruptSystem;
  }

  getSummaryGenerator(): RoundSummaryGenerator {
    return this.summaryGenerator;
  }

  setUnitActive(unitId: ID, active: boolean): void {
    const entry = this.state.turnOrder.find((e) => e.unitId === unitId);
    if (entry) {
      entry.isActive = active;
    }
  }

  updateUnitSpeed(unitId: ID, newSpeed: number): void {
    const unit = this.units.get(unitId);
    if (unit) {
      unit.speed = newSpeed;
    }
    // Save acted state per unit so we don't lose progress
    const actedMap = new Map<ID, boolean>();
    const activeMap = new Map<ID, boolean>();
    const delayMap = new Map<ID, number>();
    for (const e of this.state.turnOrder) {
      actedMap.set(e.unitId, e.hasActed);
      activeMap.set(e.unitId, e.isActive);
      delayMap.set(e.unitId, e.delayCounter);
    }
    this.buildTurnOrder();
    // Restore acted/active/delay state
    for (const e of this.state.turnOrder) {
      if (actedMap.has(e.unitId)) {
        e.hasActed = actedMap.get(e.unitId)!;
        e.isActive = activeMap.get(e.unitId)!;
        e.delayCounter = delayMap.get(e.unitId)!;
      }
    }
  }

  addUnit(unit: UnitWithSpeed): void {
    this.units.set(unit.id, unit);
    this.buildTurnOrder();
  }

  removeUnit(unitId: ID): void {
    this.units.delete(unitId);
    this.state.turnOrder = this.state.turnOrder.filter((e) => e.unitId !== unitId);
  }

  pause(): void {
    this.state.paused = true;
  }

  resume(): void {
    this.state.paused = false;
  }

  isPaused(): boolean {
    return this.state.paused;
  }

  setFastForward(value: boolean): void {
    this.state.fastForward = value;
  }

  isFastForward(): boolean {
    return this.state.fastForward;
  }

  getRoundHistory(): RoundSummary[] {
    return this.state.turnHistory.map((h) => ({
      ...h,
      kills: new Map(h.kills),
      damageDealt: new Map(h.damageDealt),
      damageTaken: new Map(h.damageTaken),
      healingDone: new Map(h.healingDone),
      events: [...h.events]
    }));
  }

  private createHookContext(): TurnHookContext {
    return {
      manager: this,
      unitId: this.state.currentUnitId,
      phase: this.state.currentPhase,
      roundNumber: this.state.currentRound,
      state: this.getState()
    };
  }

  toJSON(): Record<string, unknown> {
    return {
      state: {
        currentRound: this.state.currentRound,
        currentPhase: this.state.currentPhase,
        currentUnitId: this.state.currentUnitId,
        turnOrder: this.state.turnOrder.map((e) => ({ ...e })),
        delayedActions: this.state.delayedActions.map((d) => ({
          unitId: d.unitId,
          delayUntil: d.delayUntil,
          reason: d.reason,
          canBeInterrupted: d.canBeInterrupted
        })),
        interruptQueue: this.state.interruptQueue.map((i) => ({ ...i })),
        turnHistory: this.state.turnHistory.map((h) => ({
          roundNumber: h.roundNumber,
          actingUnits: [...h.actingUnits],
          kills: Array.from(h.kills.entries()),
          damageDealt: Array.from(h.damageDealt.entries()),
          damageTaken: Array.from(h.damageTaken.entries()),
          healingDone: Array.from(h.healingDone.entries()),
          events: [...h.events]
        })),
        config: { ...this.state.config },
        paused: this.state.paused,
        fastForward: this.state.fastForward
      },
      currentTurnIndex: this.currentTurnIndex,
      tiebreakerCounter: this.tiebreakerCounter,
      units: Array.from(this.units.entries()),
      interruptSystem: this.interruptSystem.toJSON(),
      summaryGenerator: this.summaryGenerator.toJSON()
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    const stateData = data.state as Record<string, unknown>;

    this.state = {
      currentRound: stateData.currentRound as number,
      currentPhase: stateData.currentPhase as TurnPhase,
      currentUnitId: stateData.currentUnitId as ID | undefined,
      turnOrder: (stateData.turnOrder as TurnOrderEntry[]) || [],
      delayedActions: (stateData.delayedActions as DelayAction[]) || [],
      interruptQueue: (stateData.interruptQueue as InterruptRequest[]) || [],
      turnHistory: (stateData.turnHistory as Array<Record<string, unknown>> || []).map((h) => ({
        roundNumber: h.roundNumber as number,
        actingUnits: h.actingUnits as ID[],
        kills: new Map(h.kills as Array<[string, number]>),
        damageDealt: new Map(h.damageDealt as Array<[string, number]>),
        damageTaken: new Map(h.damageTaken as Array<[string, number]>),
        healingDone: new Map(h.healingDone as Array<[string, number]>),
        events: h.events as unknown[]
      })),
      config: stateData.config as TurnOrderConfig,
      paused: stateData.paused as boolean,
      fastForward: stateData.fastForward as boolean
    };

    this.currentTurnIndex = data.currentTurnIndex as number;
    this.tiebreakerCounter = data.tiebreakerCounter as number;

    this.units = new Map();
    const unitsData = data.units as Array<[ID, UnitWithSpeed]> || [];
    for (const [id, unit] of unitsData) {
      this.units.set(id, unit);
    }

    this.interruptSystem = InterruptSystem.fromJSON(data.interruptSystem as Record<string, unknown>);
    this.summaryGenerator = RoundSummaryGenerator.fromJSON(data.summaryGenerator as Record<string, unknown>);
    this.hooks = new Map();
  }

  static fromJSON(data: Record<string, unknown>): TurnManager {
    const dummyConfig: TurnOrderConfig = {
      speedSortOrder: 'desc',
      enableDelayAction: true,
      enableInterrupts: true,
      interruptPriorityBias: 0
    };
    const manager = new TurnManager(dummyConfig, []);
    manager.fromJSON(data);
    return manager;
  }
}
