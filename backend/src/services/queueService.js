const Redis = require('ioredis');
const EventEmitter = require('events');
const crypto = require('crypto');
const redisConfig = require('../config/redis');

const QUEUE_STATUSES = {
  PENDING: 'pending',
  PROCESSING: 'processing',
  COMPLETED: 'completed',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  DELAYED: 'delayed'
};

const JOB_TYPES = {
  MEDIA_PROCESS: 'media_process',
  THUMBNAIL_GENERATE: 'thumbnail_generate',
  METADATA_EXTRACT: 'metadata_extract',
  DISTRIBUTION_PUSH: 'distribution_push'
};

class RedisQueueService extends EventEmitter {
  constructor(options = {}) {
    super();
    
    this.redisOptions = {
      ...redisConfig.connectionOptions,
      host: redisConfig.host,
      port: redisConfig.port,
      password: redisConfig.password || undefined,
      db: redisConfig.db
    };
    
    this.keyPrefix = redisConfig.keyPrefix || 'mediahub:';
    this.maxRetries = options.maxRetries || 3;
    this.retryDelay = options.retryDelay || 5000;
    this.concurrency = options.concurrency || 2;
    this.pollInterval = options.pollInterval || 1000;
    this.maxHistorySize = options.maxHistorySize || 1000;
    this.lockTTL = options.lockTTL || 30000;
    
    this.redis = null;
    this.isRunning = false;
    this.workers = new Map();
    this.activeWorkers = new Map();
    this.pollTimers = new Map();
    this.workerId = this.generateWorkerId();
    
    console.log(`[RedisQueueService] Worker ID: ${this.workerId}`);
  }

  generateWorkerId() {
    return `worker_${Date.now()}_${crypto.randomBytes(4).toString('hex')}`;
  }

  generateJobId() {
    return `job_${Date.now()}_${crypto.randomBytes(12).toString('hex')}`;
  }

  getQueueKey(queueName, type) {
    const keys = {
      pending: `${this.keyPrefix}queue:${queueName}:pending`,
      processing: `${this.keyPrefix}queue:${queueName}:processing`,
      completed: `${this.keyPrefix}queue:${queueName}:completed`,
      failed: `${this.keyPrefix}queue:${queueName}:failed`,
      delayed: `${this.keyPrefix}queue:${queueName}:delayed`
    };
    return keys[type] || null;
  }

  getJobKey(jobId) {
    return `${this.keyPrefix}job:${jobId}`;
  }

  getLockKey(jobId) {
    return `${this.keyPrefix}lock:${jobId}`;
  }

  getWorkerActiveKey(queueName) {
    return `${this.keyPrefix}worker:active:${queueName}:${this.workerId}`;
  }

  async initialize() {
    try {
      this.redis = new Redis(this.redisOptions);
      
      this.redis.on('connect', () => {
        console.log('[RedisQueueService] Redis connected');
      });
      
      this.redis.on('error', (error) => {
        console.error('[RedisQueueService] Redis error:', error);
      });
      
      this.redis.on('close', () => {
        console.warn('[RedisQueueService] Redis connection closed');
      });

      await this.redis.ping();
      console.log('[RedisQueueService] Redis connection verified');
      
      await this.recoverStuckJobs();
      
      this.isRunning = true;
      this.startPolling();
      
      console.log('[RedisQueueService] Initialized successfully');
      return this;
      
    } catch (error) {
      console.error('[RedisQueueService] Failed to initialize:', error);
      throw error;
    }
  }

  async shutdown() {
    this.isRunning = false;
    
    for (const [queueName, timer] of this.pollTimers) {
      clearTimeout(timer);
    }
    this.pollTimers.clear();
    
    for (const [queueName, activeJobs] of this.activeWorkers) {
      console.log(`[RedisQueueService] Waiting for ${activeJobs.size} active jobs in queue: ${queueName}`);
    }
    
    if (this.redis) {
      await this.redis.quit();
    }
    
    console.log('[RedisQueueService] Shutdown complete');
    return this;
  }

