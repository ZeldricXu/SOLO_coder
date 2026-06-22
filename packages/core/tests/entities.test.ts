import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  HexGrid,
  Pathfinder,
  FieldOfViewCalculator,
  cubeCoords,
  cubeKey,
} from '../src/grid';
import {
  BaseMapEntity,
  ChestEntityClass,
  MechanismEntityClass,
  DestructibleEntityClass,
  PortalEntityClass,
  MapEntityManager,
} from '../src/entities';
import type {
  MapEntity,
  EntityType,
  EntityState,
  EntityCategory,
  LootItem,
} from '../src/types/entities';
import type { CubeCoords, Faction } from '../src/types';
import { createEmptyGrid, createUnit } from './factories';

describe('Test 1: 实体基础属性与序列化', () => {
  let chest: ChestEntityClass;
  let mechanism: MechanismEntityClass;
  let destructible: DestructibleEntityClass;
  let portal: PortalEntityClass;
  let pos: CubeCoords;

  beforeEach(() => {
    pos = cubeCoords(0, 0, 0);
    chest = new ChestEntityClass('宝箱1', pos, {
      loot: [{ itemId: 'sword', quantity: 1, dropRate: 1.0 }],
      gold: 100,
    });
    mechanism = new MechanismEntityClass(
      '机关1',
      cubeCoords(1, 0, -1),
      'switch',
      'toggle_terrain',
      { targetCoords: [], fromTerrain: 'plain', toTerrain: 'mountain' }
    );
    destructible = new DestructibleEntityClass(
      '木箱',
      cubeCoords(2, -1, -1),
      50,
      { defense: 5 }
    );
    portal = new PortalEntityClass(
      '传送门A',
      cubeCoords(3, -2, -1),
      'portal-b'
    );
  });

  it('宝箱实体基础属性正确', () => {
    expect(chest.type).toBe('chest');
    expect(chest.category).toBe('interactive');
    expect(chest.state).toBe('closed');
    expect(chest.blocksMovement).toBe(true);
    expect(chest.blocksVision).toBe(false);
    expect(chest.isInteractable).toBe(true);
    expect(chest.name).toBe('宝箱1');
  });

  it('机关实体基础属性正确', () => {
    expect(mechanism.type).toBe('mechanism');
    expect(mechanism.category).toBe('interactive');
    expect(mechanism.state).toBe('idle');
    expect(mechanism.blocksMovement).toBe(false);
    expect(mechanism.isInteractable).toBe(true);
    expect(mechanism.mechanismType).toBe('switch');
    expect(mechanism.effectType).toBe('toggle_terrain');
  });

  it('可破坏物实体基础属性正确', () => {
    expect(destructible.type).toBe('destructible');
    expect(destructible.category).toBe('obstacle');
    expect(destructible.state).toBe('idle');
    expect(destructible.blocksMovement).toBe(true);
    expect(destructible.blocksVision).toBe(false);
    expect(destructible.isInteractable).toBe(false);
    expect(destructible.maxHp).toBe(50);
    expect(destructible.hp).toBe(50);
    expect(destructible.defense).toBe(5);
  });

  it('传送门实体基础属性正确', () => {
    expect(portal.type).toBe('portal');
    expect(portal.category).toBe('interactive');
    expect(portal.state).toBe('active');
    expect(portal.blocksMovement).toBe(false);
    expect(portal.blocksVision).toBe(false);
    expect(portal.isInteractable).toBe(true);
    expect(portal.portalPair).toBe('portal-b');
    expect(portal.isOneWay).toBe(false);
  });

  it('所有实体 id 唯一', () => {
    const ids = [chest.id, mechanism.id, destructible.id, portal.id];
    const uniqueIds = new Set(ids);
    expect(uniqueIds.size).toBe(ids.length);
  });

  it('宝箱 toJSON/fromJSON 往返一致', () => {
    const json = chest.toJSON();
    const newChest = ChestEntityClass.fromJSON(json);
    expect(newChest.toJSON()).toEqual(json);
  });

  it('机关 toJSON/fromJSON 往返一致', () => {
    const json = mechanism.toJSON();
    const newMechanism = MechanismEntityClass.fromJSON(json);
    expect(newMechanism.toJSON()).toEqual(json);
  });

  it('可破坏物 toJSON/fromJSON 往返一致', () => {
    const json = destructible.toJSON();
    const newDestructible = DestructibleEntityClass.fromJSON(json);
    expect(newDestructible.toJSON()).toEqual(json);
  });

  it('传送门 toJSON/fromJSON 往返一致', () => {
    const json = portal.toJSON();
    const newPortal = PortalEntityClass.fromJSON(json);
    expect(newPortal.toJSON()).toEqual(json);
  });
});

