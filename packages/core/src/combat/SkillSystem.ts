import type {
  CombatUnit,
  Skill,
  StatusEffect,
  DamageInstance,
  HealInstance,
  DelayedSkill,
  PassiveSkill,
} from '../types';
import type { CubeCoords } from '../types/grid';
import type { ID } from '../types/common';
import {
  chance,
  Serializable,
} from '../utils';
import { DamageCalculator } from './DamageCalculator';
import { StatusEffectSystem } from './StatusEffectSystem';

export type SkillSystemEventMap = {
  onBeforeSkillExecute?: (caster: CombatUnit, skill: Skill, target?: CombatUnit, coords?: CubeCoords) => boolean | void;
  onAfterSkillExecute?: (caster: CombatUnit, skill: Skill, results: SkillExecutionResult) => void;
  onSkillEffectApplied?: (effect: SkillEffectApplication) => void;
  onSkillTargetInvalid?: (caster: CombatUnit, skill: Skill, target?: CombatUnit, reason?: string) => void;
  onAuraApplied?: (source: CombatUnit, aura: PassiveSkill, target: CombatUnit) => void;
  onAuraRemoved?: (source: CombatUnit, aura: PassiveSkill, target: CombatUnit) => void;
  onPassiveTriggered?: (unit: CombatUnit, passive: PassiveSkill, triggerType: string) => void;
  onSkillStartCasting?: (caster: CombatUnit, skill: Skill, delayed: DelayedSkill) => void;
  onMpCost?: (caster: CombatUnit, amount: number) => void;
  onHpCost?: (caster: CombatUnit, amount: number) => void;
};

export interface SkillEffectApplication {
  type: string;
  sourceId: ID;
  targetId: ID;
  skillId: ID;
  effectIndex: number;
  value: number;
  damageInstance?: DamageInstance;
  healInstance?: HealInstance;
  statusEffect?: StatusEffect;
  timestamp: number;
}

export interface SkillExecutionResult {
  success: boolean;
  targets: ID[];
  effects: SkillEffectApplication[];
  damageInstances: DamageInstance[];
  healInstances: HealInstance[];
  appliedStatusEffects: StatusEffect[];
  message?: string;
}

export class SkillSystem implements Serializable {
  private damageCalculator: DamageCalculator;
  private statusEffectSystem: StatusEffectSystem;
  private statusEffectTemplates: Map<ID, StatusEffect>;
  private events: SkillSystemEventMap;
  private units: Map<ID, CombatUnit>;
  private gridTiles: Map<string, CubeCoords & { terrain: string; height: number }>;

  constructor(
    damageCalculator: DamageCalculator,
    statusEffectSystem: StatusEffectSystem,
    statusEffectTemplates: Map<ID, StatusEffect> = new Map(),
    events: SkillSystemEventMap = {}
  ) {
    this.damageCalculator = damageCalculator;
    this.statusEffectSystem = statusEffectSystem;
    this.statusEffectTemplates = statusEffectTemplates;
    this.events = events;
    this.units = new Map();
    this.gridTiles = new Map();
  }

  setUnits(units: Map<ID, CombatUnit>): void {
    this.units = units;
  }

  setGridTiles(tiles: Map<string, CubeCoords & { terrain: string; height: number }>): void {
    this.gridTiles = tiles;
  }

