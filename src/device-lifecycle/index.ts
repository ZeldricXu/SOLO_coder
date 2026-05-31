import { v4 as uuidv4 } from 'uuid';
import logger from '../common/logger';
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

export class DeviceLifecycleManager {
  private devices: Map<string, Device> = new Map();
  private activationCodes: Map<string, { deviceId: string; expiresAt: number }> = new Map();
  private config: DeviceLifecycleConfig;
  private heartbeatTimer?: NodeJS.Timeout;
  private onDeviceStatusChange?: (device: Device, oldStatus: DeviceStatus) => void;
  private onDeviceRegistered?: (device: Device) => void;
  private onDeviceDecommissioned?: (device: Device) => void;

  constructor(config: Partial<DeviceLifecycleConfig> = {}) {
    this.config = {
      defaultHeartbeatInterval: config.defaultHeartbeatInterval ?? 30000,
      heartbeatTimeoutMs: config.heartbeatTimeoutMs ?? 90000,
      activationCodeExpiryMs: config.activationCodeExpiryMs ?? 86400000,
      maxDevices: config.maxDevices ?? 10000
    };
    this.startHeartbeatMonitor();
  }

  setDeviceStatusChangeCallback(callback: (device: Device, oldStatus: DeviceStatus) => void): void {
    this.onDeviceStatusChange = callback;
  }

  setDeviceRegisteredCallback(callback: (device: Device) => void): void {
    this.onDeviceRegistered = callback;
  }

  setDeviceDecommissionedCallback(callback: (device: Device) => void): void {
    this.onDeviceDecommissioned = callback;
  }

  registerDevice(request: DeviceRegistrationRequest): { device: Device; activationCode: string } {
    if (this.devices.size >= this.config.maxDevices) {
      throw new Error('已达到最大设备数量限制');
    }

    const deviceId = uuidv4();
    const activationCode = this.generateActivationCode();

    const device: Device = {
      deviceId,
      info: {
        deviceId,
        name: request.name,
        type: request.type,
        manufacturer: request.manufacturer,
        model: request.model,
        serialNumber: request.serialNumber,
        firmwareVersion: request.firmwareVersion,
        hardwareVersion: request.hardwareVersion,
        ipAddress: request.ipAddress,
        macAddress: request.macAddress,
        location: request.location,
        tags: request.tags || {},
        metadata: request.metadata || {}
      },
      status: DeviceStatus.INACTIVE,
      credentials: {},
      activationCode,
      heartbeatInterval: request.heartbeatInterval ?? this.config.defaultHeartbeatInterval,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };

    this.devices.set(deviceId, device);
    this.activationCodes.set(activationCode, {
      deviceId,
      expiresAt: Date.now() + this.config.activationCodeExpiryMs
    });

    logger.info({ deviceId, name: request.name, type: request.type }, '设备注册成功');
    this.onDeviceRegistered?.(device);

    return { device, activationCode };
  }

  async activateDevice(activationCode: string): Promise<DeviceAuthenticationResult> {
    const codeInfo = this.activationCodes.get(activationCode);
    if (!codeInfo) {
      return { success: false, error: '激活码无效' };
    }

    if (Date.now() > codeInfo.expiresAt) {
      this.activationCodes.delete(activationCode);
      return { success: false, error: '激活码已过期' };
    }

    const device = this.devices.get(codeInfo.deviceId);
    if (!device) {
      return { success: false, error: '设备不存在' };
    }

    const oldStatus = device.status;
    device.status = DeviceStatus.ACTIVE;
    device.activatedAt = new Date().toISOString();
    device.lastHeartbeatAt = new Date().toISOString();
    device.credentials.apiKey = this.generateApiKey();
    device.credentials.lastRotatedAt = new Date().toISOString();
    device.updatedAt = new Date().toISOString();

    this.activationCodes.delete(activationCode);
    logger.info({ deviceId: device.deviceId }, '设备激活成功');
    this.onDeviceStatusChange?.(device, oldStatus);

    return {
      success: true,
      device,
      token: device.credentials.apiKey,
      expiresAt: new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString()
    };
  }

  async authenticateDevice(deviceId: string, apiKey: string): Promise<DeviceAuthenticationResult> {
    const device = this.devices.get(deviceId);
    if (!device) {
      return { success: false, error: '设备不存在' };
    }

    if (device.status === DeviceStatus.DECOMMISSIONED) {
      return { success: false, error: '设备已注销' };
    }

    if (device.credentials.apiKey !== apiKey) {
      return { success: false, error: '认证失败' };
    }

    return {
      success: true,
      device,
      token: apiKey
    };
  }

  processHeartbeat(heartbeat: DeviceHeartbeat): Device | null {
    const device = this.devices.get(heartbeat.deviceId);
    if (!device) {
      logger.warn({ deviceId: heartbeat.deviceId }, '收到未知设备的心跳');
      return null;
    }

    if (device.status === DeviceStatus.DECOMMISSIONED) {
      logger.warn({ deviceId: heartbeat.deviceId }, '已注销设备发送心跳');
      return null;
    }

    const oldStatus = device.status;
    device.lastHeartbeatAt = heartbeat.timestamp;
    device.status = heartbeat.status;

    if (heartbeat.status === DeviceStatus.ERROR) {
      device.statusMessage = heartbeat.errors?.join('; ');
    } else {
      device.statusMessage = undefined;
    }

    device.updatedAt = new Date().toISOString();

    if (oldStatus !== heartbeat.status) {
      logger.info({ deviceId: device.deviceId, oldStatus, newStatus: heartbeat.status }, '设备状态变更');
      this.onDeviceStatusChange?.(device, oldStatus);
    }

    logger.debug({ deviceId: device.deviceId }, '设备心跳已处理');
    return device;
  }

