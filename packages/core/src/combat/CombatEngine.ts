import type {
  CombatUnit,
  Skill,
  DamageInstance,
  DelayedSkill,
  DamageCalculationConfig,
  ElementChart,
  CombatLogEntry,
} from '../types';
import type { CubeCoords } from '../types/grid';
import type { ID, Faction, Direction } from '../types/common';
import { Serializable } from '../utils';
import { DamageCalculator } from './DamageCalculator';
import { SkillSystem } from './SkillSystem';
import { StatusEffectSystem } from './StatusEffectSystem';
import type { SkillExecutionResult } from './SkillSystem';
import type { StatusEffectTickResult } from './StatusEffectSystem';

export type CombatEngineEventMap = {
  onBeforeAttack?: (attacker: CombatUnit, defender: CombatUnit) => boolean | void;
  onAfterAttack?: (attacker: CombatUnit, defender: CombatUnit, damage: DamageInstance) => void;
  onBeforeCastSkill?: (caster: CombatUnit, skill: Skill, target?: CombatUnit, coords?: CubeCoords) => boolean | void;
  onAfterCastSkill?: (caster: CombatUnit, skill: Skill, result: SkillExecutionResult) => void;
  onBeforeDelayedSkillStart?: (delayed: DelayedSkill) => void;
  onAfterDelayedSkillComplete?: (delayed: DelayedSkill, result: SkillExecutionResult) => void;
  onDelayedSkillInterrupted?: (delayed: DelayedSkill, interrupter?: CombatUnit) => void;
  onBeforeKillUnit?: (unit: CombatUnit, killer?: CombatUnit, damage?: DamageInstance) => boolean | void;
  onAfterKillUnit?: (unit: CombatUnit, killer?: CombatUnit, damage?: DamageInstance) => void;
  onVictory?: (winner: Faction, survivingUnits: CombatUnit[]) => void;
  onDefeat?: (loser: Faction, deadUnits: CombatUnit[]) => void;
  onCombatLog?: (entry: CombatLogEntry) => void;
  onDirectionChange?: (unit: CombatUnit, oldDirection: Direction, newDirection: Direction) => void;
  onTurnStart?: (unit: CombatUnit) => void;
  onTurnEnd?: (unit: CombatUnit) => void;
};

export interface CombatEngineState {
  units: Map<ID, CombatUnit>;
  delayedSkills: DelayedSkill[];
  currentTurn: number;
  combatLog: CombatLogEntry[];
  isCombatActive: boolean;
  winner?: Faction;
}

export class CombatEngine implements Serializable {
  private units: Map<ID, CombatUnit>;
  private delayedSkills: DelayedSkill[];
  private currentTurn: number;
  private combatLog: CombatLogEntry[];
  private isCombatActive: boolean;
  private winner?: Faction;
  private damageCalculator: DamageCalculator;
  private skillSystem: SkillSystem;
  private statusEffectSystem: StatusEffectSystem;
  private damageConfig: DamageCalculationConfig;
  private elementChart: ElementChart;
  private events: CombatEngineEventMap;

  constructor(
    damageConfig: DamageCalculationConfig,
    elementChart: ElementChart,
    events: CombatEngineEventMap = {}
  ) {
    this.units = new Map();
    this.delayedSkills = [];
    this.currentTurn = 0;
    this.combatLog = [];
    this.isCombatActive = false;
    this.damageConfig = damageConfig;
    this.elementChart = elementChart;
    this.events = events;

    this.statusEffectSystem = new StatusEffectSystem();
    this.damageCalculator = new DamageCalculator(damageConfig, elementChart);
    this.skillSystem = new SkillSystem(this.damageCalculator, this.statusEffectSystem);
    this.skillSystem.setUnits(this.units);
  }

  addUnit(unit: CombatUnit): void {
    this.units.set(unit.id, unit);
    this.skillSystem.setUnits(this.units);
  }

  removeUnit(unitId: ID): void {
    this.units.delete(unitId);
    this.skillSystem.setUnits(this.units);
  }

