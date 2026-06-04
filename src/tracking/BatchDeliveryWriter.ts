import Redis from 'ioredis';
import { db } from '../db';
import { logger } from '../utils/logger';

export interface BufferedOperation {
  type: 'insert' | 'update_status' | 'update_callback';
  payload: Record<string, any>;
  timestamp: number;
}

export class BatchDeliveryWriter {
  private redis: Redis | null = null;
  private streamKey = 'notify:delivery_log_stream';
  private consumerGroup = 'delivery-writer';
  private consumerName = `writer-${process.pid}`;
  private flushIntervalMs: number;
  private batchSize: number;
  private flushTimer: ReturnType<typeof setInterval> | null = null;
  private buffer: BufferedOperation[] = [];
  private isFlushing = false;
  private redisAvailable = false;
  private static instance: BatchDeliveryWriter;

  private constructor(flushIntervalMs: number = 500, batchSize: number = 100) {
    this.flushIntervalMs = flushIntervalMs;
    this.batchSize = batchSize;
    this.initRedis();
  }

  public static getInstance(flushIntervalMs?: number, batchSize?: number): BatchDeliveryWriter {
    if (!BatchDeliveryWriter.instance) {
      BatchDeliveryWriter.instance = new BatchDeliveryWriter(flushIntervalMs, batchSize);
    }
    return BatchDeliveryWriter.instance;
  }

  private async initRedis(): Promise<void> {
    try {
      this.redis = new Redis(process.env.REDIS_URL || 'redis://localhost:6379', {
        retryStrategy: (times) => {
          if (times > 3) {
            logger.warn('Redis connection failed after 3 retries, falling back to direct PostgreSQL writes');
            this.redisAvailable = false;
            return null;
          }
          return Math.min(times * 200, 2000);
        },
        maxRetriesPerRequest: 1,
        connectTimeout: 3000,
      });

      this.redis.on('connect', () => {
        this.redisAvailable = true;
        logger.info('BatchDeliveryWriter: Redis connected');
      });

      this.redis.on('error', (err) => {
        this.redisAvailable = false;
        logger.warn('BatchDeliveryWriter: Redis error, falling back to direct writes', { error: err.message });
      });

      this.redis.on('close', () => {
        this.redisAvailable = false;
      });

      await this.redis.ping();
      this.redisAvailable = true;

      try {
        await this.redis.xgroup('CREATE', this.streamKey, this.consumerGroup, '0', 'MKSTREAM');
      } catch (err: any) {
        if (!err.message?.includes('BUSYGROUP')) {
          logger.warn('Failed to create consumer group', { error: err.message });
        }
      }
    } catch (err) {
      this.redisAvailable = false;
      logger.warn('BatchDeliveryWriter: Redis unavailable, using direct PostgreSQL writes');
    }
  }

  public start(): void {
    if (this.flushTimer) return;

    this.flushTimer = setInterval(() => {
      this.flush().catch((err) => {
        logger.error('Periodic flush failed', { error: err.message });
      });
    }, this.flushIntervalMs);

    logger.info('BatchDeliveryWriter started', {
      flushIntervalMs: this.flushIntervalMs,
      batchSize: this.batchSize,
    });
  }

  public stop(): void {
    if (this.flushTimer) {
      clearInterval(this.flushTimer);
      this.flushTimer = null;
    }

    if (this.buffer.length > 0) {
      this.flush().catch((err) => {
        logger.error('Final flush failed', { error: err.message });
      });
    }

    logger.info('BatchDeliveryWriter stopped');
  }

  async enqueue(operation: BufferedOperation): Promise<void> {
    if (this.redisAvailable && this.redis) {
      try {
        await this.redis.xadd(
          this.streamKey,
          '*',
          'type', operation.type,
          'payload', JSON.stringify(operation.payload),
          'timestamp', operation.timestamp.toString()
        );
        return;
      } catch (err) {
        logger.warn('Redis xadd failed, falling back to buffer', { error: err });
        this.redisAvailable = false;
      }
    }

    this.buffer.push(operation);

    if (this.buffer.length >= this.batchSize) {
      await this.flush();
    }
  }

  async flush(): Promise<void> {
    if (this.isFlushing) return;
    this.isFlushing = true;

    try {
      const redisOps = await this.readFromStream();
      const allOps = [...this.buffer, ...redisOps];
      this.buffer = [];

      if (allOps.length === 0) return;

      const inserts = allOps.filter((op) => op.type === 'insert');
      const updates = allOps.filter((op) => op.type === 'update_status');
      const callbacks = allOps.filter((op) => op.type === 'update_callback');

      if (inserts.length > 0) {
        await this.batchInsert(inserts);
      }

      if (updates.length > 0) {
        await this.batchUpdateStatus(updates);
      }

      if (callbacks.length > 0) {
        await this.batchCallback(callbacks);
      }

      if (redisOps.length > 0 && this.redis) {
        try {
          const lastId = redisOps[redisOps.length - 1].payload._stream_id;
          if (lastId) {
            await this.redis.xack(this.streamKey, this.consumerGroup, lastId);
          }
        } catch (err) {
          logger.warn('Failed to ack stream entries', { error: err });
        }
      }

      logger.debug('Batch flush completed', {
        inserts: inserts.length,
        updates: updates.length,
        callbacks: callbacks.length,
      });
    } catch (err) {
      logger.error('Batch flush error', { error: err });
    } finally {
      this.isFlushing = false;
    }
  }

