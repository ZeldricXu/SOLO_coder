import type { Logger } from '@shared/logger';
import type { CachePort } from '@shared/cache';
import type { ChainInteractionPort, ChainInteractionProvider } from '@core/ports/chainInteraction.port';
import type { Block, Transaction, TransactionReceipt, LogEntry } from '@core/domain/blockchain';
import type { ChainId, Address, Hash, HexString, GasAmount, WeiAmount } from '@shared/types';
import { NotFoundError, ConflictError, ChainInteractionError } from '@shared/errors';

export class ChainInteractionService implements ChainInteractionProvider {
  private clients: Map<ChainId, ChainInteractionPort> = new Map();

  constructor(
    private readonly clientFactory: (config: { chainId: ChainId; rpcUrl: string }) => ChainInteractionPort,
    private readonly logger: Logger,
    private readonly config: {
      chains: Array<{ chainId: ChainId; rpcUrl: string; name?: string }>;
      defaultChainId?: ChainId;
    },
    private readonly cache?: CachePort,
    private readonly cacheTTL = 15000
  ) {
    for (const chainConfig of config.chains) {
      const client = this.clientFactory(chainConfig);
      this.clients.set(chainConfig.chainId, client);
      this.logger.info('Registered chain client', {
        chainId: chainConfig.chainId,
        name: chainConfig.name || `Chain ${chainConfig.chainId}`,
      });
    }
  }

  getClient(chainId: ChainId): ChainInteractionPort {
    const client = this.clients.get(chainId);
    if (!client) {
      throw new ConflictError(`No client registered for chain ${chainId}`);
    }
    return client;
  }

  addClient(chainId: ChainId, client: ChainInteractionPort): void {
    if (this.clients.has(chainId)) {
      throw new ConflictError(`Client for chain ${chainId} already exists`);
    }
    this.clients.set(chainId, client);
    this.logger.info('Added chain client', { chainId });
  }

  removeClient(chainId: ChainId): void {
    const deleted = this.clients.delete(chainId);
    if (deleted) {
      this.logger.info('Removed chain client', { chainId });
    }
  }

  getSupportedChains(): ChainId[] {
    return Array.from(this.clients.keys());
  }

  private getCacheKey(chainId: ChainId, method: string, ...args: string[]): string {
    return `chain:${chainId}:${method}:${args.join(':')}`;
  }

  async getBlockNumber(chainId: ChainId): Promise<bigint> {
    const client = this.getClient(chainId);
    return client.getBlockNumber();
  }

  async getBlock(chainId: ChainId, blockHashOrNumber: Hash | bigint): Promise<Block | null> {
    const cacheKey = this.getCacheKey(chainId, 'block', String(blockHashOrNumber));

    if (this.cache) {
      const cached = await this.cache.get<Block>(cacheKey);
      if (cached) return cached;
    }

    const client = this.getClient(chainId);
    const block = await client.getBlock(blockHashOrNumber);

    if (block && this.cache) {
      await this.cache.set(cacheKey, block, this.cacheTTL);
    }

    return block;
  }

  async getBalance(chainId: ChainId, address: Address, blockTag?: 'latest' | 'pending' | bigint): Promise<WeiAmount> {
    const cacheKey = this.getCacheKey(chainId, 'balance', address, String(blockTag || 'latest'));

    if (this.cache && blockTag === 'latest') {
      const cached = await this.cache.get<WeiAmount>(cacheKey);
      if (cached !== null) return cached;
    }

    const client = this.getClient(chainId);
    const balance = await client.getBalance(address, blockTag);

    if (this.cache && blockTag === 'latest') {
      await this.cache.set(cacheKey, balance, this.cacheTTL);
    }

    return balance;
  }

  async getNonce(chainId: ChainId, address: Address, blockTag?: 'latest' | 'pending'): Promise<number> {
    const client = this.getClient(chainId);
    return client.getNonce(address, blockTag);
  }

  async getTransaction(chainId: ChainId, hash: Hash): Promise<Transaction | null> {
    const cacheKey = this.getCacheKey(chainId, 'tx', hash);

    if (this.cache) {
      const cached = await this.cache.get<Transaction>(cacheKey);
      if (cached) return cached;
    }

    const client = this.getClient(chainId);
    const tx = await client.getTransaction(hash);

    if (tx && this.cache) {
      await this.cache.set(cacheKey, tx, this.cacheTTL * 10);
    }

    return tx;
  }

