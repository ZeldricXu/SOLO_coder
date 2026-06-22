import type { ID, Faction, TerrainType, Direction } from './common';
import type { CubeCoords } from './grid';

export type EntityType = 'chest' | 'mechanism' | 'destructible' | 'portal' | 'campfire' | 'shop' | string;

export type EntityState = 'idle' | 'active' | 'triggered' | 'destroyed' | 'open' | 'closed' | 'disabled';

export type EntityCategory = 'interactive' | 'obstacle' | 'decoration' | 'objective';

export interface MapEntity {
  id: ID;
  type: EntityType;
  category: EntityCategory;
  name: string;
  description?: string;
  position: CubeCoords;
  state: EntityState;
  blocksMovement: boolean;
  blocksVision: boolean;
  isInteractable: boolean;
  interactRange: number;
  triggerOnStep: boolean;
  triggers: string[];
  interactCooldown: number;
  currentCooldown: number;
  faction?: Faction;
  properties: Record<string, unknown>;
  isDestroyed: boolean;
}

export interface LootItem {
  itemId: ID;
  quantity: number;
  dropRate: number;
}

export interface ChestEntity extends MapEntity {
  type: 'chest';
  loot: LootItem[];
  isOpen: boolean;
  keyRequired?: ID;
  gold?: number;
}

export type MechanismType = 'switch' | 'pressure_plate' | 'lever' | 'bridge' | 'door';

export type MechanismEffectType = 'toggle_terrain' | 'spawn_entity' | 'remove_entity' | 'trigger_damage' | 'open_door' | 'lower_bridge';

export interface ToggleTerrainEffectData {
  targetCoords: CubeCoords[];
  fromTerrain: TerrainType;
  toTerrain: TerrainType;
}

export interface SpawnEntityEffectData {
  entityType: EntityType;
  position: CubeCoords;
  count: number;
}

export interface TriggerDamageEffectData {
  damage: number;
  damageType: string;
  radius: number;
}

export type MechanismEffectData = Record<string, unknown>;

export interface MechanismEntity extends MapEntity {
  type: 'mechanism';
  mechanismType: MechanismType;
  isActive: boolean;
  linkedEntities: ID[];
  effectType: MechanismEffectType;
  effectData: MechanismEffectData;
  activationCount: number;
  maxActivations: number;
  triggerFactions: Faction[];
}

export interface DamageResistanceEntry {
  type: string;
  value: number;
  isPercent: boolean;
}

export interface DestructibleEntity extends MapEntity {
  type: 'destructible';
  maxHp: number;
  hp: number;
  defense: number;
  resistances: DamageResistanceEntry[];
  drops: LootItem[];
  breakTerrain?: TerrainType;
  blocksMovementWhenDestroyed: boolean;
  blocksVisionWhenDestroyed: boolean;
}

export interface PortalEntity extends MapEntity {
  type: 'portal';
  portalPair: ID;
  isOneWay: boolean;
  destinationOffset?: CubeCoords;
  cooldownPerUse: number;
  factionRestriction?: Faction[];
  teleportInstantly: boolean;
}

export type EntityEventType = 
  | 'ENTITY_INTERACTED'
  | 'ENTITY_DESTROYED'
  | 'ENTITY_ACTIVATED'
  | 'ENTITY_TELEPORT'
  | 'ENTITY_SPAWNED'
  | 'ENTITY_REMOVED'
  | 'ENTITY_STATE_CHANGED';
