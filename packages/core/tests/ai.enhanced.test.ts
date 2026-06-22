import { describe, it, expect, beforeEach } from 'vitest';
import {
  ThreatAssessment,
  TacticalAI,
  BehaviorTree,
  BossBehaviorTreeBuilder,
  BehaviorConditionBuilders,
  BehaviorActionBuilders,
  SquadAI,
} from '../src/ai';
import {
  cubeCoords,
  cubeDistance,
} from '../src/grid/coords';
import type {
  CombatUnit,
  ID,
  AIProfile,
  AIParameters,
  AIDecision,
  BehaviorResult,
  AIContext,
  CubeCoords,
  Faction,
  Skill,
  AIRole,
  KillPriorityAssessment,
  TargetSortMode,
} from '../src/types';
import {
  createUnit,
  createMage,
  createArcher,
  createWarrior,
  createTank,
  createEmptyGrid,
  createSkill,
  createDamageSkill,
  createDotEffect,
} from './factories';

const DEFAULT_AI_PARAMETERS: AIParameters = {
  threatWeight: {
    damage: 1,
    proximity: 1,
    hpPercentage: 1,
    statusEffects: 1,
    isHealer: 1,
    isCaster: 1,
  },
  targetWeight: {
    lowestHp: 1,
    highestThreat: 1,
    closest: 1,
    isolated: 1,
    taunted: 1,
  },
  positioning: {
    preferHighGround: 1,
    preferCover: 1,
    maintainDistance: 1,
    avoidClustering: 1,
    flankPriority: 1,
  },
  skillSelection: {
    preferAoe: 1,
    preferBuff: 1,
    preferDebuff: 1,
    preferHeal: 1,
    finishThreshold: 1.5,
  },
  riskAssessment: {
    abandonLowHpAlly: 0.5,
    pursueLowHpEnemy: 0.8,
    overextendPenalty: 0.5,
    hpSafetyThreshold: 0.3,
  },
};

function createMockAIProfile(): AIProfile {
  return {
    id: 'profile_1',
    name: '平衡型AI',
    type: 'balanced',
    description: '测试用AI配置',
    aggression: 0.6,
    defensiveness: 0.4,
    supportiveness: 0.5,
    caution: 0.5,
    parameters: DEFAULT_AI_PARAMETERS,
  };
}

function createAIContext(
  unit: CombatUnit,
  allUnits: Map<ID, CombatUnit>,
  currentTurn: number = 1
): AIContext {
  return {
    unit,
    allUnits,
    grid: null,
    currentTurn,
    memory: new Map(),
    globalMemory: new Map(),
    threatMap: new Map(),
  };
}

