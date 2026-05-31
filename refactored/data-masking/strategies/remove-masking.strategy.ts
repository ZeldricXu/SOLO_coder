import { IMaskingStrategy } from '../interfaces';
import { MaskingStrategyType } from '../../core/types';

export class RemoveMaskingStrategy implements IMaskingStrategy {
  public readonly strategyType: MaskingStrategyType = 'remove';

  public mask(): string {
    return '';
  }
}

export const createRemoveMaskingStrategy = (): RemoveMaskingStrategy => {
  return new RemoveMaskingStrategy();
};