  executeSkill(
    caster: CombatUnit,
    skill: Skill,
    targetUnit?: CombatUnit,
    targetCoords?: CubeCoords
  ): SkillExecutionResult {
    const result: SkillExecutionResult = {
      success: false,
      targets: [],
      effects: [],
      damageInstances: [],
      healInstances: [],
      appliedStatusEffects: [],
    };

    if (this.events.onBeforeSkillExecute?.(caster, skill, targetUnit, targetCoords) === false) {
      result.message = 'Skill execution blocked by event';
      return result;
    }

    if (!this.validateTarget(caster, skill, targetUnit, targetCoords)) {
      this.events.onSkillTargetInvalid?.(caster, skill, targetUnit, 'Invalid target');
      result.message = 'Invalid target';
      return result;
    }

    if (!this.canPayCost(caster, skill)) {
      result.message = 'Insufficient resources';
      return result;
    }

    this.payCost(caster, skill);

    const targets = this.getSkillTargets(caster, skill, targetUnit, targetCoords);
    result.targets = targets.map(t => t.id);

    if (skill.isDelayed) {
      const delayed: DelayedSkill = {
        skillId: skill.id,
        casterId: caster.id,
        targetCoords,
        targetUnitId: targetUnit?.id,
        remainingTurns: skill.castTime,
        totalTurns: skill.castTime,
        isInterruptible: true,
        castProgress: 0,
      };
      caster.castingSkill = delayed;
      caster.isDelaying = true;
      caster.delayReason = 'casting';
      this.events.onSkillStartCasting?.(caster, skill, delayed);
      result.success = true;
      return result;
    }

    for (const target of targets) {
      const targetResults = this.applySkillEffect(caster, skill, target, targetCoords);
      result.effects.push(...targetResults.effects);
      result.damageInstances.push(...targetResults.damageInstances);
      result.healInstances.push(...targetResults.healInstances);
      result.appliedStatusEffects.push(...targetResults.appliedStatusEffects);
    }

    if (skill.currentCooldown < skill.cooldown) {
      skill.currentCooldown = skill.cooldown;
    }

    result.success = true;
    this.events.onAfterSkillExecute?.(caster, skill, result);

    return result;
  }

  applySkillEffect(
    caster: CombatUnit,
    skill: Skill,
    target: CombatUnit,
    _targetCoords?: CubeCoords
  ): SkillExecutionResult {
    const result: SkillExecutionResult = {
      success: true,
      targets: [target.id],
      effects: [],
      damageInstances: [],
      healInstances: [],
      appliedStatusEffects: [],
    };

    const timestamp = Date.now();

    for (let i = 0; i < skill.effects.length; i++) {
      const effect = skill.effects[i];
      let application: SkillEffectApplication | null = null;

      switch (effect.type) {
        case 'damage': {
          const attackBonus = this.getTerrainAttackBonus(caster.coords);
          const defenseBonus = this.getTerrainDefenseBonus(target.coords);
          const attackerHeight = this.getTileHeight(caster.coords);
          const defenderHeight = this.getTileHeight(target.coords);

          const { terrainBonus, heightBonus } = this.damageCalculator.applyTerrainAndHeightBonus(
            caster.coords,
            target.coords,
            attackBonus,
            defenseBonus,
            attackerHeight,
            defenderHeight
          );

          const directionBonus = this.damageCalculator.calculateDirectionBonus(
            caster.direction,
            target.direction,
            caster.coords,
            target.coords
          );

          const damageInstance = this.damageCalculator.calculateDamage(
            caster,
            target,
            skill.element ?? 'neutral',
            skill.damageType ?? 'physical',
            effect.value,
            terrainBonus,
            heightBonus,
            directionBonus,
            skill.id,
            effect.armorPenetration
          );

          target.stats.hp = Math.max(0, target.stats.hp - damageInstance.finalDamage);

          result.damageInstances.push(damageInstance);
          application = {
            type: 'damage',
            sourceId: caster.id,
            targetId: target.id,
            skillId: skill.id,
            effectIndex: i,
            value: damageInstance.finalDamage,
            damageInstance,
            timestamp,
          };
          break;
        }

        case 'heal': {
          let healAmount = effect.value;
          const isCrit = chance(caster.stats.critRate * 0.5);
          if (isCrit) {
            healAmount *= 1.5;
          }

          const maxHp = target.stats.maxHp;
          const actualHeal = Math.min(healAmount, maxHp - target.stats.hp);
          const overheal = healAmount - actualHeal;

          target.stats.hp = Math.min(maxHp, target.stats.hp + actualHeal);

          const healInstance: HealInstance = {
            sourceId: caster.id,
            targetId: target.id,
            skillId: skill.id,
            baseHeal: effect.value,
            finalHeal: actualHeal,
            isCrit,
            isOverheal: overheal > 0,
            overhealAmount: overheal,
            position: target.coords,
            timestamp,
          };

          result.healInstances.push(healInstance);
          application = {
            type: 'heal',
            sourceId: caster.id,
            targetId: target.id,
            skillId: skill.id,
            effectIndex: i,
            value: actualHeal,
            healInstance,
            timestamp,
          };
          break;
        }

        case 'buff':
        case 'debuff': {
          if (effect.statusEffect) {
            const template = this.statusEffectTemplates.get(effect.statusEffect);
            if (template) {
              const effectCopy = this.cloneStatusEffect(template);
              if (effect.statusDuration) {
                effectCopy.duration = effect.statusDuration;
                effectCopy.maxDuration = effect.statusDuration;
              }
              effectCopy.source = caster.id;
              effectCopy.isDebuff = effect.type === 'debuff';

              const applied = this.statusEffectSystem.applyEffect(target, effectCopy);
              if (applied) {
                result.appliedStatusEffects.push(applied);
                application = {
                  type: effect.type,
                  sourceId: caster.id,
                  targetId: target.id,
                  skillId: skill.id,
                  effectIndex: i,
                  value: effect.value,
                  statusEffect: applied,
                  timestamp,
                };
              }
            }
          }
          break;
        }

        case 'dot':
        case 'hot': {
          if (effect.statusEffect) {
            const template = this.statusEffectTemplates.get(effect.statusEffect);
            if (template) {
              const effectCopy = this.cloneStatusEffect(template);
              effectCopy.effects = [{
                value: effect.value,
                modifierType: 'add',
                damageType: effect.damageType,
                element: effect.element,
              }];
              effectCopy.source = caster.id;
              effectCopy.isDebuff = effect.type === 'dot';

              const applied = this.statusEffectSystem.applyEffect(target, effectCopy);
              if (applied) {
                result.appliedStatusEffects.push(applied);
                application = {
                  type: effect.type,
                  sourceId: caster.id,
                  targetId: target.id,
                  skillId: skill.id,
                  effectIndex: i,
                  value: effect.value,
                  statusEffect: applied,
                  timestamp,
                };
              }
            }
          }
          break;
        }

        default:
          application = {
            type: effect.type,
            sourceId: caster.id,
            targetId: target.id,
            skillId: skill.id,
            effectIndex: i,
            value: effect.value,
            timestamp,
          };
      }

      if (application) {
        result.effects.push(application);
        this.events.onSkillEffectApplied?.(application);
      }
    }

    return result;
  }

