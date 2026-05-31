import { PrismaClient, ChainConfig } from '@prisma/client';
import { getPrismaClient } from '../../utils/database';
import { config } from '../../config';
import { NotFoundError, ValidationError, ChainError } from '../../utils/errors';
import {
  ChainConfig as IChainConfig,
  BlockData,
  TransactionReceipt,
  Transaction,
} from '../../types';
import { cacheService } from '../../utils/cache';
import { ethers } from 'ethers';

export class ChainAdapterService {
  private prisma: PrismaClient;
  private providers: Map<number, ethers.JsonRpcProvider> = new Map();
  private readonly CACHE_TTL = 60;

  constructor() {
    this.prisma = getPrismaClient();
    this.initializeProviders();
  }

  private initializeProviders(): void {
    const chainRpcMap: Record<number, string | undefined> = {
      1: config.chains.eth,
      3: config.chains.eth,
      4: config.chains.eth,
      5: config.chains.eth,
      56: config.chains.bsc,
      97: config.chains.bsc,
      137: config.chains.polygon,
      80001: config.chains.polygon,
      42161: config.chains.arbitrum,
      421613: config.chains.arbitrum,
      10: config.chains.optimism,
      69: config.chains.optimism,
    };

    for (const [chainId, rpcUrl] of Object.entries(chainRpcMap)) {
      if (rpcUrl) {
        try {
          const provider = new ethers.JsonRpcProvider(rpcUrl);
          this.providers.set(Number(chainId), provider);
        } catch (error) {
          console.warn(`Failed to initialize provider for chain ${chainId}:`, error);
        }
      }
    }
  }

  async getProvider(chainId: number): Promise<ethers.JsonRpcProvider> {
    let provider = this.providers.get(chainId);

    if (!provider) {
      const chainConfig = await this.prisma.chainConfig.findUnique({
        where: { chainId },
      });

      if (!chainConfig) {
        throw new ValidationError(`No RPC configuration found for chain ${chainId}`);
      }

      if (!chainConfig.isActive) {
        throw new ChainError(`Chain ${chainId} is not active`, chainId);
      }

      provider = new ethers.JsonRpcProvider(chainConfig.rpcUrl);
      this.providers.set(chainId, provider);
    }

    return provider;
  }

  async getBlockNumber(chainId: number): Promise<bigint> {
    const cacheKey = `block_number:${chainId}`;
    const cached = await cacheService.get<string>(cacheKey);

    if (cached) {
      return BigInt(cached);
    }

    const provider = await this.getProvider(chainId);
    const blockNumber = await provider.getBlockNumber();
    const blockNumberBigInt = BigInt(blockNumber);

    await cacheService.set(cacheKey, blockNumberBigInt.toString(), 10);

    return blockNumberBigInt;
  }

  async getBlock(chainId: number, blockNumberOrHash: number | string): Promise<BlockData | null> {
    const provider = await this.getProvider(chainId);

    let block;
    if (typeof blockNumberOrHash === 'number') {
      block = await provider.getBlock(blockNumberOrHash, true);
    } else {
      block = await provider.getBlock(blockNumberOrHash, true);
    }

    if (!block) {
      return null;
    }

    const transactions: Transaction[] = (block.prefetchedTransactions || []).map(tx => ({
      id: tx.hash,
      chainId,
      from: tx.from,
      to: tx.to || '',
      value: tx.value.toString(),
      data: tx.data,
      gasPrice: tx.gasPrice?.toString(),
      gasLimit: tx.gasLimit.toString(),
      nonce: tx.nonce,
      status: 'CONFIRMED',
      txHash: tx.hash,
      blockNumber: blockNumberOrHash ? BigInt(blockNumberOrHash) : undefined,
      createdAt: new Date(block.timestamp * 1000),
      updatedAt: new Date(block.timestamp * 1000),
    }));

    return {
      chainId,
      blockNumber: BigInt(block.number),
      blockHash: block.hash,
      timestamp: new Date(block.timestamp * 1000),
      transactions,
      gasUsed: block.gasUsed.toBigInt(),
      gasLimit: block.gasLimit.toBigInt(),
      rawData: block as any,
    };
  }

