import { JsonRpcProvider, TransactionReceipt, Log, FeeData } from 'ethers';
import { ChainId, ChainConfig } from '../types';
export interface ChainTransaction {
    hash: string;
    from: string;
    to: string | null;
    value: string;
    data: string;
    nonce: number;
    gasLimit: string;
    gasPrice?: string;
    maxFeePerGas?: string;
    maxPriorityFeePerGas?: string;
    chainId: number;
    status?: number;
    blockNumber?: number;
    blockHash?: string;
    timestamp?: number;
}
export interface ChainBlock {
    number: number;
    hash: string;
    parentHash: string;
    timestamp: number;
    miner: string;
    difficulty: string;
    gasLimit: string;
    gasUsed: string;
    transactionCount: number;
    transactions: string[];
    baseFee?: string;
}
export interface TransactionSubmission {
    id: string;
    chainId: ChainId;
    rawTransaction: string;
    hash: string;
    status: 'pending' | 'confirmed' | 'failed' | 'replaced';
    submittedAt: string;
    confirmedAt?: string;
    error?: string;
    blockNumber?: number;
    confirmations: number;
}
export declare class ChainAdapter {
    private providers;
    private submissions;
    private logger;
    constructor();
    getProvider(chainId: ChainId): JsonRpcProvider;
    getChainConfig(chainId: ChainId): ChainConfig;
    listSupportedChains(): ChainConfig[];
    getBlockNumber(chainId: ChainId): Promise<number>;
    getBlock(chainId: ChainId, blockNumber: number | string, includeTransactions?: boolean): Promise<ChainBlock | null>;
    getLatestBlock(chainId: ChainId, includeTransactions?: boolean): Promise<ChainBlock | null>;
    getTransaction(chainId: ChainId, hash: string): Promise<ChainTransaction | null>;
    getTransactionReceipt(chainId: ChainId, hash: string): Promise<TransactionReceipt | null>;
    getBalance(chainId: ChainId, address: string, blockTag?: number | string): Promise<string>;
    getNonce(chainId: ChainId, address: string, blockTag?: number | string): Promise<number>;
    getGasPrice(chainId: ChainId): Promise<string>;
    getFeeData(chainId: ChainId): Promise<FeeData>;
    estimateGas(chainId: ChainId, params: {
        to?: string;
        from?: string;
        value?: string;
        data?: string;
        gasPrice?: string;
    }): Promise<string>;
    getLogs(chainId: ChainId, params: {
        fromBlock?: number | string;
        toBlock?: number | string;
        address?: string;
        topics?: string[];
    }): Promise<Log[]>;
    getCode(chainId: ChainId, address: string, blockTag?: number | string): Promise<string>;
    call(chainId: ChainId, params: {
        to: string;
        from?: string;
        data: string;
        blockTag?: number | string;
    }): Promise<string>;
    sendTransaction(chainId: ChainId, rawTransaction: string): Promise<TransactionSubmission>;
    private monitorTransaction;
    waitForTransaction(chainId: ChainId, hash: string, confirmations?: number, timeout?: number): Promise<TransactionReceipt | null>;
    getSubmission(submissionId: string): TransactionSubmission | undefined;
    listSubmissions(chainId?: ChainId, status?: TransactionSubmission['status']): TransactionSubmission[];
    batchGetBalances(chainId: ChainId, addresses: string[]): Promise<Array<{
        address: string;
        balance: string;
    }>>;
    batchSendTransactions(chainId: ChainId, rawTransactions: string[]): Promise<TransactionSubmission[]>;
    getChainStats(chainId: ChainId): Promise<{
        blockNumber: number;
        gasPrice: string;
        baseFee?: string;
        maxPriorityFee?: string;
    }>;
    disconnect(): void;
}
export declare const chainAdapter: ChainAdapter;
//# sourceMappingURL=chainadapter.d.ts.map