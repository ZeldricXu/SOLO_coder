import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';

export class HeightHandler implements IDamageHandler {
  readonly name = 'HeightHandler';
  readonly priority = 50;

  handle(
    ctx: DamageContext,
    _config: DamageCalculationConfig,
    _elementChart: ElementChart,
    _events?: DamageCalculatorEventMap
  ): void {
    const heightMultiplier = 1 + ctx.heightBonus;
    ctx.heightMultiplier = heightMultiplier;
    ctx.finalDamage *= heightMultiplier;
  }
}
