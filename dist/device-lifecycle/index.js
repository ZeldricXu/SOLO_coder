"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.DeviceLifecycleManager = void 0;
const uuid_1 = require("uuid");
const logger_1 = __importDefault(require("../common/logger"));
const types_1 = require("../types");
class DeviceLifecycleManager {
    constructor(config = {}) {
        this.devices = new Map();
        this.activationCodes = new Map();
        this.config = {
            defaultHeartbeatInterval: config.defaultHeartbeatInterval ?? 30000,
            heartbeatTimeoutMs: config.heartbeatTimeoutMs ?? 90000,
            activationCodeExpiryMs: config.activationCodeExpiryMs ?? 86400000,
            maxDevices: config.maxDevices ?? 10000
        };
        this.startHeartbeatMonitor();
    }
    setDeviceStatusChangeCallback(callback) {
        this.onDeviceStatusChange = callback;
    }
    setDeviceRegisteredCallback(callback) {
        this.onDeviceRegistered = callback;
    }
    setDeviceDecommissionedCallback(callback) {
        this.onDeviceDecommissioned = callback;
    }
    registerDevice(request) {
        if (this.devices.size >= this.config.maxDevices) {
            throw new Error('已达到最大设备数量限制');
        }
        const deviceId = (0, uuid_1.v4)();
        const activationCode = this.generateActivationCode();
        const device = {
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
            status: types_1.DeviceStatus.INACTIVE,
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
        logger_1.default.info({ deviceId, name: request.name, type: request.type }, '设备注册成功');
        this.onDeviceRegistered?.(device);
        return { device, activationCode };
    }
    async activateDevice(activationCode) {
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
        device.status = types_1.DeviceStatus.ACTIVE;
        device.activatedAt = new Date().toISOString();
        device.lastHeartbeatAt = new Date().toISOString();
        device.credentials.apiKey = this.generateApiKey();
        device.credentials.lastRotatedAt = new Date().toISOString();
        device.updatedAt = new Date().toISOString();
        this.activationCodes.delete(activationCode);
        logger_1.default.info({ deviceId: device.deviceId }, '设备激活成功');
        this.onDeviceStatusChange?.(device, oldStatus);
        return {
            success: true,
            device,
            token: device.credentials.apiKey,
            expiresAt: new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString()
        };
    }
    async authenticateDevice(deviceId, apiKey) {
        const device = this.devices.get(deviceId);
        if (!device) {
            return { success: false, error: '设备不存在' };
        }
        if (device.status === types_1.DeviceStatus.DECOMMISSIONED) {
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
    processHeartbeat(heartbeat) {
        const device = this.devices.get(heartbeat.deviceId);
        if (!device) {
            logger_1.default.warn({ deviceId: heartbeat.deviceId }, '收到未知设备的心跳');
            return null;
        }
        if (device.status === types_1.DeviceStatus.DECOMMISSIONED) {
            logger_1.default.warn({ deviceId: heartbeat.deviceId }, '已注销设备发送心跳');
            return null;
        }
        const oldStatus = device.status;
        device.lastHeartbeatAt = heartbeat.timestamp;
        device.status = heartbeat.status;
        if (heartbeat.status === types_1.DeviceStatus.ERROR) {
            device.statusMessage = heartbeat.errors?.join('; ');
        }
        else {
            device.statusMessage = undefined;
        }
        device.updatedAt = new Date().toISOString();
        if (oldStatus !== heartbeat.status) {
            logger_1.default.info({ deviceId: device.deviceId, oldStatus, newStatus: heartbeat.status }, '设备状态变更');
            this.onDeviceStatusChange?.(device, oldStatus);
        }
        logger_1.default.debug({ deviceId: device.deviceId }, '设备心跳已处理');
        return device;
    }
    getDevice(deviceId) {
        return this.devices.get(deviceId);
    }
    listDevices(filter = {}) {
        let devices = Array.from(this.devices.values());
        if (filter.status && filter.status.length > 0) {
            devices = devices.filter(d => filter.status.includes(d.status));
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
                for (const [key, value] of Object.entries(filter.tags)) {
                    if (d.info.tags[key] !== value)
                        return false;
                }
                return true;
            });
        }
        if (filter.lastHeartbeatStart) {
            devices = devices.filter(d => d.lastHeartbeatAt && d.lastHeartbeatAt >= filter.lastHeartbeatStart);
        }
        if (filter.lastHeartbeatEnd) {
            devices = devices.filter(d => !d.lastHeartbeatAt || d.lastHeartbeatAt <= filter.lastHeartbeatEnd);
        }
        return devices;
    }
    updateDevice(deviceId, updates) {
        const device = this.devices.get(deviceId);
        if (!device)
            return null;
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
        logger_1.default.info({ deviceId }, '设备信息已更新');
        return device;
    }
    decommissionDevice(deviceId, reason) {
        const device = this.devices.get(deviceId);
        if (!device)
            return false;
        const oldStatus = device.status;
        device.status = types_1.DeviceStatus.DECOMMISSIONED;
        device.statusMessage = reason;
        device.decommissionedAt = new Date().toISOString();
        device.credentials = {};
        device.updatedAt = new Date().toISOString();
        logger_1.default.info({ deviceId, reason }, '设备已注销');
        this.onDeviceStatusChange?.(device, oldStatus);
        this.onDeviceDecommissioned?.(device);
        return true;
    }
    rotateCredentials(deviceId) {
        const device = this.devices.get(deviceId);
        if (!device || device.status === types_1.DeviceStatus.DECOMMISSIONED)
            return null;
        device.credentials.apiKey = this.generateApiKey();
        device.credentials.lastRotatedAt = new Date().toISOString();
        device.updatedAt = new Date().toISOString();
        logger_1.default.info({ deviceId }, '设备凭证已轮换');
        return { apiKey: device.credentials.apiKey };
    }
    startHeartbeatMonitor() {
        this.heartbeatTimer = setInterval(() => {
            const now = Date.now();
            for (const device of this.devices.values()) {
                if (device.status === types_1.DeviceStatus.ACTIVE && device.lastHeartbeatAt) {
                    const lastHeartbeat = new Date(device.lastHeartbeatAt).getTime();
                    if (now - lastHeartbeat > this.config.heartbeatTimeoutMs) {
                        const oldStatus = device.status;
                        device.status = types_1.DeviceStatus.OFFLINE;
                        device.statusMessage = '心跳超时';
                        device.updatedAt = new Date().toISOString();
                        logger_1.default.warn({ deviceId: device.deviceId }, '设备离线');
                        this.onDeviceStatusChange?.(device, oldStatus);
                    }
                }
            }
        }, 30000);
    }
    generateActivationCode() {
        return Math.random().toString(36).substring(2, 10).toUpperCase();
    }
    generateApiKey() {
        return 'ak_' + (0, uuid_1.v4)().replace(/-/g, '');
    }
    getStats() {
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
                case types_1.DeviceStatus.ACTIVE:
                    stats.active++;
                    break;
                case types_1.DeviceStatus.INACTIVE:
                    stats.inactive++;
                    break;
                case types_1.DeviceStatus.OFFLINE:
                    stats.offline++;
                    break;
                case types_1.DeviceStatus.ERROR:
                    stats.error++;
                    break;
                case types_1.DeviceStatus.DECOMMISSIONED:
                    stats.decommissioned++;
                    break;
            }
        }
        return stats;
    }
    stop() {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
        }
    }
}
exports.DeviceLifecycleManager = DeviceLifecycleManager;
//# sourceMappingURL=index.js.map