  getUnit(unitId: ID): CombatUnit | undefined {
    return this.units.get(unitId);
  }

  getAllUnits(): CombatUnit[] {
    return Array.from(this.units.values());
  }

  getAliveUnits(): CombatUnit[] {
    return this.getAllUnits().filter(u => u.isAlive);
  }

  getUnitsByFaction(faction: Faction): CombatUnit[] {
    return this.getAliveUnits().filter(u => u.faction === faction);
  }

  attack(attackerId: ID, defenderId: ID): DamageInstance | null {
    const attacker = this.units.get(attackerId);
    const defender = this.units.get(defenderId);

    if (!attacker || !defender || !attacker.isAlive || !defender.isAlive) {
      return null;
    }

    if (this.events.onBeforeAttack?.(attacker, defender) === false) {
      return null;
    }

    this.updateDirection(attacker, defender.coords);

    const attackBonus = 0;
    const defenseBonus = 0;
    const attackerHeight = attacker.stats.height;
    const defenderHeight = defender.stats.height;

    const { terrainBonus, heightBonus } = this.damageCalculator.applyTerrainAndHeightBonus(
      attacker.coords,
      defender.coords,
      attackBonus,
      defenseBonus,
      attackerHeight,
      defenderHeight
    );

    const directionBonus = this.damageCalculator.calculateDirectionBonus(
      attacker.direction,
      defender.direction,
      attacker.coords,
      defender.coords
    );

    const damage = this.damageCalculator.calculateDamage(
      attacker,
      defender,
      'neutral',
      'physical',
      undefined,
      terrainBonus,
      heightBonus,
      directionBonus
    );

    defender.stats.hp = Math.max(0, defender.stats.hp - damage.finalDamage);

    this.addCombatLog('damage', {
      attackerId: attacker.id,
      defenderId: defender.id,
      damage: damage.finalDamage,
      damageInstance: damage,
    });

    this.skillSystem.processPassiveTriggers(defender, 'onDamageTaken', {
      value: damage.finalDamage,
      target: defender,
      source: attacker,
    });

    this.skillSystem.processPassiveTriggers(attacker, 'onDamageDealt', {
      value: damage.finalDamage,
      target: defender,
    });

    this.events.onAfterAttack?.(attacker, defender, damage);

    if (defender.stats.hp <= 0 && defender.isAlive) {
      this.killUnit(defenderId, attackerId, damage);
    }

    return damage;
  }

  castSkill(
    casterId: ID,
    skillId: ID,
    targetUnitId?: ID,
    targetCoords?: CubeCoords
  ): SkillExecutionResult | null {
    const caster = this.units.get(casterId);
    if (!caster || !caster.isAlive) {
      return null;
    }

    const skill = caster.skills.find(s => s.id === skillId);
    if (!skill) {
      return null;
    }

    const targetUnit = targetUnitId ? this.units.get(targetUnitId) : undefined;

    if (this.events.onBeforeCastSkill?.(caster, skill, targetUnit, targetCoords) === false) {
      return {
        success: false,
        targets: [],
        effects: [],
        damageInstances: [],
        healInstances: [],
        appliedStatusEffects: [],
        message: 'Skill cast blocked by event',
      };
    }

    if (skill.currentCooldown > 0) {
      return {
        success: false,
        targets: [],
        effects: [],
        damageInstances: [],
        healInstances: [],
        appliedStatusEffects: [],
        message: 'Skill on cooldown',
      };
    }

    const result = this.skillSystem.executeSkill(caster, skill, targetUnit, targetCoords);

    this.addCombatLog('skill', {
      casterId: caster.id,
      skillId: skill.id,
      targetUnitId,
      targetCoords,
      success: result.success,
    });

    if (result.success && caster.castingSkill) {
      this.addDelayedSkill(caster.castingSkill);
    }

    if (result.success) {
      this.skillSystem.processPassiveTriggers(caster, 'onSkillCast', {
        skill,
        target: targetUnit,
      });

      for (const damageInstance of result.damageInstances) {
        const target = this.units.get(damageInstance.targetId);
        if (target) {
          this.skillSystem.processPassiveTriggers(target, 'onDamageTaken', {
            value: damageInstance.finalDamage,
            target,
            source: caster,
          });

          if (target.stats.hp <= 0 && target.isAlive) {
            this.killUnit(target.id, casterId, damageInstance);
          }
        }
      }
    }

    this.events.onAfterCastSkill?.(caster, skill, result);
    return result;
  }

