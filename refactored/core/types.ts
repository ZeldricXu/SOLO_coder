export interface UserRole {
  id: string;
  name: string;
  permissions: string[];
  dataAccessLevel: number;
}

export interface User {
  id: string;
  username: string;
  roles: UserRole[];
  department?: string;
}

export type SensitivityLevel = 'public' | 'internal' | 'confidential' | 'restricted';

export type MaskingStrategyType = 'full' | 'partial' | 'hash' | 'encrypt' | 'remove';

export interface SensitiveFieldConfig {
  fieldName: string;
  sensitivityLevel: SensitivityLevel;
  maskingStrategy: MaskingStrategyType;
  requiredPermission?: string;
  partialMasking?: {
    visibleStart: number;
    visibleEnd: number;
    maskChar: string;
  };
}

export interface AuditLogEntry {
  id: string;
  timestamp: number;
  userId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  details: Record<string, unknown>;
  previousHash: string;
  hash: string;
  nonce: number;
}

export interface EnclaveConfig {
  enclaveId: string;
  name: string;
  mrenclave: string;
  mrsigner: string;
  isvProdId: number;
  isvSvn: number;
  attributes: string[];
}

export interface RemoteAttestationReport {
  enclaveId: string;
  timestamp: number;
  quote: string;
  signature: string;
  certificateChain: string;
  isVerified: boolean;
}

export type EnclaveStatusType = 'uninitialized' | 'initialized' | 'running' | 'suspended' | 'terminated';

export interface EnclaveStatus {
  enclaveId: string;
  status: EnclaveStatusType;
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
