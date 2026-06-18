import type {
  CubeCoords,
  HexGridConfig,
  TerrainType,
  CombatUnit,
  UnitStats,
  UnitAttributes,
  Skill,
  SkillEffect,
  SkillRange,
  StatusEffect,
  StatusEffectData,
  PassiveSkill,
  DamageCalculationConfig,
  ElementChart,
  TurnOrderConfig,
  VictoryCondition,
  Faction,
  Direction,
  DamageType,
  ElementType,
  StatusEffectType,
  SkillTargetType,
  DamageResistance,
  ElementAffinity,
  TriggerCondition,
  ID,
  Attribute,
  Resource,
} from '../src/types';

import { HexGrid } from '../src/grid/HexGrid';
import { CombatEngine } from '../src/combat/CombatEngine';
import { TurnManager } from '../src/turn/TurnManager';
import { EventStore } from '../src/events/EventStore';
import { StateRebuilder } from '../src/events/StateRebuilder';
import { cubeCoords, offsetToCube, cubeDistance } from '../src/grid/coords';
import { terrainRegistry } from '../src/grid/TerrainConfig';
import { Random } from '../src/utils/math';
import { generateId } from '../src/utils/id';

// ============= 地图构造器 =============

export function createEmptyGrid(
  width: number,
  height: number,
  orientation: 'pointy' | 'flat' = 'pointy'
): HexGrid {
  const config: HexGridConfig = {
    width,
    height,
    orientation,
    defaultTerrain: 'plain',
    tileSize: 1,
  };
  return new HexGrid(config);
}

interface TerrainTileConfig {
  coords: CubeCoords;
  terrain: TerrainType;
  height?: number;
}

export function createGridWithTerrain(
  tiles: TerrainTileConfig[]
): HexGrid {
  if (tiles.length === 0) {
    return createEmptyGrid(1, 1);
  }

  let maxQ = -Infinity, minQ = Infinity;
  let maxR = -Infinity, minR = Infinity;

  for (const tile of tiles) {
    maxQ = Math.max(maxQ, tile.coords.q);
    minQ = Math.min(minQ, tile.coords.q);
    maxR = Math.max(maxR, tile.coords.r);
    minR = Math.min(minR, tile.coords.r);
  }

  const width = maxQ - minQ + 1;
  const height = maxR - minR + 1;
  const grid = createEmptyGrid(Math.max(width, 1), Math.max(height, 1));

  for (const tile of tiles) {
    grid.setTileTerrain(tile.coords, tile.terrain);
    if (tile.height !== undefined) {
      grid.setTileHeight(tile.coords, tile.height);
    }
  }

  return grid;
}

interface Map1v1Result {
  grid: HexGrid;
  p1Start: CubeCoords;
  p2Start: CubeCoords;
}

export function create1v1Map(): Map1v1Result {
  const grid = createEmptyGrid(8, 6);
  const p1Start = cubeCoords(0, 2, -2);
  const p2Start = cubeCoords(5, 3, -8);
  return { grid, p1Start, p2Start };
}

interface Map3v3Result {
  grid: HexGrid;
  playerStarts: CubeCoords[];
  enemyStarts: CubeCoords[];
}

export function create3v3ComplexMap(): Map3v3Result {
  const grid = createEmptyGrid(12, 8);

  const allTiles = grid.getAllTiles();
  for (const tile of allTiles) {
    const col = tile.offsetCoords.col;

    if (col >= 5 && col <= 6) {
      grid.setTileTerrain(tile.coords, 'mountain');
      const mountainConfig = terrainRegistry.get('mountain');
      mountainConfig.blocksMovement = true;
      mountainConfig.blocksVision = true;
      terrainRegistry.register(mountainConfig);
    }

    if (col <= 3) {
      if (tile.offsetCoords.row === 2 || tile.offsetCoords.row === 4) {
        grid.setTileTerrain(tile.coords, 'forest');
      }
    }

    if (col >= 8) {
      if (tile.offsetCoords.row === 1 || tile.offsetCoords.row === 3 || tile.offsetCoords.row === 5) {
        grid.setTileTerrain(tile.coords, 'swamp');
      }
    }
  }

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

  return { grid, playerStarts, enemyStarts };
}

