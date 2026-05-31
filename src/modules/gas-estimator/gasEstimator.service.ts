import { PrismaClient, GasHistory } from '@prisma/client';
import { getPrismaClient } from '../../utils/database';
import { ChainAdapterService } from '../chain-adapter/chainAdapter.service';
import { NotFoundError, ValidationError } from '../../utils/errors';
import {
  GasEstimate,
  GasPrice,
  EstimateGasRequest,
} from '../../types';
import { cacheService } from '../../utils/cache';
import { ethers } from 'ethers';

export class GasEstimatorService {
  private prisma: PrismaClient;
  private chainAdapter: ChainAdapterService;
  private readonly CACHE_TTL = 30;
  private readonly HISTORY_BLOCKS = 100;

  constructor() {
    this.prisma = getPrismaClient();
    this.chainAdapter = new ChainAdapterService();
  }

  async getGasEstimate(chainId: number): Promise<GasEstimate> {
    const cacheKey = `gas_estimate:${chainId}`;
    const cached = await cacheService.get<GasEstimate>(cacheKey);

    if (cached) {
      return cached;
    }

    const [currentGasPrice, feeData, historicalData] = await Promise.all([
      this.getCurrentGasPrice(chainId),
      this.chainAdapter.getFeeData(chainId).catch(() => null),
      this.getHistoricalGasData(chainId),
    ]);

    const gasEstimate = this.calculateGasEstimate(
      chainId,
      currentGasPrice,
      feeData,
      historicalData
    );

    await cacheService.set(cacheKey, gasEstimate, this.CACHE_TTL);

    return gasEstimate;
  }

  async estimateTransactionGas(request: EstimateGasRequest): Promise<{
    gasEstimate: GasEstimate;
    estimatedGasLimit: string;
    totalCostLow: string;
    totalCostAverage: string;
    totalCostHigh: string;
  }> {
    const { chainId, from, to, value, data } = request;

    const [gasEstimate, gasLimit] = await Promise.all([
      this.getGasEstimate(chainId),
      this.estimateGasLimit(chainId, from, to, value, data),
    ]);

    const gasLimitBigInt = BigInt(gasLimit);
    const totalCostLow = gasLimitBigInt * BigInt(gasEstimate.low.gasPrice);
    const totalCostAverage = gasLimitBigInt * BigInt(gasEstimate.average.gasPrice);
    const totalCostHigh = gasLimitBigInt * BigInt(gasEstimate.high.gasPrice);

    return {
      gasEstimate,
      estimatedGasLimit: gasLimit,
      totalCostLow: totalCostLow.toString(),
      totalCostAverage: totalCostAverage.toString(),
      totalCostHigh: totalCostHigh.toString(),
    };
  }

  async recordGasPrice(chainId: number, blockNumber: bigint): Promise<void> {
    try {
      const [gasPrice, feeData, blockData] = await Promise.all([
        this.getCurrentGasPrice(chainId),
        this.chainAdapter.getFeeData(chainId),
        this.chainAdapter.getBlock(chainId, Number(blockNumber)),
      ]);

      if (!blockData) {
        return;
      }

      const historicalPrices = await this.getHistoricalGasData(chainId);
      const { low, average, high } = this.calculateGasTiers(
        gasPrice,
        historicalPrices
      );

      await this.prisma.gasHistory.create({
        data: {
          chainId,
          blockNumber,
          timestamp: new Date(),
          lowGasPrice: low.gasPrice,
          averageGasPrice: average.gasPrice,
          highGasPrice: high.gasPrice,
          baseFee: feeData?.maxFeePerGas,
          priorityFee: feeData?.maxPriorityFeePerGas,
          gasUsed: blockData.gasUsed,
          blockGasLimit: blockData.gasLimit,
        },
      });
    } catch (error) {
      console.error('Failed to record gas price:', error);
    }
  }

  async getGasHistory(
    chainId: number,
    fromTime?: Date,
    toTime?: Date,
    limit: number = 100
  ): Promise<any[]> {
    const where: any = { chainId };

    if (fromTime) {
      where.timestamp = { ...where.timestamp, gte: fromTime };
    }

    if (toTime) {
      where.timestamp = { ...where.timestamp, lte: toTime };
    }

    const history = await this.prisma.gasHistory.findMany({
      where,
      take: Math.min(limit, 1000),
      orderBy: { timestamp: 'desc' },
    });

    return history.map(h => ({
      ...h,
      blockNumber: h.blockNumber.toString(),
      gasUsed: h.gasUsed.toString(),
      blockGasLimit: h.blockGasLimit.toString(),
    }));
  }

