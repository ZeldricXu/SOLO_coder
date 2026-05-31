export * from './core/types';

export * from './di/types';
export { Container, createContainer } from './di/container';

export * from './data-masking';
export * from './audit-log';
export * from './tee';

export interface SecurityModulesConfig {
  dataMaskingEncryptionKey: string;
  teeMasterKey: string;
  auditLogDifficulty?: number;
}

export class RefactoredSecuritySuite {
  public readonly dataMasking: ReturnType<typeof import('./data-masking').createDataMaskingModule>;
  public readonly auditLog: ReturnType<typeof import('./audit-log').createAuditLogModule>;
  public readonly tee: ReturnType<typeof import('./tee').createTEEModule>;

  constructor(config: SecurityModulesConfig) {
    const { createDataMaskingModule } = require('./data-masking');
    const { createAuditLogModule } = require('./audit-log');
    const { createTEEModule } = require('./tee');

    this.dataMasking = createDataMaskingModule(config.dataMaskingEncryptionKey);
    this.auditLog = createAuditLogModule();
    this.tee = createTEEModule(config.teeMasterKey);

    if (config.auditLogDifficulty) {
      this.auditLog.setDifficulty(config.auditLogDifficulty);
    }
  }

  public getStats(): Record<string, unknown> {
    return {
      dataMasking: {
        registeredFields: this.dataMasking['fieldConfigRepository']?.getAll().length || 0
      },
      auditLog: this.auditLog.getChainStats(),
      tee: {
        enclaves: this.tee.getAllEnclaves().length
      }
    };
  }
}

export const createRefactoredSecuritySuite = (config: SecurityModulesConfig): RefactoredSecuritySuite => {
  return new RefactoredSecuritySuite(config);
};
