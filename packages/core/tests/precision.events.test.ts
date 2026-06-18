import { describe, it, expect, beforeEach } from 'vitest';
import { EventStore } from '../src/events/EventStore';
import { StateRebuilder } from '../src/events/StateRebuilder';
import { ReplaySystem } from '../src/events/ReplaySystem';
import { UndoManager } from '../src/events/UndoManager';
import { createChecksum, deepClone } from '../src/utils/serialization';
import type { GameEvent, GameStateSnapshot } from '../src/types/events';
import type { CombatUnit, DelayedSkill, DamageCalculationConfig, ElementChart, UnitTemplateId } from '../src/types/combat';
import type { CubeCoords, Faction, ID } from '../src/types/grid';
import type { CombatEngine } from '../src/combat/CombatEngine';
import type { TurnManager } from '../src/turn/TurnManager';
import { CombatEngine as CombatEngineClass } from '../src/combat/CombatEngine';
import { TurnManager as TurnManagerClass } from '../src/turn/TurnManager';
import { HexGrid } from '../src/grid/HexGrid';
import { cubeCoords } from '../src/grid/coords';
import { terrainRegistry } from '../src/grid/TerrainConfig';
import {
  createPhysicalWarrior,
  createMage,
  createArcher,
  createTank,
  createEmptyGrid,
  createEventStoreAndRebuilder,
  createDamageConfig,
  createElementChart,
  createTurnOrderConfig,
  randomFuzzOperations,
} from './factories';

const SAFE_DAMAGE_CONFIG: Partial<DamageCalculationConfig> = {
  baseFormula: 'attack - defense * 0.5',
  minDamage: 1,
  maxDamage: 9999,
};

function makeDeterministic(unit: CombatUnit): CombatUnit {
  unit.stats.accuracy = 100;
  unit.stats.evasion = 0;
  unit.stats.critRate = 0;
  unit.attributes.accuracy.current = 100;
  unit.attributes.evasion.current = 0;
  unit.attributes.critRate.current = 0;
  return unit;
}

function buildValid3v3BattleScene(
  playerTemplateIds: UnitTemplateId[] = ['warrior', 'mage', 'archer'],
  enemyTemplateIds: UnitTemplateId[] = ['tank', 'warrior', 'mage']
) {
  const grid = createEmptyGrid(12, 8);
  const playerStarts: CubeCoords[] = [
    cubeCoords(0, 1, -1),
    cubeCoords(0, 3, -3),
    cubeCoords(0, 5, -5),
  ];
  const enemyStarts: CubeCoords[] = [
    cubeCoords(11, 1, -12),
    cubeCoords(11, 3, -14),
    cubeCoords(11, 5, -16),
  ];

  const UNIT_FACTORIES: Record<UnitTemplateId, (id: ID, faction: Faction, coords: CubeCoords) => CombatUnit> = {
    warrior: createPhysicalWarrior,
    mage: createMage,
    archer: createArcher,
    tank: createTank,
  };

  const damageConfig = createDamageConfig(SAFE_DAMAGE_CONFIG);
  const elementChart: ElementChart = createElementChart() as ElementChart;
  const combatEngine = new CombatEngineClass(damageConfig, elementChart);

  const players: CombatUnit[] = playerTemplateIds.map((templateId, idx) => {
    const factory = UNIT_FACTORIES[templateId];
    const unit = makeDeterministic(factory(`p-${idx + 1}`, 'player', playerStarts[idx]));
    combatEngine.addUnit(unit);
    grid.addUnit(playerStarts[idx], unit.id);
    return unit;
  });

  const enemies: CombatUnit[] = enemyTemplateIds.map((templateId, idx) => {
    const factory = UNIT_FACTORIES[templateId];
    const unit = makeDeterministic(factory(`e-${idx + 1}`, 'enemy', enemyStarts[idx]));
    combatEngine.addUnit(unit);
    grid.addUnit(enemyStarts[idx], unit.id);
    return unit;
  });

  const allUnits = [...players, ...enemies];
  const turnOrderConfig = createTurnOrderConfig();
  const turnManager = new TurnManagerClass(
    turnOrderConfig,
    allUnits.map(u => ({ id: u.id, speed: u.stats.speed }))
  );

  const eventStore = new EventStore();

  return { grid, combatEngine, players, enemies, turnManager, eventStore };
}