  async recoverStuckJobs() {
    console.log('[RedisQueueService] Recovering stuck jobs...');
    
    const queueKeys = await this.redis.keys(`${this.keyPrefix}queue:*:processing`);
    
    for (const processingKey of queueKeys) {
      const queueName = processingKey.match(/queue:([^:]+):processing/)?.[1];
      if (!queueName) continue;
      
      const pendingKey = this.getQueueKey(queueName, 'pending');
      const jobIds = await this.redis.zrange(processingKey, 0, -1);
      
      for (const jobId of jobIds) {
        const lockKey = this.getLockKey(jobId);
        const lockExists = await this.redis.exists(lockKey);
        
        if (!lockExists) {
          const job = await this.getJob(jobId);
          if (job && job.status === QUEUE_STATUSES.PROCESSING) {
            console.log(`[RedisQueueService] Recovering stuck job: ${jobId}`);
            
            const pipeline = this.redis.pipeline();
            pipeline.zrem(processingKey, jobId);
            
            if (job.retries < job.maxRetries) {
              job.retries = (job.retries || 0) + 1;
              job.status = QUEUE_STATUSES.PENDING;
              await this.saveJob(job);
              pipeline.zadd(pendingKey, job.priority || 0, jobId);
              console.log(`[RedisQueueService] Job ${jobId} returned to pending (retry ${job.retries}/${job.maxRetries})`);
            } else {
              job.status = QUEUE_STATUSES.FAILED;
              job.error = 'Job stuck and max retries exceeded';
              await this.saveJob(job);
              const failedKey = this.getQueueKey(queueName, 'failed');
              pipeline.zadd(failedKey, Date.now(), jobId);
              console.log(`[RedisQueueService] Job ${jobId} marked as failed`);
            }
            
            await pipeline.exec();
          }
        }
      }
    }
    
    console.log('[RedisQueueService] Stuck job recovery complete');
  }

  registerQueue(queueName, options = {}) {
    if (!this.workers.has(queueName)) {
      this.workers.set(queueName, {
        handler: null,
        concurrency: options.concurrency || this.concurrency,
        activeWorkers: 0
      });
      console.log(`[RedisQueueService] Queue registered: ${queueName}`);
    }
    return this;
  }

  registerWorker(queueName, handler) {
    if (!this.workers.has(queueName)) {
      this.registerQueue(queueName);
    }
    
    const queueConfig = this.workers.get(queueName);
    queueConfig.handler = handler;
    
    if (!this.activeWorkers.has(queueName)) {
      this.activeWorkers.set(queueName, new Set());
    }
    
    console.log(`[RedisQueueService] Worker registered for queue: ${queueName}`);
    return this;
  }

  async addJob(queueName, jobType, payload, options = {}) {
    if (!this.redis) {
      throw new Error('Queue service not initialized');
    }

    const jobId = options.jobId || this.generateJobId();
    const now = Date.now();
    
    const job = {
      id: jobId,
      type: jobType,
      queue: queueName,
      payload: JSON.stringify(payload),
      status: options.delay > 0 ? QUEUE_STATUSES.DELAYED : QUEUE_STATUSES.PENDING,
      priority: options.priority || 0,
      retries: 0,
      maxRetries: options.maxRetries || this.maxRetries,
      error: null,
      result: null,
      progress: 0,
      createdAt: now,
      startedAt: null,
      completedAt: null,
      delay: options.delay || 0,
      scheduledAt: options.delay > 0 ? now + options.delay : null,
      workerId: null
    };

    const pipeline = this.redis.pipeline();
    
    await this.saveJobToPipeline(pipeline, job);
    
    if (options.delay > 0) {
      const delayedKey = this.getQueueKey(queueName, 'delayed');
      pipeline.zadd(delayedKey, now + options.delay, jobId);
      console.log(`[RedisQueueService] Delayed job added: ${jobId} to queue: ${queueName}, delay: ${options.delay}ms`);
    } else {
      const pendingKey = this.getQueueKey(queueName, 'pending');
      pipeline.zadd(pendingKey, job.priority, jobId);
      console.log(`[RedisQueueService] Job added: ${jobId} to queue: ${queueName}`);
    }
    
    await pipeline.exec();
    
    this.emit('job:added', { ...job, payload: payload });
    
    return jobId;
  }

  async saveJobToPipeline(pipeline, job) {
    const jobKey = this.getJobKey(job.id);
    const jobData = {};
    
    for (const [key, value] of Object.entries(job)) {
      if (value !== null && value !== undefined) {
        jobData[key] = typeof value === 'object' ? JSON.stringify(value) : String(value);
      }
    }
    
    pipeline.hset(jobKey, jobData);
  }