  processDelayedSkills(): Array<{ delayed: DelayedSkill; result: SkillExecutionResult | null}> {
    const completedResults: Array<{ delayed: DelayedSkill; result: SkillExecutionResult | null}> = [];
    const stillDelayed: DelayedSkill[] = [];

    for (const delayed of this.delayedSkills) {
      delayed.remainingTurns -= 1;
      delayed.castProgress = (delayed.totalTurns - delayed.remainingTurns) / delayed.totalTurns;

      const caster = this.units.get(delayed.casterId);

      if (delayed.remainingTurns <= 0) {
        this.events.onBeforeDelayedSkillStart?.(delayed);

        if (caster && caster.isAlive) {
          const skill = caster.skills.find(s => s.id === delayed.skillId);
          const targetUnit = delayed.targetUnitId ? this.units.get(delayed.targetUnitId) : undefined;

          if (skill) {
            skill.isDelayed = false;
            const result = this.skillSystem.executeSkill(caster, skill, targetUnit, delayed.targetCoords);
            skill.isDelayed = true;

            this.addCombatLog('skill', {
              casterId: caster.id,
              skillId: skill.id,
              delayed: true,
              result,
            });

            if (result.success) {
              for (const damageInstance of result.damageInstances) {
                const target = this.units.get(damageInstance.targetId);
                if (target && target.stats.hp <= 0 && target.isAlive) {
                  this.killUnit(target.id, caster.id, damageInstance);
                }
              }
            }

            completedResults.push({ delayed, result });
            this.events.onAfterDelayedSkillComplete?.(delayed, result);
          } else {
            completedResults.push({ delayed, result: null });
          }

          caster.castingSkill = undefined;
          caster.isDelaying = false;
          caster.delayReason = undefined;
        } else {
          completedResults.push({ delayed, result: null });
        }
      } else {
        stillDelayed.push(delayed);

        if (caster && caster.castingSkill) {
          caster.castingSkill.remainingTurns = delayed.remainingTurns;
          caster.castingSkill.castProgress = delayed.castProgress;
        }
      }
    }

    this.delayedSkills = stillDelayed;
    return completedResults;
  }

  interruptDelayedSkill(delayedSkill: DelayedSkill, interrupterId?: ID): boolean {
    const index = this.delayedSkills.findIndex(d =>
      d.skillId === delayedSkill.skillId && d.casterId === delayedSkill.casterId
    );

    if (index < 0) return false;

    if (!delayedSkill.isInterruptible) return false;

    this.delayedSkills.splice(index, 1);

    const caster = this.units.get(delayedSkill.casterId);
    const interrupter = interrupterId ? this.units.get(interrupterId) : undefined;

    if (caster) {
      caster.castingSkill = undefined;
      caster.isDelaying = false;
      caster.delayReason = undefined;
    }

    this.events.onDelayedSkillInterrupted?.(delayedSkill, interrupter);
    return true;
  }

  killUnit(unitId: ID, killerId?: ID, damage?: DamageInstance): CombatUnit | null {
    const unit = this.units.get(unitId);
    if (!unit) return null;

    const killer = killerId ? this.units.get(killerId) : undefined;

    if (this.events.onBeforeKillUnit?.(unit, killer, damage) === false) {
      return null;
    }

    unit.isAlive = false;
    unit.stats.hp = 0;

    for (const otherUnit of this.getAliveUnits()) {
      this.skillSystem.processPassiveTriggers(otherUnit, 'onUnitDeath', {
        deadUnit: unit,
        killer,
      });
    }

    if (killer) {
      this.skillSystem.processPassiveTriggers(killer, 'onAllyDeath', {
        deadUnit: unit,
        target: unit,
      });
    }

    this.addCombatLog('death', {
      unitId: unit.id,
      killerId: killer?.id,
      damage,
      effectsOnDeath: unit.tags,
    });

    this.events.onAfterKillUnit?.(unit, killer, damage);

    this.checkVictory();

    return unit;
  }

