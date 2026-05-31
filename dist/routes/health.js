"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const monitoring_1 = require("../monitoring");
const core_1 = require("../core");
const logging_1 = require("../logging");
const router = (0, express_1.Router)();
router.get('/', (req, res) => {
    const health = {
        status: 'healthy',
        timestamp: new Date().toISOString(),
        uptime: process.uptime(),
        memory: process.memoryUsage(),
        services: {
            monitoring: 'healthy',
            core_processor: 'healthy',
            logging: logging_1.logger.getLevel(),
        },
        metrics: {
            active_tasks: core_1.coreProcessor.getActiveTaskCount(),
            queue_size: core_1.coreProcessor.getQueueSize(),
            registered_handlers: core_1.coreProcessor.getHandlerNames(),
        },
    };
    monitoring_1.monitoring.incrementCounter('health_checks', 1);
    res.json({ code: 200, data: health });
});
router.get('/metrics', (req, res) => {
    const startTime = Date.now() - 3600000;
    const endTime = Date.now();
    const report = monitoring_1.monitoring.generateReport(startTime, endTime);
    res.json({ code: 200, data: report });
});
router.get('/snapshots', (req, res) => {
    const limit = req.query.limit ? parseInt(req.query.limit, 10) : 10;
    const snapshots = monitoring_1.monitoring.getSnapshots(limit);
    res.json({ code: 200, data: snapshots });
});
exports.default = router;
//# sourceMappingURL=health.js.map