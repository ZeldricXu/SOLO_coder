import { Wallet, isAddress, keccak256, toUtf8Bytes, getBytes, recoverAddress, hashMessage } from 'ethers';
import { CrossChainMessage, ChainId } from '../types';
import { generateId, now, normalizeAddress, withRetry, getErrorMessage } from '../common/utils';
import { eventBus, EVENTS } from '../common/events';
import { LoggerContext } from '../common/logger';

export interface LockProof {
  messageId: string;
  lockTransactionHash: string;
  amount: string;
  asset: string;
  sender: string;
  recipient: string;
  sourceChain: number;
  destinationChain: number;
  nonce: number;
  timestamp: string;
  signatures: string[];
}

export interface BridgeConfig {
  sourceChain: ChainId;
  destinationChain: ChainId;
  bridgeContract: string;
  relayers: string[];
  requiredConfirmations: number;
  lockTimeout: number;
}

export class CrossChainBridge {
  private messages: Map<string, CrossChainMessage>;
  private bridgeConfigs: Map<string, BridgeConfig>;
  private lockedAssets: Map<string, { amount: string; asset: string; lockedAt: string }>;
  private mintedAssets: Map<string, { amount: string; asset: string; mintedAt: string }>;
  private logger: LoggerContext;

  constructor() {
    this.messages = new Map();
    this.bridgeConfigs = new Map();
    this.lockedAssets = new Map();
    this.mintedAssets = new Map();
    this.logger = new LoggerContext({ module: 'CrossChainBridge' });
  }

  registerBridge(config: BridgeConfig): string {
    this.logger.info('Registering bridge', {
      sourceChain: config.sourceChain,
      destinationChain: config.destinationChain,
    });

    const bridgeId = generateId('bridge');
    this.bridgeConfigs.set(bridgeId, config);

    this.logger.info('Bridge registered', { bridgeId });
    return bridgeId;
  }

  getBridge(bridgeId: string): BridgeConfig | undefined {
    return this.bridgeConfigs.get(bridgeId);
  }

  listBridges(): Array<{ id: string; config: BridgeConfig }> {
    return Array.from(this.bridgeConfigs.entries()).map(([id, config]) => ({ id, config }));
  }

  async createCrossChainMessage(params: {
    sourceChain: ChainId;
    destinationChain: ChainId;
    sender: string;
    recipient: string;
    amount: string;
    asset: string;
  }): Promise<CrossChainMessage> {
    const { sourceChain, destinationChain, sender, recipient, amount, asset } = params;

    this.logger.info('Creating cross-chain message', {
      sourceChain,
      destinationChain,
      sender,
      recipient,
      amount,
      asset,
    });

    if (!isAddress(sender)) {
      throw new Error(`Invalid sender address: ${sender}`);
    }

    if (!isAddress(recipient)) {
      throw new Error(`Invalid recipient address: ${recipient}`);
    }

    if (!isAddress(asset)) {
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

    const message: CrossChainMessage = {
      id: generateId('ccm'),
      sourceChain,
      destinationChain,
      sender: normalizeAddress(sender),
      recipient: normalizeAddress(recipient),
      amount,
      asset: normalizeAddress(asset),
      nonce,
      messageHash,
      signatures: [],
      status: 'pending',
      createdAt: now(),
    };

    this.messages.set(message.id, message);

    eventBus.emit(EVENTS.CROSS_CHAIN_MESSAGE, { type: 'created', message });
    this.logger.info('Cross-chain message created', { messageId: message.id, messageHash });

    return message;
  }

  private getNextNonce(sourceChain: number, destinationChain: number): number {
    const messages = Array.from(this.messages.values()).filter(
      (m) => m.sourceChain === sourceChain && m.destinationChain === destinationChain
    );
    return messages.length;
  }

  private generateMessageHash(params: {
    sourceChain: number;
    destinationChain: number;
    sender: string;
    recipient: string;
    amount: string;
    asset: string;
    nonce: number;
  }): string {
    const message = JSON.stringify(params);
    return keccak256(toUtf8Bytes(message));
  }

  async lockAssets(messageId: string, bridgeId: string): Promise<CrossChainMessage> {
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

    const result = await withRetry(async () => {
      const lockKey = `${messageId}:${message.asset}`;
      this.lockedAssets.set(lockKey, {
        amount: message.amount,
        asset: message.asset,
        lockedAt: now(),
      });

      message.status = 'locked';

      return message;
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying asset lock', { messageId, attempt, error: getErrorMessage(error) });
      },
    });

    eventBus.emit(EVENTS.CROSS_CHAIN_MESSAGE, { type: 'locked', message: result });
    this.logger.info('Assets locked', { messageId, amount: message.amount, asset: message.asset });

    return result;
  }