  async getTransactionReceipt(chainId: ChainId, hash: Hash): Promise<TransactionReceipt | null> {
    const cacheKey = this.getCacheKey(chainId, 'receipt', hash);

    if (this.cache) {
      const cached = await this.cache.get<TransactionReceipt>(cacheKey);
      if (cached) return cached;
    }

    const client = this.getClient(chainId);
    const receipt = await client.getTransactionReceipt(hash);

    if (receipt && this.cache) {
      await this.cache.set(cacheKey, receipt, this.cacheTTL * 100);
    }

    return receipt;
  }

  async getGasPrice(chainId: ChainId): Promise<WeiAmount> {
    const client = this.getClient(chainId);
    return client.getGasPrice();
  }

  async getFeePerGas(chainId: ChainId): Promise<{
    baseFeePerGas: WeiAmount;
    maxPriorityFeePerGas: WeiAmount;
  }> {
    const cacheKey = this.getCacheKey(chainId, 'fees');

    if (this.cache) {
      const cached = await this.cache.get<{ baseFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount }>(cacheKey);
      if (cached) return cached;
    }

    const client = this.getClient(chainId);
    const fees = await client.getFeePerGas();

    if (this.cache) {
      await this.cache.set(cacheKey, fees, 5000);
    }

    return fees;
  }

  async estimateGas(
    chainId: ChainId,
    transaction: {
      to?: Address;
      from?: Address;
      value?: WeiAmount;
      data?: HexString;
      gasPrice?: WeiAmount;
    }
  ): Promise<GasAmount> {
    const client = this.getClient(chainId);
    return client.estimateGas(transaction);
  }

  async call(
    chainId: ChainId,
    transaction: {
      to: Address;
      from?: Address;
      value?: WeiAmount;
      data: HexString;
    },
    blockTag?: 'latest' | 'pending' | bigint
  ): Promise<HexString> {
    const client = this.getClient(chainId);
    return client.call(transaction, blockTag);
  }

  async sendRawTransaction(chainId: ChainId, signedTransaction: HexString): Promise<Hash> {
    const client = this.getClient(chainId);
    this.logger.info('Sending raw transaction', { chainId });

    try {
      const hash = await client.sendRawTransaction(signedTransaction);
      this.logger.info('Transaction submitted', { chainId, hash });
      return hash;
    } catch (error) {
      this.logger.error('Failed to send transaction', { chainId, error });
      throw new ChainInteractionError(
        chainId,
        error instanceof Error ? error.message : 'Transaction submission failed',
        error instanceof Error ? error : undefined
      );
    }
  }

  async getLogs(
    chainId: ChainId,
    filter: {
      address?: Address | Address[];
      topics?: (HexString | HexString[] | null)[];
      fromBlock?: bigint | 'latest';
      toBlock?: bigint | 'latest';
    }
  ): Promise<LogEntry[]> {
    const client = this.getClient(chainId);
    return client.getLogs(filter);
  }

  async waitForTransaction(
    chainId: ChainId,
    hash: Hash,
    confirmations = 1,
    timeout = 120000
  ): Promise<TransactionReceipt | null> {
    const client = this.getClient(chainId);
    this.logger.info('Waiting for transaction', { chainId, hash, confirmations });

    const receipt = await client.waitForTransaction(hash, confirmations, timeout);

    if (receipt) {
      this.logger.info('Transaction confirmed', {
        chainId,
        hash,
        status: receipt.status,
        blockNumber: receipt.blockNumber.toString(),
      });
    } else {
      this.logger.warn('Transaction not confirmed within timeout', { chainId, hash });
    }

    return receipt;
  }

  async getChainId(chainId: ChainId): Promise<ChainId> {
    const client = this.getClient(chainId);
    return client.getChainId();
  }

  async batchGetBalances(
    chainId: ChainId,
    addresses: Address[],
    blockTag?: 'latest' | 'pending' | bigint
  ): Promise<Map<Address, WeiAmount>> {
    const results = new Map<Address, WeiAmount>();
    const client = this.getClient(chainId);

    for (const address of addresses) {
      const balance = await client.getBalance(address, blockTag);
      results.set(address, balance);
    }

    return results;
  }

  async getLatestBlocks(chainId: ChainId, count = 10): Promise<Block[]> {
    const client = this.getClient(chainId);
    const latestBlock = await client.getBlockNumber();
    const blocks: Block[] = [];

    for (let i = 0; i < count; i++) {
      const blockNum = latestBlock - BigInt(i);
      if (blockNum < BigInt(0)) break;

      const block = await client.getBlock(blockNum);
      if (block) blocks.push(block);
    }

    return blocks;
  }

  isChainSupported(chainId: ChainId): boolean {
    return this.clients.has(chainId);
  }
}