  async saveJob(job) {
    const jobKey = this.getJobKey(job.id);
    const jobData = {};
    
    for (const [key, value] of Object.entries(job)) {
      if (value !== null && value !== undefined) {
        jobData[key] = typeof value === 'object' ? JSON.stringify(value) : String(value);
      }
    }
    
    await this.redis.hset(jobKey, jobData);
  }

  async getJob(jobId) {
    if (!this.redis) return null;
    
    const jobKey = this.getJobKey(jobId);
    const jobData = await this.redis.hgetall(jobKey);
    
    if (!jobData || Object.keys(jobData).length === 0) {
      return null;
    }
    
    return this.parseJobData(jobData);
  }

  parseJobData(jobData) {
    const job = {};
    
    const numberFields = ['priority', 'retries', 'maxRetries', 'progress', 
                          'createdAt', 'startedAt', 'completedAt', 'delay', 'scheduledAt'];
    const jsonFields = ['payload', 'result'];
    
    for (const [key, value] of Object.entries(jobData)) {
      if (numberFields.includes(key)) {
        job[key] = parseInt(value, 10);
      } else if (jsonFields.includes(key)) {
        try {
          job[key] = JSON.parse(value);
        } catch {
          job[key] = value;
        }
      } else {
        job[key] = value;
      }
    }
    
    return job;
  }

  async getJobStatus(jobId) {
    const job = await this.getJob(jobId);
    return job ? {
      id: job.id,
      status: job.status,
      progress: job.progress || 0,
      error: job.error,
      result: job.result
    } : null;
  }

  async cancelJob(jobId) {
    const job = await this.getJob(jobId);
    
    if (!job) {
      return false;
    }
    
    if (job.status === QUEUE_STATUSES.PENDING || job.status === QUEUE_STATUSES.DELAYED) {
      job.status = QUEUE_STATUSES.CANCELLED;
      
      const pipeline = this.redis.pipeline();
      
      const pendingKey = this.getQueueKey(job.queue, 'pending');
      const delayedKey = this.getQueueKey(job.queue, 'delayed');
      pipeline.zrem(pendingKey, jobId);
      pipeline.zrem(delayedKey, jobId);
      
      await this.saveJobToPipeline(pipeline, job);
      await pipeline.exec();
      
      this.emit('job:cancelled', job);
      console.log(`[RedisQueueService] Job cancelled: ${jobId}`);
      
      return true;
    }
    
    return false;
  }

  async acquireLock(jobId, ttl = this.lockTTL) {
    const lockKey = this.getLockKey(jobId);
    const lockValue = `${this.workerId}:${Date.now()}`;
    
    const result = await this.redis.set(lockKey, lockValue, 'PX', ttl, 'NX');
    return result === 'OK';
  }

  async releaseLock(jobId) {
    const lockKey = this.getLockKey(jobId);
    await this.redis.del(lockKey);
  }

  startPolling() {
    if (!this.isRunning) return;
    
    for (const [queueName, config] of this.workers) {
      if (config.handler) {
        this.pollQueue(queueName);
      }
    }
  }

  async pollQueue(queueName) {
    if (!this.isRunning || !this.redis) return;
    
    const queueConfig = this.workers.get(queueName);
    if (!queueConfig || !queueConfig.handler) return;
    
    const activeJobs = this.activeWorkers.get(queueName) || new Set();
    
    try {
      await this.processDelayedJobs(queueName);
      
      while (activeJobs.size < queueConfig.concurrency) {
        const jobId = await this.getNextJob(queueName);
        
        if (!jobId) break;
        
        const lockAcquired = await this.acquireLock(jobId);
        if (!lockAcquired) {
          continue;
        }
        
        const job = await this.getJob(jobId);
        if (!job || job.status !== QUEUE_STATUSES.PENDING) {
          await this.releaseLock(jobId);
          continue;
        }
        
        activeJobs.add(jobId);
        this.processJob(queueName, job, queueConfig.handler, activeJobs);
      }
      
    } catch (error) {
      console.error(`[RedisQueueService] Error polling queue ${queueName}:`, error);
    } finally {
      if (this.isRunning) {
        const timer = setTimeout(() => this.pollQueue(queueName), this.pollInterval);
        this.pollTimers.set(queueName, timer);
      }
    }
  }

