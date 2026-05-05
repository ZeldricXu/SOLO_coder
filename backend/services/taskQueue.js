const { EventEmitter } = require('events');
const config = require('../config/config');
const logger = require('../utils/logger');

class TaskQueue extends EventEmitter {
  constructor(options = {}) {
    super();
    
    this.queue = [];
    this.processing = new Set();
    this.maxWorkers = options.maxWorkers || config.asyncSave.maxWorkers;
    this.maxQueueSize = options.queueSize || config.asyncSave.queueSize;
    this.retryDelay = options.retryDelay || config.asyncSave.retryDelay;
    this.maxRetries = options.maxRetries || config.asyncSave.maxRetries;
    
    this.stats = {
      total: 0,
      completed: 0,
      failed: 0,
      retried: 0
    };
    
    this.taskMap = new Map();
    this.isRunning = true;
    
    logger.info(`任务队列已初始化: maxWorkers=${this.maxWorkers}, maxQueueSize=${this.maxQueueSize}`);
  }

  generateTaskId() {
    return `task_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  add(taskType, taskData, options = {}) {
    if (this.queue.length >= this.maxQueueSize) {
      const error = new Error('任务队列已满');
      logger.error('添加任务失败: 队列已满', { taskType, queueSize: this.queue.length });
      this.emit('error', error, { taskType, taskData });
      return null;
    }

    const taskId = this.generateTaskId();
    
    const task = {
      id: taskId,
      type: taskType,
      data: taskData,
      status: 'pending',
      priority: options.priority || 0,
      retries: 0,
      maxRetries: options.maxRetries || this.maxRetries,
      createdAt: Date.now(),
      startedAt: null,
      completedAt: null,
      error: null
    };

    this.taskMap.set(taskId, task);
    
    const insertIndex = this.queue.findIndex(t => t.priority < task.priority);
    if (insertIndex === -1) {
      this.queue.push(task);
    } else {
      this.queue.splice(insertIndex, 0, task);
    }

    this.stats.total++;
    
    logger.debug(`任务已添加: taskId=${taskId}, type=${taskType}`);
    this.emit('task:added', task);

    process.nextTick(() => this.processQueue());

    return taskId;
  }

  async processQueue() {
    if (!this.isRunning) return;
    
    while (this.processing.size < this.maxWorkers && this.queue.length > 0) {
      const task = this.queue.shift();
      
      if (task && task.status === 'pending') {
        this.processing.add(task.id);
        task.status = 'processing';
        task.startedAt = Date.now();
        
        this.processTask(task);
      }
    }
  }

  async processTask(task) {
    try {
      this.emit('task:start', task);
      logger.debug(`开始执行任务: taskId=${task.id}, type=${task.type}`);

      let result;
      
      switch (task.type) {
        case 'save_document':
          result = await this.handleSaveDocument(task.data);
          break;
          
        case 'create_version':
          result = await this.handleCreateVersion(task.data);
          break;
          
        case 'save_and_version':
          result = await this.handleSaveAndVersion(task.data);
          break;
          
        case 'update_index':
          result = await this.handleUpdateIndex(task.data);
          break;
          
        default:
          throw new Error(`未知的任务类型: ${task.type}`);
      }

      task.status = 'completed';
      task.completedAt = Date.now();
      task.result = result;
      
      this.stats.completed++;
      this.processing.delete(task.id);
      
      logger.debug(`任务执行成功: taskId=${task.id}, type=${task.type}`);
      this.emit('task:completed', task);

    } catch (error) {
      task.retries++;
      task.error = {
        message: error.message,
        stack: error.stack
      };

      if (task.retries < task.maxRetries) {
        task.status = 'retrying';
        this.stats.retried++;
        
        logger.warn(`任务执行失败，准备重试: taskId=${task.id}, type=${task.type}, retry=${task.retries}/${task.maxRetries}`);
        this.emit('task:retry', task, error);
        
        setTimeout(() => {
          if (task.status === 'retrying') {
            task.status = 'pending';
            this.queue.push(task);
            this.processQueue();
          }
        }, this.retryDelay * task.retries);
        
      } else {
        task.status = 'failed';
        task.completedAt = Date.now();
        
        this.stats.failed++;
        this.processing.delete(task.id);
        
        logger.error(`任务执行失败，已达到最大重试次数: taskId=${task.id}, type=${task.type}`, { error: error.message });
        this.emit('task:failed', task, error);
      }
    } finally {
      this.processQueue();
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

  getTask(taskId) {
    return this.taskMap.get(taskId) || null;
  }

  getTaskStatus(taskId) {
    const task = this.getTask(taskId);
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

  waitForTask(taskId, timeout = 30000) {
    return new Promise((resolve, reject) => {
      const task = this.getTask(taskId);
      
      if (!task) {
        reject(new Error('任务不存在'));
        return;
      }

      if (task.status === 'completed' || task.status === 'failed') {
        resolve(this.getTaskStatus(taskId));
        return;
      }

      const timeoutId = setTimeout(() => {
        this.removeListener('task:completed', onComplete);
        this.removeListener('task:failed', onFailed);
        reject(new Error('任务执行超时'));
      }, timeout);

      const onComplete = (completedTask) => {
        if (completedTask.id === taskId) {
          clearTimeout(timeoutId);
          this.removeListener('task:completed', onComplete);
          this.removeListener('task:failed', onFailed);
          resolve(this.getTaskStatus(taskId));
        }
      };

      const onFailed = (failedTask) => {
        if (failedTask.id === taskId) {
          clearTimeout(timeoutId);
          this.removeListener('task:completed', onComplete);
          this.removeListener('task:failed', onFailed);
          resolve(this.getTaskStatus(taskId));
        }
      };

      this.on('task:completed', onComplete);
      this.on('task:failed', onFailed);
    });
  }

  getStats() {
    return {
      ...this.stats,
      queueSize: this.queue.length,
      processingCount: this.processing.size,
      maxWorkers: this.maxWorkers,
      maxQueueSize: this.maxQueueSize
    };
  }

  pause() {
    this.isRunning = false;
    logger.info('任务队列已暂停');
    this.emit('queue:paused');
  }

  resume() {
    this.isRunning = true;
    logger.info('任务队列已恢复');
    this.emit('queue:resumed');
    this.processQueue();
  }

  shutdown() {
    this.isRunning = false;
    this.queue = [];
    logger.info('任务队列已关闭');
    this.emit('queue:shutdown');
  }

  clear() {
    this.queue = [];
    this.taskMap.clear();
    this.stats = {
      total: 0,
      completed: 0,
      failed: 0,
      retried: 0
    };
    logger.info('任务队列已清空');
  }
}

const taskQueue = new TaskQueue();

module.exports = taskQueue;