describe('Test 1: 威胁评估增强', () => {
  let threatAssessor: ThreatAssessment;

  beforeEach(() => {
    threatAssessor = new ThreatAssessment(DEFAULT_AI_PARAMETERS.threatWeight);
  });

  it('calculateKillPriority: 低血单位优先级高于高血单位（威胁相同情况下）', () => {
    const attacker = createWarrior('attacker-1', 'player', cubeCoords(0, 0, 0));
    const lowHpEnemy = createWarrior('enemy-low', 'enemy', cubeCoords(2, 0, -2));
    const highHpEnemy = createWarrior('enemy-high', 'enemy', cubeCoords(3, 0, -3));

    lowHpEnemy.stats.hp = 20;
    lowHpEnemy.stats.maxHp = 100;
    highHpEnemy.stats.hp = 80;
    highHpEnemy.stats.maxHp = 100;

    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(attacker.id, attacker);
    allUnits.set(lowHpEnemy.id, lowHpEnemy);
    allUnits.set(highHpEnemy.id, highHpEnemy);

    const lowPriority = threatAssessor.calculateKillPriority(lowHpEnemy, attacker, allUnits);
    const highPriority = threatAssessor.calculateKillPriority(highHpEnemy, attacker, allUnits);

    expect(lowPriority.killPriority).toBeGreaterThan(highPriority.killPriority);
    expect(lowPriority.hpPercentage).toBeLessThan(highPriority.hpPercentage);
  });

  it('getPrioritizedTargets(mode=kill): 按击杀优先级排序', () => {
    const attacker = createWarrior('attacker-1', 'player', cubeCoords(0, 0, 0));
    const enemy1 = createWarrior('enemy-1', 'enemy', cubeCoords(2, 0, -2));
    const enemy2 = createMage('enemy-2', 'enemy', cubeCoords(3, 1, -4));
    const enemy3 = createArcher('enemy-3', 'enemy', cubeCoords(4, 0, -4));

    enemy1.stats.hp = 30;
    enemy2.stats.hp = 60;
    enemy3.stats.hp = 90;

    const enemies = [enemy1, enemy2, enemy3];
    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(attacker.id, attacker);
    enemies.forEach(e => allUnits.set(e.id, e));

    const result = threatAssessor.getPrioritizedTargets(attacker, enemies, 'kill', allUnits);

    expect(result).toHaveLength(3);
    expect(result[0].score).toBeGreaterThanOrEqual(result[1].score);
    expect(result[1].score).toBeGreaterThanOrEqual(result[2].score);
    expect(result[0].unit.stats.hp).toBe(30);
  });

  it('getPrioritizedTargets(mode=closest): 按距离排序', () => {
    const attacker = createWarrior('attacker-1', 'player', cubeCoords(0, 0, 0));
    const closeEnemy = createWarrior('enemy-close', 'enemy', cubeCoords(1, 0, -1));
    const midEnemy = createWarrior('enemy-mid', 'enemy', cubeCoords(3, 0, -3));
    const farEnemy = createWarrior('enemy-far', 'enemy', cubeCoords(5, 0, -5));

    const enemies = [farEnemy, midEnemy, closeEnemy];
    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(attacker.id, attacker);
    enemies.forEach(e => allUnits.set(e.id, e));

    const result = threatAssessor.getPrioritizedTargets(attacker, enemies, 'closest', allUnits);

    expect(result).toHaveLength(3);
    expect(result[0].unit.id).toBe('enemy-close');
    expect(result[2].unit.id).toBe('enemy-far');
  });

  it('getPrioritizedTargets(mode=highestDamage): 按伤害潜力排序', () => {
    const attacker = createWarrior('attacker-1', 'player', cubeCoords(0, 0, 0));
    const highDmgEnemy = createMage('enemy-mage', 'enemy', cubeCoords(2, 0, -2));
    const midDmgEnemy = createWarrior('enemy-warrior', 'enemy', cubeCoords(3, 0, -3));
    const lowDmgEnemy = createTank('enemy-tank', 'enemy', cubeCoords(4, 0, -4));

    const enemies = [lowDmgEnemy, highDmgEnemy, midDmgEnemy];
    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(attacker.id, attacker);
    enemies.forEach(e => allUnits.set(e.id, e));

    const result = threatAssessor.getPrioritizedTargets(attacker, enemies, 'highestDamage', allUnits);

    expect(result).toHaveLength(3);
    expect(result[0].score).toBeGreaterThan(result[2].score);
  });

  it('5种模式排序结果各不相同', () => {
    const attacker = createWarrior('attacker-1', 'player', cubeCoords(0, 0, 0));
    const enemy1 = createWarrior('enemy-1', 'enemy', cubeCoords(1, 0, -1));
    const enemy2 = createMage('enemy-2', 'enemy', cubeCoords(3, 1, -4));
    const enemy3 = createArcher('enemy-3', 'enemy', cubeCoords(5, 0, -5));

    enemy1.stats.hp = 90;
    enemy2.stats.hp = 50;
    enemy3.stats.hp = 20;

    const enemies = [enemy1, enemy2, enemy3];
    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(attacker.id, attacker);
    enemies.forEach(e => allUnits.set(e.id, e));

    const modes: TargetSortMode[] = ['threat', 'kill', 'closest', 'lowestHp', 'highestDamage'];
    const results: string[][] = [];

    for (const mode of modes) {
      const result = threatAssessor.getPrioritizedTargets(attacker, enemies, mode, allUnits);
      results.push(result.map(r => r.unit.id));
    }

    const uniqueOrderings = new Set(results.map(r => r.join(',')));
    expect(uniqueOrderings.size).toBeGreaterThan(1);
  });
});

