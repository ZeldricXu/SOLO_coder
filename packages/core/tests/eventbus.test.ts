import { describe, it, expect, vi, beforeEach } from 'vitest';
import { EventBus, EventStore } from '../src/events';
import type {
  EventType,
  GameEvent,
  EventSubscriber,
  EventPayloadMap,
  EventData,
} from '../src/types';

describe('EventBus - 基本发布/订阅', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('subscribe 后 publish 能正确接收到事件', () => {
    const handler = vi.fn();
    bus.subscribe('DAMAGE_DEALT', handler);

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('接收到的 event.data 内容正确', () => {
    const handler = vi.fn();
    const data = { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } };
    bus.subscribe('DAMAGE_DEALT', handler);

    bus.publish('DAMAGE_DEALT', data);

    expect(handler).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'DAMAGE_DEALT',
        data: expect.objectContaining(data),
      })
    );
  });

  it('unsubscribe 后再次发布 handler 不被调用', () => {
    const handler = vi.fn();
    const subscriberId = bus.subscribe('DAMAGE_DEALT', handler);

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });
    expect(handler).toHaveBeenCalledTimes(1);

    bus.unsubscribe(subscriberId);
    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });

    expect(handler).toHaveBeenCalledTimes(1);
  });
});

describe('EventBus - 多订阅者与优先级', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('三个订阅者都能接收到事件', () => {
    const handler1 = vi.fn();
    const handler2 = vi.fn();
    const handler3 = vi.fn();

    bus.subscribe('DAMAGE_DEALT', handler1, { priority: 10 });
    bus.subscribe('DAMAGE_DEALT', handler2, { priority: 50 });
    bus.subscribe('DAMAGE_DEALT', handler3, { priority: 100 });

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });

    expect(handler1).toHaveBeenCalledTimes(1);
    expect(handler2).toHaveBeenCalledTimes(1);
    expect(handler3).toHaveBeenCalledTimes(1);
  });

  it('调用顺序按优先级从高到低（100 → 50 → 10）', () => {
    const callOrder: number[] = [];

    bus.subscribe('DAMAGE_DEALT', () => callOrder.push(10), { priority: 10 });
    bus.subscribe('DAMAGE_DEALT', () => callOrder.push(50), { priority: 50 });
    bus.subscribe('DAMAGE_DEALT', () => callOrder.push(100), { priority: 100 });

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });

    expect(callOrder).toEqual([100, 50, 10]);
  });
});

