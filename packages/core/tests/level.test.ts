import { describe, it, expect, beforeEach } from 'vitest';
import {
  LevelManager,
  LevelValidator,
  BalanceEvaluator,
  LevelSerializer,
} from '../src/level';
import { HexGrid } from '../src/grid/HexGrid';
import { cubeCoords } from '../src/grid/coords';
import type {
  LevelConfig,
  VictoryCondition,
  Faction,
  CubeCoords,
  CombatUnit,
  ID,
  HexGridConfig,
} from '../src/types';

function createBaseLevelConfig(): LevelConfig {
  return {
    id: 'lv_test_001',
    name: '测试关卡',
    description: '这是一个用于单元测试的示例关卡',
    mapId: 'map_test_001',
    factions: {
      player: {
        units: ['p_warrior', 'p_mage'],
        startingPositions: [
          cubeCoords(0, 2, -2),
          cubeCoords(0, 3, -3),
        ],
      },
      enemy: {
        units: ['e_goblin_1', 'e_goblin_2'],
        startingPositions: [
          cubeCoords(7, 2, -9),
          cubeCoords(7, 3, -10),
        ],
      },
    },
    victoryConditions: [
      {
        id: 'vc_kill_all',
        type: 'eliminate',
        description: '消灭所有敌人',
        targetFaction: 'enemy',
        targetProgress: 2,
        progress: 0,
        isCompleted: false,
        params: { targetFaction: 'enemy' },
      },
    ],
    defeatConditions: [
      {
        id: 'dc_player_dead',
        type: 'eliminate',
        description: '所有玩家阵亡',
        targetFaction: 'player',
        targetProgress: 2,
        progress: 0,
        isCompleted: false,
        params: { targetFaction: 'player' },
      },
    ],
    reinforcements: [
      {
        turn: 3,
        faction: 'enemy',
        unitIds: ['e_boss'],
        positions: [cubeCoords(9, 0, -9)],
      },
    ],
    startingTurn: 1,
    turnLimit: 30,
    environmentalEffects: [],
  };
}

const DEFAULT_GRID_CONFIG: HexGridConfig = {
  width: 10,
  height: 10,
  orientation: 'pointy',
  defaultTerrain: 'plain',
  tileSize: 32,
};

describe('LevelManager', () => {
  let manager: LevelManager;
  let config: LevelConfig;

  beforeEach(() => {
    manager = new LevelManager();
    config = createBaseLevelConfig();
  });

  it('loadLevel 从JSON加载关卡配置', () => {
    manager.loadLevel(config, DEFAULT_GRID_CONFIG);

    const level = manager.getCurrentLevel();
    expect(level).not.toBeNull();
    expect(level!.id).toBe('lv_test_001');
    expect(manager.getUnits().size).toBe(4);

    const players = manager.getUnitsByFaction('player');
    const enemies = manager.getUnitsByFaction('enemy');
    expect(players.length).toBe(2);
    expect(enemies.length).toBe(2);

    const grid = manager.getGrid();
    expect(grid).not.toBeNull();
    expect(grid instanceof HexGrid).toBe(true);
  });

  it('triggerReinforcements 在指定回合增援', () => {
    manager.loadLevel(config, DEFAULT_GRID_CONFIG);

    const before3 = manager.triggerReinforcements(1);
    expect(before3).toHaveLength(0);

    const at3 = manager.triggerReinforcements(3);
    expect(at3).toHaveLength(1);
    expect(at3[0].faction).toBe('enemy');
    expect(typeof at3[0].spawnedUnits.length).toBe('number');
    expect(manager.getUnitsByFaction('enemy').length).toBeGreaterThanOrEqual(2);

    const again = manager.triggerReinforcements(3);
    expect(again).toHaveLength(0);
  });

  it('startLevel 启动后发布事件', () => {
    manager.loadLevel(config, DEFAULT_GRID_CONFIG);
    expect(manager.isStarted()).toBe(false);

    manager.startLevel();
    expect(manager.isStarted()).toBe(true);

    const events = manager.getEventStore().getEvents();
    expect(events.length).toBeGreaterThanOrEqual(1);
    expect(events.some(e => e.type === 'GAME_START')).toBe(true);
  });

  it('checkVictoryProgress 进度检测', () => {
    manager.loadLevel(config, DEFAULT_GRID_CONFIG);
    manager.startLevel();

    const progress = manager.checkVictoryProgress();
    expect(progress.victories).toHaveLength(1);
    expect(progress.defeats).toHaveLength(1);
    expect(progress.allVictoriesComplete).toBe(false);
    expect(progress.anyDefeatComplete).toBe(false);

    const units = manager.getUnits();
    for (const [id, u] of units) {
      if (u.faction === 'enemy') {
        u.isAlive = false;
        u.stats.hp = 0;
      }
    }

    const afterKill = manager.checkVictoryProgress();
    expect(afterKill.allVictoriesComplete).toBe(true);
    expect(manager.getWinner()).toBe('player');
  });

  it('startLevel 必须先loadLevel', () => {
    expect(() => manager.startLevel()).toThrowError(/no level loaded/);
  });

  it('resetLevel 重置所有状态', () => {
    manager.loadLevel(config, DEFAULT_GRID_CONFIG);
    manager.startLevel();
    manager.resetLevel();

    expect(manager.getCurrentLevel()).toBeNull();
    expect(manager.getGrid()).toBeNull();
    expect(manager.getUnits().size).toBe(0);
    expect(manager.isStarted()).toBe(false);
  });
});

