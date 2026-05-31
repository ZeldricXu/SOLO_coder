import { CrossChainMessage, ChainId } from '../types';
export interface LockProof {
    messageId: string;
    lockTransactionHash: string;
    amount: string;
    asset: string;
    sender: string;
    recipient: string;
    sourceChain: number;
    destinationChain: number;
    nonce: number;
    timestamp: string;
    signatures: string[];
}
export interface BridgeConfig {
    sourceChain: ChainId;
    destinationChain: ChainId;
    bridgeContract: string;
    relayers: string[];
    requiredConfirmations: number;
    lockTimeout: number;
}
export declare class CrossChainBridge {
    private messages;
    private bridgeConfigs;
    private lockedAssets;
    private mintedAssets;
    private logger;
    constructor();
    registerBridge(config: BridgeConfig): string;
    getBridge(bridgeId: string): BridgeConfig | undefined;
    listBridges(): Array<{
        id: string;
        config: BridgeConfig;
    }>;
    createCrossChainMessage(params: {
        sourceChain: ChainId;
        destinationChain: ChainId;
        sender: string;
        recipient: string;
        amount: string;
        asset: string;
    }): Promise<CrossChainMessage>;
    private getNextNonce;
    private generateMessageHash;
    lockAssets(messageId: string, bridgeId: string): Promise<CrossChainMessage>;
    addRelayerSignature(messageId: string, signature: string, relayer: string): CrossChainMessage;
    private verifyMessageSignature;
    mintAssets(messageId: string, requiredSignatures?: number): Promise<CrossChainMessage>;
    confirmMessage(messageId: string): Promise<CrossChainMessage>;
    failMessage(messageId: string, reason: string): CrossChainMessage;
    getMessage(messageId: string): CrossChainMessage | undefined;
    listMessages(params?: {
        sourceChain?: ChainId;
        destinationChain?: ChainId;
        status?: CrossChainMessage['status'];
        sender?: string;
        recipient?: string;
    }): CrossChainMessage[];
    getLockProof(messageId: string): LockProof | undefined;
    verifyAtomicity(messageId: string): {
        isAtomic: boolean;
        locked: boolean;
        minted: boolean;
        amountsMatch: boolean;
    };
    getStats(): {
        totalMessages: number;
        pending: number;
        locked: number;
        minted: number;
        confirmed: number;
        failed: number;
        totalLockedAmount: string;
        totalMintedAmount: string;
    };
}
export declare const crossChainBridge: CrossChainBridge;
//# sourceMappingURL=crosschain.d.ts.map