import type {
  ID,
  CubeCoords,
  CombatUnit,
  Skill,
  AIProfile,
  AIDecision,
  PositionEvaluation,
  ThreatAssessment as ThreatAssessmentResult,
  AIParameters,
  AIRole,
  PositionDangerInfo,
} from '../types';
import { ThreatAssessment } from './ThreatAssessment';
import {
  cubeDistance,
  getTilesInRange,
  cubeSpiral,
  cubeKey,
  cubeEquals,
} from '../grid/coords';
import { clamp, normalize, mapRange, weightedRandom, Random } from '../utils';

export interface TacticalAIConfig {
  aggression: number;
  defensiveness: number;
  supportiveness: number;
  caution: number;
  randomSeed?: number;
}

export class TacticalAI {
  private profile: AIProfile;
  private parameters: AIParameters;
  private threatAssessor: ThreatAssessment;
  private random: Random;
  private aggression: number;
  private defensiveness: number;
  private lastDecisions: Map<ID, AIDecision>;

  constructor(profile: AIProfile, config?: Partial<TacticalAIConfig>) {
    this.profile = profile;
    this.parameters = profile.parameters;
    this.threatAssessor = new ThreatAssessment(profile.parameters.threatWeight);
    this.random = new Random(config?.randomSeed ?? Date.now());
    this.aggression = config?.aggression ?? profile.aggression;
    this.defensiveness = config?.defensiveness ?? profile.defensiveness;
    this.lastDecisions = new Map();
  }

  setAggression(value: number): void {
    this.aggression = clamp(value, 0, 1);
  }

  setDefensiveness(value: number): void {
    this.defensiveness = clamp(value, 0, 1);
  }

  makeDecision(
    unit: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    reachableTiles?: CubeCoords[],
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): AIDecision {
    const enemies = this.getUnitsByFaction(unit, allUnits, false);
    const allies = this.getUnitsByFaction(unit, allUnits, true);

    const threatMap = this.threatAssessor.getThreatMap(
      unit,
      allUnits,
      getTileHeight,
      getCoverBonus
    );

    const hpRatio = unit.stats.hp / Math.max(unit.stats.maxHp, 1);
    const isLowHp = hpRatio < this.parameters.riskAssessment.hpSafetyThreshold;

    if (isLowHp && this.defensiveness > 0.6) {
      const retreatDecision = this.tryRetreat(
        unit,
        allUnits,
        reachableTiles,
        threatMap,
        getTileHeight,
        getCoverBonus,
        isPassable
      );
      if (retreatDecision) return this.finalizeDecision(unit, retreatDecision);
    }

    const allyNeedsHeal = this.findAllyNeedingHeal(unit, allies);
    if (allyNeedsHeal && this.profile.supportiveness > 0.5) {
      const healDecision = this.tryHealAlly(unit, allyNeedsHeal, getTileHeight, getCoverBonus);
      if (healDecision) return this.finalizeDecision(unit, healDecision);
    }

    const targetEvaluations = this.evaluateTarget(unit, enemies, threatMap);
    if (targetEvaluations.length === 0) {
      const waitDecision = this.createWaitDecision(unit, '无可用目标');
      return this.finalizeDecision(unit, waitDecision);
    }

    const primaryTarget = targetEvaluations[0];
    const targetUnit = allUnits.get(primaryTarget.unitId);
    if (!targetUnit) {
      const waitDecision = this.createWaitDecision(unit, '目标无效');
      return this.finalizeDecision(unit, waitDecision);
    }

    const positions = reachableTiles ?? this.getReachablePositions(unit, isPassable);
    const positionEvaluations = this.evaluatePosition(
      unit,
      positions,
      targetUnit,
      allUnits,
      threatMap,
      getTileHeight,
      getCoverBonus,
      hasLineOfSight
    );

    const bestPosition = positionEvaluations.length > 0
      ? positionEvaluations.reduce((best, curr) => curr.score > best.score ? curr : best)
      : { coords: unit.coords, score: 0 } as PositionEvaluation;

    const selectedSkill = this.selectSkill(unit, targetUnit, bestPosition.coords);

    const flankInfo = this.flankAndFocus(
      unit,
      targetUnit,
      allies,
      bestPosition.coords,
      allUnits
    );

    const decision = this.chooseAction(
      unit,
      targetUnit,
      bestPosition,
      selectedSkill,
      flankInfo,
      threatMap,
      getTileHeight,
      getCoverBonus,
      hasLineOfSight
    );

    return this.finalizeDecision(unit, decision);
  }

