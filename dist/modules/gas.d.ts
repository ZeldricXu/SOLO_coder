import { GasEstimate, ChainId } from '../types';
export interface GasHistoryEntry {
    timestamp: string;
    blockNumber: number;
    baseFee: string;
    gasPrice: string;
    maxPriorityFeePerGas: string;
    maxFeePerGas: string;
    gasUsed: string;
    gasLimit: string;
}
export interface GasPrediction {
    predictedBaseFee: string;
    predictedGasPrice: string;
    confidence: number;
    trend: 'up' | 'down' | 'stable';
}
export declare class GasEstimator {
    private history;
    private logger;
    private defaultPriorityFee;
    constructor();
    estimateGas(params: {
        chainId: ChainId;
        to?: string;
        data?: string;
        value?: string;
    }): Promise<GasEstimate>;
    private collectGasData;
    private predictNextBaseFee;
    private calculateConfidence;
    private estimateConfirmationTime;
    getGasHistory(chainId: ChainId, limit?: number): Promise<GasHistoryEntry[]>;
    getGasPrediction(chainId: ChainId): Promise<GasPrediction>;
    estimateTransactionCost(params: {
        chainId: ChainId;
        to?: string;
        data?: string;
        value?: string;
        gasPriceLevel?: 'slow' | 'standard' | 'fast';
    }): Promise<{
        gasLimit: string;
        gasPrice: string;
        maxFeePerGas: string;
        maxPriorityFeePerGas: string;
        estimatedCost: string;
        estimatedTime: number;
    }>;
    getGasRecommendation(chainId: ChainId, urgency?: 'low' | 'medium' | 'high'): Promise<{
        recommendation: string;
        gasPrice: string;
        maxFeePerGas: string;
        maxPriorityFeePerGas: string;
        estimatedTime: number;
        reasoning: string;
    }>;
    getHistoricalGasStats(chainId: ChainId): Promise<{
        avgBaseFee: string;
        minBaseFee: string;
        maxBaseFee: string;
        avgGasPrice: string;
        minGasPrice: string;
        maxGasPrice: string;
        avgPriorityFee: string;
        dataPoints: number;
        timeSpan: string;
    }>;
    private calculateTimeSpan;
    getGasComparison(chains: ChainId[]): Promise<Array<{
        chainId: ChainId;
        chainName: string;
        baseFee: string;
        standardGasPrice: string;
        estimatedCost: string;
        estimatedTime: number;
    }>>;
    clearHistory(chainId?: ChainId): void;
    getStats(): {
        trackedChains: number;
        totalEntries: number;
        avgEntriesPerChain: number;
    };
}
export declare const gasEstimator: GasEstimator;
//# sourceMappingURL=gas.d.ts.map