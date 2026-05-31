import { Wallet, Transaction, isAddress, verifyMessage } from 'ethers';
import { TransactionRequest, SignedTransaction, ChainId } from '../types';
import { CHAIN_CONFIGS } from '../config';
import { generateId, now, normalizeAddress, withRetry, numberToHex, getErrorMessage } from '../common/utils';
import { eventBus, EVENTS } from '../common/events';
import { LoggerContext } from '../common/logger';

export interface MultiSigStrategy {
  id: string;
  name: string;
  requiredSignatures: number;
  signers: string[];
  threshold: number;
}

export interface TransactionConfig {
  chainId: ChainId;
  from: string;
  to: string;
  value?: string;
  data?: string;
  gasLimit?: string;
  gasPrice?: string;
  maxPriorityFeePerGas?: string;
  maxFeePerGas?: string;
  nonce?: number;
}

export interface GasOptimizationConfig {
  maxGasPrice: string;
  priorityFee: string;
  gasLimitMultiplier: number;
  useEIP1559: boolean;
}

export class TransactionBuilder {
  private wallets: Map<string, Wallet>;
  private multiSigStrategies: Map<string, MultiSigStrategy>;
  private signedTransactions: Map<string, SignedTransaction>;
  private pendingTransactions: Map<string, TransactionRequest>;
  private logger: LoggerContext;

  constructor() {
    this.wallets = new Map();
    this.multiSigStrategies = new Map();
    this.signedTransactions = new Map();
    this.pendingTransactions = new Map();
    this.logger = new LoggerContext({ module: 'TransactionBuilder' });
  }

  addWallet(privateKey: string, chainId: ChainId): string {
    this.logger.info('Adding wallet', { chainId });

    try {
      const wallet = new Wallet(privateKey);
      const address = normalizeAddress(wallet.address);

      if (this.wallets.has(address)) {
        throw new Error(`Wallet already added: ${address}`);
      }

      this.wallets.set(address, wallet);
      this.logger.info('Wallet added', { address, chainId });

      return address;
    } catch (error) {
      this.logger.error('Failed to add wallet', error as Error);
      throw new Error('Invalid private key');
    }
  }

  removeWallet(address: string): boolean {
    const normalizedAddress = normalizeAddress(address);
    return this.wallets.delete(normalizedAddress);
  }

  getWalletAddress(address: string): string | undefined {
    const normalizedAddress = normalizeAddress(address);
    return this.wallets.has(normalizedAddress) ? normalizedAddress : undefined;
  }

  listWallets(): string[] {
    return Array.from(this.wallets.keys());
  }

  createMultiSigStrategy(params: {
    name: string;
    signers: string[];
    requiredSignatures: number;
  }): MultiSigStrategy {
    const { name, signers, requiredSignatures } = params;

    this.logger.info('Creating multi-sig strategy', { name, signerCount: signers.length, requiredSignatures });

    if (requiredSignatures > signers.length) {
      throw new Error('Required signatures cannot exceed number of signers');
    }

    if (requiredSignatures <= 0) {
      throw new Error('Required signatures must be greater than 0');
    }

    const normalizedSigners = signers.map(normalizeAddress);
    const uniqueSigners = [...new Set(normalizedSigners)];

    if (uniqueSigners.length !== normalizedSigners.length) {
      throw new Error('Duplicate signers are not allowed');
    }

    uniqueSigners.forEach((signer) => {
      if (!isAddress(signer)) {
        throw new Error(`Invalid signer address: ${signer}`);
      }
    });

    const strategy: MultiSigStrategy = {
      id: generateId('strategy'),
      name,
      signers: uniqueSigners,
      requiredSignatures,
      threshold: requiredSignatures,
    };

    this.multiSigStrategies.set(strategy.id, strategy);
    this.logger.info('Multi-sig strategy created', { strategyId: strategy.id });

    return strategy;
  }

  getMultiSigStrategy(strategyId: string): MultiSigStrategy | undefined {
    return this.multiSigStrategies.get(strategyId);
  }

  listMultiSigStrategies(): MultiSigStrategy[] {
    return Array.from(this.multiSigStrategies.values()).sort((a, b) => a.name.localeCompare(b.name));
  }

