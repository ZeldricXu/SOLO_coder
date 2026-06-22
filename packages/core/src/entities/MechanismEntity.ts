import { BaseMapEntity } from './BaseMapEntity';
import type { 
  MechanismEntity, 
  MechanismType, 
  MechanismEffectType,
  MechanismEffectData,
  ToggleTerrainEffectData,
  TriggerDamageEffectData
} from '../types/entities';
import type { ID, Faction, CubeCoords, TerrainType } from '../types';

export interface MechanismTriggerResult {
  success: boolean;
  message: string;
  effects: Array<{ type: string; data: Record<string, unknown> }>;
}

export class MechanismEntityClass extends BaseMapEntity implements MechanismEntity {
  type: 'mechanism';
  mechanismType: MechanismType;
  isActive: boolean;
  linkedEntities: ID[];
  effectType: MechanismEffectType;
  effectData: MechanismEffectData;
  activationCount: number;
  maxActivations: number;
  triggerFactions: Faction[];

  constructor(
    name: string,
    position: CubeCoords,
    mechanismType: MechanismType,
    effectType: MechanismEffectType,
    effectData: MechanismEffectData,
    options: Partial<MechanismEntity> = {}
  ) {
    super('mechanism', 'interactive', name, position, {
      ...options,
      blocksMovement: options.blocksMovement ?? false,
      blocksVision: options.blocksVision ?? false,
      isInteractable: options.isInteractable ?? true,
      interactRange: options.interactRange ?? 1,
      triggerOnStep: options.triggerOnStep ?? (mechanismType === 'pressure_plate'),
    });
    
    this.type = 'mechanism';
    this.mechanismType = mechanismType;
    this.isActive = options.isActive ?? false;
    this.linkedEntities = options.linkedEntities ?? [];
    this.effectType = effectType;
    this.effectData = effectData;
    this.activationCount = options.activationCount ?? 0;
    this.maxActivations = options.maxActivations ?? -1;
    this.triggerFactions = options.triggerFactions ?? [];
    this.state = options.state ?? (this.isActive ? 'active' : 'idle');
  }

  canTrigger(faction?: Faction): boolean {
    if (this.isDestroyed) return false;
    
    if (this.maxActivations >= 0 && this.activationCount >= this.maxActivations) {
      return false;
    }
    
    if (this.currentCooldown > 0) return false;
    
    if (this.triggerFactions.length > 0 && faction) {
      if (!this.triggerFactions.includes(faction)) {
        return false;
      }
    }
    
    return true;
  }

  trigger(triggeringFaction?: Faction): MechanismTriggerResult {
    if (!this.canTrigger(triggeringFaction)) {
      return {
        success: false,
        message: 'Cannot trigger mechanism',
        effects: [],
      };
    }
    
    this.isActive = !this.isActive;
    this.activationCount++;
    this.state = this.isActive ? 'active' : 'idle';
    
    if (this.interactCooldown > 0) {
      this.startCooldown();
    }
    
    const effects = this.applyEffect();
    
    return {
      success: true,
      message: 'Mechanism triggered',
      effects,
    };
  }

  applyEffect(): Array<{ type: string; data: Record<string, unknown> }> {
    const effects: Array<{ type: string; data: Record<string, unknown> }> = [];
    
    switch (this.effectType) {
      case 'toggle_terrain':
        effects.push({
          type: 'toggle_terrain',
          data: { ...this.effectData },
        });
        break;
        
      case 'spawn_entity':
        effects.push({
          type: 'spawn_entity',
          data: { ...this.effectData },
        });
        break;
        
      case 'remove_entity':
        effects.push({
          type: 'remove_entity',
          data: { ...this.effectData },
        });
        break;
        
      case 'trigger_damage':
        effects.push({
          type: 'trigger_damage',
          data: { ...this.effectData },
        });
        break;
        
      case 'open_door':
      case 'lower_bridge':
        effects.push({
          type: this.effectType,
          data: { linkedEntities: [...this.linkedEntities] },
        });
        break;
        
      default:
        effects.push({
          type: 'custom',
          data: { ...this.effectData },
        });
    }
    
    return effects;
  }

  getToggleTerrainData(): ToggleTerrainEffectData | null {
    if (this.effectType !== 'toggle_terrain') return null;
    return this.effectData as unknown as ToggleTerrainEffectData;
  }

  getTriggerDamageData(): TriggerDamageEffectData | null {
    if (this.effectType !== 'trigger_damage') return null;
    return this.effectData as unknown as TriggerDamageEffectData;
  }

  toJSON(): Record<string, unknown> {
    return {
      ...this.baseToJSON(),
      mechanismType: this.mechanismType,
      isActive: this.isActive,
      linkedEntities: [...this.linkedEntities],
      effectType: this.effectType,
      effectData: { ...this.effectData },
      activationCount: this.activationCount,
      maxActivations: this.maxActivations,
      triggerFactions: [...this.triggerFactions],
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    this.baseFromJSON(data);
    this.mechanismType = data.mechanismType as MechanismType;
    this.isActive = data.isActive as boolean;
    this.linkedEntities = [...(data.linkedEntities as ID[])];
    this.effectType = data.effectType as MechanismEffectType;
    this.effectData = { ...(data.effectData as Record<string, unknown>) };
    this.activationCount = data.activationCount as number;
    this.maxActivations = data.maxActivations as number;
    this.triggerFactions = [...(data.triggerFactions as Faction[])];
  }

  static fromJSON(data: Record<string, unknown>): MechanismEntityClass {
    const position = data.position as CubeCoords;
    const entity = new MechanismEntityClass(
      data.name as string,
      position,
      data.mechanismType as MechanismType,
      data.effectType as MechanismEffectType,
      data.effectData as MechanismEffectData,
      {}
    );
    entity.fromJSON(data);
    return entity;
  }
}
