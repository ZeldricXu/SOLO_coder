export type ID = string;

export interface Position {
  x: number;
  y: number;
}

export interface Size {
  width: number;
  height: number;
}

export type Faction = 'player' | 'enemy' | 'neutral' | string;

export interface AttributeModifier {
  type: 'add' | 'multiply' | 'set';
  value: number;
  source: ID;
  duration?: number;
}

export interface Attribute {
  base: number;
  modifiers: AttributeModifier[];
  current: number;
}

export interface Resource {
  current: number;
  max: number;
  min?: number;
}

export type DamageType = 
  | 'physical' 
  | 'magic' 
  | 'fire' 
  | 'ice' 
  | 'lightning' 
  | 'poison' 
  | 'holy' 
  | 'dark'
  | 'explosive'
  | 'piercing'
  | string;

export type ElementType = 'fire' | 'water' | 'earth' | 'wind' | 'light' | 'dark' | 'neutral';

export type StatusEffectType = 
  | 'stun' 
  | 'slow' 
  | 'poison' 
  | 'burn' 
  | 'freeze' 
  | 'bleed' 
  | 'regen' 
  | 'shield' 
  | 'buff' 
  | 'debuff'
  | 'invisible'
  | 'taunt'
  | 'root'
  | string;

export type SkillTargetType = 
  | 'self' 
  | 'single' 
  | 'area' 
  | 'line' 
  | 'cone' 
  | 'ally' 
  | 'enemy' 
  | 'allAlly' 
  | 'allEnemy'
  | 'terrain';

export type SkillEffectType = 
  | 'damage' 
  | 'heal' 
  | 'buff' 
  | 'debuff' 
  | 'summon' 
  | 'teleport' 
  | 'shield' 
  | 'dot' 
  | 'hot'
  | 'move'
  | 'createTerrain';

export type TerrainType = 
  | 'plain' 
  | 'forest' 
  | 'mountain' 
  | 'water' 
  | 'road' 
  | 'wall' 
  | 'lava' 
  | 'sand'
  | 'snow'
  | 'swamp'
  | 'building'
  | 'ruins'
  | string;

export type Direction = 0 | 1 | 2 | 3 | 4 | 5;

export interface TileConfig {
  type: TerrainType;
  name: string;
  moveCost: number;
  defenseBonus: number;
  attackBonus: number;
  accuracyBonus: number;
  blocksMovement: boolean;
  blocksVision: boolean;
  color: string;
  icon?: string;
}

export interface DamageResistance {
  type: DamageType;
  value: number;
  isPercent: boolean;
}

export interface ElementAffinity {
  element: ElementType;
  value: number;
}
