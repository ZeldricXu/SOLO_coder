import { PrismaClient, BlockIndex } from '@prisma/client';
import { getPrismaClient } from '../../utils/database';
import { ChainAdapterService } from '../chain-adapter/chainAdapter.service';
import { NotFoundError, ValidationError } from '../../utils/errors';
import {
  IndexedBlock,
  IndexedTransaction,
  BlockFilter,
} from '../../types';
import { cacheService } from '../../utils/cache';

export class ChainIndexerService {
  private prisma: PrismaClient;
  private chainAdapter: ChainAdapterService;
  private readonly CACHE_TTL = 3600;

  constructor() {
    this.prisma = getPrismaClient();
    this.chainAdapter = new ChainAdapterService();
  }

  private getBlockCacheKey(chainId: number, blockNumber: bigint): string {
    return `block_index:${chainId}:${blockNumber}`;
  }

  private getTransactionCacheKey(chainId: number, txHash: string): string {
    return `tx_index:${chainId}:${txHash}`;
  }

  async indexBlock(chainId: number, blockNumber: bigint): Promise<IndexedBlock> {
    const cacheKey = this.getBlockCacheKey(chainId, blockNumber);
    const cached = await cacheService.get<IndexedBlock>(cacheKey);

    if (cached) {
      return cached;
    }

    const blockData = await this.chainAdapter.getBlock(chainId, Number(blockNumber));

    if (!blockData) {
      throw new NotFoundError('Block not found');
    }

    const transactions: IndexedTransaction[] = blockData.transactions.map(tx => ({
      hash: tx.txHash || '',
      from: tx.from,
      to: tx.to,
      value: tx.value,
      input: tx.data || '0x',
      gasPrice: tx.gasPrice || '0',
      gas: tx.gasLimit,
      nonce: tx.nonce,
      status: tx.status === 'CONFIRMED' ? 'confirmed' : 'pending',
      logs: [],
    }));

    const indexedBlock: IndexedBlock = {
      chainId,
      blockNumber,
      blockHash: blockData.blockHash,
      timestamp: blockData.timestamp,
      transactionCount: transactions.length,
      gasUsed: blockData.gasUsed,
      gasLimit: blockData.gasLimit,
      transactions,
    };

    const existing = await this.prisma.blockIndex.findUnique({
      where: {
        chainId_blockNumber: {
          chainId,
          blockNumber,
        },
      },
    });

    if (!existing) {
      await this.prisma.blockIndex.create({
        data: {
          chainId,
          blockNumber,
          blockHash: blockData.blockHash,
          timestamp: blockData.timestamp,
          transactions: transactions.length,
          gasUsed: blockData.gasUsed,
          gasLimit: blockData.gasLimit,
          rawData: blockData.rawData as any,
        },
      });
    }

    await cacheService.set(cacheKey, indexedBlock, this.CACHE_TTL);

    return indexedBlock;
  }

  async getIndexedBlock(chainId: number, blockNumber: bigint): Promise<IndexedBlock> {
    const cacheKey = this.getBlockCacheKey(chainId, blockNumber);
    const cached = await cacheService.get<IndexedBlock>(cacheKey);

    if (cached) {
      return cached;
    }

    const indexed = await this.prisma.blockIndex.findUnique({
      where: {
        chainId_blockNumber: {
          chainId,
          blockNumber,
        },
      },
    });

    if (indexed) {
      const domainModel = this.toDomainModel(indexed);
      await cacheService.set(cacheKey, domainModel, this.CACHE_TTL);
      return domainModel;
    }

    return await this.indexBlock(chainId, blockNumber);
  }

  async getIndexedBlocks(filter: BlockFilter): Promise<{
    blocks: IndexedBlock[];
    total: number;
  }> {
    const { chainId, fromBlock, toBlock, fromAddress, toAddress, contractAddress } = filter;

    const where: any = { chainId };

    if (fromBlock !== undefined) {
      where.blockNumber = { ...where.blockNumber, gte: fromBlock };
    }

    if (toBlock !== undefined) {
      where.blockNumber = { ...where.blockNumber, lte: toBlock };
    }

    const [total, blocks] = await Promise.all([
      this.prisma.blockIndex.count({ where }),
      this.prisma.blockIndex.findMany({
        where,
        take: 100,
        orderBy: { blockNumber: 'desc' },
      }),
    ]);

    return {
      blocks: blocks.map(b => this.toDomainModel(b)),
      total,
    };
  }

