import type {
  LevelConfig,
  VictoryCondition,
  CubeCoords,
  Faction,
  CombatUnit,
  ID,
  HexGridConfig,
  HexTile,
  GameEvent,
  EventStoreConfig,
  EventFilter,
  EventSubscriber,
  GameStateSnapshot,
  ReplaySession,
  UndoStack
} from '../types';
import { HexGrid } from '../grid/HexGrid';
import { Pathfinder } from '../grid/Pathfinding';
import { generateId } from '../utils';

export interface LevelState {
  id: ID;
  config: LevelConfig | null;
  grid: HexGrid | null;
  pathfinder: Pathfinder | null;
  units: Map<ID, CombatUnit>;
  isStarted: boolean;
  currentTurn: number;
  victoryConditions: VictoryCondition[];
  defeatConditions: VictoryCondition[];
  triggeredReinforcements: Set<number>;
  winner?: Faction;
}

export interface ReinforcementResult {
  turn: number;
  faction: Faction;
  spawnedUnits: CombatUnit[];
  failedPositions: CubeCoords[];
}

export interface VictoryProgress {
  conditionId: ID;
  type: VictoryCondition['type'];
  description: string;
  targetFaction: Faction;
  progress: number;
  targetProgress: number;
  percentage: number;
  isCompleted: boolean;
}

export class EventStore {
  private events: GameEvent[];
  private subscribers: Map<ID, EventSubscriber>;
  private snapshots: GameStateSnapshot[];
  private undoStack: UndoStack;
  private config: EventStoreConfig;
  private currentEventIndex: number;

  constructor(config?: Partial<EventStoreConfig>) {
    this.config = {
      maxEvents: config?.maxEvents ?? 10000,
      enableCompression: config?.enableCompression ?? false,
      enableSnapshots: config?.enableSnapshots ?? true,
      snapshotInterval: config?.snapshotInterval ?? 100,
      persistenceAdapter: config?.persistenceAdapter ?? 'memory'
    };
    this.events = [];
    this.subscribers = new Map();
    this.snapshots = [];
    this.currentEventIndex = -1;
    this.undoStack = {
      events: [],
      snapshots: [],
      currentIndex: -1,
      maxSize: 50
    };
  }

  publish(event: Omit<GameEvent, 'id' | 'timestamp' | 'version'>): GameEvent {
    const fullEvent: GameEvent = {
      ...event,
      id: generateId(),
      timestamp: Date.now(),
      version: 1
    };

    this.events.push(fullEvent);
    this.currentEventIndex = this.events.length - 1;

    if (this.events.length > this.config.maxEvents) {
      this.events.shift();
      this.currentEventIndex--;
    }

    if (this.config.enableSnapshots && 
        this.events.length % this.config.snapshotInterval === 0) {
      this.takeSnapshot(fullEvent);
    }

    this.notifySubscribers(fullEvent);

    return fullEvent;
  }

  subscribe(subscriber: Omit<EventSubscriber, 'id'> & { id?: ID }): ID {
    const id = subscriber.id ?? generateId();
    this.subscribers.set(id, { ...subscriber, id } as EventSubscriber);
    return id;
  }

  unsubscribe(id: ID): boolean {
    return this.subscribers.delete(id);
  }

  query(filter: EventFilter): GameEvent[] {
    return this.events.filter(event => this.matchesFilter(event, filter));
  }

  getEvents(): readonly GameEvent[] {
    return this.events;
  }

  getEventCount(): number {
    return this.events.length;
  }

  getEventById(id: ID): GameEvent | undefined {
    return this.events.find(e => e.id === id);
  }

  getLatestEvent(): GameEvent | undefined {
    return this.events[this.events.length - 1];
  }

  takeSnapshot(event: GameEvent): GameStateSnapshot {
    const snapshot: GameStateSnapshot = {
      id: generateId(),
      eventId: event.id,
      eventIndex: this.currentEventIndex,
      turnNumber: event.turnNumber,
      state: null,
      timestamp: Date.now(),
      checksum: ''
    };
    this.snapshots.push(snapshot);
    return snapshot;
  }

  getSnapshots(): readonly GameStateSnapshot[] {
    return this.snapshots;
  }

