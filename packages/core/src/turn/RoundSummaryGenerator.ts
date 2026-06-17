import type { ID, Faction } from '../types/common';
import type { RoundSummary } from '../types/turn';
import { serializeMap, deserializeMap } from '../utils/serialization';

export interface TrackEventOptions {
  type: 'damage' | 'heal' | 'kill' | 'skill' | 'move' | 'status' | string;
  sourceUnitId?: ID;
  sourceFaction?: Faction;
  targetUnitId?: ID;
  targetFaction?: Faction;
  value?: number;
  [key: string]: unknown;
}

export class RoundSummaryGenerator {
  private currentRound: number;
  private actingUnits: ID[];
  private kills: Map<Faction, number>;
  private damageDealt: Map<Faction, number>;
  private damageTaken: Map<Faction, number>;
  private healingDone: Map<Faction, number>;
  private events: unknown[];

  constructor(roundNumber: number = 1) {
    this.currentRound = roundNumber;
    this.actingUnits = [];
    this.kills = new Map();
    this.damageDealt = new Map();
    this.damageTaken = new Map();
    this.healingDone = new Map();
    this.events = [];
  }

  trackEvent(options: TrackEventOptions): void {
    const { type, sourceFaction, targetFaction, value = 0 } = options;

    if (type === 'damage') {
      if (sourceFaction) {
        this.damageDealt.set(
          sourceFaction,
          (this.damageDealt.get(sourceFaction) || 0) + value
        );
      }
      if (targetFaction) {
        this.damageTaken.set(
          targetFaction,
          (this.damageTaken.get(targetFaction) || 0) + value
        );
      }
    } else if (type === 'heal') {
      if (sourceFaction) {
        this.healingDone.set(
          sourceFaction,
          (this.healingDone.get(sourceFaction) || 0) + value
        );
      }
    } else if (type === 'kill') {
      if (sourceFaction) {
        this.kills.set(
          sourceFaction,
          (this.kills.get(sourceFaction) || 0) + 1
        );
      }
    }

    this.events.push({
      ...options,
      timestamp: Date.now()
    });
  }

  addActingUnit(unitId: ID): void {
    if (!this.actingUnits.includes(unitId)) {
      this.actingUnits.push(unitId);
    }
  }

  generateSummary(): RoundSummary {
    return {
      roundNumber: this.currentRound,
      actingUnits: [...this.actingUnits],
      kills: new Map(this.kills),
      damageDealt: new Map(this.damageDealt),
      damageTaken: new Map(this.damageTaken),
      healingDone: new Map(this.healingDone),
      events: [...this.events]
    };
  }

  getKills(): Map<Faction, number> {
    return new Map(this.kills);
  }

  getKillsByFaction(faction: Faction): number {
    return this.kills.get(faction) || 0;
  }

  getDamageStats(): {
    damageDealt: Map<Faction, number>;
    damageTaken: Map<Faction, number>;
    healingDone: Map<Faction, number>;
  } {
    return {
      damageDealt: new Map(this.damageDealt),
      damageTaken: new Map(this.damageTaken),
      healingDone: new Map(this.healingDone)
    };
  }

  getTotalDamageDealt(): number {
    let total = 0;
    for (const value of this.damageDealt.values()) {
      total += value;
    }
    return total;
  }

  getTotalKills(): number {
    let total = 0;
    for (const value of this.kills.values()) {
      total += value;
    }
    return total;
  }

  reset(roundNumber?: number): void {
    if (roundNumber !== undefined) {
      this.currentRound = roundNumber;
    }
    this.actingUnits = [];
    this.kills.clear();
    this.damageDealt.clear();
    this.damageTaken.clear();
    this.healingDone.clear();
    this.events = [];
  }

  toJSON(): Record<string, unknown> {
    return {
      currentRound: this.currentRound,
      actingUnits: [...this.actingUnits],
      kills: serializeMap(this.kills, (k) => String(k)),
      damageDealt: serializeMap(this.damageDealt, (k) => String(k)),
      damageTaken: serializeMap(this.damageTaken, (k) => String(k)),
      healingDone: serializeMap(this.healingDone, (k) => String(k)),
      events: [...this.events]
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    this.currentRound = data.currentRound as number;
    this.actingUnits = (data.actingUnits as ID[]) || [];
    this.kills = deserializeMap(
      data.kills as Array<{ key: string; value: number }>,
      (k) => k as Faction
    );
    this.damageDealt = deserializeMap(
      data.damageDealt as Array<{ key: string; value: number }>,
      (k) => k as Faction
    );
    this.damageTaken = deserializeMap(
      data.damageTaken as Array<{ key: string; value: number }>,
      (k) => k as Faction
    );
    this.healingDone = deserializeMap(
      data.healingDone as Array<{ key: string; value: number }>,
      (k) => k as Faction
    );
    this.events = (data.events as unknown[]) || [];
  }

  static fromJSON(data: Record<string, unknown>): RoundSummaryGenerator {
    const generator = new RoundSummaryGenerator();
    generator.fromJSON(data);
    return generator;
  }
}
