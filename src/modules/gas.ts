import { GasEstimate, ChainId } from '../types';
import { CHAIN_CONFIGS, GAS_HISTORY_WINDOW, GAS_PRICE_PERCENTILES } from '../config';
import { chainAdapter } from './chainadapter';
import { now, calculatePercentile, withRetry, getErrorMessage } from '../common/utils';
import { eventBus, EVENTS } from '../common/events';
import { LoggerContext } from '../common/logger';

export interface GasHistoryEntry {
  timestamp: string;
  blockNumber: number;
  baseFee: string;
  gasPrice: string;
  maxPriorityFeePerGas: string;
  maxFeePerGas: string;
  gasUsed: string;
  gasLimit: string;
}

export interface GasPrediction {
  predictedBaseFee: string;
  predictedGasPrice: string;
  confidence: number;
  trend: 'up' | 'down' | 'stable';
}

export class GasEstimator {
  private history: Map<ChainId, GasHistoryEntry[]>;
  private logger: LoggerContext;
  private defaultPriorityFee = '2000000000';

  constructor() {
    this.history = new Map();
    this.logger = new LoggerContext({ module: 'GasEstimator' });
  }

  async estimateGas(params: {
    chainId: ChainId;
    to?: string;
    data?: string;
    value?: string;
  }): Promise<GasEstimate> {
    const { chainId, to, data, value } = params;

    this.logger.info('Estimating gas', { chainId, to });

    const config = CHAIN_CONFIGS[chainId];
    if (!config) {
      throw new Error(`Unsupported chain: ${chainId}`);
    }

    await this.collectGasData(chainId);

    const history = this.history.get(chainId) || [];
    if (history.length === 0) {
      throw new Error('No gas history available for this chain');
    }

    const baseFees = history.map((h) => parseInt(h.baseFee));
    const priorityFees = history.map((h) => parseInt(h.maxPriorityFeePerGas));
    const gasPrices = history.map((h) => parseInt(h.gasPrice));

    const currentBaseFee = baseFees[baseFees.length - 1];
    const prediction = this.predictNextBaseFee(baseFees);

    const slowPriorityFee = calculatePercentile(priorityFees, GAS_PRICE_PERCENTILES.slow);
    const standardPriorityFee = calculatePercentile(priorityFees, GAS_PRICE_PERCENTILES.standard);
    const fastPriorityFee = calculatePercentile(priorityFees, GAS_PRICE_PERCENTILES.fast);

    const slowGasPrice = calculatePercentile(gasPrices, GAS_PRICE_PERCENTILES.slow);
    const standardGasPrice = calculatePercentile(gasPrices, GAS_PRICE_PERCENTILES.standard);
    const fastGasPrice = calculatePercentile(gasPrices, GAS_PRICE_PERCENTILES.fast);

    const estimate: GasEstimate = {
      chainId,
      slow: {
        gasPrice: slowGasPrice.toString(),
        maxFeePerGas: (currentBaseFee * 2 + slowPriorityFee).toString(),
        maxPriorityFeePerGas: slowPriorityFee.toString(),
        estimatedTime: this.estimateConfirmationTime(slowGasPrice),
      },
      standard: {
        gasPrice: standardGasPrice.toString(),
        maxFeePerGas: (currentBaseFee * 2 + standardPriorityFee).toString(),
        maxPriorityFeePerGas: standardPriorityFee.toString(),
        estimatedTime: this.estimateConfirmationTime(standardGasPrice),
      },
      fast: {
        gasPrice: fastGasPrice.toString(),
        maxFeePerGas: (currentBaseFee * 2 + fastPriorityFee).toString(),
        maxPriorityFeePerGas: fastPriorityFee.toString(),
        estimatedTime: this.estimateConfirmationTime(fastGasPrice),
      },
      baseFee: currentBaseFee.toString(),
      timestamp: now(),
    };

    eventBus.emit(EVENTS.GAS_ESTIMATED, { chainId, estimate });
    this.logger.info('Gas estimation complete', {
      chainId,
      baseFee: currentBaseFee,
      standardGasPrice,
    });

    return estimate;
  }