  async indexBlocksRange(chainId: number, fromBlock: bigint, toBlock: bigint): Promise<number> {
    if (fromBlock > toBlock) {
      throw new ValidationError('fromBlock must be less than or equal to toBlock');
    }

    const range = Number(toBlock - fromBlock) + 1;
    if (range > 1000) {
      throw new ValidationError('Block range too large. Maximum 1000 blocks allowed.');
    }

    let indexedCount = 0;

    for (let blockNum = fromBlock; blockNum <= toBlock; blockNum++) {
      try {
        await this.indexBlock(chainId, blockNum);
        indexedCount++;
      } catch (error) {
        console.error(`Failed to index block ${blockNum}:`, error);
      }
    }

    return indexedCount;
  }

  async getLatestIndexedBlock(chainId: number): Promise<IndexedBlock | null> {
    const latest = await this.prisma.blockIndex.findFirst({
      where: { chainId },
      orderBy: { blockNumber: 'desc' },
    });

    if (!latest) {
      return null;
    }

    return this.toDomainModel(latest);
  }

  async searchTransactions(
    chainId: number,
    filter: {
      fromAddress?: string;
      toAddress?: string;
      contractAddress?: string;
      fromBlock?: bigint;
      toBlock?: bigint;
      page?: number;
      pageSize?: number;
    }
  ): Promise<{
    transactions: IndexedTransaction[];
    total: number;
  }> {
    const {
      fromAddress,
      toAddress,
      contractAddress,
      fromBlock,
      toBlock,
      page = 1,
      pageSize = 20,
    } = filter;

    const blockWhere: any = { chainId };

    if (fromBlock !== undefined) {
      blockWhere.blockNumber = { ...blockWhere.blockNumber, gte: fromBlock };
    }

    if (toBlock !== undefined) {
      blockWhere.blockNumber = { ...blockWhere.blockNumber, lte: toBlock };
    }

    const blocks = await this.prisma.blockIndex.findMany({
      where: blockWhere,
      orderBy: { blockNumber: 'desc' },
      take: 100,
    });

    const allTransactions: IndexedTransaction[] = [];

    for (const block of blocks) {
      const blockData = await this.chainAdapter.getBlock(chainId, Number(block.blockNumber));
      
      if (blockData && blockData.transactions) {
        for (const tx of blockData.transactions) {
          let matches = true;

          if (fromAddress && tx.from.toLowerCase() !== fromAddress.toLowerCase()) {
            matches = false;
          }

          if (toAddress && tx.to && tx.to.toLowerCase() !== toAddress.toLowerCase()) {
            matches = false;
          }

          if (contractAddress && tx.to && tx.to.toLowerCase() !== contractAddress.toLowerCase()) {
            matches = false;
          }

          if (matches) {
            allTransactions.push({
              hash: tx.txHash || '',
              from: tx.from,
              to: tx.to,
              value: tx.value,
              input: tx.data || '0x',
              gasPrice: tx.gasPrice || '0',
              gas: tx.gasLimit,
              nonce: tx.nonce,
              status: 'confirmed',
              logs: [],
            });
          }
        }
      }
    }

    const startIndex = (page - 1) * pageSize;
    const endIndex = startIndex + pageSize;
    const paginatedTransactions = allTransactions.slice(startIndex, endIndex);

    return {
      transactions: paginatedTransactions,
      total: allTransactions.length,
    };
  }

  async getBlockTransactionCount(chainId: number, blockNumber: bigint): Promise<number> {
    const block = await this.getIndexedBlock(chainId, blockNumber);
    return block.transactionCount;
  }

