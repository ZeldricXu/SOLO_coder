import { Contract, JsonRpcProvider, Interface, Log, EventLog } from 'ethers';
import WebSocket from 'ws';
import { ContractEvent, ChainId } from '../types';
import { CHAIN_CONFIGS } from '../config';
import { generateId, now, withRetry, normalizeAddress, hexToNumber, getErrorMessage } from '../common/utils';
import { eventBus, EVENTS, DomainEvent } from '../common/events';
import { LoggerContext } from '../common/logger';

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

export class ContractEventListener {
  private listeners: Map<string, EventListenerConfig>;
  private providers: Map<ChainId, JsonRpcProvider>;
  private wsConnections: Map<ChainId, WebSocket>;
  private processingQueue: Map<string, ContractEvent[]>;
  private logger: LoggerContext;

  constructor() {
    this.listeners = new Map();
    this.providers = new Map();
    this.wsConnections = new Map();
    this.processingQueue = new Map();
    this.logger = new LoggerContext({ module: 'ContractEventListener' });
  }

  private getProvider(chainId: ChainId): JsonRpcProvider {
    if (!this.providers.has(chainId)) {
      const config = CHAIN_CONFIGS[chainId];
      if (!config) {
        throw new Error(`Unsupported chain: ${chainId}`);
      }
      this.providers.set(chainId, new JsonRpcProvider(config.rpcUrl, chainId));
    }
    return this.providers.get(chainId)!;
  }

  async createListener(params: {
    chainId: ChainId;
    address: string;
    eventName: string;
    abi: Array<Record<string, unknown>>;
    callbackUrl?: string;
    fromBlock?: number | 'latest';
  }): Promise<EventListenerConfig> {
    const { chainId, address, eventName, abi, callbackUrl, fromBlock = 'latest' } = params;

    this.logger.info('Creating event listener', { chainId, address, eventName });

    const normalizedAddress = normalizeAddress(address);

    const iface = new Interface(abi);
    try {
      iface.getEvent(eventName);
    } catch (error) {
      throw new Error(`Event ${eventName} not found in ABI`);
    }

    const listenerId = generateId('listener');

    const config: EventListenerConfig = {
      id: listenerId,
      chainId,
      address: normalizedAddress,
      eventName,
      abi,
      callbackUrl,
      fromBlock,
      createdAt: now(),
      active: false,
      processedCount: 0,
    };

    this.listeners.set(listenerId, config);
    this.processingQueue.set(listenerId, []);

    const domainEvent: DomainEvent<EventListenerConfig> = {
      id: generateId('event'),
      type: EVENTS.LISTENER_CREATED,
      source: 'ContractEventListener',
      timestamp: now(),
      data: config,
      metadata: {
        listenerId,
        chainId,
        address: normalizedAddress,
        eventName,
      },
    };

    eventBus.emitDomainEvent(domainEvent);

    this.logger.info('Event listener created', { listenerId, chainId, address, eventName });
    return config;
  }

  async startListener(listenerId: string): Promise<EventListenerConfig> {
    this.logger.info('Starting event listener', { listenerId });

    const listener = this.listeners.get(listenerId);
    if (!listener) {
      throw new Error(`Listener not found: ${listenerId}`);
    }

    if (listener.active) {
      this.logger.warn('Listener already active', { listenerId });
      return listener;
    }

    await this.startListening(listener);
    listener.active = true;

    const domainEvent: DomainEvent<{ listenerId: string; chainId: ChainId }> = {
      id: generateId('event'),
      type: EVENTS.LISTENER_STARTED,
      source: 'ContractEventListener',
      timestamp: now(),
      data: { listenerId, chainId: listener.chainId },
      metadata: {
        listenerId,
        chainId: listener.chainId,
        address: listener.address,
        eventName: listener.eventName,
      },
    };

    eventBus.emitDomainEvent(domainEvent);

    this.logger.info('Event listener started', { listenerId });
    return listener;
  }

  private async startListening(listener: EventListenerConfig): Promise<void> {
    const { chainId, address, eventName: listenerEventName, abi, fromBlock, id: listenerId } = listener;
    const provider = this.getProvider(chainId);
    const iface = new Interface(abi);

    const contract = new Contract(address, abi, provider);
    const eventFragment = iface.getEvent(listenerEventName)!;

    let startBlock = fromBlock;
    if (startBlock === 'latest') {
      startBlock = await provider.getBlockNumber();
    }

    if (listener.lastProcessedBlock) {
      startBlock = listener.lastProcessedBlock + 1;
    }

    this.logger.info('Catching up historical events', {
      listenerId,
      fromBlock: startBlock,
      chainId,
    });

    try {
      const eventTopic = iface.getEvent(eventFragment.name || '')?.topicHash || '';
      const logs = await provider.getLogs({
        address,
        topics: [eventTopic],
        fromBlock: startBlock,
        toBlock: 'latest',
      });

      for (const log of logs) {
        await this.processLog(log, listener, iface);
      }
    } catch (error) {
      this.logger.error('Failed to catch up historical events', error as Error, { listenerId });
    }

    this.logger.info('Setting up real-time event listener', { listenerId });

    const contractEventName = eventFragment.name || '';
    contract.on(contractEventName, (...args) => {
      const eventLog = args[args.length - 1] as EventLog;
      if (eventLog) {
        this.processLog(eventLog, listener, iface).catch((err) => {
          this.logger.error('Failed to process real-time event', err, { listenerId });
        });
      }
    });

    this.setupWebSocketListener(listener).catch((err) => {
      this.logger.error('WebSocket setup failed', err, { listenerId });
    });
  }