  async getTransaction(chainId: number, txHash: string): Promise<ethers.TransactionResponse | null> {
    const cacheKey = `transaction:${chainId}:${txHash}`;
    const cached = await cacheService.get<ethers.TransactionResponse>(cacheKey);

    if (cached) {
      return cached;
    }

    const provider = await this.getProvider(chainId);
    const transaction = await provider.getTransaction(txHash);

    if (transaction) {
      await cacheService.set(cacheKey, transaction, 3600);
    }

    return transaction;
  }

  async getTransactionReceipt(chainId: number, txHash: string): Promise<TransactionReceipt | null> {
    const cacheKey = `receipt:${chainId}:${txHash}`;
    const cached = await cacheService.get<TransactionReceipt>(cacheKey);

    if (cached) {
      return cached;
    }

    const provider = await this.getProvider(chainId);
    const receipt = await provider.getTransactionReceipt(txHash);

    if (!receipt) {
      return null;
    }

    const formattedReceipt: TransactionReceipt = {
      txHash: receipt.hash,
      status: receipt.status === 1,
      blockNumber: BigInt(receipt.blockNumber),
      blockHash: receipt.blockHash,
      gasUsed: receipt.gasUsed.toBigInt(),
      cumulativeGasUsed: receipt.cumulativeGasUsed.toBigInt(),
      logs: receipt.logs.map(log => ({
        address: log.address,
        topics: log.topics,
        data: log.data,
        blockNumber: BigInt(log.blockNumber),
        transactionHash: log.transactionHash,
        logIndex: log.index,
      })),
    };

    await cacheService.set(cacheKey, formattedReceipt, 3600);

    return formattedReceipt;
  }

  async getBalance(chainId: number, address: string, blockTag?: number | string): Promise<string> {
    const provider = await this.getProvider(chainId);
    const balance = await provider.getBalance(address, blockTag as any);
    return balance.toString();
  }

  async getNonce(chainId: number, address: string, blockTag?: number | string): Promise<number> {
    const provider = await this.getProvider(chainId);
    return await provider.getTransactionCount(address, blockTag as any);
  }

  async call(chainId: number, to: string, data: string, from?: string): Promise<string> {
    const provider = await this.getProvider(chainId);

    const tx = {
      to,
      data,
      from,
    };

    return await provider.call(tx);
  }

  async estimateGas(
    chainId: number,
    to: string,
    data: string,
    value?: string,
    from?: string
  ): Promise<string> {
    const provider = await this.getProvider(chainId);

    const tx: any = {
      to,
      data,
    };

    if (value) {
      tx.value = value;
    }

    if (from) {
      tx.from = from;
    }

    const gasEstimate = await provider.estimateGas(tx);
    return gasEstimate.toString();
  }

  async getGasPrice(chainId: number): Promise<string> {
    const cacheKey = `gas_price:${chainId}`;
    const cached = await cacheService.get<string>(cacheKey);

    if (cached) {
      return cached;
    }

    const provider = await this.getProvider(chainId);
    const gasPrice = await provider.getGasPrice();
    const gasPriceStr = gasPrice.toString();

    await cacheService.set(cacheKey, gasPriceStr, 10);

    return gasPriceStr;
  }

  async getFeeData(chainId: number): Promise<{
    gasPrice?: string;
    maxFeePerGas?: string;
    maxPriorityFeePerGas?: string;
  }> {
    const cacheKey = `fee_data:${chainId}`;
    const cached = await cacheService.get<any>(cacheKey);

    if (cached) {
      return cached;
    }

    const provider = await this.getProvider(chainId);
    const feeData = await provider.getFeeData();

    const result = {
      gasPrice: feeData.gasPrice?.toString(),
      maxFeePerGas: feeData.maxFeePerGas?.toString(),
      maxPriorityFeePerGas: feeData.maxPriorityFeePerGas?.toString(),
    };

    await cacheService.set(cacheKey, result, 10);

    return result;
  }

  async broadcastTransaction(chainId: number, signedTx: string): Promise<string> {
    const provider = await this.getProvider(chainId);
    const tx = await provider.broadcastTransaction(signedTx);
    return tx.hash;
  }

