"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.chainAdapter = exports.ChainAdapter = void 0;
const ethers_1 = require("ethers");
const config_1 = require("../config");
const utils_1 = require("../common/utils");
const logger_1 = require("../common/logger");
class ChainAdapter {
    providers;
    submissions;
    logger;
    constructor() {
        this.providers = new Map();
        this.submissions = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'ChainAdapter' });
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
    getChainConfig(chainId) {
        const config = config_1.CHAIN_CONFIGS[chainId];
        if (!config) {
            throw new Error(`Unsupported chain: ${chainId}`);
        }
        return config;
    }
    listSupportedChains() {
        return Object.values(config_1.CHAIN_CONFIGS);
    }
    async getBlockNumber(chainId) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(() => provider.getBlockNumber(), {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getBlockNumber', { chainId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async getBlock(chainId, blockNumber, includeTransactions = false) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(async () => {
            const block = await provider.getBlock(blockNumber, includeTransactions);
            if (!block)
                return null;
            return {
                number: blockNumber,
                hash: block.hash || '',
                parentHash: block.parentHash,
                timestamp: block.timestamp,
                miner: block.miner,
                difficulty: block.difficulty.toString(),
                gasLimit: block.gasLimit.toString(),
                gasUsed: block.gasUsed.toString(),
                transactionCount: block.transactions.length,
                transactions: block.transactions.map((tx) => (typeof tx === 'string' ? tx : tx.hash)),
                baseFee: block.baseFeePerGas?.toString(),
            };
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getBlock', { chainId, blockNumber, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async getLatestBlock(chainId, includeTransactions = false) {
        return this.getBlock(chainId, 'latest', includeTransactions);
    }
    async getTransaction(chainId, hash) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(async () => {
            const tx = await provider.getTransaction(hash);
            if (!tx)
                return null;
            const receipt = await provider.getTransactionReceipt(hash);
            return {
                hash: tx.hash,
                from: tx.from || '',
                to: tx.to,
                value: tx.value.toString(),
                data: tx.data,
                nonce: tx.nonce,
                gasLimit: tx.gasLimit.toString(),
                gasPrice: tx.gasPrice?.toString(),
                maxFeePerGas: tx.maxFeePerGas?.toString(),
                maxPriorityFeePerGas: tx.maxPriorityFeePerGas?.toString(),
                chainId: Number(tx.chainId),
                status: receipt?.status ?? undefined,
                blockNumber: receipt?.blockNumber,
                blockHash: receipt?.blockHash,
                timestamp: (await provider.getBlock(receipt?.blockNumber || 'latest'))?.timestamp,
            };
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getTransaction', { chainId, hash, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async getTransactionReceipt(chainId, hash) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(() => provider.getTransactionReceipt(hash), {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getTransactionReceipt', { chainId, hash, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async getBalance(chainId, address, blockTag) {
        const provider = this.getProvider(chainId);
        const normalizedAddress = (0, utils_1.normalizeAddress)(address);
        return (0, utils_1.withRetry)(async () => {
            const balance = await provider.getBalance(normalizedAddress, blockTag);
            return balance.toString();
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getBalance', { chainId, address, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async getNonce(chainId, address, blockTag) {
        const provider = this.getProvider(chainId);
        const normalizedAddress = (0, utils_1.normalizeAddress)(address);
        return (0, utils_1.withRetry)(async () => {
            return provider.getTransactionCount(normalizedAddress, blockTag);
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getNonce', { chainId, address, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async getGasPrice(chainId) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(async () => {
            const feeData = await provider.getFeeData();
            return (feeData.gasPrice ?? feeData.maxFeePerGas ?? 0n).toString();
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getGasPrice', { chainId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async getFeeData(chainId) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(() => provider.getFeeData(), {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getFeeData', { chainId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async estimateGas(chainId, params) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(async () => {
            const gas = await provider.estimateGas({
                to: params.to,
                from: params.from,
                value: params.value,
                data: params.data,
                gasPrice: params.gasPrice,
            });
            return gas.toString();
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying estimateGas', { chainId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async getLogs(chainId, params) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(async () => {
            return provider.getLogs({
                fromBlock: params.fromBlock,
                toBlock: params.toBlock,
                address: params.address ? (0, utils_1.normalizeAddress)(params.address) : undefined,
                topics: params.topics,
            });
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getLogs', { chainId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async getCode(chainId, address, blockTag) {
        const provider = this.getProvider(chainId);
        const normalizedAddress = (0, utils_1.normalizeAddress)(address);
        return (0, utils_1.withRetry)(() => provider.getCode(normalizedAddress, blockTag), {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying getCode', { chainId, address, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async call(chainId, params) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(async () => {
            return provider.call({
                to: (0, utils_1.normalizeAddress)(params.to),
                from: params.from ? (0, utils_1.normalizeAddress)(params.from) : undefined,
                data: params.data,
                blockTag: params.blockTag,
            });
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying call', { chainId, to: params.to, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async sendTransaction(chainId, rawTransaction) {
        this.logger.info('Sending transaction', { chainId });
        const provider = this.getProvider(chainId);
        const submissionId = (0, utils_1.generateId)('submit');
        return (0, utils_1.withRetry)(async () => {
            const txResponse = await provider.broadcastTransaction(rawTransaction);
            const submission = {
                id: submissionId,
                chainId,
                rawTransaction,
                hash: txResponse.hash,
                status: 'pending',
                submittedAt: (0, utils_1.now)(),
                confirmations: 0,
            };
            this.submissions.set(submissionId, submission);
            this.monitorTransaction(submissionId).catch((error) => {
                this.logger.error('Transaction monitoring failed', error, { submissionId });
            });
            this.logger.info('Transaction submitted', { submissionId, hash: txResponse.hash });
            return submission;
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying sendTransaction', { chainId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    async monitorTransaction(submissionId) {
        const submission = this.submissions.get(submissionId);
        if (!submission)
            return;
        const provider = this.getProvider(submission.chainId);
        const maxAttempts = 50;
        let attempts = 0;
        while (attempts < maxAttempts && submission.status === 'pending') {
            attempts++;
            try {
                const receipt = await provider.getTransactionReceipt(submission.hash);
                if (receipt) {
                    submission.status = receipt.status === 1 ? 'confirmed' : 'failed';
                    submission.confirmedAt = (0, utils_1.now)();
                    submission.blockNumber = receipt.blockNumber;
                    submission.confirmations = 1;
                    this.logger.info('Transaction confirmed', {
                        submissionId,
                        hash: submission.hash,
                        status: submission.status,
                        blockNumber: receipt.blockNumber,
                    });
                    break;
                }
                await new Promise((resolve) => setTimeout(resolve, 3000));
            }
            catch (error) {
                this.logger.warn('Error checking transaction status', error, { submissionId });
                await new Promise((resolve) => setTimeout(resolve, 5000));
            }
        }
        if (submission.status === 'pending' && attempts >= maxAttempts) {
            this.logger.warn('Transaction monitoring timed out', { submissionId, hash: submission.hash });
        }
    }
    async waitForTransaction(chainId, hash, confirmations = 1, timeout = 60000) {
        const provider = this.getProvider(chainId);
        return (0, utils_1.withRetry)(async () => {
            const startTime = Date.now();
            let receipt = await provider.getTransactionReceipt(hash);
            while (!receipt && Date.now() - startTime < timeout) {
                await new Promise((resolve) => setTimeout(resolve, 2000));
                receipt = await provider.getTransactionReceipt(hash);
            }
            if (receipt && confirmations > 1) {
                let currentConfirmations = await receipt.confirmations();
                while (currentConfirmations < confirmations && Date.now() - startTime < timeout) {
                    await new Promise((resolve) => setTimeout(resolve, 5000));
                    receipt = await provider.getTransactionReceipt(hash);
                    if (receipt) {
                        currentConfirmations = await receipt.confirmations();
                    }
                }
            }
            return receipt;
        }, {
            retries: 2,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying waitForTransaction', { chainId, hash, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
    }
    getSubmission(submissionId) {
        return this.submissions.get(submissionId);
    }
    listSubmissions(chainId, status) {
        let submissions = Array.from(this.submissions.values());
        if (chainId !== undefined) {
            submissions = submissions.filter((s) => s.chainId === chainId);
        }
        if (status) {
            submissions = submissions.filter((s) => s.status === status);
        }
        return submissions.sort((a, b) => new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime());
    }
    async batchGetBalances(chainId, addresses) {
        this.logger.info('Batch getting balances', { chainId, count: addresses.length });
        const results = await Promise.all(addresses.map(async (address) => ({
            address,
            balance: await this.getBalance(chainId, address),
        })));
        return results;
    }
    async batchSendTransactions(chainId, rawTransactions) {
        this.logger.info('Batch sending transactions', { chainId, count: rawTransactions.length });
        const results = await Promise.all(rawTransactions.map((rawTx) => this.sendTransaction(chainId, rawTx)));
        return results;
    }
    async getChainStats(chainId) {
        const [blockNumber, gasPrice, feeData] = await Promise.all([
            this.getBlockNumber(chainId),
            this.getGasPrice(chainId),
            this.getFeeData(chainId),
        ]);
        const provider = this.getProvider(chainId);
        const latestBlock = await provider.getBlock('latest');
        return {
            blockNumber,
            gasPrice,
            baseFee: latestBlock?.baseFeePerGas?.toString(),
            maxPriorityFee: feeData.maxPriorityFeePerGas?.toString(),
        };
    }
    disconnect() {
        this.providers.forEach((provider) => {
            provider.destroy();
        });
        this.providers.clear();
        this.logger.info('All chain connections disconnected');
    }
}
exports.ChainAdapter = ChainAdapter;
exports.chainAdapter = new ChainAdapter();
//# sourceMappingURL=chainadapter.js.map