const { EventEmitter } = require('events');
const config = require('../config/config');
const logger = require('../utils/logger');
const redisService = require('./redisService');

const TASK_STATUSES = {
  PENDING: 'pending',
  PROCESSING: 'processing',
  COMPLETED: 'completed',
  FAILED: 'failed',
  RETRYING: 'retrying'
};

const TASK_TYPES = {
  SAVE_DOCUMENT: 'save_document',
  CREATE_VERSION: 'create_version',
  SAVE_AND_VERSION: 'save_and_version',
  UPDATE_INDEX: 'update_index'
};

const REDIS_KEYS = {
  TASK_QUEUE: 'task:queue',
  TASK_PROCESSING: 'task:processing',
  TASK_STORAGE: 'task:storage',
  TASK_LOCK: 'task:lock',
  TASK_STATS: 'task:stats',
  TASK_ID_COUNTER: 'task:id:counter'
};

class RedisTaskQueue extends EventEmitter {
  constructor(options = {}) {
    super();
    
    this.maxWorkers = options.maxWorkers || config.asyncSave.maxWorkers;
    this.maxQueueSize = options.queueSize || config.asyncSave.queueSize;
    this.retryDelay = options.retryDelay || config.asyncSave.retryDelay;
    this.maxRetries = options.maxRetries || config.asyncSave.maxRetries;
    this.taskLockTTL = options.taskLockTTL || config.asyncSave.taskLockTTL;
    
    this.processingWorkers = 0;
    this.isRunning = false;
    this.isInitialized = false;
    
    this.workerTimers = new Map();
    this.stats = {
      total: 0,
      completed: 0,
      failed: 0,
      retried: 0
    };
  }

  async initialize() {
    if (this.isInitialized) {
      return true;
    }

    try {
      await redisService.connect();
      this.isRunning = true;
      this.isInitialized = true;
      
      await this.recoverStaleTasks();
      
      this.startWorkers();
      
      logger.info('Redis任务队列已初始化', {
        maxWorkers: this.maxWorkers,
        maxQueueSize: this.maxQueueSize
      });
      
      return true;
    } catch (error) {
      logger.error('Redis任务队列初始化失败', { error: error.message });
      return false;
    }
  }

