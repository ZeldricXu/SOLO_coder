import {
  JsonRpcProvider,
  TransactionResponse,
  TransactionReceipt,
  Block,
  Log,
  FeeData,
} from 'ethers';
import { ChainId, ChainConfig } from '../types';
import { CHAIN_CONFIGS } from '../config';
import { generateId, now, normalizeAddress, withRetry, getErrorMessage } from '../common/utils';
import { LoggerContext } from '../common/logger';

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

export class ChainAdapter {
  private providers: Map<ChainId, JsonRpcProvider>;
  private submissions: Map<string, TransactionSubmission>;
  private logger: LoggerContext;

  constructor() {
    this.providers = new Map();
    this.submissions = new Map();
    this.logger = new LoggerContext({ module: 'ChainAdapter' });
  }

  getProvider(chainId: ChainId): JsonRpcProvider {
    if (!this.providers.has(chainId)) {
      const config = CHAIN_CONFIGS[chainId];
      if (!config) {
        throw new Error(`Unsupported chain: ${chainId}`);
      }
      this.providers.set(chainId, new JsonRpcProvider(config.rpcUrl, chainId));
    }
    return this.providers.get(chainId)!;
  }

  getChainConfig(chainId: ChainId): ChainConfig {
    const config = CHAIN_CONFIGS[chainId];
    if (!config) {
      throw new Error(`Unsupported chain: ${chainId}`);
    }
    return config;
  }

  listSupportedChains(): ChainConfig[] {
    return Object.values(CHAIN_CONFIGS);
  }

