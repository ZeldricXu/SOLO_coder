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

export interface SensitiveFieldConfig {
  fieldName: string;
  sensitivityLevel: 'public' | 'internal' | 'confidential' | 'restricted';
  maskingStrategy: 'full' | 'partial' | 'hash' | 'encrypt' | 'remove';
  requiredPermission?: string;
  partialMasking?: {
    visibleStart: number;
    visibleEnd: number;
    maskChar: string;
  };
}

export interface DataClassificationRule {
  id: string;
  name: string;
  description: string;
  pattern?: RegExp;
  keywords?: string[];
  sensitivityLevel: 'public' | 'internal' | 'confidential' | 'restricted';
  category: string;
}

export interface ClassificationResult {
  fieldName: string;
  category: string;
  sensitivityLevel: 'public' | 'internal' | 'confidential' | 'restricted';
  confidence: number;
  matchedRule: string;
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

export interface MPCParticipant {
  id: string;
  name: string;
  publicKey: string;
  endpoint: string;
  status: 'active' | 'inactive' | 'disconnected';
}

export interface MPCTask {
  id: string;
  name: string;
  protocol: 'garbled-circuit' | 'secret-sharing' | 'homomorphic-encryption';
  participants: MPCParticipant[];
  status: 'pending' | 'running' | 'completed' | 'failed';
  inputFormat: Record<string, string>;
  resultFormat: Record<string, string>;
}

export interface FLClient {
  id: string;
  name: string;
  endpoint: string;
  publicKey: string;
  datasets: string[];
  status: 'available' | 'training' | 'offline';
}

export interface FLTrainingTask {
  id: string;
  name: string;
  modelArchitecture: string;
  hyperparameters: Record<string, number>;
  clients: FLClient[];
  currentRound: number;
  totalRounds: number;
  status: 'pending' | 'training' | 'aggregating' | 'completed' | 'failed';
  globalModelChecksum?: string;
}

export interface KeyShard {
  id: string;
  shardIndex: number;
  shardData: string;
  ownerId: string;
  threshold: number;
  totalShares: number;
  createdAt: number;
}

export interface DifferentialPrivacyConfig {
  epsilon: number;
  delta: number;
  mechanism: 'laplace' | 'gaussian' | 'exponential';
  sensitivity: number;
  privacyBudget: number;
  remainingBudget: number;
}

export interface DPQueryResult {
  originalValue: number;
  noisyValue: number;
  noiseAdded: number;
  epsilonUsed: number;
  deltaUsed: number;
  remainingBudget: number;
}

export type MaskingFunction = (value: string, config?: SensitiveFieldConfig) => string;
