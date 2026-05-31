import type { Logger } from '@shared/logger';
import type { CachePort } from '@shared/cache';
import type {
  ContractEventListenerPort,
  EventFilter,
  EventSubscription,
  EventCallback,
  EventCallbackContext,
  EventDecoderPort,
} from '@core/ports/eventListener.port';
import type { ChainInteractionProvider } from '@core/ports/chainInteraction.port';
import type { ContractEvent, LogEntry } from '@core/domain/blockchain';
import type { ChainId, HexString } from '@shared/types';
import { NotFoundError, ConflictError } from '@shared/errors';

export class ContractEventListenerService implements ContractEventListenerPort {
  private subscriptions: Map<string, {
    subscription: EventSubscription;
    callback: EventCallback;
    abi?: unknown;
    unsubscribeFn?: () => void;
  }> = new Map();

  constructor(
    private readonly chainProvider: ChainInteractionProvider,
    private readonly decoder: EventDecoderPort,
    private readonly logger: Logger,
    private readonly config: {
      maxSubscriptions?: number;
      retryOnError?: boolean;
      maxRetries?: number;
    } = {
      maxSubscriptions: 100,
      retryOnError: true,
      maxRetries: 3,
    },
    private readonly cache?: CachePort
  ) {}

  private generateId(): string {
    return `sub_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  }

  async subscribe<T = unknown>(
    chainId: ChainId,
    filter: EventFilter,
    callback: EventCallback<T>,
    eventName?: string,
    abi?: unknown
  ): Promise<EventSubscription> {
    if (this.subscriptions.size >= (this.config.maxSubscriptions || 100)) {
      throw new ConflictError('Maximum number of subscriptions reached');
    }

    const subscriptionId = this.generateId();
    const chainClient = this.chainProvider.getClient(chainId);

    this.logger.info('Creating event subscription', {
      subscriptionId,
      chainId,
      eventName,
      address: filter.address,
    });

    const subscription: EventSubscription = {
      id: subscriptionId,
      filter,
      eventName,
      chainId,
      createdAt: Date.now(),
      isActive: true,
    };

    const wrappedCallback: EventCallback = async (event, context) => {
      try {
        this.logger.debug('Received event', {
          subscriptionId,
          eventName: event.name,
          blockNumber: event.blockNumber.toString(),
        });
        await callback(event as ContractEvent<T>, context);
      } catch (error) {
        this.logger.error('Error in event callback', {
          error,
          subscriptionId,
          eventName: event.name,
        });
      }
    };

    const unsubscribeFn = await chainClient.subscribeToLogs(filter, async (log) => {
      const context: EventCallbackContext = {
        subscriptionId,
        eventName,
        chainId,
        receivedAt: Date.now(),
        log,
      };

      let eventData: unknown = null;
      if (abi && eventName) {
        eventData = this.decoder.decodeLog(log, abi);
      }

      const event: ContractEvent = {
        name: eventName || 'Unknown',
        address: log.address,
        blockNumber: log.blockNumber,
        transactionHash: log.transactionHash,
        data: eventData,
        raw: log,
      };

      await wrappedCallback(event, context);
    });

    this.subscriptions.set(subscriptionId, {
      subscription,
      callback: wrappedCallback,
      abi,
      unsubscribeFn,
    });

    return subscription;
  }

  async unsubscribe(subscriptionId: string): Promise<boolean> {
    const sub = this.subscriptions.get(subscriptionId);
    if (!sub) {
      return false;
    }

    if (sub.unsubscribeFn) {
      sub.unsubscribeFn();
    }

    this.subscriptions.delete(subscriptionId);
    sub.subscription.isActive = false;

    this.logger.info('Unsubscribed from events', { subscriptionId });

    return true;
  }

  async getSubscription(subscriptionId: string): Promise<EventSubscription | null> {
    const sub = this.subscriptions.get(subscriptionId);
    return sub ? { ...sub.subscription } : null;
  }

  async listSubscriptions(chainId?: ChainId): Promise<EventSubscription[]> {
    return Array.from(this.subscriptions.values())
      .filter(s => !chainId || s.subscription.chainId === chainId)
      .map(s => ({ ...s.subscription }));
  }

  async queryHistoricalEvents<T = unknown>(
    chainId: ChainId,
    filter: EventFilter,
    abi?: unknown,
    eventName?: string
  ): Promise<ContractEvent<T>[]> {
    this.logger.info('Querying historical events', {
      chainId,
      fromBlock: filter.fromBlock?.toString(),
      toBlock: filter.toBlock?.toString(),
    });

    const chainClient = this.chainProvider.getClient(chainId);
    const logs = await chainClient.getLogs(filter);

    const events: ContractEvent<T>[] = [];

    for (const log of logs) {
      let eventData: T | null = null;
      if (abi && eventName) {
        eventData = this.decoder.decodeLog<T>(log, abi);
      }

      events.push({
        name: eventName || 'Unknown',
        address: log.address,
        blockNumber: log.blockNumber,
        transactionHash: log.transactionHash,
        data: eventData as T,
        raw: log,
      });
    }

    this.logger.info('Retrieved historical events', {
      count: events.length,
      chainId,
    });

    return events;
  }

  async pause(subscriptionId: string): Promise<boolean> {
    const sub = this.subscriptions.get(subscriptionId);
    if (!sub) {
      return false;
    }

    if (sub.unsubscribeFn) {
      sub.unsubscribeFn();
      sub.unsubscribeFn = undefined;
    }

    sub.subscription.isActive = false;
    this.logger.info('Paused subscription', { subscriptionId });

    return true;
  }

  async resume(subscriptionId: string): Promise<boolean> {
    const sub = this.subscriptions.get(subscriptionId);
    if (!sub) {
      return false;
    }

    if (sub.subscription.isActive) {
      return true;
    }

    const chainClient = this.chainProvider.getClient(sub.subscription.chainId);

    const unsubscribeFn = await chainClient.subscribeToLogs(
      sub.subscription.filter,
      async (log) => {
        const context: EventCallbackContext = {
          subscriptionId,
          eventName: sub.subscription.eventName,
          chainId: sub.subscription.chainId,
          receivedAt: Date.now(),
          log,
        };

        let eventData: unknown = null;
        if (sub.abi && sub.subscription.eventName) {
          eventData = this.decoder.decodeLog(log, sub.abi);
        }

        const event: ContractEvent = {
          name: sub.subscription.eventName || 'Unknown',
          address: log.address,
          blockNumber: log.blockNumber,
          transactionHash: log.transactionHash,
          data: eventData,
          raw: log,
        };

        await sub.callback(event, context);
      }
    );

    sub.unsubscribeFn = unsubscribeFn;
    sub.subscription.isActive = true;

    this.logger.info('Resumed subscription', { subscriptionId });

    return true;
  }

  async subscribeToNewBlocks(
    chainId: ChainId,
    onBlock: (block: { number: bigint; hash: string; timestamp: bigint }) => void
  ): Promise<() => void> {
    const chainClient = this.chainProvider.getClient(chainId);
    this.logger.info('Subscribing to new blocks', { chainId });

    return chainClient.subscribeToNewBlocks((block) => {
      onBlock({
        number: block.number,
        hash: block.hash as HexString,
        timestamp: block.timestamp,
      });
    });
  }

  getActiveSubscriptionCount(): number {
    return Array.from(this.subscriptions.values()).filter(s => s.subscription.isActive).length;
  }

  async replayEvents(
    subscriptionId: string,
    fromBlock: bigint,
    toBlock: bigint
  ): Promise<number> {
    const sub = this.subscriptions.get(subscriptionId);
    if (!sub) {
      throw new NotFoundError('EventSubscription', subscriptionId);
    }

    const historicalFilter: EventFilter = {
      ...sub.subscription.filter,
      fromBlock,
      toBlock,
    };

    const events = await this.queryHistoricalEvents(
      sub.subscription.chainId,
      historicalFilter,
      sub.abi,
      sub.subscription.eventName
    );

    for (const event of events) {
      const context: EventCallbackContext = {
        subscriptionId,
        eventName: sub.subscription.eventName,
        chainId: sub.subscription.chainId,
        receivedAt: Date.now(),
        log: event.raw,
      };

      await sub.callback(event, context);
    }

    this.logger.info('Replayed events', {
      subscriptionId,
      count: events.length,
      fromBlock: fromBlock.toString(),
      toBlock: toBlock.toString(),
    });

    return events.length;
  }
}
