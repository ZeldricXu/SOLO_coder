"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const core_1 = require("../core");
const logging_1 = require("../logging");
const schemas_1 = require("../shared/schemas");
const router = (0, express_1.Router)();
router.post('/', (req, res) => {
    try {
        const body = schemas_1.TaskCreateSchema.parse(req.body);
        const task = core_1.coreProcessor.createTask(body.type, body.config, body.labels);
        const traceId = req.headers['x-trace-id'];
        if (req.query.async === 'true') {
            core_1.coreProcessor.queueTask(task.id, body.config, 0, traceId);
            res.status(202).json({ code: 202, data: { id: task.id, status: 'queued' } });
        }
        else {
            res.status(201).json({ code: 201, data: { id: task.id, status: 'provisioning' } });
        }
    }
    catch (error) {
        logging_1.logger.error('Task creation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.post('/execute/:id', async (req, res) => {
    const traceId = req.headers['x-trace-id'];
    const result = await core_1.coreProcessor.executeTask(req.params.id, req.body, traceId);
    if (!result.success) {
        res.status(500).json({ code: 500, error: result.error });
        return;
    }
    res.json({ code: 200, data: result });
});
router.get('/', (req, res) => {
    const status = req.query.status;
    const tasks = core_1.coreProcessor.listTasks(status);
    res.json({ code: 200, data: tasks });
});
router.get('/scheduled', (req, res) => {
    const tasks = core_1.coreProcessor.listScheduledTasks();
    res.json({ code: 200, data: tasks });
});
router.post('/schedule', (req, res) => {
    try {
        const { task_type, cron_expression, config, timezone } = req.body;
        const scheduled = core_1.coreProcessor.scheduleTask(task_type, cron_expression, config, timezone);
        res.status(201).json({ code: 201, data: scheduled });
    }
    catch (error) {
        logging_1.logger.error('Task scheduling failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.delete('/schedule/:id', (req, res) => {
    const cancelled = core_1.coreProcessor.cancelScheduledTask(req.params.id);
    if (!cancelled) {
        res.status(404).json({ code: 404, error: 'Scheduled task not found' });
        return;
    }
    res.json({ code: 200, message: 'Scheduled task cancelled' });
});
router.get('/:id', (req, res) => {
    const task = core_1.coreProcessor.getTask(req.params.id);
    if (!task) {
        res.status(404).json({ code: 404, error: 'Task not found' });
        return;
    }
    res.json({ code: 200, data: task });
});
router.get('/:id/status', (req, res) => {
    const status = core_1.coreProcessor.getTaskStatus(req.params.id);
    if (status === null) {
        res.status(404).json({ code: 404, error: 'Task not found' });
        return;
    }
    res.json({ code: 200, data: { id: req.params.id, status, progress: null } });
});
router.get('/:id/runs', (req, res) => {
    const runs = core_1.coreProcessor.getTaskRuns(req.params.id);
    res.json({ code: 200, data: runs });
});
router.get('/runs/:runId', (req, res) => {
    const run = core_1.coreProcessor.getRun(req.params.runId);
    if (!run) {
        res.status(404).json({ code: 404, error: 'Run not found' });
        return;
    }
    res.json({ code: 200, data: run });
});
router.delete('/:id', (req, res) => {
    const cancelled = core_1.coreProcessor.cancelTask(req.params.id);
    if (!cancelled) {
        res.status(404).json({ code: 404, error: 'Task not found or cannot be cancelled' });
        return;
    }
    res.json({ code: 200, message: 'Task cancelled' });
});
router.post('/batch', (req, res) => {
    try {
        const body = schemas_1.BatchOperationSchema.parse(req.body);
        const results = [];
        for (const op of body.operations) {
            try {
                let success = false;
                switch (op.action) {
                    case 'start':
                        success = core_1.coreProcessor.queueTask(op.id, {}, 0);
                        break;
                    case 'stop':
                    case 'delete':
                        success = core_1.coreProcessor.cancelTask(op.id);
                        break;
                    case 'restart':
                        core_1.coreProcessor.cancelTask(op.id);
                        success = core_1.coreProcessor.queueTask(op.id, {}, 0);
                        break;
                    default:
                        success = false;
                }
                results.push({ id: op.id, action: op.action, success });
            }
            catch (error) {
                results.push({ id: op.id, action: op.action, success: false, error: error.message });
            }
        }
        res.json({
            code: 200,
            data: {
                batch_id: `batch_${Date.now()}`,
                results,
            },
        });
    }
    catch (error) {
        logging_1.logger.error('Batch operation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/queue/stats', (req, res) => {
    const stats = {
        queue_size: core_1.coreProcessor.getQueueSize(),
        active_tasks: core_1.coreProcessor.getActiveTaskCount(),
        handlers: core_1.coreProcessor.getHandlerNames(),
    };
    res.json({ code: 200, data: stats });
});
exports.default = router;
//# sourceMappingURL=tasks.js.map