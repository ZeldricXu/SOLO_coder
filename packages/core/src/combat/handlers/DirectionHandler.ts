import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';

export class DirectionHandler implements IDamageHandler {
  readonly name = 'DirectionHandler';
  readonly priority = 60;

  handle(
    ctx: DamageContext,
    _config: DamageCalculationConfig,
    _elementChart: ElementChart,
    _events?: DamageCalculatorEventMap
  ): void {
    const directionMultiplier = 1 + ctx.directionBonus;
    ctx.directionMultiplier = directionMultiplier;
    ctx.finalDamage *= directionMultiplier;
  }
}