describe('Test 2: 宝箱 ChestEntity', () => {
  let chest: ChestEntityClass;
  let loot: LootItem[];

  beforeEach(() => {
    loot = [
      { itemId: 'sword', quantity: 1, dropRate: 1.0 },
      { itemId: 'gold_coin', quantity: 50, dropRate: 1.0 },
      { itemId: 'rare_gem', quantity: 1, dropRate: 0.0 },
    ];
    chest = new ChestEntityClass('测试宝箱', cubeCoords(2, 1, -3), {
      loot,
      gold: 100,
    });
  });

  it('初始状态为关闭', () => {
    expect(chest.isOpen).toBe(false);
    expect(chest.state).toBe('closed');
    expect(chest.blocksMovement).toBe(true);
  });

  it('open() 后状态变为打开', () => {
    const result = chest.open();
    expect(result.success).toBe(true);
    expect(chest.isOpen).toBe(true);
    expect(chest.state).toBe('open');
    expect(chest.blocksMovement).toBe(false);
  });

  it('再次调用 open() 不重复掉落', () => {
    chest.open();
    const result = chest.open();
    expect(result.success).toBe(false);
    expect(result.message).toBe('Chest is already open');
    expect(result.loot.length).toBe(0);
    expect(result.gold).toBe(0);
  });

  it('100% 掉落的物品一定在结果中', () => {
    const result = chest.open();
    expect(result.success).toBe(true);
    const droppedItemIds = result.loot.map(item => item.itemId);
    expect(droppedItemIds).toContain('sword');
    expect(droppedItemIds).toContain('gold_coin');
  });

  it('0% 掉落的物品不在结果中', () => {
    const result = chest.open();
    const droppedItemIds = result.loot.map(item => item.itemId);
    expect(droppedItemIds).not.toContain('rare_gem');
  });

  it('打开宝箱返回金币数量正确', () => {
    const result = chest.open();
    expect(result.gold).toBe(100);
  });

  it('带钥匙需求的宝箱没有钥匙打不开', () => {
    const keyChest = new ChestEntityClass('上锁宝箱', cubeCoords(0, 0, 0), {
      keyRequired: 'golden_key',
      loot: [{ itemId: 'treasure', quantity: 1, dropRate: 1.0 }],
    });
    const result = keyChest.open(false);
    expect(result.success).toBe(false);
    expect(result.message).toBe('Key required');
    expect(keyChest.isOpen).toBe(false);
  });

  it('带钥匙需求的宝箱有钥匙能打开', () => {
    const keyChest = new ChestEntityClass('上锁宝箱', cubeCoords(0, 0, 0), {
      keyRequired: 'golden_key',
      loot: [{ itemId: 'treasure', quantity: 1, dropRate: 1.0 }],
    });
    const result = keyChest.open(true);
    expect(result.success).toBe(true);
    expect(keyChest.isOpen).toBe(true);
  });

  it('阵营限制：敌方打不开非己方阵营宝箱', () => {
    const playerChest = new ChestEntityClass('玩家宝箱', cubeCoords(0, 0, 0), {
      faction: 'player',
      loot: [{ itemId: 'item', quantity: 1, dropRate: 1.0 }],
    });
    expect(playerChest.canInteract('enemy')).toBe(false);
    expect(playerChest.canInteract('player')).toBe(true);
  });

  it('close() 能关闭已打开的宝箱', () => {
    chest.open();
    expect(chest.isOpen).toBe(true);
    const result = chest.close();
    expect(result).toBe(true);
    expect(chest.isOpen).toBe(false);
    expect(chest.state).toBe('closed');
    expect(chest.blocksMovement).toBe(true);
  });
});

