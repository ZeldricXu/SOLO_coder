"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PromptExperimentService = exports.promptExperimentService = void 0;
const events_1 = require("events");
const utils_1 = require("../shared/utils");
const logging_1 = require("../logging");
const monitoring_1 = require("../monitoring");
class PromptExperimentService extends events_1.EventEmitter {
    prompts = new Map();
    promptVersions = new Map();
    experiments = new Map();
    trialResults = new Map();
    maxTrialsPerExperiment = 10000;
    maxVersionsPerPrompt = 50;
    createPrompt(name, content, variables, createdBy, description, tags = [], metadata) {
        const promptId = (0, utils_1.generateId)('pr');
        const versionId = (0, utils_1.generateId)('pv');
        const now = (0, utils_1.nowISO)();
        const version = {
            version_id: versionId,
            prompt_id: promptId,
            version: 1,
            content,
            variables,
            metadata: metadata || {},
            created_at: now,
            created_by: createdBy,
            description,
        };
        const prompt = {
            prompt_id: promptId,
            name,
            description,
            latest_version: 1,
            versions: [versionId],
            created_at: now,
            updated_at: now,
            created_by: createdBy,
            tags,
            is_active: true,
        };
        this.prompts.set(promptId, prompt);
        this.promptVersions.set(versionId, version);
        logging_1.logger.info('Prompt created', { prompt_id: promptId, name, version: 1 }, createdBy);
        this.emit('prompt.created', prompt, version);
        return { prompt, version };
    }
    createVersion(promptId, content, variables, createdBy, description, metadata) {
        const prompt = this.prompts.get(promptId);
        if (!prompt) {
            logging_1.logger.warn('Prompt not found for version creation', { prompt_id: promptId }, createdBy);
            return null;
        }
        const newVersion = prompt.latest_version + 1;
        const versionId = (0, utils_1.generateId)('pv');
        const now = (0, utils_1.nowISO)();
        const version = {
            version_id: versionId,
            prompt_id: promptId,
            version: newVersion,
            content,
            variables,
            metadata: metadata || {},
            created_at: now,
            created_by: createdBy,
            description,
        };
        this.promptVersions.set(versionId, version);
        prompt.versions.push(versionId);
        prompt.latest_version = newVersion;
        prompt.updated_at = now;
        if (prompt.versions.length > this.maxVersionsPerPrompt) {
            const removed = prompt.versions.shift();
            if (removed) {
                this.promptVersions.delete(removed);
            }
        }
        logging_1.logger.info('Prompt version created', { prompt_id: promptId, version: newVersion }, createdBy);
        this.emit('prompt.version_created', prompt, version);
        return version;
    }
    getPrompt(promptId) {
        return this.prompts.get(promptId) || null;
    }
    getPromptVersion(versionId) {
        return this.promptVersions.get(versionId) || null;
    }
    getPromptVersions(promptId) {
        const prompt = this.prompts.get(promptId);
        if (!prompt)
            return [];
        return prompt.versions
            .map((vid) => this.promptVersions.get(vid))
            .filter((v) => v !== undefined)
            .sort((a, b) => b.version - a.version);
    }
    renderPrompt(versionId, variables) {
        const version = this.promptVersions.get(versionId);
        if (!version)
            return null;
        let rendered = version.content;
        for (const [key, value] of Object.entries(variables)) {
            const regex = new RegExp(`\\{\\{\\s*${key}\\s*\\}\\}`, 'g');
            rendered = rendered.replace(regex, value);
        }
        return rendered;
    }
    createExperiment(name, promptId, variants, createdBy, description, trafficPercentage = 50) {
        const prompt = this.prompts.get(promptId);
        if (!prompt) {
            logging_1.logger.warn('Prompt not found for experiment', { prompt_id: promptId }, createdBy);
            return null;
        }
        const experimentId = (0, utils_1.generateId)('exp');
        const now = (0, utils_1.nowISO)();
        const experimentVariants = variants.map((v, idx) => ({
            variant_id: (0, utils_1.generateId)('var'),
            name: v.name,
            prompt_version_id: v.prompt_version_id,
            weight: v.weight,
            is_control: v.is_control || (idx === 0),
        }));
        const totalWeight = experimentVariants.reduce((sum, v) => sum + v.weight, 0);
        if (totalWeight !== 100) {
            logging_1.logger.warn('Variant weights do not sum to 100', { experiment_id: experimentId, total_weight: totalWeight }, createdBy);
        }
        const experiment = {
            experiment_id: experimentId,
            name,
            description,
            prompt_id: promptId,
            variants: experimentVariants,
            status: 'draft',
            traffic_percentage: trafficPercentage,
            created_at: now,
            created_by: createdBy,
        };
        this.experiments.set(experimentId, experiment);
        this.trialResults.set(experimentId, []);
        logging_1.logger.info('Experiment created', { experiment_id: experimentId, name, prompt_id: promptId }, createdBy);
        this.emit('experiment.created', experiment);
        return experiment;
    }
    startExperiment(experimentId) {
        const experiment = this.experiments.get(experimentId);
        if (!experiment || experiment.status !== 'draft') {
            return false;
        }
        experiment.status = 'running';
        experiment.started_at = (0, utils_1.nowISO)();
        logging_1.logger.info('Experiment started', { experiment_id: experimentId, name: experiment.name });
        this.emit('experiment.started', experiment);
        return true;
    }
    pauseExperiment(experimentId) {
        const experiment = this.experiments.get(experimentId);
        if (!experiment || experiment.status !== 'running') {
            return false;
        }
        experiment.status = 'paused';
        logging_1.logger.info('Experiment paused', { experiment_id: experimentId, name: experiment.name });
        this.emit('experiment.paused', experiment);
        return true;
    }
    resumeExperiment(experimentId) {
        const experiment = this.experiments.get(experimentId);
        if (!experiment || experiment.status !== 'paused') {
            return false;
        }
        experiment.status = 'running';
        logging_1.logger.info('Experiment resumed', { experiment_id: experimentId, name: experiment.name });
        this.emit('experiment.resumed', experiment);
        return true;
    }
    endExperiment(experimentId, winner) {
        const experiment = this.experiments.get(experimentId);
        if (!experiment || (experiment.status !== 'running' && experiment.status !== 'paused')) {
            return false;
        }
        experiment.status = 'completed';
        experiment.ended_at = (0, utils_1.nowISO)();
        experiment.winner = winner;
        logging_1.logger.info('Experiment ended', { experiment_id: experimentId, name: experiment.name, winner });
        this.emit('experiment.ended', experiment);
        return true;
    }
    selectVariant(experimentId) {
        const experiment = this.experiments.get(experimentId);
        if (!experiment || experiment.status !== 'running') {
            return null;
        }
        if (Math.random() * 100 > experiment.traffic_percentage) {
            return null;
        }
        const random = Math.random() * 100;
        let cumulative = 0;
        for (const variant of experiment.variants) {
            cumulative += variant.weight;
            if (random <= cumulative) {
                return variant;
            }
        }
        return experiment.variants[experiment.variants.length - 1];
    }
    recordTrial(experimentId, variantId, input, output, metrics, latencyMs, success = true, error) {
        const experiment = this.experiments.get(experimentId);
        if (!experiment)
            return null;
        const trial = {
            trial_id: (0, utils_1.generateId)('trl'),
            experiment_id: experimentId,
            variant_id: variantId,
            input,
            output,
            metrics,
            latency_ms: latencyMs,
            timestamp: (0, utils_1.nowISO)(),
            success,
            error,
        };
        const trials = this.trialResults.get(experimentId);
        if (trials) {
            trials.push(trial);
            if (trials.length > this.maxTrialsPerExperiment) {
                trials.shift();
            }
        }
        monitoring_1.monitoring.incrementCounter('experiment_trials', 1, { experiment_id: experimentId, variant_id: variantId });
        monitoring_1.monitoring.recordLatency('trial_latency', latencyMs, { experiment_id: experimentId, variant_id: variantId });
        this.emit('trial.recorded', trial);
        return trial;
    }
    getExperimentStats(experimentId) {
        const experiment = this.experiments.get(experimentId);
        const trials = this.trialResults.get(experimentId);
        if (!experiment || !trials)
            return null;
        const successfulTrials = trials.filter((t) => t.success);
        const latencies = trials.map((t) => t.latency_ms);
        const percentiles = (0, utils_1.calculatePercentiles)(latencies, [50, 95, 99]);
        const variantStats = {};
        for (const variant of experiment.variants) {
            const variantTrials = trials.filter((t) => t.variant_id === variant.variant_id);
            const variantSuccessful = variantTrials.filter((t) => t.success);
            const variantLatencies = variantTrials.map((t) => t.latency_ms);
            const metrics = {};
            for (const trial of variantSuccessful) {
                for (const [key, value] of Object.entries(trial.metrics)) {
                    metrics[key] = (metrics[key] || 0) + value;
                }
            }
            for (const key of Object.keys(metrics)) {
                metrics[key] = metrics[key] / variantSuccessful.length;
            }
            variantStats[variant.variant_id] = {
                trials: variantTrials.length,
                success_rate: variantTrials.length > 0 ? variantSuccessful.length / variantTrials.length : 0,
                avg_latency_ms: variantLatencies.length > 0 ? variantLatencies.reduce((a, b) => a + b, 0) / variantLatencies.length : 0,
                metrics,
            };
        }
        return {
            total_trials: trials.length,
            success_rate: trials.length > 0 ? successfulTrials.length / trials.length : 0,
            avg_latency_ms: latencies.length > 0 ? latencies.reduce((a, b) => a + b, 0) / latencies.length : 0,
            variants: variantStats,
        };
    }
    listPrompts(includeInactive = false) {
        return Array.from(this.prompts.values()).filter((p) => includeInactive || p.is_active);
    }
    listExperiments(status) {
        let exps = Array.from(this.experiments.values());
        if (status) {
            exps = exps.filter((e) => e.status === status);
        }
        return exps.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());
    }
    getExperiment(experimentId) {
        return this.experiments.get(experimentId) || null;
    }
    getTrials(experimentId, variantId, limit) {
        const trials = this.trialResults.get(experimentId);
        if (!trials)
            return [];
        let filtered = trials;
        if (variantId) {
            filtered = trials.filter((t) => t.variant_id === variantId);
        }
        const sorted = [...filtered].sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
        return limit ? sorted.slice(0, limit) : sorted;
    }
    compareExperiments(experimentIdA, experimentIdB) {
        const statsA = this.getExperimentStats(experimentIdA);
        const statsB = this.getExperimentStats(experimentIdB);
        const expA = this.getExperiment(experimentIdA);
        const expB = this.getExperiment(experimentIdB);
        if (!statsA || !statsB || !expA || !expB)
            return null;
        return {
            experiment_a: { id: experimentIdA, name: expA.name, ...statsA },
            experiment_b: { id: experimentIdB, name: expB.name, ...statsB },
            comparison: {
                trials_delta: statsB.total_trials - statsA.total_trials,
                success_rate_delta: statsB.success_rate - statsA.success_rate,
                latency_delta: statsB.avg_latency_ms - statsA.avg_latency_ms,
            },
        };
    }
    archivePrompt(promptId) {
        const prompt = this.prompts.get(promptId);
        if (!prompt)
            return false;
        prompt.is_active = false;
        prompt.updated_at = (0, utils_1.nowISO)();
        logging_1.logger.info('Prompt archived', { prompt_id: promptId });
        this.emit('prompt.archived', promptId);
        return true;
    }
    unarchivePrompt(promptId) {
        const prompt = this.prompts.get(promptId);
        if (!prompt)
            return false;
        prompt.is_active = true;
        prompt.updated_at = (0, utils_1.nowISO)();
        logging_1.logger.info('Prompt unarchived', { prompt_id: promptId });
        this.emit('prompt.unarchived', promptId);
        return true;
    }
    searchPrompts(query, tags) {
        const lowerQuery = query.toLowerCase();
        return this.listPrompts().filter((p) => {
            const matchesQuery = p.name.toLowerCase().includes(lowerQuery) ||
                (p.description?.toLowerCase().includes(lowerQuery));
            const matchesTags = !tags || tags.some((t) => p.tags.includes(t));
            return matchesQuery && matchesTags;
        });
    }
}
exports.PromptExperimentService = PromptExperimentService;
exports.promptExperimentService = new PromptExperimentService();
//# sourceMappingURL=index.js.map