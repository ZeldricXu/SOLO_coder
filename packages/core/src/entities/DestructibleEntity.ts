import { BaseMapEntity } from './BaseMapEntity';
import type { 
  DestructibleEntity, 
  LootItem,
  DamageResistanceEntry
} from '../types/entities';
import type { ID, CubeCoords, TerrainType, DamageType } from '../types';

export interface DamageResult {
  damage: number;
  actualDamage: number;
  isDestroyed: boolean;
  drops: LootItem[];
  breakTerrain?: TerrainType;
}

export class DestructibleEntityClass extends BaseMapEntity implements DestructibleEntity {
  type: 'destructible';
  maxHp: number;
  hp: number;
  defense: number;
  resistances: DamageResistanceEntry[];
  drops: LootItem[];
  breakTerrain?: TerrainType;
  blocksMovementWhenDestroyed: boolean;
  blocksVisionWhenDestroyed: boolean;

  constructor(
    name: string,
    position: CubeCoords,
    maxHp: number,
    options: Partial<DestructibleEntity> = {}
  ) {
    super('destructible', 'obstacle', name, position, {
      ...options,
      blocksMovement: options.blocksMovement ?? true,
      blocksVision: options.blocksVision ?? false,
      isInteractable: options.isInteractable ?? false,
      interactRange: options.interactRange ?? 0,
    });
    
    this.type = 'destructible';
    this.maxHp = maxHp;
    this.hp = options.hp ?? maxHp;
    this.defense = options.defense ?? 0;
    this.resistances = options.resistances ?? [];
    this.drops = options.drops ?? [];
    this.breakTerrain = options.breakTerrain;
    this.blocksMovementWhenDestroyed = options.blocksMovementWhenDestroyed ?? false;
    this.blocksVisionWhenDestroyed = options.blocksVisionWhenDestroyed ?? false;
    this.state = options.state ?? 'idle';
  }

  takeDamage(damage: number, damageType: string = 'physical'): DamageResult {
    if (this.isDestroyed) {
      return {
        damage,
        actualDamage: 0,
        isDestroyed: true,
        drops: [],
        breakTerrain: undefined,
      };
    }
    
    let actualDamage = Math.max(0, damage - this.defense);
    
    for (const resistance of this.resistances) {
      if (resistance.type === damageType || resistance.type === 'all') {
        if (resistance.isPercent) {
          actualDamage *= (1 - resistance.value);
        } else {
          actualDamage = Math.max(0, actualDamage - resistance.value);
        }
      }
    }
    
    actualDamage = Math.floor(actualDamage);
    this.hp = Math.max(0, this.hp - actualDamage);
    
    if (this.hp <= 0) {
      const result = this.destroy();
      return {
        damage,
        actualDamage,
        isDestroyed: true,
        drops: result.drops,
        breakTerrain: result.breakTerrain,
      };
    }
    
    return {
      damage,
      actualDamage,
      isDestroyed: false,
      drops: [],
      breakTerrain: undefined,
    };
  }

  destroy(): { drops: LootItem[]; breakTerrain?: TerrainType } {
    if (this.isDestroyed) {
      return { drops: [], breakTerrain: undefined };
    }
    
    super.destroy();
    
    this.blocksMovement = this.blocksMovementWhenDestroyed;
    this.blocksVision = this.blocksVisionWhenDestroyed;
    
    const droppedItems = this.rollDrops();
    
    return {
      drops: droppedItems,
      breakTerrain: this.breakTerrain,
    };
  }

  private rollDrops(): LootItem[] {
    const result: LootItem[] = [];
    
    for (const item of this.drops) {
      const roll = Math.random();
      if (roll < item.dropRate) {
        result.push({
          itemId: item.itemId,
          quantity: item.quantity,
          dropRate: item.dropRate,
        });
      }
    }
    
    return result;
  }

  heal(amount: number): number {
    if (this.isDestroyed) return 0;
    
    const actualHeal = Math.min(amount, this.maxHp - this.hp);
    this.hp += actualHeal;
    return actualHeal;
  }

  getHpPercent(): number {
    if (this.maxHp <= 0) return 0;
    return this.hp / this.maxHp;
  }

  toJSON(): Record<string, unknown> {
    return {
      ...this.baseToJSON(),
      maxHp: this.maxHp,
      hp: this.hp,
      defense: this.defense,
      resistances: this.resistances.map(r => ({ ...r })),
      drops: this.drops.map(d => ({ ...d })),
      breakTerrain: this.breakTerrain,
      blocksMovementWhenDestroyed: this.blocksMovementWhenDestroyed,
      blocksVisionWhenDestroyed: this.blocksVisionWhenDestroyed,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    this.baseFromJSON(data);
    this.maxHp = data.maxHp as number;
    this.hp = data.hp as number;
    this.defense = data.defense as number;
    this.resistances = (data.resistances as DamageResistanceEntry[]).map(r => ({ ...r }));
    this.drops = (data.drops as LootItem[]).map(d => ({ ...d }));
    this.breakTerrain = data.breakTerrain as TerrainType | undefined;
    this.blocksMovementWhenDestroyed = data.blocksMovementWhenDestroyed as boolean;
    this.blocksVisionWhenDestroyed = data.blocksVisionWhenDestroyed as boolean;
  }

  static fromJSON(data: Record<string, unknown>): DestructibleEntityClass {
    const position = data.position as CubeCoords;
    const entity = new DestructibleEntityClass(
      data.name as string,
      position,
      data.maxHp as number,
      {}
    );
    entity.fromJSON(data);
    return entity;
  }
}
