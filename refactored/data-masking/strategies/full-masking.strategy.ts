import { IMaskingStrategy } from '../interfaces';
import { MaskingStrategyType } from '../../core/types';

export class FullMaskingStrategy implements IMaskingStrategy {
  public readonly strategyType: MaskingStrategyType = 'full';

  public mask(value: string): string {
    return '*'.repeat(value.length);
  }
}

export const createFullMaskingStrategy = (): FullMaskingStrategy => {
  return new FullMaskingStrategy();
};
