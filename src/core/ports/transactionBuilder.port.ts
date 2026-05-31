import type { ChainId, Address, Hash, HexString, WeiAmount, GasAmount } from '@shared/types';
import type { Transaction, TransactionSignature, GasEstimate } from '@core/domain/blockchain';

export interface TransactionBuildParams {
  chainId: ChainId;
  from: Address;
  to: Address;
  value?: WeiAmount;
  data?: HexString;
  nonce?: number;
  gasLimit?: GasAmount;
  gasPrice?: WeiAmount;
  maxFeePerGas?: WeiAmount;
  maxPriorityFeePerGas?: WeiAmount;
  type?: 0 | 1 | 2;
  accessList?: Array<{ address: Address; storageKeys: HexString[] }>;
}

export interface MultisigStrategy {
  id: string;
  name: string;
  threshold: number;
  owners: Address[];
  validateSignatures(
    transactionHash: Hash,
    signatures: Map<Address, TransactionSignature>
  ): Promise<boolean>;
  combineSignatures(
    signatures: Map<Address, TransactionSignature>
  ): Promise<HexString>;
}

export interface GasOptimizationConfig {
  enabled: boolean;
  speed: 'slow' | 'standard' | 'fast' | 'instant';
  maxGasPrice?: WeiAmount;
  priorityFeeBoost?: number;
  gasLimitBuffer?: number;
  batchEnabled?: boolean;
  retryOnFailure?: boolean;
  maxRetries?: number;
}

export interface BuiltTransaction {
  transaction: Omit<Transaction, 'signature'>;
  transactionHash: Hash;
  unsignedData: HexString;
  chainId: ChainId;
}

export interface TransactionBuilderPort {
  buildTransaction(params: TransactionBuildParams): Promise<BuiltTransaction>;

  buildEIP1559Transaction(
    params: Omit<TransactionBuildParams, 'type'> & {
      maxFeePerGas: WeiAmount;
      maxPriorityFeePerGas: WeiAmount;
    }
  ): Promise<BuiltTransaction>;

  buildLegacyTransaction(
    params: Omit<TransactionBuildParams, 'type'> & { gasPrice: WeiAmount }
  ): Promise<BuiltTransaction>;

  buildContractDeployment(
    chainId: ChainId,
    from: Address,
    bytecode: HexString,
    constructorArgs?: unknown[],
    abi?: unknown
  ): Promise<BuiltTransaction>;

  buildContractCall(
    chainId: ChainId,
    from: Address,
    contractAddress: Address,
    methodName: string,
    params: unknown[],
    abi: unknown
  ): Promise<BuiltTransaction>;

  attachSignature(
    builtTransaction: BuiltTransaction,
    signature: TransactionSignature
  ): Promise<BuiltTransaction & { signedTransaction: HexString; transaction: Transaction }>;

  setMultisigStrategy(strategy: MultisigStrategy): void;
  getMultisigStrategy(): MultisigStrategy | null;

  setGasOptimizationConfig(config: GasOptimizationConfig): void;
  getGasOptimizationConfig(): GasOptimizationConfig;

  applyGasOptimization(
    transaction: BuiltTransaction,
    gasEstimate: GasEstimate
  ): Promise<BuiltTransaction>;
}

export interface TransactionSignerPort {
  signTransaction(builtTransaction: BuiltTransaction): Promise<{
    signedTransaction: HexString;
    signature: TransactionSignature;
  }>;

  signHash(hash: Hash): Promise<TransactionSignature>;

  getAddress(): Promise<Address>;
}
