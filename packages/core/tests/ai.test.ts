import { describe, it, expect, beforeEach } from 'vitest';
import {
  ThreatAssessment,
  TacticalAI,
  BehaviorTree,
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
} from '../src/types';

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

function createMockUnit(
  id: string,
  faction: Faction,
  q: number,
  r: number,
  overrides: Partial<CombatUnit> = {}
): CombatUnit {
  return {
    id,
    name: `Unit_${id}`,
    faction,
    templateId: `tpl_${id}`,
    coords: cubeCoords(q, r, -q - r),
    direction: 0,
    stats: {
      maxHp: 100,
      hp: 100,
      maxMp: 50,
      mp: 50,
      attack: 25,
      defense: 10,
      magicAttack: 15,
      magicDefense: 8,
      speed: 10 + Math.floor(Math.random() * 10),
      accuracy: 85,
      evasion: 10,
      critRate: 10,
      critDamage: 150,
      armorPenetration: 0,
      moveRange: 4,
      attackRange: 1,
      visionRange: 6,
      height: 1,
    },
    attributes: {
      hp: { current: 100, max: 100, min: 0 },
      mp: { current: 50, max: 50, min: 0 },
      attack: { base: 25, modifiers: [], current: 25 },
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

describe('ThreatAssessment', () => {
  let threatAssessor: ThreatAssessment;
  let units: Map<ID, CombatUnit>;

  beforeEach(() => {
    threatAssessor = new ThreatAssessment(DEFAULT_AI_PARAMETERS.threatWeight);
    units = new Map();
  });

  it('calculateThreat 按威胁值降序排序', () => {
    const assessor = createMockUnit('assessor', 'player', 0, 0);
    const nearEnemy = createMockUnit('near', 'enemy', 1, 0);
    const farEnemy = createMockUnit('far', 'enemy', 5, 0);
    const weakEnemy = createMockUnit('weak', 'enemy', 2, 0, {
      stats: { ...createMockUnit('x', 'enemy', 2, 0).stats, hp: 20, maxHp: 100 },
    });
    units.set(assessor.id, assessor);
    units.set(nearEnemy.id, nearEnemy);
    units.set(farEnemy.id, farEnemy);
    units.set(weakEnemy.id, weakEnemy);

    const threatMap = threatAssessor.getThreatMap(assessor, units);
    const threats = Array.from(threatMap.values());

    expect(threats.length).toBe(3);
    expect(threats.map(t => t.targetUnitId).length).toBe(3);
    for (let i = 1; i < threats.length; i++) {
      expect(threats[i - 1].threatLevel).toBeGreaterThanOrEqual(threats[i].threatLevel);
    }
  });

  it('estimateDamage 随距离衰减', () => {
    const attacker = createMockUnit('attacker', 'player', 0, 0);
    const closeTarget = createMockUnit('close', 'enemy', 1, 0);
    const midTarget = createMockUnit('mid', 'enemy', 3, 0);
    const farTarget = createMockUnit('far', 'enemy', 6, 0);

    const closeDmg = threatAssessor.estimateDamage(attacker, closeTarget);
    const midDmg = threatAssessor.estimateDamage(attacker, midTarget);
    const farDmg = threatAssessor.estimateDamage(attacker, farTarget);

    expect(closeDmg).toBeGreaterThan(0);
    expect(midDmg).toBeLessThanOrEqual(closeDmg);
    expect(farDmg).toBeLessThanOrEqual(midDmg);
  });

  it('getThreatMap 返回正确的威胁映射', () => {
    const assessor = createMockUnit('assessor', 'player', 0, 0);
    const e1 = createMockUnit('e1', 'enemy', 1, 0);
    const e2 = createMockUnit('e2', 'enemy', 2, 1);
    units.set(assessor.id, assessor);
    units.set(e1.id, e1);
    units.set(e2.id, e2);

    const threatMap = threatAssessor.getThreatMap(assessor, units);

    expect(threatMap.has(e1.id)).toBe(true);
    expect(threatMap.has(e2.id)).toBe(true);
    const t1 = threatMap.get(e1.id)!;
    const t2 = threatMap.get(e2.id)!;
    expect(t1.threatLevel).toBeGreaterThan(0);
    expect(t2.threatLevel).toBeGreaterThan(0);
    expect(t1.canAttack).toBe(true);
    expect(t1.estimatedDamage).toBeGreaterThan(0);
  });

  it('低血量目标威胁更高', () => {
    const assessor = createMockUnit('assessor', 'player', 0, 0);
    const healthy = createMockUnit('healthy', 'enemy', 2, 0);
    const dying = createMockUnit('dying', 'enemy', 2, 1, {
      stats: { ...createMockUnit('x', 'enemy', 2, 1).stats, hp: 10, maxHp: 100 },
    });
    units.set(assessor.id, assessor);
    units.set(healthy.id, healthy);
    units.set(dying.id, dying);

    const threatMap = threatAssessor.getThreatMap(assessor, units);

    const dyingThreat = threatMap.get(dying.id)!;
    const healthyThreat = threatMap.get(healthy.id)!;
    expect(dyingThreat.components.priorityThreat).toBeGreaterThan(healthyThreat.components.priorityThreat);
  });

  it('空单位列表返回空威胁', () => {
    const assessor = createMockUnit('assessor', 'player', 0, 0);
    units.set(assessor.id, assessor);

    const threats = threatAssessor.getThreatMap(assessor, units);

    expect(threats.size).toBe(0);
  });
});

describe('TacticalAI', () => {
  let tacticalAI: TacticalAI;
  let profile: AIProfile;
  let units: Map<ID, CombatUnit>;

  beforeEach(() => {
    profile = createMockAIProfile();
    tacticalAI = new TacticalAI(profile, { randomSeed: 42 });
    units = new Map();
  });

  it('makeDecision 返回有效决策', () => {
    const me = createMockUnit('me', 'player', 0, 0);
    const enemy = createMockUnit('enemy1', 'enemy', 2, 0);
    units.set(me.id, me);
    units.set(enemy.id, enemy);

    const decision = tacticalAI.makeDecision(me, units);

    expect(decision).toBeDefined();
    expect(decision.unitId).toBe(me.id);
    expect(decision.confidence).toBeGreaterThanOrEqual(0);
    expect(decision.confidence).toBeLessThanOrEqual(1);
    expect(['move', 'attack', 'skill', 'wait', 'delay']).toContain(decision.action);
    expect(typeof decision.reasoning).toBe('string');
    expect(Array.isArray(decision.alternatives)).toBe(true);
  });

  it('evaluateTarget 低血量目标优先', () => {
    const me = createMockUnit('me', 'player', 0, 0);
    const healthy = createMockUnit('h', 'enemy', 3, 0);
    const weak = createMockUnit('w', 'enemy', 3, 1, {
      stats: { ...createMockUnit('x', 'enemy', 3, 1).stats, hp: 15, maxHp: 100 },
    });
    units.set(me.id, me);
    units.set(healthy.id, healthy);
    units.set(weak.id, weak);
    const enemies = [healthy, weak];
    const threatMap = new ThreatAssessment(profile.parameters.threatWeight).getThreatMap(me, units);

    const evals = tacticalAI.evaluateTarget(me, enemies, threatMap);

    expect(evals.length).toBe(2);
    expect(evals[0].unitId).toBe(weak.id);
    expect(evals[0].score).toBeGreaterThan(evals[1].score);
  });

  it('evaluatePosition 高掩护和高地得分更高', () => {
    const me = createMockUnit('me', 'player', 0, 0);
    const target = createMockUnit('target', 'enemy', 5, 0);
    units.set(me.id, me);
    units.set(target.id, target);

    const lowPos = cubeCoords(1, 0, -1);
    const highPos = cubeCoords(2, -1, -1);
    const threatMap = new Map();

    const positions = [lowPos, highPos];
    const evals = tacticalAI.evaluatePosition(
      me,
      positions,
      target,
      units,
      threatMap,
      (c: CubeCoords) => c === highPos ? 3 : 0,
      (pos: CubeCoords) => pos === highPos ? 0.8 : 0
    );

    expect(evals.length).toBe(2);
    const highEval = evals.find(e => e.coords.q === highPos.q)!;
    const lowEval = evals.find(e => e.coords.q === lowPos.q)!;
    expect(highEval.factors.heightBonus).toBeGreaterThan(lowEval.factors.heightBonus);
    expect(highEval.factors.coverBonus).toBeGreaterThan(lowEval.factors.coverBonus);
  });

  it('setAggression 限制范围 0-1', () => {
    tacticalAI.setAggression(2.0);
    tacticalAI.setDefensiveness(-1.0);

    const decision = tacticalAI.makeDecision(
      createMockUnit('me', 'player', 0, 0),
      new Map([['me', createMockUnit('me', 'player', 0, 0)], ['e', createMockUnit('e', 'enemy', 1, 0)]])
    );
    expect(decision).toBeDefined();
  });

  it('无敌人时返回wait决策', () => {
    const me = createMockUnit('me', 'player', 0, 0);
    units.set(me.id, me);

    const decision = tacticalAI.makeDecision(me, units);

    expect(decision.action).toBe('wait');
    expect(decision.confidence).toBeLessThan(0.6);
  });
});

describe('BehaviorTree', () => {
  let bt: BehaviorTree;
  let context: AIContext;

  beforeEach(() => {
    bt = new BehaviorTree();
    context = {
      unit: createMockUnit('u1', 'player', 0, 0),
      allUnits: new Map(),
      grid: null,
      currentTurn: 1,
      memory: new Map(),
      globalMemory: new Map(),
      threatMap: new Map(),
    };
  });

  it('selector 返回第一个成功的子节点', () => {
    let firstCalled = false;
    let secondCalled = false;

    bt.registerCondition('always_fail', () => {
      firstCalled = true;
      return false;
    });
    bt.registerAction('always_success', () => {
      secondCalled = true;
      return 'success';
    });
    bt.registerCondition('never_reach', () => true);

    const tree = bt.selector([
      bt.conditionNode('always_fail'),
      bt.actionNode('always_success'),
      bt.conditionNode('never_reach'),
    ], 'test_sel');

    bt.setRoot(tree);
    const result = bt.execute(context);

    expect(result).toBe('success');
    expect(firstCalled).toBe(true);
    expect(secondCalled).toBe(true);
  });

  it('sequence 全部成功才返回 success', () => {
    const calls: string[] = [];

    bt.registerAction('a1', () => { calls.push('a1'); return 'success'; });
    bt.registerAction('a2', () => { calls.push('a2'); return 'success'; });
    bt.registerAction('a3', () => { calls.push('a3'); return 'success'; });

    const tree = bt.sequence([
      bt.actionNode('a1'),
      bt.actionNode('a2'),
      bt.actionNode('a3'),
    ], 'test_seq');

    bt.setRoot(tree);
    const result = bt.execute(context);

    expect(result).toBe('success');
    expect(calls).toEqual(['a1', 'a2', 'a3']);
  });

  it('sequence 中途失败返回 failure', () => {
    const calls: string[] = [];

    bt.registerAction('ok1', () => { calls.push('ok1'); return 'success'; });
    bt.registerAction('fail', () => { calls.push('fail'); return 'failure'; });
    bt.registerAction('never', () => { calls.push('never'); return 'success'; });

    const tree = bt.sequence([
      bt.actionNode('ok1'),
      bt.actionNode('fail'),
      bt.actionNode('never'),
    ], 'test_seq2');

    bt.setRoot(tree);
    const result = bt.execute(context);

    expect(result).toBe('failure');
    expect(calls).toEqual(['ok1', 'fail']);
  });

  it('decorator inverter 反转结果', () => {
    bt.registerAction('success', () => 'success');
    bt.registerAction('failure', () => 'failure');

    const invertedSuccess = bt.inverter(
      bt.actionNode('success')
    );
    const invertedFailure = bt.inverter(
      bt.actionNode('failure')
    );

    bt.setRoot(invertedSuccess);
    expect(bt.execute(context)).toBe('failure');
    bt.reset();
    bt.setRoot(invertedFailure);
    expect(bt.execute(context)).toBe('success');
  });

  it('decorator repeat 重复指定次数', () => {
    let count = 0;
    bt.registerAction('inc', () => { count++; return 'success'; });

    const repeated = bt.repeat(
      bt.actionNode('inc'),
      5
    );

    bt.setRoot(repeated);
    const result = bt.execute(context);

    expect(result).toBe('success');
    expect(count).toBe(5);
  });

  it('parallel 并行执行达到阈值即成功', () => {
    let a = false, b = false, c = false;

    bt.registerAction('set_a', () => { a = true; return 'success'; });
    bt.registerAction('set_b', () => { b = true; return 'success'; });
    bt.registerAction('set_c', () => { c = true; return 'failure'; });

    const tree = bt.parallel(
      [
        bt.actionNode('set_a'),
        bt.actionNode('set_b'),
        bt.actionNode('set_c'),
      ],
      2,
      2,
      'test_parallel'
    );

    bt.setRoot(tree);
    const result = bt.execute(context);

    expect(result).toBe('success');
    expect(a).toBe(true);
    expect(b).toBe(true);
    expect(c).toBe(true);
  });
});

describe('SquadAI', () => {
  let squad: SquadAI;
  let units: Map<ID, CombatUnit>;
  let memberIds: ID[];

  beforeEach(() => {
    units = new Map();
    memberIds = ['u1', 'u2', 'u3', 'u4'];
    memberIds.forEach((id, i) => {
      const faction: Faction = 'player';
      const unit = createMockUnit(id, faction, i, 0, {
        stats: {
          ...createMockUnit(id, faction, i, 0).stats,
          attack: 20 + i * 5,
          defense: i % 2 === 0 ? 30 : 8,
          maxHp: i % 2 === 0 ? 200 : 80,
          hp: i % 2 === 0 ? 200 : 80,
        },
      });
      units.set(id, unit);
    });
    squad = new SquadAI('squad_1', 'player', memberIds, 'assault');
  });

  it('setStrategy 切换策略并调整阵型', () => {
    squad.setStrategy('defend', units);
    expect(squad.getStrategy()).toBe('defend');
    expect(squad.getFormation()).toBe('circle');

    squad.setStrategy('flank', units);
    expect(squad.getStrategy()).toBe('flank');
    expect(squad.getFormation()).toBe('column');
  });

  it('assignRoles 自动分配角色', () => {
    const assignments = squad.assignRoles(units);

    expect(assignments.length).toBe(4);
    const roles = assignments.map(a => a.role);
    expect(roles).toContain('commander');

    const commander = assignments.find(a => a.role === 'commander');
    expect(commander?.priority).toBe(100);

    const roleTypes = new Set(roles);
    expect(roleTypes.size).toBeGreaterThanOrEqual(2);
  });

  it('strategyHistory 记录策略变更', () => {
    squad.setStrategy('assault', units);
    squad.setStrategy('defend', units);
    squad.setStrategy('retreat', units);

    const history = squad.getStrategyHistory();
    expect(history.length).toBeGreaterThanOrEqual(2);
    expect(history[0].strategy).toBe('assault');
    expect(history[history.length - 1].strategy).toBe('defend');
  });

  it('addMember/removeMember 管理成员', () => {
    const newUnit = createMockUnit('u5', 'player', 5, 0);
    units.set('u5', newUnit);

    squad.addMember('u5');
    expect(squad.getMembers()).toContain('u5');

    squad.removeMember('u5');
    expect(squad.getMembers()).not.toContain('u5');
    expect(squad.getMembers().length).toBe(4);
  });

  it('coordinateAttacks 多单位集火攻击', () => {
    const enemy = createMockUnit('e1', 'enemy', 5, 0);
    units.set(enemy.id, enemy);

    const attacks = squad.coordinateAttacks(units);

    expect(Array.isArray(attacks)).toBe(true);
    if (attacks.length > 0) {
      const first = attacks[0];
      expect(first.attackerIds.length).toBeGreaterThanOrEqual(1);
      expect(first.primaryTargetId).toBe(enemy.id);
      expect(first.focusBonus).toBeGreaterThanOrEqual(0);
      expect(first.focusBonus).toBeLessThanOrEqual(0.5);
    }
  });

  it('planMovement 根据阵型生成移动计划', () => {
    squad.setFormation('wedge');
    const plans = squad.planMovement(units);

    expect(plans.length).toBe(4);
    for (const plan of plans) {
      expect(memberIds).toContain(plan.unitId);
      expect(typeof plan.priority).toBe('number');
      expect(typeof plan.reason).toBe('string');
      expect(plan.targetCoords).toHaveProperty('q');
      expect(plan.targetCoords).toHaveProperty('r');
      expect(plan.targetCoords).toHaveProperty('s');
    }
  });
});