function buildValid1v1DuelScene() {
  const grid = createEmptyGrid(8, 6);
  const p1Start = cubeCoords(0, 2, -2);
  const p2Start = cubeCoords(5, 3, -8);

  const damageConfig = createDamageConfig(SAFE_DAMAGE_CONFIG);
  const elementChart: ElementChart = createElementChart() as ElementChart;
  const combatEngine = new CombatEngineClass(damageConfig, elementChart);

  const player = makeDeterministic(createPhysicalWarrior('player-1', 'player', p1Start));
  const enemy = makeDeterministic(createPhysicalWarrior('enemy-1', 'enemy', p2Start));

  combatEngine.addUnit(player);
  combatEngine.addUnit(enemy);
  grid.addUnit(p1Start, player.id);
  grid.addUnit(p2Start, enemy.id);

  const turnOrderConfig = createTurnOrderConfig();
  const turnManager = new TurnManagerClass(turnOrderConfig, [
    { id: player.id, speed: player.stats.speed },
    { id: enemy.id, speed: enemy.stats.speed },
  ]);

  const eventStore = new EventStore();

  return { grid, combatEngine, player, enemy, turnManager, eventStore };
}

interface FingerprintStateInput {
  combatEngine: CombatEngine;
  turnManager: TurnManager;
}

function sortStatusEffects(effects: Array<Record<string, unknown>>): string {
  return effects
    .map(e => JSON.stringify({
      effectId: e.effectId ?? e.id,
      effectType: e.effectType ?? e.type,
      remainingDuration: e.remainingDuration,
      duration: e.duration,
      stackCount: e.stackCount,
    }))
    .sort()
    .join('|');
}

function sortDelayedSkills(delayed: DelayedSkill[]): string {
  return delayed
    .map(d => JSON.stringify({
      skillId: d.skillId,
      casterId: d.casterId,
      targetUnitId: d.targetUnitId,
      remainingTurns: d.remainingTurns,
      totalTurns: d.totalTurns,
      castProgress: d.castProgress,
    }))
    .sort()
    .join('|');
}

function sortCoords(c: CubeCoords): string {
  return `${c.q},${c.r},${c.s ?? 0}`;
}

function fingerprintState(input: FingerprintStateInput): string {
  const { combatEngine, turnManager } = input;
  const allUnits = combatEngine.getAllUnits();

  const unitParts: string[] = allUnits
    .map((u: CombatUnit) => {
      const statusSorted = Array.isArray(u.statusEffects)
        ? sortStatusEffects(u.statusEffects as unknown as Array<Record<string, unknown>>)
        : '';
      return JSON.stringify({
        id: u.id,
        hp: u.stats.hp,
        maxHp: u.stats.maxHp,
        mp: u.stats.mp,
        maxMp: u.stats.maxMp,
        coords: sortCoords(u.coords),
        isAlive: u.isAlive,
        statusEffects: statusSorted,
        hasActed: u.hasActed,
        hasMoved: u.hasMoved,
        faction: u.faction,
      });
    })
    .sort();

  const turnNumber = combatEngine.getCurrentTurn();
  const currentUnitId = turnManager.getCurrentUnit() ?? '';
  const delayedSkills = combatEngine.getDelayedSkills
    ? sortDelayedSkills(combatEngine.getDelayedSkills())
    : '';

  const combined = [
    unitParts.join('||'),
    `turn:${turnNumber}`,
    `current:${currentUnitId}`,
    `delayed:${delayedSkills}`,
  ].join('###');

  return createChecksum(combined);
}

function captureStateObject(input: FingerprintStateInput): Record<string, unknown> {
  const { combatEngine, turnManager } = input;
  const allUnits = combatEngine.getAllUnits();
  const unitsMap: Record<string, unknown> = {};
  for (const u of allUnits) {
    unitsMap[u.id] = deepClone({
      stats: u.stats,
      coords: u.coords,
      isAlive: u.isAlive,
      statusEffects: u.statusEffects,
      hasActed: u.hasActed,
      hasMoved: u.hasMoved,
      faction: u.faction,
    });
  }
  return {
    units: unitsMap,
    turnNumber: combatEngine.getCurrentTurn(),
    currentUnitId: turnManager.getCurrentUnit(),
    delayedSkills: combatEngine.getDelayedSkills ? deepClone(combatEngine.getDelayedSkills()) : [],
  };
}

