import {
  createPublicClient,
  createWalletClient,
  http,
  parseEther,
  type PublicClient,
  type WalletClient,
  type Chain,
  type Transport,
} from 'viem';
import { privateKeyToAccount } from 'viem/accounts';
import type { ChainInteractionPort } from '@core/ports/chainInteraction.port';
import type { Block, Transaction, TransactionReceipt, LogEntry } from '@core/domain/blockchain';
import type {
  ChainId,
  Address,
  Hash,
  HexString,
  GasAmount,
  WeiAmount,
} from '@shared/types';

const defaultMainnet: Chain = {
  id: 1,
  name: 'Ethereum Mainnet',
  nativeCurrency: { name: 'Ether', symbol: 'ETH', decimals: 18 },
  rpcUrls: {
    default: { http: ['https://cloudflare-eth.com'] },
  },
  blockExplorers: {
    default: { name: 'Etherscan', url: 'https://etherscan.io' },
  },
};

const CHAIN_MAP: Record<number, Chain> = {
  1: defaultMainnet,
};

export class ViemChainAdapter implements ChainInteractionPort {
  private publicClient: PublicClient<Transport, Chain>;
  private walletClient?: WalletClient<Transport, Chain>;
  private chainId: ChainId;

  constructor(
    private readonly config: {
      chainId: ChainId;
      rpcUrl: string;
      privateKey?: HexString;
    }
  ) {
    this.chainId = config.chainId;
    const chain = CHAIN_MAP[config.chainId] || {
      id: config.chainId,
      name: `Chain ${config.chainId}`,
      nativeCurrency: { name: 'ETH', symbol: 'ETH', decimals: 18 },
      rpcUrls: { default: { http: [config.rpcUrl] } },
    };

    this.publicClient = createPublicClient({
      chain,
      transport: http(config.rpcUrl),
    });

    if (config.privateKey) {
      const account = privateKeyToAccount(config.privateKey as `0x${string}`);
      this.walletClient = createWalletClient({
        account,
        chain,
        transport: http(config.rpcUrl),
      });
    }
  }

  async getChainId(): Promise<ChainId> {
    return this.chainId;
  }

  async getBlockNumber(): Promise<bigint> {
    return this.publicClient.getBlockNumber();
  }

  async getBlock(blockHashOrNumber: Hash | bigint): Promise<Block | null> {
    try {
      const isHash = typeof blockHashOrNumber === 'string' && blockHashOrNumber.startsWith('0x');
      const block = isHash
        ? await this.publicClient.getBlock({ blockHash: blockHashOrNumber as `0x${string}` })
        : await this.publicClient.getBlock({ blockNumber: blockHashOrNumber as bigint });

      return {
        hash: (block.hash as Hash) || ('0x' + '0'.repeat(64) as Hash),
        number: block.number || BigInt(0),
        timestamp: block.timestamp,
        chainId: this.chainId,
        parentHash: block.parentHash as Hash,
        gasLimit: block.gasLimit,
        gasUsed: block.gasUsed,
        baseFeePerGas: block.baseFeePerGas || undefined,
        transactions: block.transactions as Hash[],
      };
    } catch {
      return null;
    }
  }

  async getBalance(
    address: Address,
    blockTag: 'latest' | 'pending' | bigint = 'latest'
  ): Promise<WeiAmount> {
    const options = typeof blockTag === 'bigint'
      ? { blockNumber: blockTag }
      : { blockTag };

    return this.publicClient.getBalance({
      address: address as `0x${string}`,
      ...options,
    });
  }

  async getNonce(
    address: Address,
    blockTag: 'latest' | 'pending' = 'pending'
  ): Promise<number> {
    return this.publicClient.getTransactionCount({
      address: address as `0x${string}`,
      blockTag,
    });
  }

