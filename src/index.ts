export * from './types';

import {
  DataMaskingModule,
  createDataMasking,
  defaultMaskingConfigs
} from './dataMasking';

import {
  AuditLogModule,
  createAuditLog,
  LogCreationParams,
  VerificationResult,
  AuditLogConfig
} from './auditLog';

import {
  TEEModule,
  createTEE,
  EnclaveStatus,
  SecureData
} from './tee';

import {
  MPCModule,
  createMPC,
  EncryptedInput,
  MPCResult,
  GarbledGate
} from './mpc';

import {
  FederatedLearningModule,
  createFederatedLearning,
  ClientGradient,
  GlobalModelUpdate,
  TrainingMetrics
} from './federatedLearning';

import {
  KeyShardingModule,
  createKeySharding,
  ShardVerificationResult,
  RecoveryResult
} from './keySharding';

import {
  DataClassificationModule,
  createDataClassification,
  DataSource,
  ClassificationReport,
  PolicyAction
} from './dataClassification';

import {
  DifferentialPrivacyModule,
  createDifferentialPrivacy,
  QueryContext,
  BudgetConsumption,
  PrivacyBudgetAccount
} from './differentialPrivacy';

export {
  DataMaskingModule,
  createDataMasking,
  defaultMaskingConfigs,
  AuditLogModule,
  createAuditLog,
  LogCreationParams,
  VerificationResult,
  AuditLogConfig,
  TEEModule,
  createTEE,
  EnclaveStatus,
  SecureData,
  MPCModule,
  createMPC,
  EncryptedInput,
  MPCResult,
  GarbledGate,
  FederatedLearningModule,
  createFederatedLearning,
  ClientGradient,
  GlobalModelUpdate,
  TrainingMetrics,
  KeyShardingModule,
  createKeySharding,
  ShardVerificationResult,
  RecoveryResult,
  DataClassificationModule,
  createDataClassification,
  DataSource,
  ClassificationReport,
  PolicyAction,
  DifferentialPrivacyModule,
  createDifferentialPrivacy,
  QueryContext,
  BudgetConsumption,
  PrivacyBudgetAccount
};

export interface SecurityModulesConfig {
  encryptionKey: string;
  teeMasterKey: string;
  defaultEpsilon?: number;
  defaultDelta?: number;
  auditLogMaxEntries?: number;
}

export class SecuritySuite {
  public dataMasking: DataMaskingModule;
  public auditLog: AuditLogModule;
  public tee: TEEModule;
  public mpc: MPCModule;
  public federatedLearning: FederatedLearningModule;
  public keySharding: KeyShardingModule;
  public dataClassification: DataClassificationModule;
  public differentialPrivacy: DifferentialPrivacyModule;

  constructor(config: SecurityModulesConfig) {
    this.dataMasking = new DataMaskingModule(config.encryptionKey);
    this.auditLog = new AuditLogModule({ maxEntries: config.auditLogMaxEntries });
    this.tee = new TEEModule(config.teeMasterKey);
    this.mpc = new MPCModule();
    this.federatedLearning = new FederatedLearningModule();
    this.keySharding = new KeyShardingModule();
    this.dataClassification = new DataClassificationModule();
    this.differentialPrivacy = new DifferentialPrivacyModule(
      config.defaultEpsilon,
      config.defaultDelta
    );
  }

  public getStats(): Record<string, unknown> {
    return {
      dataMasking: {
        registeredFields: this.dataMasking['fieldConfigs'].size
      },
      auditLog: this.auditLog.getChainStats(),
      tee: {
        enclaves: this.tee.getAllEnclaves().length
      },
      mpc: this.mpc.getTaskStats(),
      federatedLearning: this.federatedLearning.getTaskStats(),
      keySharding: this.keySharding.getStats(),
      dataClassification: this.dataClassification.getStats(),
      differentialPrivacy: this.differentialPrivacy.getStats()
    };
  }
}

export const createSecuritySuite = (config: SecurityModulesConfig): SecuritySuite => {
  return new SecuritySuite(config);
};
