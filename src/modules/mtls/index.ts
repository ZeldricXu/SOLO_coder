import * as crypto from 'crypto';
import * as fs from 'fs-extra';
import * as path from 'path';
import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { generateId, currentTimestamp } from '../../utils/helpers';

export interface Certificate {
  id: string;
  commonName: string;
  subjectAltNames: string[];
  pem: string;
  privateKey: string;
  issuer: string;
  serialNumber: string;
  notBefore: string;
  notAfter: string;
  status: 'active' | 'expired' | 'revoked';
  createdAt: string;
  rotatedAt?: string;
}

export interface RotationPolicy {
  id: string;
  name: string;
  enabled: boolean;
  commonNamePattern: string;
  daysBeforeExpiry: number;
  autoRotate: boolean;
  notifyBeforeDays: number;
  lastRun?: string;
}

export interface RevocationEntry {
  serialNumber: string;
  commonName: string;
  reason: string;
  revokedAt: string;
  revokedBy: string;
}

export interface CRL {
  id: string;
  issuer: string;
  entries: RevocationEntry[];
  lastUpdated: string;
  nextUpdate: string;
  pem: string;
}

export interface CertificateConfig {
  certDir: string;
  caCert: string;
  caKey: string;
  defaultValidityDays: number;
  keyBits: number;
  signatureAlgorithm: string;
}

export class MTLSCertificateManager {
  private config: CertificateConfig;
  private certificates: Map<string, Certificate> = new Map();
  private rotationPolicies: Map<string, RotationPolicy> = new Map();
  private crls: Map<string, CRL> = new Map();
  private rotationTimer?: NodeJS.Timeout;

  constructor(config?: Partial<CertificateConfig>) {
    this.config = {
      certDir: process.env.MTLS_CERT_DIR || './certs',
      caCert: '',
      caKey: '',
      defaultValidityDays: 365,
      keyBits: 2048,
      signatureAlgorithm: 'sha256',
      ...config,
    };
    this.initializeCA();
    this.startRotationScheduler();
    logger.info('MTLSCertificateManager initialized', { certDir: this.config.certDir });
  }

  private async initializeCA(): Promise<void> {
    await fs.ensureDir(this.config.certDir);
    
    const caCertPath = path.join(this.config.certDir, 'ca.crt');
    const caKeyPath = path.join(this.config.certDir, 'ca.key');

    if (await fs.pathExists(caCertPath) && await fs.pathExists(caKeyPath)) {
      this.config.caCert = await fs.readFile(caCertPath, 'utf-8');
      this.config.caKey = await fs.readFile(caKeyPath, 'utf-8');
      logger.info('CA certificate loaded from disk');
    } else {
      await this.generateCA();
      await fs.writeFile(caCertPath, this.config.caCert);
      await fs.writeFile(caKeyPath, this.config.caKey);
      logger.info('New CA certificate generated and saved');
    }
  }

  private async generateCA(): Promise<void> {
    const { key, cert } = await this.generateSelfSignedCertificate({
      commonName: 'Root CA',
      organization: 'Data Transform Core',
      country: 'CN',
      validityDays: 3650,
      isCA: true,
    });
    this.config.caCert = cert;
    this.config.caKey = key;
  }

  async issueCertificate(
    commonName: string,
    subjectAltNames: string[] = [],
    validityDays?: number,
  ): Promise<Certificate> {
    const id = generateId('cert_');
    const days = validityDays || this.config.defaultValidityDays;

    const { key, cert, serialNumber, notBefore, notAfter } = await this.generateSignedCertificate({
      commonName,
      subjectAltNames,
      validityDays: days,
    });

    const certificate: Certificate = {
      id,
      commonName,
      subjectAltNames,
      pem: cert,
      privateKey: key,
      issuer: 'Root CA',
      serialNumber,
      notBefore,
      notAfter,
      status: 'active',
      createdAt: currentTimestamp(),
    };

    this.certificates.set(id, certificate);
    
    const certPath = path.join(this.config.certDir, `${commonName}.crt`);
    const keyPath = path.join(this.config.certDir, `${commonName}.key`);
    await fs.writeFile(certPath, cert);
    await fs.writeFile(keyPath, key);

    logger.info('Certificate issued', { id, commonName, notAfter });
    eventBus.emit('certificate.issued', certificate);

    return certificate;
  }

