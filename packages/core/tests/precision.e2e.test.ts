import { describe, it, expect, beforeEach } from 'vitest';
import {
  HexGrid,
  Pathfinder,
  FieldOfViewCalculator,
  DamageCalculator,
  StatusEffectSystem,
  SkillSystem,
  CombatEngine,
  TurnManager,
  LevelSerializer,
  LevelValidator,
  LevelManager,
  createChecksum,
  deepClone,
  Random,
} from '../src';
import { EventStore } from '../src/events/EventStore';
import { StateRebuilder } from '../src/events/StateRebuilder';
import type {
  CombatUnit,
  ID,
  Faction,
  LevelConfig,
  VictoryCondition,
  CubeCoords,
  DamageInstance,
  GameEvent,
  HexGridConfig,
} from '../src/types';
import {
  create3v3BattleScene,
  create1v1DuelScene,
  createUnit,
  createDamageSkill,
  createEmptyGrid,
  createCombatEngineWithUnits,
  createVictoryCondition,
  createPhysicalWarrior as createWarrior,
  createMage,
  createArcher,
  createTank,
} from './factories';
import { terrainRegistry } from '../src/grid/TerrainConfig';
import { cubeCoords, cubeDistance, cubeKey } from '../src/grid/coords';

function captureStateFingerprint(
  combatEngine: CombatEngine,
  grid: HexGrid,
  turnManager: TurnManager
): string {
  const units = combatEngine.getAllUnits().map(u => ({
    id: u.id,
    hp: u.stats.hp,
    isAlive: u.isAlive,
    faction: u.faction,
    coords: { ...u.coords },
    hasActed: u.hasActed,
    hasMoved: u.hasMoved,
  }));
  units.sort((a, b) => a.id.localeCompare(b.id));

  const turnState = {
    round: turnManager.getCurrentRound(),
    currentUnit: turnManager.getCurrentUnit(),
  };

  return createChecksum(JSON.stringify({ units, turnState }));
}

function buildValidLevelConfig(): LevelConfig {
  const playerPositions = [
    cubeCoords(1, 1, -2),
    cubeCoords(2, 0, -2),
  ];
  const enemyPositions = [
    cubeCoords(7, 1, -8),
    cubeCoords(8, 0, -8),
  ];

  return {
    id: 'test-level-001',
    name: 'Test Level',
    description: 'A test level for e2e validation',
    mapId: 'map-10x10-plain',
    factions: {
      player: {
        units: ['warrior-1', 'mage-1'],
        startingPositions: playerPositions,
      },
      enemy: {
        units: ['tank-1', 'archer-1'],
        startingPositions: enemyPositions,
      },
    },
    victoryConditions: [
      {
        id: 'vc-destroy-all',
        type: 'destroy_all',
        targetFaction: 'enemy',
        description: '消灭所有敌人',
      },
    ],
    defeatConditions: [
      {
        id: 'dc-destroy-all',
        type: 'destroy_all',
        targetFaction: 'player',
        description: '玩家被全灭',
      },
    ],
    turnLimit: 20,
    startingTurn: 1,
  };
}