// ============= 单位构造器 =============

function createAttribute(base: number): Attribute {
  return {
    base,
    modifiers: [],
    current: base,
  };
}

function buildAttributesFromStats(stats: UnitStats): UnitAttributes {
  return {
    hp: { current: stats.hp, max: stats.maxHp, min: 0 },
    mp: { current: stats.mp, max: stats.maxMp, min: 0 },
    attack: createAttribute(stats.attack),
    defense: createAttribute(stats.defense),
    magicAttack: createAttribute(stats.magicAttack),
    magicDefense: createAttribute(stats.magicDefense),
    speed: createAttribute(stats.speed),
    accuracy: createAttribute(stats.accuracy),
    evasion: createAttribute(stats.evasion),
    critRate: createAttribute(stats.critRate),
    critDamage: createAttribute(stats.critDamage),
    armorPenetration: createAttribute(stats.armorPenetration),
    moveRange: createAttribute(stats.moveRange),
    attackRange: createAttribute(stats.attackRange),
    visionRange: createAttribute(stats.visionRange),
  };
}

const DEFAULT_UNIT_STATS: UnitStats = {
  maxHp: 100,
  hp: 100,
  maxMp: 50,
  mp: 50,
  attack: 30,
  defense: 15,
  magicAttack: 25,
  magicDefense: 10,
  speed: 10,
  accuracy: 90,
  evasion: 10,
  critRate: 15,
  critDamage: 1.5,
  armorPenetration: 0,
  moveRange: 4,
  attackRange: 1,
  visionRange: 5,
  height: 0,
};

export function createUnit(
  id: ID,
  name: string,
  faction: Faction,
  coords: CubeCoords,
  statsPartial: Partial<UnitStats> = {}
): CombatUnit {
  const stats: UnitStats = { ...DEFAULT_UNIT_STATS, ...statsPartial };
  const attributes = buildAttributesFromStats(stats);

  return {
    id,
    name,
    faction,
    templateId: 'custom',
    coords,
    direction: 0 as Direction,
    stats,
    attributes,
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
  };
}

export function createPhysicalWarrior(
  id: ID,
  faction: Faction,
  coords: CubeCoords
): CombatUnit {
  const resistances: DamageResistance[] = [
    { type: 'physical', value: 20, isPercent: false },
  ];

  const unit = createUnit(id, `Warrior_${id}`, faction, coords, {
    maxHp: 150,
    hp: 150,
    attack: 45,
    defense: 30,
    magicDefense: 8,
    speed: 8,
    accuracy: 95,
    evasion: 5,
  });

  unit.templateId = 'warrior';
  unit.resistances = resistances;
  return unit;
}

export function createMage(
  id: ID,
  faction: Faction,
  coords: CubeCoords
): CombatUnit {
  const resistances: DamageResistance[] = [
    { type: 'magic', value: 30, isPercent: true },
  ];

  const affinities: ElementAffinity[] = [
    { element: 'fire', value: 10 },
  ];

  const unit = createUnit(id, `Mage_${id}`, faction, coords, {
    maxHp: 70,
    hp: 70,
    magicAttack: 55,
    defense: 10,
    magicDefense: 25,
    speed: 7,
    accuracy: 80,
    evasion: 10,
    critRate: 20,
  });

  unit.templateId = 'mage';
  unit.resistances = resistances;
  unit.affinities = affinities;
  return unit;
}

export function createArcher(
  id: ID,
  faction: Faction,
  coords: CubeCoords
): CombatUnit {
  const unit = createUnit(id, `Archer_${id}`, faction, coords, {
    maxHp: 80,
    hp: 80,
    attack: 35,
    defense: 12,
    magicDefense: 10,
    speed: 12,
    accuracy: 95,
    attackRange: 3,
    visionRange: 6,
  });

  unit.templateId = 'archer';
  return unit;
}