  private async setupWebSocketListener(listener: EventListenerConfig): Promise<void> {
    const config = CHAIN_CONFIGS[listener.chainId];
    if (!config.wsUrl) {
      this.logger.debug('No WebSocket URL configured, falling back to polling', { chainId: listener.chainId });
      return;
    }

    if (this.wsConnections.has(listener.chainId)) {
      return;
    }

    const ws = new WebSocket(config.wsUrl);
    this.wsConnections.set(listener.chainId, ws);

    ws.on('open', () => {
      this.logger.info('WebSocket connected', { chainId: listener.chainId });
      ws.send(JSON.stringify({
        jsonrpc: '2.0',
        id: generateId('ws'),
        method: 'eth_subscribe',
        params: ['logs', { address: listener.address }],
      }));
    });

    ws.on('message', async (data: WebSocket.Data) => {
      try {
        const message = JSON.parse(data.toString());
        if (message.method === 'eth_subscription' && message.params?.result) {
          const iface = new Interface(listener.abi);
          await this.processLog(message.params.result, listener, iface);
        }
      } catch (error) {
        this.logger.error('WebSocket message processing failed', error as Error);
      }
    });

    ws.on('error', (error) => {
      this.logger.error('WebSocket error', error, { chainId: listener.chainId });
    });

    ws.on('close', () => {
      this.logger.info('WebSocket disconnected', { chainId: listener.chainId });
      this.wsConnections.delete(listener.chainId);
    });
  }

  private async processLog(
    log: Log,
    listener: EventListenerConfig,
    iface: Interface
  ): Promise<void> {
    try {
      const parsedLog = iface.parseLog(log);
      if (!parsedLog || parsedLog.name !== listener.eventName) {
        return;
      }

      const provider = this.getProvider(listener.chainId);
      const block = await provider.getBlock(log.blockHash);

      const event: ContractEvent = {
        blockNumber: log.blockNumber,
        blockHash: log.blockHash,
        transactionHash: log.transactionHash,
        logIndex: log.index,
        address: normalizeAddress(log.address),
        eventName: parsedLog.name,
        args: parsedLog.args.toObject() as Record<string, unknown>,
        timestamp: block?.timestamp || Math.floor(Date.now() / 1000),
      };

      this.processingQueue.get(listener.id)?.push(event);

      await this.processEvent(event, listener);

      listener.processedCount++;
      listener.lastProcessedBlock = log.blockNumber;

      const domainEvent: DomainEvent<ContractEvent> = {
        id: generateId('event'),
        type: EVENTS.CONTRACT_EVENT,
        source: 'ContractEventListener',
        timestamp: now(),
        data: event,
        metadata: {
          listenerId: listener.id,
          chainId: listener.chainId,
          address: listener.address,
          eventName: listener.eventName,
          blockNumber: event.blockNumber,
          transactionHash: event.transactionHash,
        },
      };

      eventBus.emitDomainEvent(domainEvent);

      this.logger.debug('Event processed', {
        listenerId: listener.id,
        eventName: event.eventName,
        blockNumber: event.blockNumber,
      });
    } catch (error) {
      this.logger.error('Failed to process log', error as Error, {
        listenerId: listener.id,
        transactionHash: log.transactionHash,
      });
    }
  }

  private async processEvent(event: ContractEvent, listener: EventListenerConfig): Promise<CallbackResult> {
    if (!listener.callbackUrl) {
      return { success: true, retryCount: 0 };
    }

    return withRetry(async () => {
      const response = await fetch(listener.callbackUrl!, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          listenerId: listener.id,
          event,
          timestamp: now(),
        }),
      });

      if (!response.ok) {
        throw new Error(`Callback failed with status: ${response.status}`);
      }

