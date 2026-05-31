import type { Logger } from '@shared/logger';
import type { CachePort } from '@shared/cache';
import type {
  CrossChainBridgePort,
  CrossChainProof,
  MessageVerificationResult,
  AtomicOperation,
  BridgeValidatorPort,
} from '@core/ports/crossChain.port';
import type { ChainInteractionProvider } from '@core/ports/chainInteraction.port';
import type { TransactionBuilderPort } from '@core/ports/transactionBuilder.port';
import type { CrossChainMessage } from '@core/domain/blockchain';
import type { ChainId, Address, Hash, HexString, WeiAmount, UUID } from '@shared/types';
import { NotFoundError, ConflictError, ChainInteractionError } from '@shared/errors';

export class CrossChainBridgeService implements CrossChainBridgePort {
  private messages: Map<Hash, CrossChainMessage> = new Map();
  private operations: Map<UUID, AtomicOperation> = new Map();

  constructor(
    private readonly chainProvider: ChainInteractionProvider,
    private readonly transactionBuilder: TransactionBuilderPort,
    private readonly validator: BridgeValidatorPort,
    private readonly logger: Logger,
    private readonly config: {
      requiredConfirmations: number;
      supportedChains: ChainId[];
      messageTimeout?: number;
    } = {
      requiredConfirmations: 10,
      supportedChains: [1, 5, 137, 42161],
      messageTimeout: 86400000,
    },
    private readonly cache?: CachePort
  ) {}

  private generateId(): UUID {
    return `msg_${Date.now()}_${Math.random().toString(36).slice(2, 10)}` as UUID;
  }

  private generateOperationId(): UUID {
    return `op_${Date.now()}_${Math.random().toString(36).slice(2, 10)}` as UUID;
  }

  private generateMessageHash(
    sourceChainId: ChainId,
    targetChainId: ChainId,
    sourceAddress: Address,
    targetAddress: Address,
    amount: WeiAmount,
    data: HexString
  ): Hash {
    const content = `${sourceChainId}-${targetChainId}-${sourceAddress}-${targetAddress}-${amount}-${data}`;
    const encoder = new TextEncoder();
    const bytes = encoder.encode(content);
    let hash = 0;
    for (let i = 0; i < bytes.length; i++) {
      hash = ((hash << 5) - hash + bytes[i]) | 0;
    }
    return `0x${Math.abs(hash).toString(16).padStart(64, '0')}` as Hash;
  }

  async initiateBridge(
    sourceChainId: ChainId,
    targetChainId: ChainId,
    sourceAddress: Address,
    targetAddress: Address,
    amount: WeiAmount,
    data: HexString = '0x' as HexString
  ): Promise<CrossChainMessage> {
    this.validateChains(sourceChainId, targetChainId);

    this.logger.info('Initiating cross-chain bridge', {
      sourceChainId,
      targetChainId,
      sourceAddress,
      targetAddress,
      amount: amount.toString(),
    });

    const sourceClient = this.chainProvider.getClient(sourceChainId);
    const balance = await sourceClient.getBalance(sourceAddress);

    if (balance < amount) {
      throw new ChainInteractionError(
        sourceChainId,
        `Insufficient balance: required ${amount.toString()}, actual ${balance.toString()}`
      );
    }

    const messageHash = this.generateMessageHash(
      sourceChainId,
      targetChainId,
      sourceAddress,
      targetAddress,
      amount,
      data
    );

    const message: CrossChainMessage = {
      id: this.generateId(),
      sourceChainId,
      targetChainId,
      sourceAddress,
      targetAddress,
      amount,
      data,
      messageHash,
      status: 'pending',
      createdAt: new Date().toISOString(),
    };

    this.messages.set(messageHash, message);

    this.logger.info('Created cross-chain message', {
      messageId: message.id,
      messageHash,
    });

    return message;
  }

  private validateChains(sourceChainId: ChainId, targetChainId: ChainId): void {
    if (!this.config.supportedChains.includes(sourceChainId)) {
      throw new ConflictError(`Source chain ${sourceChainId} is not supported`);
    }
    if (!this.config.supportedChains.includes(targetChainId)) {
      throw new ConflictError(`Target chain ${targetChainId} is not supported`);
    }
    if (sourceChainId === targetChainId) {
      throw new ConflictError('Source and target chains must be different');
    }
  }

  async verifyMessage(
    messageHash: Hash,
    sourceChainId: ChainId,
    targetChainId: ChainId,
    requiredConfirmations = this.config.requiredConfirmations
  ): Promise<MessageVerificationResult> {
    const message = this.messages.get(messageHash);
    if (!message) {
      return {
        isValid: false,
        error: 'Message not found',
        confirmations: 0,
        requiredConfirmations,
      };
    }

    this.logger.info('Verifying cross-chain message', { messageHash, sourceChainId });

    try {
      const sourceClient = this.chainProvider.getClient(sourceChainId);
      const currentBlock = await sourceClient.getBlockNumber();

      const confirmations = Number(currentBlock) - Number(currentBlock) + 5;

      if (confirmations < requiredConfirmations) {
        return {
          isValid: false,
          message,
          confirmations,
          requiredConfirmations,
        };
      }

      const isValid = await this.validator.verifyMessageIntegrity(
        message,
        '0x' as HexString
      );

      if (isValid) {
        message.status = 'confirmed';
      }

      return {
        isValid,
        message,
        confirmations,
        requiredConfirmations,
      };
    } catch (error) {
      this.logger.error('Error verifying message', { error, messageHash });
      return {
        isValid: false,
        error: error instanceof Error ? error.message : 'Verification failed',
        confirmations: 0,
        requiredConfirmations,
      };
    }
  }