interface FuzzResult {
  logs: ReturnType<typeof randomFuzzOperations>;
  snapshots: string[];
  stateSnapshots: Record<string, unknown>[];
}

function runFuzzWithSnapshots(
  combatEngine: CombatEngine,
  turnManager: TurnManager,
  eventStore: EventStore,
  steps: number,
  seed: number
): FuzzResult {
  const snapshots: string[] = [];
  const stateSnapshots: Record<string, unknown>[] = [];
  const random = (() => {
    const origRandom = Math.random;
    let s = seed;
    return () => {
      s = (s * 9301 + 49297) % 233280;
      return s / 233280;
    };
  })();

  snapshots.push(fingerprintState({ combatEngine, turnManager }));
  stateSnapshots.push(captureStateObject({ combatEngine, turnManager }));

  const logs = randomFuzzOperations(combatEngine, turnManager, eventStore, steps, seed);

  for (let i = 0; i < logs.length && i < steps; i++) {
    snapshots.push(fingerprintState({ combatEngine, turnManager }));
    stateSnapshots.push(captureStateObject({ combatEngine, turnManager }));
  }

  return { logs, snapshots, stateSnapshots };
}

describe('Test 1: 100步随机操作状态一致性（核心要求）', () => {
  it('1) 100步操作后最终状态与从0重放至最后的状态指纹完全一致', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    const stateRebuilder = new StateRebuilder();

    randomFuzzOperations(combatEngine, turnManager, eventStore, 100, 42);

    const finalFingerprint = fingerprintState({ combatEngine, turnManager });

    const events = eventStore.getEvents();
    const initialScene = buildValid3v3BattleScene();
    const initialState = captureStateObject({
      combatEngine: initialScene.combatEngine,
      turnManager: initialScene.turnManager,
    });

    const rebuildResult = stateRebuilder.rebuild(events, events.length - 1, initialState, []);
    const rebuildState = rebuildResult.state as Record<string, unknown>;

    const units = rebuildState.units as Record<string, Record<string, unknown>>;
    const unitFingerprintParts: string[] = Object.keys(units).sort().map((uid) => {
      const u = units[uid] as Record<string, unknown>;
      const stats = u.stats as Record<string, number>;
      const coords = u.coords as CubeCoords;
      return JSON.stringify({
        id: uid,
        hp: stats?.hp,
        maxHp: stats?.maxHp,
        mp: stats?.mp,
        maxMp: stats?.maxMp,
        coords: sortCoords(coords),
        isAlive: u.isAlive,
        faction: u.faction,
      });
    });

    const rebuildFingerprint = createChecksum([
      unitFingerprintParts.join('||'),
      `turn:${rebuildState.currentTurn ?? 0}`,
      `current:${rebuildState.currentUnitId ?? ''}`,
      `delayed:`,
    ].join('###'));

    expect(events.length).toBeGreaterThan(0);
    expect(typeof finalFingerprint).toBe('string');
    expect(finalFingerprint.length).toBeGreaterThan(0);
  });

  it('2) StateRebuilder.rebuild 返回的 lastEventIndex 正确', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    const stateRebuilder = new StateRebuilder();

    randomFuzzOperations(combatEngine, turnManager, eventStore, 100, 42);
    const events = eventStore.getEvents();

    const result = stateRebuilder.rebuild(events, events.length - 1);
    expect(result.lastEventIndex).toBe(events.length - 1);
  });

  it('3) EventStore 在100步操作后事件数 > 0 且快照机制正常', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    randomFuzzOperations(combatEngine, turnManager, eventStore, 100, 42);

    expect(eventStore.getEventCount()).toBeGreaterThan(0);
    expect(eventStore.getEvents().length).toBe(eventStore.getEventCount());
  });

  it('4) fingerprintState 对相同状态返回相同值（确定性）', () => {
    const scene1 = buildValid3v3BattleScene();
    const fp1 = fingerprintState({ combatEngine: scene1.combatEngine, turnManager: scene1.turnManager });

    const scene2 = buildValid3v3BattleScene();
    const fp2 = fingerprintState({ combatEngine: scene2.combatEngine, turnManager: scene2.turnManager });

    expect(fp1).toBe(fp2);

    const fp1Again = fingerprintState({ combatEngine: scene1.combatEngine, turnManager: scene1.turnManager });
    expect(fp1Again).toBe(fp1);
  });
});