export function createTank(
  id: ID,
  faction: Faction,
  coords: CubeCoords
): CombatUnit {
  const resistances: DamageResistance[] = [
    { type: 'physical', value: 50, isPercent: true },
  ];

  const unit = createUnit(id, `Tank_${id}`, faction, coords, {
    maxHp: 200,
    hp: 200,
    attack: 20,
    defense: 50,
    magicDefense: 15,
    speed: 5,
  });

  unit.templateId = 'tank';
  unit.resistances = resistances;
  return unit;
}

// ============= 技能与状态效果构造器 =============

interface SkillConfigPartial {
  cooldown?: number;
  mpCost?: number;
  hpCost?: number;
  castTime?: number;
  isDelayed?: boolean;
  targetType?: SkillTargetType;
  canTargetSelf?: boolean;
  canTargetAlly?: boolean;
  canTargetEnemy?: boolean;
  tags?: string[];
}

export function createSkill(
  id: ID,
  name: string,
  type: Skill['type'],
  damageType: DamageType,
  element: ElementType,
  range: number | SkillRange,
  effects: SkillEffect[],
  configPartial: SkillConfigPartial = {}
): Skill {
  const skillRange: SkillRange = typeof range === 'number'
    ? { min: 1, max: range, type: 'any', requiresLineOfSight: true }
    : range;

  const defaultTargetType: SkillTargetType = type === 'passive' ? 'self' : 'single';
  const targetType = configPartial.targetType ?? defaultTargetType;

  const isOffensive = type === 'active' && (targetType === 'single' || targetType === 'enemy' || targetType === 'area' || targetType === 'line' || targetType === 'cone' || targetType === 'allEnemy');
  const isSupportive = type === 'active' && (targetType === 'ally' || targetType === 'allAlly' || targetType === 'self');

  const canTargetSelf = configPartial.canTargetSelf ?? (targetType === 'self' || isSupportive);
  const canTargetAlly = configPartial.canTargetAlly ?? (targetType === 'ally' || targetType === 'allAlly' || isSupportive);
  const canTargetEnemy = configPartial.canTargetEnemy ?? (isOffensive);

  return {
    id,
    name,
    description: `${name} skill`,
    type,
    targetType,
    range: skillRange,
    effects,
    cooldown: configPartial.cooldown ?? 0,
    currentCooldown: 0,
    mpCost: configPartial.mpCost ?? 10,
    hpCost: configPartial.hpCost ?? 0,
    castTime: configPartial.castTime ?? 0,
    isDelayed: configPartial.isDelayed ?? false,
    interruptPriority: 0,
    element,
    damageType,
    canTargetSelf,
    canTargetAlly,
    canTargetEnemy,
    canTargetTerrain: targetType === 'terrain',
    tags: configPartial.tags ?? [],
  };
}

export function createDamageSkill(
  id: ID,
  name: string,
  damageType: DamageType,
  element: ElementType,
  value: number,
  range: number = 1,
  aoeRadius: number = 0
): Skill {
  const effects: SkillEffect[] = [
    {
      type: 'damage',
      target: aoeRadius > 0 ? 'area' : 'single',
      value,
      damageType,
      element,
      aoeRadius,
    },
  ];

  return createSkill(id, name, 'active', damageType, element, range, effects);
}

export function createWarrior(
  id: ID,
  faction: Faction,
  coords: CubeCoords
): CombatUnit {
  return createPhysicalWarrior(id, faction, coords);
}

