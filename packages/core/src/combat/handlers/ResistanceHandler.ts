import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';
import { clamp } from '../../utils';

export class ResistanceHandler implements IDamageHandler {
  readonly name = 'ResistanceHandler';
  readonly priority = 80;

  handle(
    ctx: DamageContext,
    _config: DamageCalculationConfig,
    _elementChart: ElementChart,
    _events?: DamageCalculatorEventMap
  ): void {
    let flatMitigation = 0;
    let percentMitigation = 0;

    for (const resistance of ctx.target.resistances) {
      if (resistance.type === ctx.skillDamageType || resistance.type === 'all') {
        if (resistance.isPercent) {
          percentMitigation += resistance.value;
        } else {
          flatMitigation += resistance.value;
        }
      }
    }

    percentMitigation = clamp(percentMitigation, 0, 0.9);

    ctx.resistancePercent = percentMitigation;
    ctx.resistanceMitigation = flatMitigation;

    ctx.finalDamage *= (1 - percentMitigation);
    ctx.finalDamage -= flatMitigation;
  }
}