  validateTarget(
    caster: CombatUnit,
    skill: Skill,
    targetUnit?: CombatUnit,
    targetCoords?: CubeCoords
  ): boolean {
    if (!skill.canTargetSelf && targetUnit?.id === caster.id) {
      return false;
    }

    if (!skill.canTargetAlly && targetUnit && this.isAlly(caster, targetUnit) && targetUnit.id !== caster.id) {
      return false;
    }

    if (!skill.canTargetEnemy && targetUnit && !this.isAlly(caster, targetUnit)) {
      return false;
    }

    if (!skill.canTargetTerrain && !targetUnit && targetCoords) {
      return false;
    }

    if (targetUnit && targetCoords) {
      const dist = this.getDistance(caster.coords, targetUnit.coords);
      if (dist < skill.range.min || dist > skill.range.max) {
        return false;
      }
    } else if (targetCoords) {
      const dist = this.getDistance(caster.coords, targetCoords);
      if (dist < skill.range.min || dist > skill.range.max) {
        return false;
      }
    }

    return true;
  }

  getSkillTargets(
    caster: CombatUnit,
    skill: Skill,
    targetUnit?: CombatUnit,
    targetCoords?: CubeCoords
  ): CombatUnit[] {
    const targets: CombatUnit[] = [];

    switch (skill.targetType) {
      case 'self':
        targets.push(caster);
        break;

      case 'single':
        if (targetUnit) {
          targets.push(targetUnit);
        }
        break;

      case 'allAlly':
        for (const unit of this.units.values()) {
          if (unit.isAlive && this.isAlly(caster, unit)) {
            targets.push(unit);
          }
        }
        break;

      case 'allEnemy':
        for (const unit of this.units.values()) {
          if (unit.isAlive && !this.isAlly(caster, unit)) {
            targets.push(unit);
          }
        }
        break;

      case 'ally':
        if (targetUnit && this.isAlly(caster, targetUnit) && targetUnit.isAlive) {
          targets.push(targetUnit);
        }
        break;

      case 'enemy':
        if (targetUnit && !this.isAlly(caster, targetUnit) && targetUnit.isAlive) {
          targets.push(targetUnit);
        }
        break;

      case 'area':
        if (targetCoords || targetUnit?.coords) {
          const center = targetCoords ?? targetUnit!.coords;
          const radius = skill.effects.find(e => e.aoeRadius)?.aoeRadius ?? 1;
          for (const unit of this.units.values()) {
            if (unit.isAlive && this.getDistance(unit.coords, center) <= radius) {
              targets.push(unit);
            }
          }
        }
        break;

      default:
        if (targetUnit) {
          targets.push(targetUnit);
        }
    }

    return targets;
  }