describe('Test 3: 机关 MechanismEntity', () => {
  let mechanism: MechanismEntityClass;

  beforeEach(() => {
    mechanism = new MechanismEntityClass(
      '开关',
      cubeCoords(0, 0, 0),
      'switch',
      'toggle_terrain',
      { targetCoords: [cubeCoords(1, 0, -1)], fromTerrain: 'plain', toTerrain: 'mountain' }
    );
  });

  it('初始 isActive 为 false', () => {
    expect(mechanism.isActive).toBe(false);
    expect(mechanism.state).toBe('idle');
    expect(mechanism.activationCount).toBe(0);
  });

  it('trigger() 后 isActive 变为 true', () => {
    const result = mechanism.trigger();
    expect(result.success).toBe(true);
    expect(mechanism.isActive).toBe(true);
    expect(mechanism.state).toBe('active');
    expect(mechanism.activationCount).toBe(1);
  });

  it('再次 trigger() 后 isActive 变回 false', () => {
    mechanism.trigger();
    expect(mechanism.isActive).toBe(true);
    const result = mechanism.trigger();
    expect(result.success).toBe(true);
    expect(mechanism.isActive).toBe(false);
    expect(mechanism.state).toBe('idle');
    expect(mechanism.activationCount).toBe(2);
  });

  it('trigger 返回 effectData', () => {
    const result = mechanism.trigger();
    expect(result.success).toBe(true);
    expect(result.effects.length).toBeGreaterThan(0);
    expect(result.effects[0].type).toBe('toggle_terrain');
  });

  it('maxActivations=1 的机关第二次触发失败', () => {
    const singleUse = new MechanismEntityClass(
      '一次性机关',
      cubeCoords(0, 0, 0),
      'lever',
      'spawn_entity',
      { entityType: 'chest', position: cubeCoords(1, 0, -1), count: 1 },
      { maxActivations: 1 }
    );

    const first = singleUse.trigger();
    expect(first.success).toBe(true);

    const second = singleUse.trigger();
    expect(second.success).toBe(false);
    expect(second.message).toBe('Cannot trigger mechanism');
    expect(singleUse.activationCount).toBe(1);
  });

  it('带阵营限制的机关非指定阵营触发失败', () => {
    const factionMechanism = new MechanismEntityClass(
      '阵营机关',
      cubeCoords(0, 0, 0),
      'switch',
      'toggle_terrain',
      {},
      { triggerFactions: ['player'] }
    );

    const enemyResult = factionMechanism.trigger('enemy');
    expect(enemyResult.success).toBe(false);
    expect(factionMechanism.isActive).toBe(false);

    const playerResult = factionMechanism.trigger('player');
    expect(playerResult.success).toBe(true);
    expect(factionMechanism.isActive).toBe(true);
  });

  it('冷却中的机关不能触发', () => {
    const cooldownMech = new MechanismEntityClass(
      '冷却机关',
      cubeCoords(0, 0, 0),
      'switch',
      'toggle_terrain',
      {},
      { interactCooldown: 3 }
    );

    const first = cooldownMech.trigger();
    expect(first.success).toBe(true);
    expect(cooldownMech.currentCooldown).toBe(3);

    const second = cooldownMech.trigger();
    expect(second.success).toBe(false);
  });

  it('tickCooldown 减少冷却时间', () => {
    mechanism.interactCooldown = 3;
    mechanism.trigger();
    expect(mechanism.currentCooldown).toBe(3);

    mechanism.tickCooldown();
    expect(mechanism.currentCooldown).toBe(2);

    mechanism.tickCooldown();
    expect(mechanism.currentCooldown).toBe(1);

    mechanism.tickCooldown();
    expect(mechanism.currentCooldown).toBe(0);
  });
});