  private async generateSelfSignedCertificate(options: {
    commonName: string;
    organization?: string;
    country?: string;
    validityDays: number;
    isCA?: boolean;
  }): Promise<{ key: string; cert: string }> {
    return new Promise((resolve, reject) => {
      const keyPair = crypto.generateKeyPairSync('rsa', {
        modulusLength: this.config.keyBits,
        publicKeyEncoding: { type: 'spki', format: 'pem' },
        privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
      });

      const notBefore = new Date();
      const notAfter = new Date();
      notAfter.setDate(notAfter.getDate() + options.validityDays);

      const csr = crypto.createSign('sha256');
      const subject = this.buildSubject({
        commonName: options.commonName,
        organization: options.organization,
        country: options.country,
      });
      
      csr.update(subject);
      const signature = csr.sign(keyPair.privateKey, 'base64');

      const cert = this.buildCertificate({
        subject,
        issuer: subject,
        publicKey: keyPair.publicKey,
        notBefore,
        notAfter,
        isCA: options.isCA,
        privateKey: keyPair.privateKey,
      });

      resolve({ key: keyPair.privateKey, cert });
    });
  }

  private async generateSignedCertificate(options: {
    commonName: string;
    subjectAltNames: string[];
    validityDays: number;
  }): Promise<{ key: string; cert: string; serialNumber: string; notBefore: string; notAfter: string }> {
    return new Promise((resolve, reject) => {
      const keyPair = crypto.generateKeyPairSync('rsa', {
        modulusLength: this.config.keyBits,
        publicKeyEncoding: { type: 'spki', format: 'pem' },
        privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
      });

      const notBefore = new Date();
      const notAfter = new Date();
      notAfter.setDate(notAfter.getDate() + options.validityDays);
      const serialNumber = crypto.randomBytes(16).toString('hex');

      const subject = this.buildSubject({ commonName: options.commonName });
      const issuer = this.buildSubject({ commonName: 'Root CA' });

      const cert = this.buildCertificate({
        subject,
        issuer,
        publicKey: keyPair.publicKey,
        notBefore,
        notAfter,
        serialNumber,
        subjectAltNames: options.subjectAltNames,
        privateKey: this.config.caKey,
      });

      resolve({
        key: keyPair.privateKey,
        cert,
        serialNumber,
        notBefore: notBefore.toISOString(),
        notAfter: notAfter.toISOString(),
      });
    });
  }

  private buildSubject(options: {
    commonName: string;
    organization?: string;
    country?: string;
  }): string {
    const parts: string[] = [];
    if (options.country) parts.push(`C=${options.country}`);
    if (options.organization) parts.push(`O=${options.organization}`);
    parts.push(`CN=${options.commonName}`);
    return parts.join(', ');
  }

  private buildCertificate(options: {
    subject: string;
    issuer: string;
    publicKey: string;
    notBefore: Date;
    notAfter: Date;
    isCA?: boolean;
    serialNumber?: string;
    subjectAltNames?: string[];
    privateKey: string;
  }): string {
    const serial = options.serialNumber || crypto.randomBytes(16).toString('hex');
    
    const cert = [
      '-----BEGIN CERTIFICATE-----',
      Buffer.from(JSON.stringify({
        version: 3,
        serialNumber: serial,
        signature: { algorithm: 'sha256WithRSAEncryption' },
        issuer: options.issuer,
        validity: {
          notBefore: options.notBefore.toISOString(),
          notAfter: options.notAfter.toISOString(),
        },
        subject: options.subject,
        subjectPublicKeyInfo: options.publicKey,
        extensions: {
          basicConstraints: { CA: options.isCA || false },
          subjectAltName: options.subjectAltNames?.map(dns => `DNS:${dns}`).join(', ') || '',
        },
      })).toString('base64'),
      '-----END CERTIFICATE-----',
    ].join('\n');

    return cert;
  }

  async rotateCertificate(certId: string): Promise<Certificate | undefined> {
    const cert = this.certificates.get(certId);
    if (!cert) return undefined;

    const newCert = await this.issueCertificate(
      cert.commonName,
      cert.subjectAltNames,
    );

    cert.status = 'expired';
    cert.rotatedAt = currentTimestamp();
    this.certificates.set(certId, cert);

    logger.info('Certificate rotated', { oldId: certId, newId: newCert.id });
    eventBus.emit('certificate.rotated', { oldCert: cert, newCert });

    return newCert;
  }

  createRotationPolicy(policy: Omit<RotationPolicy, 'id'>): RotationPolicy {
    const id = generateId('pol_');
    const rotationPolicy: RotationPolicy = {
      ...policy,
      id,
    };
    this.rotationPolicies.set(id, rotationPolicy);
    logger.info('Rotation policy created', { id, name: policy.name });
    return rotationPolicy;
  }

  private startRotationScheduler(): void {
    this.rotationTimer = setInterval(() => {
      this.checkAndRotateCertificates().catch(error => {
        logger.error('Certificate rotation check failed', { error });
      });
    }, 24 * 60 * 60 * 1000);
  }

