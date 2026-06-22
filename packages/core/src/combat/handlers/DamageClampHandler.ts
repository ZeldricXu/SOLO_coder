import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart, DamageInstance } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';
import { clamp } from '../../utils';

export class DamageClampHandler implements IDamageHandler {
  readonly name = 'DamageClampHandler';
  readonly priority = 100;

  handle(
    ctx: DamageContext,
    config: DamageCalculationConfig,
    _elementChart: ElementChart,
    _events?: DamageCalculatorEventMap
  ): void {
    const clamped = clamp(ctx.finalDamage, config.minDamage, config.maxDamage);
    const floored = Math.floor(clamped);
    ctx.finalDamage = floored;

    const instance: Partial<DamageInstance> = {
      sourceId: ctx.attacker.id,
      targetId: ctx.target.id,
      skillId: ctx.skillId,
      baseDamage: ctx.baseDamage,
      finalDamage: floored,
      damageType: ctx.skillDamageType,
      element: ctx.skillElement,
      isCrit: (ctx.isCrit ?? false) && !(ctx.isDodged ?? false),
      isBlocked: false,
      isDodged: ctx.isDodged ?? false,
      armorMitigation: 0,
      resistanceMitigation: ctx.resistanceMitigation ?? 0,
      terrainBonus: ctx.terrainBonus,
      elementBonus: (ctx.elementMultiplier ?? 1) - 1,
      position: ctx.target.coords,
      timestamp: Date.now(),
    };

    ctx.instance = instance;
  }
}
