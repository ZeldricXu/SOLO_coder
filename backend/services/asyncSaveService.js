const { redisTaskQueue, TASK_STATUSES } = require('./redisTaskQueue');
const taskQueue = require('./taskQueue');
const documentService = require('./documentService');
const versionService = require('./versionService');
const searchService = require('./searchService');
const logger = require('../utils/logger');
const config = require('../config/config');

const asyncSaveService = {
  
  pendingSaves: new Map(),
  
  useRedisQueue: config.asyncSave.useRedisQueue,

  getQueue() {
    if (this.useRedisQueue) {
      return redisTaskQueue;
    }
    return taskQueue;
  },

  async saveDocumentAsync(docId, user, options = {}) {
    const { content, changeDesc, title, category, tags } = options;
    
    const saveKey = `${docId}:${user}:${Date.now()}`;
    
    const taskData = {
      documentService,
      versionService,
      docId,
      user,
      content,
      changeDesc,
      title,
      category,
      tags
    };
    
    const queue = this.getQueue();
    
    if (this.useRedisQueue && !redisTaskQueue.isInitialized) {
      try {
        await redisTaskQueue.initialize();
      } catch (error) {
        logger.warn('Redis队列初始化失败，降级到内存队列', { error: error.message });
        this.useRedisQueue = false;
        return this.saveDocumentSync(docId, user, options);
      }
    }
    
    const taskId = queue.add('save_and_version', taskData, { priority: 1 });
    
    if (!taskId) {
      logger.warn(`异步保存任务添加失败，使用同步保存: doc_id=${docId}`);
      return this.saveDocumentSync(docId, user, options);
    }
    
    const pendingSave = {
      taskId,
      docId,
      user,
      options,
      status: 'queued',
      createdAt: Date.now(),
      result: null
    };
    
    this.pendingSaves.set(taskId, pendingSave);
    
    if (!this.useRedisQueue) {
      queue.once('task:completed', (task) => {
        if (task.id === taskId) {
          const pending = this.pendingSaves.get(taskId);
          if (pending) {
            pending.status = 'completed';
            pending.result = task.result;
            pending.completedAt = Date.now();
            
            searchService.invalidateUserCache(user);
            
            logger.info(`异步保存完成: task_id=${taskId}, doc_id=${docId}, user=${user}`);
          }
        }
      });
      
      queue.once('task:failed', (task, error) => {
        if (task.id === taskId) {
          const pending = this.pendingSaves.get(taskId);
          if (pending) {
            pending.status = 'failed';
            pending.error = error;
            pending.failedAt = Date.now();
            
            logger.error(`异步保存失败: task_id=${taskId}, doc_id=${docId}, error=${error.message}`);
          }
        }
      });
    }
    
    return {
      success: true,
      data: {
        save_status: 'queued',
        task_id: taskId,
        doc_id: docId,
        message: '保存请求已提交，后台正在处理',
        use_redis: this.useRedisQueue
      },
      async: true
    };
  },

  async saveDocumentSync(docId, user, options = {}) {
    const { content, changeDesc, title, category, tags } = options;
    
    const updateResult = await documentService.updateDocument(
      docId,
      user,
      content,
      '',
      title,
      category,
      tags
    );

    if (!updateResult.success) {
      return updateResult;
    }

    if (content || title || category || tags) {
      const versionResult = await versionService.createVersion(
        docId,
        user,
        content,
        changeDesc || '内容更新'
      );

      if (versionResult.success) {
        updateResult.data.version = versionResult.data.version;
      }
    }
    
    searchService.invalidateUserCache(user);
    
    return updateResult;
  },

  async quickSave(docId, user, content, options = {}) {
    if (!config.asyncSave.enabled) {
      return this.saveDocumentSync(docId, user, { content, ...options });
    }
    
    const existingTask = await this.findExistingTask(docId, user);
    if (existingTask) {
      return {
        success: true,
        data: {
          save_status: 'merged',
          task_id: existingTask.id,
          doc_id: docId,
          message: '保存已合并到现有任务'
        },
        async: true
      };
    }
    
    return this.saveDocumentAsync(docId, user, { content, ...options });
  },

  async findExistingTask(docId, user) {
    for (const [taskId, pending] of this.pendingSaves.entries()) {
      if (pending.docId === docId && 
          pending.user === user && 
          (pending.status === 'queued' || pending.status === 'processing')) {
        
        const queue = this.getQueue();
        
        if (this.useRedisQueue) {
          const task = await queue.getTask(taskId);
          if (task && (task.status === TASK_STATUSES.PENDING || task.status === TASK_STATUSES.PROCESSING)) {
            return task;
          }
        } else {
          const task = queue.getTask(taskId);
          if (task) {
            return task;
          }
        }
      }
    }
    return null;
  },

  async getSaveStatus(taskId) {
    const pending = this.pendingSaves.get(taskId);
    
    if (this.useRedisQueue) {
      try {
        if (!redisTaskQueue.isInitialized) {
          await redisTaskQueue.initialize();
        }
        
        const taskStatus = await redisTaskQueue.getTaskStatus(taskId);
        
        if (taskStatus) {
          if (pending) {
            pending.status = taskStatus.status;
            pending.completedAt = taskStatus.completedAt;
            pending.startedAt = taskStatus.startedAt;
            pending.error = taskStatus.error;
          }
          
          return {
            success: true,
            data: {
              ...taskStatus,
              use_redis: true
            }
          };
        }
      } catch (error) {
        logger.error('从Redis获取任务状态失败', { error: error.message });
      }
    }
    
    if (pending) {
      return {
        success: true,
        data: {
          task_id: taskId,
          doc_id: pending.docId,
          status: pending.status,
          created_at: pending.createdAt,
          completed_at: pending.completedAt,
          failed_at: pending.failedAt,
          result: pending.result,
          error: pending.error ? pending.error.message : null
        }
      };
    }
    
    const queue = this.getQueue();
    const taskStatus = queue.getTaskStatus(taskId);
    if (taskStatus) {
      return {
        success: true,
        data: taskStatus
      };
    }
    
    return {
      success: false,
      error: '任务不存在'
    };
  },

  async waitForSave(taskId, timeout = 30000) {
    try {
      const queue = this.getQueue();
      const result = await queue.waitForTask(taskId, timeout);
      
      const pending = this.pendingSaves.get(taskId);
      if (pending) {
        pending.status = result.status;
        if (result.status === 'completed') {
          pending.result = result;
        }
      }
      
      return {
        success: true,
        data: result
      };
    } catch (error) {
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getStats() {
    const queue = this.getQueue();
    let stats;
    
    if (this.useRedisQueue) {
      try {
        stats = await queue.getStats();
      } catch (error) {
        logger.error('获取Redis队列统计失败', { error: error.message });
        stats = taskQueue.getStats();
      }
    } else {
      stats = queue.getStats();
    }
    
    const pendingByStatus = {
      queued: 0,
      processing: 0,
      completed: 0,
      failed: 0
    };
    
    for (const pending of this.pendingSaves.values()) {
      if (pendingByStatus[pending.status] !== undefined) {
        pendingByStatus[pending.status]++;
      }
    }
    
    return {
      ...stats,
      use_redis: this.useRedisQueue,
      pending_saves: {
        total: this.pendingSaves.size,
        ...pendingByStatus
      }
    };
  },

  cleanupOldSaves(maxAge = 3600000) {
    const now = Date.now();
    let cleanedCount = 0;
    
    for (const [taskId, pending] of this.pendingSaves.entries()) {
      const age = now - pending.createdAt;
      if (age > maxAge) {
        this.pendingSaves.delete(taskId);
        cleanedCount++;
      }
    }
    
    if (cleanedCount > 0) {
      logger.debug(`清理旧的保存记录: count=${cleanedCount}`);
    }
    
    return cleanedCount;
  },

  async cancelSave(taskId) {
    const pending = this.pendingSaves.get(taskId);
    
    if (!pending) {
      return {
        success: false,
        error: '任务不存在'
      };
    }
    
    if (pending.status === 'processing') {
      return {
        success: false,
        error: '任务正在执行中，无法取消'
      };
    }
    
    this.pendingSaves.delete(taskId);
    
    return {
      success: true,
      data: {
        task_id: taskId,
        message: '任务已取消'
      }
    };
  },

  async batchSave(docId, user, changes, options = {}) {
    const results = [];
    
    for (const change of changes) {
      const result = await this.quickSave(docId, user, change.content, {
        changeDesc: change.changeDesc,
        ...options
      });
      results.push(result);
    }
    
    return {
      success: true,
      data: {
        results,
        count: results.length
      }
    };
  },

  async switchToRedisQueue() {
    try {
      await redisTaskQueue.initialize();
      this.useRedisQueue = true;
      logger.info('已切换到Redis持久化任务队列');
      return { success: true, message: '已切换到Redis队列' };
    } catch (error) {
      logger.error('切换到Redis队列失败', { error: error.message });
      return { success: false, error: error.message };
    }
  },

  switchToMemoryQueue() {
    this.useRedisQueue = false;
    logger.info('已切换到内存任务队列');
    return { success: true, message: '已切换到内存队列' };
  },

  getQueueType() {
    return {
      useRedis: this.useRedisQueue,
      type: this.useRedisQueue ? 'redis' : 'memory'
    };
  }
};

if (!asyncSaveService.useRedisQueue) {
  taskQueue.on('task:start', (task) => {
    const pending = asyncSaveService.pendingSaves.get(task.id);
    if (pending) {
      pending.status = 'processing';
      pending.startedAt = Date.now();
    }
  });
}

setInterval(() => {
  asyncSaveService.cleanupOldSaves();
}, 60000);

module.exports = asyncSaveService;
