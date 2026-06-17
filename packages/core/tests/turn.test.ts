import { describe, it, expect, beforeEach } from 'vitest';
import { TurnManager, type UnitWithSpeed } from '../src/turn/TurnManager';
import { InterruptSystem } from '../src/turn/InterruptSystem';
import { RoundSummaryGenerator } from '../src/turn/RoundSummaryGenerator';
import type { TurnOrderConfig } from '../src/types/turn';
import type { ID } from '../src/types/common';

function createUnits(): UnitWithSpeed[] {
  return [
    { id: 'fast', speed: 30, name: 'FastUnit' },
    { id: 'medium', speed: 20, name: 'MediumUnit' },
    { id: 'slow', speed: 10, name: 'SlowUnit' },
    { id: 'tie1', speed: 20, name: 'TieUnit1' },
  ];
}

describe('TurnManager - 回合管理器', () => {
  let manager: TurnManager;
  const defaultConfig: TurnOrderConfig = {
    speedSortOrder: 'desc',
    enableDelayAction: true,
    enableInterrupts: true,
    interruptPriorityBias: 0,
  };

  beforeEach(() => {
    manager = new TurnManager(defaultConfig, createUnits());
  });

  it('buildTurnOrder 按速度降序排序', () => {
    const order = manager.buildTurnOrder();
    expect(order.length).toBe(4);
    expect(order[0].unitId).toBe('fast');
    expect(order[0].speed).toBe(30);

    const mediums = order.filter(e => e.speed === 20);
    expect(mediums.length).toBe(2);
    expect(mediums[0].tiebreaker).toBeLessThan(mediums[1].tiebreaker);

    expect(order[order.length - 1].unitId).toBe('slow');
  });

  it('buildTurnOrder 按速度升序排序', () => {
    const ascConfig: TurnOrderConfig = {
      ...defaultConfig,
      speedSortOrder: 'asc',
    };
    const ascManager = new TurnManager(ascConfig, createUnits());
    const order = ascManager.buildTurnOrder();

    expect(order[0].unitId).toBe('slow');
    expect(order[order.length - 1].unitId).toBe('fast');
  });

  it('tiebreaker 处理相同速度', () => {
    const units: UnitWithSpeed[] = [
      { id: 'a', speed: 15 },
      { id: 'b', speed: 15 },
      { id: 'c', speed: 15 },
    ];
    const mgr = new TurnManager(defaultConfig, units);
    const order = mgr.getTurnOrder();

    expect(order[0].tiebreaker).toBe(0);
    expect(order[1].tiebreaker).toBe(1);
    expect(order[2].tiebreaker).toBe(2);
    expect(order.map(e => e.tiebreaker)).toEqual([0, 1, 2]);
  });

  it('startRound/nextUnit 轮转顺序', async () => {
    await manager.startRound();
    expect(manager.getCurrentRound()).toBe(1);
    expect(manager.getCurrentPhase()).toBe('start');

    const firstUnit = await manager.nextUnit();
    expect(firstUnit).toBe('fast');
    expect(manager.getCurrentUnit()).toBe('fast');
    expect(manager.getCurrentPhase()).toBe('action');

    await manager.endTurn();
    expect(manager.getCurrentUnit()).toBeUndefined();

    const secondUnit = await manager.nextUnit();
    expect(secondUnit).toBeDefined();
    expect(['medium', 'tie1']).toContain(secondUnit);
  });

  it('endTurn 结束当前回合', async () => {
    await manager.startRound();
    await manager.nextUnit();
    expect(manager.getCurrentUnit()).toBeDefined();

    await manager.endTurn();
    expect(manager.getCurrentUnit()).toBeUndefined();

    const order = manager.getTurnOrder();
    const fastEntry = order.find(e => e.unitId === 'fast');
    expect(fastEntry?.hasActed).toBe(true);
  });

  it('phase切换', async () => {
    await manager.startRound();
    expect(manager.getCurrentPhase()).toBe('start');

    await manager.nextUnit();
    expect(manager.getCurrentPhase()).toBe('action');

    await manager.endTurn();
    expect(manager.getCurrentPhase()).toBe('end');
  });

  it('delayAction 插队延迟', () => {
    manager.delayAction('fast', 1, 'casting');
    const order = manager.getTurnOrder();
    const fastEntry = order.find(e => e.unitId === 'fast');
    expect(fastEntry?.delayCounter).toBe(1);
  });

  it('isRoundComplete 回合完成检测', async () => {
    await manager.startRound();
    expect(manager.isRoundComplete()).toBe(false);

    await manager.completeRound();
    expect(manager.isRoundComplete()).toBe(true);
  });

  it('addUnit/removeUnit 动态增删单位', () => {
    const newUnit: UnitWithSpeed = { id: 'new', speed: 25 };
    manager.addUnit(newUnit);

    const order = manager.getTurnOrder();
    expect(order.length).toBe(5);
    const newEntry = order.find(e => e.unitId === 'new');
    expect(newEntry).toBeDefined();
    expect(newEntry?.speed).toBe(25);

    manager.removeUnit('fast');
    const orderAfter = manager.getTurnOrder();
    expect(orderAfter.length).toBe(4);
    expect(orderAfter.find(e => e.unitId === 'fast')).toBeUndefined();
  });

  it('updateUnitSpeed 动态更新速度', () => {
    manager.updateUnitSpeed('slow', 100);
    const order = manager.getTurnOrder();
    expect(order[0].unitId).toBe('slow');
  });

  it('完整3轮回合统计', async () => {
    for (let i = 0; i < 3; i++) {
      await manager.startRound();
      await manager.completeRound();
    }
    const history = manager.getRoundHistory();
    expect(history.length).toBe(3);
  });
});

