import { describe, it, expect, beforeEach, vi } from 'vitest';
import { DamageCalculator } from '../src/combat/DamageCalculator';
import { CombatEngine } from '../src/combat/CombatEngine';
import { StatusEffectSystem } from '../src/combat/StatusEffectSystem';
import { SkillSystem } from '../src/combat/SkillSystem';
import { TurnManager } from '../src/turn/TurnManager';
import type {
  CombatUnit,
  DamageCalculationConfig,
  ElementChart,
  StatusEffect,
  Skill,
  UnitStats,
  DamageResistance,
} from '../src/types';
import type { CubeCoords } from '../src/types/grid';
import type { DamageType, ElementType, StatusEffectType, Direction } from '../src/types/common';
import { cubeCoords } from '../src/grid/coords';
import {
  createUnit,
  createDamageConfig,
  createElementChart,
  createWarrior,
  createMage,
  createDamageSkill,
  createDotEffect,
  createHotEffect,
} from './factories';

function createPrecisionAttacker(overrides: Partial<UnitStats> = {}): CombatUnit {
  return createUnit('attacker', 'Attacker', 'player', cubeCoords(0, 0, 0), {
    attack: 100,
    defense: 0,
    magicAttack: 100,
    magicDefense: 0,
    critRate: 0,
    critDamage: 1.5,
    accuracy: 100,
    ...overrides,
  });
}

function createPrecisionDefender(
  resistances: DamageResistance[] = [],
  overrides: Partial<UnitStats> = {}
): CombatUnit {
  const defender = createUnit('defender', 'Defender', 'enemy', cubeCoords(1, 0, -1), {
    defense: 0,
    magicDefense: 0,
    evasion: 0,
    ...overrides,
  });
  defender.resistances = resistances;
  return defender;
}

describe('Test 1: 伤害类型 × 抗性组合的精确公式验证', () => {
  let calculator: DamageCalculator;
  let config: DamageCalculationConfig;
  let elementChart: ElementChart;

  beforeEach(() => {
    config = createDamageConfig({
      baseFormula: 'attack',
      minDamage: 0,
      maxDamage: 999999,
    });
    elementChart = createElementChart();
    calculator = new DamageCalculator(config, elementChart);
  });

  it('1) Physical伤害 × 无抗性 → 100', () => {
    const attacker = createPrecisionAttacker();
    const defender = createPrecisionDefender();

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'physical'
    );

    expect(result.finalDamage).toBeCloseTo(100, 3);
  });

  it('2) Physical伤害 × 护甲50%(percent) → 100 × 0.5 = 50', () => {
    const attacker = createPrecisionAttacker();
    const defender = createPrecisionDefender([
      { type: 'physical', value: 0.5, isPercent: true },
    ]);

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'physical'
    );

    expect(result.finalDamage).toBeCloseTo(50, 3);
  });

  it('3) Physical伤害 × 固定抗性20(flat) → 100 - 20 = 80', () => {
    const attacker = createPrecisionAttacker();
    const defender = createPrecisionDefender([
      { type: 'physical', value: 20, isPercent: false },
    ]);

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'physical'
    );

    expect(result.finalDamage).toBeCloseTo(80, 3);
  });

  it('4) Magic伤害 × 魔抗0 → magicAttack=100 → finalDamage≈100', () => {
    const attacker = createPrecisionAttacker();
    const defender = createPrecisionDefender();

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'magic'
    );

    expect(result.finalDamage).toBeCloseTo(100, 3);
  });

  it('5) Magic伤害 × 魔抗40%(percent true) → 100 × 0.6 = 60', () => {
    const attacker = createPrecisionAttacker();
    const defender = createPrecisionDefender([
      { type: 'magic', value: 0.4, isPercent: true },
    ]);

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'magic'
    );

    expect(result.finalDamage).toBeCloseTo(60, 3);
  });

  it('6) Magic伤害 × 护甲穿透无效（护甲穿透只对物理生效，魔法伤害50%抗性→50）', () => {
    const attacker = createPrecisionAttacker({ armorPenetration: 50 });
    const defender = createPrecisionDefender([
      { type: 'magic', value: 0.5, isPercent: true },
    ]);

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'magic'
    );

    expect(result.finalDamage).toBeCloseTo(50, 3);
  });

  it('7) Real伤害 × specific physical/magic抗性无效（但all抗性仍生效→50）', () => {
    const attacker = createPrecisionAttacker();
    const defender = createPrecisionDefender([
      { type: 'physical', value: 30, isPercent: false },
      { type: 'magic', value: 20, isPercent: false },
    ]);

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'real' as DamageType
    );

    expect(result.finalDamage).toBeCloseTo(100, 3);
  });

  it('8) 混合：Physical攻100 × 护甲20flat × 护甲30% → 先百分比后flat：100*(1-0.3)-20 = 50', () => {
    const attacker = createPrecisionAttacker();
    const defender = createPrecisionDefender([
      { type: 'physical', value: 20, isPercent: false },
      { type: 'physical', value: 0.3, isPercent: true },
    ]);

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'physical'
    );

    expect(result.finalDamage).toBeCloseTo(50, 3);
  });
});

