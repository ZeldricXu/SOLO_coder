import { IndexedBlock, IndexedTransaction, ContractEvent, ChainId } from '../types';
export interface IndexingConfig {
    chainId: ChainId;
    fromBlock: number;
    toBlock: number | 'latest';
    includeTransactions: boolean;
    includeLogs: boolean;
    logFilter?: {
        address?: string;
        topics?: string[];
    };
    abi?: Array<Record<string, unknown>>;
}
export interface IndexingProgress {
    id: string;
    chainId: ChainId;
    startBlock: number;
    currentBlock: number;
    endBlock: number;
    totalBlocks: number;
    processedBlocks: number;
    indexedTransactions: number;
    indexedLogs: number;
    status: 'running' | 'paused' | 'completed' | 'failed';
    startTime: string;
    lastUpdateTime: string;
    error?: string;
}
export declare class ChainDataIndexer {
    private providers;
    private indexedBlocks;
    private indexedTransactions;
    private indexedLogs;
    private indexingTasks;
    private logger;
    constructor();
    private getProvider;
    startIndexing(config: IndexingConfig): Promise<IndexingProgress>;
    private runIndexingTask;
    private fetchAndIndexBlock;
    private parseLog;
    pauseIndexing(taskId: string): boolean;
    resumeIndexing(taskId: string): boolean;
    cancelIndexing(taskId: string): boolean;
    getIndexingProgress(taskId: string): IndexingProgress | undefined;
    listIndexingTasks(chainId?: ChainId, status?: IndexingProgress['status']): IndexingProgress[];
    getBlock(chainId: ChainId, blockNumber: number): IndexedBlock | undefined;
    getBlockByHash(chainId: ChainId, blockHash: string): IndexedBlock | undefined;
    listBlocks(chainId: ChainId, fromBlock?: number, toBlock?: number): IndexedBlock[];
    getTransaction(hash: string): IndexedTransaction | undefined;
    listTransactions(chainId?: ChainId, from?: string, to?: string, startBlock?: number, endBlock?: number): IndexedTransaction[];
    searchTransactions(query: string, chainId?: ChainId): IndexedTransaction[];
    getLogs(chainId: ChainId, fromBlock?: number, toBlock?: number, address?: string, eventName?: string): ContractEvent[];
    indexSingleBlock(chainId: ChainId, blockNumber: number): Promise<IndexedBlock>;
    getStats(chainId?: ChainId): {
        totalBlocks: number;
        totalTransactions: number;
        totalLogs: number;
        activeTasks: number;
        completedTasks: number;
        failedTasks: number;
    };
}
export declare const chainDataIndexer: ChainDataIndexer;
//# sourceMappingURL=indexer.d.ts.map