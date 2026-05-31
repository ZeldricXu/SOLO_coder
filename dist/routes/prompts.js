"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const prompt_1 = require("../prompt");
const logging_1 = require("../logging");
const router = (0, express_1.Router)();
router.post('/', (req, res) => {
    try {
        const auth = req.auth;
        const { name, content, variables, description, tags, metadata } = req.body;
        const result = prompt_1.promptExperimentService.createPrompt(name, content, variables || [], auth?.user_id || 'system', description, tags, metadata);
        res.status(201).json({ code: 201, data: result });
    }
    catch (error) {
        logging_1.logger.error('Prompt creation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/', (req, res) => {
    const include_inactive = req.query.include_inactive === 'true';
    const prompts = prompt_1.promptExperimentService.listPrompts(include_inactive);
    res.json({ code: 200, data: prompts });
});
router.get('/search', (req, res) => {
    const query = req.query.q;
    const tags = req.query.tags ? req.query.tags.split(',') : undefined;
    if (!query) {
        res.status(400).json({ code: 400, error: 'Query parameter q is required' });
        return;
    }
    const prompts = prompt_1.promptExperimentService.searchPrompts(query, tags);
    res.json({ code: 200, data: prompts });
});
router.get('/:id', (req, res) => {
    const prompt = prompt_1.promptExperimentService.getPrompt(req.params.id);
    if (!prompt) {
        res.status(404).json({ code: 404, error: 'Prompt not found' });
        return;
    }
    res.json({ code: 200, data: prompt });
});
router.post('/:id/versions', (req, res) => {
    try {
        const auth = req.auth;
        const { content, variables, description, metadata } = req.body;
        const version = prompt_1.promptExperimentService.createVersion(req.params.id, content, variables || [], auth?.user_id || 'system', description, metadata);
        if (!version) {
            res.status(404).json({ code: 404, error: 'Prompt not found' });
            return;
        }
        res.status(201).json({ code: 201, data: version });
    }
    catch (error) {
        logging_1.logger.error('Prompt version creation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/:id/versions', (req, res) => {
    const versions = prompt_1.promptExperimentService.getPromptVersions(req.params.id);
    if (versions.length === 0) {
        res.status(404).json({ code: 404, error: 'Prompt not found' });
        return;
    }
    res.json({ code: 200, data: versions });
});
router.get('/versions/:versionId', (req, res) => {
    const version = prompt_1.promptExperimentService.getPromptVersion(req.params.versionId);
    if (!version) {
        res.status(404).json({ code: 404, error: 'Prompt version not found' });
        return;
    }
    res.json({ code: 200, data: version });
});
router.post('/versions/:versionId/render', (req, res) => {
    const rendered = prompt_1.promptExperimentService.renderPrompt(req.params.versionId, req.body || {});
    if (rendered === null) {
        res.status(404).json({ code: 404, error: 'Prompt version not found' });
        return;
    }
    res.json({ code: 200, data: { rendered } });
});
router.post('/:id/archive', (req, res) => {
    const archived = prompt_1.promptExperimentService.archivePrompt(req.params.id);
    if (!archived) {
        res.status(404).json({ code: 404, error: 'Prompt not found' });
        return;
    }
    res.json({ code: 200, message: 'Prompt archived' });
});
router.post('/:id/unarchive', (req, res) => {
    const unarchived = prompt_1.promptExperimentService.unarchivePrompt(req.params.id);
    if (!unarchived) {
        res.status(404).json({ code: 404, error: 'Prompt not found' });
        return;
    }
    res.json({ code: 200, message: 'Prompt unarchived' });
});
router.post('/experiments', (req, res) => {
    try {
        const auth = req.auth;
        const { name, prompt_id, variants, description, traffic_percentage } = req.body;
        const experiment = prompt_1.promptExperimentService.createExperiment(name, prompt_id, variants, auth?.user_id || 'system', description, traffic_percentage);
        if (!experiment) {
            res.status(404).json({ code: 404, error: 'Prompt not found' });
            return;
        }
        res.status(201).json({ code: 201, data: experiment });
    }
    catch (error) {
        logging_1.logger.error('Experiment creation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/experiments', (req, res) => {
    const status = req.query.status;
    const experiments = prompt_1.promptExperimentService.listExperiments(status);
    res.json({ code: 200, data: experiments });
});
router.get('/experiments/:id', (req, res) => {
    const experiment = prompt_1.promptExperimentService.getExperiment(req.params.id);
    if (!experiment) {
        res.status(404).json({ code: 404, error: 'Experiment not found' });
        return;
    }
    res.json({ code: 200, data: experiment });
});
router.post('/experiments/:id/start', (req, res) => {
    const started = prompt_1.promptExperimentService.startExperiment(req.params.id);
    if (!started) {
        res.status(404).json({ code: 404, error: 'Experiment not found or already running' });
        return;
    }
    res.json({ code: 200, message: 'Experiment started' });
});
router.post('/experiments/:id/pause', (req, res) => {
    const paused = prompt_1.promptExperimentService.pauseExperiment(req.params.id);
    if (!paused) {
        res.status(404).json({ code: 404, error: 'Experiment not found or not running' });
        return;
    }
    res.json({ code: 200, message: 'Experiment paused' });
});
router.post('/experiments/:id/resume', (req, res) => {
    const resumed = prompt_1.promptExperimentService.resumeExperiment(req.params.id);
    if (!resumed) {
        res.status(404).json({ code: 404, error: 'Experiment not found or not paused' });
        return;
    }
    res.json({ code: 200, message: 'Experiment resumed' });
});
router.post('/experiments/:id/end', (req, res) => {
    const { winner } = req.body;
    const ended = prompt_1.promptExperimentService.endExperiment(req.params.id, winner);
    if (!ended) {
        res.status(404).json({ code: 404, error: 'Experiment not found or already completed' });
        return;
    }
    res.json({ code: 200, message: 'Experiment ended' });
});
router.get('/experiments/:id/stats', (req, res) => {
    const stats = prompt_1.promptExperimentService.getExperimentStats(req.params.id);
    if (!stats) {
        res.status(404).json({ code: 404, error: 'Experiment not found' });
        return;
    }
    res.json({ code: 200, data: stats });
});
router.get('/experiments/:id/trials', (req, res) => {
    const variant_id = req.query.variant_id;
    const limit = req.query.limit ? parseInt(req.query.limit, 10) : undefined;
    const trials = prompt_1.promptExperimentService.getTrials(req.params.id, variant_id, limit);
    res.json({ code: 200, data: trials });
});
router.post('/experiments/:id/trials', (req, res) => {
    try {
        const { variant_id, input, output, metrics, latency_ms, success, error } = req.body;
        const trial = prompt_1.promptExperimentService.recordTrial(req.params.id, variant_id, input || {}, output || '', metrics || {}, latency_ms || 0, success !== false, error);
        if (!trial) {
            res.status(404).json({ code: 404, error: 'Experiment not found' });
            return;
        }
        res.status(201).json({ code: 201, data: trial });
    }
    catch (error) {
        logging_1.logger.error('Trial recording failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.get('/experiments/compare', (req, res) => {
    const exp_a = req.query.exp_a;
    const exp_b = req.query.exp_b;
    if (!exp_a || !exp_b) {
        res.status(400).json({ code: 400, error: 'Both exp_a and exp_b parameters are required' });
        return;
    }
    const comparison = prompt_1.promptExperimentService.compareExperiments(exp_a, exp_b);
    if (!comparison) {
        res.status(404).json({ code: 404, error: 'One or both experiments not found' });
        return;
    }
    res.json({ code: 200, data: comparison });
});
exports.default = router;
//# sourceMappingURL=prompts.js.map