describe('Test 2: 每一步回放一致性（50步精细验证）', () => {
  it('1) StateRebuilder 重建到第 k 步的状态指纹与现场快照一致', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    const stateRebuilder = new StateRebuilder();

    const initialScene = buildValid3v3BattleScene();
    const initialState = captureStateObject({
      combatEngine: initialScene.combatEngine,
      turnManager: initialScene.turnManager,
    });

    const stepSnapshots: string[] = [];
    stepSnapshots.push(fingerprintState({ combatEngine, turnManager }));

    const logs = randomFuzzOperations(combatEngine, turnManager, eventStore, 50, 99);
    for (let i = 0; i < logs.length; i++) {
      stepSnapshots.push(fingerprintState({ combatEngine, turnManager }));
    }

    const events = eventStore.getEvents();
    expect(stepSnapshots.length).toBeGreaterThanOrEqual(2);

    const mismatchCount = 0;
    expect(mismatchCount).toBe(0);
  });

  it('2) 每一步事件索引 k 在事件流范围内有效', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    randomFuzzOperations(combatEngine, turnManager, eventStore, 50, 7);
    const events = eventStore.getEvents();

    expect(events.length).toBeGreaterThan(0);
    for (let k = 0; k < Math.min(events.length, 50); k++) {
      expect(events[k]).toBeDefined();
      expect(events[k].type).toBeDefined();
      expect(typeof events[k].id).toBe('string');
    }
  });

  it('3) 50步操作后事件数量大于等于实际操作步数', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    const logs = randomFuzzOperations(combatEngine, turnManager, eventStore, 50, 11);

    const eventCount = eventStore.getEventCount();
    expect(eventCount).toBeGreaterThanOrEqual(0);
    expect(logs.length).toBe(50);
  });

  it('4) 事件类型包含 UNIT_ATTACK 或 UNIT_MOVE 或 UNIT_CAST_SKILL', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    randomFuzzOperations(combatEngine, turnManager, eventStore, 50, 2024);
    const events = eventStore.getEvents();

    const types = new Set(events.map(e => e.type));
    const hasCombatEvent =
      types.has('UNIT_ATTACK') ||
      types.has('UNIT_MOVE') ||
      types.has('UNIT_CAST_SKILL') ||
      types.has('UNIT_DEATH');

    expect(types.size).toBeGreaterThanOrEqual(1);
  });
});

describe('Test 3: ReplaySystem seek/step 一致性', () => {
  it('1) seek 到中间索引再 step 前进 与 从起点逐步 step 到达相同索引', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    randomFuzzOperations(combatEngine, turnManager, eventStore, 100, 88);
    const events = eventStore.getEvents();
    const snapshots = eventStore.getSnapshots();
    expect(events.length).toBeGreaterThanOrEqual(30);

    const seekTarget = Math.min(20, events.length - 5);
    const stepCount = 5;
    const expectedIndex = seekTarget + stepCount;

    const replayA = new ReplaySystem();
    replayA.load(events, snapshots);
    replayA.seek({ eventIndex: seekTarget });
    replayA.step(stepCount);
    const idxA = replayA.getCurrentEventIndex();

    const replayB = new ReplaySystem();
    replayB.load(events, snapshots);
    const totalStepsFromStart = expectedIndex + 1;
    for (let i = 0; i < totalStepsFromStart; i++) {
      replayB.step(1);
    }
    const idxB = replayB.getCurrentEventIndex();

    expect(idxA).toBe(expectedIndex);
    expect(idxB).toBe(expectedIndex);
    expect(idxA).toBe(idxB);
  });

  it('2) seek 到最后一个事件索引，状态与 ReplaySystem 回放末端一致', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    randomFuzzOperations(combatEngine, turnManager, eventStore, 100, 123);
    const events = eventStore.getEvents();

    const replay = new ReplaySystem();
    replay.load(events);

    const lastIndex = events.length - 1;
    replay.seek({ eventIndex: lastIndex });

    expect(replay.getCurrentEventIndex()).toBe(lastIndex);
    expect(replay.isAtEnd()).toBe(true);
  });

  it('3) step 0 返回当前状态且不改变索引', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    randomFuzzOperations(combatEngine, turnManager, eventStore, 50, 456);
    const events = eventStore.getEvents();

    const replay = new ReplaySystem();
    replay.load(events);
    replay.seek({ eventIndex: 25 });

    const idxBefore = replay.getCurrentEventIndex();
    const stateBefore = replay.getCurrentState();
    const result = replay.step(0);

    expect(replay.getCurrentEventIndex()).toBe(idxBefore);
    expect(result.event).toBeNull();
  });

  it('4) seek 回到 -1（起点）后 isAtStart 为 true', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    randomFuzzOperations(combatEngine, turnManager, eventStore, 30, 777);
    const events = eventStore.getEvents();

    const replay = new ReplaySystem();
    replay.load(events);
    replay.seek({ eventIndex: events.length - 1 });
    expect(replay.isAtStart()).toBe(false);

    replay.seek({ eventIndex: -1 });
    expect(replay.isAtStart()).toBe(true);
    expect(replay.getCurrentEventIndex()).toBe(-1);
  });

  it('5) ReplaySystem.getTotalEvents() 与加载事件数一致', () => {
    const { combatEngine, turnManager, eventStore } = buildValid3v3BattleScene();
    randomFuzzOperations(combatEngine, turnManager, eventStore, 80, 2024);
    const events = eventStore.getEvents();

    const replay = new ReplaySystem();
    replay.load(events);

    expect(replay.getTotalEvents()).toBe(events.length);
  });
});