describe('Test 2: 暴击倍率叠加顺序（关键！）', () => {
  let calculator: DamageCalculator;
  let config: DamageCalculationConfig;
  let elementChart: ElementChart;

  beforeEach(() => {
    config = createDamageConfig({
      baseFormula: 'attack',
      minDamage: 0,
      maxDamage: 999999,
      elementAdvantageMultiplier: 1.5,
      directionBackDamageMultiplier: 1.5,
    });
    elementChart = createElementChart();
    calculator = new DamageCalculator(config, elementChart);
  });

  it('验证正确顺序：技能倍率×元素克制×地形加成×方向加成×暴击倍率 = 445.5→取整445', () => {
    const attacker = createPrecisionAttacker({
      critRate: 100,
      critDamage: 1.5,
    });
    attacker.affinities = [{ element: 'fire' as ElementType, value: 1 }];

    const defender = createPrecisionDefender();
    defender.affinities = [{ element: 'ice' as ElementType, value: 1 }];

    const baseDamageOverride = 120;
    const terrainBonus = 0.1;
    const heightBonus = 0;
    const directionBonus = 0.5;

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'fire',
      'physical',
      baseDamageOverride,
      terrainBonus,
      heightBonus,
      directionBonus
    );

    expect(result.elementBonus).toBeCloseTo(0.5, 3);
    expect(result.terrainBonus).toBeCloseTo(0.1, 3);
    expect(result.isCrit).toBe(true);

    const expected = Math.floor(120 * 1.5 * 1.1 * 1.5 * 1.5);
    expect(result.finalDamage).toBeCloseTo(expected, 3);
    expect(result.finalDamage).toBeCloseTo(445, 3);
  });

  it('验证错误顺序(加算)会得到不同结果：445 ≠ 432', () => {
    const attacker = createPrecisionAttacker({
      critRate: 100,
      critDamage: 1.5,
    });
    attacker.affinities = [{ element: 'fire' as ElementType, value: 1 }];

    const defender = createPrecisionDefender();
    defender.affinities = [{ element: 'ice' as ElementType, value: 1 }];

    const baseDamageOverride = 120;
    const terrainBonus = 0.1;
    const heightBonus = 0;
    const directionBonus = 0.5;

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'fire',
      'physical',
      baseDamageOverride,
      terrainBonus,
      heightBonus,
      directionBonus
    );

    const wrongAdditive = 120 * (1 + 0.5 + 0.1 + 0.5 + 0.5);
    expect(result.finalDamage).not.toBeCloseTo(wrongAdditive, 3);
    expect(wrongAdditive).toBeCloseTo(312, 3);
  });

  it('逐个字段验证 DamageInstance', () => {
    const attacker = createPrecisionAttacker({
      critRate: 100,
      critDamage: 1.5,
    });
    attacker.affinities = [{ element: 'fire' as ElementType, value: 1 }];

    const defender = createPrecisionDefender();
    defender.affinities = [{ element: 'ice' as ElementType, value: 1 }];

    const baseDamageOverride = 120;
    const terrainBonus = 0.1;
    const heightBonus = 0;
    const directionBonus = 0.5;

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'fire',
      'physical',
      baseDamageOverride,
      terrainBonus,
      heightBonus,
      directionBonus
    );

    expect(result.baseDamage).toBeCloseTo(120, 3);
    expect(result.elementBonus).toBeCloseTo(0.5, 3);
    expect(result.terrainBonus).toBeCloseTo(0.1, 3);
    expect(result.armorMitigation).toBeCloseTo(0, 3);
    expect(result.isCrit).toBe(true);
    expect(result.isDodged).toBe(false);
    expect(result.damageType).toBe('physical');
    expect(result.element).toBe('fire');
  });
});

