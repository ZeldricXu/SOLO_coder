import type { ChainId, Address, HexString, WeiAmount, GasAmount } from '@shared/types';
import type { GasEstimate, GasPriceHistory } from '@core/domain/blockchain';

export interface GasEstimateParams {
  chainId: ChainId;
  to?: Address;
  from?: Address;
  value?: WeiAmount;
  data?: HexString;
  gasLimit?: GasAmount;
  speed?: 'slow' | 'standard' | 'fast' | 'instant';
}

export interface GasEstimationStrategy {
  id: string;
  name: string;
  description: string;
  estimate(params: GasEstimateParams, deps: GasEstimatorDependencies): Promise<GasEstimate>;
}

export interface GasEstimatorDependencies {
  getBlockNumber: () => Promise<bigint>;
  getBlock: (blockNumber: bigint) => Promise<{ baseFeePerGas?: WeiAmount; gasUsed: GasAmount; gasLimit: GasAmount } | null>;
  getFeePerGas: () => Promise<{ baseFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount }>;
  estimateGas: (tx: { to?: Address; from?: Address; value?: WeiAmount; data?: HexString }) => Promise<GasAmount>;
  getHistoricalData: (chainId: ChainId, limit?: number) => Promise<GasPriceHistory[]>;
  getPendingTransactions?: (chainId: ChainId) => Promise<number>;
}

export interface GasEstimatorPort {
  estimate(params: GasEstimateParams): Promise<GasEstimate>;

  getHistoricalPrices(chainId: ChainId, limit?: number): Promise<GasPriceHistory[]>;

  recordGasPrice(chainId: ChainId, data: Omit<GasPriceHistory, 'timestamp'>): Promise<void>;

  getCurrentGasPrice(chainId: ChainId): Promise<{
    slow: { maxFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount };
    standard: { maxFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount };
    fast: { maxFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount };
    instant: { maxFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount };
  }>;

  calculateTransactionCost(
    gasLimit: GasAmount,
    maxFeePerGas: WeiAmount
  ): WeiAmount;

  addEstimationStrategy(strategy: GasEstimationStrategy): void;
  removeEstimationStrategy(strategyId: string): void;
  setDefaultStrategy(strategyId: string): void;
}

export interface GasOptimizationPort {
  suggestGasLimit(
    chainId: ChainId,
    estimatedGas: GasAmount,
    bufferPercentage?: number
  ): GasAmount;

  suggestOptimalNonce(currentNonce: number, pendingCount: number): number;

  shouldWaitForLowerGas(
    chainId: ChainId,
    currentEstimate: GasEstimate,
    urgency: 'low' | 'medium' | 'high',
    maxWaitTime?: number
  ): Promise<{ shouldWait: boolean; suggestedDelay: number }>;

  batchOptimizeTransactions(
    transactions: Array<{
      to: Address;
      value?: WeiAmount;
      data?: HexString;
      from?: Address;
    }>,
    chainId: ChainId
  ): Promise<Array<{
    gasLimit: GasAmount;
    totalCost: WeiAmount;
    savings: WeiAmount;
  }>>;
}