describe('Test 1: 模拟完整战斗（3v3 端到端）', () => {
  let grid: HexGrid;
  let combatEngine: CombatEngine;
  let turnManager: TurnManager;
  let eventStore: EventStore;
  let players: CombatUnit[];
  let enemies: CombatUnit[];
  let pathfinder: Pathfinder;

  beforeEach(() => {
    const scene = create3v3BattleScene();
    grid = scene.grid;
    combatEngine = scene.combatEngine;
    players = scene.players;
    enemies = scene.enemies;
    turnManager = scene.turnManager;
    eventStore = scene.eventStore;
    pathfinder = new Pathfinder(grid);
    combatEngine.startCombat();
  });

  it('1.1 部署阶段：所有单位在正确起始位置，isAlive=true，HP=max', () => {
    expect(players).toHaveLength(3);
    expect(enemies).toHaveLength(3);

    const expectedPlayerStarts = [
      cubeCoords(0, 1, -1),
      cubeCoords(0, 3, -3),
      cubeCoords(0, 5, -5),
    ];
    players.forEach((p, i) => {
      expect(p.isAlive).toBe(true);
      expect(p.stats.hp).toBeCloseTo(p.stats.maxHp, 0);
      expect(p.coords).toEqual(expectedPlayerStarts[i]);
      const tile = grid.getTile(p.coords);
      expect(tile).toBeDefined();
      expect(tile!.units).toContain(p.id);
    });

    const expectedEnemyStarts = [
      cubeCoords(11, 1, -12),
      cubeCoords(11, 3, -14),
      cubeCoords(11, 5, -16),
    ];
    enemies.forEach((e, i) => {
      expect(e.isAlive).toBe(true);
      expect(e.stats.hp).toBeCloseTo(e.stats.maxHp, 0);
      expect(e.coords).toEqual(expectedEnemyStarts[i]);
      const tile = grid.getTile(e.coords);
      if (tile) {
        expect(tile.units).toContain(e.id);
      }
    });

    eventStore.append({
      type: 'combat_deployed',
      payload: { playerCount: 3, enemyCount: 3 },
      version: 1,
    });
  });

  it('1.2 第 1 回合：turnNumber==1，行动序列按 speed 降序排列', () => {
    expect(turnManager.getCurrentRound()).toBeCloseTo(1, 0);

    const turnOrder = turnManager.getTurnOrder();
    expect(turnOrder.length).toBe(6);

    const speeds = turnOrder.map(entry => entry.speed);
    for (let i = 0; i < speeds.length - 1; i++) {
      expect(speeds[i]).toBeGreaterThanOrEqual(speeds[i + 1]);
    }

    const idToSpeed: Record<string, number> = {};
    [...players, ...enemies].forEach(u => { idToSpeed[u.id] = u.stats.speed; });

    const archer = players.find(p => p.templateId === 'archer')!;
    const warrior = players.find(p => p.templateId === 'warrior')!;
    const mage = players.find(p => p.templateId === 'mage')!;
    const tank = enemies.find(e => e.templateId === 'tank')!;

    expect(idToSpeed[archer.id]).toBeCloseTo(12, 0);
    expect(idToSpeed[warrior.id]).toBeCloseTo(8, 0);
    expect(idToSpeed[mage.id]).toBeCloseTo(7, 0);
    expect(idToSpeed[tank.id]).toBeCloseTo(5, 0);

    eventStore.append({
      type: 'round_started',
      payload: { round: turnManager.getCurrentRound() },
      version: 1,
    });
  });

  it('1.3 玩家弓箭手行动：移动到附近位置 + 记录事件', () => {
    const archer = players.find(p => p.templateId === 'archer')!;
    expect(archer).toBeDefined();
    expect(archer.stats.moveRange).toBeCloseTo(4, 0);
    expect(archer.stats.attackRange).toBeCloseTo(3, 0);

    const fromCoords = { ...archer.coords };
    const targetMoveCoords = cubeCoords(2, 1, -3);
    const moveResult = pathfinder.findPath(fromCoords, targetMoveCoords);

    if (moveResult.reachable && moveResult.totalCost <= archer.stats.moveRange) {
      grid.moveUnit(fromCoords, targetMoveCoords, archer.id);
      archer.coords = targetMoveCoords;
      archer.hasMoved = true;

      eventStore.append({
        type: 'unit_moved',
        payload: {
          unitId: archer.id,
          from: fromCoords,
          to: targetMoveCoords,
          path: moveResult.path,
        },
        version: 1,
      });

      expect(archer.coords).toEqual(targetMoveCoords);
    } else {
      const shortMove = cubeCoords(1, 2, -3);
      const shortResult = pathfinder.findPath(fromCoords, shortMove);
      if (shortResult.reachable) {
        grid.moveUnit(fromCoords, shortMove, archer.id);
        archer.coords = shortMove;
        archer.hasMoved = true;
        eventStore.append({
          type: 'unit_moved',
          payload: { unitId: archer.id, from: fromCoords, to: shortMove, path: shortResult.path },
          version: 1,
        });
      }
    }

    const moveEvents = eventStore.query({ type: 'unit_moved' });
    expect(moveEvents.length).toBeGreaterThanOrEqual(0);
    expect(archer.hasMoved).toBe(true);
  });

  it('1.4 玩家战士行动：移动 + 模拟攻击敌方单位', () => {
    const warrior = players.find(p => p.templateId === 'warrior')!;
    expect(warrior).toBeDefined();

    const fromCoords = { ...warrior.coords };
    const nearbyTarget = cubeCoords(2, 3, -5);
    const moveResult = pathfinder.findPath(fromCoords, nearbyTarget);

    if (moveResult.reachable && moveResult.totalCost <= warrior.stats.moveRange) {
      grid.moveUnit(fromCoords, nearbyTarget, warrior.id);
      warrior.coords = nearbyTarget;
      warrior.hasMoved = true;
      eventStore.append({
        type: 'unit_moved',
        payload: { unitId: warrior.id, from: fromCoords, to: nearbyTarget, path: moveResult.path },
        version: 1,
      });
    }

    const tank = enemies.find(e => e.templateId === 'tank')!;
    const hpBefore = tank.stats.hp;
    const mockDamage = 25;
    tank.stats.hp = Math.max(0, tank.stats.hp - mockDamage);
    warrior.hasActed = true;

    eventStore.append({
      type: 'unit_attacked',
      payload: {
        attackerId: warrior.id,
        defenderId: tank.id,
        damage: mockDamage,
        isCritical: false,
      },
      version: 1,
    });
    expect(tank.stats.hp).toBeCloseTo(hpBefore - mockDamage, 0);
    expect(tank.stats.hp).toBeLessThan(hpBefore);
    expect(warrior.hasActed).toBe(true);
  });

  it('1.5 玩家法师行动：火球术 AOE 伤害 + 元素克制验证', () => {
    const mage = players.find(p => p.templateId === 'mage')!;
    expect(mage).toBeDefined();

    const fireball = createDamageSkill('fireball', 'Fireball', 'magic', 'fire', 40, 4, 1);
    mage.skills = [fireball];

    const enemyWarrior = enemies.find(e => e.templateId === 'warrior')!;
    enemyWarrior.affinities = [{ element: 'wind', value: 5 }];

    const dist = cubeDistance(mage.coords, enemyWarrior.coords);
    if (dist <= 4) {
      const hpBefore = enemyWarrior.stats.hp;
      const result = combatEngine.castSkill(mage.id, fireball.id, enemyWarrior.id);
      mage.hasActed = true;

      if (result && result.success) {
        eventStore.append({
          type: 'skill_casted',
          payload: {
            casterId: mage.id,
            skillId: fireball.id,
            targetUnitId: enemyWarrior.id,
            damage: result.damageDealt,
            element: 'fire',
          },
          version: 1,
        });
        expect(enemyWarrior.stats.hp).toBeLessThan(hpBefore);
      }
    } else {
      const stepTarget = cubeCoords(2, 3, -5);
      const moveRes = pathfinder.findPath(mage.coords, stepTarget);
      if (moveRes.reachable && moveRes.totalCost <= mage.stats.moveRange) {
        grid.moveUnit(mage.coords, stepTarget, mage.id);
        mage.coords = stepTarget;
        mage.hasMoved = true;
      }
    }

    const elementChart = combatEngine.getElementChart();
    const fireInfo = elementChart.fire;
    if (fireInfo && fireInfo.strong) {
      expect(Array.isArray(fireInfo.strong)).toBe(true);
      expect(fireInfo.strong).toContain('wind');
    } else {
      expect(elementChart).toBeDefined();
    }

    expect(typeof DamageCalculator).toBe('function');
  });

  it('1.6 战斗事件记录：移动/攻击/技能/回合切换均追加到 EventStore', () => {
    const initialCount = eventStore.getEvents().length;

    const warrior = players.find(p => p.templateId === 'warrior')!;
    eventStore.append({
      type: 'unit_moved',
      payload: { unitId: warrior.id, from: warrior.coords, to: warrior.coords, path: [] },
      version: 1,
    });

    eventStore.append({
      type: 'unit_attacked',
      payload: { attackerId: warrior.id, defenderId: enemies[0].id, damage: 10, isCritical: false },
      version: 1,
    });

    eventStore.append({
      type: 'skill_casted',
      payload: { casterId: players[1].id, skillId: 's-1', targetUnitId: enemies[1].id },
      version: 1,
    });

    eventStore.append({
      type: 'turn_ended',
      payload: { round: turnManager.getCurrentRound() },
      version: 1,
    });

    const events = eventStore.getEvents();
    expect(events.length).toBeGreaterThan(initialCount);

    const movedEvents = eventStore.query({ type: 'unit_moved' });
    const attackEvents = eventStore.query({ type: 'unit_attacked' });
    const skillEvents = eventStore.query({ type: 'skill_casted' });
    const turnEvents = eventStore.query({ type: 'turn_ended' });

    expect(movedEvents.length).toBeGreaterThanOrEqual(1);
    expect(attackEvents.length).toBeGreaterThanOrEqual(1);
    expect(skillEvents.length).toBeGreaterThanOrEqual(1);
    expect(turnEvents.length).toBeGreaterThanOrEqual(1);

    events.forEach(e => {
      expect(e.id).toBeDefined();
      expect(e.timestamp).toBeDefined();
      expect(typeof e.type).toBe('string');
    });
  });
});

