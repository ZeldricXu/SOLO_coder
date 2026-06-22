import type { IDamageHandler, DamageContext, DamageHandlerResult } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart, HitCalculationResult } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';
import { clamp, chance } from '../../utils';

export class HitHandler implements IDamageHandler {
  readonly name = 'HitHandler';
  readonly priority = 95;

  handle(
    ctx: DamageContext,
    _config: DamageCalculationConfig,
    _elementChart: ElementChart,
    events?: DamageCalculatorEventMap
  ): DamageHandlerResult {
    events?.onBeforeHitCalculate?.(ctx.attacker, ctx.target);

    const accuracy = ctx.attacker.stats.accuracy;
    const evasion = ctx.target.stats.evasion;

    const isGuaranteedHit = ctx.attacker.tags.includes('guaranteed_hit');
    const isGuaranteedMiss = ctx.target.tags.includes('guaranteed_miss');

    let finalHitChance = accuracy - evasion;
    finalHitChance += ctx.terrainBonus * 100;
    finalHitChance += ctx.heightBonus * 100;
    finalHitChance += ctx.directionBonus * 100;
    finalHitChance = clamp(finalHitChance, 0, 100);

    const hit = isGuaranteedHit || (!isGuaranteedMiss && chance(finalHitChance));

    ctx.isHit = hit;
    ctx.isDodged = !hit;

    const result: HitCalculationResult = {
      hit,
      accuracy,
      evasion,
      finalHitChance,
      isGuaranteedHit,
      isGuaranteedMiss,
      terrainBonus: ctx.terrainBonus,
      heightBonus: ctx.heightBonus,
      directionBonus: ctx.directionBonus,
    };

    events?.onAfterHitCalculate?.(result);

    if (!hit) {
      ctx.finalDamage = 0;
      return 'skip_remaining';
    }

    return undefined;
  }
}
