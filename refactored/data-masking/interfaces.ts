import { User, SensitiveFieldConfig, MaskingStrategyType } from '../core/types';

export interface IMaskingStrategy {
  readonly strategyType: MaskingStrategyType;
  mask(value: string, config?: SensitiveFieldConfig): string;
}

export interface IPermissionChecker {
  hasPermission(user: User, config: SensitiveFieldConfig): boolean;
}

export interface IFieldConfigRepository {
  register(config: SensitiveFieldConfig): void;
  registerMany(configs: SensitiveFieldConfig[]): void;
  get(fieldName: string): SensitiveFieldConfig | undefined;
  getAll(): SensitiveFieldConfig[];
  remove(fieldName: string): boolean;
  clear(): void;
}

export interface IDataMaskingEncryptionProvider {
  encrypt(value: string): string;
  decrypt(encryptedValue: string): string;
}

export interface IDataMaskingService {
  registerFieldConfig(config: SensitiveFieldConfig): void;
  registerFieldConfigs(configs: SensitiveFieldConfig[]): void;
  getFieldConfig(fieldName: string): SensitiveFieldConfig | undefined;
  maskData<T extends Record<string, unknown>>(data: T, user: User): T;
  maskArray<T extends Record<string, unknown>>(dataArray: T[], user: User): T[];
  decryptValue(encryptedValue: string): string;
  registerStrategy(strategy: IMaskingStrategy): void;
}
