import { PrismaClient, Certificate as DbCertificate, CertificateRevocation as DbRevocation } from '@prisma/client';
import { generateCertId, generateRevocationId } from '../../utils/idGenerator';
import { NotFoundError, ValidationError } from '../../utils/errors';
import type { CreateCertificateRequest, Certificate, CertificateRevocation, RevokeCertificateRequest, RotationPolicyConfig, CSRResult, CreateCSRRequest } from './types';
import type { PaginationParams, PaginatedResult, CertificateStatus } from '../../types';
import logger from '../../utils/logger';
import forge from 'node-forge';

const prisma = new PrismaClient();

let rotationPolicy: RotationPolicyConfig = {
  enabled: true,
  autoRenewDays: 30,
  maxRetries: 3,
  notifyBeforeDays: [7, 3, 1],
};

const toCertificate = (db: DbCertificate): Certificate => ({
  certId: db.certId,
  commonName: db.commonName,
  sanNames: db.sanNames,
  certificate: db.certificate,
  privateKey: db.privateKey,
  issuer: db.issuer,
  serialNumber: db.serialNumber,
  notBefore: db.notBefore,
  notAfter: db.notAfter,
  rotationPolicy: db.rotationPolicy,
  status: db.status as CertificateStatus,
  createdAt: db.createdAt,
  updatedAt: db.updatedAt,
});

const toRevocation = (db: DbRevocation): CertificateRevocation => ({
  revocationId: db.revocationId,
  serialNumber: db.serialNumber,
  reason: db.reason,
  revokedAt: db.revokedAt,
  createdAt: db.createdAt,
});

const generateKeyPair = (algorithm: string): { publicKey: forge.pki.PublicKey; privateKey: forge.pki.PrivateKey } => {
  const [type, bits] = algorithm.split('-');
  if (type === 'RSA') {
    return forge.pki.rsa.generateKeyPair(parseInt(bits));
  }
  const curve = bits === 'P256' ? 'prime256v1' : 'secp384r1';
  return forge.pki.ed25519.generateKeyPair();
};

const determineCertStatus = (notAfter: Date): CertificateStatus => {
  const now = new Date();
  const daysUntilExpiry = (notAfter.getTime() - now.getTime()) / (1000 * 60 * 60 * 24);
  if (daysUntilExpiry < 0) return 'expired';
  if (daysUntilExpiry <= rotationPolicy.autoRenewDays) return 'expiring';
  return 'active';
};

export const createCertificate = async (data: CreateCertificateRequest): Promise<Certificate> => {
  const { publicKey, privateKey } = generateKeyPair(data.keyAlgorithm);

  const cert = forge.pki.createCertificate();
  cert.publicKey = publicKey;
  cert.serialNumber = generateSerialNumber();
  cert.validity.notBefore = new Date();
  cert.validity.notAfter = new Date(Date.now() + data.validityDays * 24 * 60 * 60 * 1000);

  const attrs: Array<{ name: string; value?: string; shortName?: string }> = [{ name: 'commonName', value: data.commonName }];
  if (data.organization) attrs.push({ name: 'organizationName', value: data.organization });
  if (data.organizationalUnit) attrs.push({ name: 'organizationalUnitName', value: data.organizationalUnit });
  if (data.country) attrs.push({ name: 'countryName', value: data.country });
  if (data.state) attrs.push({ name: 'stateOrProvinceName', value: data.state });
  if (data.locality) attrs.push({ name: 'localityName', value: data.locality });

  cert.setSubject(attrs);
  cert.setIssuer(attrs);

  if (data.sanNames.length > 0) {
    cert.setExtensions([{
      name: 'subjectAltName',
      altNames: data.sanNames.map(name => ({
        type: 2,
        value: name,
      })),
    }]);
  }

  cert.sign(privateKey, forge.md.sha256.create());

  const pemCert = forge.pki.certificateToPem(cert);
  const pemKey = forge.pki.privateKeyToPem(privateKey);

  const certificate = await prisma.certificate.create({
    data: {
      certId: generateCertId(),
      commonName: data.commonName,
      sanNames: data.sanNames,
      certificate: pemCert,
      privateKey: pemKey,
      issuer: data.commonName,
      serialNumber: cert.serialNumber,
      notBefore: cert.validity.notBefore,
      notAfter: cert.validity.notAfter,
      rotationPolicy: data.rotationPolicy,
      status: 'active',
    },
  });

  logger.info({ certId: certificate.certId, commonName: data.commonName }, 'Certificate created');
  return toCertificate(certificate);
};

