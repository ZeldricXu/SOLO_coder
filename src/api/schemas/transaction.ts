import { z } from 'zod';
import {
  chainIdSchema,
  addressSchema,
  bigintSchema,
  hexStringSchema,
  hashSchema,
} from './common';

export const buildTransactionRequestSchema = z.object({
  chainId: chainIdSchema,
  from: addressSchema,
  to: addressSchema.optional(),
  value: bigintSchema.default(0),
  data: hexStringSchema.default('0x'),
  nonce: z.number().int().min(0).optional(),
  gasLimit: bigintSchema.optional(),
  maxFeePerGas: bigintSchema.optional(),
  maxPriorityFeePerGas: bigintSchema.optional(),
  gasPrice: bigintSchema.optional(),
  gasOptimization: z
    .object({
      enabled: z.boolean().default(false),
      speed: z.enum(['slow', 'standard', 'fast', 'instant']).default('standard'),
      gasLimitBuffer: z.number().int().min(0).max(100).optional(),
      priorityFeeBoost: z.number().int().min(0).max(100).optional(),
    })
    .default({ enabled: false, speed: 'standard' }),
  type: z.union([z.literal(0), z.literal(1), z.literal(2)]).optional(),
});

export const buildContractCallRequestSchema = z.object({
  chainId: chainIdSchema,
  from: addressSchema,
  to: addressSchema,
  functionName: z.string().min(1),
  args: z.array(z.unknown()).default([]),
  abi: z.array(z.unknown()),
  value: bigintSchema.default(0),
  nonce: z.number().int().min(0).optional(),
  gasLimit: bigintSchema.optional(),
  maxFeePerGas: bigintSchema.optional(),
  maxPriorityFeePerGas: bigintSchema.optional(),
});

export const builtTransactionResponseSchema = z.object({
  transactionHash: hashSchema,
  unsignedData: hexStringSchema,
  transaction: z.object({
    from: addressSchema,
    to: addressSchema.optional(),
    value: z.string(),
    data: hexStringSchema,
    nonce: z.number().int(),
    gasLimit: z.string(),
    gasPrice: z.string().optional(),
    maxFeePerGas: z.string().optional(),
    maxPriorityFeePerGas: z.string().optional(),
    chainId: z.number().int(),
    type: z.number().int(),
    toAddress: addressSchema.optional(),
  }),
  metadata: z.object({
    estimatedGas: z.string(),
    estimatedCost: z.string(),
    gasOptimizationApplied: z.boolean(),
  }),
});

export const attachSignatureRequestSchema = z.object({
  transaction: z.object({
    transactionHash: hashSchema,
    unsignedData: hexStringSchema,
    transaction: z.unknown(),
    metadata: z.unknown(),
  }),
  signature: z.object({
    r: hexStringSchema,
    s: hexStringSchema,
    v: z.union([z.bigint(), z.number().int()]),
  }),
});

export const signedTransactionResponseSchema = builtTransactionResponseSchema.extend({
  signedTransaction: hexStringSchema,
  transaction: builtTransactionResponseSchema.shape.transaction.extend({
    signature: z.object({
      r: hexStringSchema,
      s: hexStringSchema,
      v: z.string(),
    }),
  }),
});

export const submitTransactionRequestSchema = z.object({
  chainId: chainIdSchema,
  signedTransaction: hexStringSchema,
});

export const submitTransactionResponseSchema = z.object({
  transactionHash: hashSchema,
  status: z.enum(['pending', 'success', 'failed']),
  timestamp: z.string(),
});

export const transactionStatusResponseSchema = z.object({
  transactionHash: hashSchema,
  blockNumber: z.string().nullable(),
  blockHash: hashSchema.nullable(),
  status: z.enum(['pending', 'success', 'failed']),
  gasUsed: z.string().nullable(),
  effectiveGasPrice: z.string().nullable(),
  confirmations: z.number().int(),
  timestamp: z.string(),
});
