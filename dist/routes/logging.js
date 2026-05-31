"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const logging_1 = require("../logging");
const router = (0, express_1.Router)();
router.post('/level', (req, res) => {
    const { level, module } = req.body;
    if (!level || !['debug', 'info', 'warn', 'error', 'fatal'].includes(level)) {
        res.status(400).json({ code: 400, error: 'Valid log level required: debug, info, warn, error, fatal' });
        return;
    }
    if (module) {
        logging_1.logger.setModuleLevel(module, level);
        res.json({ code: 200, message: `Log level for module '${module}' set to ${level}` });
    }
    else {
        logging_1.logger.setLevel(level);
        res.json({ code: 200, message: `Global log level set to ${level}` });
    }
});
router.get('/level', (req, res) => {
    const module = req.query.module;
    if (module) {
        const level = logging_1.logger.getModuleLevel(module);
        res.json({ code: 200, data: { module, level } });
    }
    else {
        const level = logging_1.logger.getLevel();
        res.json({ code: 200, data: { level } });
    }
});
router.get('/levels', (req, res) => {
    const levels = logging_1.logger.moduleLevels;
    const response = {};
    if (levels) {
        levels.forEach((v, k) => { response[k] = v; });
    }
    res.json({ code: 200, data: { global: logging_1.logger.getLevel(), modules: response } });
});
router.post('/message', (req, res) => {
    const { level, message, data, module } = req.body;
    if (!level || !message) {
        res.status(400).json({ code: 400, error: 'level and message are required' });
        return;
    }
    const logFn = logging_1.logger[level];
    if (typeof logFn !== 'function') {
        res.status(400).json({ code: 400, error: 'Invalid log level' });
        return;
    }
    logFn.call(logging_1.logger, message, { ...data, module });
    res.json({ code: 200, message: 'Message logged' });
});
router.post('/flush', (req, res) => {
    const transportName = req.query.transport;
    logging_1.logger.flush?.(transportName);
    res.json({ code: 200, message: 'Logs flushed' });
});
router.get('/config', (req, res) => {
    res.json({
        code: 200,
        data: {
            level: logging_1.logger.getLevel(),
            transports: ['console', 'file'],
            include_timestamp: true,
            format: 'json',
        }
    });
});
exports.default = router;
//# sourceMappingURL=logging.js.map