import type {
  CombatUnit,
  DamageCalculationConfig,
  ElementChart,
  DamageInstance,
  HitCalculationResult,
} from '../types';
import type { CubeCoords } from '../types/grid';
import type { DamageType, ElementType, Direction } from '../types/common';
import {
  clamp,
  evaluateFormula,
  calculateElementMultiplier,
  chance,
  Serializable,
} from '../utils';
import { DamageChain, DamageContext, IDamageHandler } from './DamageChain';
import {
  ArmorPenetrationHandler,
  BaseDamageHandler,
  HitHandler,
  ElementHandler,
  TerrainHandler,
  HeightHandler,
  DirectionHandler,
  CritHandler,
  ResistanceHandler,
  ShieldHandler,
  DamageClampHandler,
} from './handlers';

export type DamageCalculatorEventMap = {
  onBeforeDamageCalculate?: (attacker: CombatUnit, target: CombatUnit, context: Record<string, unknown>) => void;
  onAfterDamageCalculate?: (instance: DamageInstance) => void;
  onBeforeHitCalculate?: (attacker: CombatUnit, target: CombatUnit) => void;
  onAfterHitCalculate?: (result: HitCalculationResult) => void;
  onCrit?: (attacker: CombatUnit, target: CombatUnit, critMultiplier: number) => void;
};

export class DamageCalculator implements Serializable {
  private config: DamageCalculationConfig;
  private elementChart: ElementChart;
  private events: DamageCalculatorEventMap;
  private chain: DamageChain;

  constructor(
    config: DamageCalculationConfig,
    elementChart: ElementChart,
    events: DamageCalculatorEventMap = {}
  ) {
    this.config = config;
    this.elementChart = elementChart;
    this.events = events;
    this.chain = this.createDefaultChain();
  }

  private createDefaultChain(): DamageChain {
    const chain = new DamageChain(this.config, this.elementChart, this.events);
    chain.addHandler(new ArmorPenetrationHandler());
    chain.addHandler(new BaseDamageHandler());
    chain.addHandler(new HitHandler());
    chain.addHandler(new ElementHandler());
    chain.addHandler(new TerrainHandler());
    chain.addHandler(new HeightHandler());
    chain.addHandler(new DirectionHandler());
    chain.addHandler(new CritHandler());
    chain.addHandler(new ResistanceHandler());
    chain.addHandler(new ShieldHandler());
    chain.addHandler(new DamageClampHandler());
    return chain;
  }

  getChain(): DamageChain {
    return this.chain;
  }

  setChain(chain: DamageChain): void {
    this.chain = chain;
    this.chain.setConfig(this.config);
    this.chain.setElementChart(this.elementChart);
    this.chain.setEvents(this.events);
  }

  calculateDamage(
    attacker: CombatUnit,
    target: CombatUnit,
    skillElement: ElementType = 'neutral',
    skillDamageType: DamageType = 'physical',
    baseDamageOverride?: number,
    terrainBonus: number = 0,
    heightBonus: number = 0,
    directionBonus: number = 0,
    skillId?: string,
    armorPenetrationOverride?: number
  ): DamageInstance {
    this.events.onBeforeDamageCalculate?.(attacker, target, {
      skillElement,
      skillDamageType,
      baseDamageOverride,
    });

    const ctx: DamageContext = {
      attacker,
      target,
      skillElement,
      skillDamageType,
      baseDamageOverride,
      terrainBonus,
      heightBonus,
      directionBonus,
      skillId,
      armorPenetrationOverride,
      finalDamage: 0,
      skipRemaining: false,
      instance: {},
    };

    this.chain.process(ctx);

    let instance = ctx.instance as Partial<DamageInstance>;
    if (!instance.sourceId) {
      instance = {
        sourceId: attacker.id,
        targetId: target.id,
        skillId,
        baseDamage: ctx.baseDamage ?? 0,
        finalDamage: Math.floor(clamp(ctx.finalDamage, this.config.minDamage, this.config.maxDamage)),
        damageType: skillDamageType,
        element: skillElement,
        isCrit: (ctx.isCrit ?? false) && !(ctx.isDodged ?? false),
        isBlocked: false,
        isDodged: ctx.isDodged ?? false,
        armorMitigation: 0,
        resistanceMitigation: ctx.resistanceMitigation ?? 0,
        terrainBonus,
        elementBonus: (ctx.elementMultiplier ?? 1) - 1,
        position: target.coords,
        timestamp: Date.now(),
      };
    }

    const finalInstance = instance as DamageInstance;

    if (ctx.isCrit && !ctx.isDodged) {
      this.events.onCrit?.(attacker, target, ctx.critMultiplier ?? 1);
    }

    this.events.onAfterDamageCalculate?.(finalInstance);

    return finalInstance;
  }