  async processDelayedJobs(queueName) {
    const delayedKey = this.getQueueKey(queueName, 'delayed');
    const pendingKey = this.getQueueKey(queueName, 'pending');
    const now = Date.now();
    
    const delayedJobs = await this.redis.zrangebyscore(delayedKey, '-inf', now);
    
    if (delayedJobs.length === 0) return;
    
    const pipeline = this.redis.pipeline();
    
    for (const jobId of delayedJobs) {
      const job = await this.getJob(jobId);
      if (job && job.status === QUEUE_STATUSES.DELAYED) {
        job.status = QUEUE_STATUSES.PENDING;
        await this.saveJobToPipeline(pipeline, job);
        
        pipeline.zrem(delayedKey, jobId);
        pipeline.zadd(pendingKey, job.priority || 0, jobId);
        
        console.log(`[RedisQueueService] Delayed job ready: ${jobId}`);
        this.emit('job:ready', jobId);
      }
    }
    
    await pipeline.exec();
  }

  async getNextJob(queueName) {
    const pendingKey = this.getQueueKey(queueName, 'pending');
    const processingKey = this.getQueueKey(queueName, 'processing');
    
    const result = await this.redis.zpopmax(pendingKey, 1);
    
    if (result && result.length >= 2) {
      const jobId = result[0];
      await this.redis.zadd(processingKey, Date.now(), jobId);
      return jobId;
    }
    
    return null;
  }

  async processJob(queueName, job, handler, activeJobs) {
    const jobId = job.id;
    
    try {
      job.status = QUEUE_STATUSES.PROCESSING;
      job.startedAt = Date.now();
      job.workerId = this.workerId;
      
      await this.saveJob(job);
      
      this.emit('job:start', job);
      console.log(`[RedisQueueService] Processing job: ${jobId}, type: ${job.type}, queue: ${queueName}`);
      
      const updateProgress = async (progress) => {
        job.progress = progress;
        await this.saveJob(job);
        this.emit('job:progress', job, progress);
      };
      
      const result = await handler(job, { updateProgress });
      
      job.status = QUEUE_STATUSES.COMPLETED;
      job.completedAt = Date.now();
      job.result = result;
      job.progress = 100;
      
      await this.saveJob(job);
      
      const processingKey = this.getQueueKey(queueName, 'processing');
      const completedKey = this.getQueueKey(queueName, 'completed');
      
      const pipeline = this.redis.pipeline();
      pipeline.zrem(processingKey, jobId);
      pipeline.zadd(completedKey, Date.now(), jobId);
      await pipeline.exec();
      
      this.emit('job:completed', job, result);
      console.log(`[RedisQueueService] Job completed: ${jobId}`);
      
    } catch (error) {
      console.error(`[RedisQueueService] Job error: ${jobId}`, error);
      
      job.error = error.message;
      job.retries = (job.retries || 0) + 1;
      
      const processingKey = this.getQueueKey(queueName, 'processing');
      const pendingKey = this.getQueueKey(queueName, 'pending');
      const failedKey = this.getQueueKey(queueName, 'failed');
      
      const pipeline = this.redis.pipeline();
      pipeline.zrem(processingKey, jobId);
      
      if (job.retries < job.maxRetries) {
        job.status = QUEUE_STATUSES.PENDING;
        await this.saveJobToPipeline(pipeline, job);
        pipeline.zadd(pendingKey, job.priority || 0, jobId);
        
        this.emit('job:retry', job, error);
        console.log(`[RedisQueueService] Job retry scheduled: ${jobId}, attempt: ${job.retries}/${job.maxRetries}`);
      } else {
        job.status = QUEUE_STATUSES.FAILED;
        job.completedAt = Date.now();
        await this.saveJobToPipeline(pipeline, job);
        pipeline.zadd(failedKey, Date.now(), jobId);
        
        this.emit('job:failed', job, error);
        console.error(`[RedisQueueService] Job failed: ${jobId}, error: ${error.message}`);
      }
      
      await pipeline.exec();
      
    } finally {
      await this.releaseLock(jobId);
      activeJobs.delete(jobId);
    }
  }