  async getTransaction(hash: Hash): Promise<Transaction | null> {
    try {
      const tx = await this.publicClient.getTransaction({ hash: hash as `0x${string}` });

      const txType = (tx.type === 'legacy' ? 0 : tx.type === 'eip2930' ? 1 : tx.type === 'eip1559' ? 2 : 0) as 0 | 1 | 2;

      return {
        hash: tx.hash as Hash,
        from: tx.from as Address,
        to: tx.to as Address | null,
        value: tx.value,
        data: tx.input as HexString,
        nonce: tx.nonce,
        gasLimit: tx.gas,
        gasPrice: tx.gasPrice || undefined,
        maxFeePerGas: tx.maxFeePerGas || undefined,
        maxPriorityFeePerGas: tx.maxPriorityFeePerGas || undefined,
        chainId: this.chainId,
        type: txType,
      };
    } catch {
      return null;
    }
  }

  async getTransactionReceipt(hash: Hash): Promise<TransactionReceipt | null> {
    try {
      const receipt = await this.publicClient.getTransactionReceipt({ hash: hash as `0x${string}` });

      return {
        transactionHash: receipt.transactionHash as Hash,
        blockHash: receipt.blockHash as Hash,
        blockNumber: receipt.blockNumber,
        status: receipt.status === 'success' ? 'success' : 'reverted',
        gasUsed: receipt.gasUsed,
        effectiveGasPrice: receipt.effectiveGasPrice,
        cumulativeGasUsed: receipt.cumulativeGasUsed,
        logs: receipt.logs.map(log => ({
          address: log.address as Address,
          topics: log.topics as HexString[],
          data: log.data as HexString,
          blockNumber: log.blockNumber || BigInt(0),
          transactionHash: log.transactionHash as Hash,
          logIndex: log.logIndex || 0,
          removed: log.removed || false,
        })),
      };
    } catch {
      return null;
    }
  }

  async getGasPrice(): Promise<WeiAmount> {
    return this.publicClient.getGasPrice();
  }

  async getFeePerGas(): Promise<{
    baseFeePerGas: WeiAmount;
    maxPriorityFeePerGas: WeiAmount;
  }> {
    const [block, fees] = await Promise.all([
      this.publicClient.getBlock(),
      this.publicClient.estimateMaxPriorityFeePerGas(),
    ]);

    return {
      baseFeePerGas: block.baseFeePerGas || parseEther('0.000000001'),
      maxPriorityFeePerGas: fees,
    };
  }

  async estimateGas(transaction: {
    to?: Address;
    from?: Address;
    value?: WeiAmount;
    data?: HexString;
    gasPrice?: WeiAmount;
  }): Promise<GasAmount> {
    return this.publicClient.estimateGas({
      to: transaction.to as `0x${string}` | undefined,
      value: transaction.value,
      data: transaction.data as `0x${string}` | undefined,
      gasPrice: transaction.gasPrice,
    } as Parameters<typeof this.publicClient.estimateGas>[0]);
  }

  async call(
    transaction: {
      to: Address;
      from?: Address;
      value?: WeiAmount;
      data: HexString;
    },
    blockTag: 'latest' | 'pending' | bigint = 'latest'
  ): Promise<HexString> {
    const baseCall = {
      to: transaction.to as `0x${string}`,
      value: transaction.value,
      data: transaction.data as `0x${string}`,
    };

    const callParams = typeof blockTag === 'bigint'
      ? { ...baseCall, blockNumber: blockTag }
      : { ...baseCall, blockTag };

    const result = await this.publicClient.call(callParams as Parameters<typeof this.publicClient.call>[0]);

    return result as unknown as HexString;
  }

  async sendRawTransaction(signedTransaction: HexString): Promise<Hash> {
    return this.publicClient.sendRawTransaction({
      serializedTransaction: signedTransaction as `0x${string}`,
    }) as unknown as Promise<Hash>;
  }

