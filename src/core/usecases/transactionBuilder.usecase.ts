import type { Logger } from '@shared/logger';
import type {
  TransactionBuilderPort,
  TransactionBuildParams,
  BuiltTransaction,
  MultisigStrategy,
  GasOptimizationConfig,
} from '@core/ports/transactionBuilder.port';
import type { Transaction, TransactionSignature, GasEstimate } from '@core/domain/blockchain';
import type { Hash, HexString, ChainId, Address, WeiAmount, GasAmount } from '@shared/types';
import { NotFoundError, ValidationError } from '@shared/errors';
import { z } from 'zod';

export class TransactionBuilderService implements TransactionBuilderPort {
  private multisigStrategy: MultisigStrategy | null = null;
  private gasConfig: GasOptimizationConfig;

  constructor(
    private readonly logger: Logger,
    config?: Partial<GasOptimizationConfig>
  ) {
    this.gasConfig = {
      enabled: true,
      speed: 'standard',
      gasLimitBuffer: 10,
      priorityFeeBoost: 0,
      batchEnabled: false,
      retryOnFailure: true,
      maxRetries: 3,
      ...config,
    };
  }

  private validateBuildParams(params: TransactionBuildParams): void {
    const schema = z.object({
      chainId: z.number().int().positive(),
      from: z.string().regex(/^0x[a-fA-F0-9]{40}$/, 'Invalid address format'),
      to: z.string().regex(/^0x[a-fA-F0-9]{40}$/, 'Invalid address format'),
      value: z.bigint().min(BigInt(0)).optional(),
      data: z.string().regex(/^0x[a-fA-F0-9]*$/).optional(),
      nonce: z.number().int().min(0).optional(),
      gasLimit: z.bigint().min(BigInt(21000)).optional(),
      gasPrice: z.bigint().min(BigInt(0)).optional(),
      maxFeePerGas: z.bigint().min(BigInt(0)).optional(),
      maxPriorityFeePerGas: z.bigint().min(BigInt(0)).optional(),
      type: z.union([z.literal(0), z.literal(1), z.literal(2)]).optional(),
      accessList: z.array(
        z.object({
          address: z.string(),
          storageKeys: z.array(z.string()),
        })
      ).optional(),
    });

    const result = schema.safeParse(params);
    if (!result.success) {
      const errors: Record<string, string[]> = {};
      for (const issue of result.error.issues) {
        const key = issue.path.join('.');
        if (!errors[key]) errors[key] = [];
        errors[key].push(issue.message);
      }
      throw new ValidationError(errors);
    }
  }

  private calculateTransactionHash(tx: Omit<Transaction, 'signature'>): Hash {
    const fields = [
      tx.nonce.toString(16),
      (tx.gasPrice || tx.maxFeePerGas || BigInt(0)).toString(16),
      tx.gasLimit.toString(16),
      tx.to || '0x',
      tx.value.toString(16),
      tx.data,
      tx.chainId.toString(16),
      '0x',
      '0x',
    ];

    let hash = '0x';
    for (const field of fields) {
      hash += field.replace('0x', '').padStart(2, '0');
    }

    const encoder = new TextEncoder();
    const data = encoder.encode(hash);
    let simpleHash = 0;
    for (let i = 0; i < data.length; i++) {
      simpleHash = ((simpleHash << 5) - simpleHash + data[i]) | 0;
    }

    return `0x${Math.abs(simpleHash).toString(16).padStart(64, '0')}` as Hash;
  }

  private encodeUnsignedTransaction(tx: Omit<Transaction, 'signature'>): HexString {
    let encoded = '0x';

    encoded += this.encodeField(tx.nonce);
    if (tx.type === 2) {
      encoded += this.encodeField(tx.maxPriorityFeePerGas || BigInt(0));
      encoded += this.encodeField(tx.maxFeePerGas || BigInt(0));
    } else {
      encoded += this.encodeField(tx.gasPrice || BigInt(0));
    }
    encoded += this.encodeField(tx.gasLimit);
    encoded += this.encodeAddress(tx.to || null);
    encoded += this.encodeField(tx.value);
    encoded += this.encodeData(tx.data);
    encoded += this.encodeField(tx.chainId);

    return encoded as HexString;
  }