describe('Test 4: 机关联动地形变化', () => {
  let grid: HexGrid;
  let manager: MapEntityManager;
  let mechanism: MechanismEntityClass;
  let targetCoords: CubeCoords[];

  beforeEach(() => {
    grid = createEmptyGrid(10, 10);
    manager = new MapEntityManager();
    manager.setGrid(grid);

    targetCoords = [
      cubeCoords(2, 1, -3),
      cubeCoords(3, 1, -4),
      cubeCoords(4, 1, -5),
    ];

    mechanism = new MechanismEntityClass(
      '地形开关',
      cubeCoords(0, 0, 0),
      'switch',
      'toggle_terrain',
      {
        targetCoords,
        fromTerrain: 'plain',
        toTerrain: 'mountain',
      }
    );

    targetCoords.forEach(coords => {
      grid.setTileTerrain(coords, 'plain');
    });
  });

  it('触发机关前地形为 plain', () => {
    for (const coords of targetCoords) {
      const tile = grid.getTile(coords);
      expect(tile?.terrain).toBe('plain');
    }
  });

  it('触发机关后地形变为 mountain', () => {
    manager.addEntity(mechanism);
    const result = manager.interactWithEntity(mechanism.id, {
      id: 'test-unit',
      position: cubeCoords(0, 0, 0),
      faction: 'player',
    });

    expect(result.success).toBe(true);

    for (const coords of targetCoords) {
      const tile = grid.getTile(coords);
      expect(tile?.terrain).toBe('mountain');
    }
  });

  it('再次触发机关地形变回 plain', () => {
    manager.addEntity(mechanism);

    manager.interactWithEntity(mechanism.id, {
      id: 'test-unit',
      position: cubeCoords(0, 0, 0),
      faction: 'player',
    });

    mechanism.currentCooldown = 0;

    manager.interactWithEntity(mechanism.id, {
      id: 'test-unit',
      position: cubeCoords(0, 0, 0),
      faction: 'player',
    });

    for (const coords of targetCoords) {
      const tile = grid.getTile(coords);
      expect(tile?.terrain).toBe('plain');
    }
  });

  it('机关联动通过 MapEntityManager 正确应用效果', () => {
    manager.addEntity(mechanism);
    const result = manager.interactWithEntity(mechanism.id, {
      id: 'unit-1',
      position: cubeCoords(0, 0, 0),
      faction: 'player',
    });

    expect(result.success).toBe(true);
    expect(result.data?.effects).toBeDefined();
    expect(Array.isArray(result.data?.effects)).toBe(true);
  });

  it('触发机关后触发 ENTITY_ACTIVATED 事件', () => {
    let eventTriggered = false;
    manager.on('ENTITY_ACTIVATED', (event) => {
      eventTriggered = true;
      expect(event.entityId).toBe(mechanism.id);
    });

    manager.addEntity(mechanism);
    manager.interactWithEntity(mechanism.id, {
      id: 'unit-1',
      position: cubeCoords(0, 0, 0),
      faction: 'player',
    });

    expect(eventTriggered).toBe(true);
  });
});

describe('Test 5: 可破坏物 DestructibleEntity', () => {
  let crate: DestructibleEntityClass;

  beforeEach(() => {
    crate = new DestructibleEntityClass('木箱', cubeCoords(0, 0, 0), 50, {
      defense: 5,
      blocksMovementWhenDestroyed: false,
      blocksVisionWhenDestroyed: false,
    });
  });

  it('初始血量满，未被摧毁', () => {
    expect(crate.hp).toBe(50);
    expect(crate.maxHp).toBe(50);
    expect(crate.isDestroyed).toBe(false);
    expect(crate.state).toBe('idle');
  });

  it('受到伤害后血量减少', () => {
    const result = crate.takeDamage(30, 'physical');
    expect(result.isDestroyed).toBe(false);
    expect(result.actualDamage).toBe(25);
    expect(crate.hp).toBe(25);
    expect(crate.isDestroyed).toBe(false);
  });

  it('防御减伤正确计算', () => {
    const result = crate.takeDamage(3, 'physical');
    expect(result.actualDamage).toBe(0);
    expect(crate.hp).toBe(50);
  });

  it('摧毁后 isDestroyed=true, state=destroyed', () => {
    const result = crate.takeDamage(100, 'physical');
    expect(result.isDestroyed).toBe(true);
    expect(crate.hp).toBe(0);
    expect(crate.isDestroyed).toBe(true);
    expect(crate.state).toBe('destroyed');
  });

  it('摧毁后 blocksMovement 变为 false', () => {
    expect(crate.blocksMovement).toBe(true);
    crate.takeDamage(100, 'physical');
    expect(crate.blocksMovement).toBe(false);
  });

  it('摧毁后 isInteractable 变为 false', () => {
    expect(crate.isInteractable).toBe(false);
    crate.takeDamage(100, 'physical');
    expect(crate.isInteractable).toBe(false);
  });

  it('摧毁后触发 ENTITY_DESTROYED 事件', () => {
    const manager = new MapEntityManager();
    const grid = createEmptyGrid(5, 5);
    manager.setGrid(grid);

    let destroyedEvent = false;
    manager.on('ENTITY_DESTROYED', (event) => {
      destroyedEvent = true;
      expect(event.entityId).toBe(crate.id);
    });

    manager.addEntity(crate);
    manager.damageEntity(crate.id, 100, 'physical');

    expect(destroyedEvent).toBe(true);
  });

  it('已摧毁的物体再受伤害无效果', () => {
    crate.takeDamage(100, 'physical');
    const result = crate.takeDamage(50, 'physical');
    expect(result.isDestroyed).toBe(true);
    expect(result.actualDamage).toBe(0);
    expect(crate.hp).toBe(0);
  });

  it('heal() 能恢复血量', () => {
    crate.takeDamage(30, 'physical');
    expect(crate.hp).toBe(25);
    const healed = crate.heal(10);
    expect(healed).toBe(10);
    expect(crate.hp).toBe(35);
  });

  it('getHpPercent() 返回正确的百分比', () => {
    expect(crate.getHpPercent()).toBeCloseTo(1.0);
    crate.takeDamage(30, 'physical');
    expect(crate.getHpPercent()).toBeCloseTo(0.5);
  });
});

