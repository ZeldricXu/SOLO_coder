import { TransactionRequest, SignedTransaction, ChainId } from '../types';
export interface MultiSigStrategy {
    id: string;
    name: string;
    requiredSignatures: number;
    signers: string[];
    threshold: number;
}
export interface TransactionConfig {
    chainId: ChainId;
    from: string;
    to: string;
    value?: string;
    data?: string;
    gasLimit?: string;
    gasPrice?: string;
    maxPriorityFeePerGas?: string;
    maxFeePerGas?: string;
    nonce?: number;
}
export interface GasOptimizationConfig {
    maxGasPrice: string;
    priorityFee: string;
    gasLimitMultiplier: number;
    useEIP1559: boolean;
}
export declare class TransactionBuilder {
    private wallets;
    private multiSigStrategies;
    private signedTransactions;
    private pendingTransactions;
    private logger;
    constructor();
    addWallet(privateKey: string, chainId: ChainId): string;
    removeWallet(address: string): boolean;
    getWalletAddress(address: string): string | undefined;
    listWallets(): string[];
    createMultiSigStrategy(params: {
        name: string;
        signers: string[];
        requiredSignatures: number;
    }): MultiSigStrategy;
    getMultiSigStrategy(strategyId: string): MultiSigStrategy | undefined;
    listMultiSigStrategies(): MultiSigStrategy[];
    buildTransaction(request: TransactionRequest, options?: {
        gasOptimization?: Partial<GasOptimizationConfig>;
    }): Promise<{
        transactionId: string;
        transaction: TransactionRequest;
        estimatedGas: string;
        estimatedCost: string;
    }>;
    private estimateGas;
    private getOptimalGasPrice;
    signTransaction(transactionId: string, from: string): Promise<SignedTransaction>;
    signMessage(message: string, from: string): Promise<{
        signature: string;
        address: string;
    }>;
    verifySignature(message: string, signature: string, expectedAddress: string): boolean;
    getSignedTransaction(hash: string): SignedTransaction | undefined;
    listSignedTransactions(chainId?: ChainId, from?: string): SignedTransaction[];
    getPendingTransaction(transactionId: string): TransactionRequest | undefined;
    listPendingTransactions(): Array<{
        id: string;
        transaction: TransactionRequest;
    }>;
    cancelPendingTransaction(transactionId: string): boolean;
    batchBuildTransactions(requests: TransactionRequest[], options?: {
        gasOptimization?: Partial<GasOptimizationConfig>;
    }): Promise<Array<{
        transactionId: string;
        transaction: TransactionRequest;
        estimatedGas: string;
        estimatedCost: string;
    }>>;
    batchSignTransactions(transactionIds: string[], from: string): Promise<SignedTransaction[]>;
    optimizeGasForTransaction(transactionId: string, optimization: Partial<GasOptimizationConfig>): TransactionRequest;
}
export declare const transactionBuilder: TransactionBuilder;
//# sourceMappingURL=transaction.d.ts.map