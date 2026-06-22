import { BaseMapEntity } from './BaseMapEntity';
import { ChestEntityClass } from './ChestEntity';
import { MechanismEntityClass } from './MechanismEntity';
import { DestructibleEntityClass } from './DestructibleEntity';
import { PortalEntityClass } from './PortalEntity';
import type { 
  MapEntity, 
  EntityType, 
  EntityState,
  EntityCategory,
  ChestEntity,
  MechanismEntity,
  DestructibleEntity,
  PortalEntity,
  LootItem,
  EntityEventType
} from '../types/entities';
import type { ID, Faction, CubeCoords } from '../types';
import type { HexGrid } from '../grid/HexGrid';
import { cubeKey, cubeDistance } from '../grid/coords';
import { generateId } from '../utils';

export interface EntityEvent {
  type: EntityEventType;
  entityId: ID;
  data: Record<string, unknown>;
  timestamp: number;
}

export type EntityEventHandler = (event: EntityEvent) => void;

export class MapEntityManager {
  private entities: Map<ID, BaseMapEntity>;
  private grid: HexGrid | null;
  private eventHandlers: Set<EntityEventHandler>;
  private eventHistory: EntityEvent[];

  constructor() {
    this.entities = new Map();
    this.grid = null;
    this.eventHandlers = new Set();
    this.eventHistory = [];
  }

  setGrid(grid: HexGrid): void {
    this.grid = grid;
  }

  getGrid(): HexGrid | null {
    return this.grid;
  }

  addEntity(entity: BaseMapEntity): void {
    if (this.entities.has(entity.id)) {
      return;
    }
    
    this.entities.set(entity.id, entity);
    
    if (this.grid) {
      this.grid.addObject(entity.position, entity.id);
    }
    
    this.emitEvent({
      type: 'ENTITY_SPAWNED',
      entityId: entity.id,
      data: { entity: entity.toJSON() },
      timestamp: Date.now(),
    });
  }

  removeEntity(entityId: ID): boolean {
    const entity = this.entities.get(entityId);
    if (!entity) return false;
    
    if (this.grid) {
      this.grid.removeObject(entity.position, entityId);
    }
    
    this.entities.delete(entityId);
    
    this.emitEvent({
      type: 'ENTITY_REMOVED',
      entityId,
      data: { entity: entity.toJSON() },
      timestamp: Date.now(),
    });
    
    return true;
  }

  getEntity(entityId: ID): BaseMapEntity | undefined {
    return this.entities.get(entityId);
  }

  hasEntity(entityId: ID): boolean {
    return this.entities.has(entityId);
  }

  getAllEntities(): BaseMapEntity[] {
    return Array.from(this.entities.values());
  }

  getEntitiesByType(type: EntityType): BaseMapEntity[] {
    return Array.from(this.entities.values()).filter(e => e.type === type);
  }

  getEntitiesByCategory(category: EntityCategory): BaseMapEntity[] {
    return Array.from(this.entities.values()).filter(e => e.category === category);
  }

  getEntitiesAtPosition(position: CubeCoords): BaseMapEntity[] {
    const key = cubeKey(position);
    const results: BaseMapEntity[] = [];
    
    for (const entity of this.entities.values()) {
      if (cubeKey(entity.position) === key) {
        results.push(entity);
      }
    }
    
    return results;
  }

  getEntitiesByTrigger(trigger: string): BaseMapEntity[] {
    return Array.from(this.entities.values()).filter(e => e.triggers.includes(trigger));
  }

  getEntitiesInRange(center: CubeCoords, range: number): BaseMapEntity[] {
    return Array.from(this.entities.values()).filter(e => 
      cubeDistance(center, e.position) <= range
    );
  }

  getInteractableEntities(unitPosition: CubeCoords, unitFaction?: Faction): BaseMapEntity[] {
    return Array.from(this.entities.values()).filter(e => {
      if (!e.isInteractable || e.isDestroyed) return false;
      if (e.currentCooldown > 0) return false;
      if (e.faction && unitFaction && e.faction !== unitFaction) return false;
      
      const distance = cubeDistance(unitPosition, e.position);
      return distance <= e.interactRange;
    });
  }

  interactWithEntity(
    entityId: ID, 
    interactor: { id: ID; position: CubeCoords; faction?: Faction },
    options: Record<string, unknown> = {}
  ): { success: boolean; message: string; data?: Record<string, unknown> } {
    const entity = this.entities.get(entityId);
    if (!entity) {
      return { success: false, message: 'Entity not found' };
    }
    
    if (!entity.canInteract(interactor.faction)) {
      return { success: false, message: 'Cannot interact with this entity' };
    }
    
    if (!entity.isInRange(interactor.position)) {
      return { success: false, message: 'Out of interaction range' };
    }
    
    let result: { success: boolean; message: string; data?: Record<string, unknown> };
    
    switch (entity.type) {
      case 'chest':
        result = this.handleChestInteraction(entity as unknown as ChestEntityClass, options);
        break;
      case 'mechanism':
        result = this.handleMechanismInteraction(entity as unknown as MechanismEntityClass, interactor.faction);
        break;
      case 'portal':
        result = this.handlePortalInteraction(entity as unknown as PortalEntityClass, interactor);
        break;
      default:
        result = { success: true, message: 'Interaction complete' };
    }
    
    if (result.success) {
      entity.startCooldown();
      
      this.emitEvent({
        type: 'ENTITY_INTERACTED',
        entityId,
        data: {
          interactorId: interactor.id,
          interactorFaction: interactor.faction,
          ...result.data,
        },
        timestamp: Date.now(),
      });
    }
    
    return result;
  }