  async getGasStatistics(chainId: number, hours: number = 24): Promise<{
    chainId: number;
    periodHours: number;
    averageGasPrice: string;
    minGasPrice: string;
    maxGasPrice: string;
    gasPriceTrend: 'up' | 'down' | 'stable';
    volatility: number;
  }> {
    const fromTime = new Date(Date.now() - hours * 60 * 60 * 1000);

    const history = await this.prisma.gasHistory.findMany({
      where: {
        chainId,
        timestamp: { gte: fromTime },
      },
      orderBy: { timestamp: 'asc' },
    });

    if (history.length === 0) {
      const currentEstimate = await this.getGasEstimate(chainId);
      return {
        chainId,
        periodHours: hours,
        averageGasPrice: currentEstimate.average.gasPrice,
        minGasPrice: currentEstimate.low.gasPrice,
        maxGasPrice: currentEstimate.high.gasPrice,
        gasPriceTrend: 'stable',
        volatility: 0,
      };
    }

    const prices = history.map(h => BigInt(h.averageGasPrice));
    const avg = prices.reduce((a, b) => a + b, BigInt(0)) / BigInt(prices.length);
    const min = prices.reduce((a, b) => (a < b ? a : b));
    const max = prices.reduce((a, b) => (a > b ? a : b));

    const first = prices[0];
    const last = prices[prices.length - 1];
    const changePercent = Number((last - first) * BigInt(100) / first);

    let trend: 'up' | 'down' | 'stable' = 'stable';
    if (changePercent > 5) trend = 'up';
    else if (changePercent < -5) trend = 'down';

    const mean = avg;
    const squaredDiffs = prices.map(p => (p - mean) ** BigInt(2));
    const avgSquaredDiff = squaredDiffs.reduce((a, b) => a + b, BigInt(0)) / BigInt(prices.length);
    const volatility = Number(avgSquaredDiff) / Number(mean ** BigInt(2));

    return {
      chainId,
      periodHours: hours,
      averageGasPrice: avg.toString(),
      minGasPrice: min.toString(),
      maxGasPrice: max.toString(),
      gasPriceTrend: trend,
      volatility: Math.sqrt(volatility),
    };
  }

  private async getCurrentGasPrice(chainId: number): Promise<string> {
    try {
      return await this.chainAdapter.getGasPrice(chainId);
    } catch (error) {
      const history = await this.prisma.gasHistory.findFirst({
        where: { chainId },
        orderBy: { timestamp: 'desc' },
      });

      if (history) {
        return history.averageGasPrice;
      }

      const defaultGasPrices: Record<number, string> = {
        1: '30000000000',
        56: '5000000000',
        137: '30000000000',
        42161: '100000000',
        10: '1000000',
      };

      return defaultGasPrices[chainId] || '20000000000';
    }
  }

  private async getHistoricalGasData(chainId: number): Promise<GasHistory[]> {
    return await this.prisma.gasHistory.findMany({
      where: { chainId },
      take: this.HISTORY_BLOCKS,
      orderBy: { timestamp: 'desc' },
    });
  }

  private calculateGasEstimate(
    chainId: number,
    currentGasPrice: string,
    feeData: any,
    historicalData: GasHistory[]
  ): GasEstimate {
    const currentPrice = BigInt(currentGasPrice);
    
    const { low, average, high } = this.calculateGasTiers(
      currentGasPrice,
      historicalData
    );

    return {
      chainId,
      low,
      average,
      high,
      baseFee: feeData?.maxFeePerGas,
      priorityFee: feeData?.maxPriorityFeePerGas,
      timestamp: new Date(),
    };
  }

  private calculateGasTiers(
    currentGasPrice: string,
    historicalData: GasHistory[]
  ): { low: GasPrice; average: GasPrice; high: GasPrice } {
    const currentPrice = BigInt(currentGasPrice);
    
    if (historicalData.length === 0) {
      return {
        low: {
          gasPrice: (currentPrice * BigInt(90) / BigInt(100)).toString(),
          estimatedTime: 120,
          confidence: 0.6,
        },
        average: {
          gasPrice: currentPrice.toString(),
          estimatedTime: 60,
          confidence: 0.85,
        },
        high: {
          gasPrice: (currentPrice * BigInt(120) / BigInt(100)).toString(),
          estimatedTime: 15,
          confidence: 0.95,
        },
      };
    }

    const historicalAvg = historicalData.map(h => BigInt(h.averageGasPrice));
    const minHistorical = historicalAvg.reduce((a, b) => (a < b ? a : b));
    const maxHistorical = historicalAvg.reduce((a, b) => (a > b ? a : b));

    const lowPrice = minHistorical < currentPrice
      ? minHistorical
      : currentPrice * BigInt(90) / BigInt(100);

    const highPrice = maxHistorical > currentPrice
      ? maxHistorical
      : currentPrice * BigInt(120) / BigInt(100);

    return {
      low: {
        gasPrice: lowPrice.toString(),
        estimatedTime: 180,
        confidence: 0.5,
      },
      average: {
        gasPrice: currentPrice.toString(),
        estimatedTime: 60,
        confidence: 0.85,
      },
      high: {
        gasPrice: highPrice.toString(),
        estimatedTime: 15,
        confidence: 0.98,
      },
    };
  }

  private async estimateGasLimit(
    chainId: number,
    from?: string,
    to?: string,
    value?: string,
    data?: string
  ): Promise<string> {
    if (to && data) {
      try {
        return await this.chainAdapter.estimateGas(
          chainId,
          to,
          data,
          value,
          from
        );
      } catch (error) {
        console.warn('Failed to estimate gas from chain, using fallback:', error);
      }
    }

    const baseGasLimit = BigInt(21000);
    let gasLimit = baseGasLimit;

    if (data && data !== '0x') {
      try {
        const dataBytes = Buffer.from(data.slice(2), 'hex');
        for (const byte of dataBytes) {
          gasLimit += byte === 0 ? BigInt(4) : BigInt(16);
        }
      } catch (error) {
        gasLimit += BigInt(64000);
      }
    }

    gasLimit = gasLimit + (gasLimit / BigInt(10));

    return gasLimit.toString();
  }
}

export const gasEstimatorService = new GasEstimatorService();
export default gasEstimatorService;
