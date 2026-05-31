"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ValidationError = exports.gasEstimateSchema = exports.blockIndexSchema = exports.storageUploadSchema = exports.deriveAddressSchema = exports.crossChainMessageSchema = exports.transactionRequestSchema = exports.eventListenerSchema = exports.zkProofSchema = exports.signatureSchema = exports.multiSigProposalSchema = exports.batchOperationSchema = exports.createResourceSchema = exports.chainIdSchema = exports.positiveNumberSchema = exports.hexSchema = exports.addressSchema = void 0;
exports.validateSchema = validateSchema;
const zod_1 = require("zod");
exports.addressSchema = zod_1.z.string().regex(/^0x[a-fA-F0-9]{40}$/, 'Invalid Ethereum address');
exports.hexSchema = zod_1.z.string().regex(/^0x[a-fA-F0-9]*$/, 'Invalid hex string');
exports.positiveNumberSchema = zod_1.z.number().positive();
exports.chainIdSchema = zod_1.z.union([
    zod_1.z.literal(1),
    zod_1.z.literal(5),
    zod_1.z.literal(137),
    zod_1.z.literal(80001),
    zod_1.z.literal(42161),
    zod_1.z.literal(10),
]);
exports.createResourceSchema = zod_1.z.object({
    type: zod_1.z.string().min(1),
    config: zod_1.z.record(zod_1.z.unknown()),
    labels: zod_1.z.record(zod_1.z.string()).optional(),
});
exports.batchOperationSchema = zod_1.z.object({
    operations: zod_1.z.array(zod_1.z.object({
        action: zod_1.z.string().min(1),
        id: zod_1.z.string().min(1),
        params: zod_1.z.record(zod_1.z.unknown()).optional(),
    })),
});
exports.multiSigProposalSchema = zod_1.z.object({
    walletId: zod_1.z.string().min(1),
    destination: exports.addressSchema,
    value: zod_1.z.string().min(1),
    data: exports.hexSchema.optional(),
    requiredSignatures: zod_1.z.number().int().positive(),
    signers: zod_1.z.array(exports.addressSchema).min(1),
    description: zod_1.z.string().optional(),
});
exports.signatureSchema = zod_1.z.object({
    proposalId: zod_1.z.string().min(1),
    signature: exports.hexSchema,
    signer: exports.addressSchema,
});
exports.zkProofSchema = zod_1.z.object({
    proof: zod_1.z.record(zod_1.z.unknown()),
    publicSignals: zod_1.z.array(zod_1.z.string()),
    circuitId: zod_1.z.string().min(1),
    verificationKey: zod_1.z.string().min(1),
});
exports.eventListenerSchema = zod_1.z.object({
    chainId: exports.chainIdSchema,
    address: exports.addressSchema,
    eventName: zod_1.z.string().min(1),
    abi: zod_1.z.array(zod_1.z.record(zod_1.z.unknown())),
    callbackUrl: zod_1.z.string().url().optional(),
    fromBlock: zod_1.z.union([zod_1.z.number().int().min(0), zod_1.z.literal('latest')]).optional(),
});
exports.transactionRequestSchema = zod_1.z.object({
    chainId: exports.chainIdSchema,
    from: exports.addressSchema,
    to: exports.addressSchema,
    value: zod_1.z.string().optional(),
    data: exports.hexSchema.optional(),
    gasLimit: zod_1.z.string().optional(),
    gasPrice: zod_1.z.string().optional(),
    maxPriorityFeePerGas: zod_1.z.string().optional(),
    maxFeePerGas: zod_1.z.string().optional(),
    nonce: zod_1.z.number().int().min(0).optional(),
});
exports.crossChainMessageSchema = zod_1.z.object({
    sourceChain: exports.chainIdSchema,
    destinationChain: exports.chainIdSchema,
    sender: exports.addressSchema,
    recipient: exports.addressSchema,
    amount: zod_1.z.string().min(1),
    asset: exports.addressSchema,
});
exports.deriveAddressSchema = zod_1.z.object({
    walletId: zod_1.z.string().min(1),
    mnemonic: zod_1.z.string().min(1).optional(),
    path: zod_1.z.string().optional(),
    index: zod_1.z.number().int().min(0).optional(),
    count: zod_1.z.number().int().positive().optional(),
    label: zod_1.z.string().optional(),
    tags: zod_1.z.array(zod_1.z.string()).optional(),
});
exports.storageUploadSchema = zod_1.z.object({
    content: zod_1.z.union([zod_1.z.string(), zod_1.z.instanceof(Uint8Array)]),
    contentType: zod_1.z.string().default('application/octet-stream'),
    network: zod_1.z.enum(['ipfs', 'arweave']).default('ipfs'),
    pin: zod_1.z.boolean().default(true),
});
exports.blockIndexSchema = zod_1.z.object({
    chainId: exports.chainIdSchema,
    fromBlock: zod_1.z.number().int().min(0),
    toBlock: zod_1.z.union([zod_1.z.number().int().min(0), zod_1.z.literal('latest')]).default('latest'),
    includeTransactions: zod_1.z.boolean().default(true),
    includeLogs: zod_1.z.boolean().default(true),
});
exports.gasEstimateSchema = zod_1.z.object({
    chainId: exports.chainIdSchema,
    to: exports.addressSchema.optional(),
    data: exports.hexSchema.optional(),
    value: zod_1.z.string().optional(),
});
function validateSchema(schema, data) {
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
class ValidationError extends Error {
    details;
    constructor(message, details) {
        super(message);
        this.name = 'ValidationError';
        this.details = details;
    }
}
exports.ValidationError = ValidationError;
//# sourceMappingURL=validation.js.map