import type { ChainId, Address, Hash, HexString, WeiAmount, UUID } from '@shared/types';
import type { CrossChainMessage } from '@core/domain/blockchain';

export interface CrossChainProof {
  messageHash: Hash;
  sourceChainId: ChainId;
  targetChainId: ChainId;
  proof: HexString[];
  blockHash: Hash;
  transactionHash: Hash;
  timestamp: number;
}

export interface MessageVerificationResult {
  isValid: boolean;
  message?: CrossChainMessage;
  error?: string;
  confirmations: number;
  requiredConfirmations: number;
}

export interface AtomicOperation {
  id: UUID;
  sourceOperation: {
    chainId: ChainId;
    type: 'lock' | 'burn';
    transactionHash?: Hash;
    address: Address;
    amount: WeiAmount;
  };
  targetOperation: {
    chainId: ChainId;
    type: 'mint' | 'unlock';
    transactionHash?: Hash;
    address: Address;
    amount: WeiAmount;
  };
  status: 'pending' | 'source_confirmed' | 'source_executed' | 'target_executing' | 'completed' | 'failed' | 'rolled_back';
  createdAt: number;
  completedAt?: number;
}

export interface CrossChainBridgePort {
  initiateBridge(
    sourceChainId: ChainId,
    targetChainId: ChainId,
    sourceAddress: Address,
    targetAddress: Address,
    amount: WeiAmount,
    data?: HexString
  ): Promise<CrossChainMessage>;

  verifyMessage(
    messageHash: Hash,
    sourceChainId: ChainId,
    targetChainId: ChainId,
    requiredConfirmations?: number
  ): Promise<MessageVerificationResult>;

  generateProof(messageHash: Hash, sourceChainId: ChainId): Promise<CrossChainProof>;

  executeBridge(
    proof: CrossChainProof,
    targetSigner?: Address
  ): Promise<{
    transactionHash: Hash;
    messageId: UUID;
  }>;

  getMessage(messageHash: Hash): Promise<CrossChainMessage | null>;

  getAtomicOperation(operationId: UUID): Promise<AtomicOperation | null>;

  initiateAtomicBridge(
    sourceChainId: ChainId,
    targetChainId: ChainId,
    sourceAddress: Address,
    targetAddress: Address,
    amount: WeiAmount,
    data?: HexString
  ): Promise<AtomicOperation>;

  confirmSourceOperation(
    operationId: UUID,
    transactionHash: Hash
  ): Promise<AtomicOperation>;

  rollbackOperation(
    operationId: UUID,
    reason?: string
  ): Promise<AtomicOperation>;
}

export interface BridgeValidatorPort {
  validateLockTransaction(
    chainId: ChainId,
    transactionHash: Hash,
    expectedAmount: WeiAmount,
    expectedRecipient: Address
  ): Promise<boolean>;

  validateMintTransaction(
    chainId: ChainId,
    transactionHash: Hash,
    expectedAmount: WeiAmount,
    expectedRecipient: Address
  ): Promise<boolean>;

  verifyMessageIntegrity(message: CrossChainMessage, signature: HexString): Promise<boolean>;
}
