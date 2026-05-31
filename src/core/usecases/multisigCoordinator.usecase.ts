import type { Logger } from '@shared/logger';
import type { MultisigCoordinatorPort } from '@core/ports/wallet.port';
import type { TransactionBuilderPort, MultisigStrategy } from '@core/ports/transactionBuilder.port';
import type { ChainId, Address, Hash, HexString, WeiAmount } from '@shared/types';
import { NotFoundError, ConflictError, SignatureVerificationError } from '@shared/errors';

interface MultisigProposal {
  id: string;
  walletAddress: Address;
  chainId: ChainId;
  to: Address;
  value: WeiAmount;
  data: HexString;
  nonce: bigint;
  transactionHash: Hash;
  unsignedData: HexString;
  signatures: Map<Address, HexString>;
  strategy: MultisigStrategy;
  status: 'pending' | 'ready' | 'executing' | 'executed' | 'failed';
  createdAt: number;
  executedAt?: number;
  error?: string;
}

export class MultisigCoordinatorService implements MultisigCoordinatorPort {
  private proposals: Map<string, MultisigProposal> = new Map();
  private nonceLocks: Map<string, Promise<void>> = new Map();
  private nonceCounters: Map<string, bigint> = new Map();

  constructor(
    private readonly transactionBuilder: TransactionBuilderPort,
    private readonly defaultStrategy: MultisigStrategy,
    private readonly logger: Logger
  ) {}

