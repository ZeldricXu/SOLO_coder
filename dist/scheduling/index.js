"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.TaskScheduler = void 0;
const uuid_1 = require("uuid");
const logger_1 = __importDefault(require("../common/logger"));
const types_1 = require("../types");
const utils_1 = require("../common/utils");
class TaskScheduler {
    constructor(config = {}) {
        this.taskQueue = [];
        this.runningTasks = new Map();
        this.completedTasks = new Map();
        this.jobs = new Map();
        this.executors = new Map();
        this.isRunning = false;
        this.config = {
            maxConcurrentTasks: config.maxConcurrentTasks ?? 10,
            defaultTimeoutMs: config.defaultTimeoutMs ?? 300000,
            defaultMaxRetries: config.defaultMaxRetries ?? 3,
            retryDelayMs: config.retryDelayMs ?? 1000
        };
        this.semaphore = new utils_1.Semaphore(this.config.maxConcurrentTasks);
    }
    setTaskCompleteCallback(callback) {
        this.onTaskComplete = callback;
    }
    setJobCompleteCallback(callback) {
        this.onJobComplete = callback;
    }
    registerExecutor(taskType, executor) {
        this.executors.set(taskType, executor);
        logger_1.default.info({ taskType }, '注册任务执行器');
    }
    unregisterExecutor(taskType) {
        this.executors.delete(taskType);
    }
    createJob(name, taskDefinitions, context = {}) {
        const jobId = (0, uuid_1.v4)();
        const tasks = taskDefinitions.map((def, index) => ({
            taskId: (0, uuid_1.v4)(),
            name: def.name,
            type: def.type,
            dependencies: def.dependencies || [],
            status: types_1.TaskStatus.PENDING,
            priority: def.priority ?? 5,
            payload: def.payload,
            createdAt: new Date().toISOString(),
            retries: 0,
            maxRetries: def.maxRetries ?? this.config.defaultMaxRetries,
            timeoutMs: def.timeoutMs ?? this.config.defaultTimeoutMs,
            tags: def.tags || {}
        }));
        const job = {
            jobId,
            name,
            tasks,
            status: types_1.TaskStatus.PENDING,
            createdAt: new Date().toISOString(),
            context
        };
        this.jobs.set(jobId, job);
        logger_1.default.info({ jobId, name, taskCount: tasks.length }, '创建任务编排');
        return job;
    }
    submitJob(jobId) {
        const job = this.jobs.get(jobId);
        if (!job) {
            throw new Error(`任务编排不存在: ${jobId}`);
        }
        job.status = types_1.TaskStatus.RUNNING;
        job.startedAt = new Date().toISOString();
        for (const task of job.tasks) {
            this.taskQueue.push(task);
        }
        this.taskQueue.sort((a, b) => b.priority - a.priority);
        logger_1.default.info({ jobId, taskCount: job.tasks.length }, '提交任务编排');
        this.startProcessing();
    }
    submitSingleTask(definition) {
        const task = {
            taskId: (0, uuid_1.v4)(),
            name: definition.name,
            type: definition.type,
            dependencies: definition.dependencies || [],
            status: types_1.TaskStatus.PENDING,
            priority: definition.priority ?? 5,
            payload: definition.payload,
            createdAt: new Date().toISOString(),
            retries: 0,
            maxRetries: definition.maxRetries ?? this.config.defaultMaxRetries,
            timeoutMs: definition.timeoutMs ?? this.config.defaultTimeoutMs,
            tags: definition.tags || {}
        };
        this.taskQueue.push(task);
        this.taskQueue.sort((a, b) => b.priority - a.priority);
        logger_1.default.info({ taskId: task.taskId, name: task.name }, '提交独立任务');
        this.startProcessing();
        return task;
    }
    startProcessing() {
        if (this.isRunning)
            return;
        this.isRunning = true;
        this.processLoop().catch(error => {
            logger_1.default.error({ error }, '调度处理循环异常');
            this.isRunning = false;
        });
    }
    async processLoop() {
        while (this.taskQueue.length > 0 || this.runningTasks.size > 0) {
            const readyTasks = this.getReadyTasks();
            for (const task of readyTasks) {
                if (!this.runningTasks.has(task.taskId)) {
                    this.executeTask(task).catch(error => {
                        logger_1.default.error({ taskId: task.taskId, error }, '任务执行异常');
                    });
                }
            }
            if (this.taskQueue.length === 0 && this.runningTasks.size === 0) {
                break;
            }
            await (0, utils_1.sleep)(100);
        }
        this.isRunning = false;
        this.checkAndCompleteJobs();
    }
    getReadyTasks() {
        const ready = [];
        const completedIds = new Set([
            ...this.completedTasks.keys(),
            ...Array.from(this.runningTasks.values()).filter(t => t.status === types_1.TaskStatus.COMPLETED).map(t => t.taskId)
        ]);
        for (let i = 0; i < this.taskQueue.length; i++) {
            const task = this.taskQueue[i];
            if (task.status !== types_1.TaskStatus.PENDING)
                continue;
            const dependenciesMet = task.dependencies.every(dep => completedIds.has(dep));
            if (dependenciesMet) {
                ready.push(task);
            }
        }
        return ready.sort((a, b) => b.priority - a.priority);
    }
    async executeTask(task) {
        await this.semaphore.acquire();
        const executor = this.executors.get(task.type);
        if (!executor) {
            task.status = types_1.TaskStatus.FAILED;
            task.error = `未找到执行器: ${task.type}`;
            this.completeTask(task);
            this.semaphore.release();
            return;
        }
        this.runningTasks.set(task.taskId, task);
        task.status = types_1.TaskStatus.RUNNING;
        task.startedAt = new Date().toISOString();
        const job = this.findJobForTask(task.taskId);
        logger_1.default.info({ taskId: task.taskId, type: task.type, jobId: job?.jobId }, '开始执行任务');
        const timeoutPromise = new Promise((_, reject) => {
            setTimeout(() => reject(new Error(`任务超时 (${task.timeoutMs}ms)`)), task.timeoutMs);
        });
        try {
            const result = await Promise.race([
                executor(task, job?.context || {}),
                timeoutPromise
            ]);
            task.status = types_1.TaskStatus.COMPLETED;
            task.result = result;
            task.completedAt = new Date().toISOString();
            logger_1.default.info({ taskId: task.taskId, duration: Date.now() - new Date(task.startedAt).getTime() }, '任务执行成功');
        }
        catch (error) {
            const errorMessage = error instanceof Error ? error.message : String(error);
            if (task.retries < task.maxRetries) {
                task.retries++;
                task.status = types_1.TaskStatus.PENDING;
                logger_1.default.warn({ taskId: task.taskId, retries: task.retries, error: errorMessage }, '任务执行失败，准备重试');
                this.runningTasks.delete(task.taskId);
                this.semaphore.release();
                await (0, utils_1.sleep)(this.config.retryDelayMs * Math.pow(2, task.retries - 1));
                return;
            }
            task.status = types_1.TaskStatus.FAILED;
            task.error = errorMessage;
            task.completedAt = new Date().toISOString();
            logger_1.default.error({ taskId: task.taskId, retries: task.retries, error: errorMessage }, '任务执行失败，已达最大重试次数');
        }
        this.completeTask(task);
        this.semaphore.release();
    }
    completeTask(task) {
        this.runningTasks.delete(task.taskId);
        this.completedTasks.set(task.taskId, task);
        const index = this.taskQueue.findIndex(t => t.taskId === task.taskId);
        if (index > -1) {
            this.taskQueue.splice(index, 1);
        }
        this.onTaskComplete?.(task);
        this.checkAndCompleteJobs();
    }
    findJobForTask(taskId) {
        for (const job of this.jobs.values()) {
            if (job.tasks.some(t => t.taskId === taskId)) {
                return job;
            }
        }
        return undefined;
    }
    checkAndCompleteJobs() {
        for (const job of this.jobs.values()) {
            if (job.status !== types_1.TaskStatus.RUNNING)
                continue;
            const allCompleted = job.tasks.every(t => t.status === types_1.TaskStatus.COMPLETED || t.status === types_1.TaskStatus.FAILED);
            if (allCompleted) {
                const hasFailed = job.tasks.some(t => t.status === types_1.TaskStatus.FAILED);
                job.status = hasFailed ? types_1.TaskStatus.FAILED : types_1.TaskStatus.COMPLETED;
                job.completedAt = new Date().toISOString();
                logger_1.default.info({ jobId: job.jobId, status: job.status }, '任务编排完成');
                this.onJobComplete?.(job);
            }
        }
    }
    getTaskStatus(taskId) {
        return this.runningTasks.get(taskId) ||
            this.completedTasks.get(taskId) ||
            this.taskQueue.find(t => t.taskId === taskId);
    }
    getJobStatus(jobId) {
        return this.jobs.get(jobId);
    }
    cancelTask(taskId) {
        const task = this.getTaskStatus(taskId);
        if (!task || task.status === types_1.TaskStatus.COMPLETED || task.status === types_1.TaskStatus.FAILED) {
            return false;
        }
        task.status = types_1.TaskStatus.CANCELLED;
        const index = this.taskQueue.findIndex(t => t.taskId === taskId);
        if (index > -1) {
            this.taskQueue.splice(index, 1);
        }
        this.completedTasks.set(taskId, task);
        logger_1.default.info({ taskId }, '任务已取消');
        return true;
    }
    getStats() {
        return {
            pending: this.taskQueue.filter(t => t.status === types_1.TaskStatus.PENDING).length,
            running: this.runningTasks.size,
            completed: Array.from(this.completedTasks.values()).filter(t => t.status === types_1.TaskStatus.COMPLETED).length,
            failed: Array.from(this.completedTasks.values()).filter(t => t.status === types_1.TaskStatus.FAILED).length,
            jobs: this.jobs.size
        };
    }
    listJobs() {
        return Array.from(this.jobs.values());
    }
    stop() {
        this.isRunning = false;
        this.taskQueue = [];
    }
    reset() {
        this.stop();
        this.taskQueue = [];
        this.runningTasks.clear();
        this.completedTasks.clear();
        this.jobs.clear();
    }
}
exports.TaskScheduler = TaskScheduler;
//# sourceMappingURL=index.js.map