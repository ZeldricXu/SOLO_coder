"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.transactionBuilder = exports.TransactionBuilder = void 0;
const ethers_1 = require("ethers");
const config_1 = require("../config");
const utils_1 = require("../common/utils");
const events_1 = require("../common/events");
const logger_1 = require("../common/logger");
class TransactionBuilder {
    wallets;
    multiSigStrategies;
    signedTransactions;
    pendingTransactions;
    logger;
    constructor() {
        this.wallets = new Map();
        this.multiSigStrategies = new Map();
        this.signedTransactions = new Map();
        this.pendingTransactions = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'TransactionBuilder' });
    }
    addWallet(privateKey, chainId) {
        this.logger.info('Adding wallet', { chainId });
        try {
            const wallet = new ethers_1.Wallet(privateKey);
            const address = (0, utils_1.normalizeAddress)(wallet.address);
            if (this.wallets.has(address)) {
                throw new Error(`Wallet already added: ${address}`);
            }
            this.wallets.set(address, wallet);
            this.logger.info('Wallet added', { address, chainId });
            return address;
        }
        catch (error) {
            this.logger.error('Failed to add wallet', error);
            throw new Error('Invalid private key');
        }
    }
    removeWallet(address) {
        const normalizedAddress = (0, utils_1.normalizeAddress)(address);
        return this.wallets.delete(normalizedAddress);
    }
    getWalletAddress(address) {
        const normalizedAddress = (0, utils_1.normalizeAddress)(address);
        return this.wallets.has(normalizedAddress) ? normalizedAddress : undefined;
    }
    listWallets() {
        return Array.from(this.wallets.keys());
    }
    createMultiSigStrategy(params) {
        const { name, signers, requiredSignatures } = params;
        this.logger.info('Creating multi-sig strategy', { name, signerCount: signers.length, requiredSignatures });
        if (requiredSignatures > signers.length) {
            throw new Error('Required signatures cannot exceed number of signers');
        }
        if (requiredSignatures <= 0) {
            throw new Error('Required signatures must be greater than 0');
        }
        const normalizedSigners = signers.map(utils_1.normalizeAddress);
        const uniqueSigners = [...new Set(normalizedSigners)];
        if (uniqueSigners.length !== normalizedSigners.length) {
            throw new Error('Duplicate signers are not allowed');
        }
        uniqueSigners.forEach((signer) => {
            if (!(0, ethers_1.isAddress)(signer)) {
                throw new Error(`Invalid signer address: ${signer}`);
            }
        });
        const strategy = {
            id: (0, utils_1.generateId)('strategy'),
            name,
            signers: uniqueSigners,
            requiredSignatures,
            threshold: requiredSignatures,
        };
        this.multiSigStrategies.set(strategy.id, strategy);
        this.logger.info('Multi-sig strategy created', { strategyId: strategy.id });
        return strategy;
    }
    getMultiSigStrategy(strategyId) {
        return this.multiSigStrategies.get(strategyId);
    }
    listMultiSigStrategies() {
        return Array.from(this.multiSigStrategies.values()).sort((a, b) => a.name.localeCompare(b.name));
    }
    async buildTransaction(request, options) {
        this.logger.info('Building transaction', { chainId: request.chainId, to: request.to });
        const { chainId, from, to, value = '0', data = '0x' } = request;
        const normalizedFrom = (0, utils_1.normalizeAddress)(from);
        const normalizedTo = (0, utils_1.normalizeAddress)(to);
        if (!(0, ethers_1.isAddress)(normalizedFrom)) {
            throw new Error(`Invalid from address: ${from}`);
        }
        if (!(0, ethers_1.isAddress)(normalizedTo)) {
            throw new Error(`Invalid to address: ${to}`);
        }
        const config = config_1.CHAIN_CONFIGS[chainId];
        if (!config) {
            throw new Error(`Unsupported chain: ${chainId}`);
        }
        const optimizationConfig = {
            maxGasPrice: '100000000000',
            priorityFee: '2000000000',
            gasLimitMultiplier: 1.2,
            useEIP1559: true,
            ...options?.gasOptimization,
        };
        const estimatedGas = await this.estimateGas(request);
        const adjustedGasLimit = Math.floor(parseInt(estimatedGas) * optimizationConfig.gasLimitMultiplier).toString();
        const { gasPrice, maxFeePerGas, maxPriorityFeePerGas } = await this.getOptimalGasPrice(chainId, optimizationConfig);
        const transaction = {
            chainId,
            from: normalizedFrom,
            to: normalizedTo,
            value,
            data,
            gasLimit: adjustedGasLimit,
            nonce: request.nonce,
        };
        if (optimizationConfig.useEIP1559) {
            transaction.maxFeePerGas = maxFeePerGas;
            transaction.maxPriorityFeePerGas = maxPriorityFeePerGas;
        }
        else {
            transaction.gasPrice = gasPrice;
        }
        const transactionId = (0, utils_1.generateId)('tx');
        this.pendingTransactions.set(transactionId, transaction);
        const estimatedCost = (BigInt(adjustedGasLimit) * BigInt(maxFeePerGas || gasPrice)).toString();
        this.logger.info('Transaction built', {
            transactionId,
            chainId,
            gasLimit: adjustedGasLimit,
            estimatedCost,
        });
        return {
            transactionId,
            transaction,
            estimatedGas: adjustedGasLimit,
            estimatedCost,
        };
    }
    async estimateGas(request) {
        if (request.gasLimit) {
            return request.gasLimit;
        }
        const baseGas = 21000;
        const data = request.data || '0x';
        const dataGas = data.slice(2).length / 2 * 16;
        return (baseGas + Math.floor(dataGas)).toString();
    }
    async getOptimalGasPrice(chainId, config) {
        const baseFee = '30000000000';
        const maxPriorityFeePerGas = config.priorityFee;
        const maxFeePerGas = (BigInt(baseFee) * BigInt(2) + BigInt(maxPriorityFeePerGas)).toString();
        const gasPrice = (BigInt(baseFee) + BigInt(maxPriorityFeePerGas)).toString();
        return {
            gasPrice: gasPrice > config.maxGasPrice ? config.maxGasPrice : gasPrice,
            maxFeePerGas: maxFeePerGas > config.maxGasPrice ? config.maxGasPrice : maxFeePerGas,
            maxPriorityFeePerGas,
        };
    }
    async signTransaction(transactionId, from) {
        this.logger.info('Signing transaction', { transactionId, from });
        const transaction = this.pendingTransactions.get(transactionId);
        if (!transaction) {
            throw new Error(`Transaction not found: ${transactionId}`);
        }
        const normalizedFrom = (0, utils_1.normalizeAddress)(from);
        const wallet = this.wallets.get(normalizedFrom);
        if (!wallet) {
            throw new Error(`Wallet not found for address: ${from}`);
        }
        if ((0, utils_1.normalizeAddress)(wallet.address) !== normalizedFrom) {
            throw new Error('Wallet address mismatch');
        }
        const result = await (0, utils_1.withRetry)(async () => {
            const tx = new ethers_1.Transaction();
            tx.chainId = transaction.chainId;
            tx.to = transaction.to;
            tx.value = transaction.value || 0;
            tx.data = transaction.data || '0x';
            tx.gasLimit = transaction.gasLimit || '21000';
            tx.nonce = transaction.nonce || 0;
            if (transaction.maxFeePerGas && transaction.maxPriorityFeePerGas) {
                tx.maxFeePerGas = transaction.maxFeePerGas;
                tx.maxPriorityFeePerGas = transaction.maxPriorityFeePerGas;
                tx.type = 2;
            }
            else {
                tx.gasPrice = transaction.gasPrice || '20000000000';
                tx.type = 0;
            }
            const signedTx = await wallet.signTransaction(tx);
            const parsedTx = ethers_1.Transaction.from(signedTx);
            const signedTransaction = {
                rawTransaction: signedTx,
                hash: parsedTx.hash,
                from: normalizedFrom,
                to: transaction.to,
                value: transaction.value || '0',
                gasLimit: tx.gasLimit.toString(),
                nonce: tx.nonce,
                chainId: transaction.chainId,
            };
            return signedTransaction;
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying transaction signing', { transactionId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
        this.signedTransactions.set(result.hash, result);
        this.pendingTransactions.delete(transactionId);
        events_1.eventBus.emit(events_1.EVENTS.TRANSACTION_SIGNED, result);
        this.logger.info('Transaction signed', { transactionId, hash: result.hash });
        return result;
    }
    async signMessage(message, from) {
        this.logger.info('Signing message', { from });
        const normalizedFrom = (0, utils_1.normalizeAddress)(from);
        const wallet = this.wallets.get(normalizedFrom);
        if (!wallet) {
            throw new Error(`Wallet not found for address: ${from}`);
        }
        const signature = await wallet.signMessage(message);
        return {
            signature,
            address: normalizedFrom,
        };
    }
    verifySignature(message, signature, expectedAddress) {
        try {
            const recoveredAddress = (0, ethers_1.verifyMessage)(message, signature);
            return (0, utils_1.normalizeAddress)(recoveredAddress) === (0, utils_1.normalizeAddress)(expectedAddress);
        }
        catch (error) {
            this.logger.error('Signature verification failed', error);
            return false;
        }
    }
    getSignedTransaction(hash) {
        return this.signedTransactions.get(hash);
    }
    listSignedTransactions(chainId, from) {
        let txs = Array.from(this.signedTransactions.values());
        if (chainId !== undefined) {
            txs = txs.filter((t) => t.chainId === chainId);
        }
        if (from) {
            txs = txs.filter((t) => (0, utils_1.normalizeAddress)(t.from) === (0, utils_1.normalizeAddress)(from));
        }
        return txs.sort((a, b) => b.nonce - a.nonce);
    }
    getPendingTransaction(transactionId) {
        return this.pendingTransactions.get(transactionId);
    }
    listPendingTransactions() {
        return Array.from(this.pendingTransactions.entries()).map(([id, transaction]) => ({
            id,
            transaction,
        }));
    }
    cancelPendingTransaction(transactionId) {
        return this.pendingTransactions.delete(transactionId);
    }
    batchBuildTransactions(requests, options) {
        this.logger.info('Batch building transactions', { count: requests.length });
        return Promise.all(requests.map((req) => this.buildTransaction(req, options)));
    }
    async batchSignTransactions(transactionIds, from) {
        this.logger.info('Batch signing transactions', { count: transactionIds.length, from });
        const results = [];
        for (const id of transactionIds) {
            try {
                const signed = await this.signTransaction(id, from);
                results.push(signed);
            }
            catch (error) {
                this.logger.error('Failed to sign transaction in batch', error, { transactionId: id });
            }
        }
        return results;
    }
    optimizeGasForTransaction(transactionId, optimization) {
        const transaction = this.pendingTransactions.get(transactionId);
        if (!transaction) {
            throw new Error(`Transaction not found: ${transactionId}`);
        }
        const config = {
            maxGasPrice: optimization.maxGasPrice || '100000000000',
            priorityFee: optimization.priorityFee || '2000000000',
            gasLimitMultiplier: optimization.gasLimitMultiplier || 1.2,
            useEIP1559: optimization.useEIP1559 ?? true,
        };
        if (config.useEIP1559) {
            transaction.maxFeePerGas = config.maxGasPrice;
            transaction.maxPriorityFeePerGas = config.priorityFee;
            delete transaction.gasPrice;
        }
        else {
            transaction.gasPrice = config.maxGasPrice;
            delete transaction.maxFeePerGas;
            delete transaction.maxPriorityFeePerGas;
        }
        if (transaction.gasLimit) {
            transaction.gasLimit = Math.floor(parseInt(transaction.gasLimit) * config.gasLimitMultiplier).toString();
        }
        this.logger.info('Gas optimized for transaction', { transactionId, ...config });
        return transaction;
    }
}
exports.TransactionBuilder = TransactionBuilder;
exports.transactionBuilder = new TransactionBuilder();
//# sourceMappingURL=transaction.js.map