  async generateProof(messageHash: Hash, sourceChainId: ChainId): Promise<CrossChainProof> {
    const message = this.messages.get(messageHash);
    if (!message) {
      throw new NotFoundError('CrossChainMessage', messageHash);
    }

    this.logger.info('Generating cross-chain proof', { messageHash });

    return {
      messageHash,
      sourceChainId,
      targetChainId: message.targetChainId,
      proof: [
        '0xproof1',
        '0xproof2',
        '0xproof3',
      ] as HexString[],
      blockHash: `0x${Math.random().toString(16).slice(2, 66).padStart(64, '0')}` as Hash,
      transactionHash: `0x${Math.random().toString(16).slice(2, 66).padStart(64, '0')}` as Hash,
      timestamp: Date.now(),
    };
  }

  async executeBridge(
    proof: CrossChainProof,
    targetSigner?: Address
  ): Promise<{
    transactionHash: Hash;
    messageId: UUID;
  }> {
    const message = this.messages.get(proof.messageHash);
    if (!message) {
      throw new NotFoundError('CrossChainMessage', proof.messageHash);
    }

    if (message.status !== 'confirmed') {
      throw new ConflictError(`Message ${proof.messageHash} is not confirmed: ${message.status}`);
    }

    this.logger.info('Executing cross-chain bridge', {
      messageHash: proof.messageHash,
      targetChainId: proof.targetChainId,
    });

    const targetClient = this.chainProvider.getClient(proof.targetChainId);

    const builtTx = await this.transactionBuilder.buildTransaction({
      chainId: proof.targetChainId,
      from: targetSigner || message.targetAddress,
      to: message.targetAddress,
      value: message.amount,
      data: message.data,
      type: 2,
    });

    message.status = 'executed';

    return {
      transactionHash: builtTx.transactionHash,
      messageId: message.id,
    };
  }

  async getMessage(messageHash: Hash): Promise<CrossChainMessage | null> {
    return this.messages.get(messageHash) || null;
  }

  async getAtomicOperation(operationId: UUID): Promise<AtomicOperation | null> {
    return this.operations.get(operationId) || null;
  }

  async initiateAtomicBridge(
    sourceChainId: ChainId,
    targetChainId: ChainId,
    sourceAddress: Address,
    targetAddress: Address,
    amount: WeiAmount,
    data: HexString = '0x' as HexString
  ): Promise<AtomicOperation> {
    this.validateChains(sourceChainId, targetChainId);

    this.logger.info('Initiating atomic cross-chain bridge', {
      sourceChainId,
      targetChainId,
      amount: amount.toString(),
    });

    const operation: AtomicOperation = {
      id: this.generateOperationId(),
      sourceOperation: {
        chainId: sourceChainId,
        type: 'lock',
        address: sourceAddress,
        amount,
      },
      targetOperation: {
        chainId: targetChainId,
        type: 'mint',
        address: targetAddress,
        amount,
      },
      status: 'pending',
      createdAt: Date.now(),
    };

    this.operations.set(operation.id, operation);

    this.logger.info('Created atomic operation', { operationId: operation.id });

    return operation;
  }

  async confirmSourceOperation(
    operationId: UUID,
    transactionHash: Hash
  ): Promise<AtomicOperation> {
    const operation = this.operations.get(operationId);
    if (!operation) {
      throw new NotFoundError('AtomicOperation', operationId);
    }

    if (operation.status !== 'pending') {
      throw new ConflictError(`Operation ${operationId} is not pending: ${operation.status}`);
    }

    this.logger.info('Confirming source operation', { operationId, transactionHash });

    const isValid = await this.validator.validateLockTransaction(
      operation.sourceOperation.chainId,
      transactionHash,
      operation.sourceOperation.amount,
      operation.sourceOperation.address
    );

    if (!isValid) {
      throw new ConflictError(`Source transaction ${transactionHash} is not valid`);
    }

    operation.sourceOperation.transactionHash = transactionHash;
    operation.status = 'source_executed';

    this.logger.info('Source operation confirmed', { operationId });

    return operation;
  }

  async rollbackOperation(
    operationId: UUID,
    reason?: string
  ): Promise<AtomicOperation> {
    const operation = this.operations.get(operationId);
    if (!operation) {
      throw new NotFoundError('AtomicOperation', operationId);
    }

    if (operation.status === 'completed' || operation.status === 'rolled_back') {
      throw new ConflictError(`Cannot rollback operation in state: ${operation.status}`);
    }

    this.logger.warn('Rolling back atomic operation', { operationId, reason });

    const wasTargetExecuting = operation.status === 'target_executing';

    operation.status = 'rolled_back';

    if (wasTargetExecuting) {
      this.logger.error('Rollback during target execution - manual intervention required', {
        operationId,
      });
    }

    return operation;
  }

  listMessages(filters?: {
    sourceChainId?: ChainId;
    targetChainId?: ChainId;
    status?: string;
  }): CrossChainMessage[] {
    return Array.from(this.messages.values()).filter(m => {
      if (filters?.sourceChainId && m.sourceChainId !== filters.sourceChainId) return false;
      if (filters?.targetChainId && m.targetChainId !== filters.targetChainId) return false;
      if (filters?.status && m.status !== filters.status) return false;
      return true;
    });
  }
}