describe('InterruptSystem - 打断系统', () => {
  let system: InterruptSystem;

  beforeEach(() => {
    system = new InterruptSystem(0);
  });

  it('registerInterrupt 注册打断', () => {
    const id = system.registerInterrupt({
      sourceUnitId: 'src',
      targetUnitId: 'tgt',
      skillId: 'counter',
      priority: 10,
      condition: () => true,
    });

    expect(system.hasInterrupt(id)).toBe(true);
    expect(system.getInterruptCount()).toBe(1);
  });

  it('checkInterrupts 按条件触发', () => {
    system.registerInterrupt({
      sourceUnitId: 's1',
      targetUnitId: 't1',
      skillId: 'sk1',
      priority: 5,
      condition: (state: any) => state.hp < 50,
    });
    system.registerInterrupt({
      sourceUnitId: 's2',
      targetUnitId: 't2',
      skillId: 'sk2',
      priority: 15,
      condition: (state: any) => state.hp < 30,
    });

    const triggeredFull = system.checkInterrupts({ hp: 20 });
    expect(triggeredFull.length).toBe(2);

    const triggeredPartial = system.checkInterrupts({ hp: 40 });
    expect(triggeredPartial.length).toBe(1);
    expect(triggeredPartial[0].priority).toBe(5);
  });

  it('优先级排序', () => {
    system.registerInterrupt({
      sourceUnitId: 'low',
      targetUnitId: 't',
      skillId: 's',
      priority: 1,
      condition: () => true,
    });
    system.registerInterrupt({
      sourceUnitId: 'high',
      targetUnitId: 't',
      skillId: 's',
      priority: 100,
      condition: () => true,
    });
    system.registerInterrupt({
      sourceUnitId: 'mid',
      targetUnitId: 't',
      skillId: 's',
      priority: 50,
      condition: () => true,
    });

    const triggered = system.checkInterrupts({});
    expect(triggered[0].priority).toBe(100);
    expect(triggered[1].priority).toBe(50);
    expect(triggered[2].priority).toBe(1);
  });

  it('executeInterrupts 执行处理函数', () => {
    let executed = false;
    system.registerInterrupt({
      sourceUnitId: 'src',
      targetUnitId: 'tgt',
      skillId: 'counter',
      priority: 10,
      condition: () => true,
      handler: () => {
        executed = true;
      },
    });

    const results = system.executeInterrupts({ value: 100 }, 0);
    expect(results.length).toBe(1);
    expect(executed).toBe(true);
    expect(results[0].isInserted).toBe(true);
  });

  it('clearExecuted 清除已执行', () => {
    system.registerInterrupt({
      sourceUnitId: 's',
      targetUnitId: 't',
      skillId: 'sk',
      priority: 1,
      condition: () => true,
      handler: () => {},
    });

    system.executeInterrupts({}, 0);
    expect(system.getExecutedInterrupts().length).toBe(1);

    system.clearExecuted();
    expect(system.getExecutedInterrupts().length).toBe(0);
    expect(system.checkInterrupts({}).length).toBe(1);
  });

  it('unregisterInterrupt 取消注册', () => {
    const id = system.registerInterrupt({
      sourceUnitId: 's',
      targetUnitId: 't',
      skillId: 'sk',
      priority: 1,
      condition: () => true,
    });

    expect(system.unregisterInterrupt(id)).toBe(true);
    expect(system.hasInterrupt(id)).toBe(false);
    expect(system.getInterruptCount()).toBe(0);
  });

  it('priorityBias 优先级偏移', () => {
    const biasedSystem = new InterruptSystem(50);
    biasedSystem.registerInterrupt({
      sourceUnitId: 's',
      targetUnitId: 't',
      skillId: 'sk',
      priority: 10,
      condition: () => true,
    });

    const interrupt = biasedSystem.getPendingInterrupts()[0];
    expect(interrupt.priority).toBe(60);
  });
});