export function createDotEffect(
  id: ID,
  valueOrName: number | string,
  durationOrType?: number | StatusEffectType,
  tickIntervalOrValue?: number,
  damageType?: DamageType,
  element?: ElementType,
  stacks: number = 1
): StatusEffect {
  let name: string;
  let type: StatusEffectType;
  let value: number;
  let duration: number;
  let tickInterval: number;

  if (typeof valueOrName === 'number') {
    name = `Dot_${id}`;
    value = valueOrName;
    type = 'dot';
    duration = durationOrType as number ?? 3;
    tickInterval = tickIntervalOrValue as number ?? 1;
    damageType = damageType ?? 'physical';
    element = element ?? 'neutral';
  } else {
    name = valueOrName;
    type = durationOrType as StatusEffectType ?? 'dot';
    value = tickIntervalOrValue as number ?? 10;
    duration = (arguments.length > 5 ? arguments[5] : 3) as number;
    tickInterval = (arguments.length > 6 ? arguments[6] : 1) as number;
    damageType = damageType ?? 'physical';
    element = element ?? 'neutral';
  }
  const effects: StatusEffectData[] = [
    {
      value,
      modifierType: 'add',
      damageType,
      element,
    },
  ];

  return {
    id,
    type,
    name,
    description: `${name} - deals ${value} ${damageType} damage every ${tickInterval} turn(s)`,
    duration,
    maxDuration: duration,
    tickInterval,
    lastTick: 0,
    stackCount: stacks,
    maxStacks: Math.max(stacks, 5),
    source: 'factory',
    isDebuff: true,
    effects,
  };
}

export function createHotEffect(
  id: ID,
  valueOrName: number | string,
  durationOrValue?: number,
  tickIntervalOrDuration?: number,
  tickInterval: number = 1
): StatusEffect {
  let name: string;
  let value: number;
  let duration: number;
  let actualTickInterval: number;

  if (typeof valueOrName === 'number') {
    name = `Hot_${id}`;
    value = valueOrName;
    duration = durationOrValue as number ?? 3;
    actualTickInterval = tickIntervalOrDuration as number ?? 1;
  } else {
    name = valueOrName;
    value = durationOrValue as number ?? 10;
    duration = tickIntervalOrDuration as number ?? 3;
    actualTickInterval = tickInterval;
  }

  const effects: StatusEffectData[] = [
    {
      value,
      modifierType: 'add',
    },
  ];

  return {
    id,
    type: 'hot',
    name,
    description: `${name} - heals ${value} HP every ${actualTickInterval} turn(s)`,
    duration,
    maxDuration: duration,
    tickInterval: actualTickInterval,
    lastTick: 0,
    stackCount: 1,
    maxStacks: 1,
    source: 'factory',
    isDebuff: false,
    effects,
  };
}

export function createPassiveSkill(
  id: ID,
  name: string,
  triggerType: TriggerCondition['type'],
  handlerType: 'buff' | 'damage' | 'heal' | 'debuff',
  value: number,
  targetFilter: 'self' | 'ally' | 'enemy' = 'self'
): PassiveSkill {
  const triggerConditions: TriggerCondition[] = [
    {
      type: triggerType,
      targetFilter,
    },
  ];

  const skill = createSkill(
    id,
    name,
    'passive',
    'physical',
    'neutral',
    0,
    [],
    { tags: ['passive'] }
  );

  return {
    ...skill,
    type: 'passive',
    auraRadius: undefined,
    isActive: true,
    appliedEffects: new Map(),
    triggerConditions,
    effects: [
      {
        type: handlerType,
        target: targetFilter,
        value,
      },
    ],
  };
}

// ============= 配置构造器 =============

const DEFAULT_DAMAGE_CONFIG: DamageCalculationConfig = {
  baseFormula: 'max(attack - defense * 0.5, 1)',
  critMultiplier: 1.5,
  minDamage: 1,
  maxDamage: 9999,
  elementAdvantageMultiplier: 1.5,
  elementDisadvantageMultiplier: 0.7,
  armorFormula: 'defense * 0.5',
  resistanceFormula: '1 - resistance',
  terrainBonusFormula: 'terrainBonus / 100',
  heightBonusPerLevel: 0.1,
  directionBackDamageMultiplier: 1.5,
  directionSideDamageMultiplier: 1.15,
  directionFrontDamageMultiplier: 1,
};

