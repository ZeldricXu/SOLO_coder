export interface ChangeEvent {
    dataSourceId: string;
    tableName: string;
    operation: 'INSERT' | 'UPDATE' | 'DELETE';
    pk?: Record<string, any>;
    beforeData?: Record<string, any>;
    afterData?: Record<string, any>;
    timestamp: Date;
}
export declare abstract class BaseChangeDetector {
    protected isRunning: boolean;
    protected listeners: Set<(event: ChangeEvent) => void>;
    abstract start(): Promise<void>;
    abstract stop(): Promise<void>;
    onEvent(listener: (event: ChangeEvent) => void): () => void;
    protected emit(event: ChangeEvent): void;
}
