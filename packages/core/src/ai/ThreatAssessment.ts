import type {
  ID,
  CubeCoords,
  CombatUnit,
  AIParameters,
  ThreatAssessment as ThreatAssessmentResult,
} from '../types';
import { cubeDistance, isInRange } from '../grid/coords';
import { clamp, normalize, mapRange } from '../utils';

export class ThreatAssessment {
  private parameters: AIParameters['threatWeight'];
  private unitCache: Map<ID, CombatUnit>;
  private terrainHeightCache: Map<string, number>;
  private coverCache: Map<string, number>;

  constructor(parameters: AIParameters['threatWeight']) {
    this.parameters = parameters;
    this.unitCache = new Map();
    this.terrainHeightCache = new Map();
    this.coverCache = new Map();
  }

  calculateThreat(
    source: CombatUnit,
    target: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number
  ): ThreatAssessmentResult {
    this.unitCache = allUnits;

    const estimatedDamage = this.estimateDamage(source, target, getTileHeight, getCoverBonus);
    const canAttack = this.canAttackThisTurn(source, target);
    const distance = cubeDistance(source.coords, target.coords);
    const canReach = this.calculateReachability(source, target, distance);

    const damageThreat = this.calculateDamageThreat(estimatedDamage, source, target);
    const positionalThreat = this.calculatePositionalThreat(source, target, distance, getTileHeight);
    const statusThreat = this.calculateStatusThreat(target);
    const priorityThreat = this.calculatePriorityThreat(target, allUnits);

    const threatLevel = this.combineThreatComponents(
      damageThreat,
      positionalThreat,
      statusThreat,
      priorityThreat
    );

    return {
      sourceUnitId: source.id,
      targetUnitId: target.id,
      threatLevel: clamp(threatLevel, 0, 100),
      components: {
        damageThreat,
        positionalThreat,
        statusThreat,
        priorityThreat,
      },
      canAttack,
      canReach,
      estimatedDamage,
    };
  }

  estimateDamage(
    source: CombatUnit,
    target: CombatUnit,
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number
  ): number {
    const baseAttack = source.stats.attack;
    const magicAttack = source.stats.magicAttack;
    const attackPower = Math.max(baseAttack, magicAttack);

    const defense = target.stats.defense;
    const magicDefense = target.stats.magicDefense;
    const defensePower = baseAttack >= magicAttack ? defense : magicDefense;

    let rawDamage = Math.max(attackPower - defensePower * 0.5, attackPower * 0.1);

    const heightBonus = this.calculateHeightBonus(source, target, getTileHeight);
    rawDamage *= (1 + heightBonus);

    const coverPenalty = getCoverBonus ? getCoverBonus(target.coords, source.coords) : 0;
    rawDamage *= (1 - coverPenalty);

    const hpRatio = target.stats.hp / target.stats.maxHp;
    if (hpRatio < 0.2) {
      rawDamage *= 1.3;
    }

    const critMultiplier = 1 + source.stats.critRate * source.stats.critDamage * 0.5;
    rawDamage *= critMultiplier;

    const debuffMultiplier = this.calculateStatusDamageModifier(source, target);
    rawDamage *= debuffMultiplier;

    return Math.round(rawDamage * 10) / 10;
  }

  canAttackThisTurn(source: CombatUnit, target: CombatUnit): boolean {
    if (!source.isAlive || !target.isAlive) return false;
    if (source.hasActed) return false;

    const attackRange = source.stats.attackRange;
    const moveRange = source.hasMoved ? 0 : source.stats.moveRange;
    const totalReach = moveRange + attackRange;
    const distance = cubeDistance(source.coords, target.coords);

    return distance <= totalReach;
  }

  getThreatMap(
    source: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number
  ): Map<ID, ThreatAssessmentResult> {
    const threatMap = new Map<ID, ThreatAssessmentResult>();
    const enemies = this.getEnemyUnits(source, allUnits);

    for (const enemy of enemies) {
      const assessment = this.calculateThreat(
        source,
        enemy,
        allUnits,
        getTileHeight,
        getCoverBonus
      );
      threatMap.set(enemy.id, assessment);
    }

    return threatMap;
  }