  getDevice(deviceId: string): Device | undefined {
    return this.devices.get(deviceId);
  }

  listDevices(filter: DeviceFilter = {}): Device[] {
    let devices = Array.from(this.devices.values());

    if (filter.status && filter.status.length > 0) {
      devices = devices.filter(d => filter.status!.includes(d.status));
    }

    if (filter.type) {
      devices = devices.filter(d => d.info.type === filter.type);
    }

    if (filter.manufacturer) {
      devices = devices.filter(d => d.info.manufacturer === filter.manufacturer);
    }

    if (filter.location) {
      devices = devices.filter(d => d.info.location === filter.location);
    }

    if (filter.tags) {
      devices = devices.filter(d => {
        for (const [key, value] of Object.entries(filter.tags!)) {
          if (d.info.tags[key] !== value) return false;
        }
        return true;
      });
    }

    if (filter.lastHeartbeatStart) {
      devices = devices.filter(d => d.lastHeartbeatAt && d.lastHeartbeatAt >= filter.lastHeartbeatStart!);
    }

    if (filter.lastHeartbeatEnd) {
      devices = devices.filter(d => !d.lastHeartbeatAt || d.lastHeartbeatAt <= filter.lastHeartbeatEnd!);
    }

    return devices;
  }

  updateDevice(deviceId: string, updates: Partial<Omit<DeviceInfo, 'deviceId'>>): Device | null {
    const device = this.devices.get(deviceId);
    if (!device) return null;

    if (updates.tags) {
      device.info.tags = { ...device.info.tags, ...updates.tags };
      delete updates.tags;
    }

    if (updates.metadata) {
      device.info.metadata = { ...device.info.metadata, ...updates.metadata };
      delete updates.metadata;
    }

    Object.assign(device.info, updates);
    device.updatedAt = new Date().toISOString();

    logger.info({ deviceId }, '设备信息已更新');
    return device;
  }

  decommissionDevice(deviceId: string, reason?: string): boolean {
    const device = this.devices.get(deviceId);
    if (!device) return false;

    const oldStatus = device.status;
    device.status = DeviceStatus.DECOMMISSIONED;
    device.statusMessage = reason;
    device.decommissionedAt = new Date().toISOString();
    device.credentials = {};
    device.updatedAt = new Date().toISOString();

    logger.info({ deviceId, reason }, '设备已注销');
    this.onDeviceStatusChange?.(device, oldStatus);
    this.onDeviceDecommissioned?.(device);

    return true;
  }

  rotateCredentials(deviceId: string): { apiKey: string } | null {
    const device = this.devices.get(deviceId);
    if (!device || device.status === DeviceStatus.DECOMMISSIONED) return null;

    device.credentials.apiKey = this.generateApiKey();
    device.credentials.lastRotatedAt = new Date().toISOString();
    device.updatedAt = new Date().toISOString();

    logger.info({ deviceId }, '设备凭证已轮换');
    return { apiKey: device.credentials.apiKey };
  }

  private startHeartbeatMonitor(): void {
    this.heartbeatTimer = setInterval(() => {
      const now = Date.now();
      for (const device of this.devices.values()) {
        if (device.status === DeviceStatus.ACTIVE && device.lastHeartbeatAt) {
          const lastHeartbeat = new Date(device.lastHeartbeatAt).getTime();
          if (now - lastHeartbeat > this.config.heartbeatTimeoutMs) {
            const oldStatus = device.status;
            device.status = DeviceStatus.OFFLINE;
            device.statusMessage = '心跳超时';
            device.updatedAt = new Date().toISOString();
            logger.warn({ deviceId: device.deviceId }, '设备离线');
            this.onDeviceStatusChange?.(device, oldStatus);
          }
        }
      }
    }, 30000);
  }

  private generateActivationCode(): string {
    return Math.random().toString(36).substring(2, 10).toUpperCase();
  }

  private generateApiKey(): string {
    return 'ak_' + uuidv4().replace(/-/g, '');
  }

  getStats(): {
    total: number;
    active: number;
    inactive: number;
    offline: number;
    error: number;
    decommissioned: number;
  } {
    const stats = {
      total: this.devices.size,
      active: 0,
      inactive: 0,
      offline: 0,
      error: 0,
      decommissioned: 0
    };

    for (const device of this.devices.values()) {
      switch (device.status) {
        case DeviceStatus.ACTIVE:
          stats.active++;
          break;
        case DeviceStatus.INACTIVE:
          stats.inactive++;
          break;
        case DeviceStatus.OFFLINE:
          stats.offline++;
          break;
        case DeviceStatus.ERROR:
          stats.error++;
          break;
        case DeviceStatus.DECOMMISSIONED:
          stats.decommissioned++;
          break;
      }
    }

    return stats;
  }

  stop(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
    }
  }
}
