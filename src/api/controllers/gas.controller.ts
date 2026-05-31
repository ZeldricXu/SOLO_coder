import type { Request, Response, NextFunction } from 'express';
import { z } from 'zod';
import {
  gasEstimateRequestSchema,
  gasPriceResponseSchema,
  gasEstimateResponseSchema,
  gasHistoryRequestSchema,
  gasHistoryResponseSchema,
} from '../schemas/gas';
import type { AppContainer } from '@application/container';
import type { GasEstimate } from '@core/domain/blockchain';
import { errorResponseSchema } from '../schemas/common';
import { BaseError, ValidationError } from '@shared/errors';

export class GasController {
  constructor(private readonly container: AppContainer) {}

  async estimate(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const validated = gasEstimateRequestSchema.parse(req.body);

      const estimate = await this.container.gasEstimator.estimate({
        chainId: validated.chainId,
        from: validated.from,
        to: validated.to,
        value: validated.value,
        data: validated.data as `0x${string}` | undefined,
        speed: validated.speed,
        gasLimit: validated.gasLimit,
      });

      let suggestion;
      if (validated.urgency !== 'high') {
        suggestion = await this.container.gasEstimator.shouldWaitForLowerGas(
          validated.chainId,
          estimate,
          validated.urgency
        );
      }

      const response = this.serializeGasEstimate(estimate, validated.speed, validated.strategy, suggestion);

      res.status(200).json({
        code: 200,
        data: response,
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  async getCurrentPrice(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const chainId = Number(req.params.chainId);

      const prices = await this.container.gasEstimator.getCurrentGasPrice(chainId);
      const history = await this.container.gasEstimator.getHistoricalPrices(chainId, 20);

      const baseFee = prices.standard.maxFeePerGas - prices.standard.maxPriorityFeePerGas;

      const response = {
        slow: {
          maxPriorityFeePerGas: prices.slow.maxPriorityFeePerGas.toString(),
          maxFeePerGas: prices.slow.maxFeePerGas.toString(),
          estimatedConfirmedIn: 300,
        },
        standard: {
          maxPriorityFeePerGas: prices.standard.maxPriorityFeePerGas.toString(),
          maxFeePerGas: prices.standard.maxFeePerGas.toString(),
          estimatedConfirmedIn: 120,
        },
        fast: {
          maxPriorityFeePerGas: prices.fast.maxPriorityFeePerGas.toString(),
          maxFeePerGas: prices.fast.maxFeePerGas.toString(),
          estimatedConfirmedIn: 30,
        },
        instant: {
          maxPriorityFeePerGas: prices.instant.maxPriorityFeePerGas.toString(),
          maxFeePerGas: prices.instant.maxFeePerGas.toString(),
          estimatedConfirmedIn: 15,
        },
        baseFeePerGas: baseFee.toString(),
        timestamp: new Date().toISOString(),
      };

      res.status(200).json({
        code: 200,
        data: gasPriceResponseSchema.parse(response),
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  async getHistory(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const validated = gasHistoryRequestSchema.parse({
        chainId: req.params.chainId,
        ...req.query,
      });

      const history = await this.container.gasEstimator.getHistoricalPrices(
        validated.chainId,
        validated.limit
      );

      const response = history.map((h) => ({
        chainId: h.chainId,
        timestamp: h.timestamp,
        baseFeePerGas: h.baseFeePerGas.toString(),
        maxPriorityFeePerGas: h.maxPriorityFeePerGas.toString(),
        gasUsedRatio: h.gasUsedRatio,
        blockNumber: h.blockNumber?.toString() || '0',
      }));

      res.status(200).json({
        code: 200,
        data: gasHistoryResponseSchema.parse(response),
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  async optimizeGasLimit(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const { chainId, gasLimit, bufferPercentage } = req.body;

      const optimized = this.container.gasEstimator.suggestGasLimit(
        chainId,
        BigInt(gasLimit),
        bufferPercentage
      );

      res.status(200).json({
        code: 200,
        data: {
          original: gasLimit,
          optimized: optimized.toString(),
          bufferPercentage: bufferPercentage || 10,
        },
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  async calculateCost(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const { gasLimit, maxFeePerGas } = req.body;

      const cost = this.container.gasEstimator.calculateTransactionCost(
        BigInt(gasLimit),
        BigInt(maxFeePerGas)
      );

      const eth = Number(cost) / 1e18;

      res.status(200).json({
        code: 200,
        data: {
          wei: cost.toString(),
          gwei: (Number(cost) / 1e9).toString(),
          eth: eth.toString(),
        },
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  private serializeGasEstimate(
    estimate: GasEstimate,
    speed: string,
    strategy: string,
    suggestion?: { shouldWait: boolean; suggestedDelay?: number; reason?: string }
  ) {
    return {
      gasLimit: estimate.gasLimit.toString(),
      baseFeePerGas: estimate.baseFeePerGas.toString(),
      maxPriorityFeePerGas: estimate.maxPriorityFeePerGas.toString(),
      maxFeePerGas: estimate.maxFeePerGas.toString(),
      estimatedCost: estimate.estimatedCost.toString(),
      confidence: estimate.confidence,
      timestamp: estimate.timestamp,
      speed,
      strategy,
      suggestion,
    };
  }

  private handleError(error: unknown, res: Response, next: NextFunction): void {
    if (error instanceof BaseError) {
      const details = error instanceof ValidationError ? error.details : undefined;
      res.status(error.statusCode || 400).json(
        errorResponseSchema.parse({
          code: error.statusCode || 400,
          error: error.message,
          details,
        })
      );
      return;
    }

    if (error instanceof z.ZodError) {
      res.status(422).json(
        errorResponseSchema.parse({
          code: 422,
          error: 'Validation failed',
          details: error.issues,
        })
      );
      return;
    }

    next(error);
  }
}
