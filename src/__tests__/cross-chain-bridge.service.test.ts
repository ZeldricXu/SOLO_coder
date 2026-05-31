import { CrossChainBridgeService } from '../../modules/cross-chain-bridge/crossChainBridge.service';
import { testDataFactory } from '../factories';
import {
  CHAIN_IDS,
  generateAddress,
  generateCuid,
  generateTransactionHash,
  generateMessageHash,
  generateSignature,
  DEFAULT_MULTISIG_OWNERS,
} from '../test-utils';
import { NotFoundError, ValidationError } from '../../utils/errors';

jest.mock('../../utils/database', () => ({
  getPrismaClient: jest.fn(),
}));

jest.mock('../../utils/cache', () => ({
  cacheService: {
    get: jest.fn(),
    set: jest.fn(),
    delete: jest.fn(),
  },
}));

jest.mock('../../utils/crypto', () => ({
  CryptoUtils: {
    deriveAddress: jest.fn(),
    isValidAddress: jest.fn((address: string) => address.startsWith('0x') && address.length === 42),
    signMessage: jest.fn(),
    verifySignature: jest.fn(),
  },
}));

jest.mock('../../config', () => ({
  config: {
    multisig: {
      defaultThreshold: 2,
      defaultOwners: [
        '0x742d35Cc6634C0532925a3b844Bc9e8588c10516',
        '0x8626f6940E2eb28930eFb4CeF49B2d1F2C9C1199',
        '0x1aE0EA34a72D944a8C7603FfB3eC30a6669E454C',
      ],
    },
  },
}));

