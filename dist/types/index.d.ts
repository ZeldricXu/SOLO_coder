export interface CoreEntity {
    id: string;
    type: string;
    status: string;
    attributes: Record<string, unknown>;
    created_at: string;
    updated_at: string;
}
export interface ConfigDefinition {
    config_id: string;
    namespace: string;
    version: number;
    parameters: Record<string, unknown>;
    enabled: boolean;
    applied_at: string;
}
export interface RunInstance {
    run_id: string;
    entity_id: string;
    phase: string;
    progress: number;
    started_at: string;
    completed_at: string | null;
    error_detail: string | null;
}
export interface MetricsSnapshot {
    snapshot_id: string;
    timestamp: string;
    metrics: {
        throughput: number;
        latency_p99: number;
        error_rate: number;
    };
    dimensions: Record<string, string>;
}
export interface ApiResponse<T = unknown> {
    code: number;
    data?: T;
    message?: string;
}
export interface MultiSigProposal {
    id: string;
    walletId: string;
    transactionData: string;
    destination: string;
    value: string;
    nonce: number;
    requiredSignatures: number;
    currentSignatures: string[];
    signers: string[];
    status: 'pending' | 'approved' | 'executed' | 'rejected';
    createdAt: string;
    executedAt?: string;
    description?: string;
}
export interface ZKProofData {
    proof: Record<string, unknown>;
    publicSignals: string[];
    circuitId: string;
    verificationKey: string;
}
export interface ContractEvent {
    blockNumber: number;
    blockHash: string;
    transactionHash: string;
    logIndex: number;
    address: string;
    eventName: string;
    args: Record<string, unknown>;
    timestamp: number;
}
export interface TransactionRequest {
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
export interface SignedTransaction {
    rawTransaction: string;
    hash: string;
    from: string;
    to: string;
    value: string;
    gasLimit: string;
    nonce: number;
    chainId: number;
}
export interface CrossChainMessage {
    id: string;
    sourceChain: number;
    destinationChain: number;
    sender: string;
    recipient: string;
    amount: string;
    asset: string;
    nonce: number;
    messageHash: string;
    signatures: string[];
    status: 'pending' | 'locked' | 'minted' | 'confirmed' | 'failed';
    createdAt: string;
}
export interface DerivedAddress {
    path: string;
    address: string;
    publicKey: string;
    privateKey?: string;
    chainCode: string;
    index: number;
    label?: string;
    tags: string[];
    createdAt: string;
}
export interface StorageContent {
    cid: string;
    content: Uint8Array | string;
    size: number;
    contentType: string;
    pinned: boolean;
    network: 'ipfs' | 'arweave';
    createdAt: string;
}
export interface IndexedBlock {
    number: number;
    hash: string;
    parentHash: string;
    timestamp: number;
    miner: string;
    difficulty: string;
    totalDifficulty: string;
    gasUsed: string;
    gasLimit: string;
    transactionCount: number;
    transactions: IndexedTransaction[];
    logs: ContractEvent[];
}
export interface IndexedTransaction {
    hash: string;
    blockNumber: number;
    from: string;
    to: string | null;
    value: string;
    gas: string;
    gasPrice: string;
    input: string;
    nonce: number;
    status: number;
}
export interface GasEstimate {
    chainId: number;
    slow: {
        gasPrice: string;
        maxFeePerGas: string;
        maxPriorityFeePerGas: string;
        estimatedTime: number;
    };
    standard: {
        gasPrice: string;
        maxFeePerGas: string;
        maxPriorityFeePerGas: string;
        estimatedTime: number;
    };
    fast: {
        gasPrice: string;
        maxFeePerGas: string;
        maxPriorityFeePerGas: string;
        estimatedTime: number;
    };
    baseFee: string;
    timestamp: string;
}
export type ChainId = 1 | 5 | 137 | 80001 | 42161 | 10;
export interface ChainConfig {
    chainId: ChainId;
    name: string;
    rpcUrl: string;
    wsUrl?: string;
    blockExplorerUrl: string;
    nativeCurrency: {
        name: string;
        symbol: string;
        decimals: number;
    };
}
//# sourceMappingURL=index.d.ts.map