  createReplay(metadata: Record<string, unknown> = {}): ReplaySession {
    return {
      id: generateId(),
      events: [...this.events],
      snapshots: [...this.snapshots],
      startTime: this.events[0]?.timestamp ?? Date.now(),
      endTime: this.events[this.events.length - 1]?.timestamp,
      metadata,
      currentEventIndex: 0,
      isPlaying: false,
      playbackSpeed: 1
    };
  }

  clear(): void {
    this.events = [];
    this.subscribers.clear();
    this.snapshots = [];
    this.currentEventIndex = -1;
    this.undoStack = {
      events: [],
      snapshots: [],
      currentIndex: -1,
      maxSize: this.undoStack.maxSize
    };
  }

  private matchesFilter(event: GameEvent, filter: EventFilter): boolean {
    if (filter.types && !filter.types.includes(event.type)) return false;
    if (filter.sources && event.metadata.source && !filter.sources.includes(event.metadata.source)) return false;
    if (filter.targets && event.metadata.target && !filter.targets.includes(event.metadata.target)) return false;
    if (filter.factions && event.metadata.faction && !filter.factions.includes(event.metadata.faction)) return false;
    if (filter.turnRange) {
      const [min, max] = filter.turnRange;
      if (event.turnNumber < min || event.turnNumber > max) return false;
    }
    if (filter.timestampRange) {
      const [start, end] = filter.timestampRange;
      if (event.timestamp < start || event.timestamp > end) return false;
    }
    if (filter.customFilter && !filter.customFilter(event)) return false;
    return true;
  }

  private notifySubscribers(event: GameEvent): void {
    const sortedSubscribers = Array.from(this.subscribers.values())
      .sort((a, b) => a.priority - b.priority);

    for (const subscriber of sortedSubscribers) {
      if (this.matchesFilter(event, subscriber.filter)) {
        try {
          subscriber.callback(event);
        } catch (e) {
          console.error('Event subscriber error:', e);
        }
        if (subscriber.once) {
          this.subscribers.delete(subscriber.id);
        }
      }
    }
  }

  toJSON(): Record<string, unknown> {
    return {
      config: this.config,
      events: this.events,
      snapshots: this.snapshots,
      currentEventIndex: this.currentEventIndex,
      undoStack: {
        ...this.undoStack,
        events: this.undoStack.events,
        snapshots: this.undoStack.snapshots
      }
    };
  }

  static fromJSON(data: Record<string, unknown>): EventStore {
    const store = new EventStore(data.config as Partial<EventStoreConfig>);
    store.events = data.events as GameEvent[];
    store.snapshots = data.snapshots as GameStateSnapshot[];
    store.currentEventIndex = data.currentEventIndex as number;
    const undoData = data.undoStack as Record<string, unknown>;
    store.undoStack = {
      events: undoData.events as GameEvent[],
      snapshots: undoData.snapshots as GameStateSnapshot[],
      currentIndex: undoData.currentIndex as number,
      maxSize: undoData.maxSize as number
    };
    return store;
  }
}

export class LevelManager {
  private state: LevelState;
  private eventStore: EventStore;

  constructor(eventStore?: EventStore) {
    this.eventStore = eventStore ?? new EventStore();
    this.state = {
      id: generateId(),
      config: null,
      grid: null,
      pathfinder: null,
      units: new Map(),
      isStarted: false,
      currentTurn: 0,
      victoryConditions: [],
      defeatConditions: [],
      triggeredReinforcements: new Set()
    };
  }

