import { ContractEvent, ChainId } from '../types';
export interface EventListenerConfig {
    id: string;
    chainId: ChainId;
    address: string;
    eventName: string;
    abi: Array<Record<string, unknown>>;
    callbackUrl?: string;
    fromBlock: number | 'latest';
    createdAt: string;
    active: boolean;
    processedCount: number;
    lastProcessedBlock?: number;
}
export interface CallbackResult {
    success: boolean;
    error?: string;
    retryCount: number;
}
export declare class ContractEventListener {
    private listeners;
    private providers;
    private wsConnections;
    private processingQueue;
    private logger;
    constructor();
    private getProvider;
    createListener(params: {
        chainId: ChainId;
        address: string;
        eventName: string;
        abi: Array<Record<string, unknown>>;
        callbackUrl?: string;
        fromBlock?: number | 'latest';
    }): Promise<EventListenerConfig>;
    startListener(listenerId: string): Promise<EventListenerConfig>;
    private startListening;
    private setupWebSocketListener;
    private processLog;
    private processEvent;
    stopListener(listenerId: string): EventListenerConfig;
    getListener(listenerId: string): EventListenerConfig | undefined;
    listListeners(chainId?: ChainId, active?: boolean): EventListenerConfig[];
    getListenerQueue(listenerId: string): ContractEvent[];
    clearListenerQueue(listenerId: string): number;
    deleteListener(listenerId: string): boolean;
    fetchHistoricalEvents(params: {
        chainId: ChainId;
        address: string;
        eventName: string;
        abi: Array<Record<string, unknown>>;
        fromBlock: number;
        toBlock: number | 'latest';
    }): Promise<ContractEvent[]>;
    getStats(): {
        totalListeners: number;
        activeListeners: number;
        totalProcessed: number;
        queueSize: number;
    };
}
export declare const contractEventListener: ContractEventListener;
//# sourceMappingURL=events.d.ts.map