describe('LevelValidator', () => {
  let validator: LevelValidator;
  let config: LevelConfig;
  let grid: HexGrid;

  beforeEach(() => {
    validator = new LevelValidator();
    config = createBaseLevelConfig();
    grid = new HexGrid(DEFAULT_GRID_CONFIG);
  });

  it('validateReachability 可达路径正确', () => {
    const reachable = validator.validateReachability(
      grid,
      cubeCoords(0, 0, 0),
      cubeCoords(3, 0, -3),
      Infinity
    );
    expect(reachable.reachable).toBe(true);
    expect(reachable.distance).toBe(3);
    expect(reachable.pathCost).toBeGreaterThan(0);
  });

  it('validateReachability 被墙阻隔不可达', () => {
    const wallGrid = new HexGrid(DEFAULT_GRID_CONFIG);
    for (let col = 0; col < 10; col++) {
      wallGrid.setTileTerrain(cubeCoords(col, 4, -col - 4), 'wall');
    }

    const result = validator.validateReachability(
      wallGrid,
      cubeCoords(0, 2, -2),
      cubeCoords(7, 6, -13),
      Infinity
    );
    expect(result.reachable).toBe(false);
  });

  it('validateStartingPositions 重叠检测', () => {
    const overlapConfig = {
      ...config,
      factions: {
        player: {
          units: ['p1', 'p2'],
          startingPositions: [cubeCoords(0, 0, 0), cubeCoords(0, 0, 0)],
        },
        enemy: {
          units: ['e1'],
          startingPositions: [cubeCoords(5, 0, -5)],
        },
      },
    };

    const result = validator.validateStartingPositions(overlapConfig, grid);
    expect(result.valid).toBe(false);
    expect(result.overlapping.length).toBeGreaterThanOrEqual(1);
  });

  it('validateVictoryConditions 检测缺失参数', () => {
    const badConfig: LevelConfig = {
      ...config,
      victoryConditions: [
        {
          id: 'vc1',
          type: 'eliminate',
          description: '没有指定目标阵营',
          targetFaction: 'enemy',
          targetProgress: 0,
          progress: 0,
          isCompleted: false,
          params: {},
        },
        {
          id: 'vc2',
          type: 'survive',
          description: '没有指定回合数',
          targetFaction: 'player',
          targetProgress: 0,
          progress: 0,
          isCompleted: false,
          params: {},
        },
      ],
      defeatConditions: [],
    };

    const result = validator.validateVictoryConditions(badConfig);
    expect(result.valid).toBe(false);
    expect(result.missingParams.length).toBeGreaterThanOrEqual(1);
  });

  it('getValidationReport 完整报告', () => {
    const report = validator.getValidationReport(config, DEFAULT_GRID_CONFIG);

    expect(report).toHaveProperty('isValid');
    expect(report).toHaveProperty('totalErrors');
    expect(report).toHaveProperty('totalWarnings');
    expect(report).toHaveProperty('issues');
    expect(report).toHaveProperty('summary');
    expect(report.levelId).toBe(config.id);
    expect(report.levelName).toBe(config.name);
    expect(typeof report.summary).toBe('string');
  });

  it('空条件检测', () => {
    const emptyCondConfig: LevelConfig = {
      ...config,
      victoryConditions: [],
      defeatConditions: [],
    };

    const result = validator.validateVictoryConditions(emptyCondConfig);
    expect(result.noConditions).toBe(true);
    expect(result.valid).toBe(false);
  });
});