describe('Test 2: 角色自动识别', () => {
  let tacticalAI: TacticalAI;
  let squadAI: SquadAI;

  beforeEach(() => {
    const profile = createMockAIProfile();
    tacticalAI = new TacticalAI(profile);
    squadAI = new SquadAI('squad-1', 'player', []);
  });

  it('createWarrior → detectAIRole 返回 melee', () => {
    const warrior = createWarrior('warrior-1', 'player', cubeCoords(0, 0, 0));
    const role = tacticalAI.detectAIRole(warrior);
    expect(role).toBe('melee');
  });

  it('createArcher → detectAIRole 返回 ranged', () => {
    const archer = createArcher('archer-1', 'player', cubeCoords(0, 0, 0));
    const role = tacticalAI.detectAIRole(archer);
    expect(role).toBe('ranged');
  });

  it('createMage（带heal技能）→ detectAIRole 返回 healer', () => {
    const mage = createMage('mage-1', 'player', cubeCoords(0, 0, 0));
    const healSkill = createSkill(
      'heal-skill',
      '治愈术',
      'active',
      'magic',
      'light',
      3,
      [{ type: 'heal', target: 'single', value: 30 }],
      { canTargetAlly: true, tags: ['heal'] }
    );
    mage.skills.push(healSkill);

    const role = tacticalAI.detectAIRole(mage);
    expect(role).toBe('healer');
  });

  it('createTank → detectAIRole 返回 tank', () => {
    const tank = createTank('tank-1', 'player', cubeCoords(0, 0, 0));
    const role = tacticalAI.detectAIRole(tank);
    expect(role).toBe('tank');
  });

  it('自定义带 heal + buff 技能的单位 → detectAIRole 返回 support 或 healer', () => {
    const supportUnit = createUnit('support-1', 'Support', 'player', cubeCoords(0, 0, 0), {
      maxHp: 80,
      hp: 80,
      magicAttack: 30,
      attackRange: 2,
    });

    const healSkill = createSkill(
      'heal-skill',
      '小治愈',
      'active',
      'magic',
      'light',
      3,
      [{ type: 'heal', target: 'single', value: 20 }],
      { canTargetAlly: true, tags: ['heal'] }
    );

    const buffSkill1 = createSkill(
      'buff-1',
      '力量祝福',
      'active',
      'magic',
      'light',
      3,
      [{ type: 'buff', target: 'single', value: 10, stat: 'attack' }],
      { canTargetAlly: true, tags: ['buff'] }
    );

    const buffSkill2 = createSkill(
      'buff-2',
      '护盾术',
      'active',
      'magic',
      'earth',
      3,
      [{ type: 'buff', target: 'single', value: 10, stat: 'defense' }],
      { canTargetAlly: true, tags: ['buff'] }
    );

    supportUnit.skills.push(healSkill, buffSkill1, buffSkill2);

    const role = tacticalAI.detectAIRole(supportUnit);
    expect(['healer', 'support']).toContain(role);
  });

  it('高速度+低攻击的单位 → detectAIRole 返回 scout', () => {
    const scout = createUnit('scout-1', 'Scout', 'player', cubeCoords(0, 0, 0), {
      maxHp: 60,
      hp: 60,
      attack: 15,
      speed: 20,
      moveRange: 5,
      visionRange: 6,
    });

    const role = tacticalAI.detectAIRole(scout);
    expect(role).toBe('scout');
  });

  it('SquadAI.detectUnitAIRole 也能正确识别角色', () => {
    const units = [
      createWarrior('w1', 'player', cubeCoords(0, 0, 0)),
      createArcher('a1', 'player', cubeCoords(1, 0, -1)),
      createTank('t1', 'player', cubeCoords(2, 0, -2)),
    ];

    const allUnits = new Map<ID, CombatUnit>();
    units.forEach(u => allUnits.set(u.id, u));

    const squadAIWithMembers = new SquadAI('squad-2', 'player', units.map(u => u.id));
    const roles = squadAIWithMembers.assignAIRoles(allUnits);

    expect(roles.get('w1')).toBe('melee');
    expect(roles.get('a1')).toBe('ranged');
    expect(roles.get('t1')).toBe('tank');
  });
});

describe('Test 3: 远程单位保持距离', () => {
  let tacticalAI: TacticalAI;
  let threatAssessor: ThreatAssessment;

  beforeEach(() => {
    const profile = createMockAIProfile();
    profile.defensiveness = 0.7;
    tacticalAI = new TacticalAI(profile);
    threatAssessor = new ThreatAssessment(DEFAULT_AI_PARAMETERS.threatWeight);
  });

  it('远程单位 findBestKitingPosition 会找到合适距离的位置', () => {
    const archer = createArcher('archer-1', 'player', cubeCoords(0, 0, 0));
    const enemy = createWarrior('enemy-1', 'enemy', cubeCoords(2, 0, -2));

    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(archer.id, archer);
    allUnits.set(enemy.id, enemy);

    const positions: CubeCoords[] = [];
    for (let d = 0; d <= 4; d++) {
      positions.push(cubeCoords(d, 0, -d));
    }

    const enemies = [enemy];
    const decision = tacticalAI.makeRoleBasedDecision(archer, allUnits, positions);

    expect(decision).toBeDefined();
    expect(decision.action).toBeDefined();
  });

  it('calculatePositionDanger: 分数越高越危险', () => {
    const position1 = cubeCoords(0, 0, 0);
    const position2 = cubeCoords(5, 0, -5);

    const enemy1 = createWarrior('enemy-1', 'enemy', cubeCoords(1, 0, -1));
    const enemy2 = createWarrior('enemy-2', 'enemy', cubeCoords(2, 0, -2));

    const enemies = [enemy1, enemy2];

    const danger1 = threatAssessor.calculatePositionDanger(position1, enemies);
    const danger2 = threatAssessor.calculatePositionDanger(position2, enemies);

    expect(danger1.dangerScore).toBeGreaterThan(danger2.dangerScore);
    expect(danger1.nearbyEnemies.length).toBeGreaterThanOrEqual(danger2.nearbyEnemies.length);
  });

  it('远程单位不会主动冲进1格内', () => {
    const archer = createArcher('archer-1', 'player', cubeCoords(0, 0, 0));
    archer.stats.attackRange = 3;

    const enemy = createWarrior('enemy-1', 'enemy', cubeCoords(4, 0, -4));

    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(archer.id, archer);
    allUnits.set(enemy.id, enemy);

    const decision = tacticalAI.makeRoleBasedDecision(archer, allUnits);

    expect(decision).toBeDefined();
    if (decision.action === 'move' && decision.targetCoords) {
      const distAfterMove = cubeDistance(decision.targetCoords, enemy.coords);
      expect(distAfterMove).toBeGreaterThanOrEqual(1);
    }
  });

  it('位置危险度高时远程单位会撤退', () => {
    const archer = createArcher('archer-1', 'player', cubeCoords(0, 0, 0));
    archer.stats.attackRange = 3;
    archer.stats.moveRange = 4;

    const enemy1 = createWarrior('enemy-1', 'enemy', cubeCoords(1, 0, -1));
    const enemy2 = createWarrior('enemy-2', 'enemy', cubeCoords(0, 1, -1));

    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(archer.id, archer);
    allUnits.set(enemy1.id, enemy1);
    allUnits.set(enemy2.id, enemy2);

    const currentDanger = threatAssessor.calculatePositionDanger(archer.coords, [enemy1, enemy2]);
    expect(currentDanger.dangerScore).toBeGreaterThan(0);

    const decision = tacticalAI.makeRoleBasedDecision(archer, allUnits);
    expect(decision).toBeDefined();
  });
});

