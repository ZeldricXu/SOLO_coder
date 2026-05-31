import { z } from 'zod';
import { ValidationError } from './errors';
import { CryptoUtils } from './crypto';

export const validationSchemas = {
  address: z.object({
    chainId: z.number().int().positive(),
    label: z.string().max(255).optional(),
    accountIndex: z.number().int().min(0).default(0),
    addressIndex: z.number().int().min(0).default(0),
  }),

  addressUpdate: z.object({
    label: z.string().max(255).optional(),
    metadata: z.record(z.any()).optional(),
    isActive: z.boolean().optional(),
  }),

  transferRequest: z.object({
    sourceChainId: z.number().int().positive(),
    targetChainId: z.number().int().positive(),
    sourceAddress: z.string().refine(CryptoUtils.isValidAddress, {
      message: 'Invalid source address',
    }),
    targetAddress: z.string().refine(CryptoUtils.isValidAddress, {
      message: 'Invalid target address',
    }),
    amount: z.string().regex(/^\d+(\.\d+)?$/, {
      message: 'Invalid amount format',
    }),
    tokenAddress: z.string().refine(CryptoUtils.isValidAddress, {
      message: 'Invalid token address',
    }).optional(),
  }),

  proposal: z.object({
    walletId: z.string().min(1),
    chainId: z.number().int().positive(),
    type: z.enum(['TRANSFER', 'APPROVE', 'EXECUTE', 'UPDATE_OWNERS', 'CHANGE_THRESHOLD', 'CUSTOM']),
    data: z.object({
      to: z.string().refine(CryptoUtils.isValidAddress),
      value: z.string(),
      data: z.string().optional(),
      operation: z.number().int().min(0).max(1).optional(),
    }),
  }),

  signProposal: z.object({
    proposalId: z.string().min(1),
    signer: z.string().refine(CryptoUtils.isValidAddress),
    signature: z.string().min(1),
  }),

  upload: z.object({
    contentType: z.string().min(1),
    storageNetwork: z.enum(['ipfs', 'arweave', 'arweave-bundlr']),
    pin: z.boolean().default(true),
    metadata: z.record(z.any()).optional(),
  }),

  transaction: z.object({
    chainId: z.number().int().positive(),
    from: z.string().refine(CryptoUtils.isValidAddress),
    to: z.string().refine(CryptoUtils.isValidAddress),
    value: z.string(),
    data: z.string().optional(),
    gasPrice: z.string().optional(),
    gasLimit: z.string().optional(),
    maxPriorityFeePerGas: z.string().optional(),
    maxFeePerGas: z.string().optional(),
    nonce: z.number().int().min(0).optional(),
    multisig: z.object({
      walletId: z.string(),
      threshold: z.number().int().positive(),
      owners: z.array(z.string().refine(CryptoUtils.isValidAddress)),
    }).optional(),
  }),

  gasEstimate: z.object({
    chainId: z.number().int().positive(),
    from: z.string().refine(CryptoUtils.isValidAddress).optional(),
    to: z.string().refine(CryptoUtils.isValidAddress).optional(),
    value: z.string().optional(),
    data: z.string().optional(),
  }),

  blockFilter: z.object({
    chainId: z.number().int().positive(),
    fromBlock: z.string().transform(val => BigInt(val)).optional(),
    toBlock: z.string().transform(val => BigInt(val)).optional(),
    fromAddress: z.string().refine(CryptoUtils.isValidAddress).optional(),
    toAddress: z.string().refine(CryptoUtils.isValidAddress).optional(),
    contractAddress: z.string().refine(CryptoUtils.isValidAddress).optional(),
  }),

  pagination: z.object({
    page: z.coerce.number().int().min(1).default(1),
    pageSize: z.coerce.number().int().min(1).max(100).default(20),
  }),
};

export const validate = <T>(schema: z.ZodType<T>, data: any): T => {
  const result = schema.safeParse(data);
  
  if (!result.success) {
    throw new ValidationError('Validation failed', result.error.errors);
  }
  
  return result.data;
};

export const validateAsync = async <T>(schema: z.ZodType<T>, data: any): Promise<T> => {
  try {
    return await schema.parseAsync(data);
  } catch (error) {
    if (error instanceof z.ZodError) {
      throw new ValidationError('Validation failed', error.errors);
    }
    throw error;
  }
};

export default {
  schemas: validationSchemas,
  validate,
  validateAsync,
};