  private async collectGasData(chainId: ChainId): Promise<void> {
    this.logger.debug('Collecting gas data', { chainId });

    return withRetry(async () => {
      const [blockNumber, feeData, block] = await Promise.all([
        chainAdapter.getBlockNumber(chainId),
        chainAdapter.getFeeData(chainId),
        chainAdapter.getLatestBlock(chainId),
      ]);

      const entry: GasHistoryEntry = {
        timestamp: now(),
        blockNumber,
        baseFee: block?.baseFee || '0',
        gasPrice: feeData.gasPrice?.toString() || '0',
        maxPriorityFeePerGas: feeData.maxPriorityFeePerGas?.toString() || this.defaultPriorityFee,
        maxFeePerGas: feeData.maxFeePerGas?.toString() || '0',
        gasUsed: block?.gasUsed || '0',
        gasLimit: block?.gasLimit || '0',
      };

      if (!this.history.has(chainId)) {
        this.history.set(chainId, []);
      }

      const chainHistory = this.history.get(chainId)!;
      chainHistory.push(entry);

      if (chainHistory.length > GAS_HISTORY_WINDOW) {
        chainHistory.shift();
      }

      this.logger.debug('Gas data collected', { chainId, blockNumber });
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying gas data collection', { chainId, attempt, error: getErrorMessage(error) });
      },
    });
  }

  private predictNextBaseFee(baseFees: number[]): GasPrediction {
    if (baseFees.length < 2) {
      return {
        predictedBaseFee: baseFees[baseFees.length - 1]?.toString() || '0',
        predictedGasPrice: baseFees[baseFees.length - 1]?.toString() || '0',
        confidence: 0.5,
        trend: 'stable',
      };
    }

    const recentFees = baseFees.slice(-10);
    const avgRecent = recentFees.reduce((a, b) => a + b, 0) / recentFees.length;
    const currentFee = baseFees[baseFees.length - 1];
    const previousFee = baseFees[baseFees.length - 2];

    const changePercent = ((currentFee - previousFee) / previousFee) * 100;

    let trend: 'up' | 'down' | 'stable' = 'stable';
    if (changePercent > 5) trend = 'up';
    else if (changePercent < -5) trend = 'down';

    const predictedBaseFee = Math.floor(currentFee * (1 + changePercent / 200));
    const confidence = this.calculateConfidence(recentFees);

    return {
      predictedBaseFee: predictedBaseFee.toString(),
      predictedGasPrice: (predictedBaseFee + parseInt(this.defaultPriorityFee)).toString(),
      confidence,
      trend,
    };
  }

  private calculateConfidence(values: number[]): number {
    if (values.length < 2) return 0.5;

    const mean = values.reduce((a, b) => a + b, 0) / values.length;
    const variance = values.reduce((sum, val) => sum + Math.pow(val - mean, 2), 0) / values.length;
    const stdDev = Math.sqrt(variance);
    const coefficientOfVariation = stdDev / mean;

    return Math.max(0, Math.min(1, 1 - coefficientOfVariation));
  }

  private estimateConfirmationTime(gasPrice: number): number {
    const baseTime = 15;
    const typicalGasPrice = 30000000000;

    if (gasPrice >= typicalGasPrice * 1.5) {
      return Math.floor(baseTime * 0.3);
    } else if (gasPrice >= typicalGasPrice) {
      return baseTime;
    } else if (gasPrice >= typicalGasPrice * 0.7) {
      return Math.floor(baseTime * 2);
    } else {
      return Math.floor(baseTime * 5);
    }
  }

  async getGasHistory(chainId: ChainId, limit: number = 50): Promise<GasHistoryEntry[]> {
    if (!this.history.has(chainId) || this.history.get(chainId)!.length === 0) {
      await this.collectGasData(chainId);
    }

    const history = this.history.get(chainId) || [];
    return history.slice(-limit).reverse();
  }

  async getGasPrediction(chainId: ChainId): Promise<GasPrediction> {
    if (!this.history.has(chainId) || this.history.get(chainId)!.length === 0) {
      await this.collectGasData(chainId);
    }

    const history = this.history.get(chainId) || [];
    const baseFees = history.map((h) => parseInt(h.baseFee));

    return this.predictNextBaseFee(baseFees);
  }

  async estimateTransactionCost(params: {
    chainId: ChainId;
    to?: string;
    data?: string;
    value?: string;
    gasPriceLevel?: 'slow' | 'standard' | 'fast';
  }): Promise<{
    gasLimit: string;
    gasPrice: string;
    maxFeePerGas: string;
    maxPriorityFeePerGas: string;
    estimatedCost: string;
    estimatedTime: number;
  }> {
    const { chainId, to, data, value, gasPriceLevel = 'standard' } = params;

    const [estimate, gasLimit] = await Promise.all([
      this.estimateGas({ chainId, to, data, value }),
      chainAdapter.estimateGas(chainId, { to, data, value }).catch(() => '21000'),
    ]);

    const levelData = estimate[gasPriceLevel];
    const estimatedCost = (BigInt(gasLimit) * BigInt(levelData.maxFeePerGas || levelData.gasPrice)).toString();

    return {
      gasLimit,
      gasPrice: levelData.gasPrice,
      maxFeePerGas: levelData.maxFeePerGas,
      maxPriorityFeePerGas: levelData.maxPriorityFeePerGas,
      estimatedCost,
      estimatedTime: levelData.estimatedTime,
    };
  }

  async getGasRecommendation(chainId: ChainId, urgency: 'low' | 'medium' | 'high' = 'medium'): Promise<{
    recommendation: string;
    gasPrice: string;
    maxFeePerGas: string;
    maxPriorityFeePerGas: string;
    estimatedTime: number;
    reasoning: string;
  }> {
    const estimate = await this.estimateGas({ chainId });
    const prediction = await this.getGasPrediction(chainId);

    let level: 'slow' | 'standard' | 'fast';
    let reasoning: string;

    switch (urgency) {
      case 'high':
        level = 'fast';
        reasoning = 'High urgency selected, using fast gas price for quickest confirmation.';
        break;
      case 'low':
        level = 'slow';
        reasoning = 'Low urgency selected, using slow gas price for cost savings.';
        break;
      default:
        if (prediction.trend === 'up') {
          level = 'fast';
          reasoning = 'Gas prices trending upward, recommending fast price to avoid increases.';
        } else if (prediction.trend === 'down') {
          level = 'slow';
          reasoning = 'Gas prices trending downward, recommending slow price for cost savings.';
        } else {
          level = 'standard';
          reasoning = 'Gas prices stable, recommending standard price for balance of speed and cost.';
        }
    }

    const levelData = estimate[level];

    return {
      recommendation: level,
      gasPrice: levelData.gasPrice,
      maxFeePerGas: levelData.maxFeePerGas,
      maxPriorityFeePerGas: levelData.maxPriorityFeePerGas,
      estimatedTime: levelData.estimatedTime,
      reasoning,
    };
  }

  async getHistoricalGasStats(chainId: ChainId): Promise<{
    avgBaseFee: string;
    minBaseFee: string;
    maxBaseFee: string;
    avgGasPrice: string;
    minGasPrice: string;
    maxGasPrice: string;
    avgPriorityFee: string;
    dataPoints: number;
    timeSpan: string;
  }> {
    const history = this.history.get(chainId) || [];
    if (history.length === 0) {
      throw new Error('No gas history available for this chain');
    }

    const baseFees = history.map((h) => parseInt(h.baseFee)).filter((v) => v > 0);
    const gasPrices = history.map((h) => parseInt(h.gasPrice)).filter((v) => v > 0);
    const priorityFees = history.map((h) => parseInt(h.maxPriorityFeePerGas)).filter((v) => v > 0);

    const avgBaseFee = baseFees.length > 0 ? Math.floor(baseFees.reduce((a, b) => a + b, 0) / baseFees.length) : 0;
    const minBaseFee = baseFees.length > 0 ? Math.min(...baseFees) : 0;
    const maxBaseFee = baseFees.length > 0 ? Math.max(...baseFees) : 0;

    const avgGasPrice = gasPrices.length > 0 ? Math.floor(gasPrices.reduce((a, b) => a + b, 0) / gasPrices.length) : 0;
    const minGasPrice = gasPrices.length > 0 ? Math.min(...gasPrices) : 0;
    const maxGasPrice = gasPrices.length > 0 ? Math.max(...gasPrices) : 0;

    const avgPriorityFee = priorityFees.length > 0
      ? Math.floor(priorityFees.reduce((a, b) => a + b, 0) / priorityFees.length)
      : 0;

    const firstEntry = history[0];
    const lastEntry = history[history.length - 1];
    const timeSpan = firstEntry && lastEntry
      ? this.calculateTimeSpan(firstEntry.timestamp, lastEntry.timestamp)
      : '0s';

    return {
      avgBaseFee: avgBaseFee.toString(),
      minBaseFee: minBaseFee.toString(),
      maxBaseFee: maxBaseFee.toString(),
      avgGasPrice: avgGasPrice.toString(),
      minGasPrice: minGasPrice.toString(),
      maxGasPrice: maxGasPrice.toString(),
      avgPriorityFee: avgPriorityFee.toString(),
      dataPoints: history.length,
      timeSpan,
    };
  }

  private calculateTimeSpan(start: string, end: string): string {
    const diff = new Date(end).getTime() - new Date(start).getTime();
    const seconds = Math.floor(diff / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);

    if (hours > 0) return `${hours}h ${minutes % 60}m`;
    if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
    return `${seconds}s`;
  }

  async getGasComparison(chains: ChainId[]): Promise<Array<{
    chainId: ChainId;
    chainName: string;
    baseFee: string;
    standardGasPrice: string;
    estimatedCost: string;
    estimatedTime: number;
  }>> {
    const results = await Promise.all(
      chains.map(async (chainId) => {
        const config = CHAIN_CONFIGS[chainId];
        if (!config) return null;

        try {
          const estimate = await this.estimateGas({ chainId });
          const estimatedCost = (BigInt('21000') * BigInt(estimate.standard.maxFeePerGas || estimate.standard.gasPrice)).toString();

          return {
            chainId,
            chainName: config.name,
            baseFee: estimate.baseFee,
            standardGasPrice: estimate.standard.gasPrice,
            estimatedCost,
            estimatedTime: estimate.standard.estimatedTime,
          };
        } catch (error) {
          this.logger.warn('Failed to get gas estimate for chain', error as Error, { chainId });
          return null;
        }
      })
    );

    return results.filter(Boolean) as Array<{
      chainId: ChainId;
      chainName: string;
      baseFee: string;
      standardGasPrice: string;
      estimatedCost: string;
      estimatedTime: number;
    }>;
  }

  clearHistory(chainId?: ChainId): void {
    if (chainId) {
      this.history.delete(chainId);
      this.logger.info('Gas history cleared', { chainId });
    } else {
      this.history.clear();
      this.logger.info('All gas history cleared');
    }
  }

  getStats(): {
    trackedChains: number;
    totalEntries: number;
    avgEntriesPerChain: number;
  } {
    const chains = Array.from(this.history.keys());
    const totalEntries = chains.reduce((sum, chainId) => sum + (this.history.get(chainId)?.length || 0), 0);

    return {
      trackedChains: chains.length,
      totalEntries,
      avgEntriesPerChain: chains.length > 0 ? Math.floor(totalEntries / chains.length) : 0,
    };
  }
}

export const gasEstimator = new GasEstimator();
