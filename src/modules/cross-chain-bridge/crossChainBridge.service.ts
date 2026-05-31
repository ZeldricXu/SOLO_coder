import { Prisma, PrismaClient, CrossChainTransfer } from '@prisma/client';
import { getPrismaClient } from '../../utils/database';
import { CryptoUtils } from '../../utils/crypto';
import { config } from '../../config';
import { NotFoundError, ValidationError, BridgeError } from '../../utils/errors';
import { CrossChainTransferRequest, CrossChainMessage, Signature, TransferStatus } from '../../types';
import { cacheService } from '../../utils/cache';
import { ethers } from 'ethers';

const MAX_AMOUNT_DIGITS = 78;
const MAX_TRANSACTION_HASH_LENGTH = 66;
const MAX_SIGNATURE_LENGTH = 132;
const MAX_SIGNATURE_COUNT = 100;

const VALID_STATUSES: TransferStatus[] = [
  'PENDING',
  'LOCKED',
  'VALIDATED',
  'MINTED',
  'CONFIRMED',
  'FAILED',
  'REJECTED',
];

export class CrossChainBridgeService {
  private prisma: PrismaClient;
  private readonly BRIDGE_ABI = [
    'function lockTokens(address token, uint256 amount, uint256 targetChainId, address targetAddress) external',
    'function unlockTokens(bytes32 messageHash, address token, uint256 amount, address recipient) external',
    'event TokensLocked(bytes32 indexed messageHash, address indexed token, uint256 amount, uint256 indexed targetChainId, address targetAddress)',
    'event TokensUnlocked(bytes32 indexed messageHash, address indexed token, uint256 amount, address indexed recipient)',
  ];

  constructor() {
    this.prisma = getPrismaClient();
  }

