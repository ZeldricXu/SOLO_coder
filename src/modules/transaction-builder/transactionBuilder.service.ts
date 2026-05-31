import { PrismaClient, Transaction } from '@prisma/client';
import { getPrismaClient } from '../../utils/database';
import { CryptoUtils } from '../../utils/crypto';
import { config } from '../../config';
import { NotFoundError, ValidationError } from '../../utils/errors';
import {
  BuildTransactionRequest,
  SignTransactionRequest,
  Transaction as ITransaction,
  TransactionStatus,
  Signature,
  MultisigConfig,
} from '../../types';
import { cacheService } from '../../utils/cache';
import { ethers } from 'ethers';

export class TransactionBuilderService {
  private prisma: PrismaClient;
  private readonly CACHE_TTL = 3600;

  constructor() {
    this.prisma = getPrismaClient();
  }

  private getCacheKey(transactionId: string): string {
    return `transaction:${transactionId}`;
  }

  async buildTransaction(request: BuildTransactionRequest): Promise<ITransaction> {
    this.validateTransactionRequest(request);

    const {
      chainId,
      from,
      to,
      value,
      data,
      gasPrice,
      gasLimit,
      maxPriorityFeePerGas,
      maxFeePerGas,
      nonce,
      multisig,
    } = request;

    const actualNonce = nonce !== undefined ? nonce : await this.getNextNonce(from, chainId);

    const estimatedGasLimit = gasLimit || await this.estimateGasLimit(
      chainId,
      from,
      to,
      value,
      data
    );

    const transaction = await this.prisma.transaction.create({
      data: {
        chainId,
        from,
        to,
        value,
        data,
        gasPrice,
        gasLimit: estimatedGasLimit,
        nonce: actualNonce,
        status: 'PENDING',
      },
    });

    const cacheKey = this.getCacheKey(transaction.id);
    const domainModel = this.toDomainModel(transaction);
    await cacheService.set(cacheKey, domainModel, this.CACHE_TTL);

    return domainModel;
  }

  async signTransaction(request: SignTransactionRequest): Promise<ITransaction> {
    const { transactionId, signer, signature } = request;

    const transaction = await this.prisma.transaction.findUnique({
      where: { id: transactionId },
    });

    if (!transaction) {
      throw new NotFoundError('Transaction not found');
    }

    if (transaction.status !== 'PENDING' && transaction.status !== 'SIGNED') {
      throw new ValidationError('Transaction cannot be signed in current state');
    }

    const messageToSign = this.getTransactionMessage(transaction);
    const isValidSignature = CryptoUtils.verifySignature(
      messageToSign,
      signature,
      signer
    );

    if (!isValidSignature) {
      throw new ValidationError('Invalid signature');
    }

    const updated = await this.prisma.transaction.update({
      where: { id: transactionId },
      data: {
        status: 'SIGNED',
      },
    });

    const cacheKey = this.getCacheKey(transactionId);
    await cacheService.delete(cacheKey);

    return this.toDomainModel(updated);
  }

  async getTransaction(transactionId: string): Promise<ITransaction> {
    const cacheKey = this.getCacheKey(transactionId);
    const cached = await cacheService.get<ITransaction>(cacheKey);

    if (cached) {
      return cached;
    }

    const transaction = await this.prisma.transaction.findUnique({
      where: { id: transactionId },
    });

    if (!transaction) {
      throw new NotFoundError('Transaction not found');
    }

    const domainModel = this.toDomainModel(transaction);
    await cacheService.set(cacheKey, domainModel, this.CACHE_TTL);

    return domainModel;
  }

  async getTransactionByHash(txHash: string): Promise<ITransaction> {
    const transaction = await this.prisma.transaction.findUnique({
      where: { txHash },
    });

    if (!transaction) {
      throw new NotFoundError('Transaction not found');
    }

    return this.toDomainModel(transaction);
  }

