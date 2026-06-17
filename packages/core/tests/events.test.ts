import { describe, it, expect, beforeEach } from 'vitest';
import {
  EventStore,
  ReplaySystem,
  UndoManager,
  StateRebuilder,
} from '../src/events';
import type {
  GameEvent,
  EventType,
  GameStateSnapshot,
  EventFilter,
} from '../src/types';
import { createChecksum } from '../src/utils';

function makeEvent(
  type: EventType,
  turnNumber: number,
  data: Record<string, unknown> = {},
  metadata: Record<string, unknown> = {}
): Omit<GameEvent, 'id' | 'timestamp' | 'version'> {
  return { type, turnNumber, data, metadata };
}

describe('EventStore', () => {
  let store: EventStore;

  beforeEach(() => {
    store = new EventStore({ enableSnapshots: false });
  });

  it('append 正确添加事件', () => {
    const ev1 = store.append(makeEvent('UNIT_MOVE', 1, { unitId: 'u1' }, { source: 'u1' }));
    const ev2 = store.append(makeEvent('UNIT_ATTACK', 1, { attackerId: 'u1' }));

    expect(store.getEventCount()).toBe(2);
    expect(ev1.id).toBeDefined();
    expect(ev1.timestamp).toBeDefined();
    expect(ev1.type).toBe('UNIT_MOVE');
    expect(ev2.type).toBe('UNIT_ATTACK');
    expect(ev1.id).not.toBe(ev2.id);
  });

  it('query 按类型过滤事件', () => {
    store.append(makeEvent('UNIT_MOVE', 1));
    store.append(makeEvent('UNIT_ATTACK', 1));
    store.append(makeEvent('UNIT_MOVE', 2));
    store.append(makeEvent('TURN_END', 2));

    const moves = store.query({ types: ['UNIT_MOVE'] });
    expect(moves.length).toBe(2);
    expect(moves.every(e => e.type === 'UNIT_MOVE')).toBe(true);

    const attacks = store.query({ types: ['UNIT_ATTACK'] });
    expect(attacks.length).toBe(1);

    const combined = store.query({ types: ['UNIT_MOVE', 'UNIT_ATTACK'] });
    expect(combined.length).toBe(3);
  });

  it('query 按回合和来源过滤', () => {
    store.append(makeEvent('UNIT_ATTACK', 1, { x: 1 }, { source: 'u1', faction: 'player' }));
    store.append(makeEvent('UNIT_ATTACK', 1, { x: 2 }, { source: 'u2', faction: 'enemy' }));
    store.append(makeEvent('UNIT_ATTACK', 3, { x: 3 }, { source: 'u1', faction: 'player' }));

    const turn1 = store.query({ turnRange: [1, 1] });
    expect(turn1.length).toBe(2);

    const fromU1 = store.query({ sources: ['u1'] });
    expect(fromU1.length).toBe(2);
    expect(fromU1.every(e => e.metadata.source === 'u1')).toBe(true);

    const playerFaction = store.query({ factions: ['player'] });
    expect(playerFaction.length).toBe(2);
  });

  it('subscribe + once 订阅并触发回调', () => {
    const calls: GameEvent[] = [];
    const onceCalls: GameEvent[] = [];

    store.subscribe(
      { types: ['UNIT_ATTACK'] },
      (e) => calls.push(e)
    );
    store.subscribe(
      { types: ['UNIT_MOVE'] },
      (e) => onceCalls.push(e),
      { once: true }
    );

    store.append(makeEvent('UNIT_MOVE', 1));
    store.append(makeEvent('UNIT_ATTACK', 1));
    store.append(makeEvent('UNIT_MOVE', 2));

    expect(calls.length).toBe(1);
    expect(onceCalls.length).toBe(1);
  });

  it('subscribe 优先级排序', () => {
    const order: number[] = [];

    store.subscribe({ types: ['CUSTOM'] }, () => order.push(1), { priority: 10 });
    store.subscribe({ types: ['CUSTOM'] }, () => order.push(2), { priority: 50 });
    store.subscribe({ types: ['CUSTOM'] }, () => order.push(3), { priority: 30 });

    store.append(makeEvent('CUSTOM', 1));

    expect(order).toEqual([2, 3, 1]);
  });

  it('createSnapshot 创建快照', () => {
    store.append(makeEvent('TURN_START', 1));
    store.append(makeEvent('UNIT_ATTACK', 1));
    const state = { turn: 1, units: ['u1'] };

    const snap = store.createSnapshot(state);

    expect(snap).toBeDefined();
    expect(snap.id).toBeDefined();
    expect(snap.eventIndex).toBe(1);
    expect(snap.turnNumber).toBe(1);
    expect(snap.state).toEqual(state);
    expect(snap.checksum).toBe(createChecksum(JSON.stringify(state)));
  });

  it('unsubscribe 取消订阅', () => {
    const calls: GameEvent[] = [];
    const id = store.subscribe({ types: ['CUSTOM'] }, (e) => calls.push(e));

    store.append(makeEvent('CUSTOM', 1));
    store.unsubscribe(id);
    store.append(makeEvent('CUSTOM', 2));

    expect(calls.length).toBe(1);
  });
});

