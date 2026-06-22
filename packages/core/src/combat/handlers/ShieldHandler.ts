import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';

export class ShieldHandler implements IDamageHandler {
  readonly name = 'ShieldHandler';
  readonly priority = 90;

  handle(
    ctx: DamageContext,
    _config: DamageCalculationConfig,
    _elementChart: ElementChart,
    _events?: DamageCalculatorEventMap
  ): void {
    const targetStats = ctx.target.stats as { shield?: number };
    if (targetStats.shield && targetStats.shield > 0) {
      const absorbed = Math.min(targetStats.shield, ctx.finalDamage);
      ctx.shieldAbsorbed = absorbed;
      ctx.finalDamage -= absorbed;
      targetStats.shield -= absorbed;
    } else {
      ctx.shieldAbsorbed = 0;
    }
  }
}