  evaluateTarget(
    unit: CombatUnit,
    enemies: CombatUnit[],
    threatMap: Map<ID, ThreatAssessmentResult>
  ): Array<{ unitId: ID; score: number; reasons: string[] }> {
    const weights = this.parameters.targetWeight;
    const evaluations: Array<{ unitId: ID; score: number; reasons: string[] }> = [];

    for (const enemy of enemies) {
      let score = 0;
      const reasons: string[] = [];
      const threat = threatMap.get(enemy.id);
      const distance = cubeDistance(unit.coords, enemy.coords);

      const hpRatio = enemy.stats.hp / Math.max(enemy.stats.maxHp, 1);
      const lowestHpScore = (1 - hpRatio) * 100 * weights.lowestHp;
      score += lowestHpScore;
      if (lowestHpScore > 30) reasons.push(`低血量目标(${Math.round(lowestHpScore)}分)`);

      const threatScore = threat ? threat.threatLevel * weights.highestThreat : 50;
      score += threatScore;
      if (threatScore > 60) reasons.push(`高威胁目标(${Math.round(threatScore)}分)`);

      const attackRange = unit.stats.attackRange;
      const moveRange = unit.stats.moveRange;
      const proximityScore = mapRange(distance, 0, attackRange + moveRange, 100, 0) * weights.closest;
      score += proximityScore;
      if (proximityScore > 50) reasons.push(`近距离目标(${Math.round(proximityScore)}分)`);

      const isolatedScore = this.calculateIsolationScore(enemy, enemies) * weights.isolated;
      score += isolatedScore;
      if (isolatedScore > 40) reasons.push(`孤立目标(${Math.round(isolatedScore)}分)`);

      if (threat?.canAttack) {
        score += 25 * this.aggression;
        reasons.push('可立即攻击(+25)');
      }

      if (enemy.castingSkill) {
        score += 30;
        reasons.push('施法中目标(+30)');
      }

      const aggressionBonus = this.aggression * 15;
      score += aggressionBonus;

      evaluations.push({
        unitId: enemy.id,
        score: clamp(score, 0, 1000),
        reasons,
      });
    }

    evaluations.sort((a, b) => b.score - a.score);
    return evaluations;
  }

  evaluatePosition(
    unit: CombatUnit,
    positions: CubeCoords[],
    target: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    threatMap: Map<ID, ThreatAssessmentResult>,
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): PositionEvaluation[] {
    const config = this.parameters.positioning;
    const evaluations: PositionEvaluation[] = [];
    const enemies = this.getUnitsByFaction(unit, allUnits, false);
    const allies = this.getUnitsByFaction(unit, allUnits, true);

    for (const pos of positions) {
      const factors = {
        coverBonus: 0,
        heightBonus: 0,
        visionBonus: 0,
        movementCost: 0,
        threatExposure: 0,
        attackOpportunities: 0,
        supportOpportunities: 0,
        distanceToTarget: 0,
      };

      if (getCoverBonus) {
        let totalCover = 0;
        for (const enemy of enemies) {
          totalCover += getCoverBonus(pos, enemy.coords);
        }
        factors.coverBonus = (totalCover / Math.max(enemies.length, 1)) * 100 * config.preferCover;
      }

      if (getTileHeight) {
        const posHeight = getTileHeight(pos);
        const avgEnemyHeight = enemies.length > 0
          ? enemies.reduce((s, e) => s + getTileHeight(e.coords), 0) / enemies.length
          : 0;
        const heightDiff = posHeight - avgEnemyHeight;
        factors.heightBonus = mapRange(heightDiff, -3, 3, 0, 100) * config.preferHighGround;
      }

      const visibleCount = this.countVisibleUnits(pos, enemies, unit.stats.visionRange, hasLineOfSight);
      factors.visionBonus = mapRange(visibleCount, 0, enemies.length, 0, 100);

      const distanceFromOrigin = cubeDistance(unit.coords, pos);
      factors.movementCost = mapRange(distanceFromOrigin, 0, unit.stats.moveRange, 0, 50);

      let totalThreat = 0;
      for (const enemy of enemies) {
        const distanceToEnemy = cubeDistance(pos, enemy.coords);
        if (distanceToEnemy <= enemy.stats.attackRange + 1) {
          totalThreat += 80;
        } else if (distanceToEnemy <= enemy.stats.attackRange + enemy.stats.moveRange) {
          totalThreat += 40;
        }
      }
      factors.threatExposure = mapRange(totalThreat, 0, 200, 100, 0) * this.defensiveness;

      const attackRange = unit.stats.attackRange;
      const distanceToTarget = cubeDistance(pos, target.coords);
      if (distanceToTarget <= attackRange) {
        factors.attackOpportunities = 100;
      } else if (distanceToTarget <= attackRange + 2) {
        factors.attackOpportunities = 70;
      } else {
        factors.attackOpportunities = mapRange(distanceToTarget, attackRange, attackRange + 5, 50, 0);
      }

      let supportScore = 0;
      for (const ally of allies) {
        if (ally.id === unit.id) continue;
        const distToAlly = cubeDistance(pos, ally.coords);
        if (distToAlly <= 2) {
          supportScore += 20;
          const allyHpRatio = ally.stats.hp / Math.max(ally.stats.maxHp, 1);
          if (allyHpRatio < 0.5) supportScore += 15;
        }
      }
      factors.supportOpportunities = clamp(supportScore, 0, 100) * this.profile.supportiveness;

      factors.distanceToTarget = mapRange(
        distanceToTarget,
        0,
        unit.stats.moveRange + unit.stats.attackRange,
        100,
        0
      );

      const clusteringPenalty = this.calculateClusteringPenalty(pos, allies, config.avoidClustering);

      const totalScore = (
        factors.coverBonus +
        factors.heightBonus +
        factors.visionBonus +
        factors.threatExposure +
        factors.attackOpportunities * this.aggression +
        factors.supportOpportunities +
        factors.distanceToTarget * 0.5
      ) - factors.movementCost * 0.3 - clusteringPenalty;

      const reachableUnits: ID[] = [];
      const visibleUnits: ID[] = [];
      for (const enemy of enemies) {
        if (cubeDistance(pos, enemy.coords) <= unit.stats.attackRange) {
          reachableUnits.push(enemy.id);
        }
        if (cubeDistance(pos, enemy.coords) <= unit.stats.visionRange) {
          visibleUnits.push(enemy.id);
        }
      }

      evaluations.push({
        coords: pos,
        score: clamp(totalScore, -100, 200),
        factors,
        reachableUnits,
        visibleUnits,
      });
    }

    evaluations.sort((a, b) => b.score - a.score);
    return evaluations;
  }

