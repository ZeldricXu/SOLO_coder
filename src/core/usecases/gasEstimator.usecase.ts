import type { Logger } from '@shared/logger';
import type { CachePort } from '@shared/cache';
import type {
  GasEstimatorPort,
  GasEstimateParams,
  GasEstimationStrategy,
  GasOptimizationPort,
  GasEstimatorDependencies,
} from '@core/ports/gasEstimator.port';
import type { GasEstimate, GasPriceHistory } from '@core/domain/blockchain';
import type { ChainId, WeiAmount, GasAmount } from '@shared/types';

export class GasEstimatorService implements GasEstimatorPort, GasOptimizationPort {
  private strategies: Map<string, GasEstimationStrategy> = new Map();
  private defaultStrategyId: string;
  private history: Map<ChainId, GasPriceHistory[]> = new Map();
  private readonly MAX_HISTORY_ITEMS = 1000;

  constructor(
    private readonly deps: GasEstimatorDependencies,
    private readonly logger: Logger,
    private readonly cache?: CachePort,
    private readonly config: {
      defaultSpeed: 'slow' | 'standard' | 'fast' | 'instant';
      defaultBufferPercentage: number;
      cacheTTL?: number;
    } = {
      defaultSpeed: 'standard',
      defaultBufferPercentage: 10,
      cacheTTL: 30000,
    }
  ) {
    this.defaultStrategyId = 'history-weighted';
    this.registerDefaultStrategies();
  }

  private registerDefaultStrategies(): void {
    this.addEstimationStrategy({
      id: 'simple',
      name: 'Simple Estimator',
      description: 'Uses current network fees directly',
      estimate: async (params, deps) => {
        const speed = params.speed || this.config.defaultSpeed;
        const [fees, gasLimit] = await Promise.all([
          deps.getFeePerGas(),
          params.gasLimit || deps.estimateGas({
            to: params.to,
            from: params.from,
            value: params.value,
            data: params.data,
          }),
        ]);

        const speedMultipliers: Record<string, number> = {
          slow: 0.9,
          standard: 1.0,
          fast: 1.2,
          instant: 1.5,
        };

        const multiplier = speedMultipliers[speed];
        const maxPriorityFeePerGas = (fees.maxPriorityFeePerGas * BigInt(Math.floor(multiplier * 100))) / BigInt(100);
        const baseFeePerGas = fees.baseFeePerGas;
        const maxFeePerGas = baseFeePerGas + maxPriorityFeePerGas;

        return {
          gasLimit,
          baseFeePerGas,
          maxPriorityFeePerGas,
          maxFeePerGas,
          estimatedCost: gasLimit * maxFeePerGas,
          confidence: 0.8,
          timestamp: new Date().toISOString(),
        };
      },
    });

    this.addEstimationStrategy({
      id: 'history-weighted',
      name: 'History Weighted Estimator',
      description: 'Combines historical data with current network state',
      estimate: async (params, deps) => {
        const speed = params.speed || this.config.defaultSpeed;
        const [fees, gasLimit, historicalData] = await Promise.all([
          deps.getFeePerGas(),
          params.gasLimit || deps.estimateGas({
            to: params.to,
            from: params.from,
            value: params.value,
            data: params.data,
          }),
          deps.getHistoricalData(params.chainId, 50),
        ]);

        const avgBaseFee = this.calculateWeightedAverage(
          historicalData.map(d => d.baseFeePerGas),
          historicalData.length
        );

        const avgPriorityFee = this.calculateWeightedAverage(
          historicalData.map(d => d.maxPriorityFeePerGas),
          historicalData.length
        );

        const volatility = this.calculateVolatility(
          historicalData.map(d => d.baseFeePerGas)
        );

        const confidence = Math.max(0.5, 1 - volatility);

        const speedMultipliers: Record<string, number> = {
          slow: 0.9,
          standard: 1.0,
          fast: 1.2,
          instant: 1.5,
        };

        const multiplier = speedMultipliers[speed];
        const baseFeePerGas = (avgBaseFee + fees.baseFeePerGas) / BigInt(2);
        const maxPriorityFeePerGas = ((avgPriorityFee + fees.maxPriorityFeePerGas) * BigInt(Math.floor(multiplier * 100))) / BigInt(200);
        const maxFeePerGas = baseFeePerGas * BigInt(2) + maxPriorityFeePerGas;

        return {
          gasLimit,
          baseFeePerGas,
          maxPriorityFeePerGas,
          maxFeePerGas,
          estimatedCost: gasLimit * maxFeePerGas,
          confidence,
          timestamp: new Date().toISOString(),
        };
      },
    });
  }