describe('Test 6: 传送门 PortalEntity', () => {
  let portalA: PortalEntityClass;
  let portalB: PortalEntityClass;

  beforeEach(() => {
    portalA = new PortalEntityClass(
      '传送门A',
      cubeCoords(0, 0, 0),
      'portal-b',
      { cooldownPerUse: 2 }
    );
    portalB = new PortalEntityClass(
      '传送门B',
      cubeCoords(5, 0, -5),
      'portal-a',
      { cooldownPerUse: 2 }
    );
    portalA.id = 'portal-a';
    portalB.id = 'portal-b';
  });

  it('初始冷却为 0', () => {
    expect(portalA.currentCooldown).toBe(0);
    expect(portalB.currentCooldown).toBe(0);
  });

  it('teleport 返回目标传送门位置', () => {
    const unit = { id: 'unit-1', coords: cubeCoords(0, 0, 0), faction: 'player' as Faction };
    const result = portalA.teleport(unit, portalB);
    expect(result.success).toBe(true);
    expect(result.destination).toEqual(cubeCoords(5, 0, -5));
    expect(result.targetPortalId).toBe('portal-b');
  });

  it('传送后两者进入冷却', () => {
    const unit = { id: 'unit-1', coords: cubeCoords(0, 0, 0), faction: 'player' as Faction };
    portalA.teleport(unit, portalB);
    expect(portalA.currentCooldown).toBe(2);
    expect(portalB.currentCooldown).toBe(2);
  });

  it('冷却中传送失败返回 null', () => {
    const unit = { id: 'unit-1', coords: cubeCoords(0, 0, 0), faction: 'player' as Faction };
    portalA.teleport(unit, portalB);

    const result = portalA.teleport(unit, portalB);
    expect(result.success).toBe(false);
    expect(result.destination).toBeUndefined();
  });

  it('单向传送门：A→B 可以，B→A 不行', () => {
    portalA.isOneWay = true;
    portalB.isOneWay = true;

    const unit = { id: 'unit-1', coords: cubeCoords(0, 0, 0), faction: 'player' as Faction };
    
    const resultA = portalA.teleport(unit, portalB);
    expect(resultA.success).toBe(false);

    const resultB = portalB.teleport(unit, portalA);
    expect(resultB.success).toBe(false);
  });

  it('单向传送门：只有入口是单向时可以传送', () => {
    portalA.isOneWay = true;
    portalB.isOneWay = false;

    const unit = { id: 'unit-1', coords: cubeCoords(0, 0, 0), faction: 'player' as Faction };

    const resultA = portalA.teleport(unit, portalB);
    expect(resultA.success).toBe(true);
  });

  it('阵营限制：非指定阵营不能传送', () => {
    const restrictedPortal = new PortalEntityClass(
      '限制传送门',
      cubeCoords(0, 0, 0),
      'portal-b',
      { factionRestriction: ['player'] }
    );

    const enemyUnit = { id: 'enemy-1', coords: cubeCoords(0, 0, 0), faction: 'enemy' as Faction };
    expect(restrictedPortal.canTeleport(enemyUnit)).toBe(false);

    const playerUnit = { id: 'player-1', coords: cubeCoords(0, 0, 0), faction: 'player' as Faction };
    expect(restrictedPortal.canTeleport(playerUnit)).toBe(true);
  });

  it('tickCooldown 减少传送门冷却', () => {
    const unit = { id: 'unit-1', coords: cubeCoords(0, 0, 0), faction: 'player' as Faction };
    portalA.teleport(unit, portalB);

    expect(portalA.currentCooldown).toBe(2);
    portalA.tickCooldown();
    expect(portalA.currentCooldown).toBe(1);
    portalA.tickCooldown();
    expect(portalA.currentCooldown).toBe(0);
  });

  it('无配对传送门时传送失败', () => {
    const unit = { id: 'unit-1', coords: cubeCoords(0, 0, 0), faction: 'player' as Faction };
    const result = portalA.teleport(unit);
    expect(result.success).toBe(false);
    expect(result.message).toBe('Paired portal not found');
  });
});

