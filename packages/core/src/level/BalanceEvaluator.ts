import type {
  LevelConfig,
  Faction,
  CombatUnit,
  ID,
  CubeCoords
} from '../types';

export interface PowerScoreBreakdown {
  hp: number;
  attack: number;
  defense: number;
  speed: number;
  skills: number;
  total: number;
}

export interface FactionBalance {
  faction: Faction;
  unitCount: number;
  totalPower: number;
  averagePower: number;
  powerPerUnit: PowerScoreBreakdown[];
  maxHp: number;
  totalAttack: number;
  totalDefense: number;
  totalSpeed: number;
  healerCount: number;
  tankCount: number;
  damageDealerCount: number;
  supportCount: number;
}

export interface BalanceComparison {
  factions: FactionBalance[];
  imbalanceScore: number;
  dominantFaction?: Faction;
  weakestFaction?: Faction;
  powerRatio: Record<Faction, number>;
  recommendations: string[];
}

export interface BalancingSuggestion {
  type: 'add_unit' | 'remove_unit' | 'buff_unit' | 'nerf_unit' | 'adjust_position' | 'adjust_terrain';
  faction?: Faction;
  target?: ID;
  description: string;
  priority: 'high' | 'medium' | 'low';
  expectedImpact: number;
  details?: Record<string, unknown>;
}

export interface TurnLengthEstimate {
  faction: Faction;
  estimatedActions: number;
  estimatedTimeSeconds: number;
  breakdown: {
    moves: number;
    attacks: number;
    skills: number;
    decisions: number;
  };
}

export interface TurnEvaluation {
  perFaction: TurnLengthEstimate[];
  totalEstimatedActions: number;
  totalEstimatedTimeSeconds: number;
  isWithinAcceptableRange: boolean;
  maxRecommendedActions: number;
}

export interface UnitPowerWeights {
  hp: number;
  attack: number;
  defense: number;
  speed: number;
  skills: number;
}

export const DEFAULT_POWER_WEIGHTS: UnitPowerWeights = {
  hp: 0.3,
  attack: 0.25,
  defense: 0.15,
  speed: 0.15,
  skills: 0.15
};

export class BalanceEvaluator {
  private weights: UnitPowerWeights;

  constructor(customWeights?: Partial<UnitPowerWeights>) {
    this.weights = { ...DEFAULT_POWER_WEIGHTS, ...customWeights };
  }

  calculatePowerScore(
    unit: CombatUnit,
    customWeights?: Partial<UnitPowerWeights>
  ): { score: number; breakdown: PowerScoreBreakdown } {
    const w = { ...this.weights, ...customWeights };
    const maxHp = unit.stats.maxHp;
    const attack = unit.stats.attack;
    const defense = unit.stats.defense;
    const speed = unit.stats.speed;
    const skillCount = unit.skills.length + unit.passiveSkills.length;

    const normalizedHp = this.normalize(maxHp, 0, 300);
    const normalizedAttack = this.normalize(attack, 0, 100);
    const normalizedDefense = this.normalize(defense, 0, 80);
    const normalizedSpeed = this.normalize(speed, 0, 50);
    const normalizedSkills = this.normalize(skillCount, 0, 10);

    const breakdown: PowerScoreBreakdown = {
      hp: normalizedHp * w.hp * 100,
      attack: normalizedAttack * w.attack * 100,
      defense: normalizedDefense * w.defense * 100,
      speed: normalizedSpeed * w.speed * 100,
      skills: normalizedSkills * w.skills * 100,
      total: 0
    };

    breakdown.total = breakdown.hp + breakdown.attack + breakdown.defense + breakdown.speed + breakdown.skills;

    return { score: breakdown.total, breakdown };
  }

