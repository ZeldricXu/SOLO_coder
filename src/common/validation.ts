import { z } from 'zod';

export const addressSchema = z.string().regex(/^0x[a-fA-F0-9]{40}$/, 'Invalid Ethereum address');

export const hexSchema = z.string().regex(/^0x[a-fA-F0-9]*$/, 'Invalid hex string');

export const positiveNumberSchema = z.number().positive();

export const chainIdSchema = z.union([
  z.literal(1),
  z.literal(5),
  z.literal(137),
  z.literal(80001),
  z.literal(42161),
  z.literal(10),
]);

export const createResourceSchema = z.object({
  type: z.string().min(1),
  config: z.record(z.unknown()),
  labels: z.record(z.string()).optional(),
});

export const batchOperationSchema = z.object({
  operations: z.array(
    z.object({
      action: z.string().min(1),
      id: z.string().min(1),
      params: z.record(z.unknown()).optional(),
    })
  ),
});

export const multiSigProposalSchema = z.object({
  walletId: z.string().min(1),
  destination: addressSchema,
  value: z.string().min(1),
  data: hexSchema.optional(),
  requiredSignatures: z.number().int().positive(),
  signers: z.array(addressSchema).min(1),
  description: z.string().optional(),
});

export const signatureSchema = z.object({
  proposalId: z.string().min(1),
  signature: hexSchema,
  signer: addressSchema,
});

export const zkProofSchema = z.object({
  proof: z.record(z.unknown()),
  publicSignals: z.array(z.string()),
  circuitId: z.string().min(1),
  verificationKey: z.string().min(1),
});

export const eventListenerSchema = z.object({
  chainId: chainIdSchema,
  address: addressSchema,
  eventName: z.string().min(1),
  abi: z.array(z.record(z.unknown())),
  callbackUrl: z.string().url().optional(),
  fromBlock: z.union([z.number().int().min(0), z.literal('latest')]).optional(),
});

export const transactionRequestSchema = z.object({
  chainId: chainIdSchema,
  from: addressSchema,
  to: addressSchema,
  value: z.string().optional(),
  data: hexSchema.optional(),
  gasLimit: z.string().optional(),
  gasPrice: z.string().optional(),
  maxPriorityFeePerGas: z.string().optional(),
  maxFeePerGas: z.string().optional(),
  nonce: z.number().int().min(0).optional(),
});

export const crossChainMessageSchema = z.object({
  sourceChain: chainIdSchema,
  destinationChain: chainIdSchema,
  sender: addressSchema,
  recipient: addressSchema,
  amount: z.string().min(1),
  asset: addressSchema,
});

export const deriveAddressSchema = z.object({
  walletId: z.string().min(1),
  mnemonic: z.string().min(1).optional(),
  path: z.string().optional(),
  index: z.number().int().min(0).optional(),
  count: z.number().int().positive().optional(),
  label: z.string().optional(),
  tags: z.array(z.string()).optional(),
});

export const storageUploadSchema = z.object({
  content: z.union([z.string(), z.instanceof(Uint8Array)]),
  contentType: z.string().default('application/octet-stream'),
  network: z.enum(['ipfs', 'arweave']).default('ipfs'),
  pin: z.boolean().default(true),
});

export const blockIndexSchema = z.object({
  chainId: chainIdSchema,
  fromBlock: z.number().int().min(0),
  toBlock: z.union([z.number().int().min(0), z.literal('latest')]).default('latest'),
  includeTransactions: z.boolean().default(true),
  includeLogs: z.boolean().default(true),
});

export const gasEstimateSchema = z.object({
  chainId: chainIdSchema,
  to: addressSchema.optional(),
  data: hexSchema.optional(),
  value: z.string().optional(),
});

export function validateSchema<T>(schema: z.ZodSchema<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) {
    const errors = result.error.issues.map((issue) => ({
      path: issue.path.join('.'),
      message: issue.message,
    }));
    throw new ValidationError('Validation failed', errors);
  }
  return result.data;
}

export class ValidationError extends Error {
  public details: Array<{ path: string; message: string }>;

  constructor(message: string, details: Array<{ path: string; message: string }>) {
    super(message);
    this.name = 'ValidationError';
    this.details = details;
  }
}
