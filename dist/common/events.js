"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.EVENTS = exports.eventBus = exports.EventBus = void 0;
const events_1 = __importDefault(require("events"));
const logger_1 = require("./logger");
class EventBus {
    emitter;
    logger;
    listeners;
    constructor() {
        this.emitter = new events_1.default();
        this.emitter.setMaxListeners(100);
        this.logger = new logger_1.LoggerContext({ module: 'EventBus' });
        this.listeners = new Map();
    }
    on(event, listener) {
        const listenerId = `${event}_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
        const wrappedListener = async (data) => {
            try {
                this.logger.debug('Event received', { event, listenerId });
                await listener(data);
            }
            catch (error) {
                this.logger.error('Event listener error', error, { event, listenerId });
            }
        };
        this.emitter.on(event, wrappedListener);
        if (!this.listeners.has(event)) {
            this.listeners.set(event, new Set());
        }
        this.listeners.get(event).add(listenerId);
        return listenerId;
    }
    off(event, listenerId) {
        const eventListeners = this.listeners.get(event);
        if (!eventListeners || !eventListeners.has(listenerId)) {
            return false;
        }
        eventListeners.delete(listenerId);
        return true;
    }
    emit(event, data) {
        this.logger.debug('Emitting event', { event, listenerCount: this.emitter.listenerCount(event) });
        return this.emitter.emit(event, data);
    }
    emitDomainEvent(event) {
        this.logger.debug('Emitting domain event', {
            type: event.type,
            source: event.source,
            listenerCount: this.emitter.listenerCount(event.type),
        });
        return this.emitter.emit(event.type, event);
    }
    once(event, listener) {
        this.emitter.once(event, async (data) => {
            try {
                await listener(data);
            }
            catch (error) {
                this.logger.error('Event once listener error', error, { event });
            }
        });
    }
    removeAllListeners(event) {
        if (event) {
            this.emitter.removeAllListeners(event);
            this.listeners.delete(event);
        }
        else {
            this.emitter.removeAllListeners();
            this.listeners.clear();
        }
    }
    listenerCount(event) {
        return this.emitter.listenerCount(event);
    }
    getEvents() {
        return Array.from(this.listeners.keys());
    }
}
exports.EventBus = EventBus;
exports.eventBus = new EventBus();
exports.EVENTS = {
    PROPOSAL_CREATED: 'proposal:created',
    PROPOSAL_SIGNED: 'proposal:signed',
    PROPOSAL_EXECUTED: 'proposal:executed',
    WALLET_CONFIG_UPDATED: 'wallet:config_updated',
    PROOF_VERIFIED: 'proof:verified',
    PROOF_STRATEGY_CHANGED: 'proof:strategy_changed',
    PROOF_STRATEGY_REGISTERED: 'proof:strategy_registered',
    PROOF_STRATEGY_UNREGISTERED: 'proof:strategy_unregistered',
    CONTRACT_EVENT: 'contract:event',
    LISTENER_CREATED: 'listener:created',
    LISTENER_STARTED: 'listener:started',
    LISTENER_STOPPED: 'listener:stopped',
    LISTENER_DELETED: 'listener:deleted',
    TRANSACTION_SIGNED: 'transaction:signed',
    CROSS_CHAIN_MESSAGE: 'crosschain:message',
    ADDRESS_DERIVED: 'address:derived',
    STORAGE_PINNED: 'storage:pinned',
    BLOCK_INDEXED: 'block:indexed',
    GAS_ESTIMATED: 'gas:estimated',
    ERROR: 'system:error',
    METRICS: 'system:metrics',
};
//# sourceMappingURL=events.js.map