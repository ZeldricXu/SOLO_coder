import * as crypto from 'crypto';
import { IAttestationService } from './interfaces';
import { IEnclaveManager } from './interfaces';
import { ITEECryptoProvider } from './interfaces';
import { RemoteAttestationReport } from '../core/types';

export class AttestationService implements IAttestationService {
  private readonly attestationReports: Map<string, RemoteAttestationReport> = new Map();

  constructor(
    private readonly enclaveManager: IEnclaveManager,
    private readonly cryptoProvider: ITEECryptoProvider
  ) {}

  public generateAttestationReport(enclaveId: string, challenge: string): RemoteAttestationReport | null {
    const config = this.enclaveManager.getEnclaveConfig(enclaveId);
    const status = this.enclaveManager.getEnclaveStatus(enclaveId);
    
    if (!config || !status || status.status !== 'running') return null;

    const quoteData = JSON.stringify({
      enclaveId,
      mrenclave: config.mrenclave,
      mrsigner: config.mrsigner,
      isvProdId: config.isvProdId,
      isvSvn: config.isvSvn,
      challenge,
      timestamp: Date.now()
    });

    const quote = crypto.createHash('sha256').update(quoteData).digest('hex');
    const signature = this.cryptoProvider.sign(quote);
    const certificateChain = this.generateCertificateChain(enclaveId);

    const report: RemoteAttestationReport = {
      enclaveId,
      timestamp: Date.now(),
      quote,
      signature,
      certificateChain,
      isVerified: false
    };

    report.isVerified = this.verifyAttestationReport(report);
    this.attestationReports.set(enclaveId, report);

    return report;
  }

  public verifyAttestationReport(report: RemoteAttestationReport): boolean {
    try {
      return this.cryptoProvider.verifySignature(report.quote, report.signature);
    } catch {
      return false;
    }
  }

  public getAttestationReport(enclaveId: string): RemoteAttestationReport | undefined {
    return this.attestationReports.get(enclaveId);
  }

  public generateCertificateChain(enclaveId: string): string {
    const certs = [
      { subject: 'Root CA', issuer: 'Root CA', validFrom: Date.now() - 86400000, validTo: Date.now() + 31536000000 },
      { subject: 'Intermediate CA', issuer: 'Root CA', validFrom: Date.now() - 86400000, validTo: Date.now() + 31536000000 },
      { subject: `Enclave ${enclaveId}`, issuer: 'Intermediate CA', validFrom: Date.now() - 86400000, validTo: Date.now() + 86400000 }
    ];
    return JSON.stringify(certs);
  }
}

export const createAttestationService = (
  enclaveManager: IEnclaveManager,
  cryptoProvider: ITEECryptoProvider
): AttestationService => {
  return new AttestationService(enclaveManager, cryptoProvider);
};
