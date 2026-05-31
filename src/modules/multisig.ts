import { isAddress, getBytes, hashMessage, recoverAddress } from 'ethers';
import { MultiSigProposal } from '../types';
import { generateId, now, normalizeAddress, withRetry, getErrorMessage } from '../common/utils';
import { eventBus, EVENTS } from '../common/events';
import { LoggerContext } from '../common/logger';

export interface WalletConfig {
  walletId: string;
  signers: string[];
  requiredSignatures: number;
  nonce: number;
  version: number;
  updatedAt: string;
}

export interface ConfigUpdateParams {
  walletId: string;
  signers?: string[];
  requiredSignatures?: number;
}

export interface ConfigChangeRecord {
  id: string;
  walletId: string;
  oldConfig: { signers: string[]; requiredSignatures: number };
  newConfig: { signers: string[]; requiredSignatures: number };
  changedAt: string;
  changedBy?: string;
  reason?: string;
}

export class MultiSigWalletCoordinator {
  private proposals: Map<string, MultiSigProposal>;
  private wallets: Map<string, WalletConfig>;
  private configHistory: Map<string, ConfigChangeRecord[]>;
  private logger: LoggerContext;

  constructor() {
    this.proposals = new Map();
    this.wallets = new Map();
    this.configHistory = new Map();
    this.logger = new LoggerContext({ module: 'MultiSigWalletCoordinator' });
  }

  createWallet(walletId: string, signers: string[], requiredSignatures: number): WalletConfig {
    this.logger.info('Creating multi-sig wallet', { walletId, signerCount: signers.length, requiredSignatures });

    this.validateSignerConfig(signers, requiredSignatures);

    const normalizedSigners = signers.map(normalizeAddress);
    const uniqueSigners = [...new Set(normalizedSigners)];

    if (uniqueSigners.length !== normalizedSigners.length) {
      throw new Error('Duplicate signers are not allowed');
    }

    const config: WalletConfig = {
      walletId,
      signers: uniqueSigners,
      requiredSignatures,
      nonce: 0,
      version: 1,
      updatedAt: now(),
    };

    this.wallets.set(walletId, config);
    this.configHistory.set(walletId, []);

    this.logger.info('Multi-sig wallet created', { walletId, version: config.version });
    return config;
  }

  updateWalletConfig(params: ConfigUpdateParams, changedBy?: string, reason?: string): WalletConfig {
    const { walletId, signers, requiredSignatures } = params;

    this.logger.info('Updating wallet config', { walletId, changedBy });

    const currentConfig = this.wallets.get(walletId);
    if (!currentConfig) {
      throw new Error(`Wallet not found: ${walletId}`);
    }

    const newSigners = signers ?? currentConfig.signers;
    const newRequiredSignatures = requiredSignatures ?? currentConfig.requiredSignatures;

    if (signers !== undefined) {
      this.validateSignerConfig(signers, newRequiredSignatures);
    }

    const normalizedSigners = signers?.map(normalizeAddress);
    const uniqueSigners = normalizedSigners ? [...new Set(normalizedSigners)] : currentConfig.signers;

    const hasChanges =
      (signers !== undefined && JSON.stringify(uniqueSigners) !== JSON.stringify(currentConfig.signers)) ||
      (requiredSignatures !== undefined && requiredSignatures !== currentConfig.requiredSignatures);

    if (!hasChanges) {
      this.logger.info('No config changes detected', { walletId });
      return currentConfig;
    }

    const changeRecord: ConfigChangeRecord = {
      id: generateId('config_change'),
      walletId,
      oldConfig: {
        signers: currentConfig.signers,
        requiredSignatures: currentConfig.requiredSignatures,
      },
      newConfig: {
        signers: uniqueSigners,
        requiredSignatures: newRequiredSignatures,
      },
      changedAt: now(),
      changedBy,
      reason,
    };

    this.configHistory.get(walletId)?.push(changeRecord);

    const updatedConfig: WalletConfig = {
      ...currentConfig,
      signers: uniqueSigners,
      requiredSignatures: newRequiredSignatures,
      version: currentConfig.version + 1,
      updatedAt: now(),
    };

    this.wallets.set(walletId, updatedConfig);

    this.updatePendingProposalsForConfigChange(walletId, updatedConfig);

    eventBus.emit(EVENTS.WALLET_CONFIG_UPDATED, {
      walletId,
      oldVersion: currentConfig.version,
      newVersion: updatedConfig.version,
      changes: changeRecord,
    });

    this.logger.info('Wallet config updated', {
      walletId,
      oldVersion: currentConfig.version,
      newVersion: updatedConfig.version,
    });

    return updatedConfig;
  }

