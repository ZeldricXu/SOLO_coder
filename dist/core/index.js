"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.TaskQueue = exports.CoreProcessor = exports.coreProcessor = void 0;
const events_1 = require("events");
const node_cron_1 = __importDefault(require("node-cron"));
const utils_1 = require("../shared/utils");
const logging_1 = require("../logging");
const monitoring_1 = require("../monitoring");
class TaskQueue {
    queue = [];
    maxSize = 1000;
    enqueue(task, priority = 0) {
        if (this.queue.length >= this.maxSize) {
            return false;
        }
        this.queue.push({ task, added_at: Date.now(), priority });
        this.queue.sort((a, b) => b.priority - a.priority || a.added_at - b.added_at);
        return true;
    }
    dequeue() {
        const item = this.queue.shift();
        return item ? item.task : null;
    }
    size() {
        return this.queue.length;
    }
    clear() {
        this.queue = [];
    }
    peek() {
        return this.queue.length > 0 ? this.queue[0].task : null;
    }
}
exports.TaskQueue = TaskQueue;
class CoreProcessor extends events_1.EventEmitter {
    handlers = new Map();
    tasks = new Map();
    runs = new Map();
    scheduledTasks = new Map();
    cronJobs = new Map();
    queue = new TaskQueue();
    taskStatus = new Map();
    isRunning = false;
    maxConcurrentTasks = 5;
    activeTaskCount = 0;
    constructor() {
        super();
        this.registerDefaultHandlers();
    }
    registerDefaultHandlers() {
        this.registerHandler({
            name: 'echo',
            handler: async (payload) => payload,
            timeout_ms: 5000,
        });
        this.registerHandler({
            name: 'delay',
            handler: async (payload) => {
                const ms = payload?.ms || 1000;
                await (0, utils_1.sleep)(ms);
                return { delayed: ms };
            },
            timeout_ms: 30000,
        });
        this.registerHandler({
            name: 'health_check',
            handler: async () => {
                return {
                    status: 'healthy',
                    timestamp: (0, utils_1.nowISO)(),
                    active_tasks: this.activeTaskCount,
                    queue_size: this.queue.size(),
                };
            },
            timeout_ms: 3000,
        });
    }
    registerHandler(handler) {
        this.handlers.set(handler.name, handler);
        logging_1.logger.info('Task handler registered', { handler_name: handler.name });
        this.emit('handler.registered', handler.name);
    }
    unregisterHandler(handlerName) {
        const existed = this.handlers.has(handlerName);
        if (existed) {
            this.handlers.delete(handlerName);
            logging_1.logger.info('Task handler unregistered', { handler_name: handlerName });
            this.emit('handler.unregistered', handlerName);
        }
        return existed;
    }
    createTask(type, config, labels = {}) {
        const task = {
            id: (0, utils_1.generateId)('task'),
            type,
            config,
            labels,
            status: 'provisioning',
            created_at: (0, utils_1.nowISO)(),
            updated_at: (0, utils_1.nowISO)(),
        };
        this.tasks.set(task.id, task);
        this.taskStatus.set(task.id, 'pending');
        logging_1.logger.info('Task created', { task_id: task.id, type });
        this.emit('task.created', task);
        return task;
    }
    async executeTask(taskId, payload, traceId) {
        const task = this.tasks.get(taskId);
        if (!task) {
            return {
                success: false,
                error: `Task ${taskId} not found`,
                duration_ms: 0,
                run_id: (0, utils_1.generateId)('run'),
            };
        }
        const handler = this.handlers.get(task.type);
        if (!handler) {
            return {
                success: false,
                error: `No handler registered for task type: ${task.type}`,
                duration_ms: 0,
                run_id: (0, utils_1.generateId)('run'),
            };
        }
        const runId = (0, utils_1.generateId)('run');
        const actualTraceId = traceId || (0, utils_1.generateId)('trace');
        const startTime = Date.now();
        const run = {
            run_id: runId,
            entity_id: taskId,
            phase: 'executing',
            progress: 0,
            started_at: (0, utils_1.nowISO)(),
            completed_at: null,
            error_detail: null,
            metadata: {
                task_type: task.type,
                trace_id: actualTraceId,
            },
        };
        this.runs.set(runId, run);
        this.taskStatus.set(taskId, 'running');
        task.status = 'running';
        task.updated_at = (0, utils_1.nowISO)();
        const context = {
            trace_id: actualTraceId,
            task_id: taskId,
            run_id: runId,
            metadata: {},
            start_time: startTime,
            setProgress: (progress) => {
                run.progress = Math.max(0, Math.min(1, progress));
                this.emit('task.progress', taskId, runId, run.progress);
            },
            emit: (event, data) => {
                this.emit(`task.event.${event}`, taskId, runId, data);
            },
        };
        logging_1.logger.info('Task execution started', { task_id: taskId, run_id: runId, type: task.type }, actualTraceId);
        this.emit('task.started', taskId, runId);
        const timerId = monitoring_1.monitoring.startTimer('task_duration', { task_type: task.type });
        try {
            const timeout = handler.timeout_ms || 30000;
            const retries = handler.retry_count || 0;
            const result = await (0, utils_1.retry)(() => (0, utils_1.withTimeout)(handler.handler(payload, context), timeout, `Task ${task.type} timed out after ${timeout}ms`), retries + 1, 1000, 2);
            run.phase = 'completed';
            run.progress = 1;
            run.completed_at = (0, utils_1.nowISO)();
            task.status = 'completed';
            task.updated_at = (0, utils_1.nowISO)();
            this.taskStatus.set(taskId, 'completed');
            const duration = Date.now() - startTime;
            monitoring_1.monitoring.stopTimer('task_duration', timerId, { task_type: task.type, status: 'success' });
            monitoring_1.monitoring.incrementCounter('tasks_completed', 1, { task_type: task.type });
            logging_1.logger.info('Task execution completed', { task_id: taskId, run_id: runId, duration_ms: duration }, actualTraceId);
            this.emit('task.completed', taskId, runId, result);
            return {
                success: true,
                data: result,
                duration_ms: duration,
                run_id: runId,
            };
        }
        catch (error) {
            run.phase = 'failed';
            run.error_detail = error.message;
            run.completed_at = (0, utils_1.nowISO)();
            task.status = 'failed';
            task.updated_at = (0, utils_1.nowISO)();
            this.taskStatus.set(taskId, 'failed');
            const duration = Date.now() - startTime;
            monitoring_1.monitoring.stopTimer('task_duration', timerId, { task_type: task.type, status: 'failed' });
            monitoring_1.monitoring.incrementCounter('tasks_failed', 1, { task_type: task.type });
            logging_1.logger.error('Task execution failed', {
                task_id: taskId,
                run_id: runId,
                error: error.message,
                duration_ms: duration,
            }, actualTraceId);
            this.emit('task.failed', taskId, runId, error);
            return {
                success: false,
                error: error.message,
                duration_ms: duration,
                run_id: runId,
            };
        }
    }
    queueTask(taskId, payload, priority = 0, traceId) {
        const task = this.tasks.get(taskId);
        if (!task)
            return false;
        const queued = this.queue.enqueue(task, priority);
        if (queued) {
            this.taskStatus.set(taskId, 'queued');
            task.status = 'provisioning';
            task.updated_at = (0, utils_1.nowISO)();
            logging_1.logger.info('Task queued', { task_id: taskId, priority, queue_size: this.queue.size() });
            this.emit('task.queued', taskId);
            this.processQueue();
        }
        return queued;
    }
    async processQueue() {
        if (this.isRunning)
            return;
        this.isRunning = true;
        while (this.queue.size() > 0 && this.activeTaskCount < this.maxConcurrentTasks) {
            const task = this.queue.dequeue();
            if (task) {
                this.activeTaskCount++;
                this.executeTask(task.id, task.config)
                    .finally(() => {
                    this.activeTaskCount--;
                    setImmediate(() => this.processQueue());
                });
            }
        }
        this.isRunning = false;
    }
    scheduleTask(taskType, cronExpression, config, timezone) {
        const taskId = (0, utils_1.generateId)('sched');
        const scheduled = {
            task_id: taskId,
            cron_expression: cronExpression,
            task_type: taskType,
            config,
            enabled: true,
            last_run_at: null,
            next_run_at: null,
            timezone,
        };
        this.scheduledTasks.set(taskId, scheduled);
        const job = node_cron_1.default.schedule(cronExpression, () => {
            if (!scheduled.enabled)
                return;
            const task = this.createTask(taskType, config);
            scheduled.last_run_at = (0, utils_1.nowISO)();
            this.queueTask(task.id, config, 1);
        }, {
            scheduled: true,
            timezone,
        });
        this.cronJobs.set(taskId, job);
        try {
            scheduled.next_run_at = null;
        }
        catch {
            scheduled.next_run_at = null;
        }
        logging_1.logger.info('Task scheduled', { task_id: taskId, cron: cronExpression, task_type: taskType });
        this.emit('task.scheduled', scheduled);
        return scheduled;
    }
    cancelScheduledTask(taskId) {
        const job = this.cronJobs.get(taskId);
        if (job) {
            job.stop();
            this.cronJobs.delete(taskId);
        }
        const scheduled = this.scheduledTasks.get(taskId);
        if (scheduled) {
            scheduled.enabled = false;
            logging_1.logger.info('Scheduled task cancelled', { task_id: taskId });
            this.emit('task.unscheduled', taskId);
            return true;
        }
        return false;
    }
    getTask(taskId) {
        return this.tasks.get(taskId) || null;
    }
    getRun(runId) {
        return this.runs.get(runId) || null;
    }
    getTaskRuns(taskId) {
        return Array.from(this.runs.values()).filter((r) => r.entity_id === taskId);
    }
    listTasks(status) {
        const tasks = Array.from(this.tasks.values());
        if (status) {
            return tasks.filter((t) => t.status === status);
        }
        return tasks;
    }
    listScheduledTasks() {
        return Array.from(this.scheduledTasks.values());
    }
    getTaskStatus(taskId) {
        return this.taskStatus.get(taskId) || null;
    }
    cancelTask(taskId) {
        const status = this.taskStatus.get(taskId);
        if (status && (status === 'pending' || status === 'queued')) {
            this.taskStatus.set(taskId, 'cancelled');
            const task = this.tasks.get(taskId);
            if (task) {
                task.status = 'stopped';
                task.updated_at = (0, utils_1.nowISO)();
            }
            logging_1.logger.info('Task cancelled', { task_id: taskId });
            this.emit('task.cancelled', taskId);
            return true;
        }
        return false;
    }
    setMaxConcurrentTasks(max) {
        this.maxConcurrentTasks = Math.max(1, max);
        logging_1.logger.info('Max concurrent tasks updated', { max });
    }
    getQueueSize() {
        return this.queue.size();
    }
    getActiveTaskCount() {
        return this.activeTaskCount;
    }
    getHandlerNames() {
        return Array.from(this.handlers.keys());
    }
    shutdown() {
        for (const [taskId, job] of this.cronJobs.entries()) {
            job.stop();
            logging_1.logger.info('Cron job stopped', { task_id: taskId });
        }
        this.queue.clear();
        logging_1.logger.info('Core processor shutdown complete');
        this.emit('processor.shutdown');
    }
}
exports.CoreProcessor = CoreProcessor;
exports.coreProcessor = new CoreProcessor();
//# sourceMappingURL=index.js.map