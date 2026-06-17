import type {
  CombatUnit,
  StatusEffect,
  UnitStats,
  DamageInstance,
  HealInstance,
} from '../types';
import type { ID, DamageType, ElementType } from '../types/common';
import {
  clamp,
  Serializable,
} from '../utils';

export type StatusEffectSystemEventMap = {
  onBeforeApplyEffect?: (unit: CombatUnit, effect: StatusEffect) => boolean | void;
  onAfterApplyEffect?: (unit: CombatUnit, effect: StatusEffect) => void;
  onBeforeRemoveEffect?: (unit: CombatUnit, effect: StatusEffect) => boolean | void;
  onAfterRemoveEffect?: (unit: CombatUnit, effect: StatusEffect) => void;
  onBeforeTickEffects?: (unit: CombatUnit) => void;
  onAfterTickEffects?: (unit: CombatUnit, tickedEffects: StatusEffectTickResult[]) => void;
  onEffectTick?: (unit: CombatUnit, effect: StatusEffect, tickData: StatusEffectTickData) => void;
  onEffectStack?: (unit: CombatUnit, effect: StatusEffect, stackCount: number) => void;
  onStatModified?: (unit: CombatUnit, stat: keyof UnitStats, oldValue: number, newValue: number) => void;
};

export interface StatusEffectTickData {
  damage?: number;
  heal?: number;
  statChanges?: Partial<Record<keyof UnitStats, number>>;
}

export interface StatusEffectTickResult {
  effectId: ID;
  effectType: string;
  data: StatusEffectTickData;
  damageInstance?: DamageInstance;
  healInstance?: HealInstance;
  durationRemaining: number;
  expired: boolean;
}

export class StatusEffectSystem implements Serializable {
  private events: StatusEffectSystemEventMap;

  constructor(events: StatusEffectSystemEventMap = {}) {
    this.events = events;
  }

  applyEffect(unit: CombatUnit, effect: StatusEffect): StatusEffect | null {
    if (this.events.onBeforeApplyEffect?.(unit, effect) === false) {
      return null;
    }

    if (unit.tags.includes('immune_status_all')) {
      return null;
    }

    if (unit.tags.includes(`immune_status_${effect.type}`)) {
      return null;
    }

    const existingIndex = unit.statusEffects.findIndex(
      e => e.type === effect.type && e.source === effect.source
    );

    if (existingIndex >= 0) {
      const existing = unit.statusEffects[existingIndex];
      return this.stackEffect(unit, existing, effect);
    }

    this.applyEffectsToStats(unit, effect);
    unit.statusEffects.push(effect);

    this.events.onAfterApplyEffect?.(unit, effect);
    return effect;
  }

  removeEffect(unit: CombatUnit, effectId: ID): StatusEffect | null {
    const index = unit.statusEffects.findIndex(e => e.id === effectId);
    if (index < 0) return null;

    const effect = unit.statusEffects[index];

    if (this.events.onBeforeRemoveEffect?.(unit, effect) === false) {
      return null;
    }

    this.removeEffectsFromStats(unit, effect);
    unit.statusEffects.splice(index, 1);

    this.events.onAfterRemoveEffect?.(unit, effect);
    return effect;
  }

  removeEffectsByType(unit: CombatUnit, effectType: string): StatusEffect[] {
    const removed: StatusEffect[] = [];
    const toRemove = unit.statusEffects.filter(e => e.type === effectType);

    for (const effect of toRemove) {
      const result = this.removeEffect(unit, effect.id);
      if (result) {
        removed.push(result);
      }
    }

    return removed;
  }

  removeDebuffs(unit: CombatUnit): StatusEffect[] {
    const removed: StatusEffect[] = [];
    const toRemove = unit.statusEffects.filter(e => e.isDebuff);

    for (const effect of toRemove) {
      const result = this.removeEffect(unit, effect.id);
      if (result) {
        removed.push(result);
      }
    }

    return removed;
  }