  calculateHit(
    attacker: CombatUnit,
    target: CombatUnit,
    terrainBonus: number = 0,
    heightBonus: number = 0,
    directionBonus: number = 0
  ): HitCalculationResult {
    this.events.onBeforeHitCalculate?.(attacker, target);

    const accuracy = attacker.stats.accuracy;
    const evasion = target.stats.evasion;

    const isGuaranteedHit = attacker.tags.includes('guaranteed_hit');
    const isGuaranteedMiss = target.tags.includes('guaranteed_miss');

    let finalHitChance = accuracy - evasion;
    finalHitChance += terrainBonus * 100;
    finalHitChance += heightBonus * 100;
    finalHitChance += directionBonus * 100;
    finalHitChance = clamp(finalHitChance, 0, 100);

    let hit = isGuaranteedHit || (!isGuaranteedMiss && chance(finalHitChance));

    const result: HitCalculationResult = {
      hit,
      accuracy,
      evasion,
      finalHitChance,
      isGuaranteedHit,
      isGuaranteedMiss,
      terrainBonus,
      heightBonus,
      directionBonus,
    };

    this.events.onAfterHitCalculate?.(result);

    return result;
  }

  calculateCrit(attacker: CombatUnit, target: CombatUnit): boolean {
    const critRate = attacker.stats.critRate;
    if (target.tags.includes('immune_crit')) {
      return false;
    }
    return chance(critRate);
  }

  calculateResistance(
    target: CombatUnit,
    damageType: DamageType
  ): { resistanceMitigation: number; resistancePercent: number } {
    let flatMitigation = 0;
    let percentMitigation = 0;

    for (const resistance of target.resistances) {
      if (resistance.type === damageType || resistance.type === 'all') {
        if (resistance.isPercent) {
          percentMitigation += resistance.value;
        } else {
          flatMitigation += resistance.value;
        }
      }
    }

    percentMitigation = clamp(percentMitigation, 0, 0.9);

    return {
      resistanceMitigation: flatMitigation,
      resistancePercent: percentMitigation,
    };
  }

  applyTerrainAndHeightBonus(
    _attackerCoords: CubeCoords,
    _targetCoords: CubeCoords,
    attackerTerrainAttackBonus: number = 0,
    defenderTerrainDefenseBonus: number = 0,
    attackerHeight: number = 0,
    defenderHeight: number = 0
  ): { terrainBonus: number; heightBonus: number } {
    const terrainBonus = attackerTerrainAttackBonus - defenderTerrainDefenseBonus;
    const heightDiff = attackerHeight - defenderHeight;
    const heightBonus = heightDiff * this.config.heightBonusPerLevel;

    return {
      terrainBonus: clamp(terrainBonus, -0.5, 0.5),
      heightBonus: clamp(heightBonus, -0.5, 0.5),
    };
  }

  calculateDirectionBonus(
    attackerDirection: Direction,
    _targetDirection: Direction,
    attackerCoords: CubeCoords,
    targetCoords: CubeCoords
  ): number {
    const relativeDir = this.getRelativeDirection(
      attackerCoords,
      targetCoords,
      attackerDirection
    );

    switch (relativeDir) {
      case 'back':
        return this.config.directionBackDamageMultiplier - 1;
      case 'side':
        return this.config.directionSideDamageMultiplier - 1;
      case 'front':
      default:
        return this.config.directionFrontDamageMultiplier - 1;
    }
  }

  private getRelativeDirection(
    attackerCoords: CubeCoords,
    targetCoords: CubeCoords,
    attackerFacing: Direction
  ): 'front' | 'side' | 'back' {
    const dq = targetCoords.q - attackerCoords.q;
    const dr = targetCoords.r - attackerCoords.r;

    const facingOffsets = [
      { q: 1, r: -1 },
      { q: 1, r: 0 },
      { q: 0, r: 1 },
      { q: -1, r: 1 },
      { q: -1, r: 0 },
      { q: 0, r: -1 },
    ];

    const facing = facingOffsets[attackerFacing];
    const dot = dq * facing.q + dr * facing.r + (-dq - dr) * (-facing.q - facing.r);

    if (dot > 0.5) return 'front';
    if (dot < -0.5) return 'back';
    return 'side';
  }

  private getDominantElement(unit: CombatUnit): string {
    if (unit.affinities.length === 0) return 'neutral';

    let maxAffinity = unit.affinities[0];
    for (const affinity of unit.affinities) {
      if (affinity.value > maxAffinity.value) {
        maxAffinity = affinity;
      }
    }
    return maxAffinity.element;
  }

  getConfig(): DamageCalculationConfig {
    return { ...this.config };
  }

  setConfig(config: Partial<DamageCalculationConfig>): void {
    this.config = { ...this.config, ...config };
    this.chain.setConfig(this.config);
  }

  getElementChart(): ElementChart {
    return { ...this.elementChart };
  }

  setElementChart(chart: ElementChart): void {
    this.elementChart = { ...chart };
    this.chain.setElementChart(this.elementChart);
  }

  setEvents(events: Partial<DamageCalculatorEventMap>): void {
    this.events = { ...this.events, ...events };
    this.chain.setEvents(this.events);
  }

  toJSON(): Record<string, unknown> {
    return {
      config: this.config,
      elementChart: this.elementChart,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    if (data.config) {
      this.config = data.config as DamageCalculationConfig;
      this.chain.setConfig(this.config);
    }
    if (data.elementChart) {
      this.elementChart = data.elementChart as ElementChart;
      this.chain.setElementChart(this.elementChart);
    }
  }
}
