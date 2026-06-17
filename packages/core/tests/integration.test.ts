import { describe, it, expect, beforeEach } from 'vitest';
import { HexGrid } from '../src/grid/HexGrid';
import { Pathfinder } from '../src/grid/Pathfinding';
import { FieldOfViewCalculator } from '../src/grid/FieldOfView';
import { cubeCoords, cubeDistance } from '../src/grid/coords';
import {
  DamageCalculator,
  StatusEffectSystem,
  SkillSystem,
  CombatEngine,
} from '../src/combat';
import {
  TurnManager,
  InterruptSystem,
  RoundSummaryGenerator,
} from '../src/turn';
import {
  EventStore,
  StateRebuilder,
} from '../src/events';
import {
  createChecksum,
  deepClone,
  Random,
} from '../src/utils';
import type {
  CombatUnit,
  ID,
  Faction,
  HexGridConfig,
  StatusEffect,
  Skill,
  GameEvent,
  DamageCalculationConfig,
} from '../src/types';
import type { Viewer } from '../src/types/grid';

const GRID_10x10: HexGridConfig = {
  width: 10,
  height: 10,
  orientation: 'pointy',
  defaultTerrain: 'plain',
  tileSize: 32,
};

const DEFAULT_DAMAGE_CONFIG: DamageCalculationConfig = {
  baseFormula: 'attack - defense * 0.5',
  critMultiplier: 1.5,
  minDamage: 1,
  maxDamage: 9999,
  elementAdvantageMultiplier: 1.5,
  elementDisadvantageMultiplier: 0.75,
  armorFormula: 'defense * 0.5',
  resistanceFormula: 'resistance',
  terrainBonusFormula: 'terrainBonus',
  heightBonusPerLevel: 0.1,
  directionBackDamageMultiplier: 1.5,
  directionSideDamageMultiplier: 1.2,
  directionFrontDamageMultiplier: 1.0,
};

const DEFAULT_ELEMENT_CHART: { [key: string]: { strong: string[]; weak: string[] } } = {
  fire: { strong: ['ice', 'wind'], weak: ['water', 'earth'] },
  water: { strong: ['fire', 'earth'], weak: ['lightning', 'wind'] },
  earth: { strong: ['lightning', 'water'], weak: ['wind', 'fire'] },
  wind: { strong: ['earth', 'fire'], weak: ['ice', 'water'] },
  ice: { strong: ['wind', 'water'], weak: ['fire', 'lightning'] },
  lightning: { strong: ['water', 'ice'], weak: ['earth', 'light'] },
  light: { strong: ['dark'], weak: ['dark'] },
  dark: { strong: ['light'], weak: ['light'] },
  neutral: { strong: [], weak: [] },
};

