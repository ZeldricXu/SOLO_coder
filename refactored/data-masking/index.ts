export * from './interfaces';
export * from '../core/types';

export { FullMaskingStrategy, createFullMaskingStrategy } from './strategies/full-masking.strategy';
export { PartialMaskingStrategy, createPartialMaskingStrategy } from './strategies/partial-masking.strategy';
export { HashMaskingStrategy, createHashMaskingStrategy } from './strategies/hash-masking.strategy';
export { EncryptMaskingStrategy, createEncryptMaskingStrategy } from './strategies/encrypt-masking.strategy';
export { RemoveMaskingStrategy, createRemoveMaskingStrategy } from './strategies/remove-masking.strategy';

export { PermissionChecker, createPermissionChecker } from './permission-checker.service';
export { 
  FieldConfigRepository, 
  createFieldConfigRepository,
  defaultMaskingConfigs 
} from './field-config.repository';
export { DataMaskingEncryptionProvider, createDataMaskingEncryptionProvider } from './encryption-provider';
export { DataMaskingService, createDataMaskingService } from './data-masking.service';

import { FullMaskingStrategy, createFullMaskingStrategy } from './strategies/full-masking.strategy';
import { PartialMaskingStrategy, createPartialMaskingStrategy } from './strategies/partial-masking.strategy';
import { HashMaskingStrategy, createHashMaskingStrategy } from './strategies/hash-masking.strategy';
import { EncryptMaskingStrategy, createEncryptMaskingStrategy } from './strategies/encrypt-masking.strategy';
import { RemoveMaskingStrategy, createRemoveMaskingStrategy } from './strategies/remove-masking.strategy';
import { PermissionChecker, createPermissionChecker } from './permission-checker.service';
import { FieldConfigRepository, createFieldConfigRepository, defaultMaskingConfigs } from './field-config.repository';
import { DataMaskingEncryptionProvider, createDataMaskingEncryptionProvider } from './encryption-provider';
import { DataMaskingService, createDataMaskingService } from './data-masking.service';

export const createDataMaskingModule = (encryptionKey: string): DataMaskingService => {
  const permissionChecker = createPermissionChecker();
  const fieldConfigRepository = createFieldConfigRepository();
  const encryptionProvider = createDataMaskingEncryptionProvider(encryptionKey);

  const fullStrategy = createFullMaskingStrategy();
  const partialStrategy = createPartialMaskingStrategy();
  const hashStrategy = createHashMaskingStrategy();
  const encryptStrategy = createEncryptMaskingStrategy(encryptionProvider);
  const removeStrategy = createRemoveMaskingStrategy();

  const service = createDataMaskingService(
    permissionChecker,
    fieldConfigRepository,
    encryptionProvider,
    [fullStrategy, partialStrategy, hashStrategy, encryptStrategy, removeStrategy]
  );

  service.registerFieldConfigs(defaultMaskingConfigs);

  return service;
};

export const createDataMaskingModuleWithDefaults = (encryptionKey: string) => {
  const service = createDataMaskingModule(encryptionKey);
  return {
    service,
    permissionChecker: createPermissionChecker(),
    fieldConfigRepository: createFieldConfigRepository(),
    encryptionProvider: createDataMaskingEncryptionProvider(encryptionKey),
    strategies: {
      full: createFullMaskingStrategy(),
      partial: createPartialMaskingStrategy(),
      hash: createHashMaskingStrategy(),
      encrypt: createEncryptMaskingStrategy(createDataMaskingEncryptionProvider(encryptionKey)),
      remove: createRemoveMaskingStrategy()
    }
  };
};