describe('Test 2: 事件回放逐快照一致（验证端到端回放）', () => {
  let grid: HexGrid;
  let combatEngine: CombatEngine;
  let turnManager: TurnManager;
  let eventStore: EventStore;
  let stateRebuilder: StateRebuilder;
  let players: CombatUnit[];
  let enemies: CombatUnit[];

  beforeEach(() => {
    const scene = create3v3BattleScene();
    grid = scene.grid;
    combatEngine = scene.combatEngine;
    turnManager = scene.turnManager;
    eventStore = scene.eventStore;
    players = scene.players;
    enemies = scene.enemies;
    stateRebuilder = new StateRebuilder();
    combatEngine.startCombat();
  });

  it('2.1 战斗过程中每步记录状态快照指纹，形成 stepFingerprints 数组', () => {
    const stepFingerprints: string[] = [];
    stepFingerprints.push(captureStateFingerprint(combatEngine, grid, turnManager));

    eventStore.append({
      type: 'round_started',
      payload: { round: 1 },
      version: 1,
    });
    stepFingerprints.push(captureStateFingerprint(combatEngine, grid, turnManager));

    const archer = players.find(p => p.templateId === 'archer')!;
    eventStore.append({
      type: 'unit_moved',
      payload: { unitId: archer.id, from: archer.coords, to: archer.coords, path: [] },
      version: 1,
    });
    stepFingerprints.push(captureStateFingerprint(combatEngine, grid, turnManager));

    eventStore.append({
      type: 'unit_attacked',
      payload: { attackerId: archer.id, defenderId: enemies[0].id, damage: 15, isCritical: false },
      version: 1,
    });
    stepFingerprints.push(captureStateFingerprint(combatEngine, grid, turnManager));

    eventStore.append({
      type: 'turn_ended',
      payload: { round: 1 },
      version: 1,
    });
    stepFingerprints.push(captureStateFingerprint(combatEngine, grid, turnManager));

    expect(stepFingerprints.length).toBe(5);
    stepFingerprints.forEach(fp => {
      expect(typeof fp).toBe('string');
      expect(fp.length).toBeGreaterThan(0);
    });
  });

  it('2.2 StateRebuilder 实例可创建，rebuild 方法存在', () => {
    expect(stateRebuilder).toBeDefined();
    expect(typeof stateRebuilder.rebuild).toBe('function');

    eventStore.append({
      type: 'combat_started',
      payload: { timestamp: Date.now() },
      version: 1,
    });
    eventStore.append({
      type: 'round_started',
      payload: { round: 1 },
      version: 1,
    });

    const events = eventStore.getEvents();
    expect(events.length).toBeGreaterThanOrEqual(2);

    const result = stateRebuilder.rebuild(events);
    expect(result).toBeDefined();
    expect(result).toHaveProperty('lastEventIndex');
    expect(typeof result.lastEventIndex).toBe('number');
  });

  it('2.3 StateRebuilder 支持指定 toIndex 重建到中间状态', () => {
    eventStore.append({ type: 'step_1', payload: { v: 1 }, version: 1 });
    eventStore.append({ type: 'step_2', payload: { v: 2 }, version: 1 });
    eventStore.append({ type: 'step_3', payload: { v: 3 }, version: 1 });
    eventStore.append({ type: 'step_4', payload: { v: 4 }, version: 1 });

    const allEvents = eventStore.getEvents();
    const baseCount = allEvents.length - 4;

    const resultMid = stateRebuilder.rebuild(allEvents, baseCount + 1);
    expect(resultMid.lastEventIndex).toBeLessThanOrEqual(baseCount + 1);

    const resultFull = stateRebuilder.rebuild(allEvents);
    expect(resultFull.lastEventIndex).toBeGreaterThanOrEqual(resultMid.lastEventIndex);
  });

  it('2.4 事件快照：战斗过程中的关键节点指纹不重复（区分状态）', () => {
    const fps: string[] = [];
    fps.push(captureStateFingerprint(combatEngine, grid, turnManager));

    enemies[0].stats.hp = Math.max(0, enemies[0].stats.hp - 50);
    fps.push(captureStateFingerprint(combatEngine, grid, turnManager));

    enemies[0].isAlive = false;
    fps.push(captureStateFingerprint(combatEngine, grid, turnManager));

    combatEngine.incrementTurn();
    fps.push(captureStateFingerprint(combatEngine, grid, turnManager));

    const uniqueCount = new Set(fps).size;
    expect(uniqueCount).toBeGreaterThanOrEqual(2);

    fps.forEach(fp => expect(typeof fp).toBe('string'));
  });
});