describe('ReplaySystem', () => {
  let replay: ReplaySystem;
  let events: GameEvent[];
  let snapshots: GameStateSnapshot[];

  beforeEach(() => {
    replay = new ReplaySystem();
    const store = new EventStore({ enableSnapshots: false });
    for (let i = 0; i < 10; i++) {
      store.append(makeEvent(
        i % 3 === 0 ? 'UNIT_MOVE' : i % 3 === 1 ? 'UNIT_ATTACK' : 'TURN_END',
        Math.floor(i / 3) + 1,
        { idx: i }
      ));
    }
    events = store.getEvents();
    const snapState = { after: 4 };
    snapshots = [{
      id: 'snap1',
      eventId: events[4].id,
      eventIndex: 4,
      turnNumber: 2,
      state: snapState,
      timestamp: Date.now(),
      checksum: createChecksum(JSON.stringify(snapState)),
    }];
  });

  it('load 加载事件和快照', () => {
    replay.load(events, snapshots, { name: 'test' });

    expect(replay.getTotalEvents()).toBe(10);
    expect(replay.getSnapshots().length).toBe(1);
    expect(replay.isAtStart()).toBe(true);
    expect(replay.isPlaying()).toBe(false);
  });

  it('seek 回放到指定回合', () => {
    replay.load(events, snapshots);

    const result = replay.seek({ turnNumber: 2 });

    expect(replay.getCurrentEventIndex()).toBeGreaterThanOrEqual(0);
    expect(result.state).toBeDefined();
    expect(result.event).toBeDefined();
  });

  it('seek 回放到指定事件索引', () => {
    replay.load(events, snapshots);

    const result = replay.seek({ eventIndex: 6 });

    expect(replay.getCurrentEventIndex()).toBe(6);
    expect(result.event).toBeDefined();
    expect((result.event!.data as Record<string, unknown>).idx).toBe(6);
  });

  it('step 向前向后步进', () => {
    replay.load(events, snapshots);

    const fwd1 = replay.step(1);
    expect(fwd1.event!.data).toHaveProperty('idx');
    expect(replay.getCurrentEventIndex()).toBe(0);

    const fwd3 = replay.step(3);
    expect(replay.getCurrentEventIndex()).toBe(3);

    const back2 = replay.step(-2);
    expect(replay.getCurrentEventIndex()).toBe(1);
  });

  it('setPlaybackSpeed 设置播放速度范围[0.1,10]', () => {
    replay.load(events, snapshots);

    replay.setPlaybackSpeed(0.5);
    expect(replay.getPlaybackSpeed()).toBe(0.5);

    replay.setPlaybackSpeed(100);
    expect(replay.getPlaybackSpeed()).toBe(10);

    replay.setPlaybackSpeed(0.001);
    expect(replay.getPlaybackSpeed()).toBe(0.1);
  });

  it('play/pause 控制播放状态', () => {
    replay.load(events.slice(0, 3), snapshots);

    replay.play();
    expect(replay.isPlaying()).toBe(true);
    replay.pause();
    expect(replay.isPlaying()).toBe(false);
  });
});

