import type { ChainId, Address, Hash, HexString, GasAmount, WeiAmount } from '@shared/types';
import type { Block, Transaction, TransactionReceipt, LogEntry } from '@core/domain/blockchain';

export interface ChainInteractionPort {
  getChainId(): Promise<ChainId>;
  getBlockNumber(): Promise<bigint>;
  getBlock(blockHashOrNumber: Hash | bigint): Promise<Block | null>;
  getBalance(address: Address, blockTag?: 'latest' | 'pending' | bigint): Promise<WeiAmount>;
  getNonce(address: Address, blockTag?: 'latest' | 'pending'): Promise<number>;
  getTransaction(hash: Hash): Promise<Transaction | null>;
  getTransactionReceipt(hash: Hash): Promise<TransactionReceipt | null>;
  getGasPrice(): Promise<WeiAmount>;
  getFeePerGas(): Promise<{
    baseFeePerGas: WeiAmount;
    maxPriorityFeePerGas: WeiAmount;
  }>;
  estimateGas(transaction: {
    to?: Address;
    from?: Address;
    value?: WeiAmount;
    data?: HexString;
    gasPrice?: WeiAmount;
  }): Promise<GasAmount>;
  call(transaction: {
    to: Address;
    from?: Address;
    value?: WeiAmount;
    data: HexString;
  }, blockTag?: 'latest' | 'pending' | bigint): Promise<HexString>;
  sendRawTransaction(signedTransaction: HexString): Promise<Hash>;
  getLogs(filter: {
    address?: Address | Address[];
    topics?: (HexString | HexString[] | null)[];
    fromBlock?: bigint | 'latest';
    toBlock?: bigint | 'latest';
  }): Promise<LogEntry[]>;
  subscribeToLogs(
    filter: {
      address?: Address | Address[];
      topics?: (HexString | HexString[] | null)[];
    },
    onLog: (log: LogEntry) => void
  ): Promise<() => void>;
  subscribeToNewBlocks(onBlock: (block: Block) => void): Promise<() => void>;
  waitForTransaction(hash: Hash, confirmations?: number, timeout?: number): Promise<TransactionReceipt | null>;
}

export interface ChainInteractionProvider {
  getClient(chainId: ChainId): ChainInteractionPort;
  addClient(chainId: ChainId, client: ChainInteractionPort): void;
  removeClient(chainId: ChainId): void;
  getSupportedChains(): ChainId[];
}