  selectSkill(
    unit: CombatUnit,
    target: CombatUnit,
    fromPosition: CubeCoords
  ): Skill | null {
    const config = this.parameters.skillSelection;
    const availableSkills = unit.skills.filter(s =>
      s.type === 'active' && s.currentCooldown === 0 && unit.stats.mp >= s.mpCost
    );

    if (availableSkills.length === 0) return null;

    const skillScores: Array<{ skill: Skill; score: number }> = [];
    const distance = cubeDistance(fromPosition, target.coords);
    const targetHpRatio = target.stats.hp / Math.max(target.stats.maxHp, 1);

    for (const skill of availableSkills) {
      if (distance < skill.range.min || distance > skill.range.max) continue;

      let score = 0;

      for (const effect of skill.effects) {
        switch (effect.type) {
          case 'damage': {
            const baseDamageScore = mapRange(effect.value, 0, unit.stats.attack * 3, 0, 100);
            score += baseDamageScore;
            if (targetHpRatio * target.stats.maxHp <= effect.value * config.finishThreshold) {
              score += 60;
            }
            if (effect.aoeRadius) {
              score += 30 * config.preferAoe;
            }
            break;
          }
          case 'heal': {
            if (skill.canTargetAlly) {
              score += 50 * config.preferHeal;
            }
            break;
          }
          case 'buff': {
            score += 40 * config.preferBuff;
            break;
          }
          case 'debuff': {
            score += 35 * config.preferDebuff;
            if (effect.statusDuration && effect.statusDuration > 2) {
              score += 15;
            }
            break;
          }
        }
      }

      const mpEfficiency = 1 - (skill.mpCost / Math.max(unit.stats.mp, 1));
      score *= (0.5 + mpEfficiency * 0.5);

      if (skill.currentCooldown > 0) score *= 0.5;

      skillScores.push({ skill, score: clamp(score, 0, 500) });
    }

    if (skillScores.length === 0) return null;

    skillScores.sort((a, b) => b.score - a.score);
    const topSkills = skillScores.slice(0, Math.min(3, skillScores.length));

    return weightedRandom(
      topSkills.map(s => ({ value: s.skill, weight: Math.max(s.score, 1) }))
    );
  }

  flankAndFocus(
    unit: CombatUnit,
    target: CombatUnit,
    allies: CombatUnit[],
    position: CubeCoords,
    allUnits: Map<ID, CombatUnit>
  ): {
    isFlanking: boolean;
    isFocusing: boolean;
    focusCount: number;
    flankAngle: number;
    blockingOptions: CubeCoords[];
  } {
    const attackingAllies = allies.filter(a => {
      if (a.id === unit.id || !a.isAlive) return false;
      const dist = cubeDistance(a.coords, target.coords);
      return dist <= a.stats.attackRange + a.stats.moveRange;
    });

    const focusCount = attackingAllies.length + 1;
    const isFocusing = focusCount >= 2 && this.aggression > 0.4;

    let isFlanking = false;
    let flankAngle = 0;
    const blockingOptions: CubeCoords[] = [];

    if (allies.length > 1) {
      const allyPositions = allies.filter(a => a.id !== unit.id && a.isAlive).map(a => a.coords);
      if (allyPositions.length > 0) {
        const centroid = this.calculateCentroid(allyPositions);
        const allyToTarget = cubeDistance(centroid, target.coords);
        const unitToTarget = cubeDistance(position, target.coords);
        const allyToUnit = cubeDistance(centroid, position);

        if (allyToTarget > 0 && unitToTarget > 0 && allyToUnit > 0) {
          const angle = Math.acos(
            clamp(
              (allyToTarget ** 2 + unitToTarget ** 2 - allyToUnit ** 2) / (2 * allyToTarget * unitToTarget),
              -1,
              1
            )
          );
          flankAngle = (angle * 180) / Math.PI;
          if (flankAngle > 60) {
            isFlanking = true;
          }
        }
      }
    }

    if (isFocusing && this.aggression > 0.5) {
      const targetNeighbors = cubeSpiral(target.coords, 1).slice(1);
      for (const tile of targetNeighbors) {
        const hasUnit = allUnits.has(cubeKey(tile) as unknown as ID) ||
          Array.from(allUnits.values()).some(u => cubeEquals(u.coords, tile));
        if (!hasUnit) {
          blockingOptions.push(tile);
        }
      }
    }

    return {
      isFlanking,
      isFocusing,
      focusCount,
      flankAngle,
      blockingOptions,
    };
  }

  isRanged(unit: CombatUnit): boolean {
    if (unit.stats.attackRange > 1) return true;
    for (const skill of unit.skills) {
      if (skill.type === 'active' && skill.range.max > 1) {
        return true;
      }
    }
    return false;
  }

  isHealer(unit: CombatUnit): boolean {
    return unit.skills.some(s =>
      s.type === 'active' &&
      s.canTargetAlly &&
      s.effects.some(e => e.type === 'heal')
    );
  }

