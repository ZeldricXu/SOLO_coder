import { MultisigCoordinatorService } from '@core/usecases/multisigCoordinator.usecase';
import { TransactionBuilderService } from '@core/usecases/transactionBuilder.usecase';
import { MockLogger } from '../__mocks__/mockPorts';
import { NotFoundError, ConflictError, SignatureVerificationError } from '@shared/errors';
import type { MultisigStrategy } from '@core/ports/transactionBuilder.port';

describe('MultisigCoordinatorService', () => {
  let coordinator: MultisigCoordinatorService;
  let builder: TransactionBuilderService;
  let mockLogger: MockLogger;
  let mockStrategy: jest.Mocked<MultisigStrategy>;

  const owners = [
    '0x0000000000000000000000000000000000000001',
    '0x0000000000000000000000000000000000000002',
    '0x0000000000000000000000000000000000000003',
  ];

  beforeEach(() => {
    mockLogger = new MockLogger();
    mockStrategy = {
      id: 'test-strategy',
      name: 'Test Multisig',
      threshold: 2,
      owners,
      validateSignatures: jest.fn().mockResolvedValue(true),
      combineSignatures: jest.fn().mockResolvedValue('0xcombined' as `0x${string}`),
    };

    builder = new TransactionBuilderService(mockLogger);
    builder.setMultisigStrategy(mockStrategy);

    coordinator = new MultisigCoordinatorService(
      builder,
      mockStrategy,
      mockLogger
    );
  });

  describe('createProposal', () => {
    it('should create a new proposal', async () => {
      const result = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000002',
        BigInt('1000000000000000000'),
        '0x' as `0x${string}`
      );

      expect(result.proposalId).toBeDefined();
      expect(result.transactionHash).toBeDefined();
      expect(result.nonce).toBe(BigInt(0));
    });

    it('should increment nonce for subsequent proposals', async () => {
      const first = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000002',
        BigInt(0),
        '0x' as `0x${string}`
      );

      const second = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000003',
        BigInt(0),
        '0x' as `0x${string}`
      );

      expect(first.nonce).toBe(BigInt(0));
      expect(second.nonce).toBe(BigInt(1));
    });
  });

  describe('collectSignature', () => {
    it('should collect valid signature', async () => {
      const proposal = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000002',
        BigInt(0),
        '0x' as `0x${string}`
      );

      const result = await coordinator.collectSignature(
        proposal.proposalId,
        owners[0],
        '0x' + 'a'.repeat(130) as `0x${string}`
      );

      expect(result.currentSignatures).toBe(1);
      expect(result.threshold).toBe(2);
      expect(result.isReady).toBe(false);
    });

    it('should mark proposal as ready when threshold reached', async () => {
      const proposal = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000002',
        BigInt(0),
        '0x' as `0x${string}`
      );

      await coordinator.collectSignature(
        proposal.proposalId,
        owners[0],
        '0x' + 'a'.repeat(130) as `0x${string}`
      );

      const result = await coordinator.collectSignature(
        proposal.proposalId,
        owners[1],
        '0x' + 'b'.repeat(130) as `0x${string}`
      );

      expect(result.currentSignatures).toBe(2);
      expect(result.isReady).toBe(true);
    });

    it('should throw error for non-owner signer', async () => {
      const proposal = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000002',
        BigInt(0),
        '0x' as `0x${string}`
      );

      await expect(
        coordinator.collectSignature(
          proposal.proposalId,
          '0x0000000000000000000000000000000000000099',
          '0x' + 'a'.repeat(130) as `0x${string}`
        )
      ).rejects.toThrow(SignatureVerificationError);
    });

    it('should throw error for invalid signature', async () => {
      mockStrategy.validateSignatures.mockResolvedValue(false);

      const proposal = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000002',
        BigInt(0),
        '0x' as `0x${string}`
      );

      await expect(
        coordinator.collectSignature(
          proposal.proposalId,
          owners[0],
          '0x' + 'a'.repeat(130) as `0x${string}`
        )
      ).rejects.toThrow(SignatureVerificationError);
    });

    it('should throw error for non-existent proposal', async () => {
      await expect(
        coordinator.collectSignature(
          'non-existent-proposal',
          owners[0],
          '0x' + 'a'.repeat(130) as `0x${string}`
        )
      ).rejects.toThrow(NotFoundError);
    });
  });

  describe('executeProposal', () => {
    it('should execute ready proposal', async () => {
      const proposal = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000002',
        BigInt(0),
        '0x' as `0x${string}`
      );

      await coordinator.collectSignature(
        proposal.proposalId,
        owners[0],
        '0x' + 'a'.repeat(130) as `0x${string}`
      );
      await coordinator.collectSignature(
        proposal.proposalId,
        owners[1],
        '0x' + 'b'.repeat(130) as `0x${string}`
      );

      const result = await coordinator.executeProposal(proposal.proposalId);

      expect(result.transactionHash).toBeDefined();
      expect(mockStrategy.combineSignatures).toHaveBeenCalled();
    });

    it('should throw error for non-ready proposal', async () => {
      const proposal = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000002',
        BigInt(0),
        '0x' as `0x${string}`
      );

      await coordinator.collectSignature(
        proposal.proposalId,
        owners[0],
        '0x' + 'a'.repeat(130) as `0x${string}`
      );

      await expect(coordinator.executeProposal(proposal.proposalId)).rejects.toThrow(
        ConflictError
      );
    });
  });

  describe('getProposal', () => {
    it('should return proposal details', async () => {
      const created = await coordinator.createProposal(
        '0x0000000000000000000000000000000000000001',
        1,
        '0x0000000000000000000000000000000000000002',
        BigInt('1000000000000000000'),
        '0x' as `0x${string}`
      );

      const proposal = await coordinator.getProposal(created.proposalId);

      expect(proposal).toBeDefined();
      expect(proposal?.id).toBe(created.proposalId);
      expect(proposal?.to).toBe('0x0000000000000000000000000000000000000002');
      expect(proposal?.value).toBe(BigInt('1000000000000000000'));
      expect(proposal?.status).toBe('pending');
    });

    it('should return null for non-existent proposal', async () => {
      const proposal = await coordinator.getProposal('non-existent');
      expect(proposal).toBeNull();
    });
  });
});
