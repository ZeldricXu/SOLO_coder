export * from './interfaces';
export * from '../core/types';

export { HashProvider, createHashProvider } from './hash-provider.service';
export { PowMiner, createPowMiner } from './pow-miner.service';
export { LogRepository, createLogRepository } from './log-repository.service';
export { ChainVerifier, createChainVerifier } from './chain-verifier.service';
export { AuditLogService, createAuditLogService } from './audit-log.service';

import { HashProvider, createHashProvider } from './hash-provider.service';
import { PowMiner, createPowMiner } from './pow-miner.service';
import { LogRepository, createLogRepository } from './log-repository.service';
import { ChainVerifier, createChainVerifier } from './chain-verifier.service';
import { AuditLogService, createAuditLogService } from './audit-log.service';

export const createAuditLogModule = (): AuditLogService => {
  const hashProvider = createHashProvider();
  const powMiner = createPowMiner(hashProvider);
  const logRepository = createLogRepository();
  const chainVerifier = createChainVerifier(hashProvider);

  return createAuditLogService(
    hashProvider,
    powMiner,
    logRepository,
    chainVerifier
  );
};

export const createAuditLogModuleWithDefaults = () => {
  const hashProvider = createHashProvider();
  const powMiner = createPowMiner(hashProvider);
  const logRepository = createLogRepository();
  const chainVerifier = createChainVerifier(hashProvider);
  const service = createAuditLogService(
    hashProvider,
    powMiner,
    logRepository,
    chainVerifier
  );

  return {
    service,
    hashProvider,
    powMiner,
    logRepository,
    chainVerifier
  };
};
