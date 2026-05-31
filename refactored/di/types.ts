export const TYPES = {
  DataMasking: {
    Service: Symbol.for('DataMaskingService'),
    PermissionChecker: Symbol.for('PermissionChecker'),
    FieldConfigRepository: Symbol.for('FieldConfigRepository'),
    EncryptionProvider: Symbol.for('DataMaskingEncryptionProvider'),
    Strategies: {
      Full: Symbol.for('FullMaskingStrategy'),
      Partial: Symbol.for('PartialMaskingStrategy'),
      Hash: Symbol.for('HashMaskingStrategy'),
      Encrypt: Symbol.for('EncryptMaskingStrategy'),
      Remove: Symbol.for('RemoveMaskingStrategy'),
    }
  },
  AuditLog: {
    Service: Symbol.for('AuditLogService'),
    HashProvider: Symbol.for('HashProvider'),
    PowMiner: Symbol.for('PowMiner'),
    LogRepository: Symbol.for('LogRepository'),
    ChainVerifier: Symbol.for('ChainVerifier'),
  },
  TEE: {
    Service: Symbol.for('TEEService'),
    EnclaveManager: Symbol.for('EnclaveManager'),
    CryptoProvider: Symbol.for('TEECryptoProvider'),
    AttestationService: Symbol.for('AttestationService'),
    KeyDerivationService: Symbol.for('KeyDerivationService'),
  },
  Config: {
    DataMaskingEncryptionKey: Symbol.for('DataMaskingEncryptionKey'),
    TEEMasterKey: Symbol.for('TEEMasterKey'),
    AuditLogDifficulty: Symbol.for('AuditLogDifficulty'),
  }
} as const;

export interface InjectionToken<T = unknown> {
  symbol: symbol;
  name: string;
}

export type Class<T = unknown> = new (...args: unknown[]) => T;

export interface Factory<T = unknown> {
  (container: DIContainer): T;
}

export type Provider<T = unknown> =
  | { type: 'class'; token: symbol; implementation: Class<T> }
  | { type: 'factory'; token: symbol; factory: Factory<T> }
  | { type: 'value'; token: symbol; value: T };

export interface DIContainer {
  bind<T>(token: symbol, implementation: Class<T>): void;
  bindFactory<T>(token: symbol, factory: Factory<T>): void;
  bindValue<T>(token: symbol, value: T): void;
  get<T>(token: symbol): T;
  isBound(token: symbol): boolean;
}