  private calculateWeightedAverage(values: bigint[], count: number): bigint {
    if (count === 0 || values.length === 0) return BigInt(0);
    const sum = values.reduce((acc, val, idx) => {
      const weight = BigInt(idx + 1);
      return acc + val * weight;
    }, BigInt(0));
    const totalWeight = BigInt((count * (count + 1)) / 2);
    return sum / totalWeight;
  }

  private calculateVolatility(values: bigint[]): number {
    if (values.length < 2) return 0.5;
    const numbers = values.map(v => Number(v));
    const mean = numbers.reduce((a, b) => a + b, 0) / numbers.length;
    const variance = numbers.reduce((acc, val) => acc + Math.pow(val - mean, 2), 0) / numbers.length;
    const stdDev = Math.sqrt(variance);
    return Math.min(1, stdDev / mean);
  }

  async estimate(params: GasEstimateParams): Promise<GasEstimate> {
    const cacheKey = `gas:estimate:${params.chainId}:${params.to || '0x0'}:${params.speed || 'standard'}`;

    if (this.cache) {
      const cached = await this.cache.get<GasEstimate>(cacheKey);
      if (cached) {
        this.logger.debug('Returning cached gas estimate', { chainId: params.chainId });
        return cached;
      }
    }

    const strategy = this.strategies.get(this.defaultStrategyId);
    if (!strategy) {
      throw new Error(`No gas estimation strategy found: ${this.defaultStrategyId}`);
    }

    this.logger.info('Estimating gas', { chainId: params.chainId, strategy: strategy.id });

    const estimate = await strategy.estimate(params, this.deps);

    if (this.cache && this.config.cacheTTL) {
      await this.cache.set(cacheKey, estimate, this.config.cacheTTL);
    }

    return estimate;
  }

  async getHistoricalPrices(chainId: ChainId, limit = 100): Promise<GasPriceHistory[]> {
    const history = this.history.get(chainId) || [];
    return history.slice(-limit);
  }

  async recordGasPrice(chainId: ChainId, data: Omit<GasPriceHistory, 'timestamp'>): Promise<void> {
    const history = this.history.get(chainId) || [];
    const record: GasPriceHistory = {
      ...data,
      timestamp: new Date().toISOString(),
    };
    history.push(record);

    if (history.length > this.MAX_HISTORY_ITEMS) {
      history.shift();
    }

    this.history.set(chainId, history);
    this.logger.debug('Recorded gas price', { chainId, baseFee: data.baseFeePerGas.toString() });
  }

  async getCurrentGasPrice(chainId: ChainId): Promise<{
    slow: { maxFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount };
    standard: { maxFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount };
    fast: { maxFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount };
    instant: { maxFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount };
  }> {
    const fees = await this.deps.getFeePerGas();

    const createTier = (multiplier: number) => ({
      maxPriorityFeePerGas: (fees.maxPriorityFeePerGas * BigInt(Math.floor(multiplier * 100))) / BigInt(100),
      maxFeePerGas: fees.baseFeePerGas * BigInt(2) + (fees.maxPriorityFeePerGas * BigInt(Math.floor(multiplier * 100))) / BigInt(100),
    });

    return {
      slow: createTier(0.9),
      standard: createTier(1.0),
      fast: createTier(1.2),
      instant: createTier(1.5),
    };
  }