  applyAuras(caster: CombatUnit): Map<ID, StatusEffect> {
    const appliedEffects = new Map<ID, StatusEffect>();

    for (const passive of caster.passiveSkills) {
      if (passive.type === 'aura' && passive.isActive && passive.auraRadius !== undefined) {
        for (const unit of this.units.values()) {
          if (!unit.isAlive) continue;

          const dist = this.getDistance(caster.coords, unit.coords);
          if (dist <= passive.auraRadius) {
            const shouldAffectAlly = this.isAlly(caster, unit) && passive.canTargetAlly;
            const shouldAffectEnemy = !this.isAlly(caster, unit) && passive.canTargetEnemy;
            const shouldAffectSelf = unit.id === caster.id && passive.canTargetSelf;

            if (shouldAffectAlly || shouldAffectEnemy || shouldAffectSelf) {
              for (let i = 0; i < passive.effects.length; i++) {
                const effect = passive.effects[i];
                if ((effect.type === 'buff' || effect.type === 'debuff') && effect.statusEffect) {
                  const template = this.statusEffectTemplates.get(effect.statusEffect);
                  if (template) {
                    const effectCopy = this.cloneStatusEffect(template);
                    effectCopy.source = caster.id;
                    effectCopy.isDebuff = effect.type === 'debuff';
                    effectCopy.duration = 999;
                    effectCopy.maxDuration = 999;

                    const auraKey = `${caster.id}:${passive.id}:${effect.statusEffect}:${i}`;
                    const existing = unit.statusEffects.find(e => e.id === auraKey);
                    if (!existing) {
                      effectCopy.id = auraKey;
                      const applied = this.statusEffectSystem.applyEffect(unit, effectCopy);
                      if (applied) {
                        appliedEffects.set(applied.id, applied);
                        passive.appliedEffects.set(applied.id, applied);
                        this.events.onAuraApplied?.(caster, passive, unit);
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    return appliedEffects;
  }

  processPassiveTriggers(
    unit: CombatUnit,
    triggerType: string,
    context: Record<string, unknown> = {}
  ): SkillExecutionResult[] {
    const results: SkillExecutionResult[] = [];

    for (const passive of unit.passiveSkills) {
      if (!passive.isActive || !passive.triggerConditions) continue;

      const shouldTrigger = passive.triggerConditions.some(condition => {
        if (condition.type !== triggerType && condition.type !== 'custom') {
          return false;
        }

        if (condition.threshold !== undefined) {
          const value = context.value as number;
          if (value === undefined || value < condition.threshold) {
            return false;
          }
        }

        if (condition.targetFilter) {
          const target = context.target as CombatUnit;
          if (target) {
            if (condition.targetFilter === 'self' && target.id !== unit.id) return false;
            if (condition.targetFilter === 'ally' && !this.isAlly(unit, target)) return false;
            if (condition.targetFilter === 'enemy' && this.isAlly(unit, target)) return false;
          }
        }

        return true;
      });

      if (shouldTrigger) {
        this.events.onPassiveTriggered?.(unit, passive, triggerType);

        for (const effect of passive.effects) {
          let target: CombatUnit | undefined;

          if (effect.target === 'self') {
            target = unit;
          } else if (effect.target === 'enemy') {
            target = context.source as CombatUnit;
          } else if (effect.target === 'ally') {
            target = context.target as CombatUnit;
          } else {
            target = context.target as CombatUnit;
          }

          if (target) {
            const fakeSkill: Skill = {
              ...passive,
              type: 'active',
              targetType: effect.target === 'self' ? 'self' : 'single',
              effects: [effect],
            };
            const result = this.applySkillEffect(unit, fakeSkill, target);
            results.push(result);
          }
        }
      }
    }

    return results;
  }

  private canPayCost(caster: CombatUnit, skill: Skill): boolean {
    return caster.stats.mp >= skill.mpCost && caster.stats.hp > skill.hpCost;
  }

  private payCost(caster: CombatUnit, skill: Skill): void {
    if (skill.mpCost > 0) {
      caster.stats.mp = Math.max(0, caster.stats.mp - skill.mpCost);
      this.events.onMpCost?.(caster, skill.mpCost);
    }
    if (skill.hpCost > 0) {
      caster.stats.hp = Math.max(1, caster.stats.hp - skill.hpCost);
      this.events.onHpCost?.(caster, skill.hpCost);
    }
  }

  private isAlly(a: CombatUnit, b: CombatUnit): boolean {
    return a.faction === b.faction;
  }

  private getDistance(a: CubeCoords, b: CubeCoords): number {
    return Math.max(
      Math.abs(a.q - b.q),
      Math.abs(a.r - b.r),
      Math.abs((a.q + a.r) - (b.q + b.r))
    );
  }

  private getTerrainAttackBonus(coords: CubeCoords): number {
    const key = `${coords.q},${coords.r}`;
    const tile = this.gridTiles.get(key);
    if (!tile) return 0;
    const terrainConfig = this.getTerrainConfig(tile.terrain);
    return terrainConfig?.attackBonus ?? 0;
  }

  private getTerrainDefenseBonus(coords: CubeCoords): number {
    const key = `${coords.q},${coords.r}`;
    const tile = this.gridTiles.get(key);
    if (!tile) return 0;
    const terrainConfig = this.getTerrainConfig(tile.terrain);
    return terrainConfig?.defenseBonus ?? 0;
  }

  private getTileHeight(coords: CubeCoords): number {
    const key = `${coords.q},${coords.r}`;
    const tile = this.gridTiles.get(key);
    return tile?.height ?? 0;
  }

  private getTerrainConfig(_terrain: string): { attackBonus: number; defenseBonus: number } | null {
    return null;
  }

  private cloneStatusEffect(effect: StatusEffect): StatusEffect {
    return {
      ...effect,
      effects: effect.effects.map(e => ({ ...e })),
    };
  }

  addStatusEffectTemplate(id: ID, template: StatusEffect): void {
    this.statusEffectTemplates.set(id, template);
  }

  getStatusEffectTemplate(id: ID): StatusEffect | undefined {
    return this.statusEffectTemplates.get(id);
  }

  setEvents(events: Partial<SkillSystemEventMap>): void {
    this.events = { ...this.events, ...events };
  }

  toJSON(): Record<string, unknown> {
    const templates: Array<{ key: string; value: StatusEffect }> = [];
    for (const [key, value] of this.statusEffectTemplates.entries()) {
      templates.push({ key, value });
    }
    return {
      statusEffectTemplates: templates,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    if (data.statusEffectTemplates) {
      const templates = data.statusEffectTemplates as Array<{ key: string; value: StatusEffect }>;
      this.statusEffectTemplates = new Map();
      for (const item of templates) {
        this.statusEffectTemplates.set(item.key, item.value);
      }
    }
  }
}