  async buildTransaction(request: TransactionRequest, options?: {
    gasOptimization?: Partial<GasOptimizationConfig>;
  }): Promise<{
    transactionId: string;
    transaction: TransactionRequest;
    estimatedGas: string;
    estimatedCost: string;
  }> {
    this.logger.info('Building transaction', { chainId: request.chainId, to: request.to });

    const { chainId, from, to, value = '0', data = '0x' } = request;

    const normalizedFrom = normalizeAddress(from);
    const normalizedTo = normalizeAddress(to);

    if (!isAddress(normalizedFrom)) {
      throw new Error(`Invalid from address: ${from}`);
    }

    if (!isAddress(normalizedTo)) {
      throw new Error(`Invalid to address: ${to}`);
    }

    const config = CHAIN_CONFIGS[chainId as ChainId];
    if (!config) {
      throw new Error(`Unsupported chain: ${chainId}`);
    }

    const optimizationConfig: GasOptimizationConfig = {
      maxGasPrice: '100000000000',
      priorityFee: '2000000000',
      gasLimitMultiplier: 1.2,
      useEIP1559: true,
      ...options?.gasOptimization,
    };

    const estimatedGas = await this.estimateGas(request);
    const adjustedGasLimit = Math.floor(parseInt(estimatedGas) * optimizationConfig.gasLimitMultiplier).toString();

    const { gasPrice, maxFeePerGas, maxPriorityFeePerGas } = await this.getOptimalGasPrice(
      chainId,
      optimizationConfig
    );

    const transaction: TransactionRequest = {
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
    } else {
      transaction.gasPrice = gasPrice;
    }

    const transactionId = generateId('tx');
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

  private async estimateGas(request: TransactionRequest): Promise<string> {
    if (request.gasLimit) {
      return request.gasLimit;
    }

    const baseGas = 21000;
    const data = request.data || '0x';
    const dataGas = data.slice(2).length / 2 * 16;

    return (baseGas + Math.floor(dataGas)).toString();
  }

  private async getOptimalGasPrice(
    chainId: ChainId,
    config: GasOptimizationConfig
  ): Promise<{
    gasPrice: string;
    maxFeePerGas: string;
    maxPriorityFeePerGas: string;
  }> {
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

  async signTransaction(
    transactionId: string,
    from: string
  ): Promise<SignedTransaction> {
    this.logger.info('Signing transaction', { transactionId, from });

    const transaction = this.pendingTransactions.get(transactionId);
    if (!transaction) {
      throw new Error(`Transaction not found: ${transactionId}`);
    }

    const normalizedFrom = normalizeAddress(from);
    const wallet = this.wallets.get(normalizedFrom);
    if (!wallet) {
      throw new Error(`Wallet not found for address: ${from}`);
    }

    if (normalizeAddress(wallet.address) !== normalizedFrom) {
      throw new Error('Wallet address mismatch');
    }

    const result = await withRetry(async () => {
      const tx = new Transaction();
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
      } else {
        tx.gasPrice = transaction.gasPrice || '20000000000';
        tx.type = 0;
      }

      const signedTx = await wallet.signTransaction(tx);
      const parsedTx = Transaction.from(signedTx);

      const signedTransaction: SignedTransaction = {
        rawTransaction: signedTx,
        hash: parsedTx.hash!,
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
        this.logger.warn('Retrying transaction signing', { transactionId, attempt, error: getErrorMessage(error) });
      },
    });

    this.signedTransactions.set(result.hash, result);
    this.pendingTransactions.delete(transactionId);

    eventBus.emit(EVENTS.TRANSACTION_SIGNED, result);
    this.logger.info('Transaction signed', { transactionId, hash: result.hash });

    return result;
  }

  async signMessage(message: string, from: string): Promise<{ signature: string; address: string }> {
    this.logger.info('Signing message', { from });

    const normalizedFrom = normalizeAddress(from);
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

  verifySignature(
    message: string,
    signature: string,
    expectedAddress: string
  ): boolean {
    try {
      const recoveredAddress = verifyMessage(message, signature);
      return normalizeAddress(recoveredAddress) === normalizeAddress(expectedAddress);
    } catch (error) {
      this.logger.error('Signature verification failed', error as Error);
      return false;
    }
  }

  getSignedTransaction(hash: string): SignedTransaction | undefined {
    return this.signedTransactions.get(hash);
  }

  listSignedTransactions(chainId?: ChainId, from?: string): SignedTransaction[] {
    let txs = Array.from(this.signedTransactions.values());

    if (chainId !== undefined) {
      txs = txs.filter((t) => t.chainId === chainId);
    }

    if (from) {
      txs = txs.filter((t) => normalizeAddress(t.from) === normalizeAddress(from));
    }

    return txs.sort((a, b) => b.nonce - a.nonce);
  }

  getPendingTransaction(transactionId: string): TransactionRequest | undefined {
    return this.pendingTransactions.get(transactionId);
  }

  listPendingTransactions(): Array<{ id: string; transaction: TransactionRequest }> {
    return Array.from(this.pendingTransactions.entries()).map(([id, transaction]) => ({
      id,
      transaction,
    }));
  }

  cancelPendingTransaction(transactionId: string): boolean {
    return this.pendingTransactions.delete(transactionId);
  }

  batchBuildTransactions(
    requests: TransactionRequest[],
    options?: { gasOptimization?: Partial<GasOptimizationConfig> }
  ): Promise<Array<{
    transactionId: string;
    transaction: TransactionRequest;
    estimatedGas: string;
    estimatedCost: string;
  }>> {
    this.logger.info('Batch building transactions', { count: requests.length });
    return Promise.all(requests.map((req) => this.buildTransaction(req, options)));
  }

  async batchSignTransactions(
    transactionIds: string[],
    from: string
  ): Promise<SignedTransaction[]> {
    this.logger.info('Batch signing transactions', { count: transactionIds.length, from });

    const results: SignedTransaction[] = [];
    for (const id of transactionIds) {
      try {
        const signed = await this.signTransaction(id, from);
        results.push(signed);
      } catch (error) {
        this.logger.error('Failed to sign transaction in batch', error as Error, { transactionId: id });
      }
    }

    return results;
  }

  optimizeGasForTransaction(
    transactionId: string,
    optimization: Partial<GasOptimizationConfig>
  ): TransactionRequest {
    const transaction = this.pendingTransactions.get(transactionId);
    if (!transaction) {
      throw new Error(`Transaction not found: ${transactionId}`);
    }

    const config: GasOptimizationConfig = {
      maxGasPrice: optimization.maxGasPrice || '100000000000',
      priorityFee: optimization.priorityFee || '2000000000',
      gasLimitMultiplier: optimization.gasLimitMultiplier || 1.2,
      useEIP1559: optimization.useEIP1559 ?? true,
    };

    if (config.useEIP1559) {
      transaction.maxFeePerGas = config.maxGasPrice;
      transaction.maxPriorityFeePerGas = config.priorityFee;
      delete transaction.gasPrice;
    } else {
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

export const transactionBuilder = new TransactionBuilder();
