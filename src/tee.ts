import * as crypto from 'crypto';
import { EnclaveConfig, RemoteAttestationReport } from './types';

export interface EnclaveStatus {
  enclaveId: string;
  status: 'uninitialized' | 'initialized' | 'running' | 'suspended' | 'terminated';
  memoryUsage: number;
  cpuUsage: number;
  uptime: number;
  isHealthy: boolean;
}

export interface SecureData {
  encryptedData: string;
  iv: string;
  tag: string;
  enclaveId: string;
  timestamp: number;
}

export class TEEModule {
  private enclaves: Map<string, EnclaveConfig> = new Map();
  private enclaveStatuses: Map<string, EnclaveStatus> = new Map();
  private enclaveKeys: Map<string, Buffer> = new Map();
  private attestationReports: Map<string, RemoteAttestationReport> = new Map();
  private masterKey: Buffer;

  constructor(masterKey: string) {
    this.masterKey = crypto.scryptSync(masterKey, 'tee-salt', 32);
  }

  public createEnclave(name: string, attributes: string[] = []): EnclaveConfig {
    const enclaveId = crypto.randomUUID();
    const mrenclave = this.generateMeasurement(enclaveId, name);
    const mrsigner = this.generateSignerMeasurement();

    const config: EnclaveConfig = {
      enclaveId,
      name,
      mrenclave,
      mrsigner,
      isvProdId: Math.floor(Math.random() * 65535),
      isvSvn: 1,
      attributes
    };

    const status: EnclaveStatus = {
      enclaveId,
      status: 'initialized',
      memoryUsage: 0,
      cpuUsage: 0,
      uptime: 0,
      isHealthy: true
    };

    const enclaveKey = this.deriveEnclaveKey(enclaveId);

    this.enclaves.set(enclaveId, config);
    this.enclaveStatuses.set(enclaveId, status);
    this.enclaveKeys.set(enclaveId, enclaveKey);

    return config;
  }

  public initializeEnclave(enclaveId: string): boolean {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status || status.status !== 'initialized') return false;