describe('RoundSummaryGenerator - 回合统计', () => {
  let generator: RoundSummaryGenerator;

  beforeEach(() => {
    generator = new RoundSummaryGenerator(1);
  });

  it('trackEvent 记录事件', () => {
    generator.trackEvent({
      type: 'damage',
      sourceFaction: 'player',
      targetFaction: 'enemy',
      value: 50,
    });

    const stats = generator.getDamageStats();
    expect(stats.damageDealt.get('player')).toBe(50);
    expect(stats.damageTaken.get('enemy')).toBe(50);
  });

  it('kills统计', () => {
    generator.trackEvent({
      type: 'kill',
      sourceFaction: 'player',
      targetFaction: 'enemy',
    });
    generator.trackEvent({
      type: 'kill',
      sourceFaction: 'player',
      targetFaction: 'enemy',
    });
    generator.trackEvent({
      type: 'kill',
      sourceFaction: 'enemy',
      targetFaction: 'player',
    });

    expect(generator.getKillsByFaction('player')).toBe(2);
    expect(generator.getKillsByFaction('enemy')).toBe(1);
    expect(generator.getTotalKills()).toBe(3);
  });

  it('damage统计', () => {
    generator.trackEvent({
      type: 'damage',
      sourceFaction: 'player',
      targetFaction: 'enemy',
      value: 30,
    });
    generator.trackEvent({
      type: 'damage',
      sourceFaction: 'player',
      targetFaction: 'enemy',
      value: 45,
    });
    generator.trackEvent({
      type: 'heal',
      sourceFaction: 'player',
      targetFaction: 'player',
      value: 25,
    });

    expect(generator.getTotalDamageDealt()).toBe(75);

    const stats = generator.getDamageStats();
    expect(stats.damageDealt.get('player')).toBe(75);
    expect(stats.damageTaken.get('enemy')).toBe(75);
    expect(stats.healingDone.get('player')).toBe(25);
  });

  it('addActingUnit + generateSummary 汇总', () => {
    generator.addActingUnit('u1');
    generator.addActingUnit('u2');
    generator.addActingUnit('u1');

    generator.trackEvent({
      type: 'damage',
      sourceFaction: 'p',
      targetFaction: 'e',
      value: 100,
    });

    const summary = generator.generateSummary();
    expect(summary.roundNumber).toBe(1);
    expect(summary.actingUnits.length).toBe(2);
    expect(summary.actingUnits).toContain('u1');
    expect(summary.actingUnits).toContain('u2');
    expect(summary.damageDealt.get('p')).toBe(100);
  });

  it('reset 重置统计', () => {
    generator.trackEvent({
      type: 'damage',
      sourceFaction: 'p',
      targetFaction: 'e',
      value: 100,
    });
    generator.addActingUnit('u1');
    expect(generator.getTotalDamageDealt()).toBe(100);

    generator.reset(2);
    expect(generator.getTotalDamageDealt()).toBe(0);
    expect(generator.getTotalKills()).toBe(0);

    const summary = generator.generateSummary();
    expect(summary.roundNumber).toBe(2);
    expect(summary.actingUnits.length).toBe(0);
  });

  it('多种事件类型汇总', () => {
    generator.trackEvent({ type: 'damage', sourceFaction: 'A', targetFaction: 'B', value: 10 });
    generator.trackEvent({ type: 'damage', sourceFaction: 'B', targetFaction: 'A', value: 20 });
    generator.trackEvent({ type: 'heal', sourceFaction: 'A', targetFaction: 'A', value: 15 });
    generator.trackEvent({ type: 'kill', sourceFaction: 'B', targetFaction: 'A' });

    const summary = generator.generateSummary();
    expect(summary.events.length).toBe(4);
    expect(summary.damageDealt.get('A')).toBe(10);
    expect(summary.damageDealt.get('B')).toBe(20);
    expect(summary.kills.get('B')).toBe(1);
  });
});