  compareFactions(
    config: LevelConfig,
    unitTemplates?: Map<ID, Partial<CombatUnit>>
  ): BalanceComparison {
    const factions: FactionBalance[] = [];
    const factionUnits = this.resolveUnits(config, unitTemplates);

    for (const [factionName, units] of factionUnits.entries()) {
      const powerPerUnit: PowerScoreBreakdown[] = [];
      let totalPower = 0;
      let maxHp = 0;
      let totalAttack = 0;
      let totalDefense = 0;
      let totalSpeed = 0;
      let healerCount = 0;
      let tankCount = 0;
      let damageDealerCount = 0;
      let supportCount = 0;

      for (const unit of units) {
        const { score, breakdown } = this.calculatePowerScore(unit);
        powerPerUnit.push(breakdown);
        totalPower += score;
        maxHp += unit.stats.maxHp;
        totalAttack += unit.stats.attack;
        totalDefense += unit.stats.defense;
        totalSpeed += unit.stats.speed;

        const unitRole = this.classifyUnit(unit);
        switch (unitRole) {
          case 'healer': healerCount++; break;
          case 'tank': tankCount++; break;
          case 'damage': damageDealerCount++; break;
          case 'support': supportCount++; break;
        }
      }

      factions.push({
        faction: factionName,
        unitCount: units.length,
        totalPower,
        averagePower: units.length > 0 ? totalPower / units.length : 0,
        powerPerUnit,
        maxHp,
        totalAttack,
        totalDefense,
        totalSpeed,
        healerCount,
        tankCount,
        damageDealerCount,
        supportCount
      });
    }

    const { imbalanceScore, dominantFaction, weakestFaction, powerRatio, recommendations } = 
      this.calculateImbalance(factions);

    return {
      factions,
      imbalanceScore,
      dominantFaction,
      weakestFaction,
      powerRatio,
      recommendations
    };
  }

  suggestBalancing(
    config: LevelConfig,
    unitTemplates?: Map<ID, Partial<CombatUnit>>
  ): BalancingSuggestion[] {
    const suggestions: BalancingSuggestion[] = [];
    const comparison = this.compareFactions(config, unitTemplates);

    if (comparison.imbalanceScore >= 0.3) {
      if (comparison.dominantFaction && comparison.weakestFaction) {
        const dominant = comparison.factions.find(f => f.faction === comparison.dominantFaction);
        const weakest = comparison.factions.find(f => f.faction === comparison.weakestFaction);

        if (dominant && weakest) {
          if (dominant.unitCount > weakest.unitCount) {
            suggestions.push({
              type: 'remove_unit',
              faction: dominant.faction,
              description: `建议减少 ${dominant.faction} 阵营的单位数量`,
              priority: comparison.imbalanceScore >= 0.5 ? 'high' : 'medium',
              expectedImpact: (dominant.unitCount - weakest.unitCount) * 10,
              details: {
                currentCount: dominant.unitCount,
                suggestedCount: weakest.unitCount,
                targetFaction: weakest.faction
              }
            });
          } else if (weakest.unitCount < dominant.unitCount) {
            suggestions.push({
              type: 'add_unit',
              faction: weakest.faction,
              description: `建议增加 ${weakest.faction} 阵营的单位数量`,
              priority: comparison.imbalanceScore >= 0.5 ? 'high' : 'medium',
              expectedImpact: (dominant.unitCount - weakest.unitCount) * 10,
              details: {
                currentCount: weakest.unitCount,
                suggestedCount: dominant.unitCount,
                targetFaction: dominant.faction
              }
            });
          }

          if (dominant.averagePower > weakest.averagePower * 1.3) {
            suggestions.push({
              type: 'nerf_unit',
              faction: dominant.faction,
              description: `建议削弱 ${dominant.faction} 阵营单位的平均战力`,
              priority: 'high',
              expectedImpact: Math.round((dominant.averagePower - weakest.averagePower) * 0.5),
              details: {
                currentAverage: dominant.averagePower,
                targetAverage: weakest.averagePower * 1.2,
                suggestedNerfPercent: Math.round((1 - (weakest.averagePower * 1.2 / dominant.averagePower)) * 100)
              }
            });
          }

          if (weakest.averagePower < dominant.averagePower * 0.7) {
            suggestions.push({
              type: 'buff_unit',
              faction: weakest.faction,
              description: `建议增强 ${weakest.faction} 阵营单位的平均战力`,
              priority: 'high',
              expectedImpact: Math.round((dominant.averagePower - weakest.averagePower) * 0.5),
              details: {
                currentAverage: weakest.averagePower,
                targetAverage: dominant.averagePower * 0.85,
                suggestedBuffPercent: Math.round(((dominant.averagePower * 0.85 / weakest.averagePower) - 1) * 100)
              }
            });
          }

          if (weakest.healerCount === 0 && dominant.healerCount > 0) {
            suggestions.push({
              type: 'add_unit',
              faction: weakest.faction,
              description: `建议为 ${weakest.faction} 阵营添加治疗单位`,
              priority: 'medium',
              expectedImpact: 15,
              details: { role: 'healer' }
            });
          }

          if (weakest.tankCount === 0 && dominant.tankCount > 0) {
            suggestions.push({
              type: 'add_unit',
              faction: weakest.faction,
              description: `建议为 ${weakest.faction} 阵营添加坦克单位`,
              priority: 'medium',
              expectedImpact: 12,
              details: { role: 'tank' }
            });
          }
        }
      }
    }

    const positionSuggestions = this.analyzePositionBalance(config);
    suggestions.push(...positionSuggestions);

    suggestions.sort((a, b) => {
      const priorityOrder = { high: 0, medium: 1, low: 2 };
      if (priorityOrder[a.priority] !== priorityOrder[b.priority]) {
        return priorityOrder[a.priority] - priorityOrder[b.priority];
      }
      return b.expectedImpact - a.expectedImpact;
    });

    return suggestions;
  }