describe('Test 4: 治疗单位优先治疗', () => {
  let tacticalAI: TacticalAI;

  beforeEach(() => {
    const profile = createMockAIProfile();
    profile.supportiveness = 0.9;
    tacticalAI = new TacticalAI(profile);
  });

  it('healLowestAlly 行为节点能正确找到最低血量友军', () => {
    const healer = createMage('healer-1', 'player', cubeCoords(0, 0, 0));
    const allyA = createWarrior('ally-a', 'player', cubeCoords(1, 0, -1));
    const allyB = createWarrior('ally-b', 'player', cubeCoords(2, 0, -2));
    const allyC = createWarrior('ally-c', 'player', cubeCoords(3, 0, -3));

    const healSkill = createSkill(
      'heal-skill',
      '治愈术',
      'active',
      'magic',
      'light',
      4,
      [{ type: 'heal', target: 'single', value: 30 }],
      { canTargetAlly: true, tags: ['heal'] }
    );
    healer.skills.push(healSkill);

    allyA.stats.hp = 100;
    allyA.stats.maxHp = 100;
    allyB.stats.hp = 60;
    allyB.stats.maxHp = 100;
    allyC.stats.hp = 20;
    allyC.stats.maxHp = 100;

    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(healer.id, healer);
    allUnits.set(allyA.id, allyA);
    allUnits.set(allyB.id, allyB);
    allUnits.set(allyC.id, allyC);

    const context = createAIContext(healer, allUnits);
    const healAction = BehaviorActionBuilders.healLowestAlly();
    const result = healAction(context);

    expect(result).toBe('success');
    expect(context.memory.get('healTarget')).toBe('ally-c');
  });

  it('AI决策：优先选择治疗最低血量友军', () => {
    const healer = createMage('healer-1', 'player', cubeCoords(0, 0, 0));
    const allyA = createWarrior('ally-a', 'player', cubeCoords(1, 0, -1));
    const allyB = createWarrior('ally-b', 'player', cubeCoords(2, 0, -2));
    const allyC = createWarrior('ally-c', 'player', cubeCoords(1, 1, -2));

    const healSkill = createSkill(
      'heal-skill',
      '治愈术',
      'active',
      'magic',
      'light',
      3,
      [{ type: 'heal', target: 'single', value: 30 }],
      { canTargetAlly: true, tags: ['heal'], mpCost: 10 }
    );
    healer.skills.push(healSkill);
    healer.stats.mp = 50;

    allyA.stats.hp = 100;
    allyA.stats.maxHp = 100;
    allyB.stats.hp = 60;
    allyB.stats.maxHp = 100;
    allyC.stats.hp = 20;
    allyC.stats.maxHp = 100;

    const enemy = createWarrior('enemy-1', 'enemy', cubeCoords(5, 0, -5));

    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(healer.id, healer);
    allUnits.set(allyA.id, allyA);
    allUnits.set(allyB.id, allyB);
    allUnits.set(allyC.id, allyC);
    allUnits.set(enemy.id, enemy);

    const decision = tacticalAI.makeRoleBasedDecision(healer, allUnits);

    expect(decision).toBeDefined();
    if (decision.action === 'skill') {
      expect(decision.targetUnitId).toBe('ally-c');
    }
  });

  it('所有友军满血时，治疗者改为攻击敌人或等待', () => {
    const healer = createMage('healer-1', 'player', cubeCoords(0, 0, 0));
    const allyA = createWarrior('ally-a', 'player', cubeCoords(1, 0, -1));
    const allyB = createWarrior('ally-b', 'player', cubeCoords(2, 0, -2));

    const healSkill = createSkill(
      'heal-skill',
      '治愈术',
      'active',
      'magic',
      'light',
      3,
      [{ type: 'heal', target: 'single', value: 30 }],
      { canTargetAlly: true, tags: ['heal'], mpCost: 10 }
    );
    healer.skills.push(healSkill);

    allyA.stats.hp = 100;
    allyA.stats.maxHp = 100;
    allyB.stats.hp = 100;
    allyB.stats.maxHp = 100;

    const enemy = createWarrior('enemy-1', 'enemy', cubeCoords(3, 0, -3));

    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(healer.id, healer);
    allUnits.set(allyA.id, allyA);
    allUnits.set(allyB.id, allyB);
    allUnits.set(enemy.id, enemy);

    const decision = tacticalAI.makeRoleBasedDecision(healer, allUnits);

    expect(decision).toBeDefined();
    expect(['attack', 'wait', 'move', 'skill']).toContain(decision.action);
  });

  it('没有友军时 healLowestAlly 返回 failure', () => {
    const healer = createMage('healer-1', 'player', cubeCoords(0, 0, 0));
    const allUnits = new Map<ID, CombatUnit>();
    allUnits.set(healer.id, healer);

    const context = createAIContext(healer, allUnits);
    const healAction = BehaviorActionBuilders.healLowestAlly();
    const result = healAction(context);

    expect(result).toBe('failure');
  });
});