describe('BalanceEvaluator', () => {
  let evaluator: BalanceEvaluator;
  let config: LevelConfig;
  let unitTemplates: Map<ID, Partial<CombatUnit>>;

  beforeEach(() => {
    evaluator = new BalanceEvaluator();
    config = createBaseLevelConfig();
    unitTemplates = new Map();
    unitTemplates.set('p_warrior', {
      stats: {
        maxHp: 150, hp: 150, maxMp: 30, mp: 30,
        attack: 35, defense: 20, magicAttack: 10, magicDefense: 10,
        speed: 8, accuracy: 90, evasion: 5, critRate: 5, critDamage: 150,
        armorPenetration: 0, moveRange: 3, attackRange: 1, visionRange: 5, height: 1,
      },
    });
    unitTemplates.set('p_mage', {
      stats: {
        maxHp: 80, hp: 80, maxMp: 100, mp: 100,
        attack: 15, defense: 8, magicAttack: 40, magicDefense: 25,
        speed: 10, accuracy: 95, evasion: 12, critRate: 15, critDamage: 180,
        armorPenetration: 10, moveRange: 3, attackRange: 3, visionRange: 7, height: 1,
      },
    });
    unitTemplates.set('e_goblin_1', {
      stats: {
        maxHp: 70, hp: 70, maxMp: 20, mp: 20,
        attack: 22, defense: 8, magicAttack: 5, magicDefense: 5,
        speed: 12, accuracy: 80, evasion: 10, critRate: 8, critDamage: 140,
        armorPenetration: 0, moveRange: 4, attackRange: 1, visionRange: 5, height: 1,
      },
    });
    unitTemplates.set('e_goblin_2', {
      stats: {
        maxHp: 70, hp: 70, maxMp: 20, mp: 20,
        attack: 22, defense: 8, magicAttack: 5, magicDefense: 5,
        speed: 12, accuracy: 80, evasion: 10, critRate: 8, critDamage: 140,
        armorPenetration: 0, moveRange: 4, attackRange: 1, visionRange: 5, height: 1,
      },
    });
  });

  it('calculatePowerScore 战力计算', () => {
    const warrior: CombatUnit = {
      id: 't_warrior', name: 'warrior', faction: 'player', templateId: 'p_warrior',
      coords: cubeCoords(0, 0, 0), direction: 0,
      stats: unitTemplates.get('p_warrior')!.stats!,
      attributes: {
        hp: { current: 150, max: 150, min: 0 },
        mp: { current: 30, max: 30, min: 0 },
        attack: { base: 35, modifiers: [], current: 35 },
        defense: { base: 20, modifiers: [], current: 20 },
        magicAttack: { base: 10, modifiers: [], current: 10 },
        magicDefense: { base: 10, modifiers: [], current: 10 },
        speed: { base: 8, modifiers: [], current: 8 },
        accuracy: { base: 90, modifiers: [], current: 90 },
        evasion: { base: 5, modifiers: [], current: 5 },
        critRate: { base: 5, modifiers: [], current: 5 },
        critDamage: { base: 150, modifiers: [], current: 150 },
        armorPenetration: { base: 0, modifiers: [], current: 0 },
        moveRange: { base: 3, modifiers: [], current: 3 },
        attackRange: { base: 1, modifiers: [], current: 1 },
        visionRange: { base: 5, modifiers: [], current: 5 },
      },
      skills: [], passiveSkills: [], statusEffects: [],
      resistances: [], affinities: [], equipment: [],
      isAlive: true, hasActed: false, hasMoved: false, isDelaying: false, tags: [],
    };

    const result = evaluator.calculatePowerScore(warrior);
    expect(result.score).toBeGreaterThan(0);
    expect(result.breakdown.total).toBeCloseTo(result.score);
    expect(result.breakdown.hp).toBeGreaterThan(0);
    expect(result.breakdown.attack).toBeGreaterThan(0);
    expect(result.breakdown.defense).toBeGreaterThan(0);
  });

  it('compareFactions 不平衡检测', () => {
    const comparison = evaluator.compareFactions(config, unitTemplates);

    expect(comparison.factions).toHaveLength(2);
    expect(typeof comparison.imbalanceScore).toBe('number');
    expect(comparison.imbalanceScore).toBeGreaterThanOrEqual(0);
    expect(comparison.imbalanceScore).toBeLessThanOrEqual(1);
    expect(comparison.recommendations).toBeInstanceOf(Array);
  });

  it('suggestBalancing 返回平衡建议', () => {
    const imbalanceConfig: LevelConfig = {
      ...config,
      factions: {
        player: {
          units: ['p_warrior', 'p_mage', 'p_extra'],
          startingPositions: [
            cubeCoords(0, 2, -2),
            cubeCoords(0, 3, -3),
            cubeCoords(0, 4, -4),
          ],
        },
        enemy: {
          units: ['e_goblin_1'],
          startingPositions: [cubeCoords(7, 2, -9)],
        },
      },
    };
    unitTemplates.set('p_extra', unitTemplates.get('p_warrior')!);

    const suggestions = evaluator.suggestBalancing(imbalanceConfig, unitTemplates);

    expect(Array.isArray(suggestions)).toBe(true);
    if (suggestions.length > 0) {
      const first = suggestions[0];
      expect(['add_unit', 'remove_unit', 'buff_unit', 'nerf_unit', 'adjust_position', 'adjust_terrain']).toContain(first.type);
      expect(['high', 'medium', 'low']).toContain(first.priority);
      expect(typeof first.description).toBe('string');
      expect(first.expectedImpact).toBeGreaterThan(0);
    }
  });

  it('evaluateTurnLength 回合时长评估', () => {
    const result = evaluator.evaluateTurnLength(config, unitTemplates);

    expect(result.totalEstimatedActions).toBeGreaterThan(0);
    expect(result.totalEstimatedTimeSeconds).toBeGreaterThan(0);
    expect(result.maxRecommendedActions).toBe(30);
    expect(result.perFaction).toHaveLength(2);
  });
});

