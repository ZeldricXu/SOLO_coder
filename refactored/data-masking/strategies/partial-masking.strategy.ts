import { IMaskingStrategy } from '../interfaces';
import { SensitiveFieldConfig, MaskingStrategyType } from '../../core/types';

export class PartialMaskingStrategy implements IMaskingStrategy {
  public readonly strategyType: MaskingStrategyType = 'partial';

  public mask(value: string, config?: SensitiveFieldConfig): string {
    const partialConfig = config?.partialMasking || { visibleStart: 0, visibleEnd: 0, maskChar: '*' };
    const { visibleStart, visibleEnd, maskChar } = partialConfig;

    if (value.length <= visibleStart + visibleEnd) {
      return maskChar.repeat(value.length);
    }

    const start = value.slice(0, visibleStart);
    const middle = maskChar.repeat(value.length - visibleStart - visibleEnd);
    const end = value.slice(-visibleEnd);

    return start + middle + end;
  }
}

export const createPartialMaskingStrategy = (): PartialMaskingStrategy => {
  return new PartialMaskingStrategy();
};
