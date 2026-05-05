const { defaultQueue, EVENT_TYPES, EVENT_STATUS, EVENT_PRIORITY } = require('./queue');
const logger = require('./config/logger');
const codeAccessService = require('./services/codeAccessService');
const complexityService = require('./services/complexityService');
const lintService = require('./services/lintService');
const duplicateService = require('./services/duplicateService');
const reviewTaskService = require('./services/reviewTaskService');

class AnalysisWorker {
  constructor(options = {}) {
    this.queue = options.queue || defaultQueue;
    this.isRunning = false;
    this.concurrency = options.concurrency || parseInt(process.env.WORKER_CONCURRENCY) || 2;
    this.pollInterval = options.pollInterval || parseInt(process.env.WORKER_POLL_INTERVAL) || 1000;
    this.maxIdleTime = options.maxIdleTime || parseInt(process.env.WORKER_MAX_IDLE) || 30000;
    this.processingTasks = new Map();
    this.stats = {
      total_processed: 0,
      total_failed: 0,
      total_completed: 0,
      start_time: null
    };
  }

  async start() {
    if (this.isRunning) {
      logger.warn('Worker 已经在运行中');
      return;
    }

    this.isRunning = true;
    this.stats.start_time = new Date();

    logger.info('Analysis Worker 启动中...');
    logger.info('配置: concurrency=%d, pollInterval=%dms', this.concurrency, this.pollInterval);

    for (let i = 0; i < this.concurrency; i++) {
      this.runWorkerLoop(i).catch(error => {
        logger.error('Worker 循环[%d] 发生错误: %s', i, error.message);
        logger.error('错误堆栈: %s', error.stack);
      });
    }

    logger.info('Analysis Worker 已启动，并发数: %d', this.concurrency);
  }

  async stop() {
    logger.info('Analysis Worker 正在停止...');
    this.isRunning = false;

    const pendingTasks = Array.from(this.processingTasks.values());
    if (pendingTasks.length > 0) {
      logger.info('等待 %d 个进行中的任务完成...', pendingTasks.length);
      
      await Promise.allSettled(pendingTasks);
    }

    logger.info('Analysis Worker 已停止');
    logger.info('运行统计: total=%d, completed=%d, failed=%d',
      this.stats.total_processed, this.stats.total_completed, this.stats.total_failed);
  }

