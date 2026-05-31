import { MultisigCoordinatorService } from '@core/usecases/multisigCoordinator.usecase';
import { TransactionBuilderService } from '@core/usecases/transactionBuilder.usecase';
import { MockLogger } from '../__mocks__/mockPorts';
import { NotFoundError, ConflictError, SignatureVerificationError } from '@shared/errors';
import type { Address, HexString, Hash } from '@shared/types';
import type { TransactionSignature } from '@core/domain/blockchain';
import {
  AddressBuilder,
  HashBuilder,
  HexStringBuilder,
  MultisigStrategyBuilder,
  TestTiming,
} from '../builders/testDataBuilders';

describe('MultisigCoordinatorService - Concurrency Isolation Levels', () => {
  let coordinator: MultisigCoordinatorService;
  let transactionBuilder: TransactionBuilderService;
  let mockLogger: MockLogger;
  let owners: Address[];
  let walletAddress: Address;

  beforeEach(() => {
    mockLogger = new MockLogger();
    owners = [
      AddressBuilder.fromSeed(1),
      AddressBuilder.fromSeed(2),
      AddressBuilder.fromSeed(3),
    ];
    walletAddress = AddressBuilder.fromSeed(100);

    const strategy = MultisigStrategyBuilder.simple2of3();
    transactionBuilder = new TransactionBuilderService(mockLogger);
    transactionBuilder.setMultisigStrategy(strategy);

    coordinator = new MultisigCoordinatorService(
      transactionBuilder,
      strategy,
      mockLogger
    );
  });

  describe('Proposal Creation Isolation', () => {
    it('should isolate nonce allocation for different wallets', async () => {
      const wallet1 = AddressBuilder.fromSeed(100);
      const wallet2 = AddressBuilder.fromSeed(200);

      const proposal1 = await coordinator.createProposal(
        wallet1,
        1,
        AddressBuilder.fromSeed(1),
        BigInt('1000000000000000000'),
        '0x' as HexString
      );

      const proposal2 = await coordinator.createProposal(
        wallet2,
        1,
        AddressBuilder.fromSeed(2),
        BigInt('500000000000000000'),
        '0x' as HexString
      );

      expect(proposal1.nonce).toBe(BigInt(0));
      expect(proposal2.nonce).toBe(BigInt(0));
    });

    it('should isolate nonce allocation for different chains', async () => {
      const proposalEth = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const proposalPolygon = await coordinator.createProposal(
        walletAddress,
        137,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );

      expect(proposalEth.nonce).toBe(BigInt(0));
      expect(proposalPolygon.nonce).toBe(BigInt(0));
    });

    it('should increment nonce sequentially for same wallet and chain', async () => {
      const proposal1 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const proposal2 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );

      const proposal3 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(3),
        BigInt(0),
        '0x' as HexString
      );

      expect(proposal1.nonce).toBe(BigInt(0));
      expect(proposal2.nonce).toBe(BigInt(1));
      expect(proposal3.nonce).toBe(BigInt(2));
    });

    it('should maintain unique proposal IDs', async () => {
      const ids = new Set<string>();
      const count = 10;

      for (let i = 0; i < count; i++) {
        const proposal = await coordinator.createProposal(
          walletAddress,
          1,
          AddressBuilder.fromSeed(i + 1),
          BigInt(i * 1000),
          '0x' as HexString
        );
        ids.add(proposal.proposalId);
      }

      expect(ids.size).toBe(count);
    });

    it('should generate unique transaction hashes for different proposals', async () => {
      const hashes = new Set<Hash>();

      const proposal1 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt('1000000000000000000'),
        '0x' as HexString
      );

      const proposal2 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt('2000000000000000000'),
        '0x' as HexString
      );

      hashes.add(proposal1.transactionHash);
      hashes.add(proposal2.transactionHash);

      expect(hashes.size).toBe(2);
    });
  });

  describe('Concurrent Signature Collection - Isolation', () => {
    it('should isolate signature collection between different proposals', async () => {
      const proposal1 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const proposal2 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );

      await coordinator.collectSignature(
        proposal1.proposalId,
        owners[0],
        HexStringBuilder.signature()
      );

      const status1 = await coordinator.getProposal(proposal1.proposalId);
      const status2 = await coordinator.getProposal(proposal2.proposalId);

      expect(status1?.signatures.size).toBe(1);
      expect(status2?.signatures.size).toBe(0);
    });

    it('should prevent duplicate signatures from same owner on same proposal', async () => {
      const proposal = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      await coordinator.collectSignature(
        proposal.proposalId,
        owners[0],
        HexStringBuilder.signature()
      );

      const result = await coordinator.collectSignature(
        proposal.proposalId,
        owners[0],
        HexStringBuilder.signature()
      );

      expect(result.currentSignatures).toBe(1);
    });

    it('should allow same owner to sign different proposals independently', async () => {
      const proposal1 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const proposal2 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );

      const sig1 = '0x' + 'a'.repeat(130) as HexString;
      const sig2 = '0x' + 'b'.repeat(130) as HexString;

      await coordinator.collectSignature(proposal1.proposalId, owners[0], sig1);
      await coordinator.collectSignature(proposal2.proposalId, owners[0], sig2);

      const status1 = await coordinator.getProposal(proposal1.proposalId);
      const status2 = await coordinator.getProposal(proposal2.proposalId);

      expect(status1?.signatures.get(owners[0])).toBe(sig1);
      expect(status2?.signatures.get(owners[0])).toBe(sig2);
    });

    it('should correctly track threshold per proposal independently', async () => {
      const proposal1 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const proposal2 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );

      await coordinator.collectSignature(proposal1.proposalId, owners[0], HexStringBuilder.signature());
      await coordinator.collectSignature(proposal1.proposalId, owners[1], HexStringBuilder.signature());

      await coordinator.collectSignature(proposal2.proposalId, owners[0], HexStringBuilder.signature());

      const status1 = await coordinator.getProposal(proposal1.proposalId);
      const status2 = await coordinator.getProposal(proposal2.proposalId);

      expect(status1?.status).toBe('ready');
      expect(status2?.status).toBe('pending');
    });
  });

  describe('Concurrent Proposal Execution - Isolation', () => {
    it('should prevent execution of non-ready proposals', async () => {
      const proposal = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      await coordinator.collectSignature(proposal.proposalId, owners[0], HexStringBuilder.signature());

      await expect(
        coordinator.executeProposal(proposal.proposalId)
      ).rejects.toThrow(ConflictError);
    });

    it('should prevent concurrent execution of same proposal', async () => {
      const proposal = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      await coordinator.collectSignature(proposal.proposalId, owners[0], HexStringBuilder.signature());
      await coordinator.collectSignature(proposal.proposalId, owners[1], HexStringBuilder.signature());

      await coordinator.executeProposal(proposal.proposalId);

      await expect(
        coordinator.executeProposal(proposal.proposalId)
      ).rejects.toThrow(ConflictError);
    });

    it('should allow independent execution of different proposals', async () => {
      const proposal1 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const proposal2 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );

      await coordinator.collectSignature(proposal1.proposalId, owners[0], HexStringBuilder.signature());
      await coordinator.collectSignature(proposal1.proposalId, owners[1], HexStringBuilder.signature());

      await coordinator.collectSignature(proposal2.proposalId, owners[0], HexStringBuilder.signature());
      await coordinator.collectSignature(proposal2.proposalId, owners[1], HexStringBuilder.signature());

      const result1 = await coordinator.executeProposal(proposal1.proposalId);
      const result2 = await coordinator.executeProposal(proposal2.proposalId);

      expect(result1.transactionHash).toBeDefined();
      expect(result2.transactionHash).toBeDefined();
      expect(result1.transactionHash).not.toBe(result2.transactionHash);
    });

    it('should correctly track execution status per proposal', async () => {
      const proposal1 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const proposal2 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );

      await coordinator.collectSignature(proposal1.proposalId, owners[0], HexStringBuilder.signature());
      await coordinator.collectSignature(proposal1.proposalId, owners[1], HexStringBuilder.signature());

      await coordinator.collectSignature(proposal2.proposalId, owners[0], HexStringBuilder.signature());

      await coordinator.executeProposal(proposal1.proposalId);

      const status1 = await coordinator.getProposal(proposal1.proposalId);
      const status2 = await coordinator.getProposal(proposal2.proposalId);

      expect(status1?.status).toBe('executed');
      expect(status2?.status).toBe('pending');
    });
  });

  describe('Race Condition Prevention', () => {
    it('should handle concurrent signature collection correctly', async () => {
      const proposal = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const collectPromises = [
        coordinator.collectSignature(proposal.proposalId, owners[0], HexStringBuilder.signature()),
        coordinator.collectSignature(proposal.proposalId, owners[1], HexStringBuilder.signature()),
        coordinator.collectSignature(proposal.proposalId, owners[2], HexStringBuilder.signature()),
      ];

      await Promise.all(collectPromises);

      const status = await coordinator.getProposal(proposal.proposalId);
      expect(status?.signatures.size).toBe(3);
      expect(status?.status).toBe('ready');
    });

    it('should maintain nonce ordering under concurrent creation', async () => {
      const count = 5;
      const promises: Promise<{ proposalId: string; nonce: bigint }>[] = [];

      for (let i = 0; i < count; i++) {
        promises.push(
          coordinator.createProposal(
            walletAddress,
            1,
            AddressBuilder.fromSeed(i + 1),
            BigInt(0),
            '0x' as HexString
          )
        );
      }

      const results = await Promise.all(promises);
      const nonces = results.map(r => r.nonce).sort((a, b) => a < b ? -1 : a > b ? 1 : 0);

      for (let i = 0; i < count; i++) {
        expect(nonces[i]).toBe(BigInt(i));
      }
    });

    it('should isolate proposal data from external mutations', async () => {
      const proposal = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt('1000000000000000000'),
        '0x' as HexString
      );

      const retrieved = await coordinator.getProposal(proposal.proposalId);
      expect(retrieved).not.toBeNull();

      (retrieved as any).value = BigInt('9999999999999999999');

      const retrievedAgain = await coordinator.getProposal(proposal.proposalId);
      expect(retrievedAgain?.value).toBe(BigInt('1000000000000000000'));
    });

    it('should isolate signatures map from external modifications', async () => {
      const proposal = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      await coordinator.collectSignature(proposal.proposalId, owners[0], HexStringBuilder.signature());

      const retrieved = await coordinator.getProposal(proposal.proposalId);
      retrieved?.signatures.set(owners[1], HexStringBuilder.signature());

      const retrievedAgain = await coordinator.getProposal(proposal.proposalId);
      expect(retrievedAgain?.signatures.size).toBe(1);
    });
  });

  describe('Strategy Isolation', () => {
    it('should use proposal-specific strategy for validation', async () => {
      const strategy = MultisigStrategyBuilder.default()
        .withThreshold(1)
        .withOwners([owners[0]])
        .withValidateFn(async () => true)
        .build();

      transactionBuilder.setMultisigStrategy(strategy);

      const coordinator2 = new MultisigCoordinatorService(
        transactionBuilder,
        strategy,
        mockLogger
      );

      const proposal = await coordinator2.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const result = await coordinator2.collectSignature(
        proposal.proposalId,
        owners[0],
        HexStringBuilder.signature()
      );

      expect(result.isReady).toBe(true);
      expect(result.threshold).toBe(1);
    });

    it('should reject signatures from non-owners regardless of other proposals', async () => {
      const proposal = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );

      const nonOwner = AddressBuilder.fromSeed(999);

      await expect(
        coordinator.collectSignature(
          proposal.proposalId,
          nonOwner,
          HexStringBuilder.signature()
        )
      ).rejects.toThrow(SignatureVerificationError);
    });
  });

  describe('List and Query Isolation', () => {
    it('should filter proposals by wallet address correctly', async () => {
      const wallet1 = AddressBuilder.fromSeed(100);
      const wallet2 = AddressBuilder.fromSeed(200);

      await coordinator.createProposal(
        wallet1,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );
      await coordinator.createProposal(
        wallet1,
        1,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );
      await coordinator.createProposal(
        wallet2,
        1,
        AddressBuilder.fromSeed(3),
        BigInt(0),
        '0x' as HexString
      );

      const wallet1Proposals = coordinator.listProposals({ walletAddress: wallet1 });
      const wallet2Proposals = coordinator.listProposals({ walletAddress: wallet2 });

      expect(wallet1Proposals.length).toBe(2);
      expect(wallet2Proposals.length).toBe(1);
    });

    it('should filter proposals by chain correctly', async () => {
      await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );
      await coordinator.createProposal(
        walletAddress,
        5,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );
      await coordinator.createProposal(
        walletAddress,
        137,
        AddressBuilder.fromSeed(3),
        BigInt(0),
        '0x' as HexString
      );

      const ethProposals = coordinator.listProposals({ chainId: 1 });
      const polygonProposals = coordinator.listProposals({ chainId: 137 });

      expect(ethProposals.length).toBe(1);
      expect(polygonProposals.length).toBe(1);
    });

    it('should filter proposals by status correctly', async () => {
      const proposal1 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(1),
        BigInt(0),
        '0x' as HexString
      );
      const proposal2 = await coordinator.createProposal(
        walletAddress,
        1,
        AddressBuilder.fromSeed(2),
        BigInt(0),
        '0x' as HexString
      );

      await coordinator.collectSignature(proposal2.proposalId, owners[0], HexStringBuilder.signature());
      await coordinator.collectSignature(proposal2.proposalId, owners[1], HexStringBuilder.signature());
      await coordinator.executeProposal(proposal2.proposalId);

      const pending = coordinator.listProposals({ status: 'pending' });
      const executed = coordinator.listProposals({ status: 'executed' });

      expect(pending.length).toBe(1);
      expect(pending[0].id).toBe(proposal1.proposalId);
      expect(executed.length).toBe(1);
      expect(executed[0].id).toBe(proposal2.proposalId);
    });
  });
});
