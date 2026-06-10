import { Operation, OperationBatch, DEFAULT_CONFIG } from '../types';
import { createLogger } from '../utils/logger';

const logger = createLogger('OperationBuffer');

export type FlushCallback = (roomId: string, operations: Operation[], batchSequence: number) => void;

interface RoomBuffer {
  roomId: string;
  operations: Operation[];
  batchSequence: number;
  flushTimer: NodeJS.Timeout | null;
  lastFlushTime: number;
}

export class OperationBuffer {
  private buffers: Map<string, RoomBuffer> = new Map();
  private flushCallback: FlushCallback;
  private bufferTimeoutMs: number;
  private maxBufferSize: number;
  private maxLatencyMs: number;

  constructor(
    flushCallback: FlushCallback,
    bufferTimeoutMs: number = DEFAULT_CONFIG.operationBufferTimeoutMs,
    maxBufferSize: number = DEFAULT_CONFIG.operationBufferMaxSize,
    maxLatencyMs: number = 200
  ) {
    this.flushCallback = flushCallback;
    this.bufferTimeoutMs = bufferTimeoutMs;
    this.maxBufferSize = maxBufferSize;
    this.maxLatencyMs = maxLatencyMs;
  }

  addOperation(roomId: string, operation: Operation, immediate: boolean = false): void {
    const buffer = this.getOrCreateBuffer(roomId);
    buffer.operations.push(operation);

    if (immediate) {
      this.flush(roomId);
      return;
    }

    const now = Date.now();
    const timeSinceLastFlush = now - buffer.lastFlushTime;
    const effectiveTimeout = Math.min(this.bufferTimeoutMs, Math.max(0, this.maxLatencyMs - timeSinceLastFlush));

    if (buffer.operations.length >= this.maxBufferSize) {
      this.flush(roomId);
      return;
    }

    if (timeSinceLastFlush >= this.maxLatencyMs) {
      this.flush(roomId);
      return;
    }

    if (buffer.flushTimer) {
      clearTimeout(buffer.flushTimer);
    }

    buffer.flushTimer = setTimeout(() => {
      this.flush(roomId);
    }, effectiveTimeout);
  }

  addOperations(roomId: string, operations: Operation[], immediate: boolean = false): void {
    if (operations.length === 0) {
      return;
    }
    if (operations.length === 1) {
      this.addOperation(roomId, operations[0], immediate);
      return;
    }
    const buffer = this.getOrCreateBuffer(roomId);
    buffer.operations.push(...operations);

    if (immediate || buffer.operations.length >= this.maxBufferSize) {
      this.flush(roomId);
      return;
    }

    const now = Date.now();
    const timeSinceLastFlush = now - buffer.lastFlushTime;

    if (timeSinceLastFlush >= this.maxLatencyMs) {
      this.flush(roomId);
      return;
    }

    const effectiveTimeout = Math.min(this.bufferTimeoutMs, Math.max(0, this.maxLatencyMs - timeSinceLastFlush));

    if (buffer.flushTimer) {
      clearTimeout(buffer.flushTimer);
    }

    buffer.flushTimer = setTimeout(() => {
      this.flush(roomId);
    }, effectiveTimeout);
  }

  flush(roomId: string): OperationBatch | null {
    const buffer = this.buffers.get(roomId);
    if (!buffer || buffer.operations.length === 0) {
      return null;
    }

    if (buffer.flushTimer) {
      clearTimeout(buffer.flushTimer);
      buffer.flushTimer = null;
    }

    const operations = buffer.operations.sort((a, b) => a.sequence - b.sequence);
    buffer.batchSequence += 1;
    const batchSequence = buffer.batchSequence;
    buffer.operations = [];
    buffer.lastFlushTime = Date.now();

    const batch: OperationBatch = {
      roomId,
      operations,
      batchSequence
    };

    logger.debug('Flushing operation batch', {
      roomId,
      operationCount: operations.length,
      batchSequence
    });

    try {
      this.flushCallback(roomId, operations, batchSequence);
    } catch (error) {
      logger.error('Flush callback error', {
        roomId,
        error: error instanceof Error ? error.message : String(error)
      });
    }

    return batch;
  }

  flushAll(): void {
    for (const roomId of this.buffers.keys()) {
      this.flush(roomId);
    }
  }

  clear(roomId: string): void {
    const buffer = this.buffers.get(roomId);
    if (buffer) {
      if (buffer.flushTimer) {
        clearTimeout(buffer.flushTimer);
      }
      this.buffers.delete(roomId);
      logger.debug('Cleared operation buffer', { roomId });
    }
  }

  clearAll(): void {
    for (const [, buffer] of this.buffers) {
      if (buffer.flushTimer) {
        clearTimeout(buffer.flushTimer);
      }
    }
    this.buffers.clear();
    logger.info('Cleared all operation buffers');
  }

  getBufferSize(roomId: string): number {
    return this.buffers.get(roomId)?.operations.length ?? 0;
  }

  getBufferedOperations(roomId: string): Operation[] {
    return [...(this.buffers.get(roomId)?.operations ?? [])];
  }

  getStats(): Record<string, { bufferedCount: number; batchSequence: number; timeSinceLastFlush: number }> {
    const stats: Record<string, { bufferedCount: number; batchSequence: number; timeSinceLastFlush: number }> = {};
    const now = Date.now();
    for (const [roomId, buffer] of this.buffers) {
      stats[roomId] = {
        bufferedCount: buffer.operations.length,
        batchSequence: buffer.batchSequence,
        timeSinceLastFlush: now - buffer.lastFlushTime
      };
    }
    return stats;
  }

  private getOrCreateBuffer(roomId: string): RoomBuffer {
    let buffer = this.buffers.get(roomId);
    if (!buffer) {
      buffer = {
        roomId,
        operations: [],
        batchSequence: 0,
        flushTimer: null,
        lastFlushTime: Date.now()
      };
      this.buffers.set(roomId, buffer);
      logger.debug('Created operation buffer', { roomId });
    }
    return buffer;
  }

  destroy(): void {
    this.clearAll();
  }
}
