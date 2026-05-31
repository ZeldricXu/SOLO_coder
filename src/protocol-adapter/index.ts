import { v4 as uuidv4 } from 'uuid';
import logger from '../common/logger';
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

export class ModbusDriver implements ProtocolDriver {
  name = 'modbus-tcp';
  version = '1.0.0';
  protocol = 'modbus';
  private connected: boolean = false;
  private subscriptions: Map<string, { address: string; callback: (data: ProtocolData) => void; timer: NodeJS.Timeout }> = new Map();

  async connect(config: Record<string, unknown>): Promise<void> {
    logger.info({ host: config.host, port: config.port }, '连接Modbus TCP设备');
    await new Promise(resolve => setTimeout(resolve, 500));
    this.connected = true;
    logger.info('Modbus TCP连接成功');
  }

  async disconnect(): Promise<void> {
    this.connected = false;
    for (const sub of this.subscriptions.values()) {
      clearInterval(sub.timer);
    }
    this.subscriptions.clear();
    logger.info('Modbus TCP断开连接');
  }

  isConnected(): boolean {
    return this.connected;
  }

  async read(address: string): Promise<ProtocolData> {
    if (!this.connected) throw new Error('未连接');
    await new Promise(resolve => setTimeout(resolve, 50));
    return {
      protocol: 'modbus',
      timestamp: new Date().toISOString(),
      rawData: Buffer.from([Math.floor(Math.random() * 255), Math.floor(Math.random() * 255)]),
      metadata: { address, functionCode: 3 }
    };
  }

  async write(address: string, data: unknown): Promise<boolean> {
    if (!this.connected) throw new Error('未连接');
    logger.debug({ address, data }, 'Modbus写入');
    return true;
  }

  async subscribe(address: string, callback: (data: ProtocolData) => void): Promise<string> {
    const subscriptionId = uuidv4();
    const timer = setInterval(async () => {
      if (this.connected) {
        const data = await this.read(address);
        callback(data);
      }
    }, 1000);
    this.subscriptions.set(subscriptionId, { address, callback, timer });
    return subscriptionId;
  }

  async unsubscribe(subscriptionId: string): Promise<boolean> {
    const sub = this.subscriptions.get(subscriptionId);
    if (sub) {
      clearInterval(sub.timer);
      this.subscriptions.delete(subscriptionId);
      return true;
    }
    return false;
  }