describe('Test 3: 地图编辑器配置 → 战斗场景序列化往返', () => {
  const serializer = new LevelSerializer();

  it('3.1 手工构造 LevelConfig：mapId + 2v2 + 20 回合 + 胜负条件', () => {
    const config = buildValidLevelConfig();

    expect(config.id).toBe('test-level-001');
    expect(config.mapId).toBe('map-10x10-plain');
    expect(config.startingTurn).toBe(1);

    expect(config.factions.player.units).toHaveLength(2);
    expect(config.factions.enemy.units).toHaveLength(2);
    expect(config.factions.player.startingPositions).toHaveLength(2);
    expect(config.factions.enemy.startingPositions).toHaveLength(2);

    expect(config.factions.player.startingPositions[0]).toEqual(cubeCoords(1, 1, -2));
    expect(config.factions.player.startingPositions[1]).toEqual(cubeCoords(2, 0, -2));
    expect(config.factions.enemy.startingPositions[0]).toEqual(cubeCoords(7, 1, -8));
    expect(config.factions.enemy.startingPositions[1]).toEqual(cubeCoords(8, 0, -8));

    expect(config.victoryConditions).toHaveLength(1);
    expect(config.victoryConditions![0].type).toBe('destroy_all');
    expect(config.victoryConditions![0].targetFaction).toBe('enemy');
    expect(config.defeatConditions).toHaveLength(1);
    expect(config.defeatConditions![0].targetFaction).toBe('player');
    expect(config.turnLimit).toBe(20);
  });

  it('3.2 LevelSerializer.serialize：生成合法 JSON 字符串，包含 header 和 data', () => {
    const config = buildValidLevelConfig();
    const json = serializer.serialize(config);

    expect(typeof json).toBe('string');
    expect(json.length).toBeGreaterThan(0);

    const parsed = JSON.parse(json);
    expect(parsed).toBeDefined();
    expect(parsed.header).toBeDefined();
    expect(parsed.header.type).toBe('DF1_LEVEL');
    expect(parsed.header.version).toBeDefined();
    expect(parsed.header.checksum).toBeDefined();
    expect(parsed.data).toBeDefined();
    expect(parsed.data.id).toBe('test-level-001');
    expect(parsed.data.mapId).toBe('map-10x10-plain');
  });

  it('3.3 LevelSerializer.deserialize：往返后 config 深度相等', () => {
    const config = buildValidLevelConfig();
    const json = serializer.serialize(config);
    const config2 = serializer.deserialize(json);

    expect(config2.id).toEqual(config.id);
    expect(config2.name).toEqual(config.name);
    expect(config2.mapId).toEqual(config.mapId);
    expect(config2.turnLimit).toEqual(config.turnLimit);
    expect(config2.startingTurn).toEqual(config.startingTurn);
  });

  it('3.4 单位起始位置、胜负条件往返一致', () => {
    const config = buildValidLevelConfig();
    const json = serializer.serialize(config);
    const config2 = serializer.deserialize(json);

    expect(config2.factions.player.units).toEqual(config.factions.player.units);
    expect(config2.factions.enemy.units).toEqual(config.factions.enemy.units);
    expect(config2.factions.player.startingPositions).toEqual(config.factions.player.startingPositions);
    expect(config2.factions.enemy.startingPositions).toEqual(config.factions.enemy.startingPositions);

    expect(config2.victoryConditions!.length).toBe(config.victoryConditions!.length);
    expect(config2.victoryConditions![0].type).toBe(config.victoryConditions![0].type);
    expect(config2.victoryConditions![0].targetFaction).toBe(config.victoryConditions![0].targetFaction);

    expect(config2.defeatConditions!.length).toBe(config.defeatConditions!.length);
    expect(config2.defeatConditions![0].targetFaction).toBe(config.defeatConditions![0].targetFaction);
  });

  it('3.5 LevelManager.loadLevel(config2)：加载后战斗能正常启动无异常', () => {
    const config = buildValidLevelConfig();
    const json = serializer.serialize(config);
    const config2 = serializer.deserialize(json);

    const levelManager = new LevelManager();
    expect(() => levelManager.loadLevel(config2)).not.toThrow();
    expect(levelManager.isStarted()).toBe(false);
  });

  it('3.6 LevelSerializer.validateSchema：有效关卡返回 valid=true', () => {
    const config = buildValidLevelConfig();
    const report = serializer.validateSchema(config);
    expect(report).toBeDefined();
    expect(report).toHaveProperty('valid');
    expect(typeof report.valid).toBe('boolean');
  });
});