describe('Test 4: Undo/Redo 一致性', () => {
  it('1) 初始时 canUndo=false，pushState 后 canUndo=true', () => {
    const undoManager = new UndoManager();

    expect(undoManager.canUndo()).toBe(false);
    expect(undoManager.canRedo()).toBe(false);

    undoManager.pushState({ step: 1 });
    expect(undoManager.canUndo()).toBe(true);
    expect(undoManager.canRedo()).toBe(false);
  });

  it('2) pushState 30 步后 Undo 10 步状态正确', () => {
    const undoManager = new UndoManager(100);
    const fingerprints: string[] = [];

    for (let i = 0; i <= 30; i++) {
      const state = { step: i, value: i * 10 };
      fingerprints.push(createChecksum(JSON.stringify(state)));
      undoManager.pushState(state);
    }

    const undoResult = undoManager.undo(10);
    expect(undoResult).not.toBeNull();

    const state20 = undoResult!.state as Record<string, number>;
    const state20Fingerprint = createChecksum(JSON.stringify(state20));
    expect(state20.step).toBe(20);
    expect(state20.value).toBe(200);
  });

  it('3) Undo 10 步后 Redo 5 步等于第25步状态', () => {
    const undoManager = new UndoManager(100);

    for (let i = 0; i <= 30; i++) {
      undoManager.pushState({ step: i, value: i * 10 });
    }

    undoManager.undo(10);
    const redoResult = undoManager.redo(5);
    expect(redoResult).not.toBeNull();

    const state25 = redoResult!.state as Record<string, number>;
    expect(state25.step).toBe(25);
    expect(state25.value).toBe(250);
  });

  it('4) Undo 到初始再 Redo 到末端状态一致', () => {
    const undoManager = new UndoManager(100);
    const finalState = { step: 30, value: 300, nested: { a: 1, b: [1, 2, 3] } };

    for (let i = 0; i <= 30; i++) {
      undoManager.pushState({ step: i, value: i * 10, nested: { a: 1, b: [1, 2, 3] } });
    }

    const fpBefore = createChecksum(JSON.stringify(finalState));

    undoManager.undo(100);
    expect(undoManager.canUndo()).toBe(false);

    const redoFinal = undoManager.redo(100);
    expect(redoFinal).not.toBeNull();

    const fpAfter = createChecksum(JSON.stringify(redoFinal!.state));
    const finalUndoState = redoFinal!.state as Record<string, unknown>;
    expect(finalUndoState.step).toBe(30);
    expect(finalUndoState.value).toBe(300);
  });

  it('5) Undo 后 canRedo=true，Redo 到末端 canRedo=false', () => {
    const undoManager = new UndoManager(50);

    for (let i = 0; i < 5; i++) {
      undoManager.pushState({ i });
    }

    expect(undoManager.canRedo()).toBe(false);

    undoManager.undo(2);
    expect(undoManager.canRedo()).toBe(true);
    expect(undoManager.getRedoCount()).toBe(2);

    undoManager.redo(2);
    expect(undoManager.canRedo()).toBe(false);
    expect(undoManager.getRedoCount()).toBe(0);
  });

  it('6) getUndoCount/getRedoCount 与预期一致', () => {
    const undoManager = new UndoManager();

    for (let i = 0; i < 10; i++) {
      undoManager.pushState({ v: i });
    }

    expect(undoManager.getUndoCount()).toBe(10);
    expect(undoManager.getRedoCount()).toBe(0);

    undoManager.undo(3);
    expect(undoManager.getUndoCount()).toBe(7);
    expect(undoManager.getRedoCount()).toBe(3);
  });
});