  async getTransactionByHash(
    chainId: number,
    txHash: string
  ): Promise<IndexedTransaction | null> {
    const cacheKey = this.getTransactionCacheKey(chainId, txHash);
    const cached = await cacheService.get<IndexedTransaction>(cacheKey);

    if (cached) {
      return cached;
    }

    const tx = await this.chainAdapter.getTransaction(chainId, txHash);

    if (!tx) {
      return null;
    }

    const indexedTx: IndexedTransaction = {
      hash: tx.hash,
      from: tx.from,
      to: tx.to || '',
      value: tx.value.toString(),
      input: tx.data,
      gasPrice: tx.gasPrice?.toString() || '0',
      gas: tx.gasLimit.toString(),
      nonce: tx.nonce,
      status: tx.blockNumber ? 'confirmed' : 'pending',
      logs: [],
    };

    await cacheService.set(cacheKey, indexedTx, this.CACHE_TTL);

    return indexedTx;
  }

  async getContractTransactions(
    chainId: number,
    contractAddress: string,
    fromBlock?: bigint,
    toBlock?: bigint,
    page: number = 1,
    pageSize: number = 20
  ): Promise<{
    transactions: IndexedTransaction[];
    total: number;
  }> {
    return await this.searchTransactions(chainId, {
      contractAddress,
      fromBlock,
      toBlock,
      page,
      pageSize,
    });
  }

  async getAddressTransactions(
    chainId: number,
    address: string,
    fromBlock?: bigint,
    toBlock?: bigint,
    page: number = 1,
    pageSize: number = 20
  ): Promise<{
    transactions: IndexedTransaction[];
    total: number;
  }> {
    const [fromTxResult, toTxResult] = await Promise.all([
      this.searchTransactions(chainId, {
        fromAddress: address,
        fromBlock,
        toBlock,
        page: 1,
        pageSize: 1000,
      }),
      this.searchTransactions(chainId, {
        toAddress: address,
        fromBlock,
        toBlock,
        page: 1,
        pageSize: 1000,
      }),
    ]);

    const allTxMap = new Map<string, IndexedTransaction>();
    
    [...fromTxResult.transactions, ...toTxResult.transactions].forEach(tx => {
      allTxMap.set(tx.hash, tx);
    });

    const allTransactions = Array.from(allTxMap.values()).sort((a, b) => {
      return b.nonce - a.nonce;
    });

    const startIndex = (page - 1) * pageSize;
    const endIndex = startIndex + pageSize;
    const paginatedTransactions = allTransactions.slice(startIndex, endIndex);

    return {
      transactions: paginatedTransactions,
      total: allTransactions.length,
    };
  }

  async getBlockRange(
    chainId: number,
    limit: number = 20
  ): Promise<IndexedBlock[]> {
    const blocks = await this.prisma.blockIndex.findMany({
      where: { chainId },
      orderBy: { blockNumber: 'desc' },
      take: Math.min(limit, 100),
    });

    return blocks.map(b => this.toDomainModel(b));
  }

  async deleteBlockIndex(chainId: number, blockNumber: bigint): Promise<boolean> {
    const existing = await this.prisma.blockIndex.findUnique({
      where: {
        chainId_blockNumber: {
          chainId,
          blockNumber,
        },
      },
    });

    if (!existing) {
      throw new NotFoundError('Block index not found');
    }

    await this.prisma.blockIndex.delete({
      where: {
        chainId_blockNumber: {
          chainId,
          blockNumber,
        },
      },
    });

    const cacheKey = this.getBlockCacheKey(chainId, blockNumber);
    await cacheService.delete(cacheKey);

    return true;
  }

  private toDomainModel(block: BlockIndex): IndexedBlock {
    return {
      chainId: block.chainId,
      blockNumber: block.blockNumber,
      blockHash: block.blockHash,
      timestamp: block.timestamp,
      transactionCount: block.transactions,
      gasUsed: block.gasUsed,
      gasLimit: block.gasLimit,
      transactions: [],
    };
  }
}

export const chainIndexerService = new ChainIndexerService();
export default chainIndexerService;