describe('LevelSerializer', () => {
  let serializer: LevelSerializer;
  let config: LevelConfig;

  beforeEach(() => {
    serializer = new LevelSerializer();
    config = createBaseLevelConfig();
  });

  it('serialize/deserialize 往返一致性', () => {
    const json = serializer.serialize(config);

    const restored = serializer.deserialize(json);

    expect(restored.id).toBe(config.id);
    expect(restored.name).toBe(config.name);
    expect(Object.keys(restored.factions)).toEqual(Object.keys(config.factions));
    expect(restored.victoryConditions).toHaveLength(config.victoryConditions.length);
    expect(restored.defeatConditions).toHaveLength(config.defeatConditions.length);
    expect(restored.turnLimit).toBe(config.turnLimit);
    expect(restored.reinforcements).toHaveLength(config.reinforcements?.length ?? 0);
  });

  it('validateSchema 正确校验', () => {
    const valid = serializer.validateSchema(config);
    expect(valid.valid).toBe(true);
    expect(valid.errors).toHaveLength(0);

    const badConfig = { ...config, id: undefined } as unknown as Record<string, unknown>;
    delete badConfig.id;
    const invalid = serializer.validateSchema(badConfig);
    expect(invalid.valid).toBe(false);
    expect(invalid.errors.length).toBeGreaterThan(0);
  });

  it('validateSchema 缺少factions报错', () => {
    const noFac = { id: 'x', name: 'x', factions: {} } as unknown;
    const result = serializer.validateSchema(noFac);
    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.path === 'factions')).toBe(true);
  });

  it('applyDefaults 缺失字段补默认值', () => {
    const minimalConfig: LevelConfig = {
      id: 'minimal',
      name: '最小配置',
      description: '最小配置关卡',
      mapId: 'minimal_map',
      factions: {
        player: { units: ['p1'], startingPositions: [cubeCoords(0, 0, 0)] },
        enemy: { units: ['e1'], startingPositions: [cubeCoords(5, 0, -5)] },
      },
      victoryConditions: [],
      defeatConditions: [],
      reinforcements: undefined as unknown as [],
      environmentalEffects: undefined as unknown as [],
      startingTurn: undefined as unknown as number,
      turnLimit: undefined as unknown as number,
    };

    const json = serializer.serialize(minimalConfig);
    const restored = serializer.deserialize(json, { applyDefaults: true });

    expect(restored.victoryConditions).toEqual([]);
    expect(restored.defeatConditions).toEqual([]);
    expect(Array.isArray(restored.reinforcements)).toBe(true);
    expect(restored.startingTurn).toBe(1);
  });

  it('verifyChecksum 校验和校验', () => {
    const json = serializer.serialize(config);
    const parsed = JSON.parse(json);
    const valid = serializer.verifyChecksum(parsed);
    expect(valid).toBe(true);

    parsed.data = { ...parsed.data, name: '篡改的名字' };
    expect(serializer.verifyChecksum(parsed)).toBe(false);
  });
});
