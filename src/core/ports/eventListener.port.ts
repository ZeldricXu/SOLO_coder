import type { Address, ChainId, HexString } from '@shared/types';
import type { ContractEvent, LogEntry } from '@core/domain/blockchain';

export interface EventFilter {
  address?: Address | Address[];
  topics?: (HexString | HexString[] | null)[];
  fromBlock?: bigint | 'latest';
  toBlock?: bigint | 'latest';
}

export interface EventSubscription {
  id: string;
  filter: EventFilter;
  eventName?: string;
  chainId: ChainId;
  createdAt: number;
  isActive: boolean;
}

export interface EventCallbackContext {
  subscriptionId: string;
  eventName?: string;
  chainId: ChainId;
  receivedAt: number;
  log: LogEntry;
}

export type EventCallback<T = unknown> = (
  event: ContractEvent<T>,
  context: EventCallbackContext
) => void | Promise<void>;

export interface ContractEventListenerPort {
  subscribe<T = unknown>(
    chainId: ChainId,
    filter: EventFilter,
    callback: EventCallback<T>,
    eventName?: string
  ): Promise<EventSubscription>;

  unsubscribe(subscriptionId: string): Promise<boolean>;

  getSubscription(subscriptionId: string): Promise<EventSubscription | null>;

  listSubscriptions(chainId?: ChainId): Promise<EventSubscription[]>;

  queryHistoricalEvents<T = unknown>(
    chainId: ChainId,
    filter: EventFilter
  ): Promise<ContractEvent<T>[]>;

  pause(subscriptionId: string): Promise<boolean>;

  resume(subscriptionId: string): Promise<boolean>;
}

export interface EventDecoderPort {
  decodeLog<T = unknown>(log: LogEntry, abi: unknown): T | null;
  encodeTopics(eventSignature: string, indexedParams?: unknown[]): HexString[];
  getEventSignature(eventName: string, abi: unknown): HexString;
}