describe('EventBus - 一次性订阅 once', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('once 订阅后发布 3 次只调用 1 次', () => {
    const handler = vi.fn();
    bus.once('UNIT_DEATH', handler);

    bus.publish('UNIT_DEATH', { unitId: 'u1', position: { q: 0, r: 0, s: 0 }, effectsOnDeath: [] });
    bus.publish('UNIT_DEATH', { unitId: 'u2', position: { q: 1, r: 0, s: -1 }, effectsOnDeath: [] });
    bus.publish('UNIT_DEATH', { unitId: 'u3', position: { q: 2, r: 0, s: -2 }, effectsOnDeath: [] });

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('once 第一次调用后自动取消订阅', () => {
    const handler = vi.fn();
    const subscriberId = bus.once('UNIT_DEATH', handler);

    bus.publish('UNIT_DEATH', { unitId: 'u1', position: { q: 0, r: 0, s: 0 }, effectsOnDeath: [] });
    expect(handler).toHaveBeenCalledTimes(1);

    const result = bus.unsubscribe(subscriberId);
    expect(result).toBe(false);
  });
});

describe('EventBus - 事件过滤器', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('按 types 过滤，只接收指定类型事件', () => {
    const handler = vi.fn();
    bus.subscribe({ types: ['DAMAGE_DEALT', 'HEAL_APPLIED'] }, handler);

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });
    expect(handler).toHaveBeenCalledTimes(1);

    bus.publish('UNIT_MOVE', { unitId: 'u1', from: { q: 0, r: 0, s: 0 }, to: { q: 1, r: 0, s: -1 }, path: [], moveCost: 1 });
    expect(handler).toHaveBeenCalledTimes(1);

    bus.publish('HEAL_APPLIED', { sourceId: 's1', targetId: 't1', heal: { baseHeal: 10, finalHeal: 8 } });
    expect(handler).toHaveBeenCalledTimes(2);
  });

  it('按 source 过滤事件', () => {
    const handler = vi.fn();
    bus.subscribe({ types: ['DAMAGE_DEALT'], sources: ['source_a'] }, handler);

    bus.publish('DAMAGE_DEALT', { sourceId: 'source_a', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } }, { source: 'source_a' });
    expect(handler).toHaveBeenCalledTimes(1);

    bus.publish('DAMAGE_DEALT', { sourceId: 'source_b', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } }, { source: 'source_b' });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('按 target 过滤事件', () => {
    const handler = vi.fn();
    bus.subscribe({ types: ['DAMAGE_DEALT'], targets: ['target_x'] }, handler);

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 'target_x', damage: { baseDamage: 10, finalDamage: 8 } }, { target: 'target_x' });
    expect(handler).toHaveBeenCalledTimes(1);

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 'target_y', damage: { baseDamage: 10, finalDamage: 8 } }, { target: 'target_y' });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('按 faction 过滤事件', () => {
    const handler = vi.fn();
    bus.subscribe({ types: ['UNIT_DEATH'], factions: ['player'] }, handler);

    bus.publish('UNIT_DEATH', { unitId: 'u1', position: { q: 0, r: 0, s: 0 }, effectsOnDeath: [] }, { faction: 'player' });
    expect(handler).toHaveBeenCalledTimes(1);

    bus.publish('UNIT_DEATH', { unitId: 'e1', position: { q: 1, r: 0, s: -1 }, effectsOnDeath: [] }, { faction: 'enemy' });
    expect(handler).toHaveBeenCalledTimes(1);
  });
});

describe('EventBus - 通配符订阅', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('订阅 "*" 能接收所有类型的事件', () => {
    const handler = vi.fn();
    bus.subscribe('*', handler);

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });
    bus.publish('HEAL_APPLIED', { sourceId: 's1', targetId: 't1', heal: { baseHeal: 10, finalHeal: 8 } });
    bus.publish('UNIT_MOVE', { unitId: 'u1', from: { q: 0, r: 0, s: 0 }, to: { q: 1, r: 0, s: -1 }, path: [], moveCost: 1 });
    bus.publish('UNIT_DEATH', { unitId: 'u1', position: { q: 0, r: 0, s: 0 }, effectsOnDeath: [] });
    bus.publish('TURN_START', { unitId: 'u1', turnNumber: 1 });

    expect(handler).toHaveBeenCalledTimes(5);
  });

  it('通配符订阅与特定类型订阅都能收到事件', () => {
    const wildcardHandler = vi.fn();
    const specificHandler = vi.fn();

    bus.subscribe('*', wildcardHandler);
    bus.subscribe('DAMAGE_DEALT', specificHandler);

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });

    expect(wildcardHandler).toHaveBeenCalledTimes(1);
    expect(specificHandler).toHaveBeenCalledTimes(1);
  });
});

describe('EventBus - 事件分组', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('unsubscribeGroup 后组内所有订阅者都不再接收事件', () => {
    const handler1 = vi.fn();
    const handler2 = vi.fn();
    const handler3 = vi.fn();

    bus.subscribe('DAMAGE_DEALT', handler1, { group: 'ui' });
    bus.subscribe('HEAL_APPLIED', handler2, { group: 'ui' });
    bus.subscribe('UNIT_DEATH', handler3, { group: 'ui' });

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });
    expect(handler1).toHaveBeenCalledTimes(1);

    bus.unsubscribeGroup('ui');

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });
    bus.publish('HEAL_APPLIED', { sourceId: 's1', targetId: 't1', heal: { baseHeal: 10, finalHeal: 8 } });
    bus.publish('UNIT_DEATH', { unitId: 'u1', position: { q: 0, r: 0, s: 0 }, effectsOnDeath: [] });

    expect(handler1).toHaveBeenCalledTimes(1);
    expect(handler2).toHaveBeenCalledTimes(0);
    expect(handler3).toHaveBeenCalledTimes(0);
  });

  it('其他组的订阅不受影响', () => {
    const uiHandler = vi.fn();
    const combatHandler = vi.fn();

    bus.subscribe('DAMAGE_DEALT', uiHandler, { group: 'ui' });
    bus.subscribe('DAMAGE_DEALT', combatHandler, { group: 'combat' });

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });
    expect(uiHandler).toHaveBeenCalledTimes(1);
    expect(combatHandler).toHaveBeenCalledTimes(1);

    bus.unsubscribeGroup('ui');

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });

    expect(uiHandler).toHaveBeenCalledTimes(1);
    expect(combatHandler).toHaveBeenCalledTimes(2);
  });
});

