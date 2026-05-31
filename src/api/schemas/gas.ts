import { z } from 'zod';
import { chainIdSchema, addressSchema, bigintSchema, hexStringSchema } from './common';

export const gasEstimateRequestSchema = z.object({
  chainId: chainIdSchema,
  from: addressSchema.optional(),
  to: addressSchema.optional(),
  value: bigintSchema.optional(),
  data: hexStringSchema.optional(),
  speed: z.enum(['slow', 'standard', 'fast', 'instant']).default('standard'),
  urgency: z.enum(['low', 'medium', 'high']).default('medium'),
  strategy: z.enum(['simple', 'history-weighted']).default('history-weighted'),
  gasLimit: bigintSchema.optional(),
});

export const gasPriceResponseSchema = z.object({
  slow: z.object({
    maxPriorityFeePerGas: z.string(),
    maxFeePerGas: z.string(),
    estimatedConfirmedIn: z.number().int(),
  }),
  standard: z.object({
    maxPriorityFeePerGas: z.string(),
    maxFeePerGas: z.string(),
    estimatedConfirmedIn: z.number().int(),
  }),
  fast: z.object({
    maxPriorityFeePerGas: z.string(),
    maxFeePerGas: z.string(),
    estimatedConfirmedIn: z.number().int(),
  }),
  instant: z.object({
    maxPriorityFeePerGas: z.string(),
    maxFeePerGas: z.string(),
    estimatedConfirmedIn: z.number().int(),
  }),
  baseFeePerGas: z.string(),
  timestamp: z.string(),
});

export const gasEstimateResponseSchema = z.object({
  gasLimit: z.string(),
  baseFeePerGas: z.string(),
  maxPriorityFeePerGas: z.string(),
  maxFeePerGas: z.string(),
  estimatedCost: z.string(),
  confidence: z.number(),
  timestamp: z.string(),
  speed: z.string(),
  strategy: z.string(),
  suggestion: z
    .object({
      shouldWait: z.boolean(),
      suggestedDelay: z.number().int().optional(),
      reason: z.string().optional(),
    })
    .optional(),
});

export const gasHistoryRequestSchema = z.object({
  chainId: chainIdSchema,
  fromBlock: bigintSchema.optional(),
  toBlock: bigintSchema.optional(),
  limit: z.number().int().min(1).max(1000).default(100),
});

export const gasHistoryResponseSchema = z.array(
  z.object({
    chainId: z.number().int(),
    timestamp: z.string(),
    baseFeePerGas: z.string(),
    maxPriorityFeePerGas: z.string(),
    gasUsedRatio: z.number(),
    blockNumber: z.string(),
  })
);
