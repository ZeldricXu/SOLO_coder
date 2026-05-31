import { IDataMaskingService, IMaskingStrategy, IPermissionChecker, IFieldConfigRepository, IDataMaskingEncryptionProvider } from './interfaces';
import { User, SensitiveFieldConfig, MaskingStrategyType } from '../core/types';

export class DataMaskingService implements IDataMaskingService {
  private readonly strategies: Map<MaskingStrategyType, IMaskingStrategy> = new Map();

  constructor(
    private readonly permissionChecker: IPermissionChecker,
    private readonly fieldConfigRepository: IFieldConfigRepository,
    private readonly encryptionProvider: IDataMaskingEncryptionProvider,
    private readonly initialStrategies: IMaskingStrategy[] = []
  ) {
    this.initialStrategies.forEach(strategy => this.registerStrategy(strategy));
  }

  public registerStrategy(strategy: IMaskingStrategy): void {
    this.strategies.set(strategy.strategyType, strategy);
  }

  public registerFieldConfig(config: SensitiveFieldConfig): void {
    this.fieldConfigRepository.register(config);
  }

  public registerFieldConfigs(configs: SensitiveFieldConfig[]): void {
    this.fieldConfigRepository.registerMany(configs);
  }

  public getFieldConfig(fieldName: string): SensitiveFieldConfig | undefined {
    return this.fieldConfigRepository.get(fieldName);
  }

  public maskData<T extends Record<string, unknown>>(data: T, user: User): T {
    const maskedData = { ...data };

    for (const [fieldName, value] of Object.entries(maskedData)) {
      const config = this.fieldConfigRepository.get(fieldName);
      if (config && typeof value === 'string') {
        const shouldMask = !this.permissionChecker.hasPermission(user, config);
        if (shouldMask) {
          maskedData[fieldName as keyof T] = this.applyMasking(value, config) as T[keyof T];
        }
      }
    }

    return maskedData;
  }

  public maskArray<T extends Record<string, unknown>>(dataArray: T[], user: User): T[] {
    return dataArray.map(data => this.maskData(data, user));
  }

  public decryptValue(encryptedValue: string): string {
    return this.encryptionProvider.decrypt(encryptedValue);
  }

  private applyMasking(value: string, config: SensitiveFieldConfig): string {
    const strategy = this.strategies.get(config.maskingStrategy);
    return strategy ? strategy.mask(value, config) : value;
  }
}

export const createDataMaskingService = (
  permissionChecker: IPermissionChecker,
  fieldConfigRepository: IFieldConfigRepository,
  encryptionProvider: IDataMaskingEncryptionProvider,
  strategies: IMaskingStrategy[] = []
): DataMaskingService => {
  return new DataMaskingService(
    permissionChecker,
    fieldConfigRepository,
    encryptionProvider,
    strategies
  );
};