  async normalize(rawData: ProtocolData): Promise<NormalizedData> {
    const value = rawData.rawData.readUInt16BE(0);
    return {
      id: uuidv4(),
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

export class MQTTDriver implements ProtocolDriver {
  name = 'mqtt';
  version = '1.0.0';
  protocol = 'mqtt';
  private connected: boolean = false;
  private subscriptions: Map<string, { topic: string; callback: (data: ProtocolData) => void }> = new Map();

  async connect(config: Record<string, unknown>): Promise<void> {
    logger.info({ broker: config.broker }, '连接MQTT Broker');
    await new Promise(resolve => setTimeout(resolve, 300));
    this.connected = true;
    logger.info('MQTT连接成功');
  }

  async disconnect(): Promise<void> {
    this.connected = false;
    this.subscriptions.clear();
    logger.info('MQTT断开连接');
  }

  isConnected(): boolean {
    return this.connected;
  }

  async read(topic: string): Promise<ProtocolData> {
    if (!this.connected) throw new Error('未连接');
    return {
      protocol: 'mqtt',
      timestamp: new Date().toISOString(),
      rawData: Buffer.from(JSON.stringify({ value: Math.random() * 100 })),
      metadata: { topic }
    };
  }

  async write(topic: string, data: unknown): Promise<boolean> {
    if (!this.connected) throw new Error('未连接');
    logger.debug({ topic, data }, 'MQTT发布');
    return true;
  }

  async subscribe(topic: string, callback: (data: ProtocolData) => void): Promise<string> {
    const subscriptionId = uuidv4();
    this.subscriptions.set(subscriptionId, { topic, callback });
    return subscriptionId;
  }

  async unsubscribe(subscriptionId: string): Promise<boolean> {
    return this.subscriptions.delete(subscriptionId);
  }

  async normalize(rawData: ProtocolData): Promise<NormalizedData> {
    let parsedData: Record<string, unknown> = {};
    try {
      parsedData = JSON.parse(rawData.rawData.toString());
    } catch {
      parsedData = { raw: rawData.rawData.toString() };
    }
    return {
      id: uuidv4(),
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

export class OPCUADriver implements ProtocolDriver {
  name = 'opc-ua';
  version = '1.0.0';
  protocol = 'opcua';
  private connected: boolean = false;

  async connect(config: Record<string, unknown>): Promise<void> {
    logger.info({ endpoint: config.endpoint }, '连接OPC UA服务器');
    await new Promise(resolve => setTimeout(resolve, 1000));
    this.connected = true;
    logger.info('OPC UA连接成功');
  }

  async disconnect(): Promise<void> {
    this.connected = false;
    logger.info('OPC UA断开连接');
  }

  isConnected(): boolean {
    return this.connected;
  }

  async read(nodeId: string): Promise<ProtocolData> {
    if (!this.connected) throw new Error('未连接');
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

  async write(nodeId: string, data: unknown): Promise<boolean> {
    if (!this.connected) throw new Error('未连接');
    logger.debug({ nodeId, data }, 'OPC UA写入');
    return true;
  }

  async subscribe(nodeId: string, callback: (data: ProtocolData) => void): Promise<string> {
    const subscriptionId = uuidv4();
    return subscriptionId;
  }

  async unsubscribe(subscriptionId: string): Promise<boolean> {
    return true;
  }

  async normalize(rawData: ProtocolData): Promise<NormalizedData> {
    let parsedData: Record<string, unknown> = {};
    try {
      parsedData = JSON.parse(rawData.rawData.toString());
    } catch {
      parsedData = { raw: rawData.rawData.toString() };
    }
    return {
      id: uuidv4(),
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

export class ProtocolAdapterManager extends EventEmitter {
  private drivers: Map<string, ProtocolDriver> = new Map();
  private adapters: Map<string, { driver: ProtocolDriver; config: AdapterConfig }> = new Map();
  private forwardingRules: Map<string, DataForwardingRule> = new Map();
  private reconnectTimers: Map<string, NodeJS.Timeout> = new Map();

  constructor() {
    super();
    this.registerBuiltinDrivers();
  }

  private registerBuiltinDrivers(): void {
    this.registerDriver(new ModbusDriver());
    this.registerDriver(new MQTTDriver());
    this.registerDriver(new OPCUADriver());
  }

  registerDriver(driver: ProtocolDriver): void {
    this.drivers.set(driver.name, driver);
    logger.info({ name: driver.name, protocol: driver.protocol }, '注册协议驱动');
  }

  unregisterDriver(driverName: string): boolean {
    return this.drivers.delete(driverName);
  }

  getDriver(driverName: string): ProtocolDriver | undefined {
    return this.drivers.get(driverName);
  }

  async createAdapter(adapterId: string, config: AdapterConfig): Promise<void> {
    const driver = this.drivers.get(config.driverName);
    if (!driver) {
      throw new Error(`驱动不存在: ${config.driverName}`);
    }

    try {
      await driver.connect(config.connectionConfig);
      this.adapters.set(adapterId, { driver, config });
      logger.info({ adapterId, driverName: config.driverName }, '创建协议适配器');
      this.emit('adapter:connected', { adapterId, driver: config.driverName });
    } catch (error) {
      logger.error({ adapterId, error }, '适配器连接失败');
      if (config.autoReconnect) {
        this.scheduleReconnect(adapterId, config);
      }
      throw error;
    }
  }

  private scheduleReconnect(adapterId: string, config: AdapterConfig): void {
    let attempts = 0;
    const attemptReconnect = async () => {
      if (attempts >= config.maxReconnectAttempts) {
        logger.error({ adapterId }, '达到最大重连次数，停止重连');
        return;
      }

      attempts++;
      logger.info({ adapterId, attempt: attempts }, '尝试重新连接');

      try {
        const driver = this.drivers.get(config.driverName);
        if (driver) {
          await driver.connect(config.connectionConfig);
          this.adapters.set(adapterId, { driver, config });
          logger.info({ adapterId }, '重连成功');
          this.emit('adapter:connected', { adapterId });
          return;
        }
      } catch (error) {
        logger.warn({ adapterId, attempt: attempts, error }, '重连失败');
      }

      this.reconnectTimers.set(adapterId, setTimeout(attemptReconnect, config.reconnectIntervalMs));
    };

    this.reconnectTimers.set(adapterId, setTimeout(attemptReconnect, config.reconnectIntervalMs));
  }

  async removeAdapter(adapterId: string): Promise<boolean> {
    const adapter = this.adapters.get(adapterId);
    if (!adapter) return false;

    try {
      await adapter.driver.disconnect();
    } catch (error) {
      logger.error({ adapterId, error }, '断开驱动连接失败');
    }

    const reconnectTimer = this.reconnectTimers.get(adapterId);
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      this.reconnectTimers.delete(adapterId);
    }

    this.adapters.delete(adapterId);
    logger.info({ adapterId }, '移除协议适配器');
    return true;
  }

  getAdapter(adapterId: string): ProtocolDriver | undefined {
    return this.adapters.get(adapterId)?.driver;
  }

  async readData(adapterId: string, address: string): Promise<ProtocolData> {
    const adapter = this.adapters.get(adapterId);
    if (!adapter) {
      throw new Error(`适配器不存在: ${adapterId}`);
    }
    return adapter.driver.read(address);
  }

  async readAndNormalize(adapterId: string, address: string): Promise<NormalizedData> {
    const rawData = await this.readData(adapterId, address);
    const adapter = this.adapters.get(adapterId)!;
    const normalized = await adapter.driver.normalize(rawData);
    this.emit('data:normalized', normalized);
    return normalized;
  }

  async writeData(adapterId: string, address: string, data: unknown): Promise<boolean> {
    const adapter = this.adapters.get(adapterId);
    if (!adapter) {
      throw new Error(`适配器不存在: ${adapterId}`);
    }
    return adapter.driver.write(address, data);
  }

  async subscribe(adapterId: string, address: string, callback: (data: NormalizedData) => void): Promise<string> {
    const adapter = this.adapters.get(adapterId);
    if (!adapter) {
      throw new Error(`适配器不存在: ${adapterId}`);
    }

    const normalizedCallback = async (rawData: ProtocolData) => {
      try {
        const normalized = await adapter.driver.normalize(rawData);
        this.applyForwardingRules(normalized);
        callback(normalized);
      } catch (error) {
        logger.error({ adapterId, error }, '数据标准化失败');
      }
    };

    return adapter.driver.subscribe(address, normalizedCallback);
  }

  async unsubscribe(adapterId: string, subscriptionId: string): Promise<boolean> {
    const adapter = this.adapters.get(adapterId);
    if (!adapter) return false;
    return adapter.driver.unsubscribe(subscriptionId);
  }

  addForwardingRule(rule: Omit<DataForwardingRule, 'ruleId'>): DataForwardingRule {
    const ruleId = uuidv4();
    const fullRule: DataForwardingRule = { ...rule, ruleId };
    this.forwardingRules.set(ruleId, fullRule);
    logger.info({ ruleId, sourceProtocol: rule.sourceProtocol }, '添加数据转发规则');
    return fullRule;
  }

  removeForwardingRule(ruleId: string): boolean {
    return this.forwardingRules.delete(ruleId);
  }

  private applyForwardingRules(data: NormalizedData): void {
    for (const rule of this.forwardingRules.values()) {
      if (!rule.enabled || rule.sourceProtocol !== data.protocol) continue;

      if (rule.filter) {
        if (rule.filter.tags) {
          let match = true;
          for (const [key, value] of Object.entries(rule.filter.tags)) {
            if (data.tags[key] !== value) {
              match = false;
              break;
            }
          }
          if (!match) continue;
        }
      }

      const transformedData = rule.transform ? rule.transform(data) : data;
      this.emit('data:forward', { ruleId: rule.ruleId, data: transformedData });
      logger.debug({ ruleId: rule.ruleId, protocol: data.protocol }, '应用转发规则');
    }
  }

  normalizeData(driverName: string, rawData: ProtocolData): Promise<NormalizedData> {
    const driver = this.drivers.get(driverName);
    if (!driver) {
      throw new Error(`驱动不存在: ${driverName}`);
    }
    return driver.normalize(rawData);
  }

  listDrivers(): Array<{ name: string; protocol: string; version: string }> {
    return Array.from(this.drivers.values()).map(d => ({
      name: d.name,
      protocol: d.protocol,
      version: d.version
    }));
  }

  listAdapters(): Array<{ adapterId: string; driverName: string; connected: boolean }> {
    return Array.from(this.adapters.entries()).map(([id, adapter]) => ({
      adapterId: id,
      driverName: adapter.config.driverName,
      connected: adapter.driver.isConnected()
    }));
  }

  async stop(): Promise<void> {
    for (const [adapterId, adapter] of this.adapters) {
      try {
        await adapter.driver.disconnect();
      } catch (error) {
        logger.error({ adapterId, error }, '断开驱动失败');
      }
    }

    for (const timer of this.reconnectTimers.values()) {
      clearTimeout(timer);
    }

    this.adapters.clear();
    this.reconnectTimers.clear();
  }
}