  async runWorkerLoop(workerId) {
    logger.info('Worker[%d] 循环启动', workerId);

    while (this.isRunning) {
      try {
        const event = await this.queue.pop(this.pollInterval);

        if (!event) {
          continue;
        }

        logger.info('Worker[%d] 开始处理事件: event_id=%s, type=%s', 
          workerId, event.event_id, event.type);

        const processingPromise = this.processEvent(event);
        this.processingTasks.set(event.event_id, processingPromise);

        try {
          const result = await processingPromise;
          await this.queue.acknowledge(event.event_id, true, result);
          this.stats.total_completed++;
          logger.info('Worker[%d] 事件处理成功: event_id=%s', workerId, event.event_id);
        } catch (error) {
          await this.queue.acknowledge(event.event_id, false, { message: error.message });
          this.stats.total_failed++;
          logger.error('Worker[%d] 事件处理失败: event_id=%s, error=%s', 
            workerId, event.event_id, error.message);
        } finally {
          this.processingTasks.delete(event.event_id);
          this.stats.total_processed++;
        }

      } catch (error) {
        logger.error('Worker[%d] 循环错误: %s', workerId, error.message);
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
    }

    logger.info('Worker[%d] 循环已退出', workerId);
  }

  async processEvent(event) {
    logger.debug('处理事件: type=%s, data=%o', event.type, event.data);

    switch (event.type) {
      case EVENT_TYPES.CODE_COMMIT:
        return await this.handleCodeCommit(event.data);
      
      case EVENT_TYPES.ANALYSIS_REQUEST:
        return await this.handleAnalysisRequest(event.data);
      
      case EVENT_TYPES.COMPLEXITY_ANALYSIS:
        return await this.handleComplexityAnalysis(event.data);
      
      case EVENT_TYPES.LINT_ANALYSIS:
        return await this.handleLintAnalysis(event.data);
      
      case EVENT_TYPES.DUPLICATE_ANALYSIS:
        return await this.handleDuplicateAnalysis(event.data);
      
      case EVENT_TYPES.REVIEW_TASK_CREATE:
        return await this.handleReviewTaskCreate(event.data);
      
      default:
        logger.warn('未知的事件类型: %s', event.type);
        return { skipped: true, reason: 'Unknown event type' };
    }
  }

  async handleCodeCommit(data) {
    const { commit_id, repo_id, author, message, commit_time, files } = data;

    logger.info('处理代码提交事件: commit_id=%s, repo_id=%s', commit_id, repo_id);

    const commitResult = await codeAccessService.handleCommitEvent({
      commit_id,
      repo_id,
      author: author || 'unknown',
      message,
      commit_time,
      files: files || []
    });

    const changedFiles = commitResult.changedFiles;

    const analysisResult = await this.performFullAnalysis(commit_id, changedFiles, repo_id);

    return {
      commit_id,
      commit: commitResult.commit,
      files_count: changedFiles.length,
      analysis: analysisResult
    };
  }

  async performFullAnalysis(commit_id, changedFiles, repo_id = null) {
    logger.info('执行完整分析流程: commit_id=%s, files=%d, repo_id=%s', commit_id, changedFiles.length, repo_id);

    const [complexityResult, lintResult, duplicateResult] = await Promise.allSettled([
      complexityService.analyzeCommit(commit_id, changedFiles),
      lintService.analyzeCommit(commit_id, changedFiles, repo_id),
      duplicateService.analyzeCommit(commit_id, changedFiles)
    ]);

    const complexity = complexityResult.status === 'fulfilled' 
      ? complexityResult.value 
      : { error: complexityResult.reason?.message };
    
    const lint = lintResult.status === 'fulfilled' 
      ? lintResult.value 
      : { error: lintResult.reason?.message };
    
    const duplicate = duplicateResult.status === 'fulfilled' 
      ? duplicateResult.value 
      : { error: duplicateResult.reason?.message };

    const overallScore = this.calculateOverallScore(
      complexity.overall_score || 50,
      lint.score || 50,
      duplicate.score || 50
    );

    const reviewTask = await reviewTaskService.createTaskFromAnalysis(commit_id, {
      overall_score: overallScore,
      complexity,
      lint,
      duplicate
    });

    logger.info('完整分析完成: commit_id=%s, overall_score=%d', commit_id, overallScore);

    return {
      overall_score: overallScore,
      complexity,
      lint,
      duplicate,
      review_task: reviewTask
    };
  }

  calculateOverallScore(complexityScore, lintScore, duplicateScore) {
    const weights = {
      complexity: 0.4,
      lint: 0.4,
      duplicate: 0.2
    };
    
    const overall = 
      (complexityScore * weights.complexity) +
      (lintScore * weights.lint) +
      (duplicateScore * weights.duplicate);
    
    return Math.round(overall);
  }

  async handleAnalysisRequest(data) {
    const { commit_id, analysis_type = 'all' } = data;

    logger.info('处理分析请求: commit_id=%s, type=%s', commit_id, analysis_type);

    const changedFiles = await codeAccessService.getCommitWithFiles(commit_id);

    if (!changedFiles || !changedFiles.files) {
      throw new Error(`无法获取提交文件: ${commit_id}`);
    }

    const results = {};

    if (analysis_type === 'all' || analysis_type === 'complexity') {
      results.complexity = await complexityService.analyzeCommit(commit_id, changedFiles.files);
    }

    if (analysis_type === 'all' || analysis_type === 'lint') {
      results.lint = await lintService.analyzeCommit(commit_id, changedFiles.files);
    }

    if (analysis_type === 'all' || analysis_type === 'duplicate') {
      results.duplicate = await duplicateService.analyzeCommit(commit_id, changedFiles.files);
    }

    return results;
  }

  async handleComplexityAnalysis(data) {
    const { commit_id, changedFiles } = data;
    return await complexityService.analyzeCommit(commit_id, changedFiles);
  }

  async handleLintAnalysis(data) {
    const { commit_id, changedFiles } = data;
    return await lintService.analyzeCommit(commit_id, changedFiles);
  }

  async handleDuplicateAnalysis(data) {
    const { commit_id, changedFiles } = data;
    return await duplicateService.analyzeCommit(commit_id, changedFiles);
  }

  async handleReviewTaskCreate(data) {
    const { commit_id, analysisResults } = data;
    return await reviewTaskService.createTaskFromAnalysis(commit_id, analysisResults);
  }

  getStats() {
    const queueStats = this.queue.getStats ? this.queue.getStats() : {};
    
    return {
      ...this.stats,
      running: this.isRunning,
      concurrency: this.concurrency,
      processing_count: this.processingTasks.size,
      queue: queueStats,
      uptime: this.stats.start_time 
        ? Math.floor((Date.now() - this.stats.start_time.getTime()) / 1000)
        : 0
    };
  }
}

const workerInstance = new AnalysisWorker();

module.exports = {
  AnalysisWorker,
  defaultWorker: workerInstance
};
