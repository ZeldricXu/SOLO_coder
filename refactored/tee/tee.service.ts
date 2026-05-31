import { ITEEService } from './interfaces';
import { IEnclaveManager, ITEECryptoProvider, IAttestationService, IKeyDerivationService } from './interfaces';
import { EnclaveConfig, EnclaveStatus, SecureData, RemoteAttestationReport } from '../core/types';

export class TEEService implements ITEEService {
  constructor(
    private readonly enclaveManager: IEnclaveManager,
    private readonly cryptoProvider: ITEECryptoProvider,
    private readonly attestationService: IAttestationService,
    private readonly keyDerivationService: IKeyDerivationService
  ) {}

  public createEnclave(name: string, attributes: string[] = []): EnclaveConfig {
    const config = this.enclaveManager.createEnclave(name, attributes);
    this.keyDerivationService.deriveEnclaveKey(config.enclaveId);
    return config;
  }

  public initializeEnclave(enclaveId: string): boolean {
    return this.enclaveManager.initializeEnclave(enclaveId);
  }

  public suspendEnclave(enclaveId: string): boolean {
    return this.enclaveManager.suspendEnclave(enclaveId);
  }

  public resumeEnclave(enclaveId: string): boolean {
    return this.enclaveManager.resumeEnclave(enclaveId);
  }

  public terminateEnclave(enclaveId: string): boolean {
    const terminated = this.enclaveManager.terminateEnclave(enclaveId);
    if (terminated) {
      this.keyDerivationService.removeEnclaveKey(enclaveId);
    }
    return terminated;
  }

  public getEnclaveConfig(enclaveId: string): EnclaveConfig | undefined {
    return this.enclaveManager.getEnclaveConfig(enclaveId);
  }

  public getEnclaveStatus(enclaveId: string): EnclaveStatus | undefined {
    return this.enclaveManager.getEnclaveStatus(enclaveId);
  }

  public getAllEnclaves(): EnclaveConfig[] {
    return this.enclaveManager.getAllEnclaves();
  }

  public encryptInEnclave(enclaveId: string, plaintext: string): SecureData | null {
    return this.cryptoProvider.encryptInEnclave(enclaveId, plaintext);
  }

  public decryptInEnclave(enclaveId: string, secureData: SecureData): string | null {
    return this.cryptoProvider.decryptInEnclave(enclaveId, secureData);
  }

  public generateAttestationReport(enclaveId: string, challenge: string): RemoteAttestationReport | null {
    return this.attestationService.generateAttestationReport(enclaveId, challenge);
  }

  public verifyAttestationReport(report: RemoteAttestationReport): boolean {
    return this.attestationService.verifyAttestationReport(report);
  }

  public verifyEnclaveIdentity(enclaveId: string, expectedMrenclave?: string, expectedMrsigner?: string): boolean {
    return this.enclaveManager.verifyEnclaveIdentity(enclaveId, expectedMrenclave, expectedMrsigner);
  }

  public updateEnclaveSvn(enclaveId: string): boolean {
    return this.enclaveManager.updateEnclaveSvn(enclaveId);
  }

  public getAttestationReport(enclaveId: string): RemoteAttestationReport | undefined {
    return this.attestationService.getAttestationReport(enclaveId);
  }

  public executeSecureComputation(enclaveId: string, computation: (data: unknown) => unknown, inputData: unknown): unknown | null {
    const status = this.enclaveManager.getEnclaveStatus(enclaveId);
    if (!status || status.status !== 'running') return null;

    try {
      (this.enclaveManager as any).updateEnclaveResources(
        enclaveId,
        Math.random() * 20,
        Math.random() * 50
      );

      return computation(inputData);
    } catch {
      return null;
    }
  }
}

export const createTEEService = (
  enclaveManager: IEnclaveManager,
  cryptoProvider: ITEECryptoProvider,
  attestationService: IAttestationService,
  keyDerivationService: IKeyDerivationService
): TEEService => {
  return new TEEService(
    enclaveManager,
    cryptoProvider,
    attestationService,
    keyDerivationService
  );
};