  removeBuffs(unit: CombatUnit): StatusEffect[] {
    const removed: StatusEffect[] = [];
    const toRemove = unit.statusEffects.filter(e => !e.isDebuff);

    for (const effect of toRemove) {
      const result = this.removeEffect(unit, effect.id);
      if (result) {
        removed.push(result);
      }
    }

    return removed;
  }

  tickEffects(unit: CombatUnit): StatusEffectTickResult[] {
    this.events.onBeforeTickEffects?.(unit);

    const results: StatusEffectTickResult[] = [];
    const expiredEffects: ID[] = [];
    const timestamp = Date.now();

    for (let i = 0; i < unit.statusEffects.length; i++) {
      const effect = unit.statusEffects[i];
      const tickData: StatusEffectTickData = {};

      const shouldTickNow = this.shouldTickNow(effect);
      let ticked = false;

      if (shouldTickNow) {
        for (const effectData of effect.effects) {
          if (effectData.damageType) {
            const damage = effectData.value;
            tickData.damage = (tickData.damage ?? 0) + damage;
            unit.stats.hp = Math.max(0, unit.stats.hp - damage);
            ticked = true;
          } else if (effectData.stat) {
            if (effectData.modifierType === 'add') {
              tickData.statChanges = tickData.statChanges ?? {};
              tickData.statChanges[effectData.stat] = (tickData.statChanges[effectData.stat] ?? 0) + effectData.value;
            }
          } else {
            const isHeal = effect.type === 'regen' || effect.type === 'hot';
            if (isHeal) {
              const healAmount = effectData.value;
              tickData.heal = (tickData.heal ?? 0) + healAmount;
              unit.stats.hp = Math.min(unit.stats.maxHp, unit.stats.hp + healAmount);
              ticked = true;
            }
          }
        }
      }

      if (effect.duration > 0) {
        effect.duration -= 1;
      }

      if (shouldTickNow) {
        effect.lastTick = timestamp;
      }

      const expired = effect.duration <= 0;
      if (expired) {
        expiredEffects.push(effect.id);
      }

      if (ticked || expired) {
        let damageInstance: DamageInstance | undefined;
        let healInstance: HealInstance | undefined;

        if (tickData.damage !== undefined) {
          damageInstance = {
            sourceId: effect.source,
            targetId: unit.id,
            baseDamage: tickData.damage,
            finalDamage: tickData.damage,
            damageType: (effect.effects.find(e => e.damageType)?.damageType ?? 'physical') as DamageType,
            element: (effect.effects.find(e => e.element)?.element ?? 'neutral') as ElementType,
            isCrit: false,
            isBlocked: false,
            isDodged: false,
            armorMitigation: 0,
            resistanceMitigation: 0,
            terrainBonus: 0,
            elementBonus: 0,
            position: unit.coords,
            timestamp,
          };
        }

        if (tickData.heal !== undefined) {
          healInstance = {
            sourceId: effect.source,
            targetId: unit.id,
            baseHeal: tickData.heal,
            finalHeal: tickData.heal,
            isCrit: false,
            isOverheal: false,
            overhealAmount: 0,
            position: unit.coords,
            timestamp,
          };
        }

        results.push({
          effectId: effect.id,
          effectType: effect.type,
          data: tickData,
          damageInstance,
          healInstance,
          durationRemaining: effect.duration,
          expired,
        });

        if (ticked) {
          this.events.onEffectTick?.(unit, effect, tickData);
        }
      }
    }

    for (const effectId of expiredEffects) {
      this.removeEffect(unit, effectId);
    }

    this.events.onAfterTickEffects?.(unit, results);
    return results;
  }

