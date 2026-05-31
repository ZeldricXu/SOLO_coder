import { Prisma, PrismaClient, MultisigProposal } from '@prisma/client';
import { getPrismaClient } from '../../utils/database';
import { CryptoUtils } from '../../utils/crypto';
import { config } from '../../config';
import { NotFoundError, ValidationError, MultisigError } from '../../utils/errors';
import {
  CreateProposalRequest,
  SignProposalRequest,
  ExecuteProposalRequest,
  MultisigProposal as IMultisigProposal,
  ProposalType,
  ProposalStatus,
  Signature,
  ProposalData,
} from '../../types';
import { cacheService } from '../../utils/cache';
import { ethers } from 'ethers';

const MAX_WALLET_ID_LENGTH = 100;
const MAX_PROPOSAL_TYPE_LENGTH = 50;
const MAX_SIGNATURE_LENGTH = 132;
const MAX_VALUE_LENGTH = 78;
const MAX_DATA_LENGTH = 1048576;
const MAX_NONCE = 2147483647;
const MAX_SIGNATURE_COUNT = 100;

const VALID_PROPOSAL_TYPES: ProposalType[] = [
  'TRANSFER',
  'APPROVE',
  'EXECUTE',
  'UPDATE_OWNERS',
  'CHANGE_THRESHOLD',
  'CUSTOM',
];

const VALID_PROPOSAL_STATUSES: ProposalStatus[] = [
  'PENDING',
  'APPROVED',
  'EXECUTED',
  'REJECTED',
  'EXPIRED',
];

export class MultisigCoordinatorService {
  private prisma: PrismaClient;
  private readonly CACHE_TTL = 3600;

  constructor() {
    this.prisma = getPrismaClient();
  }

  private getCacheKey(proposalId: string): string {
    return `proposal:${proposalId}`;
  }