describe('EventBus - 暂停/恢复', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('pause 后发布事件 handler 不被调用', () => {
    const handler = vi.fn();
    bus.subscribe('DAMAGE_DEALT', handler);

    bus.pause();
    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });

    expect(handler).toHaveBeenCalledTimes(0);
  });

  it('resume 后发布事件 handler 正常调用', () => {
    const handler = vi.fn();
    bus.subscribe('DAMAGE_DEALT', handler);

    bus.pause();
    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });
    expect(handler).toHaveBeenCalledTimes(0);

    bus.resume();
    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('暂停期间的事件不缓存，直接丢弃', () => {
    const handler = vi.fn();
    bus.subscribe('DAMAGE_DEALT', handler);

    bus.pause();
    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });
    bus.publish('DAMAGE_DEALT', { sourceId: 's2', targetId: 't2', damage: { baseDamage: 20, finalDamage: 15 } });

    bus.resume();
    expect(handler).toHaveBeenCalledTimes(0);
  });
});

describe('EventBus - EventStore 双向同步', () => {
  let bus: EventBus;
  let store: EventStore;

  beforeEach(() => {
    bus = new EventBus();
    store = new EventStore({ enableSnapshots: false });
    bus.setEventStore(store);
    store.setEventBus(bus);
  });

  it('通过 bus.publish 发布的事件能在 eventStore 中查询到', () => {
    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });

    const events = store.query({ types: ['DAMAGE_DEALT'] });
    expect(events.length).toBe(1);
    expect(events[0].type).toBe('DAMAGE_DEALT');
  });

  it('通过 eventStore.append 追加的事件 bus 订阅者能收到', () => {
    const handler = vi.fn();
    bus.subscribe('UNIT_DEATH', handler);

    store.append({
      type: 'UNIT_DEATH',
      turnNumber: 1,
      data: { unitId: 'u1', position: { q: 0, r: 0, s: 0 }, effectsOnDeath: [] },
      metadata: {},
    });

    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('双向同步不会出现死循环', () => {
    const busHandler = vi.fn();
    bus.subscribe('DAMAGE_DEALT', busHandler);

    bus.publish('DAMAGE_DEALT', { sourceId: 's1', targetId: 't1', damage: { baseDamage: 10, finalDamage: 8 } });

    expect(busHandler).toHaveBeenCalledTimes(1);
    expect(store.getEventCount()).toBe(1);

    bus.publish('DAMAGE_DEALT', { sourceId: 's2', targetId: 't2', damage: { baseDamage: 20, finalDamage: 15 } });

    expect(busHandler).toHaveBeenCalledTimes(2);
    expect(store.getEventCount()).toBe(2);
  });

  it('append 5 个事件，bus 收到 5 次，store 里有 5 个', () => {
    const handler = vi.fn();
    bus.subscribe('*', handler);

    for (let i = 0; i < 5; i++) {
      store.append({
        type: 'CUSTOM',
        turnNumber: 1,
        data: { index: i },
        metadata: {},
      });
    }

    expect(handler).toHaveBeenCalledTimes(5);
    expect(store.getEventCount()).toBe(5);
  });
});

describe('EventBus - waitFor Promise', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('waitFor 在事件发布后 resolve，且接收到正确事件', async () => {
    const promise = bus.waitFor('GAME_START');

    setTimeout(() => {
      bus.publish('GAME_START', {});
    }, 10);

    const event = await promise;

    expect(event.type).toBe('GAME_START');
    expect(event).toBeDefined();
  });

  it('带 timeout 的 waitFor 超时后 reject', async () => {
    const promise = bus.waitFor('GAME_START', 10);

    await expect(promise).rejects.toThrow('Timeout waiting for event: GAME_START');
  });
});