  async getLogs(filter: {
    address?: Address | Address[];
    topics?: (HexString | HexString[] | null)[];
    fromBlock?: bigint | 'latest';
    toBlock?: bigint | 'latest';
  }): Promise<LogEntry[]> {
    const params = {
      address: filter.address as `0x${string}` | `0x${string}`[] | undefined,
      topics: filter.topics,
      fromBlock: filter.fromBlock,
      toBlock: filter.toBlock,
    };

    const logs = await this.publicClient.getLogs(params as unknown as Parameters<typeof this.publicClient.getLogs>[0]);

    return logs.map(log => ({
      address: log.address as Address,
      topics: log.topics as HexString[],
      data: log.data as HexString,
      blockNumber: log.blockNumber || BigInt(0),
      transactionHash: log.transactionHash as Hash,
      logIndex: log.logIndex || 0,
      removed: log.removed || false,
    }));
  }

  async subscribeToLogs(
    filter: {
      address?: Address | Address[];
      topics?: (HexString | HexString[] | null)[];
    },
    onLog: (log: LogEntry) => void
  ): Promise<() => void> {
    const params = {
      address: filter.address as `0x${string}` | `0x${string}`[] | undefined,
      topics: filter.topics,
      onLogs: (logs: unknown[]) => {
        for (const log of logs as Array<{
          address: string;
          topics: string[];
          data: string;
          blockNumber?: bigint;
          transactionHash?: string;
          logIndex?: number;
          removed?: boolean;
        }>) {
          onLog({
            address: log.address as Address,
            topics: log.topics as HexString[],
            data: log.data as HexString,
            blockNumber: log.blockNumber || BigInt(0),
            transactionHash: (log.transactionHash || '0x') as Hash,
            logIndex: log.logIndex || 0,
            removed: log.removed || false,
          });
        }
      },
    };

    const unwatch = this.publicClient.watchEvent(params as unknown as Parameters<typeof this.publicClient.watchEvent>[0]);

    return unwatch;
  }

  async subscribeToNewBlocks(onBlock: (block: Block) => void): Promise<() => void> {
    const unwatch = this.publicClient.watchBlocks({
      onBlock: (block) => {
        onBlock({
          hash: (block.hash as Hash) || ('0x' + '0'.repeat(64) as Hash),
          number: block.number || BigInt(0),
          timestamp: block.timestamp,
          chainId: this.chainId,
          parentHash: block.parentHash as Hash,
          gasLimit: block.gasLimit,
          gasUsed: block.gasUsed,
          baseFeePerGas: block.baseFeePerGas || undefined,
          transactions: block.transactions as Hash[],
        });
      },
    });

    return unwatch;
  }

  async waitForTransaction(
    hash: Hash,
    confirmations = 1,
    timeout = 120000
  ): Promise<TransactionReceipt | null> {
    try {
      const receipt = await this.publicClient.waitForTransactionReceipt({
        hash: hash as `0x${string}`,
        confirmations,
        timeout,
      });

      return {
        transactionHash: receipt.transactionHash as Hash,
        blockHash: receipt.blockHash as Hash,
        blockNumber: receipt.blockNumber,
        status: receipt.status === 'success' ? 'success' : 'reverted',
        gasUsed: receipt.gasUsed,
        effectiveGasPrice: receipt.effectiveGasPrice,
        cumulativeGasUsed: receipt.cumulativeGasUsed,
        logs: receipt.logs.map(log => ({
          address: log.address as Address,
          topics: log.topics as HexString[],
          data: log.data as HexString,
          blockNumber: log.blockNumber || BigInt(0),
          transactionHash: log.transactionHash as Hash,
          logIndex: log.logIndex || 0,
          removed: log.removed || false,
        })),
      };
    } catch {
      return null;
    }
  }

  static createFactory(): (config: { chainId: ChainId; rpcUrl: string }) => ChainInteractionPort {
    return (config) => new ViemChainAdapter(config);
  }
}
