export * from './interfaces';
export * from '../core/types';

export { KeyDerivationService, createKeyDerivationService } from './key-derivation.service';
export { EnclaveManager, createEnclaveManager } from './enclave-manager.service';
export { TEECryptoProvider, createTEECryptoProvider } from './crypto-provider.service';
export { AttestationService, createAttestationService } from './attestation.service';
export { TEEService, createTEEService } from './tee.service';

import { KeyDerivationService, createKeyDerivationService } from './key-derivation.service';
import { EnclaveManager, createEnclaveManager } from './enclave-manager.service';
import { TEECryptoProvider, createTEECryptoProvider } from './crypto-provider.service';
import { AttestationService, createAttestationService } from './attestation.service';
import { TEEService, createTEEService } from './tee.service';

export const createTEEModule = (masterKey: string): TEEService => {
  const keyDerivationService = createKeyDerivationService(masterKey);
  const enclaveManager = createEnclaveManager();
  const cryptoProvider = createTEECryptoProvider(keyDerivationService, enclaveManager);
  const attestationService = createAttestationService(enclaveManager, cryptoProvider);

  return createTEEService(
    enclaveManager,
    cryptoProvider,
    attestationService,
    keyDerivationService
  );
};

export const createTEEModuleWithDefaults = (masterKey: string) => {
  const keyDerivationService = createKeyDerivationService(masterKey);
  const enclaveManager = createEnclaveManager();
  const cryptoProvider = createTEECryptoProvider(keyDerivationService, enclaveManager);
  const attestationService = createAttestationService(enclaveManager, cryptoProvider);
  const service = createTEEService(
    enclaveManager,
    cryptoProvider,
    attestationService,
    keyDerivationService
  );

  return {
    service,
    keyDerivationService,
    enclaveManager,
    cryptoProvider,
    attestationService
  };
};