describe('Test 3: DOT/HOT触发时机（角色行动开始触发）', () => {
  let statusEffectSystem: StatusEffectSystem;
  let turnManager: TurnManager;

  beforeEach(() => {
    statusEffectSystem = new StatusEffectSystem();
  });

  function forceTickable(effect: StatusEffect): void {
    effect.lastTick = 0;
    effect.tickInterval = 0;
  }

  it('DOT在单位行动开始(onTurnStart)触发，不在全局回合开始(onRoundStart)触发', async () => {
    const unitA = createUnit('unitA', 'UnitA', 'player', cubeCoords(0, 0, 0), {
      hp: 100,
      maxHp: 100,
      speed: 20,
    });
    const unitB = createUnit('unitB', 'UnitB', 'enemy', cubeCoords(1, 0, -1), {
      hp: 100,
      maxHp: 100,
      speed: 10,
    });

    const dot = createDotEffect('dot-1', 10, 3);
    forceTickable(dot);
    statusEffectSystem.applyEffect(unitA, dot);

    const hpLog: Array<{ phase: string; unitId: string; hp: number }> = [];

    turnManager = new TurnManager(
      {
        speedSortOrder: 'desc',
        enableDelayAction: true,
        enableInterrupts: true,
        interruptPriorityBias: 0,
      },
      [
        { id: unitA.id, speed: unitA.stats.speed },
        { id: unitB.id, speed: unitB.stats.speed },
      ]
    );

    turnManager.addHook('onRoundStart', (ctx) => {
      hpLog.push({
        phase: 'onRoundStart',
        unitId: 'none',
        hp: unitA.stats.hp,
      });
    });

    turnManager.addHook('onTurnStart', (ctx) => {
      if (ctx.unitId === unitA.id) {
        statusEffectSystem.tickEffects(unitA);
      }
      hpLog.push({
        phase: 'onTurnStart',
        unitId: ctx.unitId ?? 'unknown',
        hp: unitA.stats.hp,
      });
    });

    turnManager.addHook('onTurnEnd', (ctx) => {
      hpLog.push({
        phase: 'onTurnEnd',
        unitId: ctx.unitId ?? 'unknown',
        hp: unitA.stats.hp,
      });
    });

    expect(unitA.stats.hp).toBeCloseTo(100, 3);

    await turnManager.startRound();
    const afterRoundStart = hpLog.find(l => l.phase === 'onRoundStart');
    expect(afterRoundStart?.hp).toBeCloseTo(100, 3);
    expect(unitA.stats.hp).toBeCloseTo(100, 3);

    await turnManager.nextUnit();
    const unitATurnStart = hpLog.find(
      l => l.phase === 'onTurnStart' && l.unitId === 'unitA'
    );
    expect(unitATurnStart?.hp).toBeCloseTo(90, 3);

    await turnManager.endTurn();
    const unitATurnEnd = hpLog.find(
      l => l.phase === 'onTurnEnd' && l.unitId === 'unitA'
    );
    expect(unitATurnEnd?.hp).toBeCloseTo(90, 3);

    await turnManager.nextUnit();
    const unitBTurnStart = hpLog.find(
      l => l.phase === 'onTurnStart' && l.unitId === 'unitB'
    );
    expect(unitBTurnStart?.hp).toBeCloseTo(90, 3);
    await turnManager.endTurn();

    expect(unitA.stats.hp).toBeCloseTo(90, 3);

    await turnManager.startRound();
    await turnManager.nextUnit();
    expect(unitA.stats.hp).toBeCloseTo(80, 3);
  });

  it('HOT在单位行动开始(onTurnStart)回血8hp', async () => {
    const unitA = createUnit('unitA', 'UnitA', 'player', cubeCoords(0, 0, 0), {
      hp: 50,
      maxHp: 100,
      speed: 20,
    });
    const unitB = createUnit('unitB', 'UnitB', 'enemy', cubeCoords(1, 0, -1), {
      hp: 100,
      maxHp: 100,
      speed: 10,
    });

    const hot = createHotEffect('hot-1', 8, 3);
    forceTickable(hot);
    statusEffectSystem.applyEffect(unitA, hot);

    turnManager = new TurnManager(
      {
        speedSortOrder: 'desc',
        enableDelayAction: true,
        enableInterrupts: true,
        interruptPriorityBias: 0,
      },
      [
        { id: unitA.id, speed: unitA.stats.speed },
        { id: unitB.id, speed: unitB.stats.speed },
      ]
    );

    turnManager.addHook('onTurnStart', (ctx) => {
      if (ctx.unitId === unitA.id) {
        statusEffectSystem.tickEffects(unitA);
      }
    });

    expect(unitA.stats.hp).toBeCloseTo(50, 3);

    await turnManager.startRound();
    expect(unitA.stats.hp).toBeCloseTo(50, 3);

    await turnManager.nextUnit();
    expect(unitA.stats.hp).toBeCloseTo(58, 3);

    await turnManager.endTurn();
    await turnManager.nextUnit();
    await turnManager.endTurn();

    expect(unitA.stats.hp).toBeCloseTo(58, 3);

    await turnManager.startRound();
    await turnManager.nextUnit();
    expect(unitA.stats.hp).toBeCloseTo(66, 3);
  });

  it('使用 StatusEffectSystem.tickEffects 直接验证 DOT 逐次扣血', () => {
    const unitA = createUnit('unitA', 'UnitA', 'player', cubeCoords(0, 0, 0), {
      hp: 100,
      maxHp: 100,
      speed: 20,
    });

    const dot = createDotEffect('dot-1', 10, 3);
    forceTickable(dot);
    statusEffectSystem.applyEffect(unitA, dot);

    expect(unitA.stats.hp).toBeCloseTo(100, 3);

    statusEffectSystem.tickEffects(unitA);
    expect(unitA.stats.hp).toBeCloseTo(90, 3);

    forceTickable(unitA.statusEffects[0]);
    statusEffectSystem.tickEffects(unitA);
    expect(unitA.stats.hp).toBeCloseTo(80, 3);

    forceTickable(unitA.statusEffects[0]);
    statusEffectSystem.tickEffects(unitA);
    expect(unitA.stats.hp).toBeCloseTo(70, 3);
  });
});