export function createDamageConfig(
  partial: Partial<DamageCalculationConfig> = {}
): DamageCalculationConfig {
  return { ...DEFAULT_DAMAGE_CONFIG, ...partial };
}

export function createElementChart(): ElementChart {
  return {
    fire: { strong: ['wind', 'ice'], weak: ['water'] },
    water: { strong: ['fire', 'lightning'], weak: ['earth'] },
    earth: { strong: ['lightning', 'water'], weak: ['wind'] },
    wind: { strong: ['earth'], weak: ['fire'] },
    light: { strong: ['dark'], weak: [] },
    dark: { strong: ['light'], weak: [] },
    lightning: { strong: [], weak: ['water', 'earth'] },
    ice: { strong: [], weak: ['fire'] },
    neutral: { strong: [], weak: [] },
  };
}

const DEFAULT_TURN_ORDER_CONFIG: TurnOrderConfig = {
  speedSortOrder: 'desc',
  enableDelayAction: true,
  enableInterrupts: true,
  interruptPriorityBias: 10,
};

export function createTurnOrderConfig(
  partial: Partial<TurnOrderConfig> = {}
): TurnOrderConfig {
  return { ...DEFAULT_TURN_ORDER_CONFIG, ...partial };
}

export function createVictoryCondition(
  type: VictoryCondition['type'],
  targetFaction: Faction,
  params: Record<string, unknown> = {}
): VictoryCondition {
  const descriptions: Record<VictoryCondition['type'], string> = {
    eliminate: '消灭所有敌方单位',
    capture: '占领目标区域',
    survive: '生存指定回合数',
    turnLimit: '在回合限制内达成目标',
    custom: '自定义胜利条件',
  };

  const defaultTargetProgress: Record<VictoryCondition['type'], number> = {
    eliminate: 1,
    capture: 1,
    survive: params.turnLimit as number ?? 10,
    turnLimit: params.turnLimit as number ?? 20,
    custom: 1,
  };

  return {
    id: generateId(),
    type,
    description: descriptions[type],
    targetFaction,
    params,
    isCompleted: false,
    progress: 0,
    targetProgress: defaultTargetProgress[type],
  };
}

// ============= 场景构造器 =============

interface DuelSceneResult {
  grid: HexGrid;
  combatEngine: CombatEngine;
  player: CombatUnit;
  enemy: CombatUnit;
  turnManager: TurnManager;
  eventStore: EventStore;
}

export function create1v1DuelScene(
  playerUnit?: CombatUnit,
  enemyUnit?: CombatUnit
): DuelSceneResult {
  const { grid, p1Start, p2Start } = create1v1Map();

  const damageConfig = createDamageConfig();
  const elementChart = createElementChart();
  const combatEngine = new CombatEngine(damageConfig, elementChart);

  const player = playerUnit ?? createPhysicalWarrior('player-1', 'player', p1Start);
  const enemy = enemyUnit ?? createPhysicalWarrior('enemy-1', 'enemy', p2Start);

  combatEngine.addUnit(player);
  combatEngine.addUnit(enemy);
  grid.addUnit(p1Start, player.id);
  grid.addUnit(p2Start, enemy.id);

  const turnOrderConfig = createTurnOrderConfig();
  const turnManager = new TurnManager(turnOrderConfig, [
    { id: player.id, speed: player.stats.speed },
    { id: enemy.id, speed: enemy.stats.speed },
  ]);

  const eventStore = new EventStore();

  return { grid, combatEngine, player, enemy, turnManager, eventStore };
}

interface BattleSceneResult {
  grid: HexGrid;
  combatEngine: CombatEngine;
  players: CombatUnit[];
  enemies: CombatUnit[];
  turnManager: TurnManager;
  eventStore: EventStore;
}

type UnitTemplateId = 'warrior' | 'mage' | 'archer' | 'tank';

const UNIT_FACTORIES: Record<UnitTemplateId, (id: ID, faction: Faction, coords: CubeCoords) => CombatUnit> = {
  warrior: createPhysicalWarrior,
  mage: createMage,
  archer: createArcher,
  tank: createTank,
};