  isTank(unit: CombatUnit): boolean {
    const defenseScore = unit.stats.defense + unit.stats.magicDefense;
    const attackScore = unit.stats.attack + unit.stats.magicAttack;
    const hasTaunt = unit.skills.some(s => s.tags.includes('taunt'));
    return (defenseScore > attackScore * 1.2 && unit.stats.maxHp > 100) || hasTaunt;
  }

  isSupport(unit: CombatUnit): boolean {
    const buffCount = unit.skills.filter(s =>
      s.type === 'active' &&
      s.canTargetAlly &&
      s.effects.some(e => e.type === 'buff')
    ).length;
    return buffCount >= 2;
  }

  isScout(unit: CombatUnit): boolean {
    return unit.stats.moveRange >= 5 && unit.stats.visionRange >= 6;
  }

  detectAIRole(unit: CombatUnit): AIRole {
    if (this.isHealer(unit)) return 'healer';
    if (this.isTank(unit)) return 'tank';
    if (this.isSupport(unit)) return 'support';
    if (this.isScout(unit)) return 'scout';
    if (this.isRanged(unit)) return 'ranged';
    return 'melee';
  }

  getUnitRole(unit: CombatUnit): AIRole {
    if (unit.aiProfile && this.profile.aiRole) {
      return this.profile.aiRole;
    }
    return this.detectAIRole(unit);
  }

  makeRoleBasedDecision(
    unit: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    reachableTiles?: CubeCoords[],
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): AIDecision {
    const role = this.getUnitRole(unit);

    switch (role) {
      case 'ranged':
        return this.makeRangedDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
      case 'healer':
        return this.makeHealerDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
      case 'tank':
        return this.makeTankDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
      case 'support':
        return this.makeSupportDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
      case 'scout':
        return this.makeScoutDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
      case 'melee':
      default:
        return this.makeDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
    }
  }

  private makeRangedDecision(
    unit: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    reachableTiles?: CubeCoords[],
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): AIDecision {
    const enemies = this.getUnitsByFaction(unit, allUnits, false);
    const threatMap = this.threatAssessor.getThreatMap(unit, allUnits, getTileHeight, getCoverBonus);

    const dangerThreshold = 60;
    const currentDanger = this.calculatePositionDanger(unit.coords, enemies).dangerScore;

    if (currentDanger > dangerThreshold && this.defensiveness > 0.4) {
      const kiteDecision = this.tryKite(unit, enemies, reachableTiles, getTileHeight, getCoverBonus, isPassable);
      if (kiteDecision) return this.finalizeDecision(unit, kiteDecision);
    }

    const targets = this.threatAssessor.getPrioritizedTargets(unit, enemies, 'threat', allUnits);
    if (targets.length === 0) {
      return this.finalizeDecision(unit, this.createWaitDecision(unit, '无可用目标'));
    }

    const primaryTarget = targets[0].unit;
    const positions = reachableTiles ?? this.getReachablePositions(unit, isPassable);
    const bestPosition = this.findBestKitingPosition(unit, positions, primaryTarget, enemies, getTileHeight);

    const selectedSkill = this.selectSkill(unit, primaryTarget, bestPosition);

    const inRange = cubeDistance(bestPosition, primaryTarget.coords) <= unit.stats.attackRange;

    if (selectedSkill && inRange && selectedSkill.canTargetEnemy) {
      return this.finalizeDecision(unit, {
        unitId: unit.id,
        action: 'skill',
        targetCoords: primaryTarget.coords,
        targetUnitId: primaryTarget.id,
        skillId: selectedSkill.id,
        confidence: 0.9,
        reasoning: `远程单位使用技能【${selectedSkill.name}】攻击目标`,
        alternatives: [],
      });
    }

    if (inRange && !unit.hasActed) {
      return this.finalizeDecision(unit, {
        unitId: unit.id,
        action: 'attack',
        targetCoords: primaryTarget.coords,
        targetUnitId: primaryTarget.id,
        confidence: 0.8,
        reasoning: '远程单位攻击目标',
        alternatives: [],
      });
    }

    if (!cubeEquals(unit.coords, bestPosition) && !unit.hasMoved) {
      return this.finalizeDecision(unit, this.createMoveDecision(unit, bestPosition, '移动到最佳射击位置'));
    }

    return this.finalizeDecision(unit, this.createWaitDecision(unit, '等待目标进入射程'));
  }

  private makeHealerDecision(
    unit: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    reachableTiles?: CubeCoords[],
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): AIDecision {
    const allies = this.getUnitsByFaction(unit, allUnits, true);
    const enemies = this.getUnitsByFaction(unit, allUnits, false);

    const healTarget = this.findLowestHpAlly(unit, allies);
    const healThreshold = this.parameters.riskAssessment.hpSafetyThreshold + 0.1;

    if (healTarget && healTarget.stats.hp / Math.max(healTarget.stats.maxHp, 1) < healThreshold) {
      const healDecision = this.tryHealAlly(unit, healTarget, getTileHeight, getCoverBonus);
      if (healDecision) return this.finalizeDecision(unit, healDecision);

      const positions = reachableTiles ?? this.getReachablePositions(unit, isPassable);
      const bestPos = this.findBestHealerPosition(unit, positions, allies, enemies, getTileHeight);
      if (!cubeEquals(unit.coords, bestPos) && !unit.hasMoved) {
        return this.finalizeDecision(unit, this.createMoveDecision(unit, bestPos, '移动到治疗位置'));
      }
    }

    const dangerThreshold = 50;
    const currentDanger = this.calculatePositionDanger(unit.coords, enemies).dangerScore;
    if (currentDanger > dangerThreshold) {
      const retreatDecision = this.tryRetreat(unit, allUnits, reachableTiles, new Map(), getTileHeight, getCoverBonus, isPassable);
      if (retreatDecision) return this.finalizeDecision(unit, retreatDecision);
    }

    return this.makeDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
  }