  getWalletConfig(walletId: string): WalletConfig | undefined {
    return this.wallets.get(walletId);
  }

  getConfigHistory(walletId: string): ConfigChangeRecord[] {
    return this.configHistory.get(walletId) || [];
  }

  private validateSignerConfig(signers: string[], requiredSignatures: number): void {
    if (requiredSignatures > signers.length) {
      throw new Error('Required signatures cannot exceed number of signers');
    }

    if (requiredSignatures <= 0) {
      throw new Error('Required signatures must be greater than 0');
    }

    signers.forEach((signer) => {
      if (!isAddress(signer)) {
        throw new Error(`Invalid signer address: ${signer}`);
      }
    });
  }

  private updatePendingProposalsForConfigChange(walletId: string, newConfig: WalletConfig): void {
    const pendingProposals = Array.from(this.proposals.values()).filter(
      (p) => p.walletId === walletId && p.status === 'pending'
    );

    for (const proposal of pendingProposals) {
      const signedSigners = proposal.currentSignatures.map((s) => s.split(':')[0]);
      const stillAuthorizedSigners = signedSigners.filter((s) => newConfig.signers.includes(s));

      proposal.requiredSignatures = newConfig.requiredSignatures;
      proposal.signers = newConfig.signers;
      proposal.currentSignatures = proposal.currentSignatures.filter((s) => {
        const signer = s.split(':')[0];
        return newConfig.signers.includes(signer);
      });

      if (proposal.currentSignatures.length >= proposal.requiredSignatures) {
        proposal.status = 'approved';
        this.logger.info('Proposal auto-approved due to config change', {
          proposalId: proposal.id,
        });
      } else if (proposal.status === 'approved') {
        proposal.status = 'pending';
        this.logger.info('Proposal reverted to pending due to config change', {
          proposalId: proposal.id,
        });
      }
    }
  }

  createProposal(params: {
    walletId: string;
    destination: string;
    value: string;
    data?: string;
    description?: string;
  }): MultiSigProposal {
    const { walletId, destination, value, data = '0x', description } = params;

    this.logger.info('Creating proposal', { walletId, destination, value });

    const wallet = this.wallets.get(walletId);
    if (!wallet) {
      throw new Error(`Wallet not found: ${walletId}`);
    }

    if (!isAddress(destination)) {
      throw new Error(`Invalid destination address: ${destination}`);
    }

    const proposal: MultiSigProposal = {
      id: generateId('proposal'),
      walletId,
      transactionData: data,
      destination: normalizeAddress(destination),
      value,
      nonce: wallet.nonce,
      requiredSignatures: wallet.requiredSignatures,
      currentSignatures: [],
      signers: wallet.signers,
      status: 'pending',
      createdAt: now(),
      description,
    };

    this.proposals.set(proposal.id, proposal);

    eventBus.emit(EVENTS.PROPOSAL_CREATED, proposal);
    this.logger.info('Proposal created', { proposalId: proposal.id });

    return proposal;
  }

  addSignature(proposalId: string, signature: string, signer: string): MultiSigProposal {
    this.logger.info('Adding signature', { proposalId, signer });

    const proposal = this.proposals.get(proposalId);
    if (!proposal) {
      throw new Error(`Proposal not found: ${proposalId}`);
    }

    if (proposal.status !== 'pending') {
      throw new Error(`Proposal is not pending: ${proposal.status}`);
    }

    const normalizedSigner = normalizeAddress(signer);
    if (!proposal.signers.includes(normalizedSigner)) {
      throw new Error(`Signer ${signer} is not authorized for this proposal`);
    }

    const prefix = normalizedSigner + ':';
    if (proposal.currentSignatures.some((s) => s.startsWith(prefix))) {
      throw new Error(`Signer ${signer} has already signed this proposal`);
    }

    const isSignatureValid = this.verifySignature(proposal, signature, normalizedSigner);
    if (!isSignatureValid) {
      throw new Error('Invalid signature');
    }

    proposal.currentSignatures.push(normalizedSigner + ':' + signature);

    if (proposal.currentSignatures.length >= proposal.requiredSignatures) {
      proposal.status = 'approved';
      this.logger.info('Proposal approved', { proposalId, signatureCount: proposal.currentSignatures.length });
    }

    eventBus.emit(EVENTS.PROPOSAL_SIGNED, { proposalId, signer: normalizedSigner });
    return proposal;
  }