export const createCSR = async (data: CreateCSRRequest): Promise<CSRResult> => {
  const { publicKey, privateKey } = generateKeyPair(data.keyAlgorithm);

  const csr = forge.pki.createCertificationRequest();
  csr.publicKey = publicKey;

  const attrs: Array<{ name: string; value?: string }> = [{ name: 'commonName', value: data.commonName }];
  if (data.organization) attrs.push({ name: 'organizationName', value: data.organization });
  if (data.organizationalUnit) attrs.push({ name: 'organizationalUnitName', value: data.organizationalUnit });
  if (data.country) attrs.push({ name: 'countryName', value: data.country });
  if (data.state) attrs.push({ name: 'stateOrProvinceName', value: data.state });
  if (data.locality) attrs.push({ name: 'localityName', value: data.locality });

  csr.setSubject(attrs);

  if (data.sanNames.length > 0) {
    csr.setAttributes([{
      name: 'extensionRequest',
      extensions: [{
        name: 'subjectAltName',
        altNames: data.sanNames.map(name => ({
          type: 2,
          value: name,
        })),
      }],
    }]);
  }

  csr.sign(privateKey, forge.md.sha256.create());

  const pemCSR = forge.pki.certificationRequestToPem(csr);
  const pemKey = forge.pki.privateKeyToPem(privateKey);

  logger.info({ commonName: data.commonName }, 'CSR generated');
  return {
    csr: pemCSR,
    privateKey: pemKey,
    commonName: data.commonName,
    sanNames: data.sanNames,
  };
};

const generateSerialNumber = (): string => {
  return '0x' + forge.util.bytesToHex(forge.random.getBytesSync(16));
};

export const getCertificate = async (certId: string): Promise<Certificate> => {
  const cert = await prisma.certificate.findUnique({ where: { certId } });
  if (!cert) throw new NotFoundError(`Certificate ${certId} not found`);
  return toCertificate(cert);
};