  checkVictory(): Faction | null {
    const aliveUnits = this.getAliveUnits();
    const factions = new Set(aliveUnits.map(u => u.faction));
    const uniqueFactions = Array.from(factions);

    if (uniqueFactions.length <= 1) {
      if (uniqueFactions.length === 1) {
      const winner = uniqueFactions[0];
      this.winner = winner;
      this.isCombatActive = false;

      const survivingUnits = this.getUnitsByFaction(winner);
      this.events.onVictory?.(winner, survivingUnits);

      const allFactions = new Set(this.getAllUnits().map(u => u.faction));
      for (const faction of allFactions) {
        if (faction !== winner) {
          const deadUnits = this.getAllUnits().filter(u => u.faction === faction && !u.isAlive);
          this.events.onDefeat?.(faction, deadUnits);
        }
      }

      return winner;
      }
    }

    return null;
  }

  updateDirection(unit: CombatUnit, targetCoords: CubeCoords): Direction {
    const oldDirection = unit.direction;
    const newDirection = this.calculateDirectionTo(unit.coords, targetCoords);

    if (oldDirection !== newDirection) {
      unit.direction = newDirection;
      this.events.onDirectionChange?.(unit, oldDirection, newDirection);
    }

    return newDirection;
  }

  calculateDirectionTo(from: CubeCoords, to: CubeCoords): Direction {
    const dq = to.q - from.q;
    const dr = to.r - from.r;

    const directions = [
      { q: 1, r: -1, dir: 0 as Direction },
      { q: 1, r: 0, dir: 1 as Direction },
      { q: 0, r: 1, dir: 2 as Direction },
      { q: -1, r: 1, dir: 3 as Direction },
      { q: -1, r: 0, dir: 4 as Direction },
      { q: 0, r: -1, dir: 5 as Direction },
    ];

    let bestDir: Direction = 0;
    let bestDot = -Infinity;

    for (const { q, r, dir } of directions) {
      const dot = dq * q + dr * r + (-dq - dr) * (-q - r);
      if (dot > bestDot) {
        bestDot = dot;
        bestDir = dir;
      }
    }

    return bestDir;
  }

  startCombat(): void {
    this.isCombatActive = true;
    this.currentTurn = 1;
    this.addCombatLog('turn', { turnNumber: this.currentTurn, phase: 'start' });
  }

  endCombat(): void {
    this.isCombatActive = false;
  }

  incrementTurn(): void {
    this.currentTurn += 1;
    this.addCombatLog('turn', { turnNumber: this.currentTurn, phase: 'start' });
  }

  getCurrentTurn(): number {
    return this.currentTurn;
  }

  tickUnitStatus(unitId: ID): StatusEffectTickResult[] {
    const unit = this.units.get(unitId);
    if (!unit) return [];
    return this.statusEffectSystem.tickEffects(unit);
  }

  applyAurasForUnit(unitId: ID): void {
    const unit = this.units.get(unitId);
    if (unit) {
      this.skillSystem.applyAuras(unit);
    }
  }

  refreshSkillCooldowns(): void {
    for (const unit of this.getAliveUnits()) {
      for (const skill of unit.skills) {
        if (skill.currentCooldown > 0) {
          skill.currentCooldown = Math.max(0, skill.currentCooldown - 1);
        }
      }
    }
  }

  addDelayedSkill(delayed: DelayedSkill): void {
    this.delayedSkills.push(delayed);
  }

  getDelayedSkills(): DelayedSkill[] {
    return [...this.delayedSkills];
  }

  getCombatLog(): CombatLogEntry[] {
    return [...this.combatLog];
  }

