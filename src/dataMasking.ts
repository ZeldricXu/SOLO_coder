import * as crypto from 'crypto';
import { User, SensitiveFieldConfig, MaskingFunction } from './types';

export class DataMaskingModule {
  private fieldConfigs: Map<string, SensitiveFieldConfig> = new Map();
  private encryptionKey: Buffer;

  constructor(encryptionKey: string) {
    this.encryptionKey = crypto.scryptSync(encryptionKey, 'salt', 32);
  }

  public registerFieldConfig(config: SensitiveFieldConfig): void {
    this.fieldConfigs.set(config.fieldName, config);
  }

  public registerFieldConfigs(configs: SensitiveFieldConfig[]): void {
    configs.forEach(config => this.registerFieldConfig(config));
  }

  public getFieldConfig(fieldName: string): SensitiveFieldConfig | undefined {
    return this.fieldConfigs.get(fieldName);
  }

  public maskData<T extends Record<string, unknown>>(data: T, user: User): T {
    const maskedData = { ...data };

    for (const [fieldName, value] of Object.entries(maskedData)) {
      const config = this.fieldConfigs.get(fieldName);
      if (!config) continue;

      const stringValue = this.coerceToString(value);
      if (stringValue === null) continue;

      const shouldMask = !this.hasPermission(user, config);
      if (shouldMask) {
        maskedData[fieldName as keyof T] = this.applyMasking(stringValue, config) as T[keyof T];
      }
    }

    return maskedData;
  }

  public maskArray<T extends Record<string, unknown>>(dataArray: T[], user: User): T[] {
    return dataArray.map(data => this.maskData(data, user));
  }

  private hasPermission(user: User, config: SensitiveFieldConfig): boolean {
    if (!config.requiredPermission) {
      return false;
    }

    return user.roles.some(role => 
      role.permissions.includes(config.requiredPermission!)
    );
  }

  private coerceToString(value: unknown): string | null {
    if (typeof value === 'string') return value;
    if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    if (value instanceof Date) return value.toISOString();
    if (value === null || value === undefined) return null;
    return null;
  }

  private applyMasking(value: string, config: SensitiveFieldConfig): string {
    const maskingStrategies: Record<string, MaskingFunction> = {
      full: this.maskFull.bind(this),
      partial: this.maskPartial.bind(this),
      hash: this.maskHash.bind(this),
      encrypt: this.maskEncrypt.bind(this),
      remove: () => ''
    };

    const strategy = maskingStrategies[config.maskingStrategy];
    return strategy ? strategy(value, config) : value;
  }

  private maskFull(value: string): string {
    return '*'.repeat(value.length);
  }

  private maskPartial(value: string, config?: SensitiveFieldConfig): string {
    if (!config) return '*'.repeat(value.length);
    const partialConfig = config.partialMasking || { visibleStart: 0, visibleEnd: 0, maskChar: '*' };
    const { visibleStart, visibleEnd, maskChar } = partialConfig;

    if (value.length <= visibleStart + visibleEnd) {
      return maskChar.repeat(value.length);
    }

    const start = value.slice(0, visibleStart);
    const middle = maskChar.repeat(value.length - visibleStart - visibleEnd);
    const end = visibleEnd > 0 ? value.slice(-visibleEnd) : '';

    return start + middle + end;
  }

  private maskHash(value: string): string {
    return crypto.createHash('sha256').update(value).digest('hex').slice(0, 16);
  }

  private maskEncrypt(value: string): string {
    const iv = crypto.randomBytes(16);
    const cipher = crypto.createCipheriv('aes-256-gcm', this.encryptionKey, iv);
    
    let encrypted = cipher.update(value, 'utf8', 'hex');
    encrypted += cipher.final('hex');
    
    const authTag = cipher.getAuthTag().toString('hex');
    
    return `${iv.toString('hex')}:${encrypted}:${authTag}`;
  }

  public decryptValue(encryptedValue: string): string {
    try {
      const parts = encryptedValue.split(':');
      const ivHex = parts[0];
      const authTagHex = parts[parts.length - 1];
      const encryptedHex = parts.slice(1, -1).join(':');
      
      const iv = Buffer.from(ivHex, 'hex');
      const encrypted = Buffer.from(encryptedHex, 'hex');
      const authTag = Buffer.from(authTagHex, 'hex');

      const decipher = crypto.createDecipheriv('aes-256-gcm', this.encryptionKey, iv);
      decipher.setAuthTag(authTag);

      let decrypted = decipher.update(encrypted.toString('hex'), 'hex', 'utf8');
      decrypted += decipher.final('utf8');

      return decrypted;
    } catch {
      return '';
    }
  }
}

export const createDataMasking = (encryptionKey: string): DataMaskingModule => {
  return new DataMaskingModule(encryptionKey);
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
