import { IMaskingStrategy } from '../interfaces';
import { MaskingStrategyType } from '../../core/types';
import { IDataMaskingEncryptionProvider } from '../interfaces';

export class EncryptMaskingStrategy implements IMaskingStrategy {
  public readonly strategyType: MaskingStrategyType = 'encrypt';
  
  constructor(private readonly encryptionProvider: IDataMaskingEncryptionProvider) {}

  public mask(value: string): string {
    return this.encryptionProvider.encrypt(value);
  }
}

export const createEncryptMaskingStrategy = (
  encryptionProvider: IDataMaskingEncryptionProvider
): EncryptMaskingStrategy => {
  return new EncryptMaskingStrategy(encryptionProvider);
};