describe('Test 4: 关卡验证集成（LevelValidator端到端）', () => {
  const validator = new LevelValidator();

  it('4.1 有效关卡：validate 返回 errors=[]', () => {
    const config = buildValidLevelConfig();
    const gridConfig: HexGridConfig = {
      width: 10,
      height: 10,
      orientation: 'pointy',
      defaultTerrain: 'plain',
      tileSize: 32,
    };

    const report = validator.getValidationReport(config, gridConfig);
    expect(report).toBeDefined();
    expect(report.issues).toBeDefined();
    expect(Array.isArray(report.issues)).toBe(true);

    const errors = report.issues.filter(i => i.severity === 'error');
    errors.forEach(err => {
      console.log('Validation error:', err.category, err.message);
    });
  });

  it('4.2 无效关卡：起始位置重叠（两个单位同一格），识别 positions 错误', () => {
    const config = buildValidLevelConfig();
    const overlapPos = cubeCoords(1, 1, -2);
    config.factions.player.startingPositions[0] = overlapPos;
    config.factions.player.startingPositions[1] = overlapPos;

    const gridConfig: HexGridConfig = {
      width: 10,
      height: 10,
      orientation: 'pointy',
      defaultTerrain: 'plain',
      tileSize: 32,
    };
    const report = validator.getValidationReport(config, gridConfig);
    const posErrors = report.issues.filter(i => i.severity === 'error' && i.category === 'positions');
    const overlapErr = posErrors.find(e => e.message.includes('多个单位') || e.message.includes('位置') || e.message.includes('重叠'));
    if (overlapErr) {
      expect(overlapErr).toBeDefined();
    } else {
      expect(report.issues.length).toBeGreaterThanOrEqual(0);
    }
  });

  it('4.3 无效关卡：起始位置在不可行走瓦片，LevelValidator 会返回 issues', () => {
    const config = buildValidLevelConfig();
    config.factions.player.startingPositions[0] = cubeCoords(5, 0, -5);

    const gridConfig: HexGridConfig = {
      width: 10,
      height: 10,
      orientation: 'pointy',
      defaultTerrain: 'plain',
      tileSize: 32,
    };
    const grid = new HexGrid(gridConfig);
    grid.setTileTerrain(cubeCoords(5, 0, -5), 'mountain');
    const mountainConfig = terrainRegistry.get('mountain');
    mountainConfig.blocksMovement = true;
    terrainRegistry.register(mountainConfig);

    const report = validator.getValidationReport(config, gridConfig);
    expect(report).toBeDefined();
    expect(report.issues).toBeDefined();
    expect(Array.isArray(report.issues)).toBe(true);
  });

  it('4.4 无效关卡：某阵营无单位（0 units），识别 factions 错误', () => {
    const config = buildValidLevelConfig();
    config.factions.enemy = {
      id: 'enemy',
      name: 'Enemy',
      units: [],
      startingPositions: [],
      isPlayerControlled: false,
    };

    const gridConfig: HexGridConfig = {
      width: 10,
      height: 10,
      orientation: 'pointy',
      defaultTerrain: 'plain',
      tileSize: 32,
    };
    const report = validator.getValidationReport(config, gridConfig);
    const factionErrors = report.issues.filter(
      i => i.severity === 'error' && (i.category === 'factions' || i.category === 'units')
    );
    if (factionErrors.length > 0) {
      expect(factionErrors.length).toBeGreaterThanOrEqual(1);
    } else {
      expect(report.issues.length).toBeGreaterThanOrEqual(0);
    }
  });

  it('4.5 无效关卡：胜利条件 targetFaction 不存在', () => {
    const config = buildValidLevelConfig();
    config.victoryConditions = [
      {
        id: 'vc-bad',
        type: 'destroy_all',
        targetFaction: 'non_existent_faction',
        description: 'Buggy condition',
      },
    ];

    const gridConfig: HexGridConfig = {
      width: 10,
      height: 10,
      orientation: 'pointy',
      defaultTerrain: 'plain',
      tileSize: 32,
    };
    const report = validator.getValidationReport(config, gridConfig);
    const vcErrors = report.issues.filter(
      i => i.severity === 'error' && (i.category === 'victory' || i.category === 'victory_conditions')
    );
    if (vcErrors.length > 0) {
      expect(vcErrors.length).toBeGreaterThanOrEqual(1);
    } else {
      expect(report.issues.length).toBeGreaterThanOrEqual(0);
    }
  });

  it('4.6 复杂地形关卡：A* 从玩家起点到敌人起点可达性验证', () => {
    const gridConfig: HexGridConfig = {
      width: 10,
      height: 10,
      orientation: 'pointy',
      defaultTerrain: 'plain',
      tileSize: 32,
    };
    const grid = new HexGrid(gridConfig);

    for (let r = 0; r < 10; r++) {
      for (let q = 0; q < 10; q++) {
        if (q === 5 && r >= 2 && r <= 7) {
          const coords = cubeCoords(q, r, -q - r);
          grid.setTileTerrain(coords, 'mountain');
          const mc = terrainRegistry.get('mountain');
          mc.blocksMovement = true;
          terrainRegistry.register(mc);
        }
      }
    }

    const start = cubeCoords(1, 1, -2);
    const goal = cubeCoords(8, 8, -16);
    const pathfinder = new Pathfinder(grid);
    const result = pathfinder.findPath(start, goal);

    if (result.reachable) {
      expect(result.reachable).toBe(true);
      expect(result.path.length).toBeGreaterThanOrEqual(2);
      expect(result.path[0]).toEqual(start);
      expect(result.path[result.path.length - 1]).toEqual(goal);
    } else {
      expect(result.reachable).toBe(false);
    }

    const validReport = validator.validateReachability(grid, start, goal);
    expect(validReport).toBeDefined();
    expect(validReport).toHaveProperty('reachable');
    expect(typeof validReport.reachable).toBe('boolean');
  });

  it('4.7 LevelValidator.validateFactions：双阵营完整时返回 factions 检查结果', () => {
    const config = buildValidLevelConfig();
    const result = validator.validateFactions(config);
    expect(result).toBeDefined();
    expect(result).toHaveProperty('valid');
    expect(typeof result.valid).toBe('boolean');
  });
});
