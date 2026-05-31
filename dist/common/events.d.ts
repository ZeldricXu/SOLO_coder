export interface DomainEvent<T = unknown> {
    id: string;
    type: string;
    source: string;
    timestamp: string;
    data: T;
    metadata?: Record<string, unknown>;
}
export declare class EventBus {
    private emitter;
    private logger;
    private listeners;
    constructor();
    on<T = unknown>(event: string, listener: (data: T) => void | Promise<void>): string;
    off(event: string, listenerId: string): boolean;
    emit<T = unknown>(event: string, data: T): boolean;
    emitDomainEvent<T = unknown>(event: DomainEvent<T>): boolean;
    once<T = unknown>(event: string, listener: (data: T) => void | Promise<void>): void;
    removeAllListeners(event?: string): void;
    listenerCount(event: string): number;
    getEvents(): string[];
}
export declare const eventBus: EventBus;
export declare const EVENTS: {
    readonly PROPOSAL_CREATED: "proposal:created";
    readonly PROPOSAL_SIGNED: "proposal:signed";
    readonly PROPOSAL_EXECUTED: "proposal:executed";
    readonly WALLET_CONFIG_UPDATED: "wallet:config_updated";
    readonly PROOF_VERIFIED: "proof:verified";
    readonly PROOF_STRATEGY_CHANGED: "proof:strategy_changed";
    readonly PROOF_STRATEGY_REGISTERED: "proof:strategy_registered";
    readonly PROOF_STRATEGY_UNREGISTERED: "proof:strategy_unregistered";
    readonly CONTRACT_EVENT: "contract:event";
    readonly LISTENER_CREATED: "listener:created";
    readonly LISTENER_STARTED: "listener:started";
    readonly LISTENER_STOPPED: "listener:stopped";
    readonly LISTENER_DELETED: "listener:deleted";
    readonly TRANSACTION_SIGNED: "transaction:signed";
    readonly CROSS_CHAIN_MESSAGE: "crosschain:message";
    readonly ADDRESS_DERIVED: "address:derived";
    readonly STORAGE_PINNED: "storage:pinned";
    readonly BLOCK_INDEXED: "block:indexed";
    readonly GAS_ESTIMATED: "gas:estimated";
    readonly ERROR: "system:error";
    readonly METRICS: "system:metrics";
};
//# sourceMappingURL=events.d.ts.map