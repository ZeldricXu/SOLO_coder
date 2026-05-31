import { z } from 'zod';
export declare const addressSchema: z.ZodString;
export declare const hexSchema: z.ZodString;
export declare const positiveNumberSchema: z.ZodNumber;
export declare const chainIdSchema: z.ZodUnion<[z.ZodLiteral<1>, z.ZodLiteral<5>, z.ZodLiteral<137>, z.ZodLiteral<80001>, z.ZodLiteral<42161>, z.ZodLiteral<10>]>;
export declare const createResourceSchema: z.ZodObject<{
    type: z.ZodString;
    config: z.ZodRecord<z.ZodString, z.ZodUnknown>;
    labels: z.ZodOptional<z.ZodRecord<z.ZodString, z.ZodString>>;
}, "strip", z.ZodTypeAny, {
    type: string;
    config: Record<string, unknown>;
    labels?: Record<string, string> | undefined;
}, {
    type: string;
    config: Record<string, unknown>;
    labels?: Record<string, string> | undefined;
}>;
export declare const batchOperationSchema: z.ZodObject<{
    operations: z.ZodArray<z.ZodObject<{
        action: z.ZodString;
        id: z.ZodString;
        params: z.ZodOptional<z.ZodRecord<z.ZodString, z.ZodUnknown>>;
    }, "strip", z.ZodTypeAny, {
        action: string;
        id: string;
        params?: Record<string, unknown> | undefined;
    }, {
        action: string;
        id: string;
        params?: Record<string, unknown> | undefined;
    }>, "many">;
}, "strip", z.ZodTypeAny, {
    operations: {
        action: string;
        id: string;
        params?: Record<string, unknown> | undefined;
    }[];
}, {
    operations: {
        action: string;
        id: string;
        params?: Record<string, unknown> | undefined;
    }[];
}>;
export declare const multiSigProposalSchema: z.ZodObject<{
    walletId: z.ZodString;
    destination: z.ZodString;
    value: z.ZodString;
    data: z.ZodOptional<z.ZodString>;
    requiredSignatures: z.ZodNumber;
    signers: z.ZodArray<z.ZodString, "many">;
    description: z.ZodOptional<z.ZodString>;
}, "strip", z.ZodTypeAny, {
    value: string;
    walletId: string;
    destination: string;
    requiredSignatures: number;
    signers: string[];
    data?: string | undefined;
    description?: string | undefined;
}, {
    value: string;
    walletId: string;
    destination: string;
    requiredSignatures: number;
    signers: string[];
    data?: string | undefined;
    description?: string | undefined;
}>;
export declare const signatureSchema: z.ZodObject<{
    proposalId: z.ZodString;
    signature: z.ZodString;
    signer: z.ZodString;
}, "strip", z.ZodTypeAny, {
    proposalId: string;
    signature: string;
    signer: string;
}, {
    proposalId: string;
    signature: string;
    signer: string;
}>;
export declare const zkProofSchema: z.ZodObject<{
    proof: z.ZodRecord<z.ZodString, z.ZodUnknown>;
    publicSignals: z.ZodArray<z.ZodString, "many">;
    circuitId: z.ZodString;
    verificationKey: z.ZodString;
}, "strip", z.ZodTypeAny, {
    proof: Record<string, unknown>;
    publicSignals: string[];
    circuitId: string;
    verificationKey: string;
}, {
    proof: Record<string, unknown>;
    publicSignals: string[];
    circuitId: string;
    verificationKey: string;
}>;
export declare const eventListenerSchema: z.ZodObject<{
    chainId: z.ZodUnion<[z.ZodLiteral<1>, z.ZodLiteral<5>, z.ZodLiteral<137>, z.ZodLiteral<80001>, z.ZodLiteral<42161>, z.ZodLiteral<10>]>;
    address: z.ZodString;
    eventName: z.ZodString;
    abi: z.ZodArray<z.ZodRecord<z.ZodString, z.ZodUnknown>, "many">;
    callbackUrl: z.ZodOptional<z.ZodString>;
    fromBlock: z.ZodOptional<z.ZodUnion<[z.ZodNumber, z.ZodLiteral<"latest">]>>;
}, "strip", z.ZodTypeAny, {
    chainId: 1 | 5 | 137 | 80001 | 42161 | 10;
    address: string;
    eventName: string;
    abi: Record<string, unknown>[];
    callbackUrl?: string | undefined;
    fromBlock?: number | "latest" | undefined;
}, {
    chainId: 1 | 5 | 137 | 80001 | 42161 | 10;
    address: string;
    eventName: string;
    abi: Record<string, unknown>[];
    callbackUrl?: string | undefined;
    fromBlock?: number | "latest" | undefined;
}>;
export declare const transactionRequestSchema: z.ZodObject<{
    chainId: z.ZodUnion<[z.ZodLiteral<1>, z.ZodLiteral<5>, z.ZodLiteral<137>, z.ZodLiteral<80001>, z.ZodLiteral<42161>, z.ZodLiteral<10>]>;
    from: z.ZodString;
    to: z.ZodString;
    value: z.ZodOptional<z.ZodString>;
    data: z.ZodOptional<z.ZodString>;
    gasLimit: z.ZodOptional<z.ZodString>;
    gasPrice: z.ZodOptional<z.ZodString>;
    maxPriorityFeePerGas: z.ZodOptional<z.ZodString>;
    maxFeePerGas: z.ZodOptional<z.ZodString>;
    nonce: z.ZodOptional<z.ZodNumber>;
}, "strip", z.ZodTypeAny, {
    chainId: 1 | 5 | 137 | 80001 | 42161 | 10;
    from: string;
    to: string;
    value?: string | undefined;
    data?: string | undefined;
    gasLimit?: string | undefined;
    gasPrice?: string | undefined;
    maxPriorityFeePerGas?: string | undefined;
    maxFeePerGas?: string | undefined;
    nonce?: number | undefined;
}, {
    chainId: 1 | 5 | 137 | 80001 | 42161 | 10;
    from: string;
    to: string;
    value?: string | undefined;
    data?: string | undefined;
    gasLimit?: string | undefined;
    gasPrice?: string | undefined;
    maxPriorityFeePerGas?: string | undefined;
    maxFeePerGas?: string | undefined;
    nonce?: number | undefined;
}>;
export declare const crossChainMessageSchema: z.ZodObject<{
    sourceChain: z.ZodUnion<[z.ZodLiteral<1>, z.ZodLiteral<5>, z.ZodLiteral<137>, z.ZodLiteral<80001>, z.ZodLiteral<42161>, z.ZodLiteral<10>]>;
    destinationChain: z.ZodUnion<[z.ZodLiteral<1>, z.ZodLiteral<5>, z.ZodLiteral<137>, z.ZodLiteral<80001>, z.ZodLiteral<42161>, z.ZodLiteral<10>]>;
    sender: z.ZodString;
    recipient: z.ZodString;
    amount: z.ZodString;
    asset: z.ZodString;
}, "strip", z.ZodTypeAny, {
    sourceChain: 1 | 5 | 137 | 80001 | 42161 | 10;
    destinationChain: 1 | 5 | 137 | 80001 | 42161 | 10;
    sender: string;
    recipient: string;
    amount: string;
    asset: string;
}, {
    sourceChain: 1 | 5 | 137 | 80001 | 42161 | 10;
    destinationChain: 1 | 5 | 137 | 80001 | 42161 | 10;
    sender: string;
    recipient: string;
    amount: string;
    asset: string;
}>;
export declare const deriveAddressSchema: z.ZodObject<{
    walletId: z.ZodString;
    mnemonic: z.ZodOptional<z.ZodString>;
    path: z.ZodOptional<z.ZodString>;
    index: z.ZodOptional<z.ZodNumber>;
    count: z.ZodOptional<z.ZodNumber>;
    label: z.ZodOptional<z.ZodString>;
    tags: z.ZodOptional<z.ZodArray<z.ZodString, "many">>;
}, "strip", z.ZodTypeAny, {
    walletId: string;
    path?: string | undefined;
    mnemonic?: string | undefined;
    index?: number | undefined;
    count?: number | undefined;
    label?: string | undefined;
    tags?: string[] | undefined;
}, {
    walletId: string;
    path?: string | undefined;
    mnemonic?: string | undefined;
    index?: number | undefined;
    count?: number | undefined;
    label?: string | undefined;
    tags?: string[] | undefined;
}>;
export declare const storageUploadSchema: z.ZodObject<{
    content: z.ZodUnion<[z.ZodString, z.ZodType<Uint8Array<ArrayBuffer>, z.ZodTypeDef, Uint8Array<ArrayBuffer>>]>;
    contentType: z.ZodDefault<z.ZodString>;
    network: z.ZodDefault<z.ZodEnum<["ipfs", "arweave"]>>;
    pin: z.ZodDefault<z.ZodBoolean>;
}, "strip", z.ZodTypeAny, {
    content: string | Uint8Array<ArrayBuffer>;
    contentType: string;
    network: "ipfs" | "arweave";
    pin: boolean;
}, {
    content: string | Uint8Array<ArrayBuffer>;
    contentType?: string | undefined;
    network?: "ipfs" | "arweave" | undefined;
    pin?: boolean | undefined;
}>;
export declare const blockIndexSchema: z.ZodObject<{
    chainId: z.ZodUnion<[z.ZodLiteral<1>, z.ZodLiteral<5>, z.ZodLiteral<137>, z.ZodLiteral<80001>, z.ZodLiteral<42161>, z.ZodLiteral<10>]>;
    fromBlock: z.ZodNumber;
    toBlock: z.ZodDefault<z.ZodUnion<[z.ZodNumber, z.ZodLiteral<"latest">]>>;
    includeTransactions: z.ZodDefault<z.ZodBoolean>;
    includeLogs: z.ZodDefault<z.ZodBoolean>;
}, "strip", z.ZodTypeAny, {
    chainId: 1 | 5 | 137 | 80001 | 42161 | 10;
    fromBlock: number;
    toBlock: number | "latest";
    includeTransactions: boolean;
    includeLogs: boolean;
}, {
    chainId: 1 | 5 | 137 | 80001 | 42161 | 10;
    fromBlock: number;
    toBlock?: number | "latest" | undefined;
    includeTransactions?: boolean | undefined;
    includeLogs?: boolean | undefined;
}>;
export declare const gasEstimateSchema: z.ZodObject<{
    chainId: z.ZodUnion<[z.ZodLiteral<1>, z.ZodLiteral<5>, z.ZodLiteral<137>, z.ZodLiteral<80001>, z.ZodLiteral<42161>, z.ZodLiteral<10>]>;
    to: z.ZodOptional<z.ZodString>;
    data: z.ZodOptional<z.ZodString>;
    value: z.ZodOptional<z.ZodString>;
}, "strip", z.ZodTypeAny, {
    chainId: 1 | 5 | 137 | 80001 | 42161 | 10;
    value?: string | undefined;
    data?: string | undefined;
    to?: string | undefined;
}, {
    chainId: 1 | 5 | 137 | 80001 | 42161 | 10;
    value?: string | undefined;
    data?: string | undefined;
    to?: string | undefined;
}>;
export declare function validateSchema<T>(schema: z.ZodSchema<T>, data: unknown): T;
export declare class ValidationError extends Error {
    details: Array<{
        path: string;
        message: string;
    }>;
    constructor(message: string, details: Array<{
        path: string;
        message: string;
    }>);
}
//# sourceMappingURL=validation.d.ts.map