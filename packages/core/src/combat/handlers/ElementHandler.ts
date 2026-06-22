import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';
import { calculateElementMultiplier } from '../../utils';

export class ElementHandler implements IDamageHandler {
  readonly name = 'ElementHandler';
  readonly priority = 30;

  handle(
    ctx: DamageContext,
    config: DamageCalculationConfig,
    elementChart: ElementChart,
    _events?: DamageCalculatorEventMap
  ): void {
    const dominantElement = this.getDominantElement(ctx.target);
    const elementMultiplier = calculateElementMultiplier(
      ctx.skillElement,
      dominantElement,
      elementChart,
      config.elementAdvantageMultiplier,
      config.elementDisadvantageMultiplier
    );

    ctx.elementMultiplier = elementMultiplier;
    ctx.finalDamage *= elementMultiplier;
  }

  private getDominantElement(unit: { affinities: Array<{ element: string; value: number }> }): string {
    if (unit.affinities.length === 0) return 'neutral';

    let maxAffinity = unit.affinities[0];
    for (const affinity of unit.affinities) {
      if (affinity.value > maxAffinity.value) {
        maxAffinity = affinity;
      }
    }
    return maxAffinity.element;
  }
}
