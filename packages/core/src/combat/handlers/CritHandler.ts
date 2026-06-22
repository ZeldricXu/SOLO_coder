import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';
import { chance } from '../../utils';

export class CritHandler implements IDamageHandler {
  readonly name = 'CritHandler';
  readonly priority = 70;

  handle(
    ctx: DamageContext,
    _config: DamageCalculationConfig,
    _elementChart: ElementChart,
    events?: DamageCalculatorEventMap
  ): void {
    const critRate = ctx.attacker.stats.critRate;
    const isCrit = !ctx.target.tags.includes('immune_crit') && chance(critRate);
    const critMultiplier = isCrit ? ctx.attacker.stats.critDamage : 1;

    ctx.isCrit = isCrit;
    ctx.critMultiplier = critMultiplier;
    ctx.finalDamage *= critMultiplier;

    if (isCrit && !ctx.isDodged) {
      events?.onCrit?.(ctx.attacker, ctx.target, critMultiplier);
    }
  }
}
