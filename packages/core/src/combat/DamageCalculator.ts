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

  constructor(
    config: DamageCalculationConfig,
    elementChart: ElementChart,
    events: DamageCalculatorEventMap = {}
  ) {
    this.config = config;
    this.elementChart = elementChart;
    this.events = events;
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

    const timestamp = Date.now();
    const isMagic = skillDamageType === 'magic';

    const baseAttack = isMagic
      ? attacker.stats.magicAttack
      : attacker.stats.attack;
    const baseDefense = isMagic
      ? target.stats.magicDefense
      : target.stats.defense;
    const armorPen = armorPenetrationOverride ?? attacker.stats.armorPenetration;

    let baseDamage = baseDamageOverride ?? evaluateFormula(this.config.baseFormula, {
      attack: baseAttack,
      defense: baseDefense,
      armorPenetration: armorPen,
      baseAttack,
      targetDefense: baseDefense,
    });

    const elementMultiplier = calculateElementMultiplier(
      skillElement,
      this.getDominantElement(target),
      this.elementChart,
      this.config.elementAdvantageMultiplier,
      this.config.elementDisadvantageMultiplier
    );

    const isCrit = this.calculateCrit(attacker, target);
    const critMultiplier = isCrit ? attacker.stats.critDamage : 1;

    const { resistanceMitigation, resistancePercent } = this.calculateResistance(
      target,
      skillDamageType
    );

    let finalDamage = baseDamage;
    finalDamage *= elementMultiplier;
    finalDamage *= (1 + terrainBonus);
    finalDamage *= (1 + heightBonus);
    finalDamage *= (1 + directionBonus);
    finalDamage *= critMultiplier;
    finalDamage *= (1 - resistancePercent);
    finalDamage -= resistanceMitigation;

    const hitResult = this.calculateHit(
      attacker,
      target,
      terrainBonus,
      heightBonus,
      directionBonus
    );

    const isDodged = !hitResult.hit;
    const isBlocked = false;

    if (isDodged) {
      finalDamage = 0;
    }

    finalDamage = clamp(
      finalDamage,
      this.config.minDamage,
      this.config.maxDamage
    );

    const instance: DamageInstance = {
      sourceId: attacker.id,
      targetId: target.id,
      skillId,
      baseDamage,
      finalDamage: Math.floor(finalDamage),
      damageType: skillDamageType,
      element: skillElement,
      isCrit: isCrit && !isDodged,
      isBlocked,
      isDodged,
      armorMitigation: 0,
      resistanceMitigation,
      terrainBonus,
      elementBonus: elementMultiplier - 1,
      position: target.coords,
      timestamp,
    };

    if (isCrit && !isDodged) {
      this.events.onCrit?.(attacker, target, critMultiplier);
    }

    this.events.onAfterDamageCalculate?.(instance);

    return instance;
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
  }

  getElementChart(): ElementChart {
    return { ...this.elementChart };
  }

  setElementChart(chart: ElementChart): void {
    this.elementChart = { ...chart };
  }

  setEvents(events: Partial<DamageCalculatorEventMap>): void {
    this.events = { ...this.events, ...events };
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
    }
    if (data.elementChart) {
      this.elementChart = data.elementChart as ElementChart;
    }
  }
}