  async initiateTransfer(request: CrossChainTransferRequest): Promise<any> {
    this.validateTransferRequest(request);

    const messageId = `transfer_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    const nonce = ethers.keccak256(ethers.toUtf8Bytes(messageId));

    const message: CrossChainMessage = {
      id: messageId,
      sourceChainId: request.sourceChainId,
      targetChainId: request.targetChainId,
      sourceAddress: request.sourceAddress.trim(),
      targetAddress: request.targetAddress.trim(),
      amount: request.amount.trim(),
      tokenAddress: request.tokenAddress?.trim(),
      nonce,
      timestamp: Date.now(),
    };

    const messageHash = this.hashMessage(message);

    try {
      const transfer = await this.prisma.crossChainTransfer.create({
        data: {
          sourceChainId: request.sourceChainId,
          targetChainId: request.targetChainId,
          sourceAddress: request.sourceAddress.trim(),
          targetAddress: request.targetAddress.trim(),
          amount: request.amount.trim(),
          tokenAddress: request.tokenAddress?.trim(),
          status: 'PENDING',
          messageHash,
        },
      });

      return {
        transfer: this.toDomainModel(transfer),
        message,
        messageHash,
      };
    } catch (error) {
      throw new BridgeError(`Failed to initiate transfer: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  async confirmLock(transferId: string, txHash: string, signatures: Signature[]): Promise<any> {
    if (!transferId || transferId.trim() === '') {
      throw new ValidationError('Transfer ID is required');
    }

    if (!txHash || txHash.trim() === '') {
      throw new ValidationError('Transaction hash is required');
    }

    if (txHash.length > MAX_TRANSACTION_HASH_LENGTH) {
      throw new ValidationError(`Transaction hash cannot exceed ${MAX_TRANSACTION_HASH_LENGTH} characters`);
    }

    if (!signatures || signatures.length === 0) {
      throw new ValidationError('At least one signature is required');
    }

    if (signatures.length > MAX_SIGNATURE_COUNT) {
      throw new ValidationError(`Signature count cannot exceed ${MAX_SIGNATURE_COUNT}`);
    }

    const threshold = 2;
    if (signatures.length < threshold) {
      throw new ValidationError(`Insufficient signatures. Required: ${threshold}`);
    }

    for (const sig of signatures) {
      if (!sig.signer || sig.signer.trim() === '') {
        throw new ValidationError('Signer address is required');
      }
      if (!sig.signature || sig.signature.trim() === '') {
        throw new ValidationError('Signature value is required');
      }
      if (sig.signature.length > MAX_SIGNATURE_LENGTH) {
        throw new ValidationError(`Signature cannot exceed ${MAX_SIGNATURE_LENGTH} characters`);
      }
      if (!CryptoUtils.isValidAddress(sig.signer.trim())) {
        throw new ValidationError('Invalid signer address format');
      }
    }

    const transfer = await this.prisma.crossChainTransfer.findUnique({
      where: { id: transferId },
    });

    if (!transfer) {
      throw new NotFoundError('Transfer not found');
    }

    if (transfer.status !== 'PENDING') {
      throw new BridgeError('Transfer is not in pending state');
    }

    let validSignatures: Signature[] = [];
    try {
      validSignatures = await this.validateSignatures(
        transfer.messageHash!,
        signatures,
        config.multisig.defaultOwners
      );
    } catch (error) {
      throw new BridgeError(`Signature validation failed: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }

    if (validSignatures.length < threshold) {
      throw new ValidationError('Invalid signatures');
    }

    try {
      const updated = await this.prisma.crossChainTransfer.update({
        where: { id: transferId },
        data: {
          status: 'LOCKED',
          sourceTxHash: txHash.trim(),
          signatures: validSignatures as any,
        },
      });

      return {
        transfer: this.toDomainModel(updated),
        canExecute: validSignatures.length >= threshold,
      };
    } catch (error) {
      throw new BridgeError(`Failed to update transfer status: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  async validateMessage(transferId: string, proof: any): Promise<any> {
    if (!transferId || transferId.trim() === '') {
      throw new ValidationError('Transfer ID is required');
    }

    if (!proof) {
      throw new ValidationError('Cross-chain proof is required');
    }

    const transfer = await this.prisma.crossChainTransfer.findUnique({
      where: { id: transferId },
    });

    if (!transfer) {
      throw new NotFoundError('Transfer not found');
    }

    if (transfer.status !== 'LOCKED') {
      throw new BridgeError('Transfer must be locked first');
    }

    let isProofValid = false;
    try {
      isProofValid = await this.verifyCrossChainProof(proof, transfer);
    } catch (error) {
      throw new BridgeError(`Proof verification failed: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }

    if (!isProofValid) {
      throw new BridgeError('Invalid cross-chain proof');
    }

    try {
      const updated = await this.prisma.crossChainTransfer.update({
        where: { id: transferId },
        data: {
          status: 'VALIDATED',
        },
      });

      return {
        transfer: this.toDomainModel(updated),
        readyForMinting: true,
      };
    } catch (error) {
      throw new BridgeError(`Failed to validate transfer: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  async executeMint(transferId: string): Promise<any> {
    if (!transferId || transferId.trim() === '') {
      throw new ValidationError('Transfer ID is required');
    }

    const transfer = await this.prisma.crossChainTransfer.findUnique({
      where: { id: transferId },
    });

    if (!transfer) {
      throw new NotFoundError('Transfer not found');
    }

    if (transfer.status !== 'VALIDATED') {
      throw new BridgeError('Transfer must be validated first');
    }

    const mintTxHash = `0x${ethers.randomBytes(32).toString('hex')}`;

    try {
      const updated = await this.prisma.crossChainTransfer.update({
        where: { id: transferId },
        data: {
          status: 'MINTED',
          targetTxHash: mintTxHash,
        },
      });

      return {
        transfer: this.toDomainModel(updated),
        mintTransaction: {
          txHash: mintTxHash,
          chainId: transfer.targetChainId,
        },
      };
    } catch (error) {
      throw new BridgeError(`Failed to execute mint: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  async confirmTransfer(transferId: string): Promise<any> {
    if (!transferId || transferId.trim() === '') {
      throw new ValidationError('Transfer ID is required');
    }

    const transfer = await this.prisma.crossChainTransfer.findUnique({
      where: { id: transferId },
    });

    if (!transfer) {
      throw new NotFoundError('Transfer not found');
    }

    if (transfer.status !== 'MINTED') {
      throw new BridgeError('Transfer must be minted first');
    }

    try {
      const updated = await this.prisma.crossChainTransfer.update({
        where: { id: transferId },
        data: {
          status: 'CONFIRMED',
        },
      });

      return {
        transfer: this.toDomainModel(updated),
        completed: true,
      };
    } catch (error) {
      throw new BridgeError(`Failed to confirm transfer: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }

  async getTransfer(transferId: string): Promise<any> {
    if (!transferId || transferId.trim() === '') {
      throw new ValidationError('Transfer ID is required');
    }

    const transfer = await this.prisma.crossChainTransfer.findUnique({
      where: { id: transferId },
    });

    if (!transfer) {
      throw new NotFoundError('Transfer not found');
    }

    return this.toDomainModel(transfer);
  }

  async getTransfers(filters?: {
    sourceChainId?: number;
    targetChainId?: number;
    sourceAddress?: string;
    targetAddress?: string;
    status?: TransferStatus;
    page?: number;
    pageSize?: number;
  }): Promise<{ items: any[]; total: number }> {
    const {
      sourceChainId,
      targetChainId,
      sourceAddress,
      targetAddress,
      status,
      page = 1,
      pageSize = 20,
    } = filters || {};

    const validatedPage = Math.max(1, page);
    const validatedPageSize = Math.min(Math.max(1, pageSize), 100);

    if (sourceChainId !== undefined && sourceChainId <= 0) {
      throw new ValidationError('Source chain ID must be positive');
    }

    if (targetChainId !== undefined && targetChainId <= 0) {
      throw new ValidationError('Target chain ID must be positive');
    }

    if (status && !VALID_STATUSES.includes(status)) {
      throw new ValidationError('Invalid transfer status');
    }

    if (sourceAddress && !CryptoUtils.isValidAddress(sourceAddress.trim())) {
      throw new ValidationError('Invalid source address format');
    }

    if (targetAddress && !CryptoUtils.isValidAddress(targetAddress.trim())) {
      throw new ValidationError('Invalid target address format');
    }

    const where: any = {};

    if (sourceChainId !== undefined) {
      where.sourceChainId = sourceChainId;
    }

    if (targetChainId !== undefined) {
      where.targetChainId = targetChainId;
    }

    if (sourceAddress) {
      where.sourceAddress = sourceAddress.trim();
    }

    if (targetAddress) {
      where.targetAddress = targetAddress.trim();
    }

    if (status) {
      where.status = status;
    }

    const [total, transfers] = await Promise.all([
      this.prisma.crossChainTransfer.count({ where }),
      this.prisma.crossChainTransfer.findMany({
        where,
        skip: (validatedPage - 1) * validatedPageSize,
        take: validatedPageSize,
        orderBy: { createdAt: 'desc' },
      }),
    ]);

    return {
      items: transfers.map(t => this.toDomainModel(t)),
      total,
    };
  }

  async getPendingTransfers(chainId: number): Promise<any[]> {
    if (!chainId || chainId <= 0) {
      throw new ValidationError('Valid chain ID is required');
    }

    const transfers = await this.prisma.crossChainTransfer.findMany({
      where: {
        OR: [
          { sourceChainId: chainId, status: 'PENDING' },
          { targetChainId: chainId, status: 'VALIDATED' },
        ],
      },
      orderBy: { createdAt: 'asc' },
    });

    return transfers.map(t => this.toDomainModel(t));
  }

  private validateTransferRequest(request: CrossChainTransferRequest): void {
    if (!request.sourceChainId || request.sourceChainId <= 0) {
      throw new ValidationError('Valid source chain ID is required');
    }

    if (!request.targetChainId || request.targetChainId <= 0) {
      throw new ValidationError('Valid target chain ID is required');
    }

    if (request.sourceChainId === request.targetChainId) {
      throw new ValidationError('Source and target chains must be different');
    }

    if (!request.amount || request.amount.trim() === '') {
      throw new ValidationError('Amount is required');
    }

    const amountStr = request.amount.trim();

    if (amountStr.length > MAX_AMOUNT_DIGITS) {
      throw new ValidationError(`Amount cannot exceed ${MAX_AMOUNT_DIGITS} digits`);
    }

    if (!/^[0-9]+$/.test(amountStr)) {
      throw new ValidationError('Amount must be a valid non-negative integer string');
    }

    try {
      const amount = BigInt(amountStr);
      if (amount <= BigInt(0)) {
        throw new ValidationError('Amount must be positive');
      }
    } catch {
      throw new ValidationError('Invalid amount format');
    }

    if (!request.sourceAddress || request.sourceAddress.trim() === '') {
      throw new ValidationError('Source address is required');
    }

    if (!CryptoUtils.isValidAddress(request.sourceAddress.trim())) {
      throw new ValidationError('Invalid source address');
    }

    if (!request.targetAddress || request.targetAddress.trim() === '') {
      throw new ValidationError('Target address is required');
    }

    if (!CryptoUtils.isValidAddress(request.targetAddress.trim())) {
      throw new ValidationError('Invalid target address');
    }

    if (request.tokenAddress && !CryptoUtils.isValidAddress(request.tokenAddress.trim())) {
      throw new ValidationError('Invalid token address');
    }
  }

  private hashMessage(message: CrossChainMessage): string {
    const types = [
      'uint256',
      'uint256',
      'address',
      'address',
      'uint256',
      'bytes32',
      'uint256',
    ];

    const values = [
      message.sourceChainId,
      message.targetChainId,
      message.sourceAddress,
      message.targetAddress,
      message.amount,
      message.nonce,
      message.timestamp,
    ];

    if (message.tokenAddress) {
      types.push('address');
      values.push(message.tokenAddress);
    }

    const packed = ethers.solidityPacked(types, values);
    return ethers.keccak256(packed);
  }

  private async validateSignatures(
    messageHash: string,
    signatures: Signature[],
    authorizedSigners: string[]
  ): Promise<Signature[]> {
    const validSignatures: Signature[] = [];
    const seenSigners = new Set<string>();

    for (const sig of signatures) {
      const signerLower = sig.signer.toLowerCase();

      if (seenSigners.has(signerLower)) {
        continue;
      }

      const isAuthorized = authorizedSigners.some(
        s => s.toLowerCase() === signerLower
      );

      if (!isAuthorized) {
        continue;
      }

      let isValid = false;
      try {
        isValid = CryptoUtils.verifySignature(
          messageHash,
          sig.signature,
          sig.signer
        );
      } catch {
        isValid = false;
      }

      if (isValid) {
        validSignatures.push(sig);
        seenSigners.add(signerLower);
      }
    }

    return validSignatures;
  }

  private async verifyCrossChainProof(proof: any, transfer: CrossChainTransfer): Promise<boolean> {
    if (!proof) {
      return false;
    }

    if (typeof proof.blockNumber === 'undefined' || proof.blockNumber === null) {
      return false;
    }

    if (typeof proof.transactionIndex === 'undefined' || proof.transactionIndex === null) {
      return false;
    }

    const blockNum = Number(proof.blockNumber);
    const txIndex = Number(proof.transactionIndex);

    if (isNaN(blockNum) || blockNum < 0) {
      return false;
    }

    if (isNaN(txIndex) || txIndex < 0) {
      return false;
    }

    return true;
  }

  private toDomainModel(transfer: CrossChainTransfer): any {
    return {
      id: transfer.id,
      sourceChainId: transfer.sourceChainId,
      targetChainId: transfer.targetChainId,
      sourceAddress: transfer.sourceAddress,
      targetAddress: transfer.targetAddress,
      amount: transfer.amount,
      tokenAddress: transfer.tokenAddress,
      status: transfer.status as TransferStatus,
      sourceTxHash: transfer.sourceTxHash,
      targetTxHash: transfer.targetTxHash,
      messageHash: transfer.messageHash,
      signatures: transfer.signatures as any,
      createdAt: transfer.createdAt,
      updatedAt: transfer.updatedAt,
    };
  }
}

export const crossChainBridgeService = new CrossChainBridgeService();
export default crossChainBridgeService;
