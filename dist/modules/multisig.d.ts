import { MultiSigProposal } from '../types';
export interface WalletConfig {
    walletId: string;
    signers: string[];
    requiredSignatures: number;
    nonce: number;
    version: number;
    updatedAt: string;
}
export interface ConfigUpdateParams {
    walletId: string;
    signers?: string[];
    requiredSignatures?: number;
}
export interface ConfigChangeRecord {
    id: string;
    walletId: string;
    oldConfig: {
        signers: string[];
        requiredSignatures: number;
    };
    newConfig: {
        signers: string[];
        requiredSignatures: number;
    };
    changedAt: string;
    changedBy?: string;
    reason?: string;
}
export declare class MultiSigWalletCoordinator {
    private proposals;
    private wallets;
    private configHistory;
    private logger;
    constructor();
    createWallet(walletId: string, signers: string[], requiredSignatures: number): WalletConfig;
    updateWalletConfig(params: ConfigUpdateParams, changedBy?: string, reason?: string): WalletConfig;
    getWalletConfig(walletId: string): WalletConfig | undefined;
    getConfigHistory(walletId: string): ConfigChangeRecord[];
    private validateSignerConfig;
    private updatePendingProposalsForConfigChange;
    createProposal(params: {
        walletId: string;
        destination: string;
        value: string;
        data?: string;
        description?: string;
    }): MultiSigProposal;
    addSignature(proposalId: string, signature: string, signer: string): MultiSigProposal;
    private verifySignature;
    private getProposalMessage;
    private recoverSigner;
    executeProposal(proposalId: string): Promise<{
        proposal: MultiSigProposal;
        transactionHash: string;
    }>;
    rejectProposal(proposalId: string): MultiSigProposal;
    getProposal(proposalId: string): MultiSigProposal | undefined;
    listProposals(walletId?: string, status?: MultiSigProposal['status']): MultiSigProposal[];
    getWallet(walletId: string): WalletConfig | undefined;
    listWallets(): string[];
    getProposalSigners(proposalId: string): {
        signed: string[];
        pending: string[];
    };
}
export declare const multiSigCoordinator: MultiSigWalletCoordinator;
//# sourceMappingURL=multisig.d.ts.map