  clearCombatLog(): void {
    this.combatLog = [];
  }

  addCombatLog(type: CombatLogEntry['type'], data: Record<string, unknown>): void {
    const entry: CombatLogEntry = {
      type,
      data,
      timestamp: Date.now(),
      turnNumber: this.currentTurn,
    };
    this.combatLog.push(entry);
    this.events.onCombatLog?.(entry);
  }

  getState(): CombatEngineState {
    return {
      units: new Map(this.units),
      delayedSkills: [...this.delayedSkills],
      currentTurn: this.currentTurn,
      combatLog: [...this.combatLog],
      isCombatActive: this.isCombatActive,
      winner: this.winner,
    };
  }

  getDamageCalculator(): DamageCalculator {
    return this.damageCalculator;
  }

  getSkillSystem(): SkillSystem {
    return this.skillSystem;
  }

  getStatusEffectSystem(): StatusEffectSystem {
    return this.statusEffectSystem;
  }

  getDamageConfig(): DamageCalculationConfig {
    return { ...this.damageConfig };
  }

  setDamageConfig(config: Partial<DamageCalculationConfig>): void {
    this.damageConfig = { ...this.damageConfig, ...config };
    this.damageCalculator.setConfig(config);
  }

  getElementChart(): ElementChart {
    return { ...this.elementChart };
  }

  setElementChart(chart: ElementChart): void {
    this.elementChart = { ...chart };
    this.damageCalculator.setElementChart(chart);
  }

  setGridTiles(tiles: Map<string, CubeCoords & { terrain: string; height: number }>): void {
    this.skillSystem.setGridTiles(tiles);
  }

  setEvents(events: Partial<CombatEngineEventMap>): void {
    this.events = { ...this.events, ...events };
  }

  isActive(): boolean {
    return this.isCombatActive;
  }

  getWinner(): Faction | undefined {
    return this.winner;
  }

  toJSON(): Record<string, unknown> {
    const unitsData: Array<{ key: string; value: CombatUnit }> = [];
    for (const [key, value] of this.units.entries()) {
      unitsData.push({ key, value });
    }

    return {
      units: unitsData,
      delayedSkills: this.delayedSkills,
      currentTurn: this.currentTurn,
      combatLog: this.combatLog,
      isCombatActive: this.isCombatActive,
      winner: this.winner,
      damageConfig: this.damageConfig,
      elementChart: this.elementChart,
      damageCalculator: this.damageCalculator.toJSON(),
      skillSystem: this.skillSystem.toJSON(),
      statusEffectSystem: this.statusEffectSystem.toJSON(),
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    if (data.units) {
      const unitsData = data.units as Array<{ key: string; value: CombatUnit }>;
      this.units = new Map();
      for (const item of unitsData) {
        this.units.set(item.key, item.value);
      }
      this.skillSystem.setUnits(this.units);
    }
    if (data.delayedSkills) {
      this.delayedSkills = data.delayedSkills as DelayedSkill[];
    }
    if (data.currentTurn !== undefined) {
      this.currentTurn = data.currentTurn as number;
    }
    if (data.combatLog) {
      this.combatLog = data.combatLog as CombatLogEntry[];
    }
    if (data.isCombatActive !== undefined) {
      this.isCombatActive = data.isCombatActive as boolean;
    }
    if (data.winner !== undefined) {
      this.winner = data.winner as Faction;
    }
    if (data.damageConfig) {
      this.damageConfig = data.damageConfig as DamageCalculationConfig;
    }
    if (data.elementChart) {
      this.elementChart = data.elementChart as ElementChart;
    }
    if (data.damageCalculator) {
      this.damageCalculator.fromJSON(data.damageCalculator as Record<string, unknown>);
    }
    if (data.skillSystem) {
      this.skillSystem.fromJSON(data.skillSystem as Record<string, unknown>);
    }
    if (data.statusEffectSystem) {
      this.statusEffectSystem.fromJSON(data.statusEffectSystem as Record<string, unknown>);
    }
  }
}