  private makeTankDecision(
    unit: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    reachableTiles?: CubeCoords[],
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): AIDecision {
    const allies = this.getUnitsByFaction(unit, allUnits, true);
    const enemies = this.getUnitsByFaction(unit, allUnits, false);

    const hasTauntSkill = unit.skills.some(s =>
      s.type === 'active' && s.tags.includes('taunt') && s.currentCooldown === 0 && unit.stats.mp >= s.mpCost
    );

    if (hasTauntSkill && enemies.length > 0) {
      const nearbyEnemies = enemies.filter(e =>
        cubeDistance(unit.coords, e.coords) <= 3
      );
      if (nearbyEnemies.length >= 2) {
        const tauntSkill = unit.skills.find(s => s.tags.includes('taunt'))!;
        return this.finalizeDecision(unit, {
          unitId: unit.id,
          action: 'skill',
          targetCoords: unit.coords,
          targetUnitId: unit.id,
          skillId: tauntSkill.id,
          confidence: 0.85,
          reasoning: '坦克使用嘲讽技能吸引敌人',
          alternatives: [],
        });
      }
    }

    const healer = allies.find(a => this.isHealer(a));
    if (healer && !unit.hasMoved) {
      const positions = reachableTiles ?? this.getReachablePositions(unit, isPassable);
      const bestPos = this.findTankPosition(unit, positions, healer, enemies, getTileHeight);
      if (!cubeEquals(unit.coords, bestPos)) {
        return this.finalizeDecision(unit, this.createMoveDecision(unit, bestPos, '坦克站位保护队友'));
      }
    }

    return this.makeDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
  }

  private makeSupportDecision(
    unit: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    reachableTiles?: CubeCoords[],
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): AIDecision {
    const allies = this.getUnitsByFaction(unit, allUnits, true);

    const buffSkill = unit.skills.find(s =>
      s.type === 'active' &&
      s.canTargetAlly &&
      s.effects.some(e => e.type === 'buff') &&
      s.currentCooldown === 0 &&
      unit.stats.mp >= s.mpCost
    );

    if (buffSkill) {
      const target = this.findAllyNeedingBuff(unit, allies);
      if (target) {
        const distance = cubeDistance(unit.coords, target.coords);
        if (distance >= buffSkill.range.min && distance <= buffSkill.range.max) {
          return this.finalizeDecision(unit, {
            unitId: unit.id,
            action: 'skill',
            targetCoords: target.coords,
            targetUnitId: target.id,
            skillId: buffSkill.id,
            confidence: 0.8,
            reasoning: `辅助单位给【${target.name}】上Buff`,
            alternatives: [],
          });
        }
      }
    }

    return this.makeDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
  }

  private makeScoutDecision(
    unit: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    reachableTiles?: CubeCoords[],
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): AIDecision {
    const enemies = this.getUnitsByFaction(unit, allUnits, false);

    if (enemies.length === 0 && !unit.hasMoved) {
      const positions = reachableTiles ?? this.getReachablePositions(unit, isPassable);
      const scoutPos = this.findBestScoutPosition(unit, positions, getTileHeight);
      if (!cubeEquals(unit.coords, scoutPos)) {
        return this.finalizeDecision(unit, this.createMoveDecision(unit, scoutPos, '侦查单位探索前方'));
      }
    }

    return this.makeDecision(unit, allUnits, reachableTiles, getTileHeight, getCoverBonus, isPassable, hasLineOfSight);
  }

  private tryKite(
    unit: CombatUnit,
    enemies: CombatUnit[],
    reachableTiles?: CubeCoords[],
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): AIDecision | null {
    const positions = reachableTiles ?? this.getReachablePositions(unit, isPassable);
    if (positions.length === 0) return null;

    let bestPos: CubeCoords = unit.coords;
    let bestScore = -Infinity;

    for (const pos of positions) {
      let score = 0;

      let minEnemyDist = Infinity;
      for (const enemy of enemies) {
        const dist = cubeDistance(pos, enemy.coords);
        if (dist < minEnemyDist) minEnemyDist = dist;
      }
      score += minEnemyDist * 10;

      const inAttackRange = enemies.some(e =>
        cubeDistance(pos, e.coords) <= unit.stats.attackRange
      );
      if (inAttackRange) score += 50;

      if (getTileHeight) {
        score += getTileHeight(pos) * 15;
      }

      if (getCoverBonus) {
        let totalCover = 0;
        for (const enemy of enemies) {
          totalCover += getCoverBonus(pos, enemy.coords);
        }
        score += totalCover * 100;
      }

      if (score > bestScore) {
        bestScore = score;
        bestPos = pos;
      }
    }

    if (!cubeEquals(unit.coords, bestPos)) {
      return this.createMoveDecision(unit, bestPos, '风筝战术：保持距离并攻击');
    }

    return null;
  }

