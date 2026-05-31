"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.contractEventListener = exports.ContractEventListener = void 0;
const ethers_1 = require("ethers");
const ws_1 = __importDefault(require("ws"));
const config_1 = require("../config");
const utils_1 = require("../common/utils");
const events_1 = require("../common/events");
const logger_1 = require("../common/logger");
class ContractEventListener {
    listeners;
    providers;
    wsConnections;
    processingQueue;
    logger;
    constructor() {
        this.listeners = new Map();
        this.providers = new Map();
        this.wsConnections = new Map();
        this.processingQueue = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'ContractEventListener' });
    }
    getProvider(chainId) {
        if (!this.providers.has(chainId)) {
            const config = config_1.CHAIN_CONFIGS[chainId];
            if (!config) {
                throw new Error(`Unsupported chain: ${chainId}`);
            }
            this.providers.set(chainId, new ethers_1.JsonRpcProvider(config.rpcUrl, chainId));
        }
        return this.providers.get(chainId);
    }
    async createListener(params) {
        const { chainId, address, eventName, abi, callbackUrl, fromBlock = 'latest' } = params;
        this.logger.info('Creating event listener', { chainId, address, eventName });
        const normalizedAddress = (0, utils_1.normalizeAddress)(address);
        const iface = new ethers_1.Interface(abi);
        try {
            iface.getEvent(eventName);
        }
        catch (error) {
            throw new Error(`Event ${eventName} not found in ABI`);
        }
        const listenerId = (0, utils_1.generateId)('listener');
        const config = {
            id: listenerId,
            chainId,
            address: normalizedAddress,
            eventName,
            abi,
            callbackUrl,
            fromBlock,
            createdAt: (0, utils_1.now)(),
            active: false,
            processedCount: 0,
        };
        this.listeners.set(listenerId, config);
        this.processingQueue.set(listenerId, []);
        const domainEvent = {
            id: (0, utils_1.generateId)('event'),
            type: events_1.EVENTS.LISTENER_CREATED,
            source: 'ContractEventListener',
            timestamp: (0, utils_1.now)(),
            data: config,
            metadata: {
                listenerId,
                chainId,
                address: normalizedAddress,
                eventName,
            },
        };
        events_1.eventBus.emitDomainEvent(domainEvent);
        this.logger.info('Event listener created', { listenerId, chainId, address, eventName });
        return config;
    }
    async startListener(listenerId) {
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
        const domainEvent = {
            id: (0, utils_1.generateId)('event'),
            type: events_1.EVENTS.LISTENER_STARTED,
            source: 'ContractEventListener',
            timestamp: (0, utils_1.now)(),
            data: { listenerId, chainId: listener.chainId },
            metadata: {
                listenerId,
                chainId: listener.chainId,
                address: listener.address,
                eventName: listener.eventName,
            },
        };
        events_1.eventBus.emitDomainEvent(domainEvent);
        this.logger.info('Event listener started', { listenerId });
        return listener;
    }
    async startListening(listener) {
        const { chainId, address, eventName: listenerEventName, abi, fromBlock, id: listenerId } = listener;
        const provider = this.getProvider(chainId);
        const iface = new ethers_1.Interface(abi);
        const contract = new ethers_1.Contract(address, abi, provider);
        const eventFragment = iface.getEvent(listenerEventName);
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
        }
        catch (error) {
            this.logger.error('Failed to catch up historical events', error, { listenerId });
        }
        this.logger.info('Setting up real-time event listener', { listenerId });
        const contractEventName = eventFragment.name || '';
        contract.on(contractEventName, (...args) => {
            const eventLog = args[args.length - 1];
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
    async setupWebSocketListener(listener) {
        const config = config_1.CHAIN_CONFIGS[listener.chainId];
        if (!config.wsUrl) {
            this.logger.debug('No WebSocket URL configured, falling back to polling', { chainId: listener.chainId });
            return;
        }
        if (this.wsConnections.has(listener.chainId)) {
            return;
        }
        const ws = new ws_1.default(config.wsUrl);
        this.wsConnections.set(listener.chainId, ws);
        ws.on('open', () => {
            this.logger.info('WebSocket connected', { chainId: listener.chainId });
            ws.send(JSON.stringify({
                jsonrpc: '2.0',
                id: (0, utils_1.generateId)('ws'),
                method: 'eth_subscribe',
                params: ['logs', { address: listener.address }],
            }));
        });
        ws.on('message', async (data) => {
            try {
                const message = JSON.parse(data.toString());
                if (message.method === 'eth_subscription' && message.params?.result) {
                    const iface = new ethers_1.Interface(listener.abi);
                    await this.processLog(message.params.result, listener, iface);
                }
            }
            catch (error) {
                this.logger.error('WebSocket message processing failed', error);
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
    async processLog(log, listener, iface) {
        try {
            const parsedLog = iface.parseLog(log);
            if (!parsedLog || parsedLog.name !== listener.eventName) {
                return;
            }
            const provider = this.getProvider(listener.chainId);
            const block = await provider.getBlock(log.blockHash);
            const event = {
                blockNumber: log.blockNumber,
                blockHash: log.blockHash,
                transactionHash: log.transactionHash,
                logIndex: log.index,
                address: (0, utils_1.normalizeAddress)(log.address),
                eventName: parsedLog.name,
                args: parsedLog.args.toObject(),
                timestamp: block?.timestamp || Math.floor(Date.now() / 1000),
            };
            this.processingQueue.get(listener.id)?.push(event);
            await this.processEvent(event, listener);
            listener.processedCount++;
            listener.lastProcessedBlock = log.blockNumber;
            const domainEvent = {
                id: (0, utils_1.generateId)('event'),
                type: events_1.EVENTS.CONTRACT_EVENT,
                source: 'ContractEventListener',
                timestamp: (0, utils_1.now)(),
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
            events_1.eventBus.emitDomainEvent(domainEvent);
            this.logger.debug('Event processed', {
                listenerId: listener.id,
                eventName: event.eventName,
                blockNumber: event.blockNumber,
            });
        }
        catch (error) {
            this.logger.error('Failed to process log', error, {
                listenerId: listener.id,
                transactionHash: log.transactionHash,
            });
        }
    }
    async processEvent(event, listener) {
        if (!listener.callbackUrl) {
            return { success: true, retryCount: 0 };
        }
        return (0, utils_1.withRetry)(async () => {
            const response = await fetch(listener.callbackUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    listenerId: listener.id,
                    event,
                    timestamp: (0, utils_1.now)(),
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
                    error: (0, utils_1.getErrorMessage)(error),
                });
            },
        }).catch((error) => ({
            success: false,
            error: (0, utils_1.getErrorMessage)(error),
            retryCount: 3,
        }));
    }
    stopListener(listenerId) {
        this.logger.info('Stopping event listener', { listenerId });
        const listener = this.listeners.get(listenerId);
        if (!listener) {
            throw new Error(`Listener not found: ${listenerId}`);
        }
        listener.active = false;
        const config = config_1.CHAIN_CONFIGS[listener.chainId];
        if (config.wsUrl) {
            this.wsConnections.get(listener.chainId)?.close();
            this.wsConnections.delete(listener.chainId);
        }
        const domainEvent = {
            id: (0, utils_1.generateId)('event'),
            type: events_1.EVENTS.LISTENER_STOPPED,
            source: 'ContractEventListener',
            timestamp: (0, utils_1.now)(),
            data: { listenerId, chainId: listener.chainId },
            metadata: {
                listenerId,
                chainId: listener.chainId,
                address: listener.address,
                eventName: listener.eventName,
                processedCount: listener.processedCount,
            },
        };
        events_1.eventBus.emitDomainEvent(domainEvent);
        this.logger.info('Event listener stopped', { listenerId });
        return listener;
    }
    getListener(listenerId) {
        return this.listeners.get(listenerId);
    }
    listListeners(chainId, active) {
        let listeners = Array.from(this.listeners.values());
        if (chainId !== undefined) {
            listeners = listeners.filter((l) => l.chainId === chainId);
        }
        if (active !== undefined) {
            listeners = listeners.filter((l) => l.active === active);
        }
        return listeners.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    }
    getListenerQueue(listenerId) {
        return this.processingQueue.get(listenerId) || [];
    }
    clearListenerQueue(listenerId) {
        const queue = this.processingQueue.get(listenerId);
        if (!queue)
            return 0;
        const count = queue.length;
        this.processingQueue.set(listenerId, []);
        return count;
    }
    deleteListener(listenerId) {
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
        const domainEvent = {
            id: (0, utils_1.generateId)('event'),
            type: events_1.EVENTS.LISTENER_DELETED,
            source: 'ContractEventListener',
            timestamp: (0, utils_1.now)(),
            data: { listenerId, chainId: listener.chainId },
            metadata: {
                listenerId,
                chainId: listener.chainId,
                address: listener.address,
                eventName: listener.eventName,
            },
        };
        events_1.eventBus.emitDomainEvent(domainEvent);
        this.logger.info('Event listener deleted', { listenerId });
        return true;
    }
    async fetchHistoricalEvents(params) {
        const { chainId, address, eventName, abi, fromBlock, toBlock } = params;
        this.logger.info('Fetching historical events', {
            chainId,
            address,
            eventName,
            fromBlock,
            toBlock,
        });
        const provider = this.getProvider(chainId);
        const iface = new ethers_1.Interface(abi);
        const eventFragment = iface.getEvent(eventName);
        const eventTopic = eventFragment?.topicHash || '';
        const logs = await provider.getLogs({
            address: (0, utils_1.normalizeAddress)(address),
            topics: [eventTopic],
            fromBlock,
            toBlock,
        });
        const events = [];
        for (const log of logs) {
            try {
                const parsedLog = iface.parseLog(log);
                if (!parsedLog)
                    continue;
                const block = await provider.getBlock(log.blockHash);
                events.push({
                    blockNumber: log.blockNumber,
                    blockHash: log.blockHash,
                    transactionHash: log.transactionHash,
                    logIndex: log.index,
                    address: (0, utils_1.normalizeAddress)(log.address),
                    eventName: parsedLog.name,
                    args: parsedLog.args.toObject(),
                    timestamp: block?.timestamp || Math.floor(Date.now() / 1000),
                });
            }
            catch (error) {
                this.logger.warn('Failed to parse log', error, { transactionHash: log.transactionHash });
            }
        }
        this.logger.info('Historical events fetched', { count: events.length });
        return events;
    }
    getStats() {
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
exports.ContractEventListener = ContractEventListener;
exports.contractEventListener = new ContractEventListener();
//# sourceMappingURL=events.js.map