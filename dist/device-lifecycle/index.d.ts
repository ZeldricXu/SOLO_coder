import { DeviceStatus } from '../types';
export interface DeviceInfo {
    deviceId: string;
    name: string;
    type: string;
    manufacturer: string;
    model: string;
    serialNumber: string;
    firmwareVersion: string;
    hardwareVersion: string;
    ipAddress?: string;
    macAddress?: string;
    location?: string;
    tags: Record<string, string>;
    metadata: Record<string, unknown>;
}
export interface Device {
    deviceId: string;
    info: DeviceInfo;
    status: DeviceStatus;
    statusMessage?: string;
    credentials: {
        apiKey?: string;
        certificate?: string;
        lastRotatedAt?: string;
    };
    activationCode?: string;
    activatedAt?: string;
    lastHeartbeatAt?: string;
    heartbeatInterval: number;
    createdAt: string;
    updatedAt: string;
    decommissionedAt?: string;
}
export interface DeviceRegistrationRequest {
    name: string;
    type: string;
    manufacturer: string;
    model: string;
    serialNumber: string;
    firmwareVersion: string;
    hardwareVersion: string;
    ipAddress?: string;
    macAddress?: string;
    location?: string;
    tags?: Record<string, string>;
    metadata?: Record<string, unknown>;
    heartbeatInterval?: number;
}
export interface DeviceAuthenticationResult {
    success: boolean;
    device?: Device;
    token?: string;
    expiresAt?: string;
    error?: string;
}
export interface DeviceHeartbeat {
    deviceId: string;
    timestamp: string;
    status: DeviceStatus;
    metrics?: Record<string, number>;
    errors?: string[];
}
export interface DeviceFilter {
    status?: DeviceStatus[];
    type?: string;
    manufacturer?: string;
    location?: string;
    tags?: Record<string, string>;
    lastHeartbeatStart?: string;
    lastHeartbeatEnd?: string;
}
export interface DeviceLifecycleConfig {
    defaultHeartbeatInterval: number;
    heartbeatTimeoutMs: number;
    activationCodeExpiryMs: number;
    maxDevices: number;
}
export declare class DeviceLifecycleManager {
    private devices;
    private activationCodes;
    private config;
    private heartbeatTimer?;
    private onDeviceStatusChange?;
    private onDeviceRegistered?;
    private onDeviceDecommissioned?;
    constructor(config?: Partial<DeviceLifecycleConfig>);
    setDeviceStatusChangeCallback(callback: (device: Device, oldStatus: DeviceStatus) => void): void;
    setDeviceRegisteredCallback(callback: (device: Device) => void): void;
    setDeviceDecommissionedCallback(callback: (device: Device) => void): void;
    registerDevice(request: DeviceRegistrationRequest): {
        device: Device;
        activationCode: string;
    };
    activateDevice(activationCode: string): Promise<DeviceAuthenticationResult>;
    authenticateDevice(deviceId: string, apiKey: string): Promise<DeviceAuthenticationResult>;
    processHeartbeat(heartbeat: DeviceHeartbeat): Device | null;
    getDevice(deviceId: string): Device | undefined;
    listDevices(filter?: DeviceFilter): Device[];
    updateDevice(deviceId: string, updates: Partial<Omit<DeviceInfo, 'deviceId'>>): Device | null;
    decommissionDevice(deviceId: string, reason?: string): boolean;
    rotateCredentials(deviceId: string): {
        apiKey: string;
    } | null;
    private startHeartbeatMonitor;
    private generateActivationCode;
    private generateApiKey;
    getStats(): {
        total: number;
        active: number;
        inactive: number;
        offline: number;
        error: number;
        decommissioned: number;
    };
    stop(): void;
}
//# sourceMappingURL=index.d.ts.map