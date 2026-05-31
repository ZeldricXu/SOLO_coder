import { CrossChainBridgeService } from '@core/usecases/crossChainBridge.usecase';
import { TransactionBuilderService } from '@core/usecases/transactionBuilder.usecase';
import { MockLogger } from '../__mocks__/mockPorts';
import { NotFoundError, ConflictError, ChainInteractionError } from '@shared/errors';
import {
  AddressBuilder,
  HashBuilder,
  HexStringBuilder,
  CrossChainMessageBuilder,
  AtomicOperationBuilder,
  CrossChainProofBuilder,
  MultisigStrategyBuilder,
  createMockChainProvider,
  createMockValidator,
} from '../builders/testDataBuilders';

describe('CrossChainBridgeService - Data Consistency Guarantees', () => {
  let bridgeService: CrossChainBridgeService;
  let transactionBuilder: TransactionBuilderService;
  let mockLogger: MockLogger;
  let chainProvider: ReturnType<typeof createMockChainProvider>;
  let validator: ReturnType<typeof createMockValidator>;

  const sourceChainId = 1;
  const targetChainId = 5;
  const sourceAddress = AddressBuilder.fromSeed(1);
  const targetAddress = AddressBuilder.fromSeed(2);
  const defaultAmount = BigInt('1000000000000000000');

  beforeEach(() => {
    mockLogger = new MockLogger();
    chainProvider = createMockChainProvider();
    validator = createMockValidator();

    transactionBuilder = new TransactionBuilderService(mockLogger);
    transactionBuilder.setMultisigStrategy(MultisigStrategyBuilder.simple2of3());

    bridgeService = new CrossChainBridgeService(
      chainProvider,
      transactionBuilder,
      validator,
      mockLogger,
      {
        requiredConfirmations: 2,
        supportedChains: [1, 5, 137, 42161],
        messageTimeout: 86400000,
      }
    );
  });

  describe('Initiate Bridge - Atomic Message Creation', () => {
    it('should create message with deterministic hash based on content', async () => {
      const message1 = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const message2 = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      expect(message1.messageHash).toBe(message2.messageHash);
      expect(message1.messageHash).toMatch(/^0x[a-fA-F0-9]{64}$/);
    });

    it('should generate different hashes for different amounts', async () => {
      const message1 = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        BigInt('1000000000000000000')
      );

      const message2 = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        BigInt('2000000000000000000')
      );

      expect(message1.messageHash).not.toBe(message2.messageHash);
    });

    it('should validate chain support before creating message', async () => {
      await expect(
        bridgeService.initiateBridge(
          999,
          targetChainId,
          sourceAddress,
          targetAddress,
          defaultAmount
        )
      ).rejects.toThrow(ConflictError);
    });

    it('should reject same source and target chains', async () => {
      await expect(
        bridgeService.initiateBridge(
          sourceChainId,
          sourceChainId,
          sourceAddress,
          targetAddress,
          defaultAmount
        )
      ).rejects.toThrow(ConflictError);
    });

    it('should check source balance before initiating', async () => {
      chainProvider.getClient.mockReturnValue({
        ...chainProvider.getClient(),
        getBalance: jest.fn().mockResolvedValue(BigInt('500000000000000000')),
      });

      await expect(
        bridgeService.initiateBridge(
          sourceChainId,
          targetChainId,
          sourceAddress,
          targetAddress,
          defaultAmount
        )
      ).rejects.toThrow(ChainInteractionError);
    });

    it('should store message for future retrieval', async () => {
      const message = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const retrieved = await bridgeService.getMessage(message.messageHash);

      expect(retrieved).toBeDefined();
      expect(retrieved?.messageHash).toBe(message.messageHash);
      expect(retrieved?.status).toBe('pending');
    });

    it('should return null for non-existent message', async () => {
      const result = await bridgeService.getMessage(HashBuilder.random());
      expect(result).toBeNull();
    });
  });

  describe('Message Verification - Consistency Checks', () => {
    it('should verify message integrity with correct confirmations', async () => {
      const message = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const result = await bridgeService.verifyMessage(
        message.messageHash,
        sourceChainId,
        targetChainId,
        1
      );

      expect(result.isValid).toBe(true);
      expect(result.confirmations).toBeGreaterThanOrEqual(1);
    });

    it('should fail verification when confirmations insufficient', async () => {
      const message = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const result = await bridgeService.verifyMessage(
        message.messageHash,
        sourceChainId,
        targetChainId,
        100
      );

      expect(result.isValid).toBe(false);
    });

    it('should mark message as confirmed after successful verification', async () => {
      const message = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      await bridgeService.verifyMessage(
        message.messageHash,
        sourceChainId,
        targetChainId,
        1
      );

      const updated = await bridgeService.getMessage(message.messageHash);
      expect(updated?.status).toBe('confirmed');
    });

    it('should return error for non-existent message verification', async () => {
      const result = await bridgeService.verifyMessage(
        HashBuilder.random(),
        sourceChainId,
        targetChainId,
        1
      );

      expect(result.isValid).toBe(false);
      expect(result.error).toBeDefined();
    });

    it('should fail verification when validator rejects', async () => {
      validator.verifyMessageIntegrity.mockResolvedValue(false);

      const message = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const result = await bridgeService.verifyMessage(
        message.messageHash,
        sourceChainId,
        targetChainId,
        1
      );

      expect(result.isValid).toBe(false);
    });
  });

  describe('Atomic Operations - Two-Phase Commit', () => {
    it('should create atomic operation with matching amounts', async () => {
      const operation = await bridgeService.initiateAtomicBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      expect(operation.sourceOperation.amount).toBe(defaultAmount);
      expect(operation.targetOperation.amount).toBe(defaultAmount);
      expect(operation.sourceOperation.type).toBe('lock');
      expect(operation.targetOperation.type).toBe('mint');
      expect(operation.status).toBe('pending');
    });

    it('should retrieve stored atomic operation', async () => {
      const operation = await bridgeService.initiateAtomicBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const retrieved = await bridgeService.getAtomicOperation(operation.id);
      expect(retrieved?.id).toBe(operation.id);
    });

    it('should confirm source operation and transition state', async () => {
      const operation = await bridgeService.initiateAtomicBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const txHash = HashBuilder.random();
      const updated = await bridgeService.confirmSourceOperation(operation.id, txHash);

      expect(updated.status).toBe('source_executed');
      expect(updated.sourceOperation.transactionHash).toBe(txHash);
    });

    it('should reject confirmation for non-existent operation', async () => {
      await expect(
        bridgeService.confirmSourceOperation('non-existent' as any, HashBuilder.random())
      ).rejects.toThrow(NotFoundError);
    });

    it('should reject confirmation for already completed operation', async () => {
      const operation = await bridgeService.initiateAtomicBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const txHash = HashBuilder.random();
      await bridgeService.confirmSourceOperation(operation.id, txHash);

      await expect(
        bridgeService.confirmSourceOperation(operation.id, txHash)
      ).rejects.toThrow(ConflictError);
    });

    it('should reject confirmation when validator rejects lock transaction', async () => {
      validator.validateLockTransaction.mockResolvedValue(false);

      const operation = await bridgeService.initiateAtomicBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      await expect(
        bridgeService.confirmSourceOperation(operation.id, HashBuilder.random())
      ).rejects.toThrow(ConflictError);
    });
  });

  describe('Rollback Mechanism - Consistency Recovery', () => {
    it('should rollback pending operation', async () => {
      const operation = await bridgeService.initiateAtomicBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const rolledBack = await bridgeService.rollbackOperation(
        operation.id,
        'User cancellation'
      );

      expect(rolledBack.status).toBe('rolled_back');
    });

    it('should rollback source_executed operation', async () => {
      const operation = await bridgeService.initiateAtomicBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      await bridgeService.confirmSourceOperation(operation.id, HashBuilder.random());

      const rolledBack = await bridgeService.rollbackOperation(operation.id);
      expect(rolledBack.status).toBe('rolled_back');
    });

    it('should log error when rolling back during target execution', async () => {
      const operation = await bridgeService.initiateAtomicBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      await bridgeService.confirmSourceOperation(operation.id, HashBuilder.random());

      (operation as any).status = 'target_executing';

      const rolledBack = await bridgeService.rollbackOperation(operation.id);
      expect(rolledBack.status).toBe('rolled_back');
      expect(mockLogger.error).toHaveBeenCalled();
    });

    it('should reject rollback for completed operation', async () => {
      const operation = AtomicOperationBuilder.default()
        .withStatus('completed')
        .build();

      (bridgeService as any).operations.set(operation.id, operation);

      await expect(
        bridgeService.rollbackOperation(operation.id)
      ).rejects.toThrow(ConflictError);
    });

    it('should reject rollback for already rolled back operation', async () => {
      const operation = await bridgeService.initiateAtomicBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      await bridgeService.rollbackOperation(operation.id);

      await expect(
        bridgeService.rollbackOperation(operation.id)
      ).rejects.toThrow(ConflictError);
    });
  });

  describe('Bridge Execution - Final Consistency Check', () => {
    it('should execute bridge for confirmed message', async () => {
      const message = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      await bridgeService.verifyMessage(
        message.messageHash,
        sourceChainId,
        targetChainId,
        1
      );

      const proof = await bridgeService.generateProof(message.messageHash, sourceChainId);
      const result = await bridgeService.executeBridge(proof);

      expect(result.transactionHash).toBeDefined();
      expect(result.messageId).toBe(message.id);
    });

    it('should mark message as executed after successful bridge', async () => {
      const message = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      await bridgeService.verifyMessage(
        message.messageHash,
        sourceChainId,
        targetChainId,
        1
      );

      const proof = await bridgeService.generateProof(message.messageHash, sourceChainId);
      await bridgeService.executeBridge(proof);

      const updated = await bridgeService.getMessage(message.messageHash);
      expect(updated?.status).toBe('executed');
    });

    it('should reject execution for unconfirmed message', async () => {
      const message = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const proof = CrossChainProofBuilder.default()
        .withMessageHash(message.messageHash)
        .withSourceChain(sourceChainId)
        .withTargetChain(targetChainId)
        .build();

      await expect(bridgeService.executeBridge(proof)).rejects.toThrow(ConflictError);
    });

    it('should reject execution for non-existent message', async () => {
      const proof = CrossChainProofBuilder.default().build();

      await expect(bridgeService.executeBridge(proof)).rejects.toThrow(NotFoundError);
    });

    it('should generate proof for valid message', async () => {
      const message = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const proof = await bridgeService.generateProof(message.messageHash, sourceChainId);

      expect(proof.messageHash).toBe(message.messageHash);
      expect(proof.proof.length).toBeGreaterThan(0);
      expect(proof.blockHash).toMatch(/^0x[a-fA-F0-9]{64}$/);
    });

    it('should reject proof generation for non-existent message', async () => {
      await expect(
        bridgeService.generateProof(HashBuilder.random(), sourceChainId)
      ).rejects.toThrow(NotFoundError);
    });
  });

  describe('Message Filtering - Query Consistency', () => {
    it('should list messages filtered by source chain', async () => {
      await bridgeService.initiateBridge(
        1,
        5,
        sourceAddress,
        targetAddress,
        defaultAmount
      );
      await bridgeService.initiateBridge(
        137,
        5,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      const ethMessages = bridgeService.listMessages({ sourceChainId: 1 });
      expect(ethMessages.length).toBe(1);
      expect(ethMessages[0].sourceChainId).toBe(1);
    });

    it('should list messages filtered by status', async () => {
      const message1 = await bridgeService.initiateBridge(
        sourceChainId,
        targetChainId,
        sourceAddress,
        targetAddress,
        defaultAmount
      );
      await bridgeService.initiateBridge(
        sourceChainId,
        137,
        sourceAddress,
        targetAddress,
        defaultAmount
      );

      await bridgeService.verifyMessage(
        message1.messageHash,
        sourceChainId,
        targetChainId,
        1
      );

      const confirmed = bridgeService.listMessages({ status: 'confirmed' });
      expect(confirmed.length).toBe(1);
      expect(confirmed[0].messageHash).toBe(message1.messageHash);
    });
  });
});
