import type { Address, ChainId, Hash, HexString, GasAmount, WeiAmount, ISO8601Date, UUID } from '@shared/types';

export interface Block {
  hash: Hash;
  number: bigint;
  timestamp: bigint;
  chainId: ChainId;
  parentHash: Hash;
  gasLimit: GasAmount;
  gasUsed: GasAmount;
  baseFeePerGas?: WeiAmount;
  transactions: Hash[];
}

export interface Transaction {
  hash: Hash;
  from: Address;
  to: Address | null;
  value: WeiAmount;
  data: HexString;
  nonce: number;
  gasLimit: GasAmount;
  gasPrice?: WeiAmount;
  maxFeePerGas?: WeiAmount;
  maxPriorityFeePerGas?: WeiAmount;
  chainId: ChainId;
  type: 0 | 1 | 2;
  signature?: TransactionSignature;
}

export interface TransactionReceipt {
  transactionHash: Hash;
  blockHash: Hash;
  blockNumber: bigint;
  status: 'success' | 'reverted';
  gasUsed: GasAmount;
  effectiveGasPrice: WeiAmount;
  cumulativeGasUsed: GasAmount;
  logs: LogEntry[];
}

export interface TransactionSignature {
  r: HexString;
  s: HexString;
  v: bigint;
}

export interface LogEntry {
  address: Address;
  topics: HexString[];
  data: HexString;
  blockNumber: bigint;
  transactionHash: Hash;
  logIndex: number;
  removed: boolean;
}

export interface ContractEvent<T = unknown> {
  name: string;
  address: Address;
  blockNumber: bigint;
  transactionHash: Hash;
  data: T;
  raw: LogEntry;
}

export interface GasEstimate {
  gasLimit: GasAmount;
  baseFeePerGas: WeiAmount;
  maxPriorityFeePerGas: WeiAmount;
  maxFeePerGas: WeiAmount;
  estimatedCost: WeiAmount;
  confidence: number;
  timestamp: ISO8601Date;
}

export interface GasPriceHistory {
  chainId: ChainId;
  timestamp: ISO8601Date;
  baseFeePerGas: WeiAmount;
  maxPriorityFeePerGas: WeiAmount;
  gasUsedRatio: number;
  blockNumber?: bigint;
}

export interface MultisigWallet {
  id: UUID;
  address: Address;
  chainId: ChainId;
  owners: Address[];
  threshold: number;
  nonce: bigint;
  label?: string;
}

export interface HdWallet {
  id: UUID;
  seedFingerprint: string;
  purpose: number;
  coinType: number;
  account: number;
  createdAt: ISO8601Date;
}

export interface DerivedAddress {
  id: UUID;
  walletId: UUID;
  address: Address;
  path: string;
  chainId: ChainId;
  label?: string;
  tags: string[];
  createdAt: ISO8601Date;
  updatedAt: ISO8601Date;
}

export interface CrossChainMessage {
  id: UUID;
  sourceChainId: ChainId;
  targetChainId: ChainId;
  sourceAddress: Address;
  targetAddress: Address;
  amount: WeiAmount;
  data: HexString;
  messageHash: Hash;
  status: 'pending' | 'confirmed' | 'executed' | 'failed';
  createdAt: ISO8601Date;
}

export interface StorageContent {
  cid: string;
  content: Uint8Array;
  size: number;
  contentType: string;
  createdAt: ISO8601Date;
}

export interface PinStatus {
  cid: string;
  status: 'pinning' | 'pinned' | 'failed';
  peers: string[];
  createdAt: ISO8601Date;
}
