import * as crypto from 'crypto';
import { IMaskingStrategy } from '../interfaces';
import { MaskingStrategyType } from '../../core/types';

export class HashMaskingStrategy implements IMaskingStrategy {
  public readonly strategyType: MaskingStrategyType = 'hash';

  public mask(value: string): string {
    return crypto.createHash('sha256').update(value).digest('hex').slice(0, 16);
  }
}

export const createHashMaskingStrategy = (): HashMaskingStrategy => {
  return new HashMaskingStrategy();
};