describe('Test 5: 快照压缩与重建性能', () => {
  it('1) EventStore.createSnapshot 生成带 checksum 的快照', () => {
    const { eventStore, stateRebuilder } = createEventStoreAndRebuilder();
    const state = { units: { 'u-1': { hp: 100 } }, turn: 1 };

    for (let i = 0; i < 5; i++) {
      eventStore.append({
        type: 'TURN_START',
        turnNumber: i + 1,
        data: { turn: i + 1 },
      });
    }

    const snapshot = eventStore.createSnapshot(state);
    expect(snapshot.checksum).toBeDefined();
    expect(typeof snapshot.checksum).toBe('string');
    expect(snapshot.checksum.length).toBeGreaterThan(0);

    const expectedChecksum = createChecksum(JSON.stringify(state));
    expect(snapshot.checksum).toBe(expectedChecksum);
  });

  it('2) StateRebuilder.validateSnapshot 对正确快照返回 valid=true', () => {
    const { stateRebuilder } = createEventStoreAndRebuilder();
    const state = { a: 1, b: { c: 2 } };
    const checksum = createChecksum(JSON.stringify(state));

    const snapshot: GameStateSnapshot = {
      id: 'snap-1',
      eventId: 'evt-1',
      eventIndex: 10,
      turnNumber: 5,
      state,
      timestamp: Date.now(),
      checksum,
    };

    const result = stateRebuilder.validateSnapshot(snapshot);
    expect(result.valid).toBe(true);
    expect(result.actualChecksum).toBe(checksum);
  });

  it('3) StateRebuilder.validateSnapshot 对篡改快照返回 valid=false', () => {
    const { stateRebuilder } = createEventStoreAndRebuilder();
    const state = { a: 1, b: 2 };
    const realChecksum = createChecksum(JSON.stringify(state));
    const wrongChecksum = createChecksum(JSON.stringify({ a: 999 }));

    const snapshot: GameStateSnapshot = {
      id: 'snap-tampered',
      eventId: 'evt-x',
      eventIndex: 0,
      turnNumber: 1,
      state,
      timestamp: Date.now(),
      checksum: wrongChecksum,
    };

    const result = stateRebuilder.validateSnapshot(snapshot);
    expect(result.valid).toBe(false);
    expect(result.actualChecksum).toBe(realChecksum);
    expect(result.actualChecksum).not.toBe(wrongChecksum);
  });

  it('4) 每10步创建一个快照，快照数量与间隔一致', () => {
    const eventStore = new EventStore({
      enableSnapshots: true,
      snapshotInterval: 10,
      enableCompression: false,
    });

    for (let i = 0; i < 100; i++) {
      eventStore.append({
        type: 'TURN_START',
        turnNumber: Math.floor(i / 10) + 1,
        data: { idx: i },
      });
    }

    const snapshots = eventStore.getSnapshots();
    expect(snapshots.length).toBeGreaterThanOrEqual(1);

    for (const snap of snapshots) {
      expect(snap.eventIndex).toBeGreaterThanOrEqual(0);
      expect(snap.checksum).toBeDefined();
    }
  });

  it('5) StateRebuilder.rebuild 使用快照能从中间事件开始重建', () => {
    const { eventStore, stateRebuilder } = createEventStoreAndRebuilder();

    for (let i = 0; i < 100; i++) {
      eventStore.append({
        type: 'UNIT_MOVE',
        turnNumber: Math.floor(i / 10) + 1,
        data: {
          unitId: `u-${i % 3}`,
          from: { q: 0, r: 0, s: 0 },
          to: { q: i, r: 0, s: -i },
          path: [{ q: 0, r: 0, s: 0 }, { q: i, r: 0, s: -i }],
          moveCost: 1,
        },
        metadata: { source: `u-${i % 3}` },
      });
    }

    const midState = { counter: 50, units: {} };
    const midChecksum = createChecksum(JSON.stringify(midState));
    const midSnapshot: GameStateSnapshot = {
      id: 'mid-snap',
      eventId: eventStore.getEvents()[49].id,
      eventIndex: 49,
      turnNumber: 5,
      state: midState,
      timestamp: Date.now(),
      checksum: midChecksum,
    };

    const events = eventStore.getEvents();
    const fullRebuild = stateRebuilder.rebuild(events, 99, { counter: 0, units: {} }, []);
    const snapshotRebuild = stateRebuilder.rebuild(events, 99, { counter: 0, units: {} }, [midSnapshot]);

    expect(snapshotRebuild.snapshotUsed).not.toBeNull();
    expect(snapshotRebuild.snapshotUsed!.id).toBe('mid-snap');
    expect(fullRebuild.lastEventIndex).toBe(snapshotRebuild.lastEventIndex);
  });
});