  private findBestKitingPosition(
    unit: CombatUnit,
    positions: CubeCoords[],
    target: CombatUnit,
    enemies: CombatUnit[],
    getTileHeight?: (coords: CubeCoords) => number
  ): CubeCoords {
    let bestPos = unit.coords;
    let bestScore = -Infinity;

    for (const pos of positions) {
      let score = 0;
      const distToTarget = cubeDistance(pos, target.coords);

      const optimalRange = unit.stats.attackRange * 0.8;
      const rangeDiff = Math.abs(distToTarget - optimalRange);
      score -= rangeDiff * 5;

      if (distToTarget <= unit.stats.attackRange) {
        score += 80;
      }

      let totalEnemyDist = 0;
      for (const enemy of enemies) {
        totalEnemyDist += cubeDistance(pos, enemy.coords);
      }
      score += totalEnemyDist * 2;

      if (getTileHeight) {
        score += getTileHeight(pos) * 20;
      }

      if (score > bestScore) {
        bestScore = score;
        bestPos = pos;
      }
    }

    return bestPos;
  }

  private findBestHealerPosition(
    unit: CombatUnit,
    positions: CubeCoords[],
    allies: CombatUnit[],
    enemies: CombatUnit[],
    getTileHeight?: (coords: CubeCoords) => number
  ): CubeCoords {
    let bestPos = unit.coords;
    let bestScore = -Infinity;

    for (const pos of positions) {
      let score = 0;

      let allyInRange = 0;
      for (const ally of allies) {
        if (ally.id === unit.id) continue;
        const dist = cubeDistance(pos, ally.coords);
        if (dist <= unit.stats.attackRange) {
          allyInRange++;
          const hpRatio = ally.stats.hp / Math.max(ally.stats.maxHp, 1);
          score += (1 - hpRatio) * 30;
        }
      }
      score += allyInRange * 20;

      let minEnemyDist = Infinity;
      for (const enemy of enemies) {
        const dist = cubeDistance(pos, enemy.coords);
        if (dist < minEnemyDist) minEnemyDist = dist;
      }
      score += minEnemyDist * 5;

      if (getTileHeight) {
        score += getTileHeight(pos) * 10;
      }

      if (score > bestScore) {
        bestScore = score;
        bestPos = pos;
      }
    }

    return bestPos;
  }

  private findTankPosition(
    unit: CombatUnit,
    positions: CubeCoords[],
    allyToProtect: CombatUnit,
    enemies: CombatUnit[],
    getTileHeight?: (coords: CubeCoords) => number
  ): CubeCoords {
    let bestPos = unit.coords;
    let bestScore = -Infinity;

    for (const pos of positions) {
      let score = 0;

      const distToAlly = cubeDistance(pos, allyToProtect.coords);
      if (distToAlly <= 2) {
        score += 50;
      } else {
        score -= distToAlly * 10;
      }

      let enemyBetween = 0;
      for (const enemy of enemies) {
        const distToEnemy = cubeDistance(pos, enemy.coords);
        if (distToEnemy <= unit.stats.attackRange + 1) {
          enemyBetween++;
        }
      }
      score += enemyBetween * 15;

      if (getTileHeight) {
        score += getTileHeight(pos) * 10;
      }

      if (score > bestScore) {
        bestScore = score;
        bestPos = pos;
      }
    }

    return bestPos;
  }

  private findBestScoutPosition(
    unit: CombatUnit,
    positions: CubeCoords[],
    getTileHeight?: (coords: CubeCoords) => number
  ): CubeCoords {
    let bestPos = unit.coords;
    let bestScore = -Infinity;

    for (const pos of positions) {
      let score = 0;

      const dist = cubeDistance(unit.coords, pos);
      score += dist * 5;

      if (getTileHeight) {
        score += getTileHeight(pos) * 25;
      }

      if (score > bestScore) {
        bestScore = score;
        bestPos = pos;
      }
    }

    return bestPos;
  }

  private findLowestHpAlly(unit: CombatUnit, allies: CombatUnit[]): CombatUnit | null {
    let lowest: CombatUnit | null = null;
    let lowestRatio = 1;

    for (const ally of allies) {
      if (ally.id === unit.id || !ally.isAlive) continue;
      const ratio = ally.stats.hp / Math.max(ally.stats.maxHp, 1);
      if (ratio < lowestRatio) {
        lowestRatio = ratio;
        lowest = ally;
      }
    }

    return lowest;
  }

  private findAllyNeedingBuff(unit: CombatUnit, allies: CombatUnit[]): CombatUnit | null {
    const buffableAllies = allies.filter(ally => {
      if (ally.id === unit.id || !ally.isAlive) return false;
      const dist = cubeDistance(unit.coords, ally.coords);
      return dist <= unit.stats.attackRange;
    });

    if (buffableAllies.length === 0) return null;

    return buffableAllies.reduce((best, current) => {
      const bestScore = this.calculateBuffNeed(best);
      const currentScore = this.calculateBuffNeed(current);
      return currentScore > bestScore ? current : best;
    });
  }

  private calculateBuffNeed(unit: CombatUnit): number {
    let score = 0;
    const debuffCount = unit.statusEffects.filter(s => s.isDebuff).length;
    score += debuffCount * 20;

    const hpRatio = unit.stats.hp / Math.max(unit.stats.maxHp, 1);
    score += (1 - hpRatio) * 30;

    return score;
  }

  calculatePositionDanger(
    position: CubeCoords,
    enemies: CombatUnit[]
  ): PositionDangerInfo {
    return this.threatAssessor.calculatePositionDanger(position, enemies);
  }