  async getTransactions(filters?: {
    chainId?: number;
    from?: string;
    to?: string;
    status?: TransactionStatus;
    page?: number;
    pageSize?: number;
  }): Promise<{ items: ITransaction[]; total: number }> {
    const {
      chainId,
      from,
      to,
      status,
      page = 1,
      pageSize = 20,
    } = filters || {};

    const where: any = {};

    if (chainId !== undefined) {
      where.chainId = chainId;
    }

    if (from) {
      where.from = from;
    }

    if (to) {
      where.to = to;
    }

    if (status) {
      where.status = status;
    }

    const [total, transactions] = await Promise.all([
      this.prisma.transaction.count({ where }),
      this.prisma.transaction.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
      }),
    ]);

    return {
      items: transactions.map(t => this.toDomainModel(t)),
      total,
    };
  }

  async updateTransactionStatus(
    transactionId: string,
    status: TransactionStatus,
    txHash?: string,
    blockNumber?: bigint,
    errorMessage?: string
  ): Promise<ITransaction> {
    const transaction = await this.prisma.transaction.findUnique({
      where: { id: transactionId },
    });

    if (!transaction) {
      throw new NotFoundError('Transaction not found');
    }

    const updated = await this.prisma.transaction.update({
      where: { id: transactionId },
      data: {
        status,
        txHash,
        blockNumber,
        errorMessage,
      },
    });

    const cacheKey = this.getCacheKey(transactionId);
    await cacheService.delete(cacheKey);

    return this.toDomainModel(updated);
  }

  async buildMultisigTransaction(
    request: BuildTransactionRequest & { multisig: MultisigConfig }
  ): Promise<{ transaction: ITransaction; unsignedTx: string }> {
    const transaction = await this.buildTransaction(request);

    const unsignedTx = this.serializeUnsignedTransaction(
      transaction,
      request.multisig
    );

    return {
      transaction,
      unsignedTx,
    };
  }

  async signMultisigTransaction(
    transactionId: string,
    signer: string,
    signature: string
  ): Promise<{ transaction: ITransaction; signatures: Signature[]; thresholdReached: boolean }> {
    const transaction = await this.prisma.transaction.findUnique({
      where: { id: transactionId },
    });

    if (!transaction) {
      throw new NotFoundError('Transaction not found');
    }

    const messageToSign = this.getTransactionMessage(transaction);
    const isValidSignature = CryptoUtils.verifySignature(
      messageToSign,
      signature,
      signer
    );

    if (!isValidSignature) {
      throw new ValidationError('Invalid signature');
    }

    const existingSignatures: Signature[] = [];
    const updatedSignatures = [...existingSignatures, {
      signer,
      signature,
      timestamp: Date.now(),
    }];

    const threshold = config.multisig.defaultThreshold;
    const thresholdReached = updatedSignatures.length >= threshold;

    const updated = await this.prisma.transaction.update({
      where: { id: transactionId },
      data: {
        status: thresholdReached ? 'SIGNED' : 'PENDING',
      },
    });

    const cacheKey = this.getCacheKey(transactionId);
    await cacheService.delete(cacheKey);

    return {
      transaction: this.toDomainModel(updated),
      signatures: updatedSignatures,
      thresholdReached,
    };
  }

  async getPendingNonces(address: string, chainId: number): Promise<{ pending: number; confirmed: number }> {
    const pendingTransactions = await this.prisma.transaction.count({
      where: {
        from: address,
        chainId,
        status: { in: ['PENDING', 'SIGNED', 'BROADCAST'] },
      },
    });

    const lastConfirmed = await this.prisma.transaction.findFirst({
      where: {
        from: address,
        chainId,
        status: { in: ['CONFIRMED', 'FAILED'] },
      },
      orderBy: { nonce: 'desc' },
    });

    const confirmedNonce = lastConfirmed ? lastConfirmed.nonce : -1;
    const nextNonce = confirmedNonce + 1 + pendingTransactions;

    return {
      pending: nextNonce,
      confirmed: confirmedNonce + 1,
    };
  }

  async optimizeGas(
    chainId: number,
    from: string,
    to: string,
    value: string,
    data?: string
  ): Promise<{ gasLimit: string; gasPrice?: string; maxFeePerGas?: string; maxPriorityFeePerGas?: string }> {
    const baseGasLimit = BigInt(21000);
    let gasLimit = baseGasLimit;

    if (data && data !== '0x') {
      const dataBytes = Buffer.from(data.slice(2), 'hex');
      for (const byte of dataBytes) {
        gasLimit += byte === 0 ? BigInt(4) : BigInt(16);
      }
    }

    gasLimit = gasLimit + (gasLimit / BigInt(10));

    return {
      gasLimit: gasLimit.toString(),
    };
  }

  private validateTransactionRequest(request: BuildTransactionRequest): void {
    if (!request.chainId || request.chainId <= 0) {
      throw new ValidationError('Valid chain ID is required');
    }

    if (!request.from || !CryptoUtils.isValidAddress(request.from)) {
      throw new ValidationError('Valid from address is required');
    }

    if (!request.to || !CryptoUtils.isValidAddress(request.to)) {
      throw new ValidationError('Valid to address is required');
    }

    if (!request.value) {
      throw new ValidationError('Value is required');
    }

    try {
      BigInt(request.value);
    } catch {
      throw new ValidationError('Invalid value format');
    }

    if (request.gasLimit) {
      try {
        BigInt(request.gasLimit);
      } catch {
        throw new ValidationError('Invalid gas limit format');
      }
    }

    if (request.gasPrice) {
      try {
        BigInt(request.gasPrice);
      } catch {
        throw new ValidationError('Invalid gas price format');
      }
    }
  }

  private async getNextNonce(address: string, chainId: number): Promise<number> {
    const nonces = await this.getPendingNonces(address, chainId);
    return nonces.pending;
  }

  private async estimateGasLimit(
    chainId: number,
    from: string,
    to: string,
    value: string,
    data?: string
  ): Promise<string> {
    const optimized = await this.optimizeGas(chainId, from, to, value, data);
    return optimized.gasLimit;
  }

  private getTransactionMessage(transaction: Transaction): string {
    const message = JSON.stringify({
      chainId: transaction.chainId,
      from: transaction.from,
      to: transaction.to,
      value: transaction.value,
      data: transaction.data || '0x',
      gasLimit: transaction.gasLimit,
      gasPrice: transaction.gasPrice || '0',
      nonce: transaction.nonce,
    });

    return message;
  }

  private serializeUnsignedTransaction(
    transaction: ITransaction,
    multisig: MultisigConfig
  ): string {
    const tx = {
      to: transaction.to,
      value: transaction.value,
      data: transaction.data || '0x',
      nonce: transaction.nonce,
      gasLimit: transaction.gasLimit,
      chainId: transaction.chainId,
      threshold: multisig.threshold,
      owners: multisig.owners,
    };

    return JSON.stringify(tx);
  }

  private toDomainModel(transaction: Transaction): ITransaction {
    return {
      id: transaction.id,
      chainId: transaction.chainId,
      from: transaction.from,
      to: transaction.to,
      value: transaction.value,
      data: transaction.data || undefined,
      gasPrice: transaction.gasPrice || undefined,
      gasLimit: transaction.gasLimit,
      nonce: transaction.nonce,
      status: transaction.status as TransactionStatus,
      txHash: transaction.txHash || undefined,
      blockNumber: transaction.blockNumber || undefined,
      errorMessage: transaction.errorMessage || undefined,
      createdAt: transaction.createdAt,
      updatedAt: transaction.updatedAt,
    };
  }
}

export const transactionBuilderService = new TransactionBuilderService();
export default transactionBuilderService;