export function create3v3BattleScene(
  playerTemplateIds: UnitTemplateId[] = ['warrior', 'mage', 'archer'],
  enemyTemplateIds: UnitTemplateId[] = ['tank', 'warrior', 'mage']
): BattleSceneResult {
  const { grid, playerStarts, enemyStarts } = create3v3ComplexMap();

  const damageConfig = createDamageConfig();
  const elementChart = createElementChart();
  const combatEngine = new CombatEngine(damageConfig, elementChart);

  const players: CombatUnit[] = playerTemplateIds.map((templateId, idx) => {
    const factory = UNIT_FACTORIES[templateId];
    const unit = factory(`p-${idx + 1}`, 'player', playerStarts[idx]);
    combatEngine.addUnit(unit);
    grid.addUnit(playerStarts[idx], unit.id);
    return unit;
  });

  const enemies: CombatUnit[] = enemyTemplateIds.map((templateId, idx) => {
    const factory = UNIT_FACTORIES[templateId];
    const unit = factory(`e-${idx + 1}`, 'enemy', enemyStarts[idx]);
    combatEngine.addUnit(unit);
    grid.addUnit(enemyStarts[idx], unit.id);
    return unit;
  });

  const allUnits = [...players, ...enemies];
  const turnOrderConfig = createTurnOrderConfig();
  const turnManager = new TurnManager(
    turnOrderConfig,
    allUnits.map(u => ({ id: u.id, speed: u.stats.speed }))
  );

  const eventStore = new EventStore();

  return { grid, combatEngine, players, enemies, turnManager, eventStore };
}

export function createCombatEngineWithUnits(
  units: CombatUnit[],
  damageConfig?: DamageCalculationConfig,
  elementChart?: ElementChart
): CombatEngine {
  const engine = new CombatEngine(
    damageConfig ?? createDamageConfig(),
    elementChart ?? createElementChart()
  );

  for (const unit of units) {
    engine.addUnit(unit);
  }

  return engine;
}

interface EventStoreRebuilderPair {
  eventStore: EventStore;
  stateRebuilder: StateRebuilder;
}

export function createEventStoreAndRebuilder(): EventStoreRebuilderPair {
  const eventStore = new EventStore();
  const stateRebuilder = new StateRebuilder();
  return { eventStore, stateRebuilder };
}

interface FuzzOperationLog {
  step: number;
  unitId: ID;
  action: 'move' | 'attack' | 'wait' | 'cast_skill';
  target?: ID | CubeCoords;
  skillId?: ID;
  success: boolean;
  message?: string;
}