  addRelayerSignature(messageId: string, signature: string, relayer: string): CrossChainMessage {
    this.logger.info('Adding relayer signature', { messageId, relayer });

    const message = this.messages.get(messageId);
    if (!message) {
      throw new Error(`Message not found: ${messageId}`);
    }

    if (message.status !== 'locked') {
      throw new Error(`Message assets not locked yet: ${message.status}`);
    }

    const normalizedRelayer = normalizeAddress(relayer);

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

  private verifyMessageSignature(messageHash: string, signature: string, signer: string): boolean {
    try {
      const messageHashBytes = getBytes(messageHash);
      const recoveredAddress = recoverAddress(messageHashBytes, signature);
      return normalizeAddress(recoveredAddress) === normalizeAddress(signer);
    } catch (error) {
      this.logger.error('Signature verification failed', error as Error);
      return false;
    }
  }

  async mintAssets(messageId: string, requiredSignatures: number = 1): Promise<CrossChainMessage> {
    this.logger.info('Minting assets', { messageId, requiredSignatures });

    const message = this.messages.get(messageId);
    if (!message) {
      throw new Error(`Message not found: ${messageId}`);
    }

    if (message.status !== 'locked') {
      throw new Error(`Message is not locked: ${message.status}`);
    }

    if (message.signatures.length < requiredSignatures) {
      throw new Error(
        `Insufficient signatures: required ${requiredSignatures}, got ${message.signatures.length}`
      );
    }

    const result = await withRetry(async () => {
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
        mintedAt: now(),
      });

      message.status = 'minted';

      return message;
    }, {
      retries: 3,
      onRetry: (error, attempt) => {
        this.logger.warn('Retrying asset mint', { messageId, attempt, error: getErrorMessage(error) });
      },
    });

    eventBus.emit(EVENTS.CROSS_CHAIN_MESSAGE, { type: 'minted', message: result });
    this.logger.info('Assets minted', { messageId, amount: message.amount, recipient: message.recipient });

    return result;
  }

  async confirmMessage(messageId: string): Promise<CrossChainMessage> {
    this.logger.info('Confirming message', { messageId });

    const message = this.messages.get(messageId);
    if (!message) {
      throw new Error(`Message not found: ${messageId}`);
    }

    if (message.status !== 'minted') {
      throw new Error(`Message is not minted: ${message.status}`);
    }

    message.status = 'confirmed';

    eventBus.emit(EVENTS.CROSS_CHAIN_MESSAGE, { type: 'confirmed', message });
    this.logger.info('Message confirmed', { messageId });

    return message;
  }

  failMessage(messageId: string, reason: string): CrossChainMessage {
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

    eventBus.emit(EVENTS.CROSS_CHAIN_MESSAGE, { type: 'failed', message, reason });
    this.logger.warn('Message failed', { messageId, reason });

    return message;
  }

  getMessage(messageId: string): CrossChainMessage | undefined {
    return this.messages.get(messageId);
  }

  listMessages(params?: {
    sourceChain?: ChainId;
    destinationChain?: ChainId;
    status?: CrossChainMessage['status'];
    sender?: string;
    recipient?: string;
  }): CrossChainMessage[] {
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
      messages = messages.filter((m) => normalizeAddress(m.sender) === normalizeAddress(params.sender!));
    }

    if (params?.recipient) {
      messages = messages.filter((m) => normalizeAddress(m.recipient) === normalizeAddress(params.recipient!));
    }

    return messages.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }

  getLockProof(messageId: string): LockProof | undefined {
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
      lockTransactionHash: generateId('lock'),
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

  verifyAtomicity(messageId: string): {
    isAtomic: boolean;
    locked: boolean;
    minted: boolean;
    amountsMatch: boolean;
  } {
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

  getStats(): {
    totalMessages: number;
    pending: number;
    locked: number;
    minted: number;
    confirmed: number;
    failed: number;
    totalLockedAmount: string;
    totalMintedAmount: string;
  } {
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

export const crossChainBridge = new CrossChainBridge();