describe('EventBus - 类型安全', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('UNIT_MOVE 事件 data 包含 unitId, from, to 字段', () => {
    let receivedData: EventData<'UNIT_MOVE'> | null = null;

    bus.subscribe('UNIT_MOVE', (event) => {
      receivedData = event.data;
    });

    bus.publish('UNIT_MOVE', {
      unitId: 'u1',
      from: { q: 0, r: 0, s: 0 },
      to: { q: 1, r: 0, s: -1 },
      path: [],
      moveCost: 1,
    });

    expect(receivedData).not.toBeNull();
    expect(typeof receivedData!.unitId).toBe('string');
    expect(receivedData!.unitId).toBe('u1');
    expect(receivedData!.from).toBeDefined();
    expect(receivedData!.from.q).toBe(0);
    expect(receivedData!.to).toBeDefined();
    expect(receivedData!.to.q).toBe(1);
  });

  it('DAMAGE_DEALT 事件 data 包含 sourceId, targetId, damage 字段', () => {
    let receivedData: EventData<'DAMAGE_DEALT'> | null = null;

    bus.subscribe('DAMAGE_DEALT', (event) => {
      receivedData = event.data;
    });

    bus.publish('DAMAGE_DEALT', {
      sourceId: 's1',
      targetId: 't1',
      damage: { baseDamage: 10, finalDamage: 8 },
    });

    expect(receivedData).not.toBeNull();
    expect(typeof receivedData!.sourceId).toBe('string');
    expect(receivedData!.sourceId).toBe('s1');
    expect(typeof receivedData!.targetId).toBe('string');
    expect(receivedData!.targetId).toBe('t1');
    expect(receivedData!.damage).toBeDefined();
    expect(receivedData!.damage.baseDamage).toBe(10);
    expect(receivedData!.damage.finalDamage).toBe(8);
  });
});

describe('EventBus - getSubscriberCount 与 clear', () => {
  let bus: EventBus;

  beforeEach(() => {
    bus = new EventBus();
  });

  it('getSubscriberCount 返回总订阅者数量', () => {
    bus.subscribe('DAMAGE_DEALT', vi.fn());
    bus.subscribe('DAMAGE_DEALT', vi.fn());
    bus.subscribe('HEAL_APPLIED', vi.fn());
    bus.subscribe('UNIT_MOVE', vi.fn());
    bus.subscribe('UNIT_DEATH', vi.fn());
    bus.subscribe('UNIT_DEATH', vi.fn());
    bus.subscribe('TURN_START', vi.fn());
    bus.subscribe('TURN_END', vi.fn());
    bus.subscribe('GAME_START', vi.fn());
    bus.subscribe('GAME_END', vi.fn());

    expect(bus.getSubscriberCount()).toBe(10);
  });

  it('getSubscriberCount 按事件类型返回对应数量', () => {
    bus.subscribe('UNIT_DEATH', vi.fn());
    bus.subscribe('UNIT_DEATH', vi.fn());
    bus.subscribe('DAMAGE_DEALT', vi.fn());
    bus.subscribe('HEAL_APPLIED', vi.fn());

    expect(bus.getSubscriberCount('UNIT_DEATH')).toBe(2);
    expect(bus.getSubscriberCount('DAMAGE_DEALT')).toBe(1);
    expect(bus.getSubscriberCount('HEAL_APPLIED')).toBe(1);
    expect(bus.getSubscriberCount('UNIT_MOVE')).toBe(0);
  });

  it('clear 后 getSubscriberCount 返回 0', () => {
    bus.subscribe('DAMAGE_DEALT', vi.fn());
    bus.subscribe('HEAL_APPLIED', vi.fn());
    bus.subscribe('UNIT_MOVE', vi.fn());

    expect(bus.getSubscriberCount()).toBe(3);

    bus.clear();

    expect(bus.getSubscriberCount()).toBe(0);
    expect(bus.getSubscriberCount('DAMAGE_DEALT')).toBe(0);
  });
});