  async waitForTransaction(
    chainId: number,
    txHash: string,
    confirmations: number = 1,
    timeout: number = 60000
  ): Promise<TransactionReceipt | null> {
    const provider = await this.getProvider(chainId);

    try {
      const receipt = await provider.waitForTransaction(txHash, confirmations, timeout);

      if (!receipt) {
        return null;
      }

      return {
        txHash: receipt.hash,
        status: receipt.status === 1,
        blockNumber: BigInt(receipt.blockNumber),
        blockHash: receipt.blockHash,
        gasUsed: receipt.gasUsed.toBigInt(),
        cumulativeGasUsed: receipt.cumulativeGasUsed.toBigInt(),
        logs: receipt.logs.map(log => ({
          address: log.address,
          topics: log.topics,
          data: log.data,
          blockNumber: BigInt(log.blockNumber),
          transactionHash: log.transactionHash,
          logIndex: log.index,
        })),
      };
    } catch (error) {
      console.error('Error waiting for transaction:', error);
      return null;
    }
  }

  async getChainConfig(chainId: number): Promise<IChainConfig> {
    const cacheKey = `chain_config:${chainId}`;
    const cached = await cacheService.get<IChainConfig>(cacheKey);

    if (cached) {
      return cached;
    }

    const chainConfig = await this.prisma.chainConfig.findUnique({
      where: { chainId },
    });

    if (!chainConfig) {
      throw new NotFoundError('Chain configuration not found');
    }

    const domainModel = this.toDomainModel(chainConfig);
    await cacheService.set(cacheKey, domainModel, this.CACHE_TTL * 60);

    return domainModel;
  }

  async listChainConfigs(): Promise<IChainConfig[]> {
    const chains = await this.prisma.chainConfig.findMany({
      where: { isActive: true },
      orderBy: { chainId: 'asc' },
    });

    return chains.map(c => this.toDomainModel(c));
  }

  async addChainConfig(configData: Partial<IChainConfig>): Promise<IChainConfig> {
    if (!configData.chainId) {
      throw new ValidationError('Chain ID is required');
    }

    if (!configData.name) {
      throw new ValidationError('Chain name is required');
    }

    if (!configData.rpcUrl) {
      throw new ValidationError('RPC URL is required');
    }

    const existing = await this.prisma.chainConfig.findUnique({
      where: { chainId: configData.chainId },
    });

    if (existing) {
      throw new ValidationError('Chain configuration already exists');
    }

    const chainConfig = await this.prisma.chainConfig.create({
      data: {
        chainId: configData.chainId,
        name: configData.name,
        rpcUrl: configData.rpcUrl,
        explorerUrl: configData.explorerUrl,
        nativeCurrency: configData.nativeCurrency || {
          name: 'Ether',
          symbol: 'ETH',
          decimals: 18,
        },
        isActive: configData.isActive !== false,
      },
    });

    const cacheKey = `chain_config:${configData.chainId}`;
    await cacheService.delete(cacheKey);

    if (chainConfig.isActive) {
      try {
        const provider = new ethers.JsonRpcProvider(chainConfig.rpcUrl);
        this.providers.set(chainConfig.chainId, provider);
      } catch (error) {
        console.warn(`Failed to initialize provider for chain ${chainConfig.chainId}:`, error);
      }
    }

    return this.toDomainModel(chainConfig);
  }

  async updateChainConfig(chainId: number, configData: Partial<IChainConfig>): Promise<IChainConfig> {
    const existing = await this.prisma.chainConfig.findUnique({
      where: { chainId },
    });

    if (!existing) {
      throw new NotFoundError('Chain configuration not found');
    }

    const updated = await this.prisma.chainConfig.update({
      where: { chainId },
      data: {
        name: configData.name,
        rpcUrl: configData.rpcUrl,
        explorerUrl: configData.explorerUrl,
        nativeCurrency: configData.nativeCurrency,
        isActive: configData.isActive,
      },
    });

    const cacheKey = `chain_config:${chainId}`;
    await cacheService.delete(cacheKey);

    if (updated.isActive && updated.rpcUrl !== existing.rpcUrl) {
      try {
        const provider = new ethers.JsonRpcProvider(updated.rpcUrl);
        this.providers.set(chainId, provider);
      } catch (error) {
        console.warn(`Failed to initialize provider for chain ${chainId}:`, error);
      }
    } else if (!updated.isActive) {
      this.providers.delete(chainId);
    }

    return this.toDomainModel(updated);
  }

  private toDomainModel(config: ChainConfig): IChainConfig {
    return {
      chainId: config.chainId,
      name: config.name,
      rpcUrl: config.rpcUrl,
      explorerUrl: config.explorerUrl || undefined,
      nativeCurrency: config.nativeCurrency as any,
      isActive: config.isActive,
    };
  }
}

export const chainAdapterService = new ChainAdapterService();
export default chainAdapterService;