    status.status = 'running';
    status.uptime = Date.now();
    this.enclaveStatuses.set(enclaveId, status);
    return true;
  }

  public suspendEnclave(enclaveId: string): boolean {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status || status.status !== 'running') return false;

    status.status = 'suspended';
    this.enclaveStatuses.set(enclaveId, status);
    return true;
  }

  public resumeEnclave(enclaveId: string): boolean {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status || status.status !== 'suspended') return false;

    status.status = 'running';
    this.enclaveStatuses.set(enclaveId, status);
    return true;
  }

  public terminateEnclave(enclaveId: string): boolean {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status) return false;

    status.status = 'terminated';
    status.isHealthy = false;
    this.enclaveStatuses.set(enclaveId, status);
    this.enclaveKeys.delete(enclaveId);
    return true;
  }

  public getEnclaveConfig(enclaveId: string): EnclaveConfig | undefined {
    return this.enclaves.get(enclaveId);
  }

  public getEnclaveStatus(enclaveId: string): EnclaveStatus | undefined {
    return this.enclaveStatuses.get(enclaveId);
  }

  public getAllEnclaves(): EnclaveConfig[] {
    return Array.from(this.enclaves.values());
  }

  public encryptInEnclave(enclaveId: string, plaintext: string): SecureData | null {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status || status.status !== 'running') return null;

    const key = this.enclaveKeys.get(enclaveId);
    if (!key) return null;

    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    
    let encrypted = cipher.update(plaintext, 'utf8', 'hex');
    encrypted += cipher.final('hex');
    
    const tag = cipher.getAuthTag().toString('hex');

    return {
      encryptedData: encrypted,
      iv: iv.toString('hex'),
      tag,
      enclaveId,
      timestamp: Date.now()
    };
  }

  public decryptInEnclave(enclaveId: string, secureData: SecureData): string | null {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status || status.status !== 'running') return null;
    if (secureData.enclaveId !== enclaveId) return null;

    const key = this.enclaveKeys.get(enclaveId);
    if (!key) return null;

    try {
      const iv = Buffer.from(secureData.iv, 'hex');
      const encrypted = Buffer.from(secureData.encryptedData, 'hex');
      const tag = Buffer.from(secureData.tag, 'hex');

      const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
      decipher.setAuthTag(tag);

      let decrypted = decipher.update(encrypted.toString('hex'), 'hex', 'utf8');
      decrypted += decipher.final('utf8');

      return decrypted;
    } catch {
      return null;
    }
  }

  public generateAttestationReport(enclaveId: string, challenge: string): RemoteAttestationReport | null {
    const config = this.enclaves.get(enclaveId);
    const status = this.enclaveStatuses.get(enclaveId);
    
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
    const signature = this.signWithMasterKey(quote);
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
      const expectedSignature = crypto
        .createHmac('sha256', this.masterKey)
        .update(report.quote)
        .digest('hex');
      
      return crypto.timingSafeEqual(
        Buffer.from(report.signature, 'hex'),
        Buffer.from(expectedSignature, 'hex')
      );
    } catch {
      return false;
    }
  }

  public verifyEnclaveIdentity(enclaveId: string, expectedMrenclave?: string, expectedMrsigner?: string): boolean {
    const config = this.enclaves.get(enclaveId);
    if (!config) return false;

    if (expectedMrenclave && config.mrenclave !== expectedMrenclave) return false;
    if (expectedMrsigner && config.mrsigner !== expectedMrsigner) return false;

    return true;
  }

  public updateEnclaveSvn(enclaveId: string): boolean {
    const config = this.enclaves.get(enclaveId);
    if (!config) return false;

    config.isvSvn++;
    this.enclaves.set(enclaveId, config);
    return true;
  }

  public getAttestationReport(enclaveId: string): RemoteAttestationReport | undefined {
    return this.attestationReports.get(enclaveId);
  }

  private generateMeasurement(enclaveId: string, name: string): string {
    return crypto
      .createHash('sha256')
      .update(enclaveId + name + Date.now())
      .digest('hex');
  }

  private generateSignerMeasurement(): string {
    return crypto
      .createHash('sha256')
      .update('signer-key' + this.masterKey.toString('hex'))
      .digest('hex');
  }

  private deriveEnclaveKey(enclaveId: string): Buffer {
    return crypto
      .createHmac('sha256', this.masterKey)
      .update(enclaveId)
      .digest();
  }

  private signWithMasterKey(data: string): string {
    return crypto
      .createHmac('sha256', this.masterKey)
      .update(data)
      .digest('hex');
  }

  private generateCertificateChain(enclaveId: string): string {
    const certs = [
      { subject: 'Root CA', issuer: 'Root CA', validFrom: Date.now() - 86400000, validTo: Date.now() + 31536000000 },
      { subject: 'Intermediate CA', issuer: 'Root CA', validFrom: Date.now() - 86400000, validTo: Date.now() + 31536000000 },
      { subject: `Enclave ${enclaveId}`, issuer: 'Intermediate CA', validFrom: Date.now() - 86400000, validTo: Date.now() + 86400000 }
    ];
    return JSON.stringify(certs);
  }

  private extractPublicKeyFromChain(certificateChain: string): string {
    const certs = JSON.parse(certificateChain);
    const leafCert = certs[certs.length - 1];
    return crypto.createHash('sha256').update(JSON.stringify(leafCert)).digest('hex');
  }

  public executeSecureComputation(enclaveId: string, computation: (data: unknown) => unknown, inputData: unknown): unknown | null {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status || status.status !== 'running') return null;

    try {
      status.cpuUsage = Math.min(100, status.cpuUsage + Math.random() * 20);
      status.memoryUsage = Math.min(1000, status.memoryUsage + Math.random() * 50);
      this.enclaveStatuses.set(enclaveId, status);

      return computation(inputData);
    } catch {
      return null;
    }
  }
}

export const createTEE = (masterKey: string): TEEModule => {
  return new TEEModule(masterKey);
};