  loadLevel(config: LevelConfig, gridConfig?: HexGridConfig): void {
    this.resetLevel();
    this.state.config = config;
    this.state.id = config.id;
    this.state.currentTurn = config.startingTurn ?? 1;
    this.state.victoryConditions = config.victoryConditions.map(vc => ({ ...vc, isCompleted: false, progress: 0 }));
    this.state.defeatConditions = config.defeatConditions.map(dc => ({ ...dc, isCompleted: false, progress: 0 }));

    if (gridConfig) {
      this.state.grid = new HexGrid(gridConfig);
      this.state.pathfinder = new Pathfinder(this.state.grid);
    } else {
      this.state.grid = new HexGrid({
        width: 20,
        height: 15,
        orientation: 'pointy',
        defaultTerrain: 'plain',
        tileSize: 32
      });
      this.state.pathfinder = new Pathfinder(this.state.grid);
    }

    for (const [faction, factionConfig] of Object.entries(config.factions)) {
      factionConfig.units.forEach((unitId, index) => {
        const position = factionConfig.startingPositions[index];
        if (position) {
          const unit = this.createUnit(unitId, faction as Faction, position);
          this.state.units.set(unit.id, unit);
          this.state.grid?.addUnit(position, unit.id);
        }
      });
    }

    this.eventStore.publish({
      type: 'CUSTOM',
      turnNumber: this.state.currentTurn,
      data: { levelId: config.id, action: 'loaded' },
      metadata: { faction: 'neutral' }
    });
  }

  startLevel(): void {
    if (!this.state.config) {
      throw new Error('Cannot start level: no level loaded');
    }
    if (this.state.isStarted) {
      return;
    }

    this.state.isStarted = true;

    this.eventStore.publish({
      type: 'GAME_START',
      turnNumber: this.state.currentTurn,
      data: { levelId: this.state.config.id },
      metadata: { faction: 'neutral' }
    });
  }

  getCurrentLevel(): LevelConfig | null {
    return this.state.config ? { ...this.state.config } : null;
  }

  resetLevel(): void {
    this.state.config = null;
    this.state.grid = null;
    this.state.pathfinder = null;
    this.state.units.clear();
    this.state.isStarted = false;
    this.state.currentTurn = 0;
    this.state.victoryConditions = [];
    this.state.defeatConditions = [];
    this.state.triggeredReinforcements.clear();
    this.state.winner = undefined;
  }

  getVictoryConditions(): VictoryCondition[] {
    return this.state.victoryConditions.map(vc => ({ ...vc }));
  }

  getDefeatConditions(): VictoryCondition[] {
    return this.state.defeatConditions.map(dc => ({ ...dc }));
  }

  checkVictoryProgress(): {
    victories: VictoryProgress[];
    defeats: VictoryProgress[];
    allVictoriesComplete: boolean;
    anyDefeatComplete: boolean;
  } {
    this.updateVictoryProgress();

    const victories = this.state.victoryConditions.map(vc => ({
      conditionId: vc.id,
      type: vc.type,
      description: vc.description,
      targetFaction: vc.targetFaction,
      progress: vc.progress,
      targetProgress: vc.targetProgress,
      percentage: vc.targetProgress > 0 ? Math.min(100, (vc.progress / vc.targetProgress) * 100) : 0,
      isCompleted: vc.isCompleted
    }));

    const defeats = this.state.defeatConditions.map(dc => ({
      conditionId: dc.id,
      type: dc.type,
      description: dc.description,
      targetFaction: dc.targetFaction,
      progress: dc.progress,
      targetProgress: dc.targetProgress,
      percentage: dc.targetProgress > 0 ? Math.min(100, (dc.progress / dc.targetProgress) * 100) : 0,
      isCompleted: dc.isCompleted
    }));

    const allVictoriesComplete = victories.length > 0 && victories.every(v => v.isCompleted);
    const anyDefeatComplete = defeats.some(d => d.isCompleted);

    if (allVictoriesComplete && !this.state.winner) {
      const enemyEliminated = victories.find(v =>
        v.type === 'eliminate' && v.targetFaction === 'enemy' && v.isCompleted
      );
      this.state.winner = enemyEliminated ? 'player' : (victories.length > 0 ? 'player' : 'enemy');
      this.eventStore.publish({
        type: 'VICTORY',
        turnNumber: this.state.currentTurn,
        data: { winner: this.state.winner },
        metadata: { faction: this.state.winner }
      });
    }

    if (anyDefeatComplete && !this.state.winner) {
      const playerEliminated = defeats.find(d =>
        d.type === 'eliminate' && d.targetFaction === 'player' && d.isCompleted
      );
      this.state.winner = playerEliminated ? 'enemy' : (defeats.length > 0 ? 'enemy' : 'player');
      this.eventStore.publish({
        type: 'DEFEAT',
        turnNumber: this.state.currentTurn,
        data: { winner: this.state.winner },
        metadata: { faction: this.state.winner }
      });
    }

    return { victories, defeats, allVictoriesComplete, anyDefeatComplete };
  }