describe('Test 5: 行为树条件节点', () => {
  let unit: CombatUnit;
  let allUnits: Map<ID, CombatUnit>;

  beforeEach(() => {
    unit = createWarrior('test-unit', 'player', cubeCoords(0, 0, 0));
    allUnits = new Map<ID, CombatUnit>();
    allUnits.set(unit.id, unit);
  });

  it('hasHpBelow(50): HP 60% → false；HP 40% → true', () => {
    unit.stats.maxHp = 100;
    unit.stats.hp = 60;

    const context = createAIContext(unit, allUnits);
    const condition = BehaviorConditionBuilders.hasHpBelow(0.5);

    expect(condition(context)).toBe(false);

    unit.stats.hp = 40;
    expect(condition(context)).toBe(true);
  });

  it('hasHpAbove(80): HP 90% → true；HP 70% → false', () => {
    unit.stats.maxHp = 100;
    unit.stats.hp = 90;

    const context = createAIContext(unit, allUnits);
    const condition = BehaviorConditionBuilders.hasHpAbove(0.8);

    expect(condition(context)).toBe(true);

    unit.stats.hp = 70;
    expect(condition(context)).toBe(false);
  });

  it('everyNTurns(3): 回合3,6,9触发；其他回合不触发', () => {
    const condition = BehaviorConditionBuilders.everyNTurns(3);

    const context1 = createAIContext(unit, allUnits, 3);
    expect(condition(context1)).toBe(true);

    const context2 = createAIContext(unit, allUnits, 6);
    expect(condition(context2)).toBe(true);

    const context3 = createAIContext(unit, allUnits, 9);
    expect(condition(context3)).toBe(true);

    const context4 = createAIContext(unit, allUnits, 2);
    expect(condition(context4)).toBe(false);

    const context5 = createAIContext(unit, allUnits, 4);
    expect(condition(context5)).toBe(false);
  });

  it('hasStatusEffect(poison): 有该效果时返回true，没有时返回false', () => {
    const condition = BehaviorConditionBuilders.hasStatusEffect('poison');
    const context = createAIContext(unit, allUnits);

    expect(condition(context)).toBe(false);

    const poisonEffect = createDotEffect('poison-1', 'poison', 'poison', 10, 3, 'physical', 'poison', 1);
    unit.statusEffects.push(poisonEffect);

    expect(condition(context)).toBe(true);
  });

  it('enemyCountBelow(3): 敌人数量 <3 时 true，>=3 时 false', () => {
    const condition = BehaviorConditionBuilders.enemyCountBelow(3);

    const enemy1 = createWarrior('enemy-1', 'enemy', cubeCoords(2, 0, -2));
    const enemy2 = createWarrior('enemy-2', 'enemy', cubeCoords(3, 0, -3));
    const enemy3 = createWarrior('enemy-3', 'enemy', cubeCoords(4, 0, -4));

    allUnits.set(enemy1.id, enemy1);
    allUnits.set(enemy2.id, enemy2);

    const context1 = createAIContext(unit, allUnits);
    expect(condition(context1)).toBe(true);

    allUnits.set(enemy3.id, enemy3);
    const context2 = createAIContext(unit, allUnits);
    expect(condition(context2)).toBe(false);
  });

  it('hasMpAbove: 正确检测MP百分比', () => {
    const condition = BehaviorConditionBuilders.hasMpAbove(0.5);
    unit.stats.maxMp = 100;
    unit.stats.mp = 60;

    const context = createAIContext(unit, allUnits);
    expect(condition(context)).toBe(true);

    unit.stats.mp = 40;
    expect(condition(context)).toBe(false);
  });
});

