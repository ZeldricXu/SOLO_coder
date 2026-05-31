import type { Request, Response, NextFunction } from 'express';
import { z } from 'zod';
import {
  buildTransactionRequestSchema,
  buildContractCallRequestSchema,
  builtTransactionResponseSchema,
  attachSignatureRequestSchema,
  signedTransactionResponseSchema,
  submitTransactionRequestSchema,
  submitTransactionResponseSchema,
  transactionStatusResponseSchema,
} from '../schemas/transaction';
import type { AppContainer } from '@application/container';
import { errorResponseSchema } from '../schemas/common';
import { BaseError, ValidationError } from '@shared/errors';
import type { BuiltTransaction } from '@core/ports/transactionBuilder.port';
import type { Transaction } from '@core/domain/blockchain';
import type { HexString } from '@shared/types';

export class TransactionController {
  constructor(private readonly container: AppContainer) {}

  async build(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const validated = buildTransactionRequestSchema.parse(req.body);

      if (validated.gasOptimization.enabled) {
        this.container.transactionBuilder.setGasOptimizationConfig(validated.gasOptimization);
      }

      let gasParams: Partial<{
        gasLimit: bigint;
        maxFeePerGas: bigint;
        maxPriorityFeePerGas: bigint;
      }> = {};

      if (validated.gasOptimization.enabled && !validated.gasLimit) {
        const estimate = await this.container.gasEstimator.estimate({
          chainId: validated.chainId,
          from: validated.from,
          to: validated.to,
          value: validated.value,
          data: validated.data as `0x${string}` | undefined,
          speed: validated.gasOptimization.speed,
        });
        gasParams = {
          gasLimit: estimate.gasLimit,
          maxFeePerGas: estimate.maxFeePerGas,
          maxPriorityFeePerGas: estimate.maxPriorityFeePerGas,
        };
      }

      const builtTx = await this.container.transactionBuilder.buildTransaction({
        chainId: validated.chainId,
        from: validated.from,
        to: validated.to || '0x0000000000000000000000000000000000000000' as HexString,
        value: validated.value,
        data: validated.data as HexString | undefined,
        nonce: validated.nonce,
        gasLimit: validated.gasLimit || gasParams.gasLimit,
        maxFeePerGas: validated.maxFeePerGas || gasParams.maxFeePerGas,
        maxPriorityFeePerGas: validated.maxPriorityFeePerGas || gasParams.maxPriorityFeePerGas,
        gasPrice: validated.gasPrice,
      });

      if (validated.gasOptimization.enabled && gasParams.gasLimit) {
        const estimate = await this.container.gasEstimator.estimate({
          chainId: validated.chainId,
          speed: validated.gasOptimization.speed,
        });
        const optimized = await this.container.transactionBuilder.applyGasOptimization(builtTx, estimate);
        res.status(200).json({
          code: 200,
          data: this.serializeBuiltTransaction(optimized, true),
        });
        return;
      }

      res.status(200).json({
        code: 200,
        data: this.serializeBuiltTransaction(builtTx, false),
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  async buildContractCall(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const validated = buildContractCallRequestSchema.parse(req.body);

      const builtTx = await this.container.transactionBuilder.buildContractCall(
      validated.chainId,
      validated.from,
      validated.to,
      validated.functionName,
      validated.args,
      validated.abi
    );

      res.status(200).json({
        code: 200,
        data: this.serializeBuiltTransaction(builtTx, false),
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  async attachSignature(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const validated = attachSignatureRequestSchema.parse(req.body);

      const signed = await this.container.transactionBuilder.attachSignature(
        validated.transaction as unknown as BuiltTransaction,
        {
          r: validated.signature.r as `0x${string}`,
          s: validated.signature.s as `0x${string}`,
          v: BigInt(validated.signature.v),
        }
      );

      res.status(200).json({
        code: 200,
        data: this.serializeSignedTransaction(signed),
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  async submit(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const validated = submitTransactionRequestSchema.parse(req.body);

      const chainClient = this.container.chainInteraction.getClient(validated.chainId);
      const hash = await chainClient.sendRawTransaction(
        validated.signedTransaction as `0x${string}`
      );

      const response = submitTransactionResponseSchema.parse({
        transactionHash: hash,
        status: 'pending',
        timestamp: new Date().toISOString(),
      });

      res.status(200).json({
        code: 200,
        data: response,
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  async getStatus(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const { chainId, hash } = req.params;

      const chainClient = this.container.chainInteraction.getClient(Number(chainId));
      const receipt = await chainClient.getTransactionReceipt(hash as `0x${string}`);
      const currentBlock = await chainClient.getBlockNumber();

      if (!receipt) {
        res.status(200).json({
          code: 200,
          data: transactionStatusResponseSchema.parse({
            transactionHash: hash,
            blockNumber: null,
            blockHash: null,
            status: 'pending',
            gasUsed: null,
            effectiveGasPrice: null,
            confirmations: 0,
            timestamp: new Date().toISOString(),
          }),
        });
        return;
      }

      const confirmations = receipt.blockNumber ? Number(currentBlock - receipt.blockNumber + 1n) : 0;

      res.status(200).json({
        code: 200,
        data: transactionStatusResponseSchema.parse({
          transactionHash: receipt.transactionHash,
          blockNumber: receipt.blockNumber?.toString() || null,
          blockHash: receipt.blockHash,
          status: receipt.status,
          gasUsed: receipt.gasUsed.toString(),
          effectiveGasPrice: receipt.effectiveGasPrice?.toString() || null,
          confirmations,
          timestamp: new Date().toISOString(),
        }),
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  async waitForConfirmation(req: Request, res: Response, next: NextFunction): Promise<void> {
    try {
      const { chainId, hash } = req.params;
      const confirmations = Number(req.query.confirmations) || 1;
      const timeout = Number(req.query.timeout) || 60000;

      const chainClient = this.container.chainInteraction.getClient(Number(chainId));

      const receipt = await chainClient.waitForTransaction(
        hash as `0x${string}`,
        confirmations,
        timeout
      );

      if (!receipt) {
        res.status(200).json({
          code: 200,
          data: {
            transactionHash: hash,
            status: 'timeout',
            message: 'Transaction not confirmed within timeout',
          },
        });
        return;
      }

      const currentBlock = await chainClient.getBlockNumber();
      const txConfirmations = receipt.blockNumber ? Number(currentBlock - receipt.blockNumber + 1n) : 0;

      res.status(200).json({
        code: 200,
        data: transactionStatusResponseSchema.parse({
          transactionHash: receipt.transactionHash,
          blockNumber: receipt.blockNumber?.toString() || null,
          blockHash: receipt.blockHash,
          status: receipt.status,
          gasUsed: receipt.gasUsed.toString(),
          effectiveGasPrice: receipt.effectiveGasPrice?.toString() || null,
          confirmations: txConfirmations,
          timestamp: new Date().toISOString(),
        }),
      });
    } catch (error) {
      this.handleError(error, res, next);
    }
  }

  private serializeBuiltTransaction(tx: BuiltTransaction, optimized: boolean): z.infer<typeof builtTransactionResponseSchema> {
    return builtTransactionResponseSchema.parse({
      transactionHash: tx.transactionHash,
      unsignedData: tx.unsignedData,
      transaction: {
        from: tx.transaction.from,
        to: tx.transaction.to,
        value: tx.transaction.value.toString(),
        data: tx.transaction.data,
        nonce: tx.transaction.nonce,
        gasLimit: tx.transaction.gasLimit.toString(),
        gasPrice: tx.transaction.gasPrice?.toString(),
        maxFeePerGas: tx.transaction.maxFeePerGas?.toString(),
        maxPriorityFeePerGas: tx.transaction.maxPriorityFeePerGas?.toString(),
        chainId: tx.transaction.chainId,
        type: tx.transaction.type,
        toAddress: tx.transaction.to,
      },
      metadata: {
        estimatedGas: tx.transaction.gasLimit.toString(),
        estimatedCost: (
          (tx.transaction.maxFeePerGas || tx.transaction.gasPrice || 0n) * tx.transaction.gasLimit
        ).toString(),
        gasOptimizationApplied: optimized,
      },
    });
  }

  private serializeSignedTransaction(
    tx: BuiltTransaction & { signedTransaction: HexString; transaction: Transaction }
  ) {
    return signedTransactionResponseSchema.parse({
      transactionHash: tx.transactionHash,
      unsignedData: tx.unsignedData,
      signedTransaction: tx.signedTransaction,
      transaction: {
        from: tx.transaction.from,
        to: tx.transaction.to,
        value: tx.transaction.value.toString(),
        data: tx.transaction.data,
        nonce: tx.transaction.nonce,
        gasLimit: tx.transaction.gasLimit.toString(),
        gasPrice: tx.transaction.gasPrice?.toString(),
        maxFeePerGas: tx.transaction.maxFeePerGas?.toString(),
        maxPriorityFeePerGas: tx.transaction.maxPriorityFeePerGas?.toString(),
        chainId: tx.transaction.chainId,
        type: tx.transaction.type,
        toAddress: tx.transaction.to,
        signature: tx.transaction.signature
          ? {
              r: tx.transaction.signature.r,
              s: tx.transaction.signature.s,
              v: tx.transaction.signature.v.toString(),
            }
          : undefined,
      },
      metadata: {
        estimatedGas: tx.transaction.gasLimit.toString(),
        estimatedCost: (
          (tx.transaction.maxFeePerGas || tx.transaction.gasPrice || 0n) * tx.transaction.gasLimit
        ).toString(),
        gasOptimizationApplied: false,
      },
    });
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