  async createProposal(request: CreateProposalRequest): Promise<IMultisigProposal> {
    this.validateProposalRequest(request);

    const { walletId, chainId, type, data } = request;

    const existingProposals = await this.prisma.multisigProposal.findMany({
      where: {
        walletId: walletId.trim(),
        chainId,
        status: { in: ['PENDING', 'APPROVED'] },
      },
      orderBy: { nonce: 'desc' },
      take: 1,
    });

    const nextNonce = existingProposals.length > 0
      ? existingProposals[0].nonce + 1
      : 0;

    if (nextNonce > MAX_NONCE) {
      throw new ValidationError('Nonce has exceeded maximum value');
    }

    try {
      const proposal = await this.prisma.multisigProposal.create({
        data: {
          walletId: walletId.trim(),
          chainId,
          nonce: nextNonce,
          type,
          data: {
            to: data.to.trim(),
            value: data.value?.trim() || '0',
            data: data.data?.trim() || '0x',
            operation: data.operation ?? 0,
          },
          threshold: config.multisig.defaultThreshold,
          requiredSigners: config.multisig.defaultOwners.length,
          signatures: [],
          status: 'PENDING',
        },
      });

      const cacheKey = this.getCacheKey(proposal.id);
      const domainModel = this.toDomainModel(proposal);
      await cacheService.set(cacheKey, domainModel, this.CACHE_TTL);

      return domainModel;
    } catch (error) {
      throw new MultisigError(`Failed to create proposal: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  async signProposal(request: SignProposalRequest): Promise<IMultisigProposal> {
    const { proposalId, signer, signature } = request;

    if (!proposalId || proposalId.trim() === '') {
      throw new ValidationError('Proposal ID is required');
    }

    if (!signer || signer.trim() === '') {
      throw new ValidationError('Signer address is required');
    }

    const trimmedSigner = signer.trim();
    if (!CryptoUtils.isValidAddress(trimmedSigner)) {
      throw new ValidationError('Invalid signer address format');
    }

    if (!signature || signature.trim() === '') {
      throw new ValidationError('Signature is required');
    }

    if (signature.length > MAX_SIGNATURE_LENGTH) {
      throw new ValidationError(`Signature cannot exceed ${MAX_SIGNATURE_LENGTH} characters`);
    }

    const proposal = await this.prisma.multisigProposal.findUnique({
      where: { id: proposalId.trim() },
    });

    if (!proposal) {
      throw new NotFoundError('Proposal not found');
    }

    if (proposal.status !== 'PENDING') {
      throw new MultisigError('Proposal is not in pending state');
    }

    const existingSignatures = (proposal.signatures as Signature[]) || [];
    const alreadySigned = existingSignatures.some(
      s => s.signer.toLowerCase() === trimmedSigner.toLowerCase()
    );

    if (alreadySigned) {
      throw new MultisigError('Signer has already signed this proposal');
    }

    const isAuthorizedSigner = config.multisig.defaultOwners.some(
      o => o.toLowerCase() === trimmedSigner.toLowerCase()
    );

    if (!isAuthorizedSigner) {
      throw new MultisigError('Signer is not an authorized owner');
    }

    let messageToSign = '';
    try {
      messageToSign = this.getProposalMessage(proposal);
    } catch (error) {
      throw new ValidationError('Invalid proposal data');
    }

    let isValidSignature = false;
    try {
      isValidSignature = CryptoUtils.verifySignature(
        messageToSign,
        signature.trim(),
        trimmedSigner
      );
    } catch {
      isValidSignature = false;
    }

    if (!isValidSignature) {
      throw new ValidationError('Invalid signature');
    }

    const newSignatures: Signature[] = [
      ...existingSignatures,
      {
        signer: trimmedSigner,
        signature: signature.trim(),
        timestamp: Date.now(),
      },
    ];

    let newStatus: ProposalStatus = 'PENDING';
    if (newSignatures.length >= proposal.threshold) {
      newStatus = 'APPROVED';
    }

    try {
      const updated = await this.prisma.multisigProposal.update({
        where: { id: proposalId.trim() },
        data: {
          signatures: newSignatures as any,
          status: newStatus,
        },
      });

      const cacheKey = this.getCacheKey(proposalId.trim());
      await cacheService.delete(cacheKey);

      return this.toDomainModel(updated);
    } catch (error) {
      throw new MultisigError(`Failed to update proposal: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  async executeProposal(request: ExecuteProposalRequest): Promise<{ proposal: IMultisigProposal; txHash: string }> {
    const { proposalId } = request;

    if (!proposalId || proposalId.trim() === '') {
      throw new ValidationError('Proposal ID is required');
    }

    const proposal = await this.prisma.multisigProposal.findUnique({
      where: { id: proposalId.trim() },
    });

    if (!proposal) {
      throw new NotFoundError('Proposal not found');
    }

    if (proposal.status !== 'APPROVED') {
      throw new MultisigError('Proposal must be approved before execution');
    }

    const signatures = (proposal.signatures as Signature[]) || [];
    if (signatures.length < proposal.threshold) {
      throw new MultisigError('Insufficient signatures for execution');
    }

    const txHash = `0x${ethers.randomBytes(32).toString('hex')}`;

    try {
      const updated = await this.prisma.multisigProposal.update({
        where: { id: proposalId.trim() },
        data: {
          status: 'EXECUTED',
          executedTxHash: txHash,
        },
      });

      const cacheKey = this.getCacheKey(proposalId.trim());
      await cacheService.delete(cacheKey);

      return {
        proposal: this.toDomainModel(updated),
        txHash,
      };
    } catch (error) {
      throw new MultisigError(`Failed to execute proposal: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  async getProposal(proposalId: string): Promise<IMultisigProposal> {
    if (!proposalId || proposalId.trim() === '') {
      throw new ValidationError('Proposal ID is required');
    }

    const cacheKey = this.getCacheKey(proposalId.trim());
    const cached = await cacheService.get<IMultisigProposal>(cacheKey);

    if (cached) {
      return cached;
    }

    const proposal = await this.prisma.multisigProposal.findUnique({
      where: { id: proposalId.trim() },
    });

    if (!proposal) {
      throw new NotFoundError('Proposal not found');
    }

    const domainModel = this.toDomainModel(proposal);
    await cacheService.set(cacheKey, domainModel, this.CACHE_TTL);

    return domainModel;
  }

  async getProposals(filters?: {
    walletId?: string;
    chainId?: number;
    status?: ProposalStatus;
    type?: ProposalType;
    page?: number;
    pageSize?: number;
  }): Promise<{ items: IMultisigProposal[]; total: number }> {
    const {
      walletId,
      chainId,
      status,
      type,
      page = 1,
      pageSize = 20,
    } = filters || {};

    const validatedPage = Math.max(1, page);
    const validatedPageSize = Math.min(Math.max(1, pageSize), 100);

    if (walletId && walletId.length > MAX_WALLET_ID_LENGTH) {
      throw new ValidationError(`Wallet ID cannot exceed ${MAX_WALLET_ID_LENGTH} characters`);
    }

    if (chainId !== undefined && chainId <= 0) {
      throw new ValidationError('Chain ID must be positive');
    }

    if (status && !VALID_PROPOSAL_STATUSES.includes(status)) {
      throw new ValidationError('Invalid proposal status');
    }

    if (type && !VALID_PROPOSAL_TYPES.includes(type)) {
      throw new ValidationError('Invalid proposal type');
    }

    const where: any = {};

    if (walletId) {
      where.walletId = walletId.trim();
    }

    if (chainId !== undefined) {
      where.chainId = chainId;
    }

    if (status) {
      where.status = status;
    }

    if (type) {
      where.type = type;
    }

    const [total, proposals] = await Promise.all([
      this.prisma.multisigProposal.count({ where }),
      this.prisma.multisigProposal.findMany({
        where,
        skip: (validatedPage - 1) * validatedPageSize,
        take: validatedPageSize,
        orderBy: { createdAt: 'desc' },
      }),
    ]);

    return {
      items: proposals.map(p => this.toDomainModel(p)),
      total,
    };
  }

  async getPendingProposals(walletId: string): Promise<IMultisigProposal[]> {
    if (!walletId || walletId.trim() === '') {
      throw new ValidationError('Wallet ID is required');
    }

    if (walletId.length > MAX_WALLET_ID_LENGTH) {
      throw new ValidationError(`Wallet ID cannot exceed ${MAX_WALLET_ID_LENGTH} characters`);
    }

    const proposals = await this.prisma.multisigProposal.findMany({
      where: {
        walletId: walletId.trim(),
        status: 'PENDING',
      },
      orderBy: { createdAt: 'asc' },
    });

    return proposals.map(p => this.toDomainModel(p));
  }

  async getApprovedProposals(walletId: string): Promise<IMultisigProposal[]> {
    if (!walletId || walletId.trim() === '') {
      throw new ValidationError('Wallet ID is required');
    }

    if (walletId.length > MAX_WALLET_ID_LENGTH) {
      throw new ValidationError(`Wallet ID cannot exceed ${MAX_WALLET_ID_LENGTH} characters`);
    }

    const proposals = await this.prisma.multisigProposal.findMany({
      where: {
        walletId: walletId.trim(),
        status: 'APPROVED',
      },
      orderBy: { createdAt: 'asc' },
    });

    return proposals.map(p => this.toDomainModel(p));
  }

  async rejectProposal(proposalId: string, reason?: string): Promise<IMultisigProposal> {
    if (!proposalId || proposalId.trim() === '') {
      throw new ValidationError('Proposal ID is required');
    }

    const proposal = await this.prisma.multisigProposal.findUnique({
      where: { id: proposalId.trim() },
    });

    if (!proposal) {
      throw new NotFoundError('Proposal not found');
    }

    if (proposal.status !== 'PENDING' && proposal.status !== 'APPROVED') {
      throw new MultisigError('Proposal cannot be rejected in current state');
    }

    try {
      const updated = await this.prisma.multisigProposal.update({
        where: { id: proposalId.trim() },
        data: {
          status: 'REJECTED',
        },
      });

      const cacheKey = this.getCacheKey(proposalId.trim());
      await cacheService.delete(cacheKey);

      return this.toDomainModel(updated);
    } catch (error) {
      throw new MultisigError(`Failed to reject proposal: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  async getProposalSignatures(proposalId: string): Promise<Signature[]> {
    if (!proposalId || proposalId.trim() === '') {
      throw new ValidationError('Proposal ID is required');
    }

    const proposal = await this.prisma.multisigProposal.findUnique({
      where: { id: proposalId.trim() },
    });

    if (!proposal) {
      throw new NotFoundError('Proposal not found');
    }

    return (proposal.signatures as Signature[]) || [];
  }

  async canExecute(proposalId: string): Promise<boolean> {
    if (!proposalId || proposalId.trim() === '') {
      return false;
    }

    const proposal = await this.prisma.multisigProposal.findUnique({
      where: { id: proposalId.trim() },
    });

    if (!proposal) {
      return false;
    }

    const signatures = (proposal.signatures as Signature[]) || [];
    return signatures.length >= proposal.threshold;
  }

  private validateProposalRequest(request: CreateProposalRequest): void {
    if (!request.walletId || request.walletId.trim() === '') {
      throw new ValidationError('Wallet ID is required');
    }

    const trimmedWalletId = request.walletId.trim();
    if (trimmedWalletId.length > MAX_WALLET_ID_LENGTH) {
      throw new ValidationError(`Wallet ID cannot exceed ${MAX_WALLET_ID_LENGTH} characters`);
    }

    if (!request.chainId || request.chainId <= 0) {
      throw new ValidationError('Valid chain ID is required');
    }

    if (!request.type || request.type.trim() === '') {
      throw new ValidationError('Proposal type is required');
    }

    if (!VALID_PROPOSAL_TYPES.includes(request.type as ProposalType)) {
      throw new ValidationError('Invalid proposal type');
    }

    if (!request.data) {
      throw new ValidationError('Proposal data is required');
    }

    if (!request.data.to || request.data.to.trim() === '') {
      throw new ValidationError('Recipient address is required');
    }

    const trimmedTo = request.data.to.trim();
    if (!CryptoUtils.isValidAddress(trimmedTo)) {
      throw new ValidationError('Invalid recipient address');
    }

    if (request.data.value !== undefined && request.data.value !== null) {
      const trimmedValue = request.data.value.trim();
      if (trimmedValue.length > MAX_VALUE_LENGTH) {
        throw new ValidationError(`Value cannot exceed ${MAX_VALUE_LENGTH} characters`);
      }
      if (trimmedValue && !/^[0-9]+$/.test(trimmedValue)) {
        throw new ValidationError('Value must be a valid non-negative integer string');
      }
    }

    if (request.data.data !== undefined && request.data.data !== null) {
      const trimmedData = request.data.data.trim();
      if (trimmedData.length > MAX_DATA_LENGTH) {
        throw new ValidationError(`Proposal data cannot exceed ${MAX_DATA_LENGTH} characters`);
      }
      if (trimmedData && !/^0x[0-9a-fA-F]*$/.test(trimmedData)) {
        throw new ValidationError('Proposal data must be a valid hex string');
      }
    }

    if (request.data.operation !== undefined) {
      if (request.data.operation !== 0 && request.data.operation !== 1) {
        throw new ValidationError('Operation must be 0 (CALL) or 1 (DELEGATECALL)');
      }
    }
  }

  private getProposalMessage(proposal: MultisigProposal): string {
    const proposalData = proposal.data as ProposalData;

    if (!proposalData) {
      throw new ValidationError('Proposal data is missing');
    }

    const message = JSON.stringify({
      walletId: proposal.walletId,
      chainId: proposal.chainId,
      nonce: proposal.nonce,
      type: proposal.type,
      to: proposalData.to,
      value: proposalData.value || '0',
      data: proposalData.data || '0x',
      operation: proposalData.operation || 0,
    });

    return message;
  }

  private toDomainModel(proposal: MultisigProposal): IMultisigProposal {
    return {
      id: proposal.id,
      walletId: proposal.walletId,
      chainId: proposal.chainId,
      nonce: proposal.nonce,
      type: proposal.type as ProposalType,
      data: proposal.data as ProposalData,
      threshold: proposal.threshold,
      requiredSigners: proposal.requiredSigners,
      signatures: (proposal.signatures as Signature[]) || [],
      status: proposal.status as ProposalStatus,
      executedTxHash: proposal.executedTxHash || undefined,
      createdAt: proposal.createdAt,
      updatedAt: proposal.updatedAt,
    };
  }
}

export const multisigCoordinatorService = new MultisigCoordinatorService();
export default multisigCoordinatorService;