  chooseAction(
    unit: CombatUnit,
    target: CombatUnit,
    position: PositionEvaluation,
    skill: Skill | null,
    flankInfo: ReturnType<TacticalAI['flankAndFocus']>,
    threatMap: Map<ID, ThreatAssessmentResult>,
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): AIDecision {
    const shouldMove = !cubeEquals(unit.coords, position.coords) && !unit.hasMoved;
    const distanceToTarget = cubeDistance(position.coords, target.coords);
    const inAttackRange = distanceToTarget <= unit.stats.attackRange;

    if (skill && inAttackRange && skill.canTargetEnemy) {
      if (hasLineOfSight && skill.range.requiresLineOfSight) {
        const hasLOS = hasLineOfSight(position.coords, target.coords);
        if (!hasLOS) {
          if (shouldMove) {
            return this.createMoveDecision(unit, position.coords, '移动到有利位置');
          }
          return this.createWaitDecision(unit, '无技能视野');
        }
      }

      return {
        unitId: unit.id,
        action: 'skill',
        targetCoords: target.coords,
        targetUnitId: target.id,
        skillId: skill.id,
        confidence: 0.9,
        reasoning: `使用技能【${skill.name}】攻击目标`,
        alternatives: [],
      };
    }

    if (inAttackRange && !unit.hasActed) {
      const attackDecision: AIDecision = {
        unitId: unit.id,
        action: 'attack',
        targetCoords: target.coords,
        targetUnitId: target.id,
        confidence: clamp(0.7 + this.aggression * 0.3, 0, 1),
        reasoning: flankInfo.isFocusing
          ? `集火攻击目标(${flankInfo.focusCount}个单位)`
          : '普通攻击目标',
        alternatives: [],
      };

      if (shouldMove && position.score > 10) {
        attackDecision.alternatives.push(
          this.createMoveDecision(unit, position.coords, '移动到更好位置后再攻击')
        );
      }

      return attackDecision;
    }

    if (shouldMove) {
      const moveReason = [];
      if (position.factors.attackOpportunities > 50) moveReason.push('接近目标');
      if (position.factors.coverBonus > 30) moveReason.push('寻找掩护');
      if (position.factors.heightBonus > 30) moveReason.push('抢占高地');
      if (flankInfo.isFlanking) moveReason.push('侧翼包抄');

      return this.createMoveDecision(
        unit,
        position.coords,
        moveReason.length > 0 ? moveReason.join('、') : '战术移动'
      );
    }

    if (this.defensiveness > 0.7 && this.calculateThreatExposure(unit, threatMap) > 70) {
      return this.createWaitDecision(unit, '保持防御姿态');
    }

    if (this.random.chance(100 * this.profile.caution * 0.3)) {
      return {
        unitId: unit.id,
        action: 'delay',
        confidence: 0.5,
        reasoning: '延迟行动观察局势',
        alternatives: [],
      };
    }

    return this.createWaitDecision(unit, '等待更好时机');
  }

  private tryRetreat(
    unit: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    reachableTiles: CubeCoords[] | undefined,
    threatMap: Map<ID, ThreatAssessmentResult>,
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): AIDecision | null {
    const positions = reachableTiles ?? this.getReachablePositions(unit, isPassable);
    const enemies = this.getUnitsByFaction(unit, allUnits, false);
    const allies = this.getUnitsByFaction(unit, allUnits, true);

    if (allies.length <= 1) return null;

    const allyCentroid = this.calculateCentroid(
      allies.filter(a => a.id !== unit.id).map(a => a.coords)
    );

    let bestPos: CubeCoords | null = null;
    let bestScore = -Infinity;

    for (const pos of positions) {
      let score = 0;
      const distToAlly = cubeDistance(pos, allyCentroid);
      score += mapRange(distToAlly, 0, unit.stats.moveRange + 3, 100, 0) * 0.4;

      let totalEnemyDist = 0;
      for (const enemy of enemies) {
        totalEnemyDist += cubeDistance(pos, enemy.coords);
      }
      score += mapRange(totalEnemyDist, 0, enemies.length * 8, 0, 100) * 0.3;

      if (getCoverBonus) {
        let totalCover = 0;
        for (const enemy of enemies) {
          totalCover += getCoverBonus(pos, enemy.coords);
        }
        score += totalCover * 100 * 0.3;
      }

      if (score > bestScore) {
        bestScore = score;
        bestPos = pos;
      }
    }

    if (bestPos && !cubeEquals(unit.coords, bestPos)) {
      return this.createMoveDecision(unit, bestPos, '低血量撤退至友军附近');
    }

    return null;
  }

  private tryHealAlly(
    unit: CombatUnit,
    ally: CombatUnit,
    getTileHeight?: (coords: CubeCoords) => number,
    getCoverBonus?: (coords: CubeCoords, from: CubeCoords) => number
  ): AIDecision | null {
    const healSkill = unit.skills.find(s =>
      s.type === 'active' &&
      s.currentCooldown === 0 &&
      unit.stats.mp >= s.mpCost &&
      s.canTargetAlly &&
      s.effects.some(e => e.type === 'heal')
    );

    if (!healSkill) return null;

    const distance = cubeDistance(unit.coords, ally.coords);
    if (distance >= healSkill.range.min && distance <= healSkill.range.max) {
      return {
        unitId: unit.id,
        action: 'skill',
        targetCoords: ally.coords,
        targetUnitId: ally.id,
        skillId: healSkill.id,
        confidence: 0.85,
        reasoning: `治疗低血量友军【${ally.name}】`,
        alternatives: [],
      };
    }

    return null;
  }