  generateTaskId() {
    return `task_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  async add(taskType, taskData, options = {}) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    try {
      const queueSize = await redisService.llen(REDIS_KEYS.TASK_QUEUE);
      if (queueSize >= this.maxQueueSize) {
        const error = new Error('任务队列已满');
        logger.error('添加任务失败: 队列已满', { taskType, queueSize });
        this.emit('error', error, { taskType, taskData });
        return null;
      }

      const taskId = this.generateTaskId();
      const priority = options.priority || 0;
      const maxRetries = options.maxRetries || this.maxRetries;

      const task = {
        id: taskId,
        type: taskType,
        data: taskData,
        status: TASK_STATUSES.PENDING,
        priority: priority,
        retries: 0,
        maxRetries: maxRetries,
        createdAt: Date.now(),
        startedAt: null,
        completedAt: null,
        error: null
      };

      const taskKey = `${REDIS_KEYS.TASK_STORAGE}:${taskId}`;
      await redisService.hset(taskKey, 'data', JSON.stringify(task));
      
      const score = priority * -1000 + (Date.now() / 1000);
      await redisService.zadd(REDIS_KEYS.TASK_QUEUE, score, taskId);

      this.stats.total++;
      
      logger.debug(`任务已添加到Redis队列: taskId=${taskId}, type=${taskType}`);
      this.emit('task:added', task);

      return taskId;
    } catch (error) {
      logger.error('添加任务到Redis失败', { error: error.message, taskType });
      return null;
    }
  }

  async recoverStaleTasks() {
    try {
      const processingTasks = await redisService.hgetall(REDIS_KEYS.TASK_PROCESSING);
      
      for (const [taskId, lockExpireTime] of Object.entries(processingTasks)) {
        const expireTime = parseInt(lockExpireTime);
        const now = Date.now();
        
        if (now > expireTime) {
          logger.warn(`发现超时任务，恢复到队列: taskId=${taskId}`);
          
          const taskKey = `${REDIS_KEYS.TASK_STORAGE}:${taskId}`;
          const taskData = await redisService.hget(taskKey, 'data');
          
          if (taskData) {
            const task = JSON.parse(taskData);
            
            if (task.retries < task.maxRetries) {
              task.retries++;
              task.status = TASK_STATUSES.RETRYING;
              
              await redisService.hset(taskKey, 'data', JSON.stringify(task));
              
              const score = task.priority * -1000 + (Date.now() / 1000);
              await redisService.zadd(REDIS_KEYS.TASK_QUEUE, score, taskId);
              
              this.stats.retried++;
              logger.info(`超时任务已恢复: taskId=${taskId}, retry=${task.retries}/${task.maxRetries}`);
            } else {
              task.status = TASK_STATUSES.FAILED;
              task.error = { message: '任务执行超时，已达到最大重试次数' };
              await redisService.hset(taskKey, 'data', JSON.stringify(task));
              
              this.stats.failed++;
              logger.error(`超时任务已标记为失败: taskId=${taskId}`);
            }
            
            await redisService.hdel(REDIS_KEYS.TASK_PROCESSING, taskId);
          }
        }
      }
    } catch (error) {
      logger.error('恢复超时任务失败', { error: error.message });
    }
  }

  startWorkers() {
    for (let i = 0; i < this.maxWorkers; i++) {
      this.runWorker(i);
    }
    logger.info(`已启动 ${this.maxWorkers} 个Worker`);
  }

  async runWorker(workerId) {
    while (this.isRunning) {
      try {
        const task = await this.fetchNextTask();
        
        if (task) {
          this.processingWorkers++;
          await this.processTask(task);
          this.processingWorkers--;
        } else {
          await this.sleep(100);
        }
      } catch (error) {
        logger.error(`Worker ${workerId} 执行失败`, { error: error.message });
        await this.sleep(1000);
      }
    }
  }

  async fetchNextTask() {
    try {
      const results = await redisService.zrange(REDIS_KEYS.TASK_QUEUE, 0, 0);
      
      if (!results || results.length === 0) {
        return null;
      }

      const taskId = results[0];
      
      const lockKey = `${REDIS_KEYS.TASK_LOCK}:${taskId}`;
      const lockAcquired = await redisService.setnx(lockKey, '1');
      
      if (lockAcquired !== 1) {
        await redisService.zrem(REDIS_KEYS.TASK_QUEUE, taskId);
        return null;
      }

      await redisService.expire(lockKey, 30);

      const removed = await redisService.zrem(REDIS_KEYS.TASK_QUEUE, taskId);
      
      if (removed !== 1) {
        await redisService.del(lockKey);
        return null;
      }

      const taskKey = `${REDIS_KEYS.TASK_STORAGE}:${taskId}`;
      const taskData = await redisService.hget(taskKey, 'data');
      
      if (!taskData) {
        await redisService.del(lockKey);
        return null;
      }

      const task = JSON.parse(taskData);
      task.startedAt = Date.now();
      task.status = TASK_STATUSES.PROCESSING;

      await redisService.hset(taskKey, 'data', JSON.stringify(task));
      
      const lockExpireTime = Date.now() + this.taskLockTTL;
      await redisService.hset(REDIS_KEYS.TASK_PROCESSING, taskId, lockExpireTime.toString());

      return task;
    } catch (error) {
      logger.error('获取下一个任务失败', { error: error.message });
      return null;
    }
  }

  async processTask(task) {
    const taskKey = `${REDIS_KEYS.TASK_STORAGE}:${task.id}`;
    const lockKey = `${REDIS_KEYS.TASK_LOCK}:${task.id}`;

    try {
      this.emit('task:start', task);
      logger.debug(`开始执行任务: taskId=${task.id}, type=${task.type}`);

      let result;
      
      switch (task.type) {
        case TASK_TYPES.SAVE_DOCUMENT:
          result = await this.handleSaveDocument(task.data);
          break;
          
        case TASK_TYPES.CREATE_VERSION:
          result = await this.handleCreateVersion(task.data);
          break;
          
        case TASK_TYPES.SAVE_AND_VERSION:
          result = await this.handleSaveAndVersion(task.data);
          break;
          
        case TASK_TYPES.UPDATE_INDEX:
          result = await this.handleUpdateIndex(task.data);
          break;
          
        default:
          throw new Error(`未知的任务类型: ${task.type}`);
      }

      task.status = TASK_STATUSES.COMPLETED;
      task.completedAt = Date.now();
      task.result = result;
      
      this.stats.completed++;
      
      logger.debug(`任务执行成功: taskId=${task.id}, type=${task.type}`);
      this.emit('task:completed', task);

    } catch (error) {
      task.retries++;
      task.error = {
        message: error.message,
        stack: error.stack
      };

      if (task.retries < task.maxRetries) {
        task.status = TASK_STATUSES.RETRYING;
        this.stats.retried++;
        
        logger.warn(`任务执行失败，准备重试: taskId=${task.id}, type=${task.type}, retry=${task.retries}/${task.maxRetries}`);
        this.emit('task:retry', task, error);
        
        const delay = this.retryDelay * task.retries;
        
        setTimeout(async () => {
          try {
            const score = task.priority * -1000 + (Date.now() / 1000);
            await redisService.zadd(REDIS_KEYS.TASK_QUEUE, score, task.id);
            await redisService.hset(taskKey, 'data', JSON.stringify(task));
            await redisService.hdel(REDIS_KEYS.TASK_PROCESSING, task.id);
            await redisService.del(lockKey);
          } catch (err) {
            logger.error('任务重试调度失败', { error: err.message, taskId: task.id });
          }
        }, delay);
        
        return;
      } else {
        task.status = TASK_STATUSES.FAILED;
        task.completedAt = Date.now();
        
        this.stats.failed++;
        
        logger.error(`任务执行失败，已达到最大重试次数: taskId=${task.id}, type=${task.type}`, { error: error.message });
        this.emit('task:failed', task, error);
      }
    }

    try {
      await redisService.hset(taskKey, 'data', JSON.stringify(task));
      await redisService.hdel(REDIS_KEYS.TASK_PROCESSING, task.id);
      await redisService.del(lockKey);
    } catch (error) {
      logger.error('更新任务状态失败', { error: error.message, taskId: task.id });
    }
  }

  async handleSaveDocument(data) {
    const { documentService, docId, user, content, title, category, tags } = data;
    
    if (!documentService) {
      throw new Error('documentService 未提供');
    }

    const result = await documentService.updateDocument(
      docId,
      user,
      content,
      '',
      title,
      category,
      tags
    );

    if (!result.success) {
      throw new Error(result.error || '文档保存失败');
    }

    return result.data;
  }

  async handleCreateVersion(data) {
    const { versionService, docId, user, content, changeDesc } = data;
    
    if (!versionService) {
      throw new Error('versionService 未提供');
    }

    const result = await versionService.createVersion(
      docId,
      user,
      content,
      changeDesc
    );

    if (!result.success) {
      throw new Error(result.error || '版本创建失败');
    }

    return result.data;
  }

  async handleSaveAndVersion(data) {
    const { documentService, versionService, docId, user, content, changeDesc, title, category, tags } = data;
    
    if (!documentService || !versionService) {
      throw new Error('必要的服务未提供');
    }

    const saveResult = await this.handleSaveDocument({
      documentService,
      docId,
      user,
      content,
      title,
      category,
      tags
    });

    const versionResult = await this.handleCreateVersion({
      versionService,
      docId,
      user,
      content,
      changeDesc
    });

    return {
      document: saveResult,
      version: versionResult
    };
  }

  async handleUpdateIndex(data) {
    const { searchService, docId, document } = data;
    
    if (!searchService) {
      throw new Error('searchService 未提供');
    }

    logger.debug(`索引更新任务: docId=${docId}`);
    return { docId, updated: true };
  }

  async getTask(taskId) {
    const taskKey = `${REDIS_KEYS.TASK_STORAGE}:${taskId}`;
    const taskData = await redisService.hget(taskKey, 'data');
    
    if (!taskData) {
      return null;
    }

    return JSON.parse(taskData);
  }

  async getTaskStatus(taskId) {
    const task = await this.getTask(taskId);
    
    if (!task) {
      return null;
    }
    
    return {
      id: task.id,
      type: task.type,
      status: task.status,
      retries: task.retries,
      maxRetries: task.maxRetries,
      createdAt: task.createdAt,
      startedAt: task.startedAt,
      completedAt: task.completedAt,
      error: task.error,
      duration: task.completedAt && task.startedAt 
        ? task.completedAt - task.startedAt 
        : null
    };
  }

  async waitForTask(taskId, timeout = 30000) {
    return new Promise(async (resolve, reject) => {
      const startTime = Date.now();
      const checkInterval = 100;

      const checkTask = async () => {
        const status = await this.getTaskStatus(taskId);
        
        if (!status) {
          reject(new Error('任务不存在'));
          return;
        }

        if (status.status === TASK_STATUSES.COMPLETED || status.status === TASK_STATUSES.FAILED) {
          resolve(status);
          return;
        }

        if (Date.now() - startTime > timeout) {
          reject(new Error('任务执行超时'));
          return;
        }

        setTimeout(checkTask, checkInterval);
      };

      checkTask();
    });
  }

  async getPendingCount() {
    return redisService.zcard(REDIS_KEYS.TASK_QUEUE);
  }

  async getProcessingCount() {
    const processing = await redisService.hgetall(REDIS_KEYS.TASK_PROCESSING);
    return Object.keys(processing).length;
  }

  async getStats() {
    const pendingCount = await this.getPendingCount();
    const processingCount = await this.getProcessingCount();

    return {
      ...this.stats,
      queueSize: pendingCount,
      processingCount: processingCount,
      maxWorkers: this.maxWorkers,
      maxQueueSize: this.maxQueueSize
    };
  }

  async pause() {
    this.isRunning = false;
    logger.info('Redis任务队列已暂停');
    this.emit('queue:paused');
  }

  async resume() {
    this.isRunning = true;
    logger.info('Redis任务队列已恢复');
    this.emit('queue:resumed');
    this.startWorkers();
  }

  async shutdown() {
    this.isRunning = false;
    
    for (const [workerId, timer] of this.workerTimers) {
      clearInterval(timer);
    }
    this.workerTimers.clear();

    logger.info('Redis任务队列已关闭');
    this.emit('queue:shutdown');
  }

  async clear() {
    await redisService.del(REDIS_KEYS.TASK_QUEUE);
    await redisService.del(REDIS_KEYS.TASK_PROCESSING);
    
    const taskKeys = await redisService.keys(`${REDIS_KEYS.TASK_STORAGE}:*`);
    for (const key of taskKeys) {
      await redisService.del(key);
    }
    
    this.stats = {
      total: 0,
      completed: 0,
      failed: 0,
      retried: 0
    };

    logger.info('Redis任务队列已清空');
  }

  sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}

const redisTaskQueue = new RedisTaskQueue();

module.exports = {
  RedisTaskQueue,
  redisTaskQueue,
  TASK_STATUSES,
  TASK_TYPES,
  REDIS_KEYS
};