export const listCertificates = async (params: PaginationParams, status?: CertificateStatus, commonName?: string): Promise<PaginatedResult<Certificate>> => {
  const where: Record<string, unknown> = {};
  if (status) where.status = status;
  if (commonName) where.commonName = { contains: commonName };

  const [items, total] = await Promise.all([
    prisma.certificate.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
    }),
    prisma.certificate.count({ where }),
  ]);
  return {
    items: items.map(toCertificate),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const importCertificate = async (certificate: string, privateKey: string): Promise<Certificate> => {
  try {
    const cert = forge.pki.certificateFromPem(certificate);
    const commonName = cert.subject.getField('CN')?.value as string || '';
    const sanNames: string[] = [];

    const sanExtension = cert.getExtension('subjectAltName');
    if (sanExtension && 'altNames' in sanExtension) {
      (sanExtension.altNames as Array<{ type: number; value: string }>).forEach((alt: { type: number; value: string }) => {
        if (alt.type === 2) sanNames.push(alt.value);
      });
    }

    const existing = await prisma.certificate.findUnique({ where: { serialNumber: cert.serialNumber } });
    if (existing) throw new ValidationError(`Certificate with serial ${cert.serialNumber} already exists`);

    const certificateRecord = await prisma.certificate.create({
      data: {
        certId: generateCertId(),
        commonName,
        sanNames,
        certificate,
        privateKey,
        issuer: cert.issuer.getField('CN')?.value as string || '',
        serialNumber: cert.serialNumber,
        notBefore: cert.validity.notBefore,
        notAfter: cert.validity.notAfter,
        rotationPolicy: 'manual',
        status: determineCertStatus(cert.validity.notAfter),
      },
    });

    logger.info({ certId: certificateRecord.certId, commonName }, 'Certificate imported');
    return toCertificate(certificateRecord);
  } catch (error) {
    if (error instanceof ValidationError) throw error;
    throw new ValidationError('Invalid certificate or private key');
  }
};

export const revokeCertificate = async (data: RevokeCertificateRequest): Promise<CertificateRevocation> => {
  const cert = await prisma.certificate.findFirst({ where: { serialNumber: data.serialNumber } });
  if (!cert) throw new NotFoundError(`Certificate with serial ${data.serialNumber} not found`);

  await prisma.certificate.update({
    where: { id: cert.id },
    data: { status: 'revoked' },
  });

  const revocation = await prisma.certificateRevocation.create({
    data: {
      revocationId: generateRevocationId(),
      serialNumber: data.serialNumber,
      reason: data.reason,
      revokedAt: new Date(),
    },
  });

  logger.info({ serialNumber: data.serialNumber, reason: data.reason }, 'Certificate revoked');
  return toRevocation(revocation);
};

export const listRevocations = async (params: PaginationParams): Promise<PaginatedResult<CertificateRevocation>> => {
  const [items, total] = await Promise.all([
    prisma.certificateRevocation.findMany({
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { revokedAt: 'desc' },
    }),
    prisma.certificateRevocation.count(),
  ]);
  return {
    items: items.map(toRevocation),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const getCRL = async () => {
  const revocations = await prisma.certificateRevocation.findMany({
    orderBy: { revokedAt: 'desc' },
  });

  return {
    issuer: 'ChaosLab CA',
    thisUpdate: new Date(),
    nextUpdate: new Date(Date.now() + 24 * 60 * 60 * 1000),
    revokedCertificates: revocations.map(r => ({
      serialNumber: r.serialNumber,
      revocationDate: r.revokedAt,
      reason: r.reason,
    })),
  };
};

export const renewCertificate = async (certId: string): Promise<Certificate> => {
  const cert = await prisma.certificate.findUnique({ where: { certId } });
  if (!cert) throw new NotFoundError(`Certificate ${certId} not found`);

  const forgeCert = forge.pki.certificateFromPem(cert.certificate);
  const privateKey = forge.pki.privateKeyFromPem(cert.privateKey);

  const newCert = forge.pki.createCertificate();
  newCert.publicKey = forgeCert.publicKey;
  newCert.serialNumber = generateSerialNumber();
  newCert.validity.notBefore = new Date();
  newCert.validity.notAfter = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000);
  newCert.setSubject(forgeCert.subject.attributes);
  newCert.setIssuer(forgeCert.issuer.attributes);

  const extensions = forgeCert.extensions.filter(e => e.name !== 'subjectAltName');
  const sanExtension = forgeCert.getExtension('subjectAltName');
  if (sanExtension) {
    extensions.push(sanExtension);
  }
  newCert.setExtensions(extensions);

  newCert.sign(privateKey, forge.md.sha256.create());

  const pemCert = forge.pki.certificateToPem(newCert);

  const updatedCert = await prisma.certificate.update({
    where: { certId },
    data: {
      certificate: pemCert,
      serialNumber: newCert.serialNumber,
      notBefore: newCert.validity.notBefore,
      notAfter: newCert.validity.notAfter,
      status: 'active',
    },
  });

  logger.info({ certId }, 'Certificate renewed');
  return toCertificate(updatedCert);
};

export const getExpiringCertificates = async (days: number = 30): Promise<Certificate[]> => {
  const threshold = new Date(Date.now() + days * 24 * 60 * 60 * 1000);
  const certs = await prisma.certificate.findMany({
    where: {
      notAfter: { lte: threshold },
      status: 'active',
    },
    orderBy: { notAfter: 'asc' },
  });
  return certs.map(toCertificate);
};

export const deleteCertificate = async (certId: string): Promise<void> => {
  await prisma.certificate.delete({ where: { certId } });
  logger.info({ certId }, 'Certificate deleted');
};

export const getRotationPolicy = (): RotationPolicyConfig => ({ ...rotationPolicy });

export const updateRotationPolicy = (config: Partial<RotationPolicyConfig>): RotationPolicyConfig => {
  rotationPolicy = { ...rotationPolicy, ...config };
  logger.info({ config }, 'Rotation policy updated');
  return rotationPolicy;
};

export default {
  createCertificate,
  createCSR,
  getCertificate,
  listCertificates,
  importCertificate,
  revokeCertificate,
  listRevocations,
  getCRL,
  renewCertificate,
  getExpiringCertificates,
  deleteCertificate,
  getRotationPolicy,
  updateRotationPolicy,
};