  async getQueueStats(queueName) {
    if (!this.redis) return null;
    
    const pendingKey = this.getQueueKey(queueName, 'pending');
    const processingKey = this.getQueueKey(queueName, 'processing');
    const completedKey = this.getQueueKey(queueName, 'completed');
    const failedKey = this.getQueueKey(queueName, 'failed');
    const delayedKey = this.getQueueKey(queueName, 'delayed');
    
    const [pending, processing, completed, failed, delayed] = await Promise.all([
      this.redis.zcard(pendingKey),
      this.redis.zcard(processingKey),
      this.redis.zcard(completedKey),
      this.redis.zcard(failedKey),
      this.redis.zcard(delayedKey)
    ]);
    
    const queueConfig = this.workers.get(queueName);
    const activeJobs = this.activeWorkers.get(queueName);
    
    return {
      name: queueName,
      pending: pending,
      processing: processing,
      completed: completed,
      failed: failed,
      delayed: delayed,
      activeWorkers: activeJobs ? activeJobs.size : 0,
      concurrency: queueConfig?.concurrency || this.concurrency
    };
  }

  async getAllStats() {
    const stats = {};
    
    for (const queueName of this.workers.keys()) {
      stats[queueName] = await this.getQueueStats(queueName);
    }
    
    return stats;
  }

  async clearCompleted(queueName) {
    const completedKey = this.getQueueKey(queueName, 'completed');
    const jobIds = await this.redis.zrange(completedKey, 0, -1);
    
    const pipeline = this.redis.pipeline();
    
    for (const jobId of jobIds) {
      const jobKey = this.getJobKey(jobId);
      pipeline.del(jobKey);
    }
    
    pipeline.del(completedKey);
    await pipeline.exec();
    
    console.log(`[RedisQueueService] Cleared ${jobIds.length} completed jobs from queue: ${queueName}`);
  }

  async clearFailed(queueName) {
    const failedKey = this.getQueueKey(queueName, 'failed');
    const jobIds = await this.redis.zrange(failedKey, 0, -1);
    
    const pipeline = this.redis.pipeline();
    
    for (const jobId of jobIds) {
      const jobKey = this.getJobKey(jobId);
      pipeline.del(jobKey);
    }
    
    pipeline.del(failedKey);
    await pipeline.exec();
    
    console.log(`[RedisQueueService] Cleared ${jobIds.length} failed jobs from queue: ${queueName}`);
  }

  async retryFailedJobs(queueName) {
    const failedKey = this.getQueueKey(queueName, 'failed');
    const pendingKey = this.getQueueKey(queueName, 'pending');
    const jobIds = await this.redis.zrange(failedKey, 0, -1);
    
    if (jobIds.length === 0) return 0;
    
    const pipeline = this.redis.pipeline();
    let retriedCount = 0;
    
    for (const jobId of jobIds) {
      const job = await this.getJob(jobId);
      if (job) {
        job.status = QUEUE_STATUSES.PENDING;
        job.retries = 0;
        job.error = null;
        
        await this.saveJobToPipeline(pipeline, job);
        
        pipeline.zrem(failedKey, jobId);
        pipeline.zadd(pendingKey, job.priority || 0, jobId);
        
        retriedCount++;
      }
    }
    
    if (retriedCount > 0) {
      await pipeline.exec();
      console.log(`[RedisQueueService] Retried ${retriedCount} failed jobs in queue: ${queueName}`);
    }
    
    return retriedCount;
  }

  async getPendingJobs(queueName) {
    const pendingKey = this.getQueueKey(queueName, 'pending');
    const jobIds = await this.redis.zrange(pendingKey, 0, -1);
    
    const jobs = [];
    for (const jobId of jobIds) {
      const job = await this.getJob(jobId);
      if (job) {
        jobs.push(job);
      }
    }
    
    return jobs;
  }

  async getFailedJobs(queueName) {
    const failedKey = this.getQueueKey(queueName, 'failed');
    const jobIds = await this.redis.zrange(failedKey, 0, -1);
    
    const jobs = [];
    for (const jobId of jobIds) {
      const job = await this.getJob(jobId);
      if (job) {
        jobs.push(job);
      }
    }
    
    return jobs;
  }
}

const QUEUES = {
  MEDIA_PROCESSING: 'media_processing',
  DISTRIBUTION: 'distribution',
  THUMBNAIL: 'thumbnail'
};

const queueService = new RedisQueueService({
  concurrency: 2,
  maxRetries: 3,
  retryDelay: 5000,
  pollInterval: 1000,
  lockTTL: 30000
});

module.exports = {
  queueService,
  RedisQueueService,
  QUEUE_STATUSES,
  JOB_TYPES,
  QUEUES
};