  evaluateTurnLength(
    config: LevelConfig,
    unitTemplates?: Map<ID, Partial<CombatUnit>>
  ): TurnEvaluation {
    const perFaction: TurnLengthEstimate[] = [];
    const factionUnits = this.resolveUnits(config, unitTemplates);
    const maxRecommendedActions = 30;

    let totalEstimatedActions = 0;
    let totalEstimatedTimeSeconds = 0;

    for (const [factionName, units] of factionUnits.entries()) {
      let moves = 0;
      let attacks = 0;
      let skills = 0;
      let decisions = 0;

      for (const unit of units) {
        const moveRange = unit.stats.moveRange;
        const attackRange = unit.stats.attackRange;

        moves += Math.min(moveRange, 8);
        attacks += Math.max(1, attackRange);
        skills += unit.skills.filter(s => s.type === 'active').length * 2;

        const decisionComplexity = 
          (unit.skills.length > 3 ? 1 : 0) +
          (moveRange > 5 ? 1 : 0) +
          (unit.aiProfile ? 0.5 : 1);
        decisions += Math.ceil(decisionComplexity);
      }

      const estimatedActions = moves + attacks + skills + decisions;
      const estimatedTimeSeconds = 
        moves * 2 +
        attacks * 3 +
        skills * 5 +
        decisions * 4;

      perFaction.push({
        faction: factionName,
        estimatedActions,
        estimatedTimeSeconds,
        breakdown: { moves, attacks, skills, decisions }
      });

      totalEstimatedActions += estimatedActions;
      totalEstimatedTimeSeconds += estimatedTimeSeconds;
    }

    return {
      perFaction,
      totalEstimatedActions,
      totalEstimatedTimeSeconds,
      isWithinAcceptableRange: totalEstimatedActions <= maxRecommendedActions,
      maxRecommendedActions
    };
  }

  private resolveUnits(
    config: LevelConfig,
    unitTemplates?: Map<ID, Partial<CombatUnit>>
  ): Map<Faction, CombatUnit[]> {
    const result = new Map<Faction, CombatUnit[]>();

    for (const [faction, factionConfig] of Object.entries(config.factions)) {
      const units: CombatUnit[] = [];
      for (const unitId of factionConfig.units) {
        const template = unitTemplates?.get(unitId);
        units.push(this.createStubUnit(unitId, faction as Faction, template));
      }
      result.set(faction as Faction, units);
    }

    return result;
  }

