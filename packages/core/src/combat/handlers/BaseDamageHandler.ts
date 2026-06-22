import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';
import { evaluateFormula } from '../../utils';

export class BaseDamageHandler implements IDamageHandler {
  readonly name = 'BaseDamageHandler';
  readonly priority = 10;

  handle(
    ctx: DamageContext,
    config: DamageCalculationConfig,
    _elementChart: ElementChart,
    _events?: DamageCalculatorEventMap
  ): void {
    const isMagic = ctx.skillDamageType === 'magic';
    const baseAttack = isMagic
      ? ctx.attacker.stats.magicAttack
      : ctx.attacker.stats.attack;
    const baseDefense = isMagic
      ? ctx.target.stats.magicDefense
      : ctx.target.stats.defense;
    const armorPen = ctx.armorPenetrationOverride ?? ctx.attacker.stats.armorPenetration;

    const effectiveDefense = Math.max(0, baseDefense - armorPen);

    ctx.baseDamage = ctx.baseDamageOverride ?? evaluateFormula(config.baseFormula, {
      attack: baseAttack,
      defense: effectiveDefense,
      armorPenetration: armorPen,
      baseAttack,
      targetDefense: effectiveDefense,
    });

    ctx.finalDamage = ctx.baseDamage;
  }
}