  modifyStat(
    unit: CombatUnit,
    stat: keyof UnitStats,
    value: number,
    modifierType: 'add' | 'multiply' | 'set' = 'add'
  ): number {
    const oldValue = unit.stats[stat];
    let newValue: number;

    switch (modifierType) {
      case 'add':
        newValue = oldValue + value;
        break;
      case 'multiply':
        newValue = oldValue * value;
        break;
      case 'set':
        newValue = value;
        break;
    }

    newValue = this.clampStat(stat, newValue);
    unit.stats[stat] = newValue;

    this.events.onStatModified?.(unit, stat, oldValue, newValue);
    return newValue;
  }

  stackEffect(
    unit: CombatUnit,
    existing: StatusEffect,
    incoming: StatusEffect
  ): StatusEffect {
    if (existing.stackCount < existing.maxStacks) {
      existing.stackCount += 1;
      this.events.onEffectStack?.(unit, existing, existing.stackCount);
    }

    if (incoming.duration > existing.duration) {
      this.removeEffectsFromStats(unit, existing);
      existing.duration = incoming.duration;
      existing.maxDuration = Math.max(existing.maxDuration, incoming.maxDuration);
      this.applyEffectsToStats(unit, existing);
    }

    const stackMultiplier = 1 + (existing.stackCount - 1) * 0.1;
    existing.effects = existing.effects.map(e => ({
      ...e,
      value: Math.round(e.value * stackMultiplier),
    }));

    return existing;
  }

  getEffectsByType(unit: CombatUnit, effectType: string): StatusEffect[] {
    return unit.statusEffects.filter(e => e.type === effectType);
  }

  getActiveBuffs(unit: CombatUnit): StatusEffect[] {
    return unit.statusEffects.filter(e => !e.isDebuff);
  }

  getActiveDebuffs(unit: CombatUnit): StatusEffect[] {
    return unit.statusEffects.filter(e => e.isDebuff);
  }

  hasEffectType(unit: CombatUnit, effectType: string): boolean {
    return unit.statusEffects.some(e => e.type === effectType);
  }

  getTotalStatModifier(unit: CombatUnit, stat: keyof UnitStats): number {
    let totalAdd = 0;
    let totalMultiply = 1;
    let setValue: number | null = null;

    for (const effect of unit.statusEffects) {
      for (const effectData of effect.effects) {
        if (effectData.stat === stat) {
          const stackMultiplier = effect.stackCount > 1
            ? 1 + (effect.stackCount - 1) * 0.1
            : 1;
          const value = effectData.value * stackMultiplier;

          switch (effectData.modifierType) {
            case 'add':
              totalAdd += value;
              break;
            case 'multiply':
              totalMultiply *= (1 + value);
              break;
            case 'set':
              if (setValue === null || Math.abs(value) > Math.abs(setValue)) {
                setValue = value;
              }
              break;
          }
        }
      }
    }

    if (setValue !== null) {
      return setValue;
    }

    const nonAttributeStats = ['maxHp', 'hp', 'maxMp', 'mp', 'height'];
    if (nonAttributeStats.includes(stat as string)) {
      return unit.stats[stat] * totalMultiply + totalAdd - unit.stats[stat];
    }

    type AttributeKey = 'attack' | 'defense' | 'magicAttack' | 'magicDefense' | 'speed' | 'accuracy' | 'evasion' | 'critRate' | 'critDamage' | 'armorPenetration' | 'moveRange' | 'attackRange' | 'visionRange';
    const attrStat = stat as AttributeKey;
    const attr = unit.attributes[attrStat];
    const base = attr?.base ?? unit.stats[stat];
    return base * totalMultiply + totalAdd - base;
  }