  private generateId(): string {
    return `proposal_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  }

  async createProposal(
    walletAddress: Address,
    chainId: ChainId,
    to: Address,
    value: WeiAmount,
    data: HexString,
    meta?: Record<string, unknown>
  ): Promise<{
    proposalId: string;
    nonce: bigint;
    transactionHash: Hash;
  }> {
    this.logger.info('Creating multisig proposal', { walletAddress, chainId, to });

    const strategy = this.transactionBuilder.getMultisigStrategy() || this.defaultStrategy;
    const nonce = await this.getNextNonceLocked(walletAddress, chainId);

    const builtTx = await this.transactionBuilder.buildTransaction({
      chainId,
      from: walletAddress,
      to,
      value,
      data,
      nonce: Number(nonce),
    });

    const proposal: MultisigProposal = {
      id: this.generateId(),
      walletAddress,
      chainId,
      to,
      value,
      data,
      nonce,
      transactionHash: builtTx.transactionHash,
      unsignedData: builtTx.unsignedData,
      signatures: new Map(),
      strategy,
      status: 'pending',
      createdAt: Date.now(),
    };

    this.proposals.set(proposal.id, proposal);

    this.logger.info('Created multisig proposal', {
      proposalId: proposal.id,
      threshold: strategy.threshold,
      totalOwners: strategy.owners.length,
    });

    return {
      proposalId: proposal.id,
      nonce,
      transactionHash: proposal.transactionHash,
    };
  }

  private async getNextNonce(walletAddress: Address, chainId: ChainId): Promise<bigint> {
    let maxNonce = BigInt(0);
    for (const proposal of this.proposals.values()) {
      if (proposal.walletAddress === walletAddress && proposal.chainId === chainId) {
        if (proposal.nonce >= maxNonce) {
          maxNonce = proposal.nonce + BigInt(1);
        }
      }
    }
    return maxNonce;
  }

  private async getNextNonceLocked(walletAddress: Address, chainId: ChainId): Promise<bigint> {
    const lockKey = `${walletAddress}-${chainId}`;
    const existingLock = this.nonceLocks.get(lockKey);
    
    if (existingLock) {
      await existingLock;
    }

    let resolveLock: () => void;
    const lock = new Promise<void>((resolve) => {
      resolveLock = resolve;
    });
    this.nonceLocks.set(lockKey, lock);

    try {
      const counterKey = `${walletAddress}-${chainId}`;
      const currentNonce = this.nonceCounters.get(counterKey) || BigInt(0);
      this.nonceCounters.set(counterKey, currentNonce + BigInt(1));
      return currentNonce;
    } finally {
      this.nonceLocks.delete(lockKey);
      resolveLock!();
    }
  }

  async collectSignature(
    proposalId: string,
    signer: Address,
    signature: HexString
  ): Promise<{
    proposalId: string;
    currentSignatures: number;
    threshold: number;
    isReady: boolean;
  }> {
    const proposal = this.proposals.get(proposalId);
    if (!proposal) {
      throw new NotFoundError('MultisigProposal', proposalId);
    }

    if (proposal.status !== 'pending') {
      throw new ConflictError(`Proposal ${proposalId} is not in pending state: ${proposal.status}`);
    }

    if (!proposal.strategy.owners.includes(signer)) {
      throw new SignatureVerificationError(`Address ${signer} is not an owner of wallet ${proposal.walletAddress}`);
    }

    if (proposal.signatures.has(signer)) {
      this.logger.warn('Signature already collected', { proposalId, signer });
      return {
        proposalId,
        currentSignatures: proposal.signatures.size,
        threshold: proposal.strategy.threshold,
        isReady: proposal.signatures.size >= proposal.strategy.threshold,
      };
    }

    const isValid = await this.verifySignature(proposal, signer, signature);
    if (!isValid) {
      throw new SignatureVerificationError(`Invalid signature from ${signer} for proposal ${proposalId}`);
    }

    proposal.signatures.set(signer, signature);
    const isReady = proposal.signatures.size >= proposal.strategy.threshold;

    if (isReady) {
      proposal.status = 'ready';
      this.logger.info('Proposal reached threshold', { proposalId });
    }

    this.logger.info('Collected signature', {
      proposalId,
      signer,
      currentSignatures: proposal.signatures.size,
      threshold: proposal.strategy.threshold,
      isReady,
    });

    return {
      proposalId,
      currentSignatures: proposal.signatures.size,
      threshold: proposal.strategy.threshold,
      isReady,
    };
  }

  private async verifySignature(
    proposal: MultisigProposal,
    signer: Address,
    signature: HexString
  ): Promise<boolean> {
    try {
      const sigMap = new Map<Address, HexString>();
      sigMap.set(signer, signature);

      const signerSig: { r: HexString; s: HexString; v: bigint } = {
        r: '0x' + signature.slice(2, 66) as HexString,
        s: '0x' + signature.slice(66, 130) as HexString,
        v: BigInt('0x' + signature.slice(130, 132)) || BigInt(27),
      };

      const signatures = new Map<Address, { r: HexString; s: HexString; v: bigint }>();
      signatures.set(signer, signerSig);

      return await proposal.strategy.validateSignatures(proposal.transactionHash, signatures);
    } catch (error) {
      this.logger.error('Signature verification failed', { error, proposalId: proposal.id, signer });
      return false;
    }
  }

  async executeProposal(proposalId: string): Promise<{
    transactionHash: Hash;
    nonce: bigint;
  }> {
    const proposal = this.proposals.get(proposalId);
    if (!proposal) {
      throw new NotFoundError('MultisigProposal', proposalId);
    }

    if (proposal.status !== 'ready') {
      throw new ConflictError(`Proposal ${proposalId} is not ready for execution: ${proposal.status}`);
    }

    proposal.status = 'executing';
    this.logger.info('Executing multisig proposal', { proposalId });

    try {
      const combinedSignature = await this.combineSignatures(proposal);

      proposal.status = 'executed';
      proposal.executedAt = Date.now();

      this.logger.info('Executed multisig proposal', {
        proposalId,
        transactionHash: proposal.transactionHash,
      });

      return {
        transactionHash: proposal.transactionHash,
        nonce: proposal.nonce,
      };
    } catch (error) {
      proposal.status = 'failed';
      proposal.error = error instanceof Error ? error.message : 'Unknown error';
      this.logger.error('Failed to execute proposal', { error, proposalId });
      throw error;
    }
  }

  private async combineSignatures(proposal: MultisigProposal): Promise<HexString> {
    const signatures = new Map<Address, { r: HexString; s: HexString; v: bigint }>();

    for (const [signer, sig] of proposal.signatures.entries()) {
      signatures.set(signer, {
        r: '0x' + sig.slice(2, 66) as HexString,
        s: '0x' + sig.slice(66, 130) as HexString,
        v: BigInt('0x' + sig.slice(130, 132)) || BigInt(27),
      });
    }

    return proposal.strategy.combineSignatures(signatures);
  }

  async getProposal(proposalId: string): Promise<{
    id: string;
    walletAddress: Address;
    to: Address;
    value: WeiAmount;
    data: HexString;
    nonce: bigint;
    signatures: Map<Address, HexString>;
    status: 'pending' | 'ready' | 'executing' | 'executed' | 'failed';
  } | null> {
    const proposal = this.proposals.get(proposalId);
    if (!proposal) return null;

    return {
      id: proposal.id,
      walletAddress: proposal.walletAddress,
      to: proposal.to,
      value: proposal.value,
      data: proposal.data,
      nonce: proposal.nonce,
      signatures: new Map(proposal.signatures),
      status: proposal.status,
    };
  }

  listProposals(filters?: {
    walletAddress?: Address;
    chainId?: ChainId;
    status?: string;
  }): Array<{
    id: string;
    walletAddress: Address;
    chainId: ChainId;
    to: Address;
    value: WeiAmount;
    status: string;
    signatures: number;
    threshold: number;
    createdAt: number;
  }> {
    return Array.from(this.proposals.values())
      .filter(p => {
        if (filters?.walletAddress && p.walletAddress !== filters.walletAddress) return false;
        if (filters?.chainId && p.chainId !== filters.chainId) return false;
        if (filters?.status && p.status !== filters.status) return false;
        return true;
      })
      .map(p => ({
        id: p.id,
        walletAddress: p.walletAddress,
        chainId: p.chainId,
        to: p.to,
        value: p.value,
        status: p.status,
        signatures: p.signatures.size,
        threshold: p.strategy.threshold,
        createdAt: p.createdAt,
      }));
  }
}