  getAggregatedThreatMap(
    faction: string,
    allUnits: Map<ID, CombatUnit>,
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number
  ): Map<ID, number> {
    const aggregatedThreat = new Map<ID, number>();
    const alliedUnits: CombatUnit[] = [];
    const enemyUnits: CombatUnit[] = [];

    for (const unit of allUnits.values()) {
      if (!unit.isAlive) continue;
      if (unit.faction === faction) {
        alliedUnits.push(unit);
      } else {
        enemyUnits.push(unit);
        aggregatedThreat.set(unit.id, 0);
      }
    }

    for (const enemy of enemyUnits) {
      let totalThreat = 0;
      for (const ally of alliedUnits) {
        const assessment = this.calculateThreat(
          enemy,
          ally,
          allUnits,
          getTileHeight,
          getCoverBonus
        );
        totalThreat += assessment.threatLevel;
      }
      aggregatedThreat.set(enemy.id, totalThreat);
    }

    return aggregatedThreat;
  }

  private calculateDamageThreat(
    estimatedDamage: number,
    source: CombatUnit,
    target: CombatUnit
  ): number {
    const damageRatio = estimatedDamage / Math.max(target.stats.maxHp, 1);
    const hpRatio = target.stats.hp / Math.max(target.stats.maxHp, 1);
    const finishBonus = hpRatio < damageRatio ? 50 : 0;

    return mapRange(damageRatio, 0, 0.5, 0, 50) * this.parameters.damage + finishBonus;
  }

  private calculatePositionalThreat(
    source: CombatUnit,
    target: CombatUnit,
    distance: number,
    getTileHeight?: (coords: CubeCoords) => number
  ): number {
    const attackRange = source.stats.attackRange;
    const moveRange = source.stats.moveRange;
    const visionRange = source.stats.visionRange;

    let proximityScore = 0;
    if (distance <= attackRange) {
      proximityScore = 100;
    } else if (distance <= attackRange + moveRange) {
      proximityScore = mapRange(distance, attackRange, attackRange + moveRange, 100, 60);
    } else if (distance <= visionRange) {
      proximityScore = mapRange(distance, attackRange + moveRange, visionRange, 60, 20);
    } else {
      proximityScore = 10;
    }

    let heightBonus = 0;
    if (getTileHeight) {
      const sourceHeight = getTileHeight(source.coords);
      const targetHeight = getTileHeight(target.coords);
      const heightDiff = sourceHeight - targetHeight;
      heightBonus = mapRange(Math.abs(heightDiff), 0, 3, 0, 20) * Math.sign(heightDiff);
    }

    return (proximityScore * 0.8 + Math.max(heightBonus, 0) * 0.2) * this.parameters.proximity;
  }

  private calculateStatusThreat(target: CombatUnit): number {
    let statusScore = 0;
    const debuffs = target.statusEffects.filter(s => s.isDebuff);

    if (debuffs.length > 0) {
      const totalDuration = debuffs.reduce((sum, d) => sum + d.duration, 0);
      const totalStacks = debuffs.reduce((sum, d) => sum + d.stackCount, 0);
      statusScore = mapRange(totalDuration + totalStacks * 2, 0, 15, 0, 60);
    }

    const hpRatio = target.stats.hp / Math.max(target.stats.maxHp, 1);
    if (hpRatio < 0.3) {
      statusScore += mapRange(hpRatio, 0.3, 0, 0, 40);
    }

    return statusScore * this.parameters.statusEffects;
  }

