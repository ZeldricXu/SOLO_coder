"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const zod_1 = require("zod");
const config_1 = require("../config");
const logging_1 = require("../logging");
const router = (0, express_1.Router)();
const CreateConfigSchema = zod_1.z.object({
    namespace: zod_1.z.string(),
    parameters: zod_1.z.record(zod_1.z.unknown()),
    enabled: zod_1.z.boolean().default(true),
});
const UpdateConfigSchema = zod_1.z.object({
    parameters: zod_1.z.record(zod_1.z.unknown()),
    enabled: zod_1.z.boolean().optional(),
});
const RollbackSchema = zod_1.z.object({
    target_version: zod_1.z.number().int().min(1),
    reason: zod_1.z.string(),
});
router.post('/', (req, res) => {
    try {
        const auth = req.auth;
        const body = CreateConfigSchema.parse(req.body);
        const config = config_1.configManager.createConfig(body.namespace, body.parameters, auth?.user_id || 'system', body.enabled);
        res.status(201).json({ code: 201, data: config });
    }
    catch (error) {
        logging_1.logger.error('Config creation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/', (req, res) => {
    const namespace = req.query.namespace;
    const configs = config_1.configManager.listConfigs(namespace);
    res.json({ code: 200, data: configs });
});
router.get('/:id', (req, res) => {
    const config = config_1.configManager.getConfig(req.params.id);
    if (!config) {
        res.status(404).json({ code: 404, error: 'Config not found' });
        return;
    }
    res.json({ code: 200, data: config });
});
router.put('/:id', (req, res) => {
    try {
        const auth = req.auth;
        const body = UpdateConfigSchema.parse(req.body);
        const config = config_1.configManager.updateConfig(req.params.id, body.parameters, auth?.user_id || 'system', body.enabled);
        if (!config) {
            res.status(404).json({ code: 404, error: 'Config not found' });
            return;
        }
        res.json({ code: 200, data: config });
    }
    catch (error) {
        logging_1.logger.error('Config update failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.post('/:id/rollback', (req, res) => {
    try {
        const auth = req.auth;
        const body = RollbackSchema.parse(req.body);
        const config = config_1.configManager.rollbackConfig(req.params.id, body.target_version, body.reason, auth?.user_id || 'system');
        if (!config) {
            res.status(404).json({ code: 404, error: 'Config or version not found' });
            return;
        }
        res.json({ code: 200, data: config });
    }
    catch (error) {
        logging_1.logger.error('Config rollback failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/:id/versions', (req, res) => {
    const versions = config_1.configManager.listConfigVersions(req.params.id);
    if (versions.length === 0) {
        res.status(404).json({ code: 404, error: 'Config not found' });
        return;
    }
    res.json({ code: 200, data: versions });
});
router.get('/:id/versions/:version', (req, res) => {
    const version = parseInt(req.params.version, 10);
    const config = config_1.configManager.getConfigVersion(req.params.id, version);
    if (!config) {
        res.status(404).json({ code: 404, error: 'Config version not found' });
        return;
    }
    res.json({ code: 200, data: config });
});
router.get('/:id/diff', (req, res) => {
    const versionA = parseInt(req.query.from, 10);
    const versionB = parseInt(req.query.to, 10);
    if (isNaN(versionA) || isNaN(versionB)) {
        res.status(400).json({ code: 400, error: 'Both from and to version parameters are required' });
        return;
    }
    const diff = config_1.configManager.diffConfigs(req.params.id, versionA, versionB);
    res.json({ code: 200, data: diff });
});
router.get('/:id/rollback-history', (req, res) => {
    const history = config_1.configManager.getRollbackHistory(req.params.id);
    res.json({ code: 200, data: history });
});
router.delete('/:id', (req, res) => {
    const deleted = config_1.configManager.deleteConfig(req.params.id);
    if (!deleted) {
        res.status(404).json({ code: 404, error: 'Config not found' });
        return;
    }
    res.json({ code: 200, message: 'Config deleted successfully' });
});
exports.default = router;
//# sourceMappingURL=config.js.map