describe('Test 7: MapEntityManager 集成测试', () => {
  let manager: MapEntityManager;
  let grid: HexGrid;
  let chest1: ChestEntityClass;
  let chest2: ChestEntityClass;
  let mechanism: MechanismEntityClass;
  let destructible1: DestructibleEntityClass;
  let destructible2: DestructibleEntityClass;
  let portalA: PortalEntityClass;
  let portalB: PortalEntityClass;

  beforeEach(() => {
    grid = createEmptyGrid(8, 8);
    manager = new MapEntityManager();
    manager.setGrid(grid);

    chest1 = new ChestEntityClass('宝箱1', cubeCoords(1, 1, -2), {
      loot: [{ itemId: 'potion', quantity: 2, dropRate: 1.0 }],
    });
    chest2 = new ChestEntityClass('宝箱2', cubeCoords(2, 2, -4), {
      loot: [{ itemId: 'sword', quantity: 1, dropRate: 1.0 }],
    });

    mechanism = new MechanismEntityClass(
      '机关1',
      cubeCoords(3, 1, -4),
      'switch',
      'toggle_terrain',
      { targetCoords: [], fromTerrain: 'plain', toTerrain: 'mountain' }
    );

    destructible1 = new DestructibleEntityClass('木箱1', cubeCoords(4, 2, -6), 30);
    destructible2 = new DestructibleEntityClass('木箱2', cubeCoords(5, 3, -8), 40);

    portalA = new PortalEntityClass('传送门A', cubeCoords(0, 3, -3), 'portal-b');
    portalB = new PortalEntityClass('传送门B', cubeCoords(7, 3, -10), 'portal-a');
    portalA.id = 'portal-a';
    portalB.id = 'portal-b';
  });

  it('addEntity 和 getEntity 正常工作', () => {
    manager.addEntity(chest1);
    const entity = manager.getEntity(chest1.id);
    expect(entity).toBeDefined();
    expect(entity?.id).toBe(chest1.id);
    expect(entity?.type).toBe('chest');
  });

  it('getEntitiesByType 返回正确数量', () => {
    manager.addEntity(chest1);
    manager.addEntity(chest2);
    manager.addEntity(mechanism);
    manager.addEntity(destructible1);
    manager.addEntity(destructible2);

    const chests = manager.getEntitiesByType('chest');
    expect(chests.length).toBe(2);

    const mechanisms = manager.getEntitiesByType('mechanism');
    expect(mechanisms.length).toBe(1);

    const destructibles = manager.getEntitiesByType('destructible');
    expect(destructibles.length).toBe(2);
  });

  it('getEntitiesAtPosition 返回该格上的实体', () => {
    manager.addEntity(chest1);
    manager.addEntity(chest2);

    const entitiesAtChest1 = manager.getEntitiesAtPosition(cubeCoords(1, 1, -2));
    expect(entitiesAtChest1.length).toBe(1);
    expect(entitiesAtChest1[0].id).toBe(chest1.id);

    const entitiesAtEmpty = manager.getEntitiesAtPosition(cubeCoords(0, 0, 0));
    expect(entitiesAtEmpty.length).toBe(0);
  });

  it('removeEntity 后实体被移除', () => {
    manager.addEntity(chest1);
    expect(manager.hasEntity(chest1.id)).toBe(true);

    const result = manager.removeEntity(chest1.id);
    expect(result).toBe(true);
    expect(manager.getEntity(chest1.id)).toBeUndefined();
    expect(manager.hasEntity(chest1.id)).toBe(false);
  });

  it('tickCooldowns 减少所有实体冷却', () => {
    const cooldownChest = new ChestEntityClass('冷却宝箱', cubeCoords(0, 0, 0), {
      interactCooldown: 2,
    });
    cooldownChest.currentCooldown = 2;

    const cooldownMech = new MechanismEntityClass(
      '冷却机关',
      cubeCoords(1, 0, -1),
      'switch',
      'toggle_terrain',
      {},
      { interactCooldown: 3 }
    );
    cooldownMech.currentCooldown = 3;

    manager.addEntity(cooldownChest);
    manager.addEntity(cooldownMech);

    manager.tickCooldowns();

    expect(cooldownChest.currentCooldown).toBe(1);
    expect(cooldownMech.currentCooldown).toBe(2);
  });

  it('getAllEntities 返回所有实体', () => {
    manager.addEntity(chest1);
    manager.addEntity(chest2);
    manager.addEntity(mechanism);
    manager.addEntity(destructible1);
    manager.addEntity(destructible2);
    manager.addEntity(portalA);
    manager.addEntity(portalB);

    const all = manager.getAllEntities();
    expect(all.length).toBe(7);
  });

  it('触发机关后联动正确执行', () => {
    const targetPos = cubeCoords(4, 0, -4);
    grid.setTileTerrain(targetPos, 'plain');

    const mech = new MechanismEntityClass(
      '测试机关',
      cubeCoords(3, 0, -3),
      'switch',
      'toggle_terrain',
      { targetCoords: [targetPos], fromTerrain: 'plain', toTerrain: 'mountain' }
    );

    manager.addEntity(mech);

    expect(grid.getTerrain(targetPos)).toBe('plain');

    const result = manager.interactWithEntity(mech.id, {
      id: 'unit-1',
      position: cubeCoords(3, 0, -3),
      faction: 'player',
    });

    expect(result.success).toBe(true);
    expect(grid.getTerrain(targetPos)).toBe('mountain');
  });

  it('getEntitiesByCategory 按类别筛选', () => {
    manager.addEntity(chest1);
    manager.addEntity(mechanism);
    manager.addEntity(destructible1);
    manager.addEntity(portalA);

    const interactive = manager.getEntitiesByCategory('interactive');
    expect(interactive.length).toBe(3);

    const obstacles = manager.getEntitiesByCategory('obstacle');
    expect(obstacles.length).toBe(1);
  });

  it('blocksMovement 检测阻挡', () => {
    manager.addEntity(destructible1);
    expect(manager.blocksMovement(cubeCoords(4, 2, -6))).toBe(true);
    expect(manager.blocksMovement(cubeCoords(0, 0, 0))).toBe(false);
  });

  it('damageEntity 造成伤害并返回结果', () => {
    manager.addEntity(destructible1);
    const result = manager.damageEntity(destructible1.id, 20, 'physical');
    expect(result.success).toBe(true);
    expect(result.isDestroyed).toBe(false);
    expect(result.actualDamage).toBe(20);
  });
});