  recalculateAllStats(unit: CombatUnit): void {
    const nonAttributeStats = ['maxHp', 'hp', 'maxMp', 'mp', 'height'];
    type AttributeKey = 'attack' | 'defense' | 'magicAttack' | 'magicDefense' | 'speed' | 'accuracy' | 'evasion' | 'critRate' | 'critDamage' | 'armorPenetration' | 'moveRange' | 'attackRange' | 'visionRange';

    const statKeys: (keyof UnitStats)[] = [
      'maxHp', 'hp', 'maxMp', 'mp',
      'attack', 'defense', 'magicAttack', 'magicDefense',
      'speed', 'accuracy', 'evasion', 'critRate', 'critDamage',
      'armorPenetration', 'moveRange', 'attackRange', 'visionRange', 'height'
    ];

    for (const stat of statKeys) {
      if (stat === 'hp' || stat === 'mp') continue;

      let baseValue: number;
      if (nonAttributeStats.includes(stat as string)) {
        baseValue = unit.stats[stat];
      } else {
        const attrStat = stat as AttributeKey;
        const attr = unit.attributes[attrStat];
        baseValue = attr?.base ?? unit.stats[stat];
      }
      const modifier = this.getTotalStatModifier(unit, stat);
      unit.stats[stat] = this.clampStat(stat, baseValue + modifier);
    }
  }

  clearAllEffects(unit: CombatUnit): StatusEffect[] {
    const removed: StatusEffect[] = [];
    const allEffects = [...unit.statusEffects];

    for (const effect of allEffects) {
      const result = this.removeEffect(unit, effect.id);
      if (result) {
        removed.push(result);
      }
    }

    return removed;
  }

  private applyEffectsToStats(unit: CombatUnit, effect: StatusEffect): void {
    for (const effectData of effect.effects) {
      if (effectData.stat && !effectData.damageType) {
        this.modifyStat(unit, effectData.stat, effectData.value, effectData.modifierType);
      }
    }
  }

  private removeEffectsFromStats(unit: CombatUnit, effect: StatusEffect): void {
    const nonAttributeStats = ['maxHp', 'hp', 'maxMp', 'mp', 'height'];
    type AttributeKey = 'attack' | 'defense' | 'magicAttack' | 'magicDefense' | 'speed' | 'accuracy' | 'evasion' | 'critRate' | 'critDamage' | 'armorPenetration' | 'moveRange' | 'attackRange' | 'visionRange';

    for (const effectData of effect.effects) {
      if (effectData.stat && !effectData.damageType) {
        const inverseType: 'add' | 'multiply' | 'set' =
          effectData.modifierType === 'add' ? 'add' :
          effectData.modifierType === 'multiply' ? 'multiply' : 'set';
        
        let inverseValue: number;
        const stat = effectData.stat;
        
        if (effectData.modifierType === 'add') {
          inverseValue = -effectData.value;
        } else if (effectData.modifierType === 'multiply') {
          inverseValue = 1 / (1 + effectData.value);
        } else if (nonAttributeStats.includes(stat as string)) {
          inverseValue = unit.stats[stat];
        } else {
          const attrStat = stat as AttributeKey;
          const attr = unit.attributes[attrStat];
          inverseValue = attr?.base ?? unit.stats[stat];
        }

        this.modifyStat(unit, effectData.stat, inverseValue, inverseType);
      }
    }
  }

  private shouldTickNow(effect: StatusEffect): boolean {
    if (effect.tickInterval <= 0) return true;

    const now = Date.now();
    return (now - effect.lastTick) >= effect.tickInterval * 1000;
  }

  private clampStat(stat: keyof UnitStats, value: number): number {
    switch (stat) {
      case 'hp':
      case 'mp':
        return clamp(Math.floor(value), 0, Number.MAX_SAFE_INTEGER);
      case 'critRate':
      case 'accuracy':
      case 'evasion':
        return clamp(value, 0, 100);
      default:
        return clamp(Math.floor(value), 0, Number.MAX_SAFE_INTEGER);
    }
  }

  setEvents(events: Partial<StatusEffectSystemEventMap>): void {
    this.events = { ...this.events, ...events };
  }

  toJSON(): Record<string, unknown> {
    return {};
  }

  fromJSON(_data: Record<string, unknown>): void {
  }
}
