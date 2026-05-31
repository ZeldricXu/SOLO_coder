"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const features_1 = require("../features");
const logging_1 = require("../logging");
const router = (0, express_1.Router)();
router.post('/', (req, res) => {
    try {
        const auth = req.auth;
        const { name, value_type, dimensions, description, default_value, is_online, is_offline, ttl_seconds, tags } = req.body;
        const feature = features_1.featureStorageService.registerFeature(name, value_type, dimensions || [], auth?.user_id || 'system', {
            description,
            default_value,
            is_online,
            is_offline,
            ttl_seconds,
            tags,
        });
        res.status(201).json({ code: 201, data: feature });
    }
    catch (error) {
        logging_1.logger.error('Feature registration failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/', (req, res) => {
    const tags = req.query.tags ? req.query.tags.split(',') : undefined;
    const features = features_1.featureStorageService.listFeatures(tags);
    res.json({ code: 200, data: features });
});
router.get('/:id', (req, res) => {
    const feature = features_1.featureStorageService.getFeature(req.params.id);
    if (!feature) {
        res.status(404).json({ code: 404, error: 'Feature not found' });
        return;
    }
    res.json({ code: 200, data: feature });
});
router.put('/:id', (req, res) => {
    const updated = features_1.featureStorageService.updateFeature(req.params.id, req.body);
    if (!updated) {
        res.status(404).json({ code: 404, error: 'Feature not found' });
        return;
    }
    res.json({ code: 200, data: updated });
});
router.post('/online/:featureId/:entityId', (req, res) => {
    const { value, dimensions } = req.body;
    const stored = features_1.featureStorageService.setOnlineFeatureValue(req.params.featureId, req.params.entityId, value, dimensions || {});
    if (!stored) {
        res.status(404).json({ code: 404, error: 'Feature not found or not available for online serving' });
        return;
    }
    res.json({ code: 200, data: stored });
});
router.get('/online/:featureId/:entityId', (req, res) => {
    const value = features_1.featureStorageService.getOnlineFeatureValue(req.params.featureId, req.params.entityId);
    res.json({ code: 200, data: value });
});
router.post('/online/batch/:entityId', (req, res) => {
    const { feature_ids } = req.body;
    const values = features_1.featureStorageService.getOnlineFeatures(feature_ids, req.params.entityId);
    res.json({ code: 200, data: values });
});
router.post('/offline/:featureId/:entityId', (req, res) => {
    const { value, timestamp, dimensions } = req.body;
    const stored = features_1.featureStorageService.recordOfflineFeatureValue(req.params.featureId, req.params.entityId, value, timestamp, dimensions || {});
    if (!stored) {
        res.status(404).json({ code: 404, error: 'Feature not found or not available for offline storage' });
        return;
    }
    res.json({ code: 200, data: stored });
});
router.get('/offline/:featureId/:entityId', (req, res) => {
    const startTime = req.query.start_time ? parseInt(req.query.start_time, 10) : undefined;
    const endTime = req.query.end_time ? parseInt(req.query.end_time, 10) : undefined;
    const limit = req.query.limit ? parseInt(req.query.limit, 10) : undefined;
    const values = features_1.featureStorageService.getOfflineFeatureValues(req.params.featureId, req.params.entityId, startTime, endTime, limit);
    res.json({ code: 200, data: values });
});
router.post('/backfill', (req, res) => {
    const { feature_id, entity_ids } = req.body;
    const job = features_1.featureStorageService.backfillFeature(feature_id, entity_ids, async (entityId) => {
        return { entity_id: entityId, backfilled_at: new Date().toISOString() };
    });
    res.status(201).json({ code: 201, data: job });
});
router.get('/backfill/:jobId', (req, res) => {
    const job = features_1.featureStorageService.getBackfillJob(req.params.jobId);
    if (!job) {
        res.status(404).json({ code: 404, error: 'Backfill job not found' });
        return;
    }
    res.json({ code: 200, data: job });
});
router.get('/backfill', (req, res) => {
    const featureId = req.query.feature_id;
    const jobs = features_1.featureStorageService.listBackfillJobs(featureId);
    res.json({ code: 200, data: jobs });
});
router.post('/groups', (req, res) => {
    try {
        const auth = req.auth;
        const { name, feature_ids, description } = req.body;
        const group = features_1.featureStorageService.createFeatureGroup(name, feature_ids, auth?.user_id || 'system', description);
        res.status(201).json({ code: 201, data: group });
    }
    catch (error) {
        logging_1.logger.error('Feature group creation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/groups/:groupId', (req, res) => {
    const group = features_1.featureStorageService.getFeatureGroup(req.params.groupId);
    if (!group) {
        res.status(404).json({ code: 404, error: 'Feature group not found' });
        return;
    }
    res.json({ code: 200, data: group });
});
router.get('/groups/:groupId/online/:entityId', (req, res) => {
    const values = features_1.featureStorageService.getOnlineFeaturesByGroup(req.params.groupId, req.params.entityId);
    if (!values) {
        res.status(404).json({ code: 404, error: 'Feature group not found' });
        return;
    }
    res.json({ code: 200, data: values });
});
router.post('/online/config/:featureId', (req, res) => {
    const updated = features_1.featureStorageService.configureOnlineFeature(req.params.featureId, req.body);
    if (!updated) {
        res.status(404).json({ code: 404, error: 'Feature not found' });
        return;
    }
    res.json({ code: 200, message: 'Online feature config updated' });
});
router.get('/online/config/:featureId', (req, res) => {
    const config = features_1.featureStorageService.getOnlineConfig(req.params.featureId);
    if (!config) {
        res.status(404).json({ code: 404, error: 'Feature not found' });
        return;
    }
    res.json({ code: 200, data: config });
});
router.get('/consistency/:featureId/:entityId', (req, res) => {
    const result = features_1.featureStorageService.checkConsistency(req.params.featureId, req.params.entityId);
    res.json({ code: 200, data: result });
});
router.post('/cache/invalidate', (req, res) => {
    const { feature_id, entity_id } = req.body;
    features_1.featureStorageService.invalidateCache(feature_id, entity_id);
    res.json({ code: 200, message: 'Cache invalidated' });
});
router.get('/stats/summary', (req, res) => {
    const stats = features_1.featureStorageService.getStats();
    res.json({ code: 200, data: stats });
});
exports.default = router;
//# sourceMappingURL=features.js.map