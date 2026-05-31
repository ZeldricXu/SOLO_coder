"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.chainDataIndexer = exports.ChainDataIndexer = void 0;
const ethers_1 = require("ethers");
const config_1 = require("../config");
const utils_1 = require("../common/utils");
const events_1 = require("../common/events");
const logger_1 = require("../common/logger");
class ChainDataIndexer {
    providers;
    indexedBlocks;
    indexedTransactions;
    indexedLogs;
    indexingTasks;
    logger;
    constructor() {
        this.providers = new Map();
        this.indexedBlocks = new Map();
        this.indexedTransactions = new Map();
        this.indexedLogs = new Map();
        this.indexingTasks = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'ChainDataIndexer' });
    }
    getProvider(chainId) {
        if (!this.providers.has(chainId)) {
            const config = config_1.CHAIN_CONFIGS[chainId];
            if (!config) {
                throw new Error(`Unsupported chain: ${chainId}`);
            }
            this.providers.set(chainId, new ethers_1.JsonRpcProvider(config.rpcUrl, chainId));
        }
        return this.providers.get(chainId);
    }
    async startIndexing(config) {
        const { chainId, fromBlock, toBlock = 'latest', includeTransactions = true, includeLogs = true, logFilter, abi, } = config;
        this.logger.info('Starting indexing task', { chainId, fromBlock, toBlock });
        const provider = this.getProvider(chainId);
        const latestBlock = await provider.getBlockNumber();
        const actualToBlock = toBlock === 'latest' ? latestBlock : Math.min(toBlock, latestBlock);
        if (fromBlock > actualToBlock) {
            throw new Error(`fromBlock (${fromBlock}) is greater than toBlock (${actualToBlock})`);
        }
        const taskId = (0, utils_1.generateId)('index');
        const progress = {
            id: taskId,
            chainId,
            startBlock: fromBlock,
            currentBlock: fromBlock - 1,
            endBlock: actualToBlock,
            totalBlocks: actualToBlock - fromBlock + 1,
            processedBlocks: 0,
            indexedTransactions: 0,
            indexedLogs: 0,
            status: 'running',
            startTime: (0, utils_1.now)(),
            lastUpdateTime: (0, utils_1.now)(),
        };
        this.indexingTasks.set(taskId, progress);
        this.runIndexingTask(taskId, config, actualToBlock).catch((error) => {
            this.logger.error('Indexing task failed', error, { taskId });
            progress.status = 'failed';
            progress.error = error.message;
            progress.lastUpdateTime = (0, utils_1.now)();
        });
        this.logger.info('Indexing task started', { taskId, totalBlocks: progress.totalBlocks });
        return progress;
    }
    async runIndexingTask(taskId, config, toBlock) {
        const progress = this.indexingTasks.get(taskId);
        const { chainId, includeTransactions, includeLogs, logFilter, abi } = config;
        const provider = this.getProvider(chainId);
        const batchSize = 10;
        const iface = abi ? new ethers_1.Interface(abi) : null;
        for (let blockNumber = config.fromBlock; blockNumber <= toBlock; blockNumber += batchSize) {
            if (progress.status !== 'running') {
                this.logger.info('Indexing task paused or stopped', { taskId, blockNumber });
                break;
            }
            const endBatch = Math.min(blockNumber + batchSize - 1, toBlock);
            try {
                await (0, utils_1.withRetry)(async () => {
                    for (let bn = blockNumber; bn <= endBatch; bn++) {
                        const block = await this.fetchAndIndexBlock(chainId, bn, includeTransactions, includeLogs, logFilter, iface);
                        progress.currentBlock = bn;
                        progress.processedBlocks++;
                        progress.indexedTransactions += block.transactionCount;
                        progress.indexedLogs += block.logs.length;
                        progress.lastUpdateTime = (0, utils_1.now)();
                        events_1.eventBus.emit(events_1.EVENTS.BLOCK_INDEXED, { taskId, blockNumber: bn, chainId });
                    }
                }, {
                    retries: 3,
                    onRetry: (error, attempt) => {
                        this.logger.warn('Retrying block indexing', {
                            taskId,
                            fromBlock: blockNumber,
                            toBlock: endBatch,
                            attempt,
                            error: (0, utils_1.getErrorMessage)(error),
                        });
                    },
                });
                const percentComplete = Math.round((progress.processedBlocks / progress.totalBlocks) * 100);
                this.logger.debug('Indexing progress', {
                    taskId,
                    percentComplete,
                    processed: progress.processedBlocks,
                    total: progress.totalBlocks,
                });
            }
            catch (error) {
                this.logger.error('Batch indexing failed', error, {
                    taskId,
                    fromBlock: blockNumber,
                    toBlock: endBatch,
                });
                throw error;
            }
        }
        if (progress.status === 'running') {
            progress.status = 'completed';
            progress.lastUpdateTime = (0, utils_1.now)();
            this.logger.info('Indexing task completed', {
                taskId,
                totalBlocks: progress.processedBlocks,
                totalTransactions: progress.indexedTransactions,
                totalLogs: progress.indexedLogs,
            });
        }
    }
    async fetchAndIndexBlock(chainId, blockNumber, includeTransactions, includeLogs, logFilter, iface) {
        const provider = this.getProvider(chainId);
        const block = await provider.getBlock(blockNumber, includeTransactions);
        if (!block) {
            throw new Error(`Block not found: ${blockNumber}`);
        }
        let transactions = [];
        if (includeTransactions && block.transactions.length > 0) {
            transactions = await Promise.all(block.transactions.map(async (tx) => {
                let fullTx;
                if (typeof tx === 'string') {
                    fullTx = await provider.getTransactionReceipt(tx);
                    return {
                        hash: fullTx?.hash || tx,
                        blockNumber,
                        from: fullTx?.from || '',
                        to: fullTx?.to || null,
                        value: '0',
                        gas: fullTx?.gasUsed.toString() || '0',
                        gasPrice: '0',
                        input: '',
                        nonce: 0,
                        status: fullTx?.status ?? 0,
                    };
                }
                else {
                    const txObj = tx;
                    return {
                        hash: txObj.hash,
                        blockNumber,
                        from: txObj.from || '',
                        to: txObj.to || null,
                        value: txObj.value.toString(),
                        gas: txObj.gasLimit.toString(),
                        gasPrice: txObj.gasPrice?.toString() || '0',
                        input: txObj.data,
                        nonce: txObj.nonce,
                        status: 1,
                    };
                }
            }));
            transactions.forEach((tx) => {
                this.indexedTransactions.set(tx.hash, tx);
            });
        }
        let logs = [];
        if (includeLogs) {
            try {
                const rawLogs = await provider.getLogs({
                    fromBlock: blockNumber,
                    toBlock: blockNumber,
                    address: logFilter?.address,
                    topics: logFilter?.topics,
                });
                logs = rawLogs.map((log) => this.parseLog(log, block.timestamp, iface));
                logs.forEach((log) => {
                    const logKey = `${log.blockHash}_${log.logIndex}`;
                    this.indexedLogs.set(logKey, log);
                });
            }
            catch (error) {
                this.logger.warn('Failed to fetch logs', error, { blockNumber });
            }
        }
        const indexedBlock = {
            number: blockNumber,
            hash: block.hash || '',
            parentHash: block.parentHash,
            timestamp: block.timestamp,
            miner: block.miner,
            difficulty: block.difficulty.toString(),
            totalDifficulty: block.difficulty.toString(),
            gasUsed: block.gasUsed.toString(),
            gasLimit: block.gasLimit.toString(),
            transactionCount: transactions.length,
            transactions,
            logs,
        };
        this.indexedBlocks.set(blockNumber, indexedBlock);
        return indexedBlock;
    }
    parseLog(log, blockTimestamp, iface) {
        let eventName = 'Unknown';
        let args = {};
        if (iface) {
            try {
                const parsed = iface.parseLog(log);
                if (parsed) {
                    eventName = parsed.name;
                    args = parsed.args.toObject();
                }
            }
            catch {
                // Fall through to default values
            }
        }
        return {
            blockNumber: log.blockNumber,
            blockHash: log.blockHash,
            transactionHash: log.transactionHash,
            logIndex: log.index,
            address: (0, utils_1.normalizeAddress)(log.address),
            eventName,
            args,
            timestamp: blockTimestamp,
        };
    }
    pauseIndexing(taskId) {
        const progress = this.indexingTasks.get(taskId);
        if (!progress)
            return false;
        if (progress.status === 'running') {
            progress.status = 'paused';
            progress.lastUpdateTime = (0, utils_1.now)();
            this.logger.info('Indexing task paused', { taskId });
            return true;
        }
        return false;
    }
    resumeIndexing(taskId) {
        const progress = this.indexingTasks.get(taskId);
        if (!progress)
            return false;
        if (progress.status === 'paused') {
            progress.status = 'running';
            progress.lastUpdateTime = (0, utils_1.now)();
            this.logger.info('Indexing task resumed', { taskId });
            return true;
        }
        return false;
    }
    cancelIndexing(taskId) {
        const progress = this.indexingTasks.get(taskId);
        if (!progress)
            return false;
        if (progress.status === 'running' || progress.status === 'paused') {
            progress.status = 'failed';
            progress.error = 'Cancelled by user';
            progress.lastUpdateTime = (0, utils_1.now)();
            this.logger.info('Indexing task cancelled', { taskId });
            return true;
        }
        return false;
    }
    getIndexingProgress(taskId) {
        return this.indexingTasks.get(taskId);
    }
    listIndexingTasks(chainId, status) {
        let tasks = Array.from(this.indexingTasks.values());
        if (chainId !== undefined) {
            tasks = tasks.filter((t) => t.chainId === chainId);
        }
        if (status) {
            tasks = tasks.filter((t) => t.status === status);
        }
        return tasks.sort((a, b) => new Date(b.startTime).getTime() - new Date(a.startTime).getTime());
    }
    getBlock(chainId, blockNumber) {
        return this.indexedBlocks.get(blockNumber);
    }
    getBlockByHash(chainId, blockHash) {
        return Array.from(this.indexedBlocks.values()).find((b) => b.hash === blockHash);
    }
    listBlocks(chainId, fromBlock, toBlock) {
        let blocks = Array.from(this.indexedBlocks.values());
        if (fromBlock !== undefined) {
            blocks = blocks.filter((b) => b.number >= fromBlock);
        }
        if (toBlock !== undefined) {
            blocks = blocks.filter((b) => b.number <= toBlock);
        }
        return blocks.sort((a, b) => b.number - a.number);
    }
    getTransaction(hash) {
        return this.indexedTransactions.get(hash);
    }
    listTransactions(chainId, from, to, startBlock, endBlock) {
        let txs = Array.from(this.indexedTransactions.values());
        if (chainId !== undefined) {
            txs = txs.filter((t) => t.blockNumber >= 0);
        }
        if (from) {
            txs = txs.filter((t) => (0, utils_1.normalizeAddress)(t.from) === (0, utils_1.normalizeAddress)(from));
        }
        if (to) {
            txs = txs.filter((t) => t.to && (0, utils_1.normalizeAddress)(t.to) === (0, utils_1.normalizeAddress)(to));
        }
        if (startBlock !== undefined) {
            txs = txs.filter((t) => t.blockNumber >= startBlock);
        }
        if (endBlock !== undefined) {
            txs = txs.filter((t) => t.blockNumber <= endBlock);
        }
        return txs.sort((a, b) => b.blockNumber - a.blockNumber);
    }
    searchTransactions(query, chainId) {
        const queryLower = query.toLowerCase();
        let txs = Array.from(this.indexedTransactions.values());
        if (chainId !== undefined) {
            txs = txs.filter((t) => t.blockNumber >= 0);
        }
        return txs.filter((t) => t.hash.toLowerCase().includes(queryLower) ||
            t.from.toLowerCase().includes(queryLower) ||
            (t.to && t.to.toLowerCase().includes(queryLower)) ||
            t.input.toLowerCase().includes(queryLower));
    }
    getLogs(chainId, fromBlock, toBlock, address, eventName) {
        let logs = Array.from(this.indexedLogs.values());
        if (fromBlock !== undefined) {
            logs = logs.filter((l) => l.blockNumber >= fromBlock);
        }
        if (toBlock !== undefined) {
            logs = logs.filter((l) => l.blockNumber <= toBlock);
        }
        if (address) {
            logs = logs.filter((l) => (0, utils_1.normalizeAddress)(l.address) === (0, utils_1.normalizeAddress)(address));
        }
        if (eventName) {
            logs = logs.filter((l) => l.eventName === eventName);
        }
        return logs.sort((a, b) => b.blockNumber - a.blockNumber || b.logIndex - a.logIndex);
    }
    async indexSingleBlock(chainId, blockNumber) {
        this.logger.info('Indexing single block', { chainId, blockNumber });
        const existing = this.indexedBlocks.get(blockNumber);
        if (existing) {
            return existing;
        }
        const block = await this.fetchAndIndexBlock(chainId, blockNumber, true, true);
        events_1.eventBus.emit(events_1.EVENTS.BLOCK_INDEXED, { blockNumber, chainId });
        return block;
    }
    getStats(chainId) {
        let blocks = Array.from(this.indexedBlocks.values());
        if (chainId !== undefined) {
            blocks = blocks.filter((b) => b.number >= 0);
        }
        const tasks = Array.from(this.indexingTasks.values());
        return {
            totalBlocks: blocks.length,
            totalTransactions: this.indexedTransactions.size,
            totalLogs: this.indexedLogs.size,
            activeTasks: tasks.filter((t) => t.status === 'running').length,
            completedTasks: tasks.filter((t) => t.status === 'completed').length,
            failedTasks: tasks.filter((t) => t.status === 'failed').length,
        };
    }
}
exports.ChainDataIndexer = ChainDataIndexer;
exports.chainDataIndexer = new ChainDataIndexer();
//# sourceMappingURL=indexer.js.map