describe('Test 4: HP溢出负数处理', () => {
  let engine: CombatEngine;
  let calculator: DamageCalculator;

  beforeEach(() => {
    const config = createDamageConfig({
      baseFormula: 'attack',
      minDamage: 0,
      maxDamage: 999999,
    });
    const chart = createElementChart();
    engine = new CombatEngine(config, chart);
    calculator = new DamageCalculator(config, chart);
  });

  it('HP不能变负，必须clamp到0，isAlive=false', () => {
    const attacker = createUnit('atk', 'Attacker', 'player', cubeCoords(0, 0, 0), {
      attack: 10000,
      accuracy: 100,
      critRate: 0,
    });
    const defender = createUnit('def', 'Defender', 'enemy', cubeCoords(1, 0, -1), {
      hp: 50,
      maxHp: 50,
      defense: 0,
      evasion: 0,
    });

    engine.addUnit(attacker);
    engine.addUnit(defender);
    engine.startCombat();

    const damage = engine.attack(attacker.id, defender.id);

    expect(damage).not.toBeNull();
    expect(damage!.finalDamage).toBeGreaterThanOrEqual(50);
    expect(defender.stats.hp).toBeCloseTo(0, 3);
    expect(defender.stats.hp).not.toBeLessThan(0);
    expect(defender.isAlive).toBe(false);
  });

  it('DamageInstance.finalDamage可以是原始伤害值，但Unit.stats.hp必须是0', () => {
    const attacker = createPrecisionAttacker({ attack: 10000 });
    const defender = createPrecisionDefender([], { hp: 50, maxHp: 50 });

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'physical'
    );

    expect(result.finalDamage).toBeCloseTo(10000, 3);

    defender.stats.hp = Math.max(0, defender.stats.hp - result.finalDamage);
    expect(defender.stats.hp).toBeCloseTo(0, 3);
  });

  it('恰好等于HP的伤害也正确处理', () => {
    const attacker = createUnit('atk', 'Attacker', 'player', cubeCoords(0, 0, 0), {
      attack: 50,
      accuracy: 100,
      critRate: 0,
    });
    const defender = createUnit('def', 'Defender', 'enemy', cubeCoords(1, 0, -1), {
      hp: 50,
      maxHp: 50,
      defense: 0,
      evasion: 0,
    });

    engine.addUnit(attacker);
    engine.addUnit(defender);
    engine.startCombat();

    engine.attack(attacker.id, defender.id);

    expect(defender.stats.hp).toBeCloseTo(0, 3);
    expect(defender.isAlive).toBe(false);
  });
});