  private async readFromStream(): Promise<BufferedOperation[]> {
    if (!this.redisAvailable || !this.redis) return [];

    try {
      const results = await this.redis.xreadgroup(
        'GROUP', this.consumerGroup, this.consumerName,
        'COUNT', this.batchSize,
        'BLOCK', 0,
        'STREAMS', this.streamKey,
        '>'
      ) as any[];

      if (!results || results.length === 0) return [];

      const operations: BufferedOperation[] = [];
      const streamData = results[0] as any[];
      if (!streamData || streamData.length < 2) return [];

      const messages = streamData[1] as Array<[string, string[]]>;
      for (const [id, fields] of messages) {
        const fieldMap: Record<string, string> = {};
        for (let i = 0; i < fields.length; i += 2) {
          fieldMap[fields[i]] = fields[i + 1];
        }

        operations.push({
          type: fieldMap.type as BufferedOperation['type'],
          payload: { ...JSON.parse(fieldMap.payload), _stream_id: id },
          timestamp: parseInt(fieldMap.timestamp) || Date.now(),
        });
      }

      return operations;
    } catch (err) {
      logger.warn('Failed to read from Redis Stream', { error: err });
      this.redisAvailable = false;
      return [];
    }
  }

  private async batchInsert(operations: BufferedOperation[]): Promise<void> {
    const values = operations.map((op) => {
      const p = op.payload;
      return `('${p.delivery_id}', '${p.tenant_id}', '${p.notification_type}', '${p.channel}', '${p.provider}', '${p.recipient}', '${p.status}', '${p.priority}', ${p.metadata ? `'${JSON.stringify(p.metadata).replace(/'/g, "''")}'` : 'NULL'})`;
    });

    const sql = `
      INSERT INTO delivery_logs (delivery_id, tenant_id, notification_type, channel, provider, recipient, status, priority, metadata)
      VALUES ${values.join(', ')}
      ON CONFLICT (delivery_id, channel) DO NOTHING
    `;

    try {
      await db.query(sql);
    } catch (err) {
      logger.error('Batch insert failed, falling back to individual inserts', { error: err });
      for (const op of operations) {
        try {
          await db.withTenantContext(op.payload.tenant_id, async () => {
            await db.query(
              `INSERT INTO delivery_logs (delivery_id, tenant_id, notification_type, channel, provider, recipient, status, priority, metadata)
               VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
               ON CONFLICT (delivery_id, channel) DO NOTHING`,
              [
                op.payload.delivery_id,
                op.payload.tenant_id,
                op.payload.notification_type,
                op.payload.channel,
                op.payload.provider,
                op.payload.recipient,
                op.payload.status,
                op.payload.priority,
                op.payload.metadata,
              ]
            );
          });
        } catch (innerErr) {
          logger.error('Individual insert also failed', { deliveryId: op.payload.delivery_id, error: innerErr });
        }
      }
    }
  }

  private async batchUpdateStatus(operations: BufferedOperation[]): Promise<void> {
    for (const op of operations) {
      try {
        await db.withTenantContext(op.payload.tenant_id, async () => {
          await db.query(
            `UPDATE delivery_logs 
             SET status = $1, message_id = $2, error_message = $3, updated_at = NOW()
             WHERE delivery_id = $4 AND channel = $5`,
            [
              op.payload.status,
              op.payload.message_id,
              op.payload.error_message,
              op.payload.delivery_id,
              op.payload.channel,
            ]
          );
        });
      } catch (err) {
        logger.error('Batch update status failed for delivery', {
          deliveryId: op.payload.delivery_id,
          error: err,
        });
      }
    }
  }

  private async batchCallback(operations: BufferedOperation[]): Promise<void> {
    for (const op of operations) {
      try {
        await db.withTenantContext(op.payload.tenant_id, async () => {
          await db.query(
            `UPDATE delivery_logs 
             SET status = $1, updated_at = NOW(), metadata = COALESCE(metadata, '{}'::jsonb) || $3::jsonb
             WHERE message_id = $2`,
            [
              op.payload.status,
              op.payload.message_id,
              op.payload.metadata || {},
            ]
          );
        });
      } catch (err) {
        logger.error('Batch callback failed', {
          messageId: op.payload.message_id,
          error: err,
        });
      }
    }
  }

  public getBufferSize(): number {
    return this.buffer.length;
  }

  public isRedisAvailable(): boolean {
    return this.redisAvailable;
  }

  public async close(): Promise<void> {
    this.stop();
    if (this.redis) {
      await this.redis.disconnect();
    }
  }
}