  private handleChestInteraction(
    chest: ChestEntityClass, 
    options: Record<string, unknown>
  ): { success: boolean; message: string; data?: Record<string, unknown> } {
    const hasKey = options.hasKey as boolean | undefined;
    const result = chest.open(hasKey);
    
    return {
      success: result.success,
      message: result.message,
      data: {
        loot: result.loot,
        gold: result.gold,
      },
    };
  }

  private handleMechanismInteraction(
    mechanism: MechanismEntityClass,
    faction?: Faction
  ): { success: boolean; message: string; data?: Record<string, unknown> } {
    const result = mechanism.trigger(faction);
    
    if (result.success) {
      this.applyMechanismEffects(mechanism, result.effects);
      
      if (mechanism.linkedEntities.length > 0) {
        this.triggerLinkedEntities(mechanism);
      }
      
      this.emitEvent({
        type: 'ENTITY_ACTIVATED',
        entityId: mechanism.id,
        data: {
          isActive: mechanism.isActive,
          activationCount: mechanism.activationCount,
          effects: result.effects,
        },
        timestamp: Date.now(),
      });
    }
    
    return {
      success: result.success,
      message: result.message,
      data: { effects: result.effects },
    };
  }

  private handlePortalInteraction(
    portal: PortalEntityClass,
    interactor: { id: ID; position: CubeCoords; faction?: Faction }
  ): { success: boolean; message: string; data?: Record<string, unknown> } {
    const pairedPortal = this.entities.get(portal.portalPair) as PortalEntityClass | undefined;
    
    const result = portal.teleport(
      { id: interactor.id, coords: interactor.position, faction: interactor.faction },
      pairedPortal
    );
    
    if (result.success && result.destination) {
      this.emitEvent({
        type: 'ENTITY_TELEPORT',
        entityId: portal.id,
        data: {
          unitId: interactor.id,
          from: interactor.position,
          to: result.destination,
          targetPortalId: result.targetPortalId,
        },
        timestamp: Date.now(),
      });
    }
    
    return {
      success: result.success,
      message: result.message,
      data: {
        destination: result.destination,
        targetPortalId: result.targetPortalId,
      },
    };
  }

  private applyMechanismEffects(
    mechanism: MechanismEntityClass,
    effects: Array<{ type: string; data: Record<string, unknown> }>
  ): void {
    if (!this.grid) return;
    
    for (const effect of effects) {
      switch (effect.type) {
        case 'toggle_terrain':
          this.applyToggleTerrainEffect(effect.data);
          break;
        case 'spawn_entity':
          break;
        case 'remove_entity':
          break;
        case 'trigger_damage':
          break;
      }
    }
  }

  private applyToggleTerrainEffect(data: Record<string, unknown>): void {
    if (!this.grid) return;
    
    const targetCoords = data.targetCoords as CubeCoords[] | undefined;
    const fromTerrain = data.fromTerrain as string | undefined;
    const toTerrain = data.toTerrain as string | undefined;
    
    if (!targetCoords || !toTerrain) return;
    
    for (const coords of targetCoords) {
      const tile = this.grid.getTile(coords);
      if (tile) {
        if (fromTerrain && tile.terrain === fromTerrain) {
          this.grid.setTileTerrain(coords, toTerrain);
        } else if (fromTerrain && tile.terrain === toTerrain) {
          this.grid.setTileTerrain(coords, fromTerrain);
        } else {
          this.grid.setTileTerrain(coords, toTerrain);
        }
      }
    }
  }

  private triggerLinkedEntities(mechanism: MechanismEntityClass): void {
    for (const linkedId of mechanism.linkedEntities) {
      const linkedEntity = this.entities.get(linkedId);
      if (!linkedEntity) continue;
      
      if (linkedEntity.type === 'mechanism') {
        const linkedMechanism = linkedEntity as unknown as MechanismEntityClass;
        linkedMechanism.trigger();
      }
    }
  }

