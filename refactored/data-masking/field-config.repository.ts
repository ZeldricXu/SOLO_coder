import { IFieldConfigRepository } from './interfaces';
import { SensitiveFieldConfig } from '../core/types';

export class FieldConfigRepository implements IFieldConfigRepository {
  private readonly fieldConfigs: Map<string, SensitiveFieldConfig> = new Map();

  public register(config: SensitiveFieldConfig): void {
    this.fieldConfigs.set(config.fieldName, config);
  }

  public registerMany(configs: SensitiveFieldConfig[]): void {
    configs.forEach(config => this.register(config));
  }

  public get(fieldName: string): SensitiveFieldConfig | undefined {
    return this.fieldConfigs.get(fieldName);
  }

  public getAll(): SensitiveFieldConfig[] {
    return Array.from(this.fieldConfigs.values());
  }

  public remove(fieldName: string): boolean {
    return this.fieldConfigs.delete(fieldName);
  }

  public clear(): void {
    this.fieldConfigs.clear();
  }
}

export const createFieldConfigRepository = (): FieldConfigRepository => {
  return new FieldConfigRepository();
};

export const defaultMaskingConfigs: SensitiveFieldConfig[] = [
  {
    fieldName: 'idCard',
    sensitivityLevel: 'restricted',
    maskingStrategy: 'partial',
    requiredPermission: 'view:idCard',
    partialMasking: { visibleStart: 6, visibleEnd: 4, maskChar: '*' }
  },
  {
    fieldName: 'phone',
    sensitivityLevel: 'confidential',
    maskingStrategy: 'partial',
    requiredPermission: 'view:phone',
    partialMasking: { visibleStart: 3, visibleEnd: 4, maskChar: '*' }
  },
  {
    fieldName: 'email',
    sensitivityLevel: 'confidential',
    maskingStrategy: 'partial',
    requiredPermission: 'view:email',
    partialMasking: { visibleStart: 2, visibleEnd: 0, maskChar: '*' }
  },
  {
    fieldName: 'address',
    sensitivityLevel: 'confidential',
    maskingStrategy: 'partial',
    requiredPermission: 'view:address',
    partialMasking: { visibleStart: 6, visibleEnd: 0, maskChar: '*' }
  },
  {
    fieldName: 'bankCard',
    sensitivityLevel: 'restricted',
    maskingStrategy: 'partial',
    requiredPermission: 'view:bankCard',
    partialMasking: { visibleStart: 4, visibleEnd: 4, maskChar: '*' }
  },
  {
    fieldName: 'password',
    sensitivityLevel: 'restricted',
    maskingStrategy: 'hash',
    requiredPermission: 'never'
  },
  {
    fieldName: 'salary',
    sensitivityLevel: 'restricted',
    maskingStrategy: 'encrypt',
    requiredPermission: 'view:salary'
  }
];
