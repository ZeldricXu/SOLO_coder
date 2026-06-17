import { describe, it, expect, beforeEach } from 'vitest';
import { DamageCalculator } from '../src/combat/DamageCalculator';
import { StatusEffectSystem } from '../src/combat/StatusEffectSystem';
import { SkillSystem } from '../src/combat/SkillSystem';
import { CombatEngine } from '../src/combat/CombatEngine';
import type {
  CombatUnit,
  DamageCalculationConfig,
  ElementChart,
  StatusEffect,
  Skill,
  UnitStats,
  UnitAttributes,
  PassiveSkill,
} from '../src/types';
import type { CubeCoords } from '../src/types/grid';
import type { DamageType, ElementType, StatusEffectType, Direction } from '../src/types/common';
import { cubeCoords } from '../src/grid/coords';

function createMockUnit(id: string, faction: string, coords: CubeCoords, statsOverrides: Partial<UnitStats> = {}): CombatUnit {
  const baseStats: UnitStats = {
    maxHp: 100,
    hp: 100,
    maxMp: 50,
    mp: 50,
    attack: 20,
    defense: 10,
    magicAttack: 15,
    magicDefense: 8,
    speed: 10,
    accuracy: 90,
    evasion: 10,
    critRate: 15,
    critDamage: 150,
    armorPenetration: 0,
    moveRange: 3,
    attackRange: 1,
    visionRange: 5,
    height: 0,
  };

  const finalStats = { ...baseStats, ...statsOverrides };

  const createAttribute = (base: number) => ({
    base,
    modifiers: [],
    current: base,
  });

  const attributes: UnitAttributes = {
    hp: { current: finalStats.hp, max: finalStats.maxHp, min: 0 },
    mp: { current: finalStats.mp, max: finalStats.maxMp, min: 0 },
    attack: createAttribute(finalStats.attack),
    defense: createAttribute(finalStats.defense),
    magicAttack: createAttribute(finalStats.magicAttack),
    magicDefense: createAttribute(finalStats.magicDefense),
    speed: createAttribute(finalStats.speed),
    accuracy: createAttribute(finalStats.accuracy),
    evasion: createAttribute(finalStats.evasion),
    critRate: createAttribute(finalStats.critRate),
    critDamage: createAttribute(finalStats.critDamage),
    armorPenetration: createAttribute(finalStats.armorPenetration),
    moveRange: createAttribute(finalStats.moveRange),
    attackRange: createAttribute(finalStats.attackRange),
    visionRange: createAttribute(finalStats.visionRange),
  };

  return {
    id,
    name: `Unit ${id}`,
    faction,
    templateId: 'warrior',
    coords,
    direction: 0 as Direction,
    stats: finalStats,
    attributes,
    skills: [],
    passiveSkills: [] as PassiveSkill[],
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

const DEFAULT_ELEMENT_CHART: ElementChart = {
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

describe('DamageCalculator - 伤害计算器', () => {
  let calculator: DamageCalculator;
  let attacker: CombatUnit;
  let defender: CombatUnit;

  beforeEach(() => {
    calculator = new DamageCalculator(DEFAULT_DAMAGE_CONFIG, DEFAULT_ELEMENT_CHART);
    attacker = createMockUnit('attacker', 'player', cubeCoords(0, 0, 0), { attack: 30, accuracy: 100, critRate: 0 });
    defender = createMockUnit('defender', 'enemy', cubeCoords(1, 0, -1), { defense: 10, evasion: 0 });
  });

  it('基础伤害计算', () => {
    const damage = calculator.calculateDamage(attacker, defender);
    expect(damage.baseDamage).toBeGreaterThan(0);
    expect(damage.finalDamage).toBeGreaterThanOrEqual(1);
    expect(damage.isCrit).toBe(false);
    expect(damage.isDodged).toBe(false);
  });

  it('护甲穿透减伤', () => {
    const normalDamage = calculator.calculateDamage(attacker, defender);
    
    const penAttacker = createMockUnit('pen-attacker', 'player', cubeCoords(0, 0, 0), {
      attack: 30,
      armorPenetration: 10,
      accuracy: 100,
      critRate: 0,
    });
    const penDamage = calculator.calculateDamage(penAttacker, defender);
    
    expect(penDamage.finalDamage).toBeGreaterThanOrEqual(normalDamage.finalDamage);
  });

  it('暴击伤害', () => {
    const critAttacker = createMockUnit('crit-attacker', 'player', cubeCoords(0, 0, 0), {
      attack: 30,
      critRate: 100,
      accuracy: 100,
      critDamage: 200,
    });
    
    const damage = calculator.calculateDamage(critAttacker, defender);
    expect(damage.isCrit).toBe(true);
  });

  it('属性克制加成 - 优势', () => {
    const fireAttacker = createMockUnit('fire-atk', 'player', cubeCoords(0, 0, 0));
    const iceDefender = createMockUnit('ice-def', 'enemy', cubeCoords(1, 0, -1));
    iceDefender.affinities = [{ element: 'ice' as ElementType, value: 1 }];

    const neutralDamage = calculator.calculateDamage(fireAttacker, iceDefender, 'neutral', 'physical');
    const fireDamage = calculator.calculateDamage(fireAttacker, iceDefender, 'fire' as ElementType, 'magic');

    expect(fireDamage.elementBonus).toBeGreaterThan(0);
  });

  it('属性克制加成 - 劣势', () => {
    const fireAttacker = createMockUnit('fire-atk', 'player', cubeCoords(0, 0, 0));
    const waterDefender = createMockUnit('water-def', 'enemy', cubeCoords(1, 0, -1));
    waterDefender.affinities = [{ element: 'water' as ElementType, value: 1 }];

    const fireDamage = calculator.calculateDamage(fireAttacker, waterDefender, 'fire' as ElementType, 'magic');
    expect(fireDamage.elementBonus).toBeLessThan(0);
  });

  it('地形防御加成', () => {
    const { terrainBonus, heightBonus } = calculator.applyTerrainAndHeightBonus(
      attacker.coords,
      defender.coords,
      0,
      0.3,
      0,
      0
    );
    expect(terrainBonus).toBeLessThan(0);
  });

  it('高度加成', () => {
    const { terrainBonus, heightBonus } = calculator.applyTerrainAndHeightBonus(
      attacker.coords,
      defender.coords,
      0,
      0,
      3,
      0
    );
    expect(heightBonus).toBeGreaterThan(0);
  });

  it('必中必闪机制', () => {
    const guaranteedHitAttacker = createMockUnit('hit', 'player', cubeCoords(0, 0, 0));
    guaranteedHitAttacker.tags = ['guaranteed_hit'];
    const hitResult = calculator.calculateHit(guaranteedHitAttacker, defender);
    expect(hitResult.isGuaranteedHit).toBe(true);
    expect(hitResult.hit).toBe(true);

    const guaranteedMissDefender = createMockUnit('miss', 'enemy', cubeCoords(1, 0, -1));
    guaranteedMissDefender.tags = ['guaranteed_miss'];
    const missResult = calculator.calculateHit(attacker, guaranteedMissDefender);
    expect(missResult.isGuaranteedMiss).toBe(true);
    expect(missResult.hit).toBe(false);
  });

  it('魔法伤害计算', () => {
    const magicAttacker = createMockUnit('mage', 'player', cubeCoords(0, 0, 0), { magicAttack: 40 });
    const magicDamage = calculator.calculateDamage(magicAttacker, defender, 'neutral', 'magic');
    expect(magicDamage.baseDamage).toBeGreaterThan(0);
    expect(magicDamage.damageType).toBe('magic');
  });
});

describe('StatusEffectSystem - 状态效果系统', () => {
  let system: StatusEffectSystem;
  let unit: CombatUnit;

  beforeEach(() => {
    system = new StatusEffectSystem();
    unit = createMockUnit('test-unit', 'player', cubeCoords(0, 0, 0));
  });

  it('applyEffect 应用效果', () => {
    const effect: StatusEffect = {
      id: 'buff-1',
      type: 'buff' as StatusEffectType,
      name: '力量祝福',
      description: '增加攻击力',
      duration: 3,
      maxDuration: 3,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 3,
      source: 'caster',
      isDebuff: false,
      effects: [{ stat: 'attack', value: 10, modifierType: 'add' }],
    };

    const result = system.applyEffect(unit, effect);
    expect(result).not.toBeNull();
    expect(unit.statusEffects.length).toBe(1);
    expect(unit.stats.attack).toBe(30);
  });

  it('removeEffect 移除效果', () => {
    const effect: StatusEffect = {
      id: 'buff-1',
      type: 'buff' as StatusEffectType,
      name: '力量祝福',
      description: '增加攻击力',
      duration: 3,
      maxDuration: 3,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 3,
      source: 'caster',
      isDebuff: false,
      effects: [{ stat: 'attack', value: 10, modifierType: 'add' }],
    };

    system.applyEffect(unit, effect);
    const oldAttack = unit.stats.attack;

    const removed = system.removeEffect(unit, 'buff-1');
    expect(removed).not.toBeNull();
    expect(unit.statusEffects.length).toBe(0);
    expect(unit.stats.attack).toBeLessThan(oldAttack);
  });

  it('tick效果 - DOT伤害', () => {
    const poison: StatusEffect = {
      id: 'poison-1',
      type: 'poison' as StatusEffectType,
      name: '中毒',
      description: '每回合受到伤害',
      duration: 3,
      maxDuration: 3,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 5,
      source: 'enemy',
      isDebuff: true,
      effects: [{ value: 10, modifierType: 'add', damageType: 'poison' as DamageType }],
    };

    system.applyEffect(unit, poison);
    const initialHp = unit.stats.hp;

    const results = system.tickEffects(unit);
    expect(results.length).toBeGreaterThan(0);
    expect(unit.stats.hp).toBeLessThan(initialHp);
    expect(poison.duration).toBe(2);
  });

  it('tick效果 - HOT治疗', () => {
    unit.stats.hp = 50;
    const regen: StatusEffect = {
      id: 'regen-1',
      type: 'regen' as StatusEffectType,
      name: '生命回复',
      description: '每回合恢复生命',
      duration: 3,
      maxDuration: 3,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 3,
      source: 'healer',
      isDebuff: false,
      effects: [{ value: 20, modifierType: 'add' }],
    };

    system.applyEffect(unit, regen);
    const initialHp = unit.stats.hp;

    const results = system.tickEffects(unit);
    expect(results.length).toBeGreaterThan(0);
    expect(unit.stats.hp).toBeGreaterThan(initialHp);
  });

  it('叠加层数', () => {
    const createEffect = (): StatusEffect => ({
      id: `stack-${Date.now()}-${Math.random()}`,
      type: 'buff' as StatusEffectType,
      name: '叠加效果',
      description: '可叠加',
      duration: 3,
      maxDuration: 3,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 3,
      source: 'source',
      isDebuff: false,
      effects: [{ stat: 'attack', value: 5, modifierType: 'add' }],
    });

    const effect1 = createEffect();
    effect1.id = 'stack-1';
    system.applyEffect(unit, effect1);
    const attackAfter1 = unit.stats.attack;

    const effect2 = createEffect();
    effect2.id = 'stack-2';
    effect2.source = 'source';
    effect2.type = effect1.type;
    system.applyEffect(unit, effect2);

    expect(unit.statusEffects.length).toBe(1);
    expect(unit.statusEffects[0].stackCount).toBe(2);
  });

  it('属性修改生效/还原', () => {
    const slow: StatusEffect = {
      id: 'slow-1',
      type: 'slow' as StatusEffectType,
      name: '减速',
      description: '降低速度',
      duration: 2,
      maxDuration: 2,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 3,
      source: 'enemy',
      isDebuff: true,
      effects: [{ stat: 'speed', value: -5, modifierType: 'add' }],
    };

    const initialSpeed = unit.stats.speed;
    system.applyEffect(unit, slow);
    expect(unit.stats.speed).toBe(initialSpeed - 5);

    system.removeEffect(unit, 'slow-1');
    expect(unit.stats.speed).toBe(initialSpeed);
  });

  it('免疫状态效果', () => {
    unit.tags = ['immune_status_all'];
    const effect: StatusEffect = {
      id: 'test',
      type: 'buff' as StatusEffectType,
      name: '测试',
      description: '',
      duration: 1,
      maxDuration: 1,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 1,
      source: 'test',
      isDebuff: false,
      effects: [],
    };

    const result = system.applyEffect(unit, effect);
    expect(result).toBeNull();
    expect(unit.statusEffects.length).toBe(0);
  });
});

describe('SkillSystem - 技能系统', () => {
  let damageCalculator: DamageCalculator;
  let statusEffectSystem: StatusEffectSystem;
  let skillSystem: SkillSystem;
  let statusEffectTemplates: Map<string, StatusEffect>;
  let caster: CombatUnit;
  let target: CombatUnit;

  beforeEach(() => {
    damageCalculator = new DamageCalculator(DEFAULT_DAMAGE_CONFIG, DEFAULT_ELEMENT_CHART);
    statusEffectSystem = new StatusEffectSystem();
    statusEffectTemplates = new Map();
    
    const burnTemplate: StatusEffect = {
      id: 'burn-template',
      type: 'burn' as StatusEffectType,
      name: '燃烧',
      description: '持续燃烧伤害',
      duration: 3,
      maxDuration: 3,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 5,
      source: 'template',
      isDebuff: true,
      effects: [{ value: 8, modifierType: 'add', damageType: 'fire' as DamageType }],
    };
    statusEffectTemplates.set('burn', burnTemplate);

    const healBuffTemplate: StatusEffect = {
      id: 'regen-template',
      type: 'regen' as StatusEffectType,
      name: '治疗增益',
      description: '持续恢复',
      duration: 3,
      maxDuration: 3,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 3,
      source: 'template',
      isDebuff: false,
      effects: [{ value: 15, modifierType: 'add' }],
    };
    statusEffectTemplates.set('regen', healBuffTemplate);

    skillSystem = new SkillSystem(damageCalculator, statusEffectSystem, statusEffectTemplates);

    caster = createMockUnit('caster', 'player', cubeCoords(0, 0, 0), { attack: 35, accuracy: 100, critRate: 0 });
    target = createMockUnit('target', 'enemy', cubeCoords(1, 0, -1), { defense: 5, evasion: 0 });

    const units = new Map();
    units.set(caster.id, caster);
    units.set(target.id, target);
    skillSystem.setUnits(units);
  });

  it('executeSkill 伤害技能', () => {
    const damageSkill: Skill = {
      id: 'slash',
      name: '斩击',
      description: '基础物理攻击',
      type: 'active',
      targetType: 'enemy',
      range: { min: 0, max: 2, type: 'any', requiresLineOfSight: false },
      effects: [{ type: 'damage', target: 'single', value: 30, damageType: 'physical' }],
      cooldown: 0,
      currentCooldown: 0,
      mpCost: 0,
      hpCost: 0,
      castTime: 0,
      isDelayed: false,
      interruptPriority: 0,
      canTargetSelf: false,
      canTargetAlly: false,
      canTargetEnemy: true,
      canTargetTerrain: false,
      tags: ['physical'],
    };
    caster.skills = [damageSkill];

    const initialHp = target.stats.hp;
    const result = skillSystem.executeSkill(caster, damageSkill, target);

    expect(result.success).toBe(true);
    expect(result.damageInstances.length).toBe(1);
    expect(target.stats.hp).toBeLessThan(initialHp);
  });

  it('executeSkill 治疗技能', () => {
    target.stats.hp = 40;
    const healSkill: Skill = {
      id: 'heal',
      name: '治疗术',
      description: '恢复目标生命值',
      type: 'active',
      targetType: 'ally',
      range: { min: 0, max: 3, type: 'any', requiresLineOfSight: false },
      effects: [{ type: 'heal', target: 'single', value: 40 }],
      cooldown: 1,
      currentCooldown: 0,
      mpCost: 10,
      hpCost: 0,
      castTime: 0,
      isDelayed: false,
      interruptPriority: 0,
      canTargetSelf: true,
      canTargetAlly: true,
      canTargetEnemy: false,
      canTargetTerrain: false,
      tags: ['healing'],
    };
    caster.skills = [healSkill];

    const ally = createMockUnit('ally', 'player', cubeCoords(0, 1, -1));
    ally.stats.hp = 50;
    const units = new Map();
    units.set(caster.id, caster);
    units.set(ally.id, ally);
    units.set(target.id, target);
    skillSystem.setUnits(units);

    const initialHp = ally.stats.hp;
    const result = skillSystem.executeSkill(caster, healSkill, ally);

    expect(result.success).toBe(true);
    expect(result.healInstances.length).toBe(1);
    expect(ally.stats.hp).toBeGreaterThan(initialHp);
  });

  it('AOE目标获取', () => {
    const enemy2 = createMockUnit('enemy2', 'enemy', cubeCoords(2, 0, -2));
    const enemy3 = createMockUnit('enemy3', 'enemy', cubeCoords(1, 1, -2));

    const units = new Map();
    units.set(caster.id, caster);
    units.set(target.id, target);
    units.set(enemy2.id, enemy2);
    units.set(enemy3.id, enemy3);
    skillSystem.setUnits(units);

    const aoeSkill: Skill = {
      id: 'fireball',
      name: '火球术',
      description: 'AOE火焰伤害',
      type: 'active',
      targetType: 'area',
      range: { min: 1, max: 4, type: 'area', requiresLineOfSight: false },
      effects: [{ type: 'damage', target: 'area', value: 25, damageType: 'fire' as DamageType, element: 'fire' as ElementType, aoeRadius: 2 }],
      cooldown: 2,
      currentCooldown: 0,
      mpCost: 15,
      hpCost: 0,
      castTime: 0,
      isDelayed: false,
      interruptPriority: 0,
      canTargetSelf: false,
      canTargetAlly: false,
      canTargetEnemy: true,
      canTargetTerrain: true,
      tags: ['magic', 'fire'],
    };
    caster.skills = [aoeSkill];

    const targets = skillSystem.getSkillTargets(caster, aoeSkill, undefined, target.coords);
    expect(targets.length).toBeGreaterThanOrEqual(2);
  });

  it('validateTarget 校验目标', () => {
    const enemySkill: Skill = {
      id: 'enemy-only',
      name: '敌方技能',
      description: '',
      type: 'active',
      targetType: 'enemy',
      range: { min: 0, max: 3, type: 'any', requiresLineOfSight: false },
      effects: [{ type: 'damage', target: 'single', value: 10 }],
      cooldown: 0,
      currentCooldown: 0,
      mpCost: 0,
      hpCost: 0,
      castTime: 0,
      isDelayed: false,
      interruptPriority: 0,
      canTargetSelf: false,
      canTargetAlly: false,
      canTargetEnemy: true,
      canTargetTerrain: false,
      tags: [],
    };

    const ally = createMockUnit('ally', 'player', cubeCoords(0, 1, -1));

    const validateEnemy = skillSystem.validateTarget(caster, enemySkill, target);
    expect(validateEnemy).toBe(true);

    const validateAlly = skillSystem.validateTarget(caster, enemySkill, ally);
    expect(validateAlly).toBe(false);

    const validateSelf = skillSystem.validateTarget(caster, enemySkill, caster);
    expect(validateSelf).toBe(false);
  });

  it('光环应用', () => {
    const auraPassive: PassiveSkill = {
      id: 'leader-aura',
      name: '领袖光环',
      description: '增加附近友军攻击力',
      type: 'aura',
      targetType: 'allAlly',
      range: { min: 0, max: 0, type: 'any', requiresLineOfSight: false },
      effects: [{ type: 'buff', target: 'ally', value: 0, statusEffect: 'atk-buff' }],
      cooldown: 0,
      currentCooldown: 0,
      mpCost: 0,
      hpCost: 0,
      castTime: 0,
      isDelayed: false,
      interruptPriority: 0,
      canTargetSelf: true,
      canTargetAlly: true,
      canTargetEnemy: false,
      canTargetTerrain: false,
      tags: [],
      auraRadius: 2,
      isActive: true,
      appliedEffects: new Map(),
    };

    const atkBuff: StatusEffect = {
      id: 'atk-buff',
      type: 'buff' as StatusEffectType,
      name: '攻击增益',
      description: '攻击力+5',
      duration: 999,
      maxDuration: 999,
      tickInterval: 0,
      lastTick: 0,
      stackCount: 1,
      maxStacks: 1,
      source: 'aura',
      isDebuff: false,
      effects: [{ stat: 'attack', value: 5, modifierType: 'add' }],
    };
    statusEffectTemplates.set('atk-buff', atkBuff);

    caster.passiveSkills = [auraPassive];
    const ally = createMockUnit('ally', 'player', cubeCoords(1, 0, -1));
    const units = new Map();
    units.set(caster.id, caster);
    units.set(ally.id, ally);
    skillSystem.setUnits(units);

    const initialAttack = ally.stats.attack;
    skillSystem.applyAuras(caster);

    expect(ally.statusEffects.length).toBeGreaterThan(0);
    expect(ally.stats.attack).toBeGreaterThan(initialAttack);
  });

  it('被动触发器', () => {
    const counterPassive: PassiveSkill = {
      id: 'counter',
      name: '反击',
      description: '受伤时反击',
      type: 'reaction',
      targetType: 'single',
      range: { min: 0, max: 1, type: 'any', requiresLineOfSight: false },
      effects: [{ type: 'damage', target: 'enemy', value: 15, damageType: 'physical' }],
      cooldown: 0,
      currentCooldown: 0,
      mpCost: 0,
      hpCost: 0,
      castTime: 0,
      isDelayed: false,
      interruptPriority: 10,
      canTargetSelf: false,
      canTargetAlly: false,
      canTargetEnemy: true,
      canTargetTerrain: false,
      tags: ['reaction'],
      triggerConditions: [{ type: 'onDamageTaken', threshold: 1, targetFilter: 'self' }],
      isActive: true,
      appliedEffects: new Map(),
    };

    target.passiveSkills = [counterPassive];
    const initialCasterHp = caster.stats.hp;

    skillSystem.processPassiveTriggers(target, 'onDamageTaken', {
      value: 20,
      target: target,
      source: caster,
    });

    expect(caster.stats.hp).toBeLessThan(initialCasterHp);
  });

  it('技能消耗资源', () => {
    const expensiveSkill: Skill = {
      id: 'ultimate',
      name: '终极技能',
      description: '消耗大量资源',
      type: 'active',
      targetType: 'enemy',
      range: { min: 0, max: 5, type: 'any', requiresLineOfSight: false },
      effects: [{ type: 'damage', target: 'single', value: 100 }],
      cooldown: 5,
      currentCooldown: 0,
      mpCost: 40,
      hpCost: 10,
      castTime: 0,
      isDelayed: false,
      interruptPriority: 0,
      canTargetSelf: false,
      canTargetAlly: false,
      canTargetEnemy: true,
      canTargetTerrain: false,
      tags: ['ultimate'],
    };
    caster.skills = [expensiveSkill];
    caster.stats.mp = 20;

    const result = skillSystem.executeSkill(caster, expensiveSkill, target);
    expect(result.success).toBe(false);
    expect(result.message).toBe('Insufficient resources');
  });
});

describe('CombatEngine - 战斗引擎', () => {
  let engine: CombatEngine;
  let player1: CombatUnit;
  let player2: CombatUnit;
  let enemy1: CombatUnit;
  let enemy2: CombatUnit;

  beforeEach(() => {
    engine = new CombatEngine(DEFAULT_DAMAGE_CONFIG, DEFAULT_ELEMENT_CHART);
    
    player1 = createMockUnit('p1', 'player', cubeCoords(0, 0, 0), { attack: 35, hp: 100, maxHp: 100 });
    player2 = createMockUnit('p2', 'player', cubeCoords(0, 1, -1), { attack: 25, hp: 80, maxHp: 80 });
    enemy1 = createMockUnit('e1', 'enemy', cubeCoords(3, 0, -3), { attack: 30, hp: 90, maxHp: 90 });
    enemy2 = createMockUnit('e2', 'enemy', cubeCoords(3, 1, -4), { attack: 20, hp: 70, maxHp: 70 });

    engine.addUnit(player1);
    engine.addUnit(player2);
    engine.addUnit(enemy1);
    engine.addUnit(enemy2);
    engine.startCombat();
  });

  it('attack 完整攻击流程', () => {
    enemy1.coords = cubeCoords(1, 0, -1);

    const initialHp = enemy1.stats.hp;
    const damage = engine.attack(player1.id, enemy1.id);

    expect(damage).not.toBeNull();
    expect(damage!.finalDamage).toBeGreaterThan(0);
    expect(enemy1.stats.hp).toBeLessThan(initialHp);
    expect(enemy1.stats.hp).toBeGreaterThanOrEqual(0);
  });

  it('castSkill 施放技能', () => {
    enemy1.coords = cubeCoords(1, 0, -1);
    const damageSkill: Skill = {
      id: 'power-strike',
      name: '强力一击',
      description: '',
      type: 'active',
      targetType: 'enemy',
      range: { min: 0, max: 2, type: 'any', requiresLineOfSight: false },
      effects: [{ type: 'damage', target: 'single', value: 50, damageType: 'physical' }],
      cooldown: 2,
      currentCooldown: 0,
      mpCost: 5,
      hpCost: 0,
      castTime: 0,
      isDelayed: false,
      interruptPriority: 0,
      canTargetSelf: false,
      canTargetAlly: false,
      canTargetEnemy: true,
      canTargetTerrain: false,
      tags: [],
    };
    player1.skills = [damageSkill];

    const initialHp = enemy1.stats.hp;
    const result = engine.castSkill(player1.id, 'power-strike', enemy1.id);

    expect(result).not.toBeNull();
    expect(result!.success).toBe(true);
    expect(enemy1.stats.hp).toBeLessThan(initialHp);
    expect(damageSkill.currentCooldown).toBe(2);
  });

  it('延迟技能倒计时', () => {
    const delayedSkill: Skill = {
      id: 'big-bomb',
      name: '大炸弹',
      description: '需要蓄力',
      type: 'active',
      targetType: 'area',
      range: { min: 1, max: 4, type: 'area', requiresLineOfSight: false },
      effects: [{ type: 'damage', target: 'area', value: 80, damageType: 'explosive' as DamageType, aoeRadius: 2 }],
      cooldown: 3,
      currentCooldown: 0,
      mpCost: 20,
      hpCost: 0,
      castTime: 2,
      isDelayed: true,
      interruptPriority: 5,
      canTargetSelf: false,
      canTargetAlly: false,
      canTargetEnemy: true,
      canTargetTerrain: true,
      tags: [],
    };
    player1.skills = [delayedSkill];
    enemy1.coords = cubeCoords(1, 0, -1);

    engine.castSkill(player1.id, 'big-bomb', enemy1.id, enemy1.coords);
    expect(player1.isDelaying).toBe(true);
    expect(player1.castingSkill).toBeDefined();

    const initialEnemyHp = enemy1.stats.hp;
    const delayedSkills = engine.getDelayedSkills();
    for (let i = 0; i < 3; i++) {
      engine.processDelayedSkills();
    }

    expect(player1.isDelaying).toBe(false);
    expect(player1.castingSkill).toBeUndefined();
  });

  it('killUnit 击杀 + 复活检测', () => {
    enemy1.stats.hp = 1;
    const result = engine.killUnit(enemy1.id, player1.id);

    expect(result).not.toBeNull();
    expect(enemy1.isAlive).toBe(false);
    expect(enemy1.stats.hp).toBe(0);

    const aliveEnemies = engine.getUnitsByFaction('enemy');
    expect(aliveEnemies.length).toBe(1);
  });

  it('checkVictory 胜负判定', () => {
    expect(engine.checkVictory()).toBeNull();

    engine.killUnit(enemy1.id, player1.id);
    engine.killUnit(enemy2.id, player2.id);

    const winner = engine.checkVictory();
    expect(winner).toBe('player');
    expect(engine.getWinner()).toBe('player');
    expect(engine.isActive()).toBe(false);
  });

  it('技能冷却刷新', () => {
    const skill: Skill = {
      id: 'cd-test',
      name: '测试CD',
      description: '',
      type: 'active',
      targetType: 'enemy',
      range: { min: 0, max: 1, type: 'any', requiresLineOfSight: false },
      effects: [{ type: 'damage', target: 'single', value: 10 }],
      cooldown: 3,
      currentCooldown: 3,
      mpCost: 0,
      hpCost: 0,
      castTime: 0,
      isDelayed: false,
      interruptPriority: 0,
      canTargetSelf: false,
      canTargetAlly: false,
      canTargetEnemy: true,
      canTargetTerrain: false,
      tags: [],
    };
    player1.skills = [skill];

    expect(skill.currentCooldown).toBe(3);
    engine.refreshSkillCooldowns();
    expect(skill.currentCooldown).toBe(2);
  });

  it('获取存活单位', () => {
    const alive = engine.getAliveUnits();
    expect(alive.length).toBe(4);

    engine.killUnit(enemy1.id, player1.id);
    const aliveAfter = engine.getAliveUnits();
    expect(aliveAfter.length).toBe(3);
  });
});
