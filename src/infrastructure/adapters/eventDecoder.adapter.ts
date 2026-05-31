import {
  decodeEventLog,
  encodeEventTopics,
  getAbiItem,
  keccak256,
  toHex,
  stringToBytes,
} from 'viem';
import type { EventDecoderPort } from '@core/ports/eventListener.port';
import type { LogEntry } from '@core/domain/blockchain';
import type { HexString } from '@shared/types';

export class ViemEventDecoder implements EventDecoderPort {
  decodeLog<T = unknown>(log: LogEntry, abi: unknown): T | null {
    try {
      const abiArray = abi as Array<{ type: string; name: string; inputs?: unknown[] }>;

      try {
        const decoded = decodeEventLog({
          abi: abiArray,
          data: log.data as `0x${string}`,
          topics: log.topics as [`0x${string}`, ...`0x${string}`[]],
        });

        return decoded.args as T;
      } catch {
        // Try without strict topic typing
        const decoded = decodeEventLog({
          abi: abiArray,
          data: log.data as `0x${string}`,
          topics: log.topics as unknown as [],
        });
        return decoded.args as T;
      }
    } catch {
      return null;
    }
  }

  encodeTopics(eventSignature: string, indexedParams?: unknown[]): HexString[] {
    try {
      const topics = encodeEventTopics({
        abi: [
          {
            type: 'event',
            name: eventSignature.split('(')[0],
            inputs: this.parseEventInputs(eventSignature, indexedParams),
          },
        ],
        eventName: eventSignature.split('(')[0],
        args: indexedParams,
      });

      return topics as HexString[];
    } catch {
      const signature = eventSignature;
      const hash = keccak256(toHex(stringToBytes(signature)));
      return [hash as HexString];
    }
  }

  getEventSignature(eventName: string, abi: unknown): HexString {
    try {
      const abiArray = abi as Array<{ type: string; name: string; inputs?: Array<{ type: string }> }>;
      const event = getAbiItem({
        abi: abiArray,
        name: eventName,
      });

      if (event && 'name' in event && 'inputs' in event) {
        const inputs = (event.inputs as Array<{ type: string }>).map(i => i.type).join(',');
        const signature = `${event.name}(${inputs})`;
        return keccak256(toHex(stringToBytes(signature))) as HexString;
      }

      return keccak256(toHex(stringToBytes(eventName))) as HexString;
    } catch {
      return keccak256(toHex(stringToBytes(eventName))) as HexString;
    }
  }

  private parseEventInputs(
    eventSignature: string,
    indexedParams?: unknown[]
  ): Array<{ type: string; indexed?: boolean; name: string }> {
    const paramsMatch = eventSignature.match(/\((.*)\)/);
    if (!paramsMatch) return [];

    const params = paramsMatch[1].split(',').map((p, i) => {
      const trimmed = p.trim();
      const parts = trimmed.split(' ');
      const type = parts[0];
      const name = parts[1] || `param${i}`;
      const indexed = indexedParams && indexedParams[i] !== undefined;

      return { type, indexed, name };
    });

    return params;
  }

  static create(): EventDecoderPort {
    return new ViemEventDecoder();
  }
}