  private findAllyNeedingHeal(unit: CombatUnit, allies: CombatUnit[]): CombatUnit | null {
    const healSkill = unit.skills.find(s =>
      s.type === 'active' &&
      s.canTargetAlly &&
      s.effects.some(e => e.type === 'heal')
    );
    if (!healSkill) return null;

    const threshold = this.parameters.riskAssessment.hpSafetyThreshold + 0.1;
    let mostNeedy: CombatUnit | null = null;
    let lowestRatio = threshold;

    for (const ally of allies) {
      if (ally.id === unit.id || !ally.isAlive) continue;
      const hpRatio = ally.stats.hp / Math.max(ally.stats.maxHp, 1);
      if (hpRatio < lowestRatio) {
        lowestRatio = hpRatio;
        mostNeedy = ally;
      }
    }

    return mostNeedy;
  }

  private getUnitsByFaction(
    unit: CombatUnit,
    allUnits: Map<ID, CombatUnit>,
    isAlly: boolean
  ): CombatUnit[] {
    const result: CombatUnit[] = [];
    for (const u of allUnits.values()) {
      if (!u.isAlive) continue;
      const sameFaction = u.faction === unit.faction;
      if (isAlly ? sameFaction : !sameFaction) {
        result.push(u);
      }
    }
    return result;
  }

  private getReachablePositions(
    unit: CombatUnit,
    isPassable?: (coords: CubeCoords) => boolean
  ): CubeCoords[] {
    const positions = getTilesInRange(unit.coords, unit.stats.moveRange);
    if (!isPassable) return positions;
    return positions.filter(p => isPassable(p) || cubeEquals(p, unit.coords));
  }

  private calculateIsolationScore(target: CombatUnit, allEnemies: CombatUnit[]): number {
    let nearbyAllies = 0;
    for (const enemy of allEnemies) {
      if (enemy.id === target.id) continue;
      if (cubeDistance(enemy.coords, target.coords) <= 2) {
        nearbyAllies++;
      }
    }
    return mapRange(nearbyAllies, 0, 4, 100, 0);
  }

  private calculateClusteringPenalty(
    pos: CubeCoords,
    allies: CombatUnit[],
    weight: number
  ): number {
    let penalty = 0;
    for (const ally of allies) {
      if (ally.id.includes('dummy')) continue;
      const dist = cubeDistance(pos, ally.coords);
      if (dist === 0) penalty += 50;
      else if (dist === 1) penalty += 20;
    }
    return penalty * weight;
  }

  private calculateCentroid(positions: CubeCoords[]): CubeCoords {
    if (positions.length === 0) return { q: 0, r: 0, s: 0 };
    const sum = positions.reduce(
      (acc, p) => ({ q: acc.q + p.q, r: acc.r + p.r, s: acc.s + p.s }),
      { q: 0, r: 0, s: 0 }
    );
    const n = positions.length;
    return {
      q: sum.q / n,
      r: sum.r / n,
      s: sum.s / n,
    };
  }

  private countVisibleUnits(
    pos: CubeCoords,
    units: CombatUnit[],
    visionRange: number,
    hasLineOfSight?: (from: CubeCoords, to: CubeCoords) => boolean
  ): number {
    let count = 0;
    for (const unit of units) {
      const dist = cubeDistance(pos, unit.coords);
      if (dist <= visionRange) {
        if (!hasLineOfSight || hasLineOfSight(pos, unit.coords)) {
          count++;
        }
      }
    }
    return count;
  }

  private calculateThreatExposure(
    unit: CombatUnit,
    threatMap: Map<ID, ThreatAssessmentResult>
  ): number {
    let total = 0;
    for (const threat of threatMap.values()) {
      total += threat.threatLevel;
    }
    return mapRange(total, 0, threatMap.size * 80, 0, 100);
  }

  private createMoveDecision(unit: CombatUnit, target: CubeCoords, reason: string): AIDecision {
    return {
      unitId: unit.id,
      action: 'move',
      targetCoords: target,
      confidence: 0.75,
      reasoning: reason,
      alternatives: [],
    };
  }

  private createWaitDecision(unit: CombatUnit, reason: string): AIDecision {
    return {
      unitId: unit.id,
      action: 'wait',
      confidence: 0.4,
      reasoning: reason,
      alternatives: [],
    };
  }

  private finalizeDecision(unit: CombatUnit, decision: AIDecision): AIDecision {
    this.lastDecisions.set(unit.id, decision);
    return decision;
  }

  toJSON(): Record<string, unknown> {
    return {
      profile: this.profile,
      aggression: this.aggression,
      defensiveness: this.defensiveness,
      threatAssessor: this.threatAssessor.toJSON(),
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    if (data.profile) {
      this.profile = data.profile as AIProfile;
      this.parameters = this.profile.parameters;
    }
    if (typeof data.aggression === 'number') {
      this.aggression = data.aggression;
    }
    if (typeof data.defensiveness === 'number') {
      this.defensiveness = data.defensiveness;
    }
    if (data.threatAssessor) {
      this.threatAssessor.fromJSON(data.threatAssessor as Record<string, unknown>);
    }
    this.lastDecisions.clear();
  }
}
