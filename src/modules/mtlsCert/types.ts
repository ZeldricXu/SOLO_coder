import { z } from 'zod';
import type { CertificateStatus } from '../../types';

export const CertificateSchema = z.object({
  commonName: z.string().min(1),
  sanNames: z.array(z.string()).default([]),
  organization: z.string().optional(),
  organizationalUnit: z.string().optional(),
  country: z.string().optional(),
  state: z.string().optional(),
  locality: z.string().optional(),
  validityDays: z.number().int().positive().default(365),
  keyAlgorithm: z.enum(['RSA-2048', 'RSA-4096', 'ECDSA-P256', 'ECDSA-P384']).default('RSA-2048'),
  rotationPolicy: z.enum(['auto', 'manual']).default('auto'),
  autoRenewDays: z.number().int().positive().default(30),
});

export const CertificateSigningRequestSchema = z.object({
  commonName: z.string().min(1),
  sanNames: z.array(z.string()).default([]),
  organization: z.string().optional(),
  organizationalUnit: z.string().optional(),
  country: z.string().optional(),
  state: z.string().optional(),
  locality: z.string().optional(),
  keyAlgorithm: z.enum(['RSA-2048', 'RSA-4096', 'ECDSA-P256', 'ECDSA-P384']).default('RSA-2048'),
});

export const RevokeCertificateSchema = z.object({
  serialNumber: z.string().min(1),
  reason: z.enum(['unspecified', 'key_compromise', 'ca_compromise', 'affiliation_changed', 'superseded', 'cessation_of_operation', 'certificate_hold', 'remove_from_crl', 'privilege_withdrawn', 'aa_compromise']).default('unspecified'),
});

export const RotationPolicySchema = z.object({
  enabled: z.boolean().default(true),
  autoRenewDays: z.number().int().positive().default(30),
  maxRetries: z.number().int().nonnegative().default(3),
  notifyBeforeDays: z.array(z.number()).default([7, 3, 1]),
});

export type CreateCertificateRequest = z.infer<typeof CertificateSchema>;
export type CreateCSRRequest = z.infer<typeof CertificateSigningRequestSchema>;
export type RevokeCertificateRequest = z.infer<typeof RevokeCertificateSchema>;
export type RotationPolicyConfig = z.infer<typeof RotationPolicySchema>;

export interface Certificate {
  certId: string;
  commonName: string;
  sanNames: string[];
  certificate: string;
  privateKey: string;
  issuer: string;
  serialNumber: string;
  notBefore: Date;
  notAfter: Date;
  rotationPolicy: string;
  status: CertificateStatus;
  createdAt: Date;
  updatedAt: Date;
}

export interface CSRResult {
  csr: string;
  privateKey: string;
  commonName: string;
  sanNames: string[];
}

export interface CertificateRevocation {
  revocationId: string;
  serialNumber: string;
  reason: string;
  revokedAt: Date;
  createdAt: Date;
}

export interface CRL {
  issuer: string;
  thisUpdate: Date;
  nextUpdate: Date;
  revokedCertificates: Array<{
    serialNumber: string;
    revocationDate: Date;
    reason: string;
  }>;
}