  private createStubUnit(
    templateId: ID,
    faction: Faction,
    template?: Partial<CombatUnit>
  ): CombatUnit {
    const baseStats = {
      maxHp: 100,
      hp: 100,
      maxMp: 50,
      mp: 50,
      attack: 20,
      defense: 10,
      magicAttack: 15,
      magicDefense: 8,
      speed: 10,
      accuracy: 85,
      evasion: 10,
      critRate: 10,
      critDamage: 150,
      armorPenetration: 0,
      moveRange: 4,
      attackRange: 1,
      visionRange: 6,
      height: 1
    };

    return {
      id: templateId,
      name: template?.name ?? `Unit_${templateId}`,
      faction,
      templateId,
      coords: { q: 0, r: 0, s: 0 },
      direction: 0,
      stats: { ...baseStats, ...template?.stats },
      attributes: template?.attributes ?? {
        hp: { current: baseStats.maxHp, max: baseStats.maxHp, min: 0 },
        mp: { current: baseStats.maxMp, max: baseStats.maxMp, min: 0 },
        attack: { base: baseStats.attack, modifiers: [], current: baseStats.attack },
        defense: { base: baseStats.defense, modifiers: [], current: baseStats.defense },
        magicAttack: { base: baseStats.magicAttack, modifiers: [], current: baseStats.magicAttack },
        magicDefense: { base: baseStats.magicDefense, modifiers: [], current: baseStats.magicDefense },
        speed: { base: baseStats.speed, modifiers: [], current: baseStats.speed },
        accuracy: { base: baseStats.accuracy, modifiers: [], current: baseStats.accuracy },
        evasion: { base: baseStats.evasion, modifiers: [], current: baseStats.evasion },
        critRate: { base: baseStats.critRate, modifiers: [], current: baseStats.critRate },
        critDamage: { base: baseStats.critDamage, modifiers: [], current: baseStats.critDamage },
        armorPenetration: { base: baseStats.armorPenetration, modifiers: [], current: baseStats.armorPenetration },
        moveRange: { base: baseStats.moveRange, modifiers: [], current: baseStats.moveRange },
        attackRange: { base: baseStats.attackRange, modifiers: [], current: baseStats.attackRange },
        visionRange: { base: baseStats.visionRange, modifiers: [], current: baseStats.visionRange }
      },
      skills: template?.skills ?? [],
      passiveSkills: template?.passiveSkills ?? [],
      statusEffects: [],
      resistances: template?.resistances ?? [],
      affinities: template?.affinities ?? [],
      equipment: [],
      isAlive: true,
      hasActed: false,
      hasMoved: false,
      isDelaying: false,
      tags: template?.tags ?? [],
      aiProfile: template?.aiProfile
    };
  }

  private calculateImbalance(factions: FactionBalance[]): {
    imbalanceScore: number;
    dominantFaction?: Faction;
    weakestFaction?: Faction;
    powerRatio: Record<Faction, number>;
    recommendations: string[];
  } {
    const recommendations: string[] = [];
    const powerRatio: Record<Faction, number> = {};

    if (factions.length < 2) {
      return {
        imbalanceScore: 0,
        powerRatio,
        recommendations: ['至少需要两个阵营才能评估平衡性']
      };
    }

    const maxPower = Math.max(...factions.map(f => f.totalPower));
    const minPower = Math.min(...factions.map(f => f.totalPower));
    const totalPower = factions.reduce((sum, f) => sum + f.totalPower, 0);

    for (const faction of factions) {
      powerRatio[faction.faction] = maxPower > 0 ? faction.totalPower / maxPower : 0;
    }

    let imbalanceScore = 0;
    if (totalPower > 0 && factions.length >= 2) {
      const meanPower = totalPower / factions.length;
      const variance = factions.reduce((sum, f) => 
        sum + Math.pow(f.totalPower - meanPower, 2), 0) / factions.length;
      const stdDev = Math.sqrt(variance);
      imbalanceScore = meanPower > 0 ? Math.min(1, stdDev / meanPower) : 0;
    }

    let dominantFaction: Faction | undefined;
    let weakestFaction: Faction | undefined;

    if (maxPower > 0) {
      const dominant = factions.find(f => f.totalPower === maxPower);
      const weakest = factions.find(f => f.totalPower === minPower);
      if (dominant && weakest && dominant.faction !== weakest.faction) {
        dominantFaction = dominant.faction;
        weakestFaction = weakest.faction;

        const ratio = minPower > 0 ? maxPower / minPower : Infinity;
        if (ratio > 2) {
          recommendations.push(`${dominantFaction} 阵营战力远超 ${weakestFaction} 阵营 (${ratio.toFixed(1)}:1)，严重失衡`);
        } else if (ratio > 1.5) {
          recommendations.push(`${dominantFaction} 阵营战力略高于 ${weakestFaction} 阵营 (${ratio.toFixed(1)}:1)，建议微调`);
        }
      }
    }

    for (const faction of factions) {
      if (faction.unitCount === 0) {
        recommendations.push(`${faction.faction} 阵营没有任何单位`);
      }
      if (faction.tankCount === 0 && faction.unitCount >= 3) {
        recommendations.push(`${faction.faction} 阵营缺少坦克单位，前排防御可能不足`);
      }
      if (faction.healerCount === 0 && faction.unitCount >= 4) {
        recommendations.push(`${faction.faction} 阵营缺少治疗单位，持续作战能力较弱`);
      }
      if (faction.damageDealerCount === 0 && faction.unitCount >= 2) {
        recommendations.push(`${faction.faction} 阵营缺少输出单位，可能难以造成有效伤害`);
      }
    }

    return {
      imbalanceScore,
      dominantFaction,
      weakestFaction,
      powerRatio,
      recommendations
    };
  }