describe('CrossChainBridgeService', () => {
  let service: CrossChainBridgeService;
  let mockPrisma: any;
  let mockCache: any;
  let mockCrypto: any;

  beforeEach(() => {
    mockPrisma = {
      crossChainTransfer: {
        findUnique: jest.fn(),
        findMany: jest.fn(),
        count: jest.fn(),
        create: jest.fn(),
        update: jest.fn(),
      },
    };

    require('../../utils/database').getPrismaClient.mockReturnValue(mockPrisma);
    mockCache = require('../../utils/cache').cacheService;
    mockCrypto = require('../../utils/crypto').CryptoUtils;

    service = new CrossChainBridgeService();
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  describe('initiateTransfer', () => {
    it('should initiate transfer successfully with valid parameters', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();
      const sourceAddress = generateAddress();
      const targetAddress = generateAddress();

      mockPrisma.crossChainTransfer.create.mockResolvedValue(mockTransfer);

      const request = {
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.BSC,
        sourceAddress,
        targetAddress,
        amount: '1000000000000000000',
      };

      const result = await service.initiateTransfer(request);

      expect(result.transfer.id).toBe(mockTransfer.id);
      expect(result.transfer.status).toBe('PENDING');
      expect(result.message).toBeDefined();
      expect(result.messageHash).toBeDefined();
      expect(mockPrisma.crossChainTransfer.create).toHaveBeenCalled();
    });

    it('should throw ValidationError when source and target chains are the same', async () => {
      const sourceAddress = generateAddress();
      const targetAddress = generateAddress();

      const request = {
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.ETHEREUM,
        sourceAddress,
        targetAddress,
        amount: '1000000000000000000',
      };

      await expect(service.initiateTransfer(request)).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when amount is zero', async () => {
      const sourceAddress = generateAddress();
      const targetAddress = generateAddress();

      const request = {
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.BSC,
        sourceAddress,
        targetAddress,
        amount: '0',
      };

      await expect(service.initiateTransfer(request)).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when amount is negative', async () => {
      const sourceAddress = generateAddress();
      const targetAddress = generateAddress();

      const request = {
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.BSC,
        sourceAddress,
        targetAddress,
        amount: '-100',
      };

      await expect(service.initiateTransfer(request)).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when source address is invalid', async () => {
      const targetAddress = generateAddress();

      const request = {
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.BSC,
        sourceAddress: 'invalid_address',
        targetAddress,
        amount: '1000000000000000000',
      };

      await expect(service.initiateTransfer(request)).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when target address is invalid', async () => {
      const sourceAddress = generateAddress();

      const request = {
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.BSC,
        sourceAddress,
        targetAddress: 'invalid_address',
        amount: '1000000000000000000',
      };

      await expect(service.initiateTransfer(request)).rejects.toThrow(ValidationError);
    });

    it('should handle large amounts correctly', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer({
        amount: '1000000000000000000000000000',
      });
      const sourceAddress = generateAddress();
      const targetAddress = generateAddress();

      mockPrisma.crossChainTransfer.create.mockResolvedValue(mockTransfer);

      const request = {
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.BSC,
        sourceAddress,
        targetAddress,
        amount: '1000000000000000000000000000',
      };

      const result = await service.initiateTransfer(request);

      expect(result.transfer.amount).toBe(mockTransfer.amount);
    });

    it('should include token address when provided', async () => {
      const tokenAddress = generateAddress();
      const mockTransfer = testDataFactory.createPendingTransfer({
        tokenAddress,
      });
      const sourceAddress = generateAddress();
      const targetAddress = generateAddress();

      mockPrisma.crossChainTransfer.create.mockResolvedValue(mockTransfer);

      const request = {
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.BSC,
        sourceAddress,
        targetAddress,
        amount: '1000000000000000000',
        tokenAddress,
      };

      const result = await service.initiateTransfer(request);

      expect(result.transfer.tokenAddress).toBe(tokenAddress);
    });

    it('should validate message structure', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();
      const sourceAddress = generateAddress();
      const targetAddress = generateAddress();

      mockPrisma.crossChainTransfer.create.mockResolvedValue(mockTransfer);

      const request = {
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.BSC,
        sourceAddress,
        targetAddress,
        amount: '1000000000000000000',
      };

      const result = await service.initiateTransfer(request);

      expect(result.message.sourceChainId).toBe(request.sourceChainId);
      expect(result.message.targetChainId).toBe(request.targetChainId);
      expect(result.message.sourceAddress).toBe(request.sourceAddress);
      expect(result.message.targetAddress).toBe(request.targetAddress);
      expect(result.message.amount).toBe(request.amount);
      expect(result.message.nonce).toBeDefined();
      expect(result.message.timestamp).toBeDefined();
    });
  });

  describe('confirmLock', () => {
    it('should confirm lock successfully with valid signatures', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();
      const updatedTransfer = { ...mockTransfer, status: 'LOCKED' };
      const txHash = generateTransactionHash();
      const signatures = [
        {
          signer: DEFAULT_MULTISIG_OWNERS[0],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
        {
          signer: DEFAULT_MULTISIG_OWNERS[1],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
      ];

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockCrypto.verifySignature.mockReturnValue(true);
      mockPrisma.crossChainTransfer.update.mockResolvedValue(updatedTransfer);

      const result = await service.confirmLock(mockTransfer.id, txHash, signatures);

      expect(result.transfer.status).toBe('LOCKED');
      expect(result.canExecute).toBe(true);
      expect(mockPrisma.crossChainTransfer.update).toHaveBeenCalledWith({
        where: { id: mockTransfer.id },
        data: {
          status: 'LOCKED',
          sourceTxHash: txHash,
          signatures: expect.any(Array),
        },
      });
    });

    it('should throw NotFoundError when transfer does not exist', async () => {
      const nonExistentId = generateCuid();
      const txHash = generateTransactionHash();
      const signatures = [
        {
          signer: DEFAULT_MULTISIG_OWNERS[0],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
        {
          signer: DEFAULT_MULTISIG_OWNERS[1],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
      ];

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(null);

      await expect(
        service.confirmLock(nonExistentId, txHash, signatures)
      ).rejects.toThrow(NotFoundError);
    });

    it('should throw error when transfer is not in PENDING state', async () => {
      const mockTransfer = testDataFactory.createLockedTransfer();
      const txHash = generateTransactionHash();
      const signatures = [
        {
          signer: DEFAULT_MULTISIG_OWNERS[0],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
      ];

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(
        service.confirmLock(mockTransfer.id, txHash, signatures)
      ).rejects.toThrow('Transfer is not in pending state');
    });

    it('should throw ValidationError when insufficient signatures', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();
      const txHash = generateTransactionHash();
      const signatures = [
        {
          signer: DEFAULT_MULTISIG_OWNERS[0],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
      ];

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(
        service.confirmLock(mockTransfer.id, txHash, signatures)
      ).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when signatures are invalid', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();
      const txHash = generateTransactionHash();
      const signatures = [
        {
          signer: DEFAULT_MULTISIG_OWNERS[0],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
        {
          signer: DEFAULT_MULTISIG_OWNERS[1],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
      ];

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockCrypto.verifySignature.mockReturnValue(false);

      await expect(
        service.confirmLock(mockTransfer.id, txHash, signatures)
      ).rejects.toThrow(ValidationError);
    });

    it('should ignore duplicate signers', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();
      const updatedTransfer = { ...mockTransfer, status: 'LOCKED' };
      const txHash = generateTransactionHash();
      const sameSigner = DEFAULT_MULTISIG_OWNERS[0];
      const signatures = [
        {
          signer: sameSigner,
          signature: generateSignature(),
          timestamp: Date.now(),
        },
        {
          signer: sameSigner.toLowerCase(),
          signature: generateSignature(),
          timestamp: Date.now(),
        },
      ];

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockCrypto.verifySignature.mockReturnValue(true);
      mockPrisma.crossChainTransfer.update.mockResolvedValue(updatedTransfer);

      await expect(
        service.confirmLock(mockTransfer.id, txHash, signatures)
      ).rejects.toThrow(ValidationError);
    });

    it('should reject signatures from unauthorized signers', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();
      const txHash = generateTransactionHash();
      const unauthorizedSigner = generateAddress();
      const signatures = [
        {
          signer: unauthorizedSigner,
          signature: generateSignature(),
          timestamp: Date.now(),
        },
        {
          signer: DEFAULT_MULTISIG_OWNERS[1],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
      ];

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockCrypto.verifySignature.mockReturnValue(true);

      const result = await service.confirmLock(mockTransfer.id, txHash, signatures);

      expect(result.canExecute).toBe(true);
    });
  });

  describe('validateMessage', () => {
    it('should validate message successfully with valid proof', async () => {
      const mockTransfer = testDataFactory.createLockedTransfer();
      const updatedTransfer = { ...mockTransfer, status: 'VALIDATED' };
      const proof = {
        blockNumber: 123456,
        transactionIndex: 5,
        blockHash: generateMessageHash(),
      };

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockPrisma.crossChainTransfer.update.mockResolvedValue(updatedTransfer);

      const result = await service.validateMessage(mockTransfer.id, proof);

      expect(result.transfer.status).toBe('VALIDATED');
      expect(result.readyForMinting).toBe(true);
    });

    it('should throw NotFoundError when transfer does not exist', async () => {
      const nonExistentId = generateCuid();
      const proof = { blockNumber: 123456, transactionIndex: 5 };

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(null);

      await expect(
        service.validateMessage(nonExistentId, proof)
      ).rejects.toThrow(NotFoundError);
    });

    it('should throw error when transfer is not in LOCKED state', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();
      const proof = { blockNumber: 123456, transactionIndex: 5 };

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(
        service.validateMessage(mockTransfer.id, proof)
      ).rejects.toThrow('Transfer must be locked first');
    });

    it('should reject when proof is missing blockNumber', async () => {
      const mockTransfer = testDataFactory.createLockedTransfer();
      const proof = {
        transactionIndex: 5,
      };

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(
        service.validateMessage(mockTransfer.id, proof)
      ).rejects.toThrow('Invalid cross-chain proof');
    });

    it('should reject when proof is missing transactionIndex', async () => {
      const mockTransfer = testDataFactory.createLockedTransfer();
      const proof = {
        blockNumber: 123456,
      };

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(
        service.validateMessage(mockTransfer.id, proof)
      ).rejects.toThrow('Invalid cross-chain proof');
    });

    it('should reject when proof is null', async () => {
      const mockTransfer = testDataFactory.createLockedTransfer();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(
        service.validateMessage(mockTransfer.id, null)
      ).rejects.toThrow('Invalid cross-chain proof');
    });
  });

  describe('executeMint', () => {
    it('should execute mint successfully', async () => {
      const mockTransfer = testDataFactory.createValidatedTransfer();
      const txHash = generateTransactionHash();
      const updatedTransfer = {
        ...mockTransfer,
        status: 'MINTED',
        targetTxHash: txHash,
      };

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockPrisma.crossChainTransfer.update.mockResolvedValue(updatedTransfer);

      const result = await service.executeMint(mockTransfer.id);

      expect(result.transfer.status).toBe('MINTED');
      expect(result.mintTransaction.txHash).toBe(txHash);
      expect(result.mintTransaction.chainId).toBe(mockTransfer.targetChainId);
    });

    it('should throw NotFoundError when transfer does not exist', async () => {
      const nonExistentId = generateCuid();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(null);

      await expect(service.executeMint(nonExistentId)).rejects.toThrow(NotFoundError);
    });

    it('should throw error when transfer is not in VALIDATED state', async () => {
      const mockTransfer = testDataFactory.createLockedTransfer();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(service.executeMint(mockTransfer.id)).rejects.toThrow(
        'Transfer must be validated first'
      );
    });

    it('should not allow minting from PENDING state', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(service.executeMint(mockTransfer.id)).rejects.toThrow(
        'Transfer must be validated first'
      );
    });
  });

  describe('confirmTransfer', () => {
    it('should confirm transfer successfully', async () => {
      const mockTransfer = testDataFactory.createMintedTransfer();
      const updatedTransfer = { ...mockTransfer, status: 'CONFIRMED' };

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockPrisma.crossChainTransfer.update.mockResolvedValue(updatedTransfer);

      const result = await service.confirmTransfer(mockTransfer.id);

      expect(result.transfer.status).toBe('CONFIRMED');
      expect(result.completed).toBe(true);
    });

    it('should throw NotFoundError when transfer does not exist', async () => {
      const nonExistentId = generateCuid();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(null);

      await expect(service.confirmTransfer(nonExistentId)).rejects.toThrow(NotFoundError);
    });

    it('should throw error when transfer is not in MINTED state', async () => {
      const mockTransfer = testDataFactory.createValidatedTransfer();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(service.confirmTransfer(mockTransfer.id)).rejects.toThrow(
        'Transfer must be minted first'
      );
    });
  });

  describe('getTransfer', () => {
    it('should return transfer details', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      const result = await service.getTransfer(mockTransfer.id);

      expect(result.id).toBe(mockTransfer.id);
      expect(result.sourceChainId).toBe(mockTransfer.sourceChainId);
      expect(result.targetChainId).toBe(mockTransfer.targetChainId);
    });

    it('should throw NotFoundError when transfer does not exist', async () => {
      const nonExistentId = generateCuid();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(null);

      await expect(service.getTransfer(nonExistentId)).rejects.toThrow(NotFoundError);
    });
  });

  describe('getTransfers', () => {
    it('should return paginated transfers', async () => {
      const transfers = testDataFactory.createTransferList(5);

      mockPrisma.crossChainTransfer.count.mockResolvedValue(5);
      mockPrisma.crossChainTransfer.findMany.mockResolvedValue(transfers);

      const result = await service.getTransfers({ page: 1, pageSize: 10 });

      expect(result.total).toBe(5);
      expect(result.items.length).toBe(5);
    });

    it('should filter by sourceChainId', async () => {
      const transfers = testDataFactory.createTransferList(3, {
        sourceChainId: CHAIN_IDS.ETHEREUM,
      });

      mockPrisma.crossChainTransfer.count.mockResolvedValue(3);
      mockPrisma.crossChainTransfer.findMany.mockResolvedValue(transfers);

      await service.getTransfers({ sourceChainId: CHAIN_IDS.ETHEREUM });

      expect(mockPrisma.crossChainTransfer.count).toHaveBeenCalledWith({
        where: { sourceChainId: CHAIN_IDS.ETHEREUM },
      });
    });

    it('should filter by targetChainId', async () => {
      const transfers = testDataFactory.createTransferList(3, {
        targetChainId: CHAIN_IDS.POLYGON,
      });

      mockPrisma.crossChainTransfer.count.mockResolvedValue(3);
      mockPrisma.crossChainTransfer.findMany.mockResolvedValue(transfers);

      await service.getTransfers({ targetChainId: CHAIN_IDS.POLYGON });

      expect(mockPrisma.crossChainTransfer.count).toHaveBeenCalledWith({
        where: { targetChainId: CHAIN_IDS.POLYGON },
      });
    });

    it('should filter by sourceAddress', async () => {
      const sourceAddress = generateAddress();
      const transfers = testDataFactory.createTransferList(2, { sourceAddress });

      mockPrisma.crossChainTransfer.count.mockResolvedValue(2);
      mockPrisma.crossChainTransfer.findMany.mockResolvedValue(transfers);

      await service.getTransfers({ sourceAddress });

      expect(mockPrisma.crossChainTransfer.count).toHaveBeenCalledWith({
        where: { sourceAddress },
      });
    });

    it('should filter by status', async () => {
      const transfers = testDataFactory.createTransferList(4, { status: 'PENDING' });

      mockPrisma.crossChainTransfer.count.mockResolvedValue(4);
      mockPrisma.crossChainTransfer.findMany.mockResolvedValue(transfers);

      await service.getTransfers({ status: 'PENDING' });

      expect(mockPrisma.crossChainTransfer.count).toHaveBeenCalledWith({
        where: { status: 'PENDING' },
      });
    });

    it('should combine multiple filters', async () => {
      const transfers = testDataFactory.createTransferList(2);

      mockPrisma.crossChainTransfer.count.mockResolvedValue(2);
      mockPrisma.crossChainTransfer.findMany.mockResolvedValue(transfers);

      await service.getTransfers({
        sourceChainId: CHAIN_IDS.ETHEREUM,
        targetChainId: CHAIN_IDS.BSC,
        status: 'PENDING',
      });

      expect(mockPrisma.crossChainTransfer.count).toHaveBeenCalledWith({
        where: {
          sourceChainId: CHAIN_IDS.ETHEREUM,
          targetChainId: CHAIN_IDS.BSC,
          status: 'PENDING',
        },
      });
    });

    it('should handle empty results', async () => {
      mockPrisma.crossChainTransfer.count.mockResolvedValue(0);
      mockPrisma.crossChainTransfer.findMany.mockResolvedValue([]);

      const result = await service.getTransfers({ sourceChainId: 99999 });

      expect(result.total).toBe(0);
      expect(result.items).toEqual([]);
    });
  });

  describe('getPendingTransfers', () => {
    it('should return pending transfers for a chain', async () => {
      const pendingTransfers = [
        testDataFactory.createPendingTransfer({ sourceChainId: CHAIN_IDS.ETHEREUM }),
        testDataFactory.createValidatedTransfer({ targetChainId: CHAIN_IDS.ETHEREUM }),
      ];

      mockPrisma.crossChainTransfer.findMany.mockResolvedValue(pendingTransfers);

      const result = await service.getPendingTransfers(CHAIN_IDS.ETHEREUM);

      expect(result.length).toBe(2);
      expect(mockPrisma.crossChainTransfer.findMany).toHaveBeenCalledWith({
        where: {
          OR: [
            { sourceChainId: CHAIN_IDS.ETHEREUM, status: 'PENDING' },
            { targetChainId: CHAIN_IDS.ETHEREUM, status: 'VALIDATED' },
          ],
        },
        orderBy: { createdAt: 'asc' },
      });
    });

    it('should return empty array when no pending transfers', async () => {
      mockPrisma.crossChainTransfer.findMany.mockResolvedValue([]);

      const result = await service.getPendingTransfers(CHAIN_IDS.ETHEREUM);

      expect(result).toEqual([]);
    });
  });

  describe('State Transitions', () => {
    it('should enforce correct state progression PENDING -> LOCKED', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();
      const txHash = generateTransactionHash();
      const signatures = [
        {
          signer: DEFAULT_MULTISIG_OWNERS[0],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
        {
          signer: DEFAULT_MULTISIG_OWNERS[1],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
      ];

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockCrypto.verifySignature.mockReturnValue(true);
      mockPrisma.crossChainTransfer.update.mockResolvedValue({
        ...mockTransfer,
        status: 'LOCKED',
      });

      const result = await service.confirmLock(mockTransfer.id, txHash, signatures);
      expect(result.transfer.status).toBe('LOCKED');
    });

    it('should enforce correct state progression LOCKED -> VALIDATED', async () => {
      const mockTransfer = testDataFactory.createLockedTransfer();
      const proof = { blockNumber: 123456, transactionIndex: 5 };

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockPrisma.crossChainTransfer.update.mockResolvedValue({
        ...mockTransfer,
        status: 'VALIDATED',
      });

      const result = await service.validateMessage(mockTransfer.id, proof);
      expect(result.transfer.status).toBe('VALIDATED');
    });

    it('should enforce correct state progression VALIDATED -> MINTED', async () => {
      const mockTransfer = testDataFactory.createValidatedTransfer();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockPrisma.crossChainTransfer.update.mockResolvedValue({
        ...mockTransfer,
        status: 'MINTED',
      });

      const result = await service.executeMint(mockTransfer.id);
      expect(result.transfer.status).toBe('MINTED');
    });

    it('should enforce correct state progression MINTED -> CONFIRMED', async () => {
      const mockTransfer = testDataFactory.createMintedTransfer();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);
      mockPrisma.crossChainTransfer.update.mockResolvedValue({
        ...mockTransfer,
        status: 'CONFIRMED',
      });

      const result = await service.confirmTransfer(mockTransfer.id);
      expect(result.transfer.status).toBe('CONFIRMED');
    });

    it('should prevent skipping states', async () => {
      const mockTransfer = testDataFactory.createPendingTransfer();

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(service.executeMint(mockTransfer.id)).rejects.toThrow();
    });

    it('should prevent regressing states', async () => {
      const mockTransfer = testDataFactory.createMintedTransfer();
      const proof = { blockNumber: 123456, transactionIndex: 5 };

      mockPrisma.crossChainTransfer.findUnique.mockResolvedValue(mockTransfer);

      await expect(service.validateMessage(mockTransfer.id, proof)).rejects.toThrow();
    });
  });
});
