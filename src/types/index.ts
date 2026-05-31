export interface BaseEntity {
  id: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface Address extends BaseEntity {
  address: string;
  chainId: number;
  derivationPath: string;
  walletType: 'hd' | 'multisig' | 'imported';
  label?: string;
  metadata?: Record<string, any>;
  isActive: boolean;
}

export interface CreateAddressRequest {
  chainId: number;
  label?: string;
  accountIndex?: number;
  addressIndex?: number;
}

export interface UpdateAddressRequest {
  label?: string;
  metadata?: Record<string, any>;
  isActive?: boolean;
}

export interface CrossChainTransfer extends BaseEntity {
  sourceChainId: number;
  targetChainId: number;
  sourceAddress: string;
  targetAddress: string;
  amount: string;
  tokenAddress?: string;
  status: TransferStatus;
  sourceTxHash?: string;
  targetTxHash?: string;
  messageHash?: string;
  signatures?: Signature[];
}

export type TransferStatus = 'PENDING' | 'LOCKED' | 'VALIDATED' | 'MINTED' | 'CONFIRMED' | 'FAILED' | 'REJECTED';

export interface CrossChainMessage {
  id: string;
  sourceChainId: number;
  targetChainId: number;
  sourceAddress: string;
  targetAddress: string;
  amount: string;
  tokenAddress?: string;
  nonce: string;
  timestamp: number;
  data?: Record<string, any>;
}

export interface Signature {
  signer: string;
  signature: string;
  timestamp: number;
}

export interface CrossChainTransferRequest {
  sourceChainId: number;
  targetChainId: number;
  sourceAddress: string;
  targetAddress: string;
  amount: string;
  tokenAddress?: string;
}

export interface MultisigProposal extends BaseEntity {
  walletId: string;
  chainId: number;
  nonce: number;
  type: ProposalType;
  data: ProposalData;
  threshold: number;
  requiredSigners: number;
  signatures: Signature[];
  status: ProposalStatus;
  executedTxHash?: string;
}

export type ProposalType = 'TRANSFER' | 'APPROVE' | 'EXECUTE' | 'UPDATE_OWNERS' | 'CHANGE_THRESHOLD' | 'CUSTOM';

export type ProposalStatus = 'PENDING' | 'APPROVED' | 'EXECUTED' | 'REJECTED' | 'EXPIRED';

export interface ProposalData {
  to: string;
  value: string;
  data?: string;
  operation?: number;
}

export interface CreateProposalRequest {
  walletId: string;
  chainId: number;
  type: ProposalType;
  data: ProposalData;
}

export interface SignProposalRequest {
  proposalId: string;
  signer: string;
  signature: string;
}

export interface ExecuteProposalRequest {
  proposalId: string;
}

export interface StorageItem extends BaseEntity {
  cid: string;
  contentType: string;
  size: bigint;
  storageNetwork: 'ipfs' | 'arweave' | 'arweave-bundlr';
  isPinned: boolean;
  metadata?: Record<string, any>;
}

export interface UploadRequest {
  data: Buffer | string;
  contentType: string;
  storageNetwork: 'ipfs' | 'arweave' | 'arweave-bundlr';
  pin?: boolean;
  metadata?: Record<string, any>;
}

export interface DownloadRequest {
  cid: string;
  storageNetwork: 'ipfs' | 'arweave' | 'arweave-bundlr';
}

export interface PinRequest {
  cid: string;
  storageNetwork: 'ipfs' | 'arweave' | 'arweave-bundlr';
}

export interface Transaction extends BaseEntity {
  chainId: number;
  from: string;
  to: string;
  value: string;
  data?: string;
  gasPrice?: string;
  gasLimit: string;
  nonce: number;
  status: TransactionStatus;
  txHash?: string;
  blockNumber?: bigint;
  errorMessage?: string;
}

export type TransactionStatus = 'PENDING' | 'SIGNED' | 'BROADCAST' | 'CONFIRMED' | 'FAILED';

export interface BuildTransactionRequest {
  chainId: number;
  from: string;
  to: string;
  value: string;
  data?: string;
  gasPrice?: string;
  gasLimit?: string;
  maxPriorityFeePerGas?: string;
  maxFeePerGas?: string;
  nonce?: number;
  multisig?: MultisigConfig;
}

export interface MultisigConfig {
  walletId: string;
  threshold: number;
  owners: string[];
}

export interface SignTransactionRequest {
  transactionId: string;
  signer: string;
  signature: string;
}

export interface ChainConfig {
  chainId: number;
  name: string;
  rpcUrl: string;
  explorerUrl?: string;
  nativeCurrency: {
    name: string;
    symbol: string;
    decimals: number;
  };
  isActive: boolean;
}

export interface BlockData {
  chainId: number;
  blockNumber: bigint;
  blockHash: string;
  timestamp: Date;
  transactions: Transaction[];
  gasUsed: bigint;
  gasLimit: bigint;
  rawData: Record<string, any>;
}

export interface TransactionReceipt {
  txHash: string;
  status: boolean;
  blockNumber: bigint;
  blockHash: string;
  gasUsed: bigint;
  cumulativeGasUsed: bigint;
  logs: Log[];
}

export interface Log {
  address: string;
  topics: string[];
  data: string;
  blockNumber: bigint;
  transactionHash: string;
  logIndex: number;
}

export interface GasEstimate {
  chainId: number;
  low: GasPrice;
  average: GasPrice;
  high: GasPrice;
  baseFee?: string;
  priorityFee?: string;
  timestamp: Date;
}

export interface GasPrice {
  gasPrice: string;
  estimatedTime: number;
  confidence: number;
}

export interface EstimateGasRequest {
  chainId: number;
  from?: string;
  to?: string;
  value?: string;
  data?: string;
}

export interface IndexedBlock {
  chainId: number;
  blockNumber: bigint;
  blockHash: string;
  timestamp: Date;
  transactionCount: number;
  gasUsed: bigint;
  gasLimit: bigint;
  transactions: IndexedTransaction[];
}

export interface IndexedTransaction {
  hash: string;
  from: string;
  to: string;
  value: string;
  input: string;
  gasPrice: string;
  gas: string;
  nonce: number;
  status: 'pending' | 'confirmed' | 'failed';
  logs: Log[];
}

export interface BlockFilter {
  chainId: number;
  fromBlock?: bigint;
  toBlock?: bigint;
  fromAddress?: string;
  toAddress?: string;
  contractAddress?: string;
}

export interface ApiResponse<T> {
  code: number;
  data?: T;
  message?: string;
  errors?: ApiError[];
}

export interface ApiError {
  field?: string;
  message: string;
  code?: string;
}

export interface PaginatedResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}
