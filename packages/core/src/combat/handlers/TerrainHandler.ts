import type { IDamageHandler, DamageContext } from '../DamageChain';
import type { DamageCalculationConfig, ElementChart } from '../../types';
import type { DamageCalculatorEventMap } from '../DamageCalculator';

export class TerrainHandler implements IDamageHandler {
  readonly name = 'TerrainHandler';
  readonly priority = 40;

  handle(
    ctx: DamageContext,
    _config: DamageCalculationConfig,
    _elementChart: ElementChart,
    _events?: DamageCalculatorEventMap
  ): void {
    const terrainMultiplier = 1 + ctx.terrainBonus;
    ctx.terrainMultiplier = terrainMultiplier;
    ctx.finalDamage *= terrainMultiplier;
  }
}
