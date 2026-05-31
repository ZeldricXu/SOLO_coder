"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ProtocolAdapterManager = exports.OPCUADriver = exports.MQTTDriver = exports.ModbusDriver = void 0;
const uuid_1 = require("uuid");
const logger_1 = __importDefault(require("../common/logger"));
const events_1 = require("events");
class ModbusDriver {
    constructor() {
        this.name = 'modbus-tcp';
        this.version = '1.0.0';
        this.protocol = 'modbus';
        this.connected = false;
        this.subscriptions = new Map();
    }
    async connect(config) {
        logger_1.default.info({ host: config.host, port: config.port }, '连接Modbus TCP设备');
        await new Promise(resolve => setTimeout(resolve, 500));
        this.connected = true;
        logger_1.default.info('Modbus TCP连接成功');
    }
    async disconnect() {
        this.connected = false;
        for (const sub of this.subscriptions.values()) {
            clearInterval(sub.timer);
        }
        this.subscriptions.clear();
        logger_1.default.info('Modbus TCP断开连接');
    }
    isConnected() {
        return this.connected;
    }
    async read(address) {
        if (!this.connected)
            throw new Error('未连接');
        await new Promise(resolve => setTimeout(resolve, 50));
        return {
            protocol: 'modbus',
            timestamp: new Date().toISOString(),
            rawData: Buffer.from([Math.floor(Math.random() * 255), Math.floor(Math.random() * 255)]),
            metadata: { address, functionCode: 3 }
        };
    }
    async write(address, data) {
        if (!this.connected)
            throw new Error('未连接');
        logger_1.default.debug({ address, data }, 'Modbus写入');
        return true;
    }
    async subscribe(address, callback) {
        const subscriptionId = (0, uuid_1.v4)();
        const timer = setInterval(async () => {
            if (this.connected) {
                const data = await this.read(address);
                callback(data);
            }
        }, 1000);
        this.subscriptions.set(subscriptionId, { address, callback, timer });
        return subscriptionId;
    }
    async unsubscribe(subscriptionId) {
        const sub = this.subscriptions.get(subscriptionId);
        if (sub) {
            clearInterval(sub.timer);
            this.subscriptions.delete(subscriptionId);
            return true;
        }
        return false;
    }
    async normalize(rawData) {
        const value = rawData.rawData.readUInt16BE(0);
        return {
            id: (0, uuid_1.v4)(),
            protocol: 'modbus',
            deviceId: rawData.deviceId,
            timestamp: rawData.timestamp,
            data: {
                rawValue: value,
                scaledValue: value * 0.1,
                unit: '°C'
            },
            tags: {
                address: String(rawData.metadata.address)
            },
            quality: 'good'
        };
    }
}
exports.ModbusDriver = ModbusDriver;
class MQTTDriver {
    constructor() {
        this.name = 'mqtt';
        this.version = '1.0.0';
        this.protocol = 'mqtt';
        this.connected = false;
        this.subscriptions = new Map();
    }
    async connect(config) {
        logger_1.default.info({ broker: config.broker }, '连接MQTT Broker');
        await new Promise(resolve => setTimeout(resolve, 300));
        this.connected = true;
        logger_1.default.info('MQTT连接成功');
    }
    async disconnect() {
        this.connected = false;
        this.subscriptions.clear();
        logger_1.default.info('MQTT断开连接');
    }
    isConnected() {
        return this.connected;
    }
    async read(topic) {
        if (!this.connected)
            throw new Error('未连接');
        return {
            protocol: 'mqtt',
            timestamp: new Date().toISOString(),
            rawData: Buffer.from(JSON.stringify({ value: Math.random() * 100 })),
            metadata: { topic }
        };
    }
    async write(topic, data) {
        if (!this.connected)
            throw new Error('未连接');
        logger_1.default.debug({ topic, data }, 'MQTT发布');
        return true;
    }
    async subscribe(topic, callback) {
        const subscriptionId = (0, uuid_1.v4)();
        this.subscriptions.set(subscriptionId, { topic, callback });
        return subscriptionId;
    }
    async unsubscribe(subscriptionId) {
        return this.subscriptions.delete(subscriptionId);
    }
    async normalize(rawData) {
        let parsedData = {};
        try {
            parsedData = JSON.parse(rawData.rawData.toString());
        }
        catch {
            parsedData = { raw: rawData.rawData.toString() };
        }
        return {
            id: (0, uuid_1.v4)(),
            protocol: 'mqtt',
            deviceId: rawData.deviceId,
            timestamp: rawData.timestamp,
            data: parsedData,
            tags: {
                topic: String(rawData.metadata.topic || '')
            },
            quality: 'good'
        };
    }
}
exports.MQTTDriver = MQTTDriver;
class OPCUADriver {
    constructor() {
        this.name = 'opc-ua';
        this.version = '1.0.0';
        this.protocol = 'opcua';
        this.connected = false;
    }
    async connect(config) {
        logger_1.default.info({ endpoint: config.endpoint }, '连接OPC UA服务器');
        await new Promise(resolve => setTimeout(resolve, 1000));
        this.connected = true;
        logger_1.default.info('OPC UA连接成功');
    }
    async disconnect() {
        this.connected = false;
        logger_1.default.info('OPC UA断开连接');
    }
    isConnected() {
        return this.connected;
    }
    async read(nodeId) {
        if (!this.connected)
            throw new Error('未连接');
        await new Promise(resolve => setTimeout(resolve, 30));
        return {
            protocol: 'opcua',
            timestamp: new Date().toISOString(),
            rawData: Buffer.from(JSON.stringify({
                nodeId,
                value: Math.random() * 50,
                sourceTimestamp: new Date().toISOString()
            })),
            metadata: { nodeId }
        };
    }
    async write(nodeId, data) {
        if (!this.connected)
            throw new Error('未连接');
        logger_1.default.debug({ nodeId, data }, 'OPC UA写入');
        return true;
    }
    async subscribe(nodeId, callback) {
        const subscriptionId = (0, uuid_1.v4)();
        return subscriptionId;
    }
    async unsubscribe(subscriptionId) {
        return true;
    }
    async normalize(rawData) {
        let parsedData = {};
        try {
            parsedData = JSON.parse(rawData.rawData.toString());
        }
        catch {
            parsedData = { raw: rawData.rawData.toString() };
        }
        return {
            id: (0, uuid_1.v4)(),
            protocol: 'opcua',
            deviceId: rawData.deviceId,
            timestamp: rawData.timestamp,
            data: parsedData,
            tags: {
                nodeId: String(rawData.metadata.nodeId || '')
            },
            quality: 'good'
        };
    }
}
exports.OPCUADriver = OPCUADriver;
class ProtocolAdapterManager extends events_1.EventEmitter {
    constructor() {
        super();
        this.drivers = new Map();
        this.adapters = new Map();
        this.forwardingRules = new Map();
        this.reconnectTimers = new Map();
        this.registerBuiltinDrivers();
    }
    registerBuiltinDrivers() {
        this.registerDriver(new ModbusDriver());
        this.registerDriver(new MQTTDriver());
        this.registerDriver(new OPCUADriver());
    }
    registerDriver(driver) {
        this.drivers.set(driver.name, driver);
        logger_1.default.info({ name: driver.name, protocol: driver.protocol }, '注册协议驱动');
    }
    unregisterDriver(driverName) {
        return this.drivers.delete(driverName);
    }
    getDriver(driverName) {
        return this.drivers.get(driverName);
    }
    async createAdapter(adapterId, config) {
        const driver = this.drivers.get(config.driverName);
        if (!driver) {
            throw new Error(`驱动不存在: ${config.driverName}`);
        }
        try {
            await driver.connect(config.connectionConfig);
            this.adapters.set(adapterId, { driver, config });
            logger_1.default.info({ adapterId, driverName: config.driverName }, '创建协议适配器');
            this.emit('adapter:connected', { adapterId, driver: config.driverName });
        }
        catch (error) {
            logger_1.default.error({ adapterId, error }, '适配器连接失败');
            if (config.autoReconnect) {
                this.scheduleReconnect(adapterId, config);
            }
            throw error;
        }
    }
    scheduleReconnect(adapterId, config) {
        let attempts = 0;
        const attemptReconnect = async () => {
            if (attempts >= config.maxReconnectAttempts) {
                logger_1.default.error({ adapterId }, '达到最大重连次数，停止重连');
                return;
            }
            attempts++;
            logger_1.default.info({ adapterId, attempt: attempts }, '尝试重新连接');
            try {
                const driver = this.drivers.get(config.driverName);
                if (driver) {
                    await driver.connect(config.connectionConfig);
                    this.adapters.set(adapterId, { driver, config });
                    logger_1.default.info({ adapterId }, '重连成功');
                    this.emit('adapter:connected', { adapterId });
                    return;
                }
            }
            catch (error) {
                logger_1.default.warn({ adapterId, attempt: attempts, error }, '重连失败');
            }
            this.reconnectTimers.set(adapterId, setTimeout(attemptReconnect, config.reconnectIntervalMs));
        };
        this.reconnectTimers.set(adapterId, setTimeout(attemptReconnect, config.reconnectIntervalMs));
    }
    async removeAdapter(adapterId) {
        const adapter = this.adapters.get(adapterId);
        if (!adapter)
            return false;
        try {
            await adapter.driver.disconnect();
        }
        catch (error) {
            logger_1.default.error({ adapterId, error }, '断开驱动连接失败');
        }
        const reconnectTimer = this.reconnectTimers.get(adapterId);
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            this.reconnectTimers.delete(adapterId);
        }
        this.adapters.delete(adapterId);
        logger_1.default.info({ adapterId }, '移除协议适配器');
        return true;
    }
    getAdapter(adapterId) {
        return this.adapters.get(adapterId)?.driver;
    }
    async readData(adapterId, address) {
        const adapter = this.adapters.get(adapterId);
        if (!adapter) {
            throw new Error(`适配器不存在: ${adapterId}`);
        }
        return adapter.driver.read(address);
    }
    async readAndNormalize(adapterId, address) {
        const rawData = await this.readData(adapterId, address);
        const adapter = this.adapters.get(adapterId);
        const normalized = await adapter.driver.normalize(rawData);
        this.emit('data:normalized', normalized);
        return normalized;
    }
    async writeData(adapterId, address, data) {
        const adapter = this.adapters.get(adapterId);
        if (!adapter) {
            throw new Error(`适配器不存在: ${adapterId}`);
        }
        return adapter.driver.write(address, data);
    }
    async subscribe(adapterId, address, callback) {
        const adapter = this.adapters.get(adapterId);
        if (!adapter) {
            throw new Error(`适配器不存在: ${adapterId}`);
        }
        const normalizedCallback = async (rawData) => {
            try {
                const normalized = await adapter.driver.normalize(rawData);
                this.applyForwardingRules(normalized);
                callback(normalized);
            }
            catch (error) {
                logger_1.default.error({ adapterId, error }, '数据标准化失败');
            }
        };
        return adapter.driver.subscribe(address, normalizedCallback);
    }
    async unsubscribe(adapterId, subscriptionId) {
        const adapter = this.adapters.get(adapterId);
        if (!adapter)
            return false;
        return adapter.driver.unsubscribe(subscriptionId);
    }
    addForwardingRule(rule) {
        const ruleId = (0, uuid_1.v4)();
        const fullRule = { ...rule, ruleId };
        this.forwardingRules.set(ruleId, fullRule);
        logger_1.default.info({ ruleId, sourceProtocol: rule.sourceProtocol }, '添加数据转发规则');
        return fullRule;
    }
    removeForwardingRule(ruleId) {
        return this.forwardingRules.delete(ruleId);
    }
    applyForwardingRules(data) {
        for (const rule of this.forwardingRules.values()) {
            if (!rule.enabled || rule.sourceProtocol !== data.protocol)
                continue;
            if (rule.filter) {
                if (rule.filter.tags) {
                    let match = true;
                    for (const [key, value] of Object.entries(rule.filter.tags)) {
                        if (data.tags[key] !== value) {
                            match = false;
                            break;
                        }
                    }
                    if (!match)
                        continue;
                }
            }
            const transformedData = rule.transform ? rule.transform(data) : data;
            this.emit('data:forward', { ruleId: rule.ruleId, data: transformedData });
            logger_1.default.debug({ ruleId: rule.ruleId, protocol: data.protocol }, '应用转发规则');
        }
    }
    normalizeData(driverName, rawData) {
        const driver = this.drivers.get(driverName);
        if (!driver) {
            throw new Error(`驱动不存在: ${driverName}`);
        }
        return driver.normalize(rawData);
    }
    listDrivers() {
        return Array.from(this.drivers.values()).map(d => ({
            name: d.name,
            protocol: d.protocol,
            version: d.version
        }));
    }
    listAdapters() {
        return Array.from(this.adapters.entries()).map(([id, adapter]) => ({
            adapterId: id,
            driverName: adapter.config.driverName,
            connected: adapter.driver.isConnected()
        }));
    }
    async stop() {
        for (const [adapterId, adapter] of this.adapters) {
            try {
                await adapter.driver.disconnect();
            }
            catch (error) {
                logger_1.default.error({ adapterId, error }, '断开驱动失败');
            }
        }
        for (const timer of this.reconnectTimers.values()) {
            clearTimeout(timer);
        }
        this.adapters.clear();
        this.reconnectTimers.clear();
    }
}
exports.ProtocolAdapterManager = ProtocolAdapterManager;
//# sourceMappingURL=index.js.map