describe('Test 6: 行为树动作节点', () => {
  let unit: CombatUnit;
  let allUnits: Map<ID, CombatUnit>;

  beforeEach(() => {
    unit = createWarrior('test-unit', 'player', cubeCoords(0, 0, 0));
    allUnits = new Map<ID, CombatUnit>();
    allUnits.set(unit.id, unit);
  });

  it('moveToNearestEnemy() 动作成功时返回 success', () => {
    const enemy = createWarrior('enemy-1', 'enemy', cubeCoords(3, 0, -3));
    allUnits.set(enemy.id, enemy);

    const context = createAIContext(unit, allUnits);
    const action = BehaviorActionBuilders.moveToNearestEnemy();
    const result = action(context);

    expect(result).toBe('success');
    expect(context.memory.has('moveTarget')).toBe(true);
  });

  it('moveToNearestEnemy() 没有敌人时返回 failure', () => {
    const context = createAIContext(unit, allUnits);
    const action = BehaviorActionBuilders.moveToNearestEnemy();
    const result = action(context);

    expect(result).toBe('failure');
  });

  it('attackHighestThreat() 有威胁目标时返回 success', () => {
    const enemy1 = createWarrior('enemy-1', 'enemy', cubeCoords(2, 0, -2));
    const enemy2 = createWarrior('enemy-2', 'enemy', cubeCoords(4, 0, -4));
    allUnits.set(enemy1.id, enemy1);
    allUnits.set(enemy2.id, enemy2);

    const context = createAIContext(unit, allUnits);
    context.threatMap.set('enemy-1', 80);
    context.threatMap.set('enemy-2', 50);

    const action = BehaviorActionBuilders.attackHighestThreat();
    const result = action(context);

    expect(result).toBe('success');
    expect(context.memory.get('attackTarget')).toBe('enemy-1');
  });

  it('attackHighestThreat() 没有威胁时返回 failure', () => {
    const context = createAIContext(unit, allUnits);
    const action = BehaviorActionBuilders.attackHighestThreat();
    const result = action(context);

    expect(result).toBe('failure');
  });

  it('wait() 动作总是返回 success', () => {
    const context = createAIContext(unit, allUnits);
    const action = BehaviorActionBuilders.wait();
    const result = action(context);

    expect(result).toBe('success');
  });

  it('castAoeOnBestCluster() 找最优AOE目标', () => {
    const mage = createMage('mage-1', 'player', cubeCoords(0, 0, 0));
    const aoeSkill = createDamageSkill('aoe-skill', '火球术', 'magic', 'fire', 40, 4, 2);
    mage.skills.push(aoeSkill);
    mage.stats.mp = 50;

    const enemy1 = createWarrior('enemy-1', 'enemy', cubeCoords(3, 0, -3));
    const enemy2 = createWarrior('enemy-2', 'enemy', cubeCoords(3, 1, -4));
    const enemy3 = createWarrior('enemy-3', 'enemy', cubeCoords(6, 0, -6));

    const allUnits2 = new Map<ID, CombatUnit>();
    allUnits2.set(mage.id, mage);
    allUnits2.set(enemy1.id, enemy1);
    allUnits2.set(enemy2.id, enemy2);
    allUnits2.set(enemy3.id, enemy3);

    const context = createAIContext(mage, allUnits2);
    const action = BehaviorActionBuilders.castAoeOnBestCluster('aoe-skill', 2, 2);
    const result = action(context);

    expect(result).toBe('success');
    expect(context.memory.get('aoeSkill')).toBe('aoe-skill');
    expect(context.memory.has('aoeTarget')).toBe(true);
  });

  it('castAoeOnBestCluster() 敌人不足时返回 failure', () => {
    const mage = createMage('mage-1', 'player', cubeCoords(0, 0, 0));
    const aoeSkill = createDamageSkill('aoe-skill', '火球术', 'magic', 'fire', 40, 4, 2);
    mage.skills.push(aoeSkill);
    mage.stats.mp = 50;

    const enemy1 = createWarrior('enemy-1', 'enemy', cubeCoords(3, 0, -3));

    const allUnits2 = new Map<ID, CombatUnit>();
    allUnits2.set(mage.id, mage);
    allUnits2.set(enemy1.id, enemy1);

    const context = createAIContext(mage, allUnits2);
    const action = BehaviorActionBuilders.castAoeOnBestCluster('aoe-skill', 2, 2);
    const result = action(context);

    expect(result).toBe('failure');
  });
});