      return { success: true, retryCount: 0 };
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying callback', {
          listenerId: listener.id,
          attempt,
          error: getErrorMessage(error),
        });
      },
    }).catch((error) => ({
      success: false,
      error: getErrorMessage(error),
      retryCount: 3,
    }));
  }

  stopListener(listenerId: string): EventListenerConfig {
    this.logger.info('Stopping event listener', { listenerId });

    const listener = this.listeners.get(listenerId);
    if (!listener) {
      throw new Error(`Listener not found: ${listenerId}`);
    }

    listener.active = false;

    const config = CHAIN_CONFIGS[listener.chainId];
    if (config.wsUrl) {
      this.wsConnections.get(listener.chainId)?.close();
      this.wsConnections.delete(listener.chainId);
    }

    const domainEvent: DomainEvent<{ listenerId: string; chainId: ChainId }> = {
      id: generateId('event'),
      type: EVENTS.LISTENER_STOPPED,
      source: 'ContractEventListener',
      timestamp: now(),
      data: { listenerId, chainId: listener.chainId },
      metadata: {
        listenerId,
        chainId: listener.chainId,
        address: listener.address,
        eventName: listener.eventName,
        processedCount: listener.processedCount,
      },
    };

    eventBus.emitDomainEvent(domainEvent);

    this.logger.info('Event listener stopped', { listenerId });
    return listener;
  }

  getListener(listenerId: string): EventListenerConfig | undefined {
    return this.listeners.get(listenerId);
  }

  listListeners(chainId?: ChainId, active?: boolean): EventListenerConfig[] {
    let listeners = Array.from(this.listeners.values());

    if (chainId !== undefined) {
      listeners = listeners.filter((l) => l.chainId === chainId);
    }

    if (active !== undefined) {
      listeners = listeners.filter((l) => l.active === active);
    }

    return listeners.sort((a, b) =>
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );
  }

  getListenerQueue(listenerId: string): ContractEvent[] {
    return this.processingQueue.get(listenerId) || [];
  }

  clearListenerQueue(listenerId: string): number {
    const queue = this.processingQueue.get(listenerId);
    if (!queue) return 0;
    const count = queue.length;
    this.processingQueue.set(listenerId, []);
    return count;
  }

  deleteListener(listenerId: string): boolean {
    this.logger.info('Deleting event listener', { listenerId });

    const listener = this.listeners.get(listenerId);
    if (!listener) {
      return false;
    }

    if (listener.active) {
      this.stopListener(listenerId);
    }

    this.listeners.delete(listenerId);
    this.processingQueue.delete(listenerId);

    const domainEvent: DomainEvent<{ listenerId: string; chainId: ChainId }> = {
      id: generateId('event'),
      type: EVENTS.LISTENER_DELETED,
      source: 'ContractEventListener',
      timestamp: now(),
      data: { listenerId, chainId: listener.chainId },
      metadata: {
        listenerId,
        chainId: listener.chainId,
        address: listener.address,
        eventName: listener.eventName,
      },
    };

    eventBus.emitDomainEvent(domainEvent);

    this.logger.info('Event listener deleted', { listenerId });
    return true;
  }

  async fetchHistoricalEvents(params: {
    chainId: ChainId;
    address: string;
    eventName: string;
    abi: Array<Record<string, unknown>>;
    fromBlock: number;
    toBlock: number | 'latest';
  }): Promise<ContractEvent[]> {
    const { chainId, address, eventName, abi, fromBlock, toBlock } = params;

    this.logger.info('Fetching historical events', {
      chainId,
      address,
      eventName,
      fromBlock,
      toBlock,
    });

    const provider = this.getProvider(chainId);
    const iface = new Interface(abi);
    const eventFragment = iface.getEvent(eventName);

    const eventTopic = eventFragment?.topicHash || '';
    const logs = await provider.getLogs({
      address: normalizeAddress(address),
      topics: [eventTopic],
      fromBlock,
      toBlock,
    });

    const events: ContractEvent[] = [];
    for (const log of logs) {
      try {
        const parsedLog = iface.parseLog(log);
        if (!parsedLog) continue;

        const block = await provider.getBlock(log.blockHash);
        events.push({
          blockNumber: log.blockNumber,
          blockHash: log.blockHash,
          transactionHash: log.transactionHash,
          logIndex: log.index,
          address: normalizeAddress(log.address),
          eventName: parsedLog.name,
          args: parsedLog.args.toObject() as Record<string, unknown>,
          timestamp: block?.timestamp || Math.floor(Date.now() / 1000),
        });
      } catch (error) {
        this.logger.warn('Failed to parse log', error as Error, { transactionHash: log.transactionHash });
      }
    }

    this.logger.info('Historical events fetched', { count: events.length });
    return events;
  }

  getStats(): {
    totalListeners: number;
    activeListeners: number;
    totalProcessed: number;
    queueSize: number;
  } {
    const listeners = Array.from(this.listeners.values());
    const totalProcessed = listeners.reduce((sum, l) => sum + l.processedCount, 0);
    const queueSize = Array.from(this.processingQueue.values()).reduce((sum, q) => sum + q.length, 0);

    return {
      totalListeners: listeners.length,
      activeListeners: listeners.filter((l) => l.active).length,
      totalProcessed,
      queueSize,
    };
  }
}

export const contractEventListener = new ContractEventListener();
