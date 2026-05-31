import { EventEmitter } from 'events';
export interface ProtocolData {
    protocol: string;
    deviceId?: string;
    timestamp: string;
    rawData: Buffer;
    metadata: Record<string, unknown>;
}
export interface NormalizedData {
    id: string;
    protocol: string;
    deviceId?: string;
    timestamp: string;
    data: Record<string, unknown>;
    tags: Record<string, string>;
    quality: 'good' | 'bad' | 'uncertain';
}
export interface ProtocolDriver {
    name: string;
    version: string;
    protocol: string;
    connect(config: Record<string, unknown>): Promise<void>;
    disconnect(): Promise<void>;
    isConnected(): boolean;
    read(address: string, options?: Record<string, unknown>): Promise<ProtocolData>;
    write(address: string, data: unknown, options?: Record<string, unknown>): Promise<boolean>;
    subscribe(address: string, callback: (data: ProtocolData) => void, options?: Record<string, unknown>): Promise<string>;
    unsubscribe(subscriptionId: string): Promise<boolean>;
    normalize(rawData: ProtocolData): Promise<NormalizedData>;
}
export interface AdapterConfig {
    driverName: string;
    protocol: string;
    connectionConfig: Record<string, unknown>;
    autoReconnect: boolean;
    reconnectIntervalMs: number;
    maxReconnectAttempts: number;
}
export interface DataForwardingRule {
    ruleId: string;
    sourceProtocol: string;
    targetProtocol?: string;
    filter?: {
        tags?: Record<string, string>;
        dataPattern?: Record<string, unknown>;
    };
    transform?: (data: NormalizedData) => NormalizedData;
    targetEndpoint?: string;
    enabled: boolean;
}
export declare class ModbusDriver implements ProtocolDriver {
    name: string;
    version: string;
    protocol: string;
    private connected;
    private subscriptions;
    connect(config: Record<string, unknown>): Promise<void>;
    disconnect(): Promise<void>;
    isConnected(): boolean;
    read(address: string): Promise<ProtocolData>;
    write(address: string, data: unknown): Promise<boolean>;
    subscribe(address: string, callback: (data: ProtocolData) => void): Promise<string>;
    unsubscribe(subscriptionId: string): Promise<boolean>;
    normalize(rawData: ProtocolData): Promise<NormalizedData>;
}
export declare class MQTTDriver implements ProtocolDriver {
    name: string;
    version: string;
    protocol: string;
    private connected;
    private subscriptions;
    connect(config: Record<string, unknown>): Promise<void>;
    disconnect(): Promise<void>;
    isConnected(): boolean;
    read(topic: string): Promise<ProtocolData>;
    write(topic: string, data: unknown): Promise<boolean>;
    subscribe(topic: string, callback: (data: ProtocolData) => void): Promise<string>;
    unsubscribe(subscriptionId: string): Promise<boolean>;
    normalize(rawData: ProtocolData): Promise<NormalizedData>;
}
export declare class OPCUADriver implements ProtocolDriver {
    name: string;
    version: string;
    protocol: string;
    private connected;
    connect(config: Record<string, unknown>): Promise<void>;
    disconnect(): Promise<void>;
    isConnected(): boolean;
    read(nodeId: string): Promise<ProtocolData>;
    write(nodeId: string, data: unknown): Promise<boolean>;
    subscribe(nodeId: string, callback: (data: ProtocolData) => void): Promise<string>;
    unsubscribe(subscriptionId: string): Promise<boolean>;
    normalize(rawData: ProtocolData): Promise<NormalizedData>;
}
export declare class ProtocolAdapterManager extends EventEmitter {
    private drivers;
    private adapters;
    private forwardingRules;
    private reconnectTimers;
    constructor();
    private registerBuiltinDrivers;
    registerDriver(driver: ProtocolDriver): void;
    unregisterDriver(driverName: string): boolean;
    getDriver(driverName: string): ProtocolDriver | undefined;
    createAdapter(adapterId: string, config: AdapterConfig): Promise<void>;
    private scheduleReconnect;
    removeAdapter(adapterId: string): Promise<boolean>;
    getAdapter(adapterId: string): ProtocolDriver | undefined;
    readData(adapterId: string, address: string): Promise<ProtocolData>;
    readAndNormalize(adapterId: string, address: string): Promise<NormalizedData>;
    writeData(adapterId: string, address: string, data: unknown): Promise<boolean>;
    subscribe(adapterId: string, address: string, callback: (data: NormalizedData) => void): Promise<string>;
    unsubscribe(adapterId: string, subscriptionId: string): Promise<boolean>;
    addForwardingRule(rule: Omit<DataForwardingRule, 'ruleId'>): DataForwardingRule;
    removeForwardingRule(ruleId: string): boolean;
    private applyForwardingRules;
    normalizeData(driverName: string, rawData: ProtocolData): Promise<NormalizedData>;
    listDrivers(): Array<{
        name: string;
        protocol: string;
        version: string;
    }>;
    listAdapters(): Array<{
        adapterId: string;
        driverName: string;
        connected: boolean;
    }>;
    stop(): Promise<void>;
}
//# sourceMappingURL=index.d.ts.map