  async getBlockNumber(chainId: ChainId): Promise<number> {
    const provider = this.getProvider(chainId);
    return withRetry(() => provider.getBlockNumber(), {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying getBlockNumber', { chainId, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async getBlock(
    chainId: ChainId,
    blockNumber: number | string,
    includeTransactions: boolean = false
  ): Promise<ChainBlock | null> {
    const provider = this.getProvider(chainId);

    return withRetry(async () => {
      const block = await provider.getBlock(blockNumber, includeTransactions);
      if (!block) return null;

      return {
        number: blockNumber as number,
        hash: block.hash || '',
        parentHash: block.parentHash,
        timestamp: block.timestamp,
        miner: block.miner,
        difficulty: block.difficulty.toString(),
        gasLimit: block.gasLimit.toString(),
        gasUsed: block.gasUsed.toString(),
        transactionCount: block.transactions.length,
        transactions: block.transactions.map((tx) => (typeof tx === 'string' ? tx : (tx as { hash: string }).hash)),
        baseFee: block.baseFeePerGas?.toString(),
      };
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying getBlock', { chainId, blockNumber, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async getLatestBlock(chainId: ChainId, includeTransactions: boolean = false): Promise<ChainBlock | null> {
    return this.getBlock(chainId, 'latest', includeTransactions);
  }

  async getTransaction(chainId: ChainId, hash: string): Promise<ChainTransaction | null> {
    const provider = this.getProvider(chainId);

    return withRetry(async () => {
      const tx = await provider.getTransaction(hash);
      if (!tx) return null;

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
        this.logger.warn('Retrying getTransaction', { chainId, hash, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async getTransactionReceipt(
    chainId: ChainId,
    hash: string
  ): Promise<TransactionReceipt | null> {
    const provider = this.getProvider(chainId);

    return withRetry(() => provider.getTransactionReceipt(hash), {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying getTransactionReceipt', { chainId, hash, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async getBalance(chainId: ChainId, address: string, blockTag?: number | string): Promise<string> {
    const provider = this.getProvider(chainId);
    const normalizedAddress = normalizeAddress(address);

    return withRetry(async () => {
      const balance = await provider.getBalance(normalizedAddress, blockTag);
      return balance.toString();
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying getBalance', { chainId, address, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async getNonce(chainId: ChainId, address: string, blockTag?: number | string): Promise<number> {
    const provider = this.getProvider(chainId);
    const normalizedAddress = normalizeAddress(address);

    return withRetry(async () => {
      return provider.getTransactionCount(normalizedAddress, blockTag);
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying getNonce', { chainId, address, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async getGasPrice(chainId: ChainId): Promise<string> {
    const provider = this.getProvider(chainId);

    return withRetry(async () => {
      const feeData = await provider.getFeeData();
      return (feeData.gasPrice ?? feeData.maxFeePerGas ?? 0n).toString();
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying getGasPrice', { chainId, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async getFeeData(chainId: ChainId): Promise<FeeData> {
    const provider = this.getProvider(chainId);

    return withRetry(() => provider.getFeeData(), {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying getFeeData', { chainId, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async estimateGas(chainId: ChainId, params: {
    to?: string;
    from?: string;
    value?: string;
    data?: string;
    gasPrice?: string;
  }): Promise<string> {
    const provider = this.getProvider(chainId);

    return withRetry(async () => {
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
        this.logger.warn('Retrying estimateGas', { chainId, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async getLogs(chainId: ChainId, params: {
    fromBlock?: number | string;
    toBlock?: number | string;
    address?: string;
    topics?: string[];
  }): Promise<Log[]> {
    const provider = this.getProvider(chainId);

    return withRetry(async () => {
      return provider.getLogs({
        fromBlock: params.fromBlock,
        toBlock: params.toBlock,
        address: params.address ? normalizeAddress(params.address) : undefined,
        topics: params.topics,
      });
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying getLogs', { chainId, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async getCode(chainId: ChainId, address: string, blockTag?: number | string): Promise<string> {
    const provider = this.getProvider(chainId);
    const normalizedAddress = normalizeAddress(address);

    return withRetry(() => provider.getCode(normalizedAddress, blockTag), {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying getCode', { chainId, address, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async call(chainId: ChainId, params: {
    to: string;
    from?: string;
    data: string;
    blockTag?: number | string;
  }): Promise<string> {
    const provider = this.getProvider(chainId);

    return withRetry(async () => {
      return provider.call({
        to: normalizeAddress(params.to),
        from: params.from ? normalizeAddress(params.from) : undefined,
        data: params.data,
        blockTag: params.blockTag,
      });
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying call', { chainId, to: params.to, attempt, error: getErrorMessage(error) });
      },
    });
  }

  async sendTransaction(chainId: ChainId, rawTransaction: string): Promise<TransactionSubmission> {
    this.logger.info('Sending transaction', { chainId });

    const provider = this.getProvider(chainId);
    const submissionId = generateId('submit');

    return withRetry(async () => {
      const txResponse = await provider.broadcastTransaction(rawTransaction);

      const submission: TransactionSubmission = {
        id: submissionId,
        chainId,
        rawTransaction,
        hash: txResponse.hash,
        status: 'pending',
        submittedAt: now(),
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
        this.logger.warn('Retrying sendTransaction', { chainId, attempt, error: getErrorMessage(error) });
      },
    });
  }

  private async monitorTransaction(submissionId: string): Promise<void> {
    const submission = this.submissions.get(submissionId);
    if (!submission) return;

    const provider = this.getProvider(submission.chainId);
    const maxAttempts = 50;
    let attempts = 0;

    while (attempts < maxAttempts && submission.status === 'pending') {
      attempts++;

      try {
        const receipt = await provider.getTransactionReceipt(submission.hash);

        if (receipt) {
          submission.status = receipt.status === 1 ? 'confirmed' : 'failed';
          submission.confirmedAt = now();
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
      } catch (error) {
        this.logger.warn('Error checking transaction status', error as Error, { submissionId });
        await new Promise((resolve) => setTimeout(resolve, 5000));
      }
    }

    if (submission.status === 'pending' && attempts >= maxAttempts) {
      this.logger.warn('Transaction monitoring timed out', { submissionId, hash: submission.hash });
    }
  }

  async waitForTransaction(
    chainId: ChainId,
    hash: string,
    confirmations: number = 1,
    timeout: number = 60000
  ): Promise<TransactionReceipt | null> {
    const provider = this.getProvider(chainId);

    return withRetry(async () => {
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
        this.logger.warn('Retrying waitForTransaction', { chainId, hash, attempt, error: getErrorMessage(error) });
      },
    });
  }

  getSubmission(submissionId: string): TransactionSubmission | undefined {
    return this.submissions.get(submissionId);
  }

  listSubmissions(chainId?: ChainId, status?: TransactionSubmission['status']): TransactionSubmission[] {
    let submissions = Array.from(this.submissions.values());

    if (chainId !== undefined) {
      submissions = submissions.filter((s) => s.chainId === chainId);
    }

    if (status) {
      submissions = submissions.filter((s) => s.status === status);
    }

    return submissions.sort((a, b) =>
      new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime()
    );
  }

  async batchGetBalances(
    chainId: ChainId,
    addresses: string[]
  ): Promise<Array<{ address: string; balance: string }>> {
    this.logger.info('Batch getting balances', { chainId, count: addresses.length });

    const results = await Promise.all(
      addresses.map(async (address) => ({
        address,
        balance: await this.getBalance(chainId, address),
      }))
    );

    return results;
  }

  async batchSendTransactions(
    chainId: ChainId,
    rawTransactions: string[]
  ): Promise<TransactionSubmission[]> {
    this.logger.info('Batch sending transactions', { chainId, count: rawTransactions.length });

    const results = await Promise.all(
      rawTransactions.map((rawTx) => this.sendTransaction(chainId, rawTx))
    );

    return results;
  }

  async getChainStats(chainId: ChainId): Promise<{
    blockNumber: number;
    gasPrice: string;
    baseFee?: string;
    maxPriorityFee?: string;
  }> {
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

  disconnect(): void {
    this.providers.forEach((provider) => {
      provider.destroy();
    });
    this.providers.clear();
    this.logger.info('All chain connections disconnected');
  }
}

export const chainAdapter = new ChainAdapter();