describe('UndoManager', () => {
  let undoMgr: UndoManager;

  beforeEach(() => {
    undoMgr = new UndoManager(20);
  });

  it('pushState + undo + redo 正常工作', () => {
    const store = new EventStore({ enableSnapshots: false });
    store.append(makeEvent('TURN_START', 1));
    const events1 = store.getEvents();

    undoMgr.pushState({ turn: 1, hp: 100 }, events1);
    store.append(makeEvent('UNIT_ATTACK', 1));
    const events2 = store.getEvents().slice(1);
    undoMgr.pushState({ turn: 1, hp: 70 }, events2);
    store.append(makeEvent('TURN_END', 1));
    const events3 = store.getEvents().slice(2);
    undoMgr.pushState({ turn: 2, hp: 70 }, events3);

    expect(undoMgr.canUndo()).toBe(true);
    expect(undoMgr.canRedo()).toBe(false);

    const step1 = undoMgr.undo();
    expect(step1!.state).toEqual({ turn: 1, hp: 70 });
    expect(undoMgr.canRedo()).toBe(true);

    const step2 = undoMgr.undo();
    expect(step2!.state).toEqual({ turn: 1, hp: 100 });

    const step3 = undoMgr.undo();
    expect(step3!.state).toEqual({});

    const noUndo = undoMgr.undo();
    expect(noUndo).toBeNull();

    const redo1 = undoMgr.redo();
    expect(redo1!.state).toEqual({ turn: 1, hp: 100 });
  });

  it('canUndo/canRedo 边界检测', () => {
    expect(undoMgr.canUndo()).toBe(false);
    expect(undoMgr.canRedo()).toBe(false);

    undoMgr.pushState({ a: 1 });
    expect(undoMgr.canUndo()).toBe(true);
    expect(undoMgr.canRedo()).toBe(false);

    undoMgr.undo();
    expect(undoMgr.canUndo()).toBe(false);
    expect(undoMgr.canRedo()).toBe(true);

    undoMgr.redo();
    expect(undoMgr.canUndo()).toBe(true);
    expect(undoMgr.canRedo()).toBe(false);
  });

  it('新状态覆盖redo历史', () => {
    undoMgr.pushState({ v: 1 });
    undoMgr.pushState({ v: 2 });
    undoMgr.pushState({ v: 3 });

    undoMgr.undo(2);
    expect(undoMgr.canRedo()).toBe(true);
    expect(undoMgr.getRedoCount()).toBe(2);

    undoMgr.pushState({ v: 100 });
    expect(undoMgr.canRedo()).toBe(false);
    expect(undoMgr.getCurrentState()).toEqual({ v: 100 });
  });

  it('超过maxSize 时裁剪快照', () => {
    const smallUndo = new UndoManager(3);
    for (let i = 0; i < 10; i++) {
      smallUndo.pushState({ i });
    }

    expect(smallUndo.getTotalSnapshots()).toBe(3);
    expect(smallUndo.getUndoCount()).toBe(3);
  });

  it('getHistory 返回完整历史', () => {
    undoMgr.pushState({ step: 1 });
    undoMgr.pushState({ step: 2 });
    undoMgr.pushState({ step: 3 });

    const history = undoMgr.getHistory();
    expect(history.length).toBe(3);
    expect(history[0].isCurrent).toBe(false);
    expect(history[2].isCurrent).toBe(true);
    expect(history[2].snapshot.state).toEqual({ step: 3 });
  });
});