export function randomFuzzOperations(
  combatEngine: CombatEngine,
  turnManager: TurnManager,
  eventStore: EventStore,
  steps: number = 100,
  seed: number = 12345
): FuzzOperationLog[] {
  const random = new Random(seed);
  const logs: FuzzOperationLog[] = [];
  const aliveUnits = combatEngine.getAliveUnits();

  if (aliveUnits.length === 0) {
    return logs;
  }

  for (let step = 0; step < steps; step++) {
    const currentUnitId = turnManager.getCurrentUnit();
    const unitId = currentUnitId ?? aliveUnits[random.int(0, aliveUnits.length - 1)].id;
    const unit = combatEngine.getUnit(unitId);

    if (!unit || !unit.isAlive) {
      logs.push({
        step,
        unitId,
        action: 'wait',
        success: false,
        message: 'Unit not found or dead',
      });
      continue;
    }

    const actions: Array<'move' | 'attack' | 'wait' | 'cast_skill'> = ['wait', 'attack', 'move'];
    if (unit.skills.length > 0) {
      actions.push('cast_skill');
    }

    const action = actions[random.int(0, actions.length - 1)];
    let log: FuzzOperationLog;

    switch (action) {
      case 'move': {
        const neighbors = combatEngine
          .getAliveUnits()
          .filter(u => u.id !== unit.id)
          .map(u => u.coords);

        if (neighbors.length > 0) {
          const targetCoords = neighbors[random.int(0, neighbors.length - 1)];
          log = {
            step,
            unitId,
            action: 'move',
            target: targetCoords,
            success: true,
          };
          eventStore.append({
            type: 'UNIT_MOVE',
            turnNumber: combatEngine.getCurrentTurn() || 1,
            data: {
              unitId,
              from: unit.coords,
              to: targetCoords,
              path: [unit.coords, targetCoords],
              moveCost: 1,
            },
            metadata: {
              source: unitId,
              position: targetCoords,
              faction: unit.faction,
            },
          });
        } else {
          log = {
            step,
            unitId,
            action: 'move',
            success: false,
            message: 'No valid move targets',
          };
        }
        break;
      }

      case 'attack': {
        const enemies = combatEngine
          .getAliveUnits()
          .filter(u => u.faction !== unit.faction);

        if (enemies.length > 0) {
          const target = enemies[random.int(0, enemies.length - 1)];
          const damage = combatEngine.attack(unitId, target.id);
          log = {
            step,
            unitId,
            action: 'attack',
            target: target.id,
            success: damage !== null,
          };
          if (damage) {
            eventStore.append({
              type: 'UNIT_ATTACK',
              turnNumber: combatEngine.getCurrentTurn() || 1,
              data: {
                attackerId: unitId,
                defenderId: target.id,
                isHit: !damage.isDodged,
                isCrit: damage.isCrit,
                damage,
              },
              metadata: {
                source: unitId,
                target: target.id,
                position: target.coords,
                faction: unit.faction,
              },
            });
            if (!target.isAlive) {
              eventStore.append({
                type: 'UNIT_DEATH',
                turnNumber: combatEngine.getCurrentTurn() || 1,
                data: {
                  unitId: target.id,
                  killerId: unitId,
                  position: target.coords,
                  damage,
                  effectsOnDeath: target.tags,
                },
                metadata: {
                  source: unitId,
                  target: target.id,
                  position: target.coords,
                  faction: target.faction,
                },
              });
            }
          }
        } else {
          log = {
            step,
            unitId,
            action: 'attack',
            success: false,
            message: 'No valid attack targets',
          };
        }
        break;
      }

      case 'cast_skill': {
        if (unit.skills.length > 0) {
          const skill = unit.skills[random.int(0, unit.skills.length - 1)];
          const enemies = combatEngine
            .getAliveUnits()
            .filter(u => u.faction !== unit.faction);

          if (enemies.length > 0) {
            const target = enemies[random.int(0, enemies.length - 1)];
            const result = combatEngine.castSkill(unitId, skill.id, target.id);
            log = {
              step,
              unitId,
              action: 'cast_skill',
              skillId: skill.id,
              target: target.id,
              success: result?.success ?? false,
            };
            eventStore.append({
              type: 'UNIT_CAST_SKILL',
              turnNumber: combatEngine.getCurrentTurn() || 1,
              data: {
                casterId: unitId,
                skillId: skill.id,
                targetUnitId: target.id,
                isDelayed: skill.isDelayed,
              },
              metadata: {
                source: unitId,
                target: target.id,
                position: target.coords,
                faction: unit.faction,
              },
            });
          } else {
            log = {
              step,
              unitId,
              action: 'cast_skill',
              skillId: skill.id,
              success: false,
              message: 'No valid skill targets',
            };
          }
        } else {
          log = {
            step,
            unitId,
            action: 'cast_skill',
            success: false,
            message: 'Unit has no skills',
          };
        }
        break;
      }

      case 'wait':
      default: {
        log = {
          step,
          unitId,
          action: 'wait',
          success: true,
        };
        break;
      }
    }

    logs.push(log);

    const winner = combatEngine.checkVictory();
    if (winner) {
      eventStore.append({
        type: 'VICTORY',
        turnNumber: combatEngine.getCurrentTurn() || 1,
        data: { winner },
        metadata: { faction: winner },
      });
      break;
    }
  }

  return logs;
}
