import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';

export class ArmorPenetrationHandler implements IDamageHandler {
  readonly name = 'ArmorPenetrationHandler';
  readonly priority = 0;

  handle(
    ctx: DamageContext,
    _config: DamageCalculationConfig,
    _elementChart: ElementChart,
    _events?: DamageCalculatorEventMap
  ): void {
    const isMagic = ctx.skillDamageType === 'magic';
    const baseDefense = isMagic
      ? ctx.target.stats.magicDefense
      : ctx.target.stats.defense;
    const armorPen = ctx.armorPenetrationOverride ?? ctx.attacker.stats.armorPenetration;
    ctx.armorPenApplied = armorPen;
    (ctx as DamageContext & { _effectiveDefense?: number })._effectiveDefense = Math.max(0, baseDefense - armorPen);
  }
}