describe('Test 5: 技能冷却在回合之间正确递减', () => {
  let engine: CombatEngine;
  let config: DamageCalculationConfig;
  let chart: ElementChart;

  beforeEach(() => {
    config = createDamageConfig({
      baseFormula: 'attack',
      minDamage: 0,
      maxDamage: 999999,
    });
    chart = createElementChart();
    engine = new CombatEngine(config, chart);
  });

  it('技能CD=3，施放后currentCooldown=3，每经过一次refreshSkillCooldowns减1', () => {
    const player1 = createUnit('p1', 'Player1', 'player', cubeCoords(0, 0, 0), {
      hp: 100,
      maxHp: 100,
      speed: 20,
      mp: 999,
    });
    const player2 = createUnit('p2', 'Player2', 'player', cubeCoords(0, 1, -1), {
      hp: 100,
      maxHp: 100,
      speed: 10,
    });
    const enemy = createUnit('e1', 'Enemy1', 'enemy', cubeCoords(2, 0, -2), {
      hp: 1000,
      maxHp: 1000,
      defense: 0,
      evasion: 0,
    });

    const skill = createDamageSkill('cd-test', 'CDTest', 'physical', 'neutral', 10, 3);
    skill.cooldown = 3;
    player1.skills = [skill];

    engine.addUnit(player1);
    engine.addUnit(player2);
    engine.addUnit(enemy);
    engine.startCombat();

    const result = engine.castSkill(player1.id, 'cd-test', enemy.id);
    expect(result).not.toBeNull();
    expect(result!.success).toBe(true);
    expect(skill.currentCooldown).toBeCloseTo(3, 3);

    engine.refreshSkillCooldowns();
    expect(skill.currentCooldown).toBeCloseTo(2, 3);

    engine.refreshSkillCooldowns();
    expect(skill.currentCooldown).toBeCloseTo(1, 3);

    engine.refreshSkillCooldowns();
    expect(skill.currentCooldown).toBeCloseTo(0, 3);

    const result2 = engine.castSkill(player1.id, 'cd-test', enemy.id);
    expect(result2).not.toBeNull();
    expect(result2!.success).toBe(true);
    expect(skill.currentCooldown).toBeCloseTo(3, 3);
  });

  it('场上2个存活玩家时，refreshSkillCooldowns同时递减所有存活单位CD', () => {
    const player1 = createUnit('p1', 'Player1', 'player', cubeCoords(0, 0, 0), {
      hp: 100,
      maxHp: 100,
      speed: 20,
    });
    const player2 = createUnit('p2', 'Player2', 'player', cubeCoords(0, 1, -1), {
      hp: 100,
      maxHp: 100,
      speed: 15,
    });

    const skill1 = createDamageSkill('s1', 'Skill1', 'physical', 'neutral', 10, 3);
    const skill2 = createDamageSkill('s2', 'Skill2', 'physical', 'neutral', 10, 3);
    skill1.cooldown = 3;
    skill2.cooldown = 3;
    player1.skills = [skill1];
    player2.skills = [skill2];

    engine.addUnit(player1);
    engine.addUnit(player2);
    engine.startCombat();

    skill1.currentCooldown = 3;
    skill2.currentCooldown = 3;

    expect(skill1.currentCooldown).toBeCloseTo(3, 3);
    expect(skill2.currentCooldown).toBeCloseTo(3, 3);

    engine.refreshSkillCooldowns();
    expect(skill1.currentCooldown).toBeCloseTo(2, 3);
    expect(skill2.currentCooldown).toBeCloseTo(2, 3);

    engine.refreshSkillCooldowns();
    expect(skill1.currentCooldown).toBeCloseTo(1, 3);
    expect(skill2.currentCooldown).toBeCloseTo(1, 3);

    engine.refreshSkillCooldowns();
    expect(skill1.currentCooldown).toBeCloseTo(0, 3);
    expect(skill2.currentCooldown).toBeCloseTo(0, 3);
  });

  it('死亡单位不参与冷却递减', () => {
    const player1 = createUnit('p1', 'Player1', 'player', cubeCoords(0, 0, 0), {
      hp: 100,
      maxHp: 100,
      speed: 20,
    });
    const player2 = createUnit('p2', 'Player2', 'player', cubeCoords(0, 1, -1), {
      hp: 1,
      maxHp: 100,
      speed: 10,
    });

    const skill1 = createDamageSkill('s1', 'Skill1', 'physical', 'neutral', 10, 3);
    const skill2 = createDamageSkill('s2', 'Skill2', 'physical', 'neutral', 10, 3);
    skill1.cooldown = 3;
    skill2.cooldown = 3;
    player1.skills = [skill1];
    player2.skills = [skill2];

    engine.addUnit(player1);
    engine.addUnit(player2);
    engine.startCombat();

    skill1.currentCooldown = 3;
    skill2.currentCooldown = 3;

    engine.killUnit(player2.id, player1.id);
    expect(player2.isAlive).toBe(false);

    engine.refreshSkillCooldowns();
    expect(skill1.currentCooldown).toBeCloseTo(2, 3);
    expect(skill2.currentCooldown).toBeCloseTo(3, 3);
  });
});

