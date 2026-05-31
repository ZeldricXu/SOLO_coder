"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.crossChainBridge = exports.CrossChainBridge = void 0;
const ethers_1 = require("ethers");
const utils_1 = require("../common/utils");
const events_1 = require("../common/events");
const logger_1 = require("../common/logger");
class CrossChainBridge {
    messages;
    bridgeConfigs;
    lockedAssets;
    mintedAssets;
    logger;
    constructor() {
        this.messages = new Map();
        this.bridgeConfigs = new Map();
        this.lockedAssets = new Map();
        this.mintedAssets = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'CrossChainBridge' });
    }
    registerBridge(config) {
        this.logger.info('Registering bridge', {
            sourceChain: config.sourceChain,
            destinationChain: config.destinationChain,
        });
        const bridgeId = (0, utils_1.generateId)('bridge');
        this.bridgeConfigs.set(bridgeId, config);
        this.logger.info('Bridge registered', { bridgeId });
        return bridgeId;
    }
    getBridge(bridgeId) {
        return this.bridgeConfigs.get(bridgeId);
    }
    listBridges() {
        return Array.from(this.bridgeConfigs.entries()).map(([id, config]) => ({ id, config }));
    }
    async createCrossChainMessage(params) {
        const { sourceChain, destinationChain, sender, recipient, amount, asset } = params;
        this.logger.info('Creating cross-chain message', {
            sourceChain,
            destinationChain,
            sender,
            recipient,
            amount,
            asset,
        });
        if (!(0, ethers_1.isAddress)(sender)) {
            throw new Error(`Invalid sender address: ${sender}`);
        }
        if (!(0, ethers_1.isAddress)(recipient)) {
            throw new Error(`Invalid recipient address: ${recipient}`);
        }
        if (!(0, ethers_1.isAddress)(asset)) {
            throw new Error(`Invalid asset address: ${asset}`);
        }
        if (BigInt(amount) <= 0) {
            throw new Error('Amount must be greater than 0');
        }
        const nonce = this.getNextNonce(sourceChain, destinationChain);
        const messageHash = this.generateMessageHash({
            sourceChain,
            destinationChain,
            sender,
            recipient,
            amount,
            asset,
            nonce,
        });
        const message = {
            id: (0, utils_1.generateId)('ccm'),
            sourceChain,
            destinationChain,
            sender: (0, utils_1.normalizeAddress)(sender),
            recipient: (0, utils_1.normalizeAddress)(recipient),
            amount,
            asset: (0, utils_1.normalizeAddress)(asset),
            nonce,
            messageHash,
            signatures: [],
            status: 'pending',
            createdAt: (0, utils_1.now)(),
        };
        this.messages.set(message.id, message);
        events_1.eventBus.emit(events_1.EVENTS.CROSS_CHAIN_MESSAGE, { type: 'created', message });
        this.logger.info('Cross-chain message created', { messageId: message.id, messageHash });
        return message;
    }
    getNextNonce(sourceChain, destinationChain) {
        const messages = Array.from(this.messages.values()).filter((m) => m.sourceChain === sourceChain && m.destinationChain === destinationChain);
        return messages.length;
    }
    generateMessageHash(params) {
        const message = JSON.stringify(params);
        return (0, ethers_1.keccak256)((0, ethers_1.toUtf8Bytes)(message));
    }
    async lockAssets(messageId, bridgeId) {
        this.logger.info('Locking assets', { messageId, bridgeId });
        const message = this.messages.get(messageId);
        if (!message) {
            throw new Error(`Message not found: ${messageId}`);
        }
        if (message.status !== 'pending') {
            throw new Error(`Message is not pending: ${message.status}`);
        }
        const bridge = this.bridgeConfigs.get(bridgeId);
        if (!bridge) {
            throw new Error(`Bridge not found: ${bridgeId}`);
        }
        const result = await (0, utils_1.withRetry)(async () => {
            const lockKey = `${messageId}:${message.asset}`;
            this.lockedAssets.set(lockKey, {
                amount: message.amount,
                asset: message.asset,
                lockedAt: (0, utils_1.now)(),
            });
            message.status = 'locked';
            return message;
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying asset lock', { messageId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
        events_1.eventBus.emit(events_1.EVENTS.CROSS_CHAIN_MESSAGE, { type: 'locked', message: result });
        this.logger.info('Assets locked', { messageId, amount: message.amount, asset: message.asset });
        return result;
    }
    addRelayerSignature(messageId, signature, relayer) {
        this.logger.info('Adding relayer signature', { messageId, relayer });
        const message = this.messages.get(messageId);
        if (!message) {
            throw new Error(`Message not found: ${messageId}`);
        }
        if (message.status !== 'locked') {
            throw new Error(`Message assets not locked yet: ${message.status}`);
        }
        const normalizedRelayer = (0, utils_1.normalizeAddress)(relayer);
        if (message.signatures.some((s) => s.startsWith(`${normalizedRelayer}:`))) {
            throw new Error(`Relayer ${relayer} has already signed`);
        }
        const isValid = this.verifyMessageSignature(message.messageHash, signature, normalizedRelayer);
        if (!isValid) {
            throw new Error('Invalid signature');
        }
        message.signatures.push(`${normalizedRelayer}:${signature}`);
        this.logger.info('Relayer signature added', { messageId, relayer: normalizedRelayer });
        return message;
    }
    verifyMessageSignature(messageHash, signature, signer) {
        try {
            const messageHashBytes = (0, ethers_1.getBytes)(messageHash);
            const recoveredAddress = (0, ethers_1.recoverAddress)(messageHashBytes, signature);
            return (0, utils_1.normalizeAddress)(recoveredAddress) === (0, utils_1.normalizeAddress)(signer);
        }
        catch (error) {
            this.logger.error('Signature verification failed', error);
            return false;
        }
    }
    async mintAssets(messageId, requiredSignatures = 1) {
        this.logger.info('Minting assets', { messageId, requiredSignatures });
        const message = this.messages.get(messageId);
        if (!message) {
            throw new Error(`Message not found: ${messageId}`);
        }
        if (message.status !== 'locked') {
            throw new Error(`Message is not locked: ${message.status}`);
        }
        if (message.signatures.length < requiredSignatures) {
            throw new Error(`Insufficient signatures: required ${requiredSignatures}, got ${message.signatures.length}`);
        }
        const result = await (0, utils_1.withRetry)(async () => {
            const lockKey = `${messageId}:${message.asset}`;
            const locked = this.lockedAssets.get(lockKey);
            if (!locked) {
                throw new Error('Assets not found in lock pool');
            }
            if (locked.amount !== message.amount) {
                throw new Error('Locked amount mismatch');
            }
            const mintKey = `${messageId}:${message.asset}`;
            this.mintedAssets.set(mintKey, {
                amount: message.amount,
                asset: message.asset,
                mintedAt: (0, utils_1.now)(),
            });
            message.status = 'minted';
            return message;
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying asset mint', { messageId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
        events_1.eventBus.emit(events_1.EVENTS.CROSS_CHAIN_MESSAGE, { type: 'minted', message: result });
        this.logger.info('Assets minted', { messageId, amount: message.amount, recipient: message.recipient });
        return result;
    }
    async confirmMessage(messageId) {
        this.logger.info('Confirming message', { messageId });
        const message = this.messages.get(messageId);
        if (!message) {
            throw new Error(`Message not found: ${messageId}`);
        }
        if (message.status !== 'minted') {
            throw new Error(`Message is not minted: ${message.status}`);
        }
        message.status = 'confirmed';
        events_1.eventBus.emit(events_1.EVENTS.CROSS_CHAIN_MESSAGE, { type: 'confirmed', message });
        this.logger.info('Message confirmed', { messageId });
        return message;
    }
    failMessage(messageId, reason) {
        this.logger.info('Failing message', { messageId, reason });
        const message = this.messages.get(messageId);
        if (!message) {
            throw new Error(`Message not found: ${messageId}`);
        }
        if (message.status === 'confirmed' || message.status === 'failed') {
            throw new Error(`Cannot fail message with status: ${message.status}`);
        }
        if (message.status === 'locked') {
            const lockKey = `${messageId}:${message.asset}`;
            this.lockedAssets.delete(lockKey);
        }
        message.status = 'failed';
        events_1.eventBus.emit(events_1.EVENTS.CROSS_CHAIN_MESSAGE, { type: 'failed', message, reason });
        this.logger.warn('Message failed', { messageId, reason });
        return message;
    }
    getMessage(messageId) {
        return this.messages.get(messageId);
    }
    listMessages(params) {
        let messages = Array.from(this.messages.values());
        if (params?.sourceChain !== undefined) {
            messages = messages.filter((m) => m.sourceChain === params.sourceChain);
        }
        if (params?.destinationChain !== undefined) {
            messages = messages.filter((m) => m.destinationChain === params.destinationChain);
        }
        if (params?.status) {
            messages = messages.filter((m) => m.status === params.status);
        }
        if (params?.sender) {
            messages = messages.filter((m) => (0, utils_1.normalizeAddress)(m.sender) === (0, utils_1.normalizeAddress)(params.sender));
        }
        if (params?.recipient) {
            messages = messages.filter((m) => (0, utils_1.normalizeAddress)(m.recipient) === (0, utils_1.normalizeAddress)(params.recipient));
        }
        return messages.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    }
    getLockProof(messageId) {
        const message = this.messages.get(messageId);
        if (!message || message.status === 'pending') {
            return undefined;
        }
        const lockKey = `${messageId}:${message.asset}`;
        const locked = this.lockedAssets.get(lockKey);
        if (!locked) {
            return undefined;
        }
        return {
            messageId,
            lockTransactionHash: (0, utils_1.generateId)('lock'),
            amount: message.amount,
            asset: message.asset,
            sender: message.sender,
            recipient: message.recipient,
            sourceChain: message.sourceChain,
            destinationChain: message.destinationChain,
            nonce: message.nonce,
            timestamp: locked.lockedAt,
            signatures: message.signatures,
        };
    }
    verifyAtomicity(messageId) {
        const message = this.messages.get(messageId);
        if (!message) {
            return { isAtomic: false, locked: false, minted: false, amountsMatch: false };
        }
        const lockKey = `${messageId}:${message.asset}`;
        const mintKey = `${messageId}:${message.asset}`;
        const locked = this.lockedAssets.has(lockKey);
        const minted = this.mintedAssets.has(mintKey);
        const lockedAmount = this.lockedAssets.get(lockKey)?.amount;
        const mintedAmount = this.mintedAssets.get(mintKey)?.amount;
        const amountsMatch = lockedAmount === mintedAmount && lockedAmount === message.amount;
        const isAtomic = locked && minted && amountsMatch;
        this.logger.info('Atomicity verification', {
            messageId,
            isAtomic,
            locked,
            minted,
            amountsMatch,
        });
        return { isAtomic, locked, minted, amountsMatch };
    }
    getStats() {
        const messages = Array.from(this.messages.values());
        const lockedAssets = Array.from(this.lockedAssets.values());
        const mintedAssets = Array.from(this.mintedAssets.values());
        const totalLockedAmount = lockedAssets.reduce((sum, a) => sum + BigInt(a.amount), BigInt(0)).toString();
        const totalMintedAmount = mintedAssets.reduce((sum, a) => sum + BigInt(a.amount), BigInt(0)).toString();
        return {
            totalMessages: messages.length,
            pending: messages.filter((m) => m.status === 'pending').length,
            locked: messages.filter((m) => m.status === 'locked').length,
            minted: messages.filter((m) => m.status === 'minted').length,
            confirmed: messages.filter((m) => m.status === 'confirmed').length,
            failed: messages.filter((m) => m.status === 'failed').length,
            totalLockedAmount,
            totalMintedAmount,
        };
    }
}
exports.CrossChainBridge = CrossChainBridge;
exports.crossChainBridge = new CrossChainBridge();
//# sourceMappingURL=crosschain.js.map