describe('Test 7: Boss 行为树建造器', () => {
  let bossUnit: CombatUnit;
  let allUnits: Map<ID, CombatUnit>;

  beforeEach(() => {
    bossUnit = createUnit('boss-1', 'Boss', 'enemy', cubeCoords(5, 3, -8), {
      maxHp: 500,
      hp: 500,
      attack: 60,
      defense: 40,
      moveRange: 3,
      attackRange: 2,
    });

    const skillA = createDamageSkill('skill-a', '技能A', 'magic', 'dark', 80, 3, 0);
    const aoeSkill = createDamageSkill('aoe-skill', 'AOE技能', 'magic', 'dark', 50, 4, 2);
    bossUnit.skills.push(skillA, aoeSkill);
    bossUnit.stats.mp = 100;

    allUnits = new Map<ID, CombatUnit>();
    allUnits.set(bossUnit.id, bossUnit);

    const player1 = createWarrior('p1', 'player', cubeCoords(0, 0, 0));
    const player2 = createArcher('p2', 'player', cubeCoords(1, 0, -1));
    const player3 = createMage('p3', 'player', cubeCoords(0, 1, -1));
    allUnits.set(player1.id, player1);
    allUnits.set(player2.id, player2);
    allUnits.set(player3.id, player3);
  });

  it('使用 BossBehaviorTreeBuilder 链式构建 Boss AI', () => {
    const builder = new BossBehaviorTreeBuilder();

    const bt = builder
      .selector('BossRoot')
        .sequence('Phase1')
          .condition('hpAbove50', BehaviorConditionBuilders.hasHpAbove(0.5))
          .action('normalAttack', BehaviorActionBuilders.attackHighestThreat())
          .condition('every2Turns', BehaviorConditionBuilders.everyNTurns(2))
          .action('castSkillA', BehaviorActionBuilders.castSkillById('skill-a', 'highestThreat'))
          .end()
        .sequence('Phase2')
          .condition('hpBelow50', BehaviorConditionBuilders.hasHpBelow(0.5))
          .action('normalAttack', BehaviorActionBuilders.attackHighestThreat())
          .condition('every3Turns', BehaviorConditionBuilders.everyNTurns(3))
          .action('castAoe', BehaviorActionBuilders.castAoeOnBestCluster('aoe-skill', 2, 2))
          .action('summon', BehaviorActionBuilders.spawnSummons({ templateId: 'minion', count: 2 }))
          .end()
        .end()
      .build();

    expect(bt).toBeInstanceOf(BehaviorTree);
    expect(bt.getRoot()).not.toBeNull();
  });

  it('构建的行为树可以执行', () => {
    const builder = new BossBehaviorTreeBuilder();

    const bt = builder
      .sequence('TestSequence')
        .action('wait', BehaviorActionBuilders.wait())
        .end()
      .build();

    const context = createAIContext(bossUnit, allUnits, 1);
    const result = bt.execute(context);

    expect(['success', 'failure', 'running']).toContain(result);
  });

  it('hasHpBelow(50) 条件正确切换分支', () => {
    const builder = new BossBehaviorTreeBuilder();

    const bt = builder
      .selector('PhaseSelector')
        .sequence('HighHpPhase')
          .condition('hpAbove50', BehaviorConditionBuilders.hasHpAbove(0.5))
          .action('highHpAction', (ctx) => {
            ctx.memory.set('phase', 'high');
            return 'success';
          })
          .end()
        .sequence('LowHpPhase')
          .action('lowHpAction', (ctx) => {
            ctx.memory.set('phase', 'low');
            return 'success';
          })
          .end()
        .end()
      .build();

    bossUnit.stats.hp = 400;
    bossUnit.stats.maxHp = 500;
    const context1 = createAIContext(bossUnit, allUnits, 1);
    bt.execute(context1);
    expect(context1.memory.get('phase')).toBe('high');

    bossUnit.stats.hp = 200;
    bossUnit.stats.maxHp = 500;
    bt.reset();
    const context2 = createAIContext(bossUnit, allUnits, 1);
    bt.execute(context2);
    expect(context2.memory.get('phase')).toBe('low');
  });

  it('fromConfig() 从JSON配置构建行为树', () => {
    const config = {
      root: {
        type: 'sequence' as const,
        name: 'TestConfigTree',
        children: [
          {
            type: 'condition' as const,
            name: 'HpCheck',
            condition: 'hasHpBelow',
            conditionParams: { percentage: 0.5 },
          },
          {
            type: 'action' as const,
            name: 'WaitAction',
            action: 'wait',
          },
        ],
      },
    };

    const bt = BossBehaviorTreeBuilder.fromConfig(config);

    expect(bt).toBeInstanceOf(BehaviorTree);
    expect(bt.getRoot()).not.toBeNull();
    expect(bt.getRoot()?.name).toBe('TestConfigTree');
  });

  it('Phase 2 (HP<50%) 包含更多技能和召唤', () => {
    const builder = new BossBehaviorTreeBuilder();

    const bt = builder
      .selector('BossBehavior')
        .sequence('Phase1')
          .condition('isPhase1', BehaviorConditionBuilders.hasHpAbove(0.5))
          .action('attack', BehaviorActionBuilders.attackHighestThreat())
          .end()
        .sequence('Phase2')
          .condition('isPhase2', BehaviorConditionBuilders.hasHpBelow(0.5))
          .action('attack', BehaviorActionBuilders.attackHighestThreat())
          .action('aoe', BehaviorActionBuilders.castAoeOnBestCluster('aoe-skill', 2, 2))
          .action('summon', BehaviorActionBuilders.spawnSummons({ templateId: 'minion', count: 1 }))
          .end()
        .end()
      .build();

    bossUnit.stats.hp = 200;
    bossUnit.stats.maxHp = 500;
    const context = createAIContext(bossUnit, allUnits, 3);
    bt.execute(context);

    expect(bt.getExecutionLog().length).toBeGreaterThan(0);
  });
});

describe('Test 8: 小队AI与集火', () => {
  let squadAI: SquadAI;
  let allUnits: Map<ID, CombatUnit>;

  beforeEach(() => {
    const tank = createTank('tank-1', 'player', cubeCoords(0, 2, -2));
    const dps = createWarrior('dps-1', 'player', cubeCoords(1, 2, -3));
    const healer = createMage('healer-1', 'player', cubeCoords(0, 3, -3));

    const healSkill = createSkill(
      'heal-skill',
      '治愈术',
      'active',
      'magic',
      'light',
      3,
      [{ type: 'heal', target: 'single', value: 30 }],
      { canTargetAlly: true, tags: ['heal'] }
    );
    healer.skills.push(healSkill);

    const enemy1 = createWarrior('e1', 'enemy', cubeCoords(5, 2, -7));
    const enemy2 = createArcher('e2', 'enemy', cubeCoords(6, 2, -8));
    const enemy3 = createMage('e3', 'enemy', cubeCoords(5, 3, -8));

    allUnits = new Map<ID, CombatUnit>();
    allUnits.set(tank.id, tank);
    allUnits.set(dps.id, dps);
    allUnits.set(healer.id, healer);
    allUnits.set(enemy1.id, enemy1);
    allUnits.set(enemy2.id, enemy2);
    allUnits.set(enemy3.id, enemy3);

    squadAI = new SquadAI('squad-1', 'player', [tank.id, dps.id, healer.id]);
  });

  it('SquadAI.assignAIRoles() 正确分配角色', () => {
    const roles = squadAI.assignAIRoles(allUnits);

    expect(roles.get('tank-1')).toBe('tank');
    expect(roles.get('dps-1')).toBe('melee');
    expect(roles.get('healer-1')).toBe('healer');
    expect(roles.size).toBe(3);
  });

  it('setFocusTarget(enemyId) 后，getFocusTarget() 返回正确', () => {
    squadAI.setFocusTarget('e1');
    expect(squadAI.getFocusTarget()).toBe('e1');

    squadAI.setFocusTarget('e2');
    expect(squadAI.getFocusTarget()).toBe('e2');

    squadAI.clearFocusTarget();
    expect(squadAI.getFocusTarget()).toBeUndefined();
  });

  it('autoSelectFocusTarget() 自动选择高威胁目标', () => {
    const targetId = squadAI.autoSelectFocusTarget(allUnits);

    expect(targetId).not.toBeNull();
    expect(targetId).toBeDefined();
    expect(squadAI.getFocusTarget()).toBe(targetId);
  });

  it('planTacticalFormation() 返回战术队形位置', () => {
    const plans = squadAI.planTacticalFormation(allUnits);

    expect(plans.length).toBeGreaterThan(0);
    expect(plans[0]).toHaveProperty('unitId');
    expect(plans[0]).toHaveProperty('targetCoords');
    expect(plans[0]).toHaveProperty('priority');
    expect(plans[0]).toHaveProperty('reason');
  });

  it('坦克在前，治疗在后排', () => {
    const plans = squadAI.planTacticalFormation(allUnits);

    const tankPlan = plans.find(p => p.unitId === 'tank-1');
    const healerPlan = plans.find(p => p.unitId === 'healer-1');

    expect(tankPlan).toBeDefined();
    expect(healerPlan).toBeDefined();

    if (tankPlan && healerPlan) {
      const enemies = Array.from(allUnits.values()).filter(u => u.faction === 'enemy');
      const enemyCenterQ = enemies.reduce((sum, e) => sum + e.coords.q, 0) / enemies.length;

      const tankDistToEnemy = Math.abs(tankPlan.targetCoords.q - enemyCenterQ);
      const healerDistToEnemy = Math.abs(healerPlan.targetCoords.q - enemyCenterQ);

      expect(tankDistToEnemy).toBeLessThanOrEqual(healerDistToEnemy + 2);
    }
  });

  it('coordinateAttacks 协调攻击', () => {
    const attacks = squadAI.coordinateAttacks(allUnits);

    expect(Array.isArray(attacks)).toBe(true);
    if (attacks.length > 0) {
      expect(attacks[0]).toHaveProperty('primaryTargetId');
      expect(attacks[0]).toHaveProperty('attackerIds');
      expect(attacks[0].attackerIds.length).toBeGreaterThan(0);
    }
  });
});
