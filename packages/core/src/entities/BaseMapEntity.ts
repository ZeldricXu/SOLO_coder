import type { 
  MapEntity, 
  EntityType, 
  EntityState, 
  EntityCategory 
} from '../types/entities';
import type { ID, Faction } from '../types/common';
import type { CubeCoords } from '../types/grid';
import { generateId } from '../utils';

export abstract class BaseMapEntity implements MapEntity {
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

  constructor(
    type: EntityType,
    category: EntityCategory,
    name: string,
    position: CubeCoords,
    options: Partial<MapEntity> = {}
  ) {
    this.id = options.id ?? generateId();
    this.type = type;
    this.category = category;
    this.name = name;
    this.description = options.description;
    this.position = { ...position };
    this.state = options.state ?? 'idle';
    this.blocksMovement = options.blocksMovement ?? false;
    this.blocksVision = options.blocksVision ?? false;
    this.isInteractable = options.isInteractable ?? true;
    this.interactRange = options.interactRange ?? 1;
    this.triggerOnStep = options.triggerOnStep ?? false;
    this.triggers = options.triggers ?? [];
    this.interactCooldown = options.interactCooldown ?? 0;
    this.currentCooldown = options.currentCooldown ?? 0;
    this.faction = options.faction;
    this.properties = options.properties ?? {};
    this.isDestroyed = options.isDestroyed ?? false;
  }

  canInteract(interactorFaction?: Faction): boolean {
    if (this.isDestroyed) return false;
    if (!this.isInteractable) return false;
    if (this.currentCooldown > 0) return false;
    if (this.faction && interactorFaction && this.faction !== interactorFaction) {
      return false;
    }
    return true;
  }

  isInRange(from: CubeCoords, range?: number): boolean {
    const dx = from.q - this.position.q;
    const dy = from.r - this.position.r;
    const dz = from.s - this.position.s;
    const distance = (Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) / 2;
    return distance <= (range ?? this.interactRange);
  }

  tickCooldown(): void {
    if (this.currentCooldown > 0) {
      this.currentCooldown--;
    }
  }

  startCooldown(): void {
    this.currentCooldown = this.interactCooldown;
  }

  setState(state: EntityState): void {
    this.state = state;
  }

  destroy(): void {
    this.isDestroyed = true;
    this.state = 'destroyed';
    this.isInteractable = false;
  }

  abstract toJSON(): Record<string, unknown>;
  abstract fromJSON(data: Record<string, unknown>): void;

  protected baseToJSON(): Record<string, unknown> {
    return {
      id: this.id,
      type: this.type,
      category: this.category,
      name: this.name,
      description: this.description,
      position: { ...this.position },
      state: this.state,
      blocksMovement: this.blocksMovement,
      blocksVision: this.blocksVision,
      isInteractable: this.isInteractable,
      interactRange: this.interactRange,
      triggerOnStep: this.triggerOnStep,
      triggers: [...this.triggers],
      interactCooldown: this.interactCooldown,
      currentCooldown: this.currentCooldown,
      faction: this.faction,
      properties: { ...this.properties },
      isDestroyed: this.isDestroyed,
    };
  }

  protected baseFromJSON(data: Record<string, unknown>): void {
    this.id = data.id as ID;
    this.type = data.type as EntityType;
    this.category = data.category as EntityCategory;
    this.name = data.name as string;
    this.description = data.description as string | undefined;
    this.position = { ...(data.position as CubeCoords) };
    this.state = data.state as EntityState;
    this.blocksMovement = data.blocksMovement as boolean;
    this.blocksVision = data.blocksVision as boolean;
    this.isInteractable = data.isInteractable as boolean;
    this.interactRange = data.interactRange as number;
    this.triggerOnStep = data.triggerOnStep as boolean;
    this.triggers = [...(data.triggers as string[])];
    this.interactCooldown = data.interactCooldown as number;
    this.currentCooldown = data.currentCooldown as number;
    this.faction = data.faction as Faction | undefined;
    this.properties = { ...(data.properties as Record<string, unknown>) };
    this.isDestroyed = data.isDestroyed as boolean;
  }
}