  private calculatePriorityThreat(
    target: CombatUnit,
    allUnits: Map<ID, CombatUnit>
  ): number {
    let priorityScore = 0;

    const hpRatio = target.stats.hp / Math.max(target.stats.maxHp, 1);
    if (hpRatio < 0.5) {
      priorityScore += mapRange(hpRatio, 0.5, 0, 0, 50);
    }
    if (hpRatio < 0.25) {
      priorityScore += 20;
    }

    const hasHealSkill = target.skills.some(s =>
      s.tags.includes('heal') || s.effects.some(e => e.type === 'heal')
    );
    if (hasHealSkill) {
      priorityScore += 40 * this.parameters.isHealer;
    }

    const hasCastingSkill = target.skills.some(s =>
      s.castTime > 0 || s.tags.includes('aoe') || s.tags.includes('magic')
    );
    if (hasCastingSkill) {
      priorityScore += 30 * this.parameters.isCaster;
    }

    const isIsolated = this.checkIsolation(target, allUnits);
    if (isIsolated) {
      priorityScore += 20;
    }

    if (target.castingSkill) {
      priorityScore += 35;
    }

    return clamp(priorityScore, 0, 100);
  }

  private combineThreatComponents(
    damageThreat: number,
    positionalThreat: number,
    statusThreat: number,
    priorityThreat: number
  ): number {
    return (
      damageThreat * this.parameters.damage +
      positionalThreat * this.parameters.proximity +
      statusThreat * this.parameters.hpPercentage +
      priorityThreat * (this.parameters.isHealer + this.parameters.isCaster) / 2
    );
  }

  private calculateHeightBonus(
    source: CombatUnit,
    target: CombatUnit,
    getTileHeight?: (coords: CubeCoords) => number
  ): number {
    if (!getTileHeight) return 0;
    const sourceHeight = getTileHeight(source.coords) + source.stats.height;
    const targetHeight = getTileHeight(target.coords) + target.stats.height;
    const heightDiff = sourceHeight - targetHeight;
    return clamp(heightDiff * 0.05, -0.2, 0.25);
  }

  private calculateStatusDamageModifier(source: CombatUnit, target: CombatUnit): number {
    let multiplier = 1;

    for (const status of source.statusEffects) {
      if (!status.isDebuff) {
        for (const effect of status.effects) {
          if (effect.stat === 'attack' || effect.stat === 'magicAttack') {
            if (effect.modifierType === 'multiply') {
              multiplier *= effect.value;
            } else if (effect.modifierType === 'add') {
              multiplier += effect.value * 0.01;
            }
          }
        }
      }
    }

    for (const status of target.statusEffects) {
      if (status.isDebuff) {
        for (const effect of status.effects) {
          if (effect.stat === 'defense' || effect.stat === 'magicDefense') {
            if (effect.modifierType === 'multiply') {
              multiplier *= (2 - effect.value);
            } else if (effect.modifierType === 'add' && effect.value < 0) {
              multiplier += Math.abs(effect.value) * 0.005;
            }
          }
        }
      }
    }

    return clamp(multiplier, 0.5, 2);
  }

  private calculateReachability(source: CombatUnit, target: CombatUnit, distance: number): number {
    const attackRange = source.stats.attackRange;
    const moveRange = source.stats.moveRange;
    const visionRange = source.stats.visionRange;

    if (distance <= attackRange) return 100;
    if (distance <= attackRange + moveRange * 0.5) return 80;
    if (distance <= attackRange + moveRange) return 60;
    if (distance <= visionRange) return 30;
    return 10;
  }

  private getEnemyUnits(source: CombatUnit, allUnits: Map<ID, CombatUnit>): CombatUnit[] {
    const enemies: CombatUnit[] = [];
    for (const unit of allUnits.values()) {
      if (unit.isAlive && unit.faction !== source.faction) {
        enemies.push(unit);
      }
    }
    return enemies;
  }

  private checkIsolation(target: CombatUnit, allUnits: Map<ID, CombatUnit>): boolean {
    let nearbyAllies = 0;
    for (const unit of allUnits.values()) {
      if (unit.id === target.id || !unit.isAlive) continue;
      if (unit.faction === target.faction) {
        const distance = cubeDistance(unit.coords, target.coords);
        if (distance <= 2) nearbyAllies++;
      }
    }
    return nearbyAllies === 0;
  }

  toJSON(): Record<string, unknown> {
    return {
      parameters: this.parameters,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    if (data.parameters) {
      this.parameters = data.parameters as AIParameters['threatWeight'];
    }
    this.unitCache.clear();
    this.terrainHeightCache.clear();
    this.coverCache.clear();
  }
}
