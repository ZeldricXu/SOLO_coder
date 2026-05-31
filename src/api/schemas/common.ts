import { z } from 'zod';
import type { ChainId } from '@shared/types';

export const chainIdSchema = z.union([
  z.number().int().positive(),
  z.string().regex(/^\d+$/).transform(Number),
]) as z.ZodType<ChainId>;

export const addressSchema = z.string().regex(/^0x[a-fA-F0-9]{40}$/);

export const hashSchema = z.string().regex(/^0x[a-fA-F0-9]{64}$/);

export const hexStringSchema = z.string().regex(/^0x[a-fA-F0-9]*$/);

export const bigintSchema = z.union([
  z.bigint(),
  z.number().int().transform(BigInt),
  z.string().regex(/^\d+$/).transform(BigInt),
]);

export const paginationSchema = z.object({
  page: z.number().int().min(1).default(1),
  limit: z.number().int().min(1).max(100).default(20),
});

export const apiResponseSchema = <T extends z.ZodTypeAny>(dataSchema: T) =>
  z.object({
    code: z.number().int(),
    data: dataSchema,
    message: z.string().optional(),
  });

export const errorResponseSchema = z.object({
  code: z.number().int(),
  error: z.string(),
  details: z.unknown().optional(),
  traceId: z.string().optional(),
});