describe('StateRebuilder', () => {
  let rebuilder: StateRebuilder;
  let events: GameEvent[];

  beforeEach(() => {
    rebuilder = new StateRebuilder();
    const store = new EventStore({ enableSnapshots: false });
    store.append({
      type: 'GAME_START',
      turnNumber: 1,
      data: {},
      metadata: {},
    });
    store.append({
      type: 'UNIT_SPAWN',
      turnNumber: 1,
      data: { unitId: 'u1', maxHp: 100 },
      metadata: { position: { q: 0, r: 0, s: 0 }, faction: 'player' },
    });
    store.append({
      type: 'UNIT_SPAWN',
      turnNumber: 1,
      data: { unitId: 'e1', maxHp: 80 },
      metadata: { position: { q: 2, r: 0, s: -2 }, faction: 'enemy' },
    });
    store.append({
      type: 'UNIT_ATTACK',
      turnNumber: 1,
      data: {
        attackerId: 'u1',
        defenderId: 'e1',
        isHit: true,
        isCrit: false,
        damage: { baseDamage: 25, finalDamage: 20 },
      },
      metadata: {},
    });
    store.append({
      type: 'HEAL_APPLIED',
      turnNumber: 1,
      data: { targetId: 'e1', amount: 5 },
      metadata: { source: 'e1_heal' },
    });
    store.append({
      type: 'TURN_START',
      turnNumber: 2,
      data: {},
      metadata: {},
    });
    store.append({
      type: 'UNIT_DEATH',
      turnNumber: 2,
      data: { unitId: 'e1', killerId: 'u1', position: { q: 2, r: 0, s: -2 } },
      metadata: {},
    });
    store.append({
      type: 'TURN_END',
      turnNumber: 2,
      data: {},
      metadata: {},
    });
    events = store.getEvents();
  });

  it('rebuild 从0到指定索引重建状态', () => {
    const result = rebuilder.rebuild(events, 3);

    expect(result.lastEventIndex).toBe(3);
    expect(result.snapshotUsed).toBeNull();
    const state = result.state as Record<string, unknown>;
    expect(state.units).toBeDefined();
    const units = state.units as Record<string, Record<string, unknown>>;
    expect(units['u1']).toBeDefined();
    expect(units['e1']).toBeDefined();
    expect(units['e1'].hp).toBe(60);
  });

  it('applyEvent 逐事件还原正确计算伤害', () => {
    let state: Record<string, unknown> = {};

    for (const e of events.slice(0, 4)) {
      state = rebuilder.applyEvent(state, e);
    }

    const units = state.units as Record<string, Record<string, unknown>>;
    expect(units['e1'].hp).toBe(60);
  });

  it('applyEvent 治疗和死亡事件', () => {
    let state: Record<string, unknown> = {};
    for (const e of events.slice(0, 5)) {
      state = rebuilder.applyEvent(state, e);
    }
    let units = state.units as Record<string, Record<string, unknown>>;
    expect(units['e1'].hp).toBe(65);

    for (const e of events.slice(5)) {
      state = rebuilder.applyEvent(state, e);
    }
    units = state.units as Record<string, Record<string, unknown>>;
    expect(units['e1'].isAlive).toBe(false);
    expect(units['e1'].hp).toBe(0);
    expect(units['e1'].killedBy).toBe('u1');
  });

  it('rebuild 使用快照加速重建', () => {
    const midState = { turn: 1, units: { u1: { hp: 100 }, e1: { hp: 80 } } };
    const snap: GameStateSnapshot = {
      id: 'snap_mid',
      eventId: events[2].id,
      eventIndex: 2,
      turnNumber: 1,
      state: midState,
      timestamp: Date.now(),
      checksum: createChecksum(JSON.stringify(midState)),
    };

    const result = rebuilder.rebuild(events, 6, {}, [snap]);

    expect(result.snapshotUsed).toBe(snap);
    expect(result.lastEventIndex).toBe(6);
    const state = result.state as Record<string, unknown>;
    const units = state.units as Record<string, Record<string, unknown>>;
    expect(units['e1'].isAlive).toBe(false);
  });

  it('validateState 校验和验证', () => {
    const state = { x: 1, y: 2 };
    const checksum = createChecksum(JSON.stringify(state));

    const valid = rebuilder.validateState(state, checksum);
    expect(valid.valid).toBe(true);
    expect(valid.actualChecksum).toBe(checksum);

    const invalid = rebuilder.validateState(state, 'wrong_checksum');
    expect(invalid.valid).toBe(false);
  });

  it('默认reducer 记录事件日志', () => {
    let state = rebuilder.applyEvent({}, events[0]);
    state = rebuilder.applyEvent(state, events[1]);
    const s = state as Record<string, unknown>;

    expect(Array.isArray(s.eventLog)).toBe(true);
    expect((s.eventLog as unknown[]).length).toBe(2);
    expect(s.currentTurn).toBe(1);
    expect(s.lastEventType).toBe('UNIT_SPAWN');
  });
});
