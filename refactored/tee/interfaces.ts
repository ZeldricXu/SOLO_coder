import { EnclaveConfig, RemoteAttestationReport, EnclaveStatus, SecureData, EnclaveStatusType } from '../core/types';

export interface IEnclaveManager {
  createEnclave(name: string, attributes?: string[]): EnclaveConfig;
  initializeEnclave(enclaveId: string): boolean;
  suspendEnclave(enclaveId: string): boolean;
  resumeEnclave(enclaveId: string): boolean;
  terminateEnclave(enclaveId: string): boolean;
  getEnclaveConfig(enclaveId: string): EnclaveConfig | undefined;
  getEnclaveStatus(enclaveId: string): EnclaveStatus | undefined;
  getAllEnclaves(): EnclaveConfig[];
  updateEnclaveSvn(enclaveId: string): boolean;
  verifyEnclaveIdentity(enclaveId: string, expectedMrenclave?: string, expectedMrsigner?: string): boolean;
}

export interface ITEECryptoProvider {
  encryptInEnclave(enclaveId: string, plaintext: string): SecureData | null;
  decryptInEnclave(enclaveId: string, secureData: SecureData): string | null;
  sign(data: string): string;
  verifySignature(data: string, signature: string): boolean;
}

export interface IKeyDerivationService {
  deriveEnclaveKey(enclaveId: string): Buffer;
  getEnclaveKey(enclaveId: string): Buffer | null;
  removeEnclaveKey(enclaveId: string): boolean;
}

export interface IAttestationService {
  generateAttestationReport(enclaveId: string, challenge: string): RemoteAttestationReport | null;
  verifyAttestationReport(report: RemoteAttestationReport): boolean;
  getAttestationReport(enclaveId: string): RemoteAttestationReport | undefined;
  generateCertificateChain(enclaveId: string): string;
}

export interface ITEEService {
  createEnclave(name: string, attributes?: string[]): EnclaveConfig;
  initializeEnclave(enclaveId: string): boolean;
  suspendEnclave(enclaveId: string): boolean;
  resumeEnclave(enclaveId: string): boolean;
  terminateEnclave(enclaveId: string): boolean;
  getEnclaveConfig(enclaveId: string): EnclaveConfig | undefined;
  getEnclaveStatus(enclaveId: string): EnclaveStatus | undefined;
  getAllEnclaves(): EnclaveConfig[];
  encryptInEnclave(enclaveId: string, plaintext: string): SecureData | null;
  decryptInEnclave(enclaveId: string, secureData: SecureData): string | null;
  generateAttestationReport(enclaveId: string, challenge: string): RemoteAttestationReport | null;
  verifyAttestationReport(report: RemoteAttestationReport): boolean;
  verifyEnclaveIdentity(enclaveId: string, expectedMrenclave?: string, expectedMrsigner?: string): boolean;
  updateEnclaveSvn(enclaveId: string): boolean;
  getAttestationReport(enclaveId: string): RemoteAttestationReport | undefined;
  executeSecureComputation(enclaveId: string, computation: (data: unknown) => unknown, inputData: unknown): unknown | null;
}