  triggerReinforcements(turn: number): ReinforcementResult[] {
    if (!this.state.config?.reinforcements) {
      return [];
    }

    const results: ReinforcementResult[] = [];
    const turnReinforcements = this.state.config.reinforcements.filter(r => r.turn === turn);

    for (const reinforcement of turnReinforcements) {
      if (this.state.triggeredReinforcements.has(turn)) {
        continue;
      }

      const spawnedUnits: CombatUnit[] = [];
      const failedPositions: CubeCoords[] = [];

      reinforcement.unitIds.forEach((unitId, index) => {
        const position = reinforcement.positions[index];
        if (!position) {
          return;
        }

        const tile = this.state.grid?.getTile(position);
        if (!tile || tile.units.length > 0 || this.state.grid?.blocksMovement(position)) {
          failedPositions.push(position);
          return;
        }

        const unit = this.createUnit(unitId, reinforcement.faction, position);
        this.state.units.set(unit.id, unit);
        this.state.grid?.addUnit(position, unit.id);
        spawnedUnits.push(unit);

        this.eventStore.publish({
          type: 'UNIT_SPAWN',
          turnNumber: this.state.currentTurn,
          data: { unitId: unit.id, reinforcement: true },
          metadata: {
            faction: reinforcement.faction,
            position: position
          }
        });
      });

      results.push({
        turn,
        faction: reinforcement.faction,
        spawnedUnits,
        failedPositions
      });
    }

    this.state.triggeredReinforcements.add(turn);
    return results;
  }

  getState(): Readonly<LevelState> {
    return this.state;
  }

  getGrid(): HexGrid | null {
    return this.state.grid;
  }

  getPathfinder(): Pathfinder | null {
    return this.state.pathfinder;
  }

  getUnits(): Map<ID, CombatUnit> {
    return new Map(this.state.units);
  }

  getUnitsByFaction(faction: Faction): CombatUnit[] {
    return Array.from(this.state.units.values()).filter(u => u.faction === faction);
  }

  getUnitById(id: ID): CombatUnit | undefined {
    return this.state.units.get(id);
  }

  getCurrentTurn(): number {
    return this.state.currentTurn;
  }

  setCurrentTurn(turn: number): void {
    this.state.currentTurn = turn;
  }

  getEventStore(): EventStore {
    return this.eventStore;
  }

  getWinner(): Faction | undefined {
    return this.state.winner;
  }

  isStarted(): boolean {
    return this.state.isStarted;
  }

  private createUnit(templateId: ID, faction: Faction, coords: CubeCoords): CombatUnit {
    return {
      id: generateId(),
      name: `Unit_${templateId}`,
      faction,
      templateId,
      coords: { ...coords },
      direction: 0,
      stats: {
        maxHp: 100,
        hp: 100,
        maxMp: 50,
        mp: 50,
        attack: 20,
        defense: 10,
        magicAttack: 15,
        magicDefense: 8,
        speed: 10,
        accuracy: 85,
        evasion: 10,
        critRate: 10,
        critDamage: 150,
        armorPenetration: 0,
        moveRange: 4,
        attackRange: 1,
        visionRange: 6,
        height: 1
      },
      attributes: {
        hp: { current: 100, max: 100, min: 0 },
        mp: { current: 50, max: 50, min: 0 },
        attack: { base: 20, modifiers: [], current: 20 },
        defense: { base: 10, modifiers: [], current: 10 },
        magicAttack: { base: 15, modifiers: [], current: 15 },
        magicDefense: { base: 8, modifiers: [], current: 8 },
        speed: { base: 10, modifiers: [], current: 10 },
        accuracy: { base: 85, modifiers: [], current: 85 },
        evasion: { base: 10, modifiers: [], current: 10 },
        critRate: { base: 10, modifiers: [], current: 10 },
        critDamage: { base: 150, modifiers: [], current: 150 },
        armorPenetration: { base: 0, modifiers: [], current: 0 },
        moveRange: { base: 4, modifiers: [], current: 4 },
        attackRange: { base: 1, modifiers: [], current: 1 },
        visionRange: { base: 6, modifiers: [], current: 6 }
      },
      skills: [],
      passiveSkills: [],
      statusEffects: [],
      resistances: [],
      affinities: [],
      equipment: [],
      isAlive: true,
      hasActed: false,
      hasMoved: false,
      isDelaying: false,
      tags: []
    };
  }