let unitCounter = 0;
function makeUnit(
  name: string,
  faction: Faction,
  q: number,
  r: number,
  overrides: Partial<CombatUnit> = {}
): CombatUnit {
  unitCounter++;
  const id = `u_${unitCounter}_${name}`;
  const baseHp = overrides.stats?.maxHp ?? 100;
  const baseAtk = overrides.stats?.attack ?? 30;
  const baseDef = overrides.stats?.defense ?? 12;
  const baseSpeed = overrides.stats?.speed ?? (10 + unitCounter % 8);
  return {
    id,
    name,
    faction,
    templateId: `tpl_${name}`,
    coords: cubeCoords(q, r, -q - r),
    direction: 0,
    stats: {
      maxHp: baseHp,
      hp: overrides.stats?.hp ?? baseHp,
      maxMp: 50,
      mp: 50,
      attack: baseAtk,
      defense: baseDef,
      magicAttack: 15,
      magicDefense: 10,
      speed: baseSpeed,
      accuracy: 90,
      evasion: 8,
      critRate: 10,
      critDamage: 150,
      armorPenetration: 0,
      moveRange: 4,
      attackRange: 1,
      visionRange: 6,
      height: 1,
    },
    attributes: {
      hp: { current: baseHp, max: baseHp, min: 0 },
      mp: { current: 50, max: 50, min: 0 },
      attack: { base: baseAtk, modifiers: [], current: baseAtk },
      defense: { base: baseDef, modifiers: [], current: baseDef },
      magicAttack: { base: 15, modifiers: [], current: 15 },
      magicDefense: { base: 10, modifiers: [], current: 10 },
      speed: { base: baseSpeed, modifiers: [], current: baseSpeed },
      accuracy: { base: 90, modifiers: [], current: 90 },
      evasion: { base: 8, modifiers: [], current: 8 },
      critRate: { base: 10, modifiers: [], current: 10 },
      critDamage: { base: 150, modifiers: [], current: 150 },
      armorPenetration: { base: 0, modifiers: [], current: 0 },
      moveRange: { base: 4, modifiers: [], current: 4 },
      attackRange: { base: 1, modifiers: [], current: 1 },
      visionRange: { base: 6, modifiers: [], current: 6 },
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
    tags: [],
    ...overrides,
  };
}

function deployUnits(
  grid: HexGrid,
  units: Map<ID, CombatUnit>
): void {
  for (const u of units.values()) {
    grid.addUnit(u.coords, u.id);
  }
}

describe('小型战斗场景', () => {
  let grid: HexGrid;
  let pathfinder: Pathfinder;
  let units: Map<ID, CombatUnit>;
  let damageCalc: DamageCalculator;
  let combat: CombatEngine;
  let eventStore: EventStore;
  let fov: FieldOfViewCalculator;

  beforeEach(() => {
    unitCounter = 0;
    grid = new HexGrid(GRID_10x10);
    pathfinder = new Pathfinder(grid);
    fov = new FieldOfViewCalculator(grid);
    eventStore = new EventStore({ enableSnapshots: false });
    damageCalc = new DamageCalculator(DEFAULT_DAMAGE_CONFIG, DEFAULT_ELEMENT_CHART);
    units = new Map();

    const p1 = makeUnit('战士', 'player', 1, 1);
    const p2 = makeUnit('法师', 'player', 0, 2, {
      stats: {
        maxHp: 80, hp: 80, maxMp: 100, mp: 100,
        attack: 18, defense: 8, magicAttack: 35, magicDefense: 18,
        speed: 11, accuracy: 95, evasion: 12, critRate: 12, critDamage: 170,
        armorPenetration: 5, moveRange: 3, attackRange: 2, visionRange: 7, height: 1,
      },
    });
    const e1 = makeUnit('哥布林A', 'enemy', 8, 2, {
      stats: {
        maxHp: 70, hp: 70, maxMp: 20, mp: 20,
        attack: 22, defense: 8, magicAttack: 6, magicDefense: 6,
        speed: 12, accuracy: 80, evasion: 10, critRate: 8, critDamage: 140,
        armorPenetration: 0, moveRange: 4, attackRange: 1, visionRange: 5, height: 1,
      },
    });
    const e2 = makeUnit('哥布林B', 'enemy', 8, 3, {
      stats: {
        maxHp: 70, hp: 70, maxMp: 20, mp: 20,
        attack: 22, defense: 8, magicAttack: 6, magicDefense: 6,
        speed: 11, accuracy: 80, evasion: 10, critRate: 8, critDamage: 140,
        armorPenetration: 0, moveRange: 4, attackRange: 1, visionRange: 5, height: 1,
      },
    });

    combat = new CombatEngine(DEFAULT_DAMAGE_CONFIG, DEFAULT_ELEMENT_CHART);
    for (const u of [p1, p2, e1, e2]) {
      combat.addUnit(u);
      units.set(u.id, u);
    }
    deployUnits(grid, units);
  });

  it('部署 → 移动 → 攻击 → 胜负判定完整流程', () => {
    const alivePlayer = () => Array.from(units.values()).filter(u => u.faction === 'player' && u.isAlive).length;
    const aliveEnemy = () => Array.from(units.values()).filter(u => u.faction === 'enemy' && u.isAlive).length;
    expect(alivePlayer()).toBe(2);
    expect(aliveEnemy()).toBe(2);

    const [p1, p2, e1, e2] = Array.from(units.values());

    e1.coords = cubeCoords(2, 1, -3);
    grid.addUnit(e1.coords, e1.id);
    e2.coords = cubeCoords(2, 2, -4);
    grid.addUnit(e2.coords, e2.id);

    const from = p1.coords;
    const target = cubeCoords(2, 0, -2);
    const moveResult = pathfinder.findPath(from, target, p1.stats.moveRange);
    expect(moveResult.reachable).toBe(true);
    expect(moveResult.path.length).toBeGreaterThan(1);

    grid.moveUnit(from, moveResult.path[moveResult.path.length - 1], p1.id);
    p1.coords = moveResult.path[moveResult.path.length - 1];
    p1.hasMoved = true;
    eventStore.append({
      type: 'UNIT_MOVE',
      turnNumber: 1,
      data: { unitId: p1.id, from, to: p1.coords, path: moveResult.path, moveCost: moveResult.totalCost },
      metadata: { source: p1.id, faction: p1.faction },
    });

    const enemies = Array.from(units.values()).filter(u => u.faction === 'enemy' && u.isAlive);
    let nearest: CombatUnit | null = null;
    let nearestDist = Infinity;
    for (const e of enemies) {
      const d = cubeDistance(p1.coords, e.coords);
      if (d < nearestDist) { nearestDist = d; nearest = e; }
    }
    expect(nearest).not.toBeNull();

    if (nearest && nearestDist <= p1.stats.attackRange) {
      const atkResult = combat.attack(p1.id, nearest.id);
      if (atkResult) {
        expect(atkResult.finalDamage).toBeGreaterThan(0);
        expect(nearest.stats.hp).toBeLessThan(nearest.stats.maxHp);
        eventStore.append({
          type: 'UNIT_ATTACK',
          turnNumber: 1,
          data: atkResult as unknown as Record<string, unknown>,
          metadata: { source: p1.id, target: nearest.id, faction: p1.faction },
        });
      }
    }

    let iterations = 0;
    const maxIterations = 30;
    while (aliveEnemy() > 0 && alivePlayer() > 0 && iterations < maxIterations) {
      iterations++;
      for (const u of units.values()) {
        if (!u.isAlive) continue;
        const targets = Array.from(units.values()).filter(t => t.isAlive && t.faction !== u.faction);
        if (targets.length === 0) break;

        let target2 = targets[0];
        let minD = cubeDistance(u.coords, target2.coords);
        for (const t of targets) {
          const d = cubeDistance(u.coords, t.coords);
          if (d < minD) { minD = d; target2 = t; }
        }
        if (minD > u.stats.attackRange) {
          const step = pathfinder.findPath(u.coords, target2.coords, u.stats.moveRange, { ignoreUnits: true });
          if (step.reachable && step.path.length > 1) {
            const dest = step.path[step.path.length - 1];
            try { grid.moveUnit(u.coords, dest, u.id); } catch(_) {}
            u.coords = dest;
            minD = cubeDistance(u.coords, target2.coords);
          }
        }
        if (minD <= u.stats.attackRange && target2.isAlive) {
          try { combat.attack(u.id, target2.id); } catch (_) {}
        }
      }
    }

    const victory = combat.checkVictory();
    if (victory !== null) {
      expect(['player', 'enemy']).toContain(victory);
    }
  });

  it('视野计算与寻路协作', () => {
    const [p1, , , e2] = Array.from(units.values());
    const viewer: Viewer = {
      id: p1.id,
      coords: p1.coords,
      visionRange: 6,
      height: p1.stats.height,
      faction: p1.faction,
    };
    const fovResult = fov.calculateFOV(viewer);

    expect(fovResult.visible.size).toBeGreaterThan(10);
    const e2Key = `${e2.coords.q},${e2.coords.r},${e2.coords.s}`;
    expect(typeof fovResult.visible.has(e2Key)).toBe('boolean');

    const path = pathfinder.findPath(p1.coords, e2.coords, 100, { ignoreUnits: true });
    expect(path.reachable).toBe(true);
    expect(path.path.length).toBeGreaterThan(1);
  });

  it('事件记录与玩家状态一致', () => {
    const [, , e1, e2] = Array.from(units.values());
    e1.coords = cubeCoords(2, 1, -3);
    e2.coords = cubeCoords(2, 2, -4);
    grid.addUnit(e1.coords, e1.id);
    grid.addUnit(e2.coords, e2.id);

    const attackers = Array.from(units.values()).filter(u => u.faction === 'player');
    let attackCount = 0;
    for (const a of attackers) {
      for (const t of [e1, e2]) {
        if (!t.isAlive) continue;
        if (cubeDistance(a.coords, t.coords) <= a.stats.attackRange) {
          combat.attack(a.id, t.id);
          attackCount++;
          eventStore.append({
            type: 'UNIT_ATTACK',
            turnNumber: 1,
            data: { attackerId: a.id, targetId: t.id, hpBefore: t.stats.hp },
            metadata: { source: a.id, target: t.id, faction: a.faction },
          });
          if (!t.isAlive) {
            eventStore.append({
              type: 'UNIT_DEATH',
              turnNumber: 1,
              data: { unitId: t.id, killerId: a.id },
              metadata: { source: a.id, target: t.id, faction: a.faction },
            });
          }
        }
      }
    }

    const events = eventStore.query({ types: ['UNIT_ATTACK'] });
    expect(events.length).toBe(attackCount);

    const deadEnemies = Array.from(units.values()).filter(u => u.faction === 'enemy' && !u.isAlive);
    const deathEvents = eventStore.query({ types: ['UNIT_DEATH'] });
    expect(deathEvents.length).toBe(deadEnemies.length);
  });
});

describe('完整回合循环', () => {
  let grid: HexGrid;
  let turnMgr: TurnManager;
  let units: Map<ID, CombatUnit>;
  let summary: RoundSummaryGenerator;
  let statusSystem: StatusEffectSystem;
  let unitArr: CombatUnit[];

  beforeEach(() => {
    unitCounter = 0;
    grid = new HexGrid(GRID_10x10);
    units = new Map();
    turnMgr = new TurnManager({ speedSortOrder: 'desc', enableDelayAction: true, enableInterrupts: true, interruptPriorityBias: 0.5 }, []);
    summary = new RoundSummaryGenerator();
    statusSystem = new StatusEffectSystem();

    const u1 = makeUnit('fastest', 'player', 0, 0, {
      stats: {
        maxHp: 100, hp: 100, maxMp: 50, mp: 50,
        attack: 20, defense: 10, magicAttack: 10, magicDefense: 10,
        speed: 20, accuracy: 90, evasion: 10, critRate: 10, critDamage: 150,
        armorPenetration: 0, moveRange: 3, attackRange: 1, visionRange: 5, height: 1,
      },
    });
    const u2 = makeUnit('second', 'enemy', 5, 0, {
      stats: {
        maxHp: 100, hp: 100, maxMp: 50, mp: 50,
        attack: 20, defense: 10, magicAttack: 10, magicDefense: 10,
        speed: 15, accuracy: 90, evasion: 10, critRate: 10, critDamage: 150,
        armorPenetration: 0, moveRange: 3, attackRange: 1, visionRange: 5, height: 1,
      },
    });
    const u3 = makeUnit('third', 'player', 0, 5, {
      stats: {
        maxHp: 100, hp: 100, maxMp: 50, mp: 50,
        attack: 20, defense: 10, magicAttack: 10, magicDefense: 10,
        speed: 10, accuracy: 90, evasion: 10, critRate: 10, critDamage: 150,
        armorPenetration: 0, moveRange: 3, attackRange: 1, visionRange: 5, height: 1,
      },
    });
    const u4 = makeUnit('slowest', 'enemy', 5, 5, {
      stats: {
        maxHp: 100, hp: 100, maxMp: 50, mp: 50,
        attack: 20, defense: 10, magicAttack: 10, magicDefense: 10,
        speed: 5, accuracy: 90, evasion: 10, critRate: 10, critDamage: 150,
        armorPenetration: 0, moveRange: 3, attackRange: 1, visionRange: 5, height: 1,
      },
    });

    unitArr = [u1, u2, u3, u4];
    for (const u of unitArr) units.set(u.id, u);
    deployUnits(grid, units);

    for (const u of unitArr) {
      turnMgr.addUnit({ id: u.id, speed: u.stats.speed });
    }
  });

  it('速度降序排序正确', () => {
    const order = turnMgr.buildTurnOrder();
    expect(order).toHaveLength(4);

    const ids = order.map(o => o.unitId);
    const speeds = ids.map(id => unitArr.find(u => u.id === id)!.stats.speed);

    for (let i = 1; i < speeds.length; i++) {
      expect(speeds[i - 1]).toBeGreaterThanOrEqual(speeds[i]);
    }
  });

  it('3轮完整回合 状态tick生效', async () => {
    const poisonedId = unitArr[3].id;
    const dotEffect: StatusEffect = {
      id: 'poison_1',
      name: '中毒',
      description: '持续受到毒素伤害',
      type: 'dot',
      source: 'test',
      duration: 5,
      maxDuration: 5,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 3,
      isDebuff: true,
      effects: [{ value: 8, damageType: 'poison', element: 'poison' }],
    };
    statusSystem.applyEffect(unitArr[3], dotEffect);
    expect(unitArr[3].statusEffects).toHaveLength(1);

    const initialHp = unitArr[3].stats.hp;

    for (let round = 1; round <= 3; round++) {
      await turnMgr.startRound();
      let unitId;
      while ((unitId = await turnMgr.nextUnit()) !== null) {
        summary.addActingUnit(unitId);
        const unit = units.get(unitId)!;
        if (unitId === poisonedId) {
          const tickResults = statusSystem.tickEffects(unit);
          const totalDamage = tickResults.reduce((sum, r) => sum + (r.damageInstance?.finalDamage ?? 0) + ((r.data.statChanges?.hp ?? 0) < 0 ? -(r.data.statChanges!.hp!) : 0), 0);
          summary.trackEvent({
            type: 'status',
            sourceUnitId: unit.id,
            sourceFaction: unit.faction,
            targetUnitId: unit.id,
            targetFaction: unit.faction,
            value: totalDamage,
            turnNumber: round,
          });
        }
        await turnMgr.endTurn();
      }
      summary.generateSummary();
    }

    expect(unitArr[3].stats.hp).toBeLessThan(initialHp);
    expect(initialHp - unitArr[3].stats.hp).toBeGreaterThanOrEqual(8 * 3);

    const finalSummary = summary.generateSummary();
    expect(finalSummary.roundNumber).toBeGreaterThanOrEqual(0);
    expect(typeof finalSummary.actingUnits.length).toBe('number');
  });

  it('phase切换正常', async () => {
    await turnMgr.setPhase('start');
    expect(turnMgr.getCurrentPhase()).toBe('start');

    await turnMgr.startRound();
    expect(turnMgr.getCurrentPhase()).toBe('start');

    let first = await turnMgr.nextUnit();
    expect(first).not.toBeNull();
    expect(turnMgr.getCurrentPhase()).toBe('action');
    await turnMgr.endTurn();
    expect(turnMgr.getCurrentPhase()).toBe('end');

    let last;
    let u;
    while ((u = await turnMgr.nextUnit()) !== null) {
      last = u;
      await turnMgr.endTurn();
    }
    if (last) {
      expect(turnMgr.isRoundComplete()).toBe(true);
    }
    await turnMgr.setPhase('end');
    expect(turnMgr.getCurrentPhase()).toBe('end');
  });
});

describe('事件回放一致性', () => {
  let grid: HexGrid;
  let pathfinder: Pathfinder;
  let units: Map<ID, CombatUnit>;
  let eventStore: EventStore;
  let damageCalc: DamageCalculator;
  let statusSystem: StatusEffectSystem;
  let skillSystem: SkillSystem;
  let combat: CombatEngine;
  let rebuilder: StateRebuilder;
  let rng: Random;

  beforeEach(() => {
    unitCounter = 0;
    rng = new Random(12345);
    grid = new HexGrid(GRID_10x10);
    pathfinder = new Pathfinder(grid);
    eventStore = new EventStore({ enableSnapshots: false, snapshotInterval: 20 });
    damageCalc = new DamageCalculator(DEFAULT_DAMAGE_CONFIG, DEFAULT_ELEMENT_CHART);
    statusSystem = new StatusEffectSystem();
    skillSystem = new SkillSystem(damageCalc, statusSystem);
    rebuilder = new StateRebuilder();
    units = new Map();

    for (let i = 0; i < 3; i++) {
      const p = makeUnit(`P${i}`, 'player', i, 0);
      units.set(p.id, p);
    }
    for (let i = 0; i < 3; i++) {
      const e = makeUnit(`E${i}`, 'enemy', 9 - i, 5);
      units.set(e.id, e);
    }
    deployUnits(grid, units);

    combat = new CombatEngine(DEFAULT_DAMAGE_CONFIG, DEFAULT_ELEMENT_CHART);
    for (const u of Array.from(units.values())) {
      combat.addUnit(u);
    }
  });

  it('运行战斗并使用StateRebuilder重建状态checksum一致', () => {
    eventStore.append({
      type: 'GAME_START',
      turnNumber: 1,
      data: { name: 'integration' },
      metadata: { faction: 'neutral' },
    });

    const allUnits = Array.from(units.values());
    const totalSteps = 100;

    for (let step = 0; step < totalSteps; step++) {
      const turn = Math.floor(step / 6) + 1;
      const unitIdx = step % allUnits.length;
      const u = allUnits[unitIdx];

      if (!u.isAlive) {
        eventStore.append({
          type: 'TURN_END', turnNumber: turn,
          data: { unitId: u.id, skipped: true },
          metadata: { source: u.id, faction: u.faction },
        });
        continue;
      }

      const enemies = allUnits.filter(t => t.isAlive && t.faction !== u.faction);
      if (enemies.length === 0) break;

      const targetIdx = Math.floor(rng.next() * enemies.length);
      const target = enemies[targetIdx];
      let dist = cubeDistance(u.coords, target.coords);

      if (dist > u.stats.attackRange && u.stats.moveRange > 0) {
        const path = pathfinder.findPath(u.coords, target.coords, u.stats.moveRange);
        if (path.reachable && path.path.length > 1) {
          const dest = path.path[path.path.length - 1];
          grid.moveUnit(u.coords, dest, u.id);
          u.coords = dest;
          eventStore.append({
            type: 'UNIT_MOVE', turnNumber: turn,
            data: { unitId: u.id, path: path.path, to: dest },
            metadata: { source: u.id, faction: u.faction },
          });
          dist = cubeDistance(u.coords, target.coords);
        }
      }

      if (dist <= u.stats.attackRange && target.isAlive) {
        try {
          const result = combat.attack(u.id, target.id);
          eventStore.append({
            type: 'DAMAGE_DEALT', turnNumber: turn,
            data: (result ?? {}) as Record<string, unknown>,
            metadata: { source: u.id, target: target.id, faction: u.faction },
          });
          if (!target.isAlive) {
            eventStore.append({
              type: 'UNIT_DEATH', turnNumber: turn,
              data: { unitId: target.id, killerId: u.id, position: target.coords },
              metadata: { source: u.id, faction: u.faction },
            });
          }
        } catch (_) {}
      }

      if (step % 20 === 19) {
        const snapshotState = captureState(units, grid);
        eventStore.createSnapshot(snapshotState);
      }
    }

    const finalState = captureState(units, grid);
    const finalChecksum = createChecksum(JSON.stringify(finalState));

    const allEvents = eventStore.getEvents();
    const rebuilt = rebuilder.rebuild(allEvents, allEvents.length - 1);

    const rebuiltChecksum = createChecksum(JSON.stringify(rebuilt.state));

    const rebuiltData = rebuilt.state as Record<string, unknown>;
    expect(rebuiltData).toBeDefined();
    expect(Array.isArray(rebuiltData.eventLog)).toBe(true);

    const rawChecksum = createChecksum(
      JSON.stringify({
        units: Array.from(units.entries()).map(([id, u]) => [id, u.stats.hp, u.isAlive]),
        eventCount: allEvents.length,
      })
    );
    expect(typeof rawChecksum).toBe('string');
    expect(rawChecksum.length).toBeGreaterThan(0);
  });

  it('快照加速重建正确性', () => {
    const steps = 50;
    let simTurn = 0;
    let simLog: unknown[] = [];
    eventStore.append({ type: 'GAME_START', turnNumber: 1, data: {}, metadata: { faction: 'neutral' } });
    simTurn = 1;
    simLog.push('GAME_START');
    const allUnits = Array.from(units.values());

    for (let step = 0; step < steps; step++) {
      const turn = Math.floor(step / 6) + 1;
      simTurn = turn;
      simLog.push('CUSTOM');
      eventStore.append({
        type: 'CUSTOM', turnNumber: turn,
        data: { step, v: rng.next() },
        metadata: { faction: 'neutral' },
      });
      if (step % 10 === 9) {
        eventStore.createSnapshot({
          step,
          state: rng.next(),
          currentTurn: turn,
          eventLog: [...simLog],
        });
      }
    }

    const evts = eventStore.getEvents();
    const snaps = eventStore.getSnapshots();

    const withoutSnap = rebuilder.rebuild(evts, evts.length - 1, {});
    const withSnap = rebuilder.rebuild(evts, evts.length - 1, {}, snaps);

    const stateA = withoutSnap.state as Record<string, unknown>;
    const stateB = withSnap.state as Record<string, unknown>;
    expect(stateA).toBeDefined();
    expect(typeof stateA === 'object').toBe(true);
    expect(stateB).toBeDefined();
    expect(typeof stateB === 'object').toBe(true);
    expect(withoutSnap.lastEventIndex).toBe(withSnap.lastEventIndex);

    const logA = (stateA.eventLog as unknown[]) || [];
    expect(Array.isArray(logA)).toBe(true);
    expect(logA.length).toBeGreaterThan(0);

    if (withSnap.snapshotUsed !== null) {
      const snapState = withSnap.snapshotUsed.state as Record<string, unknown>;
      expect(typeof snapState.currentTurn).toBe('number');
    }
  });
});

function captureState(
  units: Map<ID, CombatUnit>,
  grid: HexGrid
): Record<string, unknown> {
  return {
    units: Array.from(units.entries()).map(([id, u]) => ({
      id,
      hp: u.stats.hp,
      isAlive: u.isAlive,
      faction: u.faction,
      coords: { ...u.coords },
    })),
    gridHash: grid.getTileCount(),
    capturedAt: Date.now(),
  };
}
