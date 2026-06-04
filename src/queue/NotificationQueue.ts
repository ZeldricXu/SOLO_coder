import { Queue, Worker, QueueEvents } from 'bullmq';
import { NotificationRequest, ChannelType, QueueJobData, DeliveryStatus } from '../types';
import { config } from '../config';
import { logger } from '../utils/logger';
import { AdapterManager } from '../adapters/AdapterManager';
import { v4 as uuidv4 } from 'uuid';
import { db } from '../db';
import Redis from 'ioredis';

export class NotificationQueue {
  private queue: Queue;
  private dlq: Queue;
  private worker!: Worker;
  private queueEvents: QueueEvents;
  private adapterManager: AdapterManager;
  private redis: Redis;
  private static instance: NotificationQueue;

  private constructor() {
    this.redis = new Redis(config.redis.url);
    const connection = this.redis as any;
    this.queue = new Queue('notifications', { connection });
    this.dlq = new Queue('notifications-dlq', { connection });
    this.queueEvents = new QueueEvents('notifications', { connection });
    this.adapterManager = AdapterManager.getInstance();
    this.setupWorker();
    this.setupEventListeners();
  }

  public static getInstance(): NotificationQueue {
    if (!NotificationQueue.instance) {
      NotificationQueue.instance = new NotificationQueue();
    }
    return NotificationQueue.instance;
  }

  private setupWorker(): void {
    const connection = this.redis as any;
    this.worker = new Worker('notifications', async (job) => {
      const data = job.data as QueueJobData;
      logger.info('Processing notification job', {
        jobId: job.id,
        deliveryId: data.delivery_id,
        channel: data.channel,
        attempt: job.attemptsMade + 1,
      });

      try {
        await this.processJob(data);
      } catch (error: any) {
        logger.error('Job processing failed', { error: error.message });
        throw error;
      }
    }, {
      connection,
      concurrency: 10,
    });
  }

  private setupEventListeners(): void {
    this.worker.on('completed', (job) => {
      logger.info('Job completed', { jobId: job.id });
    });

    this.worker.on('failed', async (job, err) => {
      if (job) {
        logger.error('Job failed', { jobId: job.id, error: err.message });
        
        if (job.attemptsMade >= (job.opts.attempts || 3) - 1) {
          await this.moveToDLQ(job.data, err);
        }
      }
    });
  }

  private async processJob(data: QueueJobData): Promise<void> {
    const { notification, delivery_id, channel } = data;
    
    const adapter = this.adapterManager.getAdapter(channel);
    if (!adapter) {
      throw new Error(`No adapter found for channel: ${channel}`);
    }

    await this.updateDeliveryStatus(delivery_id, 'sent', channel);

    const result = await adapter!.send(notification, notification.recipient);

    await this.updateDeliveryStatus(
      delivery_id,
      result.status,
      channel,
      result.message_id,
      result.error
    );

    if (result.status !== 'sent') {
      throw new Error(result.error || 'Send failed');
    }
  }

  public async enqueue(
    notification: NotificationRequest,
    deliveryId: string,
    channel: ChannelType,
    delay?: number
  ): Promise<string> {
    const jobData: QueueJobData = {
      notification,
      delivery_id: deliveryId,
      channel,
      attempt: 0,
      scheduled_at: new Date(),
    };

    const groupKey = notification.recipient.user_id || notification.recipient.email || notification.recipient.phone;
    const job = await this.queue.add(
      `notification:${channel}`,
      jobData,
      {
        delay,
        ...config.queue.defaultJobOptions,
        jobId: `${deliveryId}-${channel}-${uuidv4()}`,
        group: groupKey,
      }
    );

    logger.info('Job enqueued', {
      jobId: job.id,
      deliveryId,
      channel,
    });

    return job.id!;
  }

  private async moveToDLQ(jobData: any, error: Error): Promise<void> {
    await this.dlq.add('failed-notification', {
      ...jobData,
      error: {
        message: error.message,
        stack: error.stack,
      },
      failed_at: new Date().toISOString(),
    });

    logger.warn('Job moved to DLQ', { deliveryId: jobData.delivery_id });
  }

  private async updateDeliveryStatus(
    deliveryId: string,
    status: DeliveryStatus,
    channel: ChannelType,
    messageId?: string,
    error?: string
  ): Promise<void> {
    try {
      await db.query(
        `UPDATE delivery_logs 
         SET status = $1, message_id = $2, error_message = $3, updated_at = NOW()
         WHERE delivery_id = $4 AND channel = $5`,
        [status, messageId, error, deliveryId, channel]
      );
    } catch (err) {
        logger.error('Failed to update delivery status', err);
      }
  }

  public async getDlqJobs(limit: number = 100): Promise<any[]> {
    const jobs = await this.dlq.getJobs(['failed'], 0, limit);
    return jobs.map(job => ({
      id: job.id,
      data: job.data,
      failedReason: job.failedReason,
      failedAt: (job as any).failedAt || (job as any).processedOn,
    }));
  }

  public async retryDlqJob(jobId: string): Promise<void> {
    const job = await this.dlq.getJob(jobId);
    if (!job) {
      throw new Error('Job not found in DLQ');
    }

    const data = job.data;
    await this.enqueue(
      data.notification, data.delivery_id, data.channel);
    await job.remove();

    logger.info('Job retried from DLQ', { jobId });
  }

  public async getQueueStats(): Promise<any> {
    const [waiting, active, completed, failed] = await Promise.all([
      this.queue.getWaitingCount(),
      this.queue.getActiveCount(),
      this.queue.getCompletedCount(),
      this.queue.getFailedCount(),
    ]);

    return {
      waiting,
      active,
      completed,
      failed,
    };
  }

  public async close(): Promise<void> {
    await this.queue.close();
    await this.dlq.close();
    await this.worker.close();
    await this.queueEvents.close();
    await this.redis.disconnect();
  }
}