  private verifySignature(proposal: MultiSigProposal, signature: string, signer: string): boolean {
    try {
      const message = this.getProposalMessage(proposal);
      const recoveredAddress = this.recoverSigner(message, signature);
      return normalizeAddress(recoveredAddress) === normalizeAddress(signer);
    } catch (error) {
      this.logger.error('Signature verification failed', error as Error, { proposalId: proposal.id });
      return false;
    }
  }

  private getProposalMessage(proposal: MultiSigProposal): string {
    return JSON.stringify({
      walletId: proposal.walletId,
      destination: proposal.destination,
      value: proposal.value,
      data: proposal.transactionData,
      nonce: proposal.nonce,
    });
  }

  private recoverSigner(message: string, signature: string): string {
    try {
      const messageHash = hashMessage(message);
      return recoverAddress(getBytes(messageHash), signature);
    } catch (error) {
      this.logger.error('Failed to recover signer', error as Error);
      throw new Error('Invalid signature format');
    }
  }

  async executeProposal(proposalId: string): Promise<{
    proposal: MultiSigProposal;
    transactionHash: string;
  }> {
    this.logger.info('Executing proposal', { proposalId });

    const proposal = this.proposals.get(proposalId);
    if (!proposal) {
      throw new Error(`Proposal not found: ${proposalId}`);
    }

    if (proposal.status !== 'approved') {
      throw new Error(`Proposal is not approved: ${proposal.status}`);
    }

    const result = await withRetry(async () => {
      const transactionHash = generateId('tx');

      proposal.status = 'executed';
      proposal.executedAt = now();

      const wallet = this.wallets.get(proposal.walletId);
      if (wallet) {
        wallet.nonce++;
      }

      return { proposal, transactionHash };
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying proposal execution', { proposalId, attempt, error: getErrorMessage(error) });
      },
    });

    eventBus.emit(EVENTS.PROPOSAL_EXECUTED, result);
    this.logger.info('Proposal executed', { proposalId, transactionHash: result.transactionHash });

    return result;
  }

  rejectProposal(proposalId: string): MultiSigProposal {
    this.logger.info('Rejecting proposal', { proposalId });

    const proposal = this.proposals.get(proposalId);
    if (!proposal) {
      throw new Error(`Proposal not found: ${proposalId}`);
    }

    if (proposal.status !== 'pending') {
      throw new Error(`Proposal cannot be rejected: ${proposal.status}`);
    }

    proposal.status = 'rejected';
    this.logger.info('Proposal rejected', { proposalId });

    return proposal;
  }

  getProposal(proposalId: string): MultiSigProposal | undefined {
    return this.proposals.get(proposalId);
  }

  listProposals(walletId?: string, status?: MultiSigProposal['status']): MultiSigProposal[] {
    let proposals = Array.from(this.proposals.values());

    if (walletId) {
      proposals = proposals.filter((p) => p.walletId === walletId);
    }

    if (status) {
      proposals = proposals.filter((p) => p.status === status);
    }

    return proposals.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }

  getWallet(walletId: string): WalletConfig | undefined {
    return this.wallets.get(walletId);
  }

  listWallets(): string[] {
    return Array.from(this.wallets.keys());
  }

  getProposalSigners(proposalId: string): { signed: string[]; pending: string[] } {
    const proposal = this.proposals.get(proposalId);
    if (!proposal) {
      throw new Error(`Proposal not found: ${proposalId}`);
    }

    const signed = proposal.currentSignatures.map((s) => s.split(':')[0]);
    const pending = proposal.signers.filter((s) => !signed.includes(s));

    return { signed, pending };
  }
}

export const multiSigCoordinator = new MultiSigWalletCoordinator();