  private updateVictoryProgress(): void {
    for (const condition of this.state.victoryConditions) {
      this.updateConditionProgress(condition);
    }
    for (const condition of this.state.defeatConditions) {
      this.updateConditionProgress(condition);
    }
  }

  private updateConditionProgress(condition: VictoryCondition): void {
    switch (condition.type) {
      case 'eliminate': {
        const targetFaction = condition.params.targetFaction as Faction;
        const targetUnits = this.getUnitsByFaction(targetFaction);
        const aliveUnits = targetUnits.filter(u => u.isAlive);
        const totalUnits = Math.max(1, targetUnits.length);
        const eliminated = totalUnits - aliveUnits.length;
        condition.progress = eliminated;
        condition.targetProgress = totalUnits;
        condition.isCompleted = aliveUnits.length === 0;
        break;
      }
      case 'capture': {
        const positions = condition.params.positions as CubeCoords[] ?? [];
        const capturingFaction = condition.targetFaction;
        let captured = 0;
        for (const pos of positions) {
          const tile = this.state.grid?.getTile(pos);
          const unitOnTile = tile?.units
            .map(id => this.state.units.get(id))
            .find(u => u?.faction === capturingFaction && u.isAlive);
          if (unitOnTile) captured++;
        }
        condition.progress = captured;
        condition.targetProgress = positions.length;
        condition.isCompleted = captured >= positions.length;
        break;
      }
      case 'survive': {
        const targetTurns = (condition.params.turns as number) ?? 0;
        condition.progress = this.state.currentTurn;
        condition.targetProgress = targetTurns;
        condition.isCompleted = this.state.currentTurn >= targetTurns;
        break;
      }
      case 'turnLimit': {
        const limit = (condition.params.limit as number) ?? Infinity;
        condition.progress = this.state.currentTurn;
        condition.targetProgress = limit;
        condition.isCompleted = this.state.currentTurn > limit;
        break;
      }
      case 'custom':
      default:
        break;
    }
  }

  toJSON(): Record<string, unknown> {
    return {
      state: {
        id: this.state.id,
        config: this.state.config,
        grid: this.state.grid?.toJSON(),
        units: Array.from(this.state.units.entries()),
        isStarted: this.state.isStarted,
        currentTurn: this.state.currentTurn,
        victoryConditions: this.state.victoryConditions,
        defeatConditions: this.state.defeatConditions,
        triggeredReinforcements: Array.from(this.state.triggeredReinforcements),
        winner: this.state.winner
      },
      eventStore: this.eventStore.toJSON()
    };
  }

  static fromJSON(data: Record<string, unknown>): LevelManager {
    const stateData = data.state as Record<string, unknown>;
    const eventStore = EventStore.fromJSON(data.eventStore as Record<string, unknown>);
    const manager = new LevelManager(eventStore);

    manager.state.id = stateData.id as ID;
    manager.state.config = stateData.config as LevelConfig | null;
    manager.state.isStarted = stateData.isStarted as boolean;
    manager.state.currentTurn = stateData.currentTurn as number;
    manager.state.victoryConditions = stateData.victoryConditions as VictoryCondition[];
    manager.state.defeatConditions = stateData.defeatConditions as VictoryCondition[];
    manager.state.triggeredReinforcements = new Set(stateData.triggeredReinforcements as number[]);
    manager.state.winner = stateData.winner as Faction | undefined;

    if (stateData.grid) {
      manager.state.grid = HexGrid.fromJSON(stateData.grid as Record<string, unknown>);
      manager.state.pathfinder = new Pathfinder(manager.state.grid);
    }

    const unitsEntries = stateData.units as Array<[ID, CombatUnit]>;
    for (const [id, unit] of unitsEntries) {
      manager.state.units.set(id, unit);
    }

    return manager;
  }
}
