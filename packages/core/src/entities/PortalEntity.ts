import { BaseMapEntity } from './BaseMapEntity';
import type { PortalEntity } from '../types/entities';
import type { ID, Faction, CubeCoords } from '../types';

export interface TeleportResult {
  success: boolean;
  destination?: CubeCoords;
  message: string;
  targetPortalId?: ID;
}

export interface TeleportUnit {
  id: ID;
  coords: CubeCoords;
  faction?: Faction;
}

export class PortalEntityClass extends BaseMapEntity implements PortalEntity {
  type: 'portal';
  portalPair: ID;
  isOneWay: boolean;
  destinationOffset?: CubeCoords;
  cooldownPerUse: number;
  factionRestriction?: Faction[];
  teleportInstantly: boolean;

  constructor(
    name: string,
    position: CubeCoords,
    portalPair: ID,
    options: Partial<PortalEntity> = {}
  ) {
    super('portal', 'interactive', name, position, {
      ...options,
      blocksMovement: options.blocksMovement ?? false,
      blocksVision: options.blocksVision ?? false,
      isInteractable: options.isInteractable ?? true,
      interactRange: options.interactRange ?? 0,
      triggerOnStep: options.triggerOnStep ?? (options.teleportInstantly ?? true),
    });
    
    this.type = 'portal';
    this.portalPair = portalPair;
    this.isOneWay = options.isOneWay ?? false;
    this.destinationOffset = options.destinationOffset;
    this.cooldownPerUse = options.cooldownPerUse ?? 0;
    this.factionRestriction = options.factionRestriction;
    this.teleportInstantly = options.teleportInstantly ?? true;
    this.state = options.state ?? 'active';
  }

  canTeleport(unit: TeleportUnit): boolean {
    if (this.isDestroyed) return false;
    
    if (this.currentCooldown > 0) return false;
    
    if (this.factionRestriction && this.factionRestriction.length > 0) {
      if (!unit.faction || !this.factionRestriction.includes(unit.faction)) {
        return false;
      }
    }
    
    return true;
  }

  teleport(unit: TeleportUnit, pairedPortal?: PortalEntityClass): TeleportResult {
    if (!this.canTeleport(unit)) {
      return {
        success: false,
        message: 'Cannot teleport through this portal',
      };
    }
    
    if (!pairedPortal) {
      return {
        success: false,
        message: 'Paired portal not found',
      };
    }
    
    if (this.isOneWay && pairedPortal.isOneWay) {
      return {
        success: false,
        message: 'Portal is one-way and destination does not allow entry',
      };
    }
    
    let destination: CubeCoords = { ...pairedPortal.position };
    
    if (pairedPortal.destinationOffset) {
      destination.q += pairedPortal.destinationOffset.q;
      destination.r += pairedPortal.destinationOffset.r;
      destination.s += pairedPortal.destinationOffset.s;
    }
    
    if (this.cooldownPerUse > 0) {
      this.currentCooldown = this.cooldownPerUse;
    }
    
    if (pairedPortal.cooldownPerUse > 0) {
      pairedPortal.currentCooldown = pairedPortal.cooldownPerUse;
    }
    
    return {
      success: true,
      destination,
      message: 'Teleport successful',
      targetPortalId: pairedPortal.id,
    };
  }

  setPairedPortal(portalId: ID): void {
    this.portalPair = portalId;
  }

  toJSON(): Record<string, unknown> {
    return {
      ...this.baseToJSON(),
      portalPair: this.portalPair,
      isOneWay: this.isOneWay,
      destinationOffset: this.destinationOffset ? { ...this.destinationOffset } : undefined,
      cooldownPerUse: this.cooldownPerUse,
      factionRestriction: this.factionRestriction ? [...this.factionRestriction] : undefined,
      teleportInstantly: this.teleportInstantly,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    this.baseFromJSON(data);
    this.portalPair = data.portalPair as ID;
    this.isOneWay = data.isOneWay as boolean;
    this.destinationOffset = data.destinationOffset as CubeCoords | undefined;
    this.cooldownPerUse = data.cooldownPerUse as number;
    this.factionRestriction = data.factionRestriction as Faction[] | undefined;
    this.teleportInstantly = data.teleportInstantly as boolean;
  }

  static fromJSON(data: Record<string, unknown>): PortalEntityClass {
    const position = data.position as CubeCoords;
    const entity = new PortalEntityClass(
      data.name as string,
      position,
      data.portalPair as ID,
      {}
    );
    entity.fromJSON(data);
    return entity;
  }
}