  private classifyUnit(unit: CombatUnit): 'tank' | 'damage' | 'healer' | 'support' | 'balanced' {
    const { score, breakdown } = this.calculatePowerScore(unit);

    const hasHealSkill = unit.skills.some(s => 
      s.effects.some(e => e.type === 'heal')
    );
    const hasBuffSkill = unit.skills.some(s => 
      s.effects.some(e => e.type === 'buff')
    );

    if (hasHealSkill) {
      return 'healer';
    }

    if (breakdown.defense >= 25 && breakdown.hp >= 30) {
      return 'tank';
    }

    if (breakdown.attack >= 30 || unit.stats.attack >= 35) {
      return 'damage';
    }

    if (hasBuffSkill || unit.skills.filter(s => s.type === 'active').length >= 3) {
      return 'support';
    }

    return 'balanced';
  }

  private analyzePositionBalance(config: LevelConfig): BalancingSuggestion[] {
    const suggestions: BalancingSuggestion[] = [];
    const playerPositions = config.factions['player']?.startingPositions ?? [];
    const enemyPositions = config.factions['enemy']?.startingPositions ?? [];

    if (playerPositions.length === 0 || enemyPositions.length === 0) {
      return suggestions;
    }

    let minDistance = Infinity;
    for (const pp of playerPositions) {
      for (const ep of enemyPositions) {
        const dist = Math.abs(pp.q - ep.q) + Math.abs(pp.r - ep.r) + Math.abs(pp.s - ep.s);
        const realDist = dist / 2;
        minDistance = Math.min(minDistance, realDist);
      }
    }

    if (minDistance < 3) {
      suggestions.push({
        type: 'adjust_position',
        description: '玩家与敌军起始位置过近，建议拉开距离至少3格',
        priority: 'medium',
        expectedImpact: 10,
        details: { currentMinDistance: minDistance, recommendedMinDistance: 3 }
      });
    }

    if (minDistance > 15) {
      suggestions.push({
        type: 'adjust_position',
        description: '玩家与敌军起始位置过远，建议缩短到15格以内',
        priority: 'low',
        expectedImpact: 5,
        details: { currentMinDistance: minDistance, recommendedMaxDistance: 15 }
      });
    }

    if (playerPositions.length > 1) {
      const playerSpread = this.calculatePositionSpread(playerPositions);
      if (playerSpread > 10) {
        suggestions.push({
          type: 'adjust_position',
          faction: 'player',
          description: '玩家单位过于分散，建议集中部署',
          priority: 'low',
          expectedImpact: 5,
          details: { spread: playerSpread, recommendedMaxSpread: 8 }
        });
      }
    }

    return suggestions;
  }

  private calculatePositionSpread(positions: CubeCoords[]): number {
    if (positions.length < 2) return 0;

    let maxDist = 0;
    for (let i = 0; i < positions.length; i++) {
      for (let j = i + 1; j < positions.length; j++) {
        const dist = (Math.abs(positions[i].q - positions[j].q) + 
                     Math.abs(positions[i].r - positions[j].r) + 
                     Math.abs(positions[i].s - positions[j].s)) / 2;
        maxDist = Math.max(maxDist, dist);
      }
    }
    return maxDist;
  }

  private normalize(value: number, min: number, max: number): number {
    if (max - min === 0) return 0.5;
    return Math.max(0, Math.min(1, (value - min) / (max - min)));
  }

  toJSON(): Record<string, unknown> {
    return {
      weights: this.weights
    };
  }

  static fromJSON(data: Record<string, unknown>): BalanceEvaluator {
    return new BalanceEvaluator(data.weights as Partial<UnitPowerWeights>);
  }
}
