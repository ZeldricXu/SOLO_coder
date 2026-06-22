import { BaseMapEntity } from './BaseMapEntity';
import type { ChestEntity, LootItem } from '../types/entities';
import type { ID, CubeCoords } from '../types';

export class ChestEntityClass extends BaseMapEntity implements ChestEntity {
  type: 'chest';
  loot: LootItem[];
  isOpen: boolean;
  keyRequired?: ID;
  gold?: number;

  constructor(
    name: string,
    position: CubeCoords,
    options: Partial<ChestEntity> = {}
  ) {
    super('chest', 'interactive', name, position, {
      ...options,
      blocksMovement: options.blocksMovement ?? true,
      blocksVision: options.blocksVision ?? false,
      isInteractable: options.isInteractable ?? true,
      interactRange: options.interactRange ?? 1,
    });
    
    this.type = 'chest';
    this.loot = options.loot ?? [];
    this.isOpen = options.isOpen ?? false;
    this.keyRequired = options.keyRequired;
    this.gold = options.gold;
    this.state = options.state ?? (this.isOpen ? 'open' : 'closed');
  }

  open(hasKey?: boolean): { success: boolean; loot: LootItem[]; gold: number; message: string } {
    if (this.isDestroyed) {
      return { success: false, loot: [], gold: 0, message: 'Chest is destroyed' };
    }
    
    if (this.isOpen) {
      return { success: false, loot: [], gold: 0, message: 'Chest is already open' };
    }
    
    if (this.keyRequired && !hasKey) {
      return { success: false, loot: [], gold: 0, message: 'Key required' };
    }
    
    this.isOpen = true;
    this.state = 'open';
    this.blocksMovement = false;
    
    const droppedLoot = this.rollLoot();
    const goldAmount = this.gold ?? 0;
    
    return {
      success: true,
      loot: droppedLoot,
      gold: goldAmount,
      message: 'Chest opened successfully',
    };
  }

  private rollLoot(): LootItem[] {
    const result: LootItem[] = [];
    
    for (const item of this.loot) {
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

  close(): boolean {
    if (this.isDestroyed) return false;
    if (!this.isOpen) return false;
    
    this.isOpen = false;
    this.state = 'closed';
    this.blocksMovement = true;
    
    return true;
  }

  toJSON(): Record<string, unknown> {
    return {
      ...this.baseToJSON(),
      loot: this.loot.map(item => ({ ...item })),
      isOpen: this.isOpen,
      keyRequired: this.keyRequired,
      gold: this.gold,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    this.baseFromJSON(data);
    this.loot = (data.loot as LootItem[]).map(item => ({ ...item }));
    this.isOpen = data.isOpen as boolean;
    this.keyRequired = data.keyRequired as ID | undefined;
    this.gold = data.gold as number | undefined;
  }

  static fromJSON(data: Record<string, unknown>): ChestEntityClass {
    const position = data.position as CubeCoords;
    const entity = new ChestEntityClass(data.name as string, position, {});
    entity.fromJSON(data);
    return entity;
  }
}