  private async checkAndRotateCertificates(): Promise<void> {
    const now = new Date();
    
    for (const policy of this.rotationPolicies.values()) {
      if (!policy.enabled || !policy.autoRotate) continue;

      const matchingCerts = Array.from(this.certificates.values()).filter(
        cert => cert.status === 'active' && 
                new RegExp(policy.commonNamePattern).test(cert.commonName),
      );

      for (const cert of matchingCerts) {
        const expiryDate = new Date(cert.notAfter);
        const daysToExpiry = Math.ceil(
          (expiryDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24),
        );

        if (daysToExpiry <= policy.daysBeforeExpiry) {
          logger.info('Auto-rotating certificate', {
            commonName: cert.commonName,
            daysToExpiry,
          });
          await this.rotateCertificate(cert.id);
        } else if (daysToExpiry <= policy.notifyBeforeDays) {
          eventBus.emit('certificate.expiring_soon', {
            cert,
            daysToExpiry,
            policy,
          });
        }
      }

      policy.lastRun = currentTimestamp();
    }
  }

  async revokeCertificate(certId: string, reason: string, revokedBy: string): Promise<boolean> {
    const cert = this.certificates.get(certId);
    if (!cert) return false;

    cert.status = 'revoked';
    this.certificates.set(certId, cert);

    const entry: RevocationEntry = {
      serialNumber: cert.serialNumber,
      commonName: cert.commonName,
      reason,
      revokedAt: currentTimestamp(),
      revokedBy,
    };

    let crl = this.crls.get('default');
    if (!crl) {
      crl = {
        id: generateId('crl_'),
        issuer: 'Root CA',
        entries: [],
        lastUpdated: currentTimestamp(),
        nextUpdate: this.getNextUpdateDate(),
        pem: '',
      };
    }
    crl.entries.push(entry);
    crl.lastUpdated = currentTimestamp();
    crl.nextUpdate = this.getNextUpdateDate();
    crl.pem = this.generateCRLPEM(crl);
    this.crls.set('default', crl);

    logger.info('Certificate revoked', { certId, reason, revokedBy });
    eventBus.emit('certificate.revoked', { cert, reason, revokedBy });

    return true;
  }

  private getNextUpdateDate(): string {
    const next = new Date();
    next.setDate(next.getDate() + 7);
    return next.toISOString();
  }

  private generateCRLPEM(crl: CRL): string {
    return [
      '-----BEGIN X509 CRL-----',
      Buffer.from(JSON.stringify(crl)).toString('base64'),
      '-----END X509 CRL-----',
    ].join('\n');
  }

  isCertificateRevoked(serialNumber: string): boolean {
    for (const crl of this.crls.values()) {
      if (crl.entries.some(e => e.serialNumber === serialNumber)) {
        return true;
      }
    }
    return false;
  }

  getCertificate(id: string): Certificate | undefined {
    return this.certificates.get(id);
  }

  getCertificateBySerial(serialNumber: string): Certificate | undefined {
    return Array.from(this.certificates.values()).find(
      c => c.serialNumber === serialNumber,
    );
  }

  listCertificates(): Certificate[] {
    return Array.from(this.certificates.values());
  }

  listRotationPolicies(): RotationPolicy[] {
    return Array.from(this.rotationPolicies.values());
  }

  getCRL(): CRL | undefined {
    return this.crls.get('default');
  }

  validateCertificate(certPem: string): { valid: boolean; reason?: string } {
    try {
      const cert = this.parseCertificate(certPem);
      
      if (this.isCertificateRevoked(cert.serialNumber)) {
        return { valid: false, reason: 'Certificate revoked' };
      }

      const now = new Date();
      if (new Date(cert.notAfter) < now) {
        return { valid: false, reason: 'Certificate expired' };
      }
      if (new Date(cert.notBefore) > now) {
        return { valid: false, reason: 'Certificate not yet valid' };
      }

      return { valid: true };
    } catch (error) {
      return { valid: false, reason: 'Invalid certificate format' };
    }
  }

  private parseCertificate(pem: string): any {
    try {
      const base64 = pem.replace(/-----BEGIN CERTIFICATE-----|-----END CERTIFICATE-----|\n/g, '');
      const json = Buffer.from(base64, 'base64').toString('utf-8');
      return JSON.parse(json);
    } catch {
      return {
        serialNumber: 'unknown',
        notBefore: new Date().toISOString(),
        notAfter: new Date(Date.now() + 86400000).toISOString(),
      };
    }
  }

  getCACertificate(): string {
    return this.config.caCert;
  }

  stop(): void {
    if (this.rotationTimer) {
      clearInterval(this.rotationTimer);
    }
  }
}

export const mtlsManager = new MTLSCertificateManager();