describe('Test 8: 寻路 + 实体阻挡', () => {
  let grid: HexGrid;
  let pathfinder: Pathfinder;
  let start: CubeCoords;
  let goal: CubeCoords;

  beforeEach(() => {
    grid = createEmptyGrid(10, 10);
    pathfinder = new Pathfinder(grid);
    start = cubeCoords(0, 0, 0);
    goal = cubeCoords(8, 0, -8);
  });

  it('默认情况有直线路径', () => {
    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);
    expect(result.path.length).toBeGreaterThan(0);
  });

  it('可破坏物阻挡寻路', () => {
    const blockerPos = cubeCoords(4, 0, -4);
    const blocker = new DestructibleEntityClass('阻挡木箱', blockerPos, 50, {
      blocksMovement: true,
    });
    grid.addEntity(blocker);

    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);

    const pathHasBlocker = result.path.some(
      p => p.q === blockerPos.q && p.r === blockerPos.r && p.s === blockerPos.s
    );
    expect(pathHasBlocker).toBe(false);
  });

  it('ignoreEntities=true 时可以穿过实体', () => {
    const blockerPos = cubeCoords(4, 0, -4);
    const blocker = new DestructibleEntityClass('阻挡木箱', blockerPos, 50, {
      blocksMovement: true,
    });
    grid.addEntity(blocker);

    const result = pathfinder.findPath(start, goal, Infinity, { ignoreEntities: true });
    expect(result.reachable).toBe(true);

    const pathHasBlocker = result.path.some(
      p => p.q === blockerPos.q && p.r === blockerPos.r && p.s === blockerPos.s
    );
    expect(pathHasBlocker).toBe(true);
  });

  it('摧毁可破坏物后可以穿过', () => {
    const blockerPos = cubeCoords(4, 0, -4);
    const blocker = new DestructibleEntityClass('阻挡木箱', blockerPos, 50, {
      blocksMovement: true,
      blocksMovementWhenDestroyed: false,
    });
    grid.addEntity(blocker);

    const beforeResult = pathfinder.findPath(start, goal);
    const beforePathHasBlocker = beforeResult.path.some(
      p => p.q === blockerPos.q && p.r === blockerPos.r && p.s === blockerPos.s
    );
    expect(beforePathHasBlocker).toBe(false);

    blocker.takeDamage(100, 'physical');

    pathfinder.clearCache();
    const afterResult = pathfinder.findPath(start, goal);
    const afterPathHasBlocker = afterResult.path.some(
      p => p.q === blockerPos.q && p.r === blockerPos.r && p.s === blockerPos.s
    );
    expect(afterPathHasBlocker).toBe(true);
  });

  it('视野阻挡：blocksVision=true 的实体挡住视野', () => {
    const fov = new FieldOfViewCalculator(grid);
    const viewerPos = cubeCoords(0, 0, 0);
    const targetPos = cubeCoords(6, 0, -6);

    const viewer = {
      coords: viewerPos,
      visionRange: 10,
      height: 1,
    };

    const beforeResult = fov.calculateFOV(viewer);
    const targetKey = cubeKey(targetPos);
    const beforeVisible = beforeResult.visible.has(targetKey);

    const blocker = new DestructibleEntityClass('墙', cubeCoords(3, 0, -3), 100, {
      blocksVision: true,
      blocksMovement: true,
    });
    grid.addEntity(blocker);

    const afterResult = fov.calculateFOV(viewer);
    const afterVisible = afterResult.visible.has(targetKey);

    expect(beforeVisible).toBe(true);
    expect(afterVisible).toBe(false);
  });

  it('多个实体在同一路径上都阻挡', () => {
    const pos1 = cubeCoords(2, 0, -2);
    const pos2 = cubeCoords(6, 0, -6);

    const blocker1 = new DestructibleEntityClass('木箱1', pos1, 30);
    const blocker2 = new DestructibleEntityClass('木箱2', pos2, 30);

    grid.addEntity(blocker1);
    grid.addEntity(blocker2);

    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);

    const pathCoords = result.path.map(p => cubeKey(p));
    expect(pathCoords).not.toContain(cubeKey(pos1));
    expect(pathCoords).not.toContain(cubeKey(pos2));
  });

  it('getReachableTiles 也受实体阻挡影响', () => {
    const blockerPos = cubeCoords(2, 0, -2);
    const blocker = new DestructibleEntityClass('阻挡物', blockerPos, 50);
    grid.addEntity(blocker);

    const reachable = pathfinder.getReachableTiles(start, 5);
    const blockerKey = cubeKey(blockerPos);

    expect(reachable.has(blockerKey)).toBe(false);
  });

  it('传送门不阻挡移动（blocksMovement=false）', () => {
    const portalPos = cubeCoords(4, 0, -4);
    const portal = new PortalEntityClass('传送门', portalPos, 'other-portal');
    grid.addEntity(portal);

    const result = pathfinder.findPath(start, goal);
    expect(result.reachable).toBe(true);

    const pathHasPortal = result.path.some(
      p => p.q === portalPos.q && p.r === portalPos.r && p.s === portalPos.s
    );
    expect(pathHasPortal).toBe(true);
  });
});