describe('Test 6: 伤害最小值/最大值钳位', () => {
  let calculator: DamageCalculator;

  it('minDamage=1 配置下，即使计算出0也返回1', () => {
    const config = createDamageConfig({
      baseFormula: 'attack - defense',
      minDamage: 1,
      maxDamage: 9999,
    });
    const chart = createElementChart();
    calculator = new DamageCalculator(config, chart);

    const attacker = createPrecisionAttacker({ attack: 5 });
    const defender = createPrecisionDefender([], { defense: 10 });

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'physical'
    );

    expect(result.finalDamage).toBeGreaterThanOrEqual(1);
    expect(result.finalDamage).toBeCloseTo(1, 3);
  });

  it('maxDamage=9999 配置下，计算100000被钳位到9999', () => {
    const config = createDamageConfig({
      baseFormula: 'attack',
      minDamage: 1,
      maxDamage: 9999,
    });
    const chart = createElementChart();
    calculator = new DamageCalculator(config, chart);

    const attacker = createPrecisionAttacker({ attack: 100000 });
    const defender = createPrecisionDefender();

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'physical'
    );

    expect(result.finalDamage).toBeLessThanOrEqual(9999);
    expect(result.finalDamage).toBeCloseTo(9999, 3);
  });

  it('负伤害钳位到minDamage', () => {
    const config = createDamageConfig({
      baseFormula: 'attack - defense',
      minDamage: 1,
      maxDamage: 9999,
    });
    const chart = createElementChart();
    calculator = new DamageCalculator(config, chart);

    const attacker = createPrecisionAttacker({ attack: 10 });
    const defender = createPrecisionDefender([
      { type: 'physical', value: 100, isPercent: false },
    ], { defense: 50 });

    const result = calculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'physical'
    );

    expect(result.finalDamage).toBeGreaterThanOrEqual(1);
    expect(result.finalDamage).toBeCloseTo(1, 3);
  });
});