  private encodeField(value: bigint | number): string {
    const hex = typeof value === 'number' ? value.toString(16) : value.toString(16);
    return hex.length % 2 === 0 ? hex : '0' + hex;
  }

  private encodeAddress(address: Address | null): string {
    if (!address) return '00';
    return address.replace('0x', '');
  }

  private encodeData(data: HexString): string {
    return data.replace('0x', '');
  }

  async buildTransaction(params: TransactionBuildParams): Promise<BuiltTransaction> {
    this.validateBuildParams(params);
    this.logger.info('Building transaction', { chainId: params.chainId, to: params.to });

    const type = params.type || (params.maxFeePerGas ? 2 : 0);

    const transaction: Omit<Transaction, 'signature'> = {
      hash: '' as Hash,
      from: params.from,
      to: params.to,
      value: params.value || BigInt(0),
      data: params.data || '0x' as HexString,
      nonce: params.nonce || 0,
      gasLimit: params.gasLimit || BigInt(21000),
      chainId: params.chainId,
      type: type as 0 | 1 | 2,
      ...(type === 2
        ? {
            maxFeePerGas: params.maxFeePerGas || BigInt('30000000000'),
            maxPriorityFeePerGas: params.maxPriorityFeePerGas || BigInt('2000000000'),
          }
        : { gasPrice: params.gasPrice || BigInt('30000000000') }),
    };

    const transactionHash = this.calculateTransactionHash(transaction);
    transaction.hash = transactionHash;

    return {
      transaction,
      transactionHash,
      unsignedData: this.encodeUnsignedTransaction(transaction),
      chainId: params.chainId,
    };
  }

  async buildEIP1559Transaction(
    params: Omit<TransactionBuildParams, 'type'> & {
      maxFeePerGas: WeiAmount;
      maxPriorityFeePerGas: WeiAmount;
    }
  ): Promise<BuiltTransaction> {
    return this.buildTransaction({
      ...params,
      type: 2,
    });
  }

  async buildLegacyTransaction(
    params: Omit<TransactionBuildParams, 'type'> & { gasPrice: WeiAmount }
  ): Promise<BuiltTransaction> {
    return this.buildTransaction({
      ...params,
      type: 0,
    });
  }

  async buildContractDeployment(
    chainId: ChainId,
    from: Address,
    bytecode: HexString,
    constructorArgs: unknown[] = [],
    abi?: unknown
  ): Promise<BuiltTransaction> {
    this.logger.info('Building contract deployment', { chainId, from });

    let data = bytecode;
    if (abi && constructorArgs.length > 0) {
      data = `${bytecode}${this.encodeConstructorArgs(constructorArgs, abi)}` as HexString;
    }

    return this.buildTransaction({
      chainId,
      from,
      to: '0x0000000000000000000000000000000000000000' as Address,
      value: BigInt(0),
      data,
      type: 2,
    });
  }

  private encodeConstructorArgs(args: unknown[], abi: unknown): string {
    return args.map(arg => {
      if (typeof arg === 'bigint' || typeof arg === 'number') {
        return arg.toString(16).padStart(64, '0');
      }
      if (typeof arg === 'string' && arg.startsWith('0x')) {
        return arg.replace('0x', '').padStart(64, '0');
      }
      return String(arg);
    }).join('');
  }

  async buildContractCall(
    chainId: ChainId,
    from: Address,
    contractAddress: Address,
    methodName: string,
    params: unknown[],
    abi: unknown
  ): Promise<BuiltTransaction> {
    this.logger.info('Building contract call', { chainId, contractAddress, methodName });

    const functionSignature = this.getFunctionSignature(methodName, abi);
    const data = `0x${functionSignature}${this.encodeFunctionParams(params)}` as HexString;

    return this.buildTransaction({
      chainId,
      from,
      to: contractAddress,
      value: BigInt(0),
      data,
      type: 2,
    });
  }

