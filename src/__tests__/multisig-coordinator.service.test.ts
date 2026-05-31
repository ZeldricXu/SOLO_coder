import { MultisigCoordinatorService } from '../../modules/multisig-coordinator/multisigCoordinator.service';
import { testDataFactory } from '../factories';
import {
  CHAIN_IDS,
  generateAddress,
  generateCuid,
  generateSignature,
  DEFAULT_MULTISIG_OWNERS,
  DEFAULT_THRESHOLD,
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

describe('MultisigCoordinatorService', () => {
  let service: MultisigCoordinatorService;
  let mockPrisma: any;
  let mockCache: any;
  let mockCrypto: any;

  beforeEach(() => {
    mockPrisma = {
      multisigProposal: {
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

    service = new MultisigCoordinatorService();
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  describe('createProposal', () => {
    it('should create proposal successfully with valid parameters', async () => {
      const mockProposal = testDataFactory.createPendingProposal();
      const toAddress = generateAddress();

      mockPrisma.multisigProposal.findMany.mockResolvedValue([]);
      mockPrisma.multisigProposal.create.mockResolvedValue(mockProposal);
      mockCache.set.mockResolvedValue(true);

      const request = {
        walletId: mockProposal.walletId,
        chainId: CHAIN_IDS.ETHEREUM,
        type: 'TRANSFER' as const,
        data: {
          to: toAddress,
          value: '1000000000000000000',
          data: '0x',
          operation: 0,
        },
      };

      const result = await service.createProposal(request);

      expect(result.id).toBe(mockProposal.id);
      expect(result.status).toBe('PENDING');
      expect(result.nonce).toBe(0);
      expect(result.threshold).toBe(DEFAULT_THRESHOLD);
      expect(mockPrisma.multisigProposal.create).toHaveBeenCalled();
      expect(mockCache.set).toHaveBeenCalled();
    });

    it('should increment nonce for new proposals', async () => {
      const lastProposal = testDataFactory.createPendingProposal({ nonce: 5 });
      const newProposal = testDataFactory.createPendingProposal({ nonce: 6 });
      const toAddress = generateAddress();

      mockPrisma.multisigProposal.findMany.mockResolvedValue([lastProposal]);
      mockPrisma.multisigProposal.create.mockResolvedValue(newProposal);
      mockCache.set.mockResolvedValue(true);

      const request = {
        walletId: lastProposal.walletId,
        chainId: CHAIN_IDS.ETHEREUM,
        type: 'TRANSFER' as const,
        data: {
          to: toAddress,
          value: '1000000000000000000',
          data: '0x',
          operation: 0,
        },
      };

      await service.createProposal(request);

      expect(mockPrisma.multisigProposal.create).toHaveBeenCalledWith(
        expect.objectContaining({
          data: expect.objectContaining({
            nonce: 6,
          }),
        })
      );
    });

    it('should throw ValidationError when walletId is missing', async () => {
      const toAddress = generateAddress();

      const request = {
        walletId: '',
        chainId: CHAIN_IDS.ETHEREUM,
        type: 'TRANSFER' as const,
        data: {
          to: toAddress,
          value: '1000000000000000000',
        },
      };

      await expect(service.createProposal(request)).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when chainId is invalid', async () => {
      const toAddress = generateAddress();

      const request = {
        walletId: `wallet_${Date.now()}`,
        chainId: 0,
        type: 'TRANSFER' as const,
        data: {
          to: toAddress,
          value: '1000000000000000000',
        },
      };

      await expect(service.createProposal(request)).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when recipient address is missing', async () => {
      const request = {
        walletId: `wallet_${Date.now()}`,
        chainId: CHAIN_IDS.ETHEREUM,
        type: 'TRANSFER' as const,
        data: {
          value: '1000000000000000000',
        } as any,
      };

      await expect(service.createProposal(request)).rejects.toThrow(ValidationError);
    });

    it('should throw ValidationError when recipient address is invalid', async () => {
      const request = {
        walletId: `wallet_${Date.now()}`,
        chainId: CHAIN_IDS.ETHEREUM,
        type: 'TRANSFER' as const,
        data: {
          to: 'invalid_address',
          value: '1000000000000000000',
        },
      };

      await expect(service.createProposal(request)).rejects.toThrow(ValidationError);
    });

    it('should support different proposal types', async () => {
      const mockProposal = testDataFactory.createPendingProposal({ type: 'APPROVE' });
      const toAddress = generateAddress();

      mockPrisma.multisigProposal.findMany.mockResolvedValue([]);
      mockPrisma.multisigProposal.create.mockResolvedValue(mockProposal);
      mockCache.set.mockResolvedValue(true);

      const request = {
        walletId: mockProposal.walletId,
        chainId: CHAIN_IDS.ETHEREUM,
        type: 'APPROVE' as const,
        data: {
          to: toAddress,
          value: '1000000000000000000',
        },
      };

      const result = await service.createProposal(request);

      expect(result.type).toBe('APPROVE');
    });

    it('should cache the created proposal', async () => {
      const mockProposal = testDataFactory.createPendingProposal();
      const toAddress = generateAddress();

      mockPrisma.multisigProposal.findMany.mockResolvedValue([]);
      mockPrisma.multisigProposal.create.mockResolvedValue(mockProposal);
      mockCache.set.mockResolvedValue(true);

      const request = {
        walletId: mockProposal.walletId,
        chainId: CHAIN_IDS.ETHEREUM,
        type: 'TRANSFER' as const,
        data: {
          to: toAddress,
          value: '1000000000000000000',
        },
      };

      await service.createProposal(request);

      expect(mockCache.set).toHaveBeenCalledWith(
        `proposal:${mockProposal.id}`,
        expect.any(Object),
        expect.any(Number)
      );
    });
  });

  describe('signProposal', () => {
    it('should sign proposal successfully with valid signature', async () => {
      const mockProposal = testDataFactory.createPendingProposal({
        signatures: [],
      });
      const signer = DEFAULT_MULTISIG_OWNERS[0];
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockCrypto.verifySignature.mockReturnValue(true);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        signatures: [
          {
            signer,
            signature,
            timestamp: Date.now(),
          },
        ],
      });
      mockCache.delete.mockResolvedValue(true);

      const result = await service.signProposal({
        proposalId: mockProposal.id,
        signer,
        signature,
      });

      expect(result.signatures.length).toBe(1);
      expect(result.status).toBe('PENDING');
      expect(mockCache.delete).toHaveBeenCalled();
    });

    it('should mark proposal as APPROVED when threshold is met', async () => {
      const mockProposal = testDataFactory.createPartiallySignedProposal({}, 1);
      const signer = DEFAULT_MULTISIG_OWNERS[1];
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockCrypto.verifySignature.mockReturnValue(true);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        status: 'APPROVED',
        signatures: [
          ...mockProposal.signatures,
          {
            signer,
            signature,
            timestamp: Date.now(),
          },
        ],
      });
      mockCache.delete.mockResolvedValue(true);

      const result = await service.signProposal({
        proposalId: mockProposal.id,
        signer,
        signature,
      });

      expect(result.status).toBe('APPROVED');
      expect(result.signatures.length).toBe(2);
    });

    it('should throw NotFoundError when proposal does not exist', async () => {
      const nonExistentId = generateCuid();
      const signer = DEFAULT_MULTISIG_OWNERS[0];
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(null);

      await expect(
        service.signProposal({
          proposalId: nonExistentId,
          signer,
          signature,
        })
      ).rejects.toThrow(NotFoundError);
    });

    it('should throw error when proposal is not in PENDING state', async () => {
      const mockProposal = testDataFactory.createApprovedProposal();
      const signer = DEFAULT_MULTISIG_OWNERS[2];
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      await expect(
        service.signProposal({
          proposalId: mockProposal.id,
          signer,
          signature,
        })
      ).rejects.toThrow('Proposal is not in pending state');
    });

    it('should throw error when signer has already signed', async () => {
      const existingSigner = DEFAULT_MULTISIG_OWNERS[0];
      const mockProposal = testDataFactory.createPartiallySignedProposal({}, 1);
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      await expect(
        service.signProposal({
          proposalId: mockProposal.id,
          signer: existingSigner,
          signature,
        })
      ).rejects.toThrow('Signer has already signed this proposal');
    });

    it('should throw error when signer is not an authorized owner', async () => {
      const mockProposal = testDataFactory.createPendingProposal();
      const unauthorizedSigner = generateAddress();
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      await expect(
        service.signProposal({
          proposalId: mockProposal.id,
          signer: unauthorizedSigner,
          signature,
        })
      ).rejects.toThrow('Signer is not an authorized owner');
    });

    it('should throw ValidationError when signature is invalid', async () => {
      const mockProposal = testDataFactory.createPendingProposal();
      const signer = DEFAULT_MULTISIG_OWNERS[0];
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockCrypto.verifySignature.mockReturnValue(false);

      await expect(
        service.signProposal({
          proposalId: mockProposal.id,
          signer,
          signature,
        })
      ).rejects.toThrow(ValidationError);
    });

    it('should handle case-insensitive signer comparison', async () => {
      const mockProposal = testDataFactory.createPendingProposal();
      const signer = DEFAULT_MULTISIG_OWNERS[0].toLowerCase();
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockCrypto.verifySignature.mockReturnValue(true);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        signatures: [
          {
            signer: DEFAULT_MULTISIG_OWNERS[0],
            signature,
            timestamp: Date.now(),
          },
        ],
      });
      mockCache.delete.mockResolvedValue(true);

      const result = await service.signProposal({
        proposalId: mockProposal.id,
        signer,
        signature,
      });

      expect(result.signatures.length).toBe(1);
    });

    it('should invalidate cache after signing', async () => {
      const mockProposal = testDataFactory.createPendingProposal();
      const signer = DEFAULT_MULTISIG_OWNERS[0];
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockCrypto.verifySignature.mockReturnValue(true);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        signatures: [
          {
            signer,
            signature,
            timestamp: Date.now(),
          },
        ],
      });
      mockCache.delete.mockResolvedValue(true);

      await service.signProposal({
        proposalId: mockProposal.id,
        signer,
        signature,
      });

      expect(mockCache.delete).toHaveBeenCalledWith(`proposal:${mockProposal.id}`);
    });
  });

  describe('executeProposal', () => {
    it('should execute approved proposal successfully', async () => {
      const mockProposal = testDataFactory.createApprovedProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        status: 'EXECUTED',
        executedTxHash: expect.any(String),
      });
      mockCache.delete.mockResolvedValue(true);

      const result = await service.executeProposal({
        proposalId: mockProposal.id,
      });

      expect(result.proposal.status).toBe('EXECUTED');
      expect(result.txHash).toBeDefined();
      expect(result.txHash.startsWith('0x')).toBe(true);
    });

    it('should throw NotFoundError when proposal does not exist', async () => {
      const nonExistentId = generateCuid();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(null);

      await expect(
        service.executeProposal({
          proposalId: nonExistentId,
        })
      ).rejects.toThrow(NotFoundError);
    });

    it('should throw error when proposal is not APPROVED', async () => {
      const mockProposal = testDataFactory.createPendingProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      await expect(
        service.executeProposal({
          proposalId: mockProposal.id,
        })
      ).rejects.toThrow('Proposal must be approved before execution');
    });

    it('should throw error when signatures are below threshold', async () => {
      const mockProposal = {
        ...testDataFactory.createPendingProposal(),
        status: 'APPROVED',
        threshold: 3,
        signatures: [
          {
            signer: DEFAULT_MULTISIG_OWNERS[0],
            signature: generateSignature(),
            timestamp: Date.now(),
          },
        ],
      };

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      await expect(
        service.executeProposal({
          proposalId: mockProposal.id,
        })
      ).rejects.toThrow('Insufficient signatures for execution');
    });

    it('should invalidate cache after execution', async () => {
      const mockProposal = testDataFactory.createApprovedProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        status: 'EXECUTED',
      });
      mockCache.delete.mockResolvedValue(true);

      await service.executeProposal({
        proposalId: mockProposal.id,
      });

      expect(mockCache.delete).toHaveBeenCalledWith(`proposal:${mockProposal.id}`);
    });
  });

  describe('getProposal', () => {
    it('should return proposal from cache when available', async () => {
      const mockProposal = testDataFactory.createPendingProposal();

      mockCache.get.mockResolvedValue(mockProposal);

      const result = await service.getProposal(mockProposal.id);

      expect(result).toEqual(mockProposal);
      expect(mockPrisma.multisigProposal.findUnique).not.toHaveBeenCalled();
    });

    it('should fetch from database when cache miss', async () => {
      const mockProposal = testDataFactory.createPendingProposal();

      mockCache.get.mockResolvedValue(null);
      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockCache.set.mockResolvedValue(true);

      const result = await service.getProposal(mockProposal.id);

      expect(result.id).toBe(mockProposal.id);
      expect(mockPrisma.multisigProposal.findUnique).toHaveBeenCalled();
      expect(mockCache.set).toHaveBeenCalled();
    });

    it('should throw NotFoundError when proposal does not exist', async () => {
      const nonExistentId = generateCuid();

      mockCache.get.mockResolvedValue(null);
      mockPrisma.multisigProposal.findUnique.mockResolvedValue(null);

      await expect(service.getProposal(nonExistentId)).rejects.toThrow(NotFoundError);
    });
  });

  describe('getProposals', () => {
    it('should return paginated proposals', async () => {
      const proposals = testDataFactory.createProposalList(5);

      mockPrisma.multisigProposal.count.mockResolvedValue(5);
      mockPrisma.multisigProposal.findMany.mockResolvedValue(proposals);

      const result = await service.getProposals({ page: 1, pageSize: 10 });

      expect(result.total).toBe(5);
      expect(result.items.length).toBe(5);
    });

    it('should filter by walletId', async () => {
      const walletId = `wallet_${Date.now()}`;
      const proposals = testDataFactory.createProposalList(3, { walletId });

      mockPrisma.multisigProposal.count.mockResolvedValue(3);
      mockPrisma.multisigProposal.findMany.mockResolvedValue(proposals);

      await service.getProposals({ walletId });

      expect(mockPrisma.multisigProposal.count).toHaveBeenCalledWith({
        where: { walletId },
      });
    });

    it('should filter by chainId', async () => {
      const proposals = testDataFactory.createProposalList(3, { chainId: CHAIN_IDS.BSC });

      mockPrisma.multisigProposal.count.mockResolvedValue(3);
      mockPrisma.multisigProposal.findMany.mockResolvedValue(proposals);

      await service.getProposals({ chainId: CHAIN_IDS.BSC });

      expect(mockPrisma.multisigProposal.count).toHaveBeenCalledWith({
        where: { chainId: CHAIN_IDS.BSC },
      });
    });

    it('should filter by status', async () => {
      const proposals = testDataFactory.createProposalList(4, { status: 'APPROVED' });

      mockPrisma.multisigProposal.count.mockResolvedValue(4);
      mockPrisma.multisigProposal.findMany.mockResolvedValue(proposals);

      await service.getProposals({ status: 'APPROVED' });

      expect(mockPrisma.multisigProposal.count).toHaveBeenCalledWith({
        where: { status: 'APPROVED' },
      });
    });

    it('should filter by type', async () => {
      const proposals = testDataFactory.createProposalList(2, { type: 'TRANSFER' });

      mockPrisma.multisigProposal.count.mockResolvedValue(2);
      mockPrisma.multisigProposal.findMany.mockResolvedValue(proposals);

      await service.getProposals({ type: 'TRANSFER' });

      expect(mockPrisma.multisigProposal.count).toHaveBeenCalledWith({
        where: { type: 'TRANSFER' },
      });
    });

    it('should combine multiple filters', async () => {
      const walletId = `wallet_${Date.now()}`;
      const proposals = testDataFactory.createProposalList(2);

      mockPrisma.multisigProposal.count.mockResolvedValue(2);
      mockPrisma.multisigProposal.findMany.mockResolvedValue(proposals);

      await service.getProposals({
        walletId,
        chainId: CHAIN_IDS.ETHEREUM,
        status: 'PENDING',
      });

      expect(mockPrisma.multisigProposal.count).toHaveBeenCalledWith({
        where: {
          walletId,
          chainId: CHAIN_IDS.ETHEREUM,
          status: 'PENDING',
        },
      });
    });

    it('should handle empty results', async () => {
      mockPrisma.multisigProposal.count.mockResolvedValue(0);
      mockPrisma.multisigProposal.findMany.mockResolvedValue([]);

      const result = await service.getProposals({ walletId: 'non-existent' });

      expect(result.total).toBe(0);
      expect(result.items).toEqual([]);
    });
  });

  describe('getPendingProposals', () => {
    it('should return pending proposals for a wallet', async () => {
      const walletId = `wallet_${Date.now()}`;
      const proposals = testDataFactory.createProposalList(3, {
        walletId,
        status: 'PENDING',
      });

      mockPrisma.multisigProposal.findMany.mockResolvedValue(proposals);

      const result = await service.getPendingProposals(walletId);

      expect(result.length).toBe(3);
      expect(mockPrisma.multisigProposal.findMany).toHaveBeenCalledWith({
        where: {
          walletId,
          status: 'PENDING',
        },
        orderBy: { createdAt: 'asc' },
      });
    });

    it('should return empty array when no pending proposals', async () => {
      mockPrisma.multisigProposal.findMany.mockResolvedValue([]);

      const result = await service.getPendingProposals('wallet_123');

      expect(result).toEqual([]);
    });
  });

  describe('getApprovedProposals', () => {
    it('should return approved proposals for a wallet', async () => {
      const walletId = `wallet_${Date.now()}`;
      const proposals = testDataFactory.createProposalList(2, {
        walletId,
        status: 'APPROVED',
      });

      mockPrisma.multisigProposal.findMany.mockResolvedValue(proposals);

      const result = await service.getApprovedProposals(walletId);

      expect(result.length).toBe(2);
      expect(mockPrisma.multisigProposal.findMany).toHaveBeenCalledWith({
        where: {
          walletId,
          status: 'APPROVED',
        },
        orderBy: { createdAt: 'asc' },
      });
    });
  });

  describe('rejectProposal', () => {
    it('should reject PENDING proposal successfully', async () => {
      const mockProposal = testDataFactory.createPendingProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        status: 'REJECTED',
      });
      mockCache.delete.mockResolvedValue(true);

      const result = await service.rejectProposal(mockProposal.id);

      expect(result.status).toBe('REJECTED');
      expect(mockCache.delete).toHaveBeenCalled();
    });

    it('should reject APPROVED proposal successfully', async () => {
      const mockProposal = testDataFactory.createApprovedProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        status: 'REJECTED',
      });
      mockCache.delete.mockResolvedValue(true);

      const result = await service.rejectProposal(mockProposal.id);

      expect(result.status).toBe('REJECTED');
    });

    it('should throw NotFoundError when proposal does not exist', async () => {
      const nonExistentId = generateCuid();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(null);

      await expect(service.rejectProposal(nonExistentId)).rejects.toThrow(NotFoundError);
    });

    it('should throw error when proposal is already executed', async () => {
      const mockProposal = testDataFactory.createExecutedProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      await expect(service.rejectProposal(mockProposal.id)).rejects.toThrow(
        'Proposal cannot be rejected in current state'
      );
    });

    it('should throw error when proposal is already rejected', async () => {
      const mockProposal = testDataFactory.createRejectedProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      await expect(service.rejectProposal(mockProposal.id)).rejects.toThrow(
        'Proposal cannot be rejected in current state'
      );
    });
  });

  describe('getProposalSignatures', () => {
    it('should return signatures for a proposal', async () => {
      const mockProposal = testDataFactory.createApprovedProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      const result = await service.getProposalSignatures(mockProposal.id);

      expect(result.length).toBe(mockProposal.signatures.length);
    });

    it('should return empty array when no signatures', async () => {
      const mockProposal = testDataFactory.createPendingProposal({ signatures: [] });

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      const result = await service.getProposalSignatures(mockProposal.id);

      expect(result).toEqual([]);
    });

    it('should throw NotFoundError when proposal does not exist', async () => {
      const nonExistentId = generateCuid();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(null);

      await expect(service.getProposalSignatures(nonExistentId)).rejects.toThrow(NotFoundError);
    });
  });

  describe('canExecute', () => {
    it('should return true when signatures meet threshold', async () => {
      const mockProposal = testDataFactory.createApprovedProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      const result = await service.canExecute(mockProposal.id);

      expect(result).toBe(true);
    });

    it('should return false when signatures are below threshold', async () => {
      const mockProposal = testDataFactory.createPartiallySignedProposal({}, 1);

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      const result = await service.canExecute(mockProposal.id);

      expect(result).toBe(false);
    });

    it('should return false when proposal does not exist', async () => {
      const nonExistentId = generateCuid();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(null);

      const result = await service.canExecute(nonExistentId);

      expect(result).toBe(false);
    });
  });

  describe('State Transitions', () => {
    it('should enforce correct state progression PENDING -> APPROVED', async () => {
      const mockProposal = testDataFactory.createPartiallySignedProposal({}, 1);
      const signer = DEFAULT_MULTISIG_OWNERS[1];
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockCrypto.verifySignature.mockReturnValue(true);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        status: 'APPROVED',
        signatures: [
          ...mockProposal.signatures,
          {
            signer,
            signature,
            timestamp: Date.now(),
          },
        ],
      });
      mockCache.delete.mockResolvedValue(true);

      const result = await service.signProposal({
        proposalId: mockProposal.id,
        signer,
        signature,
      });

      expect(result.status).toBe('APPROVED');
    });

    it('should enforce correct state progression APPROVED -> EXECUTED', async () => {
      const mockProposal = testDataFactory.createApprovedProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        status: 'EXECUTED',
      });
      mockCache.delete.mockResolvedValue(true);

      const result = await service.executeProposal({
        proposalId: mockProposal.id,
      });

      expect(result.proposal.status).toBe('EXECUTED');
    });

    it('should allow rejection from PENDING state', async () => {
      const mockProposal = testDataFactory.createPendingProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);
      mockPrisma.multisigProposal.update.mockResolvedValue({
        ...mockProposal,
        status: 'REJECTED',
      });
      mockCache.delete.mockResolvedValue(true);

      const result = await service.rejectProposal(mockProposal.id);

      expect(result.status).toBe('REJECTED');
    });

    it('should prevent execution from PENDING state', async () => {
      const mockProposal = testDataFactory.createPendingProposal();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      await expect(
        service.executeProposal({
          proposalId: mockProposal.id,
        })
      ).rejects.toThrow();
    });

    it('should prevent signing APPROVED proposals', async () => {
      const mockProposal = testDataFactory.createApprovedProposal();
      const signer = DEFAULT_MULTISIG_OWNERS[2];
      const signature = generateSignature();

      mockPrisma.multisigProposal.findUnique.mockResolvedValue(mockProposal);

      await expect(
        service.signProposal({
          proposalId: mockProposal.id,
          signer,
          signature,
        })
      ).rejects.toThrow();
    });
  });
});