  triggerOnStepEntities(
    position: CubeCoords,
    unit: { id: ID; faction?: Faction }
  ): Array<{ entityId: ID; type: string; data: Record<string, unknown> }> {
    const results: Array<{ entityId: ID; type: string; data: Record<string, unknown> }> = [];
    const entitiesAtPos = this.getEntitiesAtPosition(position);
    
    for (const entity of entitiesAtPos) {
      if (!entity.triggerOnStep || entity.isDestroyed) continue;
      if (entity.currentCooldown > 0) continue;
      
      switch (entity.type) {
        case 'mechanism': {
          const mechanism = entity as unknown as MechanismEntityClass;
          if (mechanism.canTrigger(unit.faction)) {
            const result = mechanism.trigger(unit.faction);
            if (result.success) {
              this.applyMechanismEffects(mechanism, result.effects);
              results.push({
                entityId: entity.id,
                type: 'mechanism_triggered',
                data: { effects: result.effects },
              });
            }
          }
          break;
        }
        case 'portal': {
          const portal = entity as unknown as PortalEntityClass;
          if (portal.teleportInstantly && portal.canTeleport({ id: unit.id, coords: position, faction: unit.faction })) {
            const pairedPortal = this.entities.get(portal.portalPair) as PortalEntityClass | undefined;
            const result = portal.teleport(
              { id: unit.id, coords: position, faction: unit.faction },
              pairedPortal
            );
            if (result.success && result.destination) {
              results.push({
                entityId: entity.id,
                type: 'teleport',
                data: { destination: result.destination, targetPortalId: result.targetPortalId },
              });
            }
          }
          break;
        }
      }
    }
    
    return results;
  }

  damageEntity(
    entityId: ID,
    damage: number,
    damageType: string = 'physical',
    attackerId?: ID
  ): { success: boolean; isDestroyed: boolean; actualDamage: number; drops?: LootItem[]; breakTerrain?: string } {
    const entity = this.entities.get(entityId);
    if (!entity) {
      return { success: false, isDestroyed: false, actualDamage: 0 };
    }
    
    if (entity.type !== 'destructible') {
      return { success: false, isDestroyed: false, actualDamage: 0 };
    }
    
    const destructible = entity as unknown as DestructibleEntityClass;
    const result = destructible.takeDamage(damage, damageType);
    
    if (result.isDestroyed) {
      if (this.grid && result.breakTerrain) {
        this.grid.setTileTerrain(entity.position, result.breakTerrain);
      }
      
      this.emitEvent({
        type: 'ENTITY_DESTROYED',
        entityId,
        data: {
          attackerId,
          damage: result.actualDamage,
          drops: result.drops,
          breakTerrain: result.breakTerrain,
        },
        timestamp: Date.now(),
      });
    }
    
    return {
      success: true,
      isDestroyed: result.isDestroyed,
      actualDamage: result.actualDamage,
      drops: result.drops,
      breakTerrain: result.breakTerrain,
    };
  }

  tickCooldowns(): void {
    for (const entity of this.entities.values()) {
      entity.tickCooldown();
    }
  }

  blocksMovement(position: CubeCoords): boolean {
    const entities = this.getEntitiesAtPosition(position);
    return entities.some(e => e.blocksMovement && !e.isDestroyed);
  }

  blocksVision(position: CubeCoords): boolean {
    const entities = this.getEntitiesAtPosition(position);
    return entities.some(e => e.blocksVision && !e.isDestroyed);
  }

  on(eventType: EntityEventType, handler: EntityEventHandler): () => void {
    const wrappedHandler: EntityEventHandler = (event) => {
      if (event.type === eventType) {
        handler(event);
      }
    };
    this.eventHandlers.add(wrappedHandler);
    return () => this.eventHandlers.delete(wrappedHandler);
  }

  onAny(handler: EntityEventHandler): () => void {
    this.eventHandlers.add(handler);
    return () => this.eventHandlers.delete(handler);
  }

  private emitEvent(event: EntityEvent): void {
    this.eventHistory.push(event);
    
    for (const handler of this.eventHandlers) {
      try {
        handler(event);
      } catch (e) {
        console.warn('Entity event handler failed:', e);
      }
    }
  }

  getEventHistory(): EntityEvent[] {
    return [...this.eventHistory];
  }

  clearEventHistory(): void {
    this.eventHistory = [];
  }

  toJSON(): Record<string, unknown> {
    const entitiesData: Array<{ id: ID; type: EntityType; data: Record<string, unknown> }> = [];
    
    for (const entity of this.entities.values()) {
      entitiesData.push({
        id: entity.id,
        type: entity.type,
        data: entity.toJSON(),
      });
    }
    
    return {
      entities: entitiesData,
      eventHistory: [...this.eventHistory],
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    this.entities.clear();
    this.eventHistory = [];
    
    const entitiesData = data.entities as Array<{ id: ID; type: EntityType; data: Record<string, unknown> }>;
    
    for (const entityData of entitiesData) {
      let entity: BaseMapEntity | null = null;
      
      switch (entityData.type) {
        case 'chest':
          entity = ChestEntityClass.fromJSON(entityData.data);
          break;
        case 'mechanism':
          entity = MechanismEntityClass.fromJSON(entityData.data);
          break;
        case 'destructible':
          entity = DestructibleEntityClass.fromJSON(entityData.data);
          break;
        case 'portal':
          entity = PortalEntityClass.fromJSON(entityData.data);
          break;
        default:
          break;
      }
      
      if (entity) {
        this.entities.set(entity.id, entity);
      }
    }
    
    if (data.eventHistory) {
      this.eventHistory = [...(data.eventHistory as EntityEvent[])];
    }
  }

  static fromJSON(data: Record<string, unknown>): MapEntityManager {
    const manager = new MapEntityManager();
    manager.fromJSON(data);
    return manager;
  }
}