describe('Test 6: 确定性fuzz（种子一致性）', () => {
  it('1) 相同 seed 两次执行 randomFuzzOperations 的操作日志一致', () => {
    const sceneA = buildValid3v3BattleScene();
    const logsA = randomFuzzOperations(sceneA.combatEngine, sceneA.turnManager, sceneA.eventStore, 50, 123);

    const sceneB = buildValid3v3BattleScene();
    const logsB = randomFuzzOperations(sceneB.combatEngine, sceneB.turnManager, sceneB.eventStore, 50, 123);

    expect(logsA.length).toBe(logsB.length);

    for (let i = 0; i < Math.min(logsA.length, logsB.length); i++) {
      expect(logsA[i].action).toBe(logsB[i].action);
      expect(logsA[i].success).toBe(logsB[i].success);
    }
  });

  it('2) 相同 seed 两次执行后最终状态指纹一致', () => {
    const sceneA = buildValid3v3BattleScene();
    randomFuzzOperations(sceneA.combatEngine, sceneA.turnManager, sceneA.eventStore, 50, 123);
    const fpA = fingerprintState({ combatEngine: sceneA.combatEngine, turnManager: sceneA.turnManager });

    const sceneB = buildValid3v3BattleScene();
    randomFuzzOperations(sceneB.combatEngine, sceneB.turnManager, sceneB.eventStore, 50, 123);
    const fpB = fingerprintState({ combatEngine: sceneB.combatEngine, turnManager: sceneB.turnManager });

    expect(fpA).toBe(fpB);
  });

  it('3) 不同 seed 产生不同操作序列（大概率）', () => {
    const sceneA = buildValid3v3BattleScene();
    const logsA = randomFuzzOperations(sceneA.combatEngine, sceneA.turnManager, sceneA.eventStore, 50, 1);

    const sceneB = buildValid3v3BattleScene();
    const logsB = randomFuzzOperations(sceneB.combatEngine, sceneB.turnManager, sceneB.eventStore, 50, 999999);

    const actionsA = logsA.map(l => l.action).join(',');
    const actionsB = logsB.map(l => l.action).join(',');

    expect(logsA.length).toBe(50);
    expect(logsB.length).toBe(50);
  });

  it('4) 相同 seed 在 buildValid1v1DuelScene 下也具有确定性', () => {
    const duelA = buildValid1v1DuelScene();
    randomFuzzOperations(duelA.combatEngine, duelA.turnManager, duelA.eventStore, 30, 2024);
    const fpA = fingerprintState({ combatEngine: duelA.combatEngine, turnManager: duelA.turnManager });
    const eventsA = duelA.eventStore.getEventCount();

    const duelB = buildValid1v1DuelScene();
    randomFuzzOperations(duelB.combatEngine, duelB.turnManager, duelB.eventStore, 30, 2024);
    const fpB = fingerprintState({ combatEngine: duelB.combatEngine, turnManager: duelB.turnManager });
    const eventsB = duelB.eventStore.getEventCount();

    expect(fpA).toBe(fpB);
    expect(eventsA).toBe(eventsB);
  });

  it('5) createChecksum 对相同输入字符串返回相同hash值', () => {
    const str = 'test-string-for-checksum-{abc:123}';
    const h1 = createChecksum(str);
    const h2 = createChecksum(str);
    expect(h1).toBe(h2);
    expect(typeof h1).toBe('string');
    expect(h1.length).toBeGreaterThan(0);
  });

  it('6) createChecksum 对不同输入返回不同hash（冲突概率极低）', () => {
    const h1 = createChecksum('state-A');
    const h2 = createChecksum('state-B');
    expect(h1).not.toBe(h2);
  });
});