  private getFunctionSignature(methodName: string, abi: unknown): string {
    if (Array.isArray(abi)) {
      const fn = abi.find((item: { name?: string; type?: string }) =>
        item.name === methodName && item.type === 'function'
      );
      if (fn) {
        const params = (fn.inputs || []).map((i: { type: string }) => i.type).join(',');
        const sig = `${methodName}(${params})`;
        const encoder = new TextEncoder();
        const data = encoder.encode(sig);
        let hash = 0;
        for (let i = 0; i < data.length; i++) {
          hash = ((hash << 5) - hash + data[i]) | 0;
        }
        return Math.abs(hash).toString(16).padStart(8, '0').slice(0, 8);
      }
    }
    return '00000000';
  }

  private encodeFunctionParams(params: unknown[]): string {
    return params.map(p => {
      if (typeof p === 'bigint' || typeof p === 'number') {
        return p.toString(16).padStart(64, '0');
      }
      if (typeof p === 'string' && p.startsWith('0x')) {
        return p.replace('0x', '').padStart(64, '0');
      }
      return String(p);
    }).join('');
  }

  async attachSignature(
    builtTransaction: BuiltTransaction,
    signature: TransactionSignature
  ): Promise<BuiltTransaction & { signedTransaction: HexString; transaction: Transaction }> {
    const signedData = `${builtTransaction.unsignedData}${signature.v.toString(16)}${signature.r.replace('0x', '')}${signature.s.replace('0x', '')}`;

    const transaction: Transaction = {
      ...builtTransaction.transaction,
      signature,
    };

    this.logger.debug('Attached signature to transaction', {
      transactionHash: builtTransaction.transactionHash,
    });

    return {
      ...builtTransaction,
      signedTransaction: signedData as HexString,
      transaction,
    };
  }

  setMultisigStrategy(strategy: MultisigStrategy): void {
    this.multisigStrategy = strategy;
    this.logger.info('Set multisig strategy', { strategyId: strategy.id });
  }

  getMultisigStrategy(): MultisigStrategy | null {
    return this.multisigStrategy;
  }

  setGasOptimizationConfig(config: GasOptimizationConfig): void {
    this.gasConfig = { ...this.gasConfig, ...config };
    this.logger.info('Updated gas optimization config', { config });
  }

  getGasOptimizationConfig(): GasOptimizationConfig {
    return { ...this.gasConfig };
  }

  async applyGasOptimization(
    transaction: BuiltTransaction,
    gasEstimate: GasEstimate
  ): Promise<BuiltTransaction> {
    if (!this.gasConfig.enabled) {
      return transaction;
    }

    this.logger.info('Applying gas optimization', {
      transactionHash: transaction.transactionHash,
    });

    const bufferPercentage = this.gasConfig.gasLimitBuffer || 10;
    const buffer = (gasEstimate.gasLimit * BigInt(bufferPercentage)) / BigInt(100);
    const optimizedGasLimit = gasEstimate.gasLimit + buffer;

    const priorityFeeBoost = this.gasConfig.priorityFeeBoost || 0;
    const boostedPriorityFee = gasEstimate.maxPriorityFeePerGas * (BigInt(100 + priorityFeeBoost)) / BigInt(100);

    const updatedTx: Omit<Transaction, 'signature'> = {
      ...transaction.transaction,
      gasLimit: optimizedGasLimit,
      maxPriorityFeePerGas: boostedPriorityFee,
      maxFeePerGas: gasEstimate.baseFeePerGas * BigInt(2) + boostedPriorityFee,
    };

    return {
      ...transaction,
      transaction: updatedTx,
    };
  }
}