  calculateTransactionCost(gasLimit: GasAmount, maxFeePerGas: WeiAmount): WeiAmount {
    return gasLimit * maxFeePerGas;
  }

  addEstimationStrategy(strategy: GasEstimationStrategy): void {
    this.strategies.set(strategy.id, strategy);
    this.logger.info('Added gas estimation strategy', { strategyId: strategy.id });
  }

  removeEstimationStrategy(strategyId: string): void {
    this.strategies.delete(strategyId);
    if (this.defaultStrategyId === strategyId) {
      this.defaultStrategyId = this.strategies.keys().next().value || 'simple';
    }
  }

  setDefaultStrategy(strategyId: string): void {
    if (!this.strategies.has(strategyId)) {
      throw new Error(`Strategy not found: ${strategyId}`);
    }
    this.defaultStrategyId = strategyId;
  }

  suggestGasLimit(
    chainId: ChainId,
    estimatedGas: GasAmount,
    bufferPercentage = this.config.defaultBufferPercentage
  ): GasAmount {
    const buffer = (estimatedGas * BigInt(bufferPercentage)) / BigInt(100);
    return estimatedGas + buffer;
  }

  suggestOptimalNonce(currentNonce: number, pendingCount: number): number {
    return currentNonce + pendingCount;
  }

  async shouldWaitForLowerGas(
    chainId: ChainId,
    currentEstimate: GasEstimate,
    urgency: 'low' | 'medium' | 'high',
    maxWaitTime = 3600000
  ): Promise<{ shouldWait: boolean; suggestedDelay: number }> {
    const historicalData = await this.deps.getHistoricalData(chainId, 100);
    if (historicalData.length < 20) {
      return { shouldWait: false, suggestedDelay: 0 };
    }

    const currentFee = Number(currentEstimate.maxFeePerGas);
    const avgFee = Number(historicalData.reduce((sum, d) => sum + d.baseFeePerGas, BigInt(0)) / BigInt(historicalData.length));

    const percentileThresholds: Record<string, number> = {
      low: 0.6,
      medium: 0.8,
      high: 1.0,
    };

    const threshold = avgFee * percentileThresholds[urgency];
    const isAboveThreshold = currentFee > threshold;

    if (urgency === 'high' || !isAboveThreshold || maxWaitTime <= 0) {
      return { shouldWait: false, suggestedDelay: 0 };
    }

    const suggestedDelay = urgency === 'low' ? 300000 : 60000;

    this.logger.info('Gas price above threshold, suggesting wait', {
      chainId,
      currentFee,
      threshold,
      urgency,
      suggestedDelay,
    });

    return { shouldWait: true, suggestedDelay: Math.min(suggestedDelay, maxWaitTime) };
  }

  async batchOptimizeTransactions(
    transactions: Array<{
      to: string;
      value?: bigint;
      data?: `0x${string}`;
      from?: string;
    }>,
    chainId: ChainId
  ): Promise<Array<{
    gasLimit: bigint;
    totalCost: bigint;
    savings: bigint;
  }>> {
    const results: Array<{ gasLimit: bigint; totalCost: bigint; savings: bigint }> = [];
    const baseEstimate = await this.deps.getFeePerGas();
    const baseFee = baseEstimate.baseFeePerGas;
    const priorityFee = baseEstimate.maxPriorityFeePerGas;
    const maxFeePerGas = baseFee * BigInt(2) + priorityFee;

    for (const tx of transactions) {
      const estimatedGas = await this.deps.estimateGas({
        to: tx.to,
        from: tx.from,
        value: tx.value,
        data: tx.data,
      });

      const optimizedGas = this.suggestGasLimit(chainId, estimatedGas, 5);
      const individualCost = optimizedGas * maxFeePerGas;
      const originalCost = estimatedGas * maxFeePerGas;
      const savings = originalCost - individualCost;

      results.push({
        gasLimit: optimizedGas,
        totalCost: individualCost,
        savings,
      });
    }

    return results;
  }
}
