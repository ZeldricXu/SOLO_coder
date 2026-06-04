import { randomUUID } from 'crypto';
export class ParameterSweepService {
    constructor(scheduler) {
        this.sweeps = new Map();
        this.scheduler = scheduler;
    }
    async startSweep(config) {
        const sweepId = config.id || randomUUID();
        const parameterValues = [];
        for (let v = config.startValue; v <= config.endValue + 1e-10; v += config.stepSize) {
            parameterValues.push(parseFloat(v.toFixed(10)));
        }
        const totalJobs = parameterValues.length;
        const result = {
            sweepId,
            parameterValues,
            results: new Array(totalJobs).fill(NaN),
            status: 'running',
            progress: 0,
            totalJobs,
            completedJobs: 0,
            errors: [],
        };
        const jobIds = [];
        for (let i = 0; i < parameterValues.length; i++) {
            const paramValue = parameterValues[i];
            const modifiedConfig = this.applyParameter(config.baseConfig, config.parameterPath, paramValue);
            const modifiedScene = this.applySceneParameter(config.scene, config.parameterPath, paramValue);
            try {
                const jobId = await this.scheduler.scheduleSimulation(modifiedScene, modifiedConfig, config.baseConfig.duration);
                jobIds.push(jobId);
            }
            catch (err) {
                result.errors.push(`Job ${i} (param=${paramValue}): ${err.message}`);
                result.completedJobs++;
                result.results[i] = NaN;
            }
        }
        const sweepJob = {
            config,
            result,
            jobIds,
            startTime: Date.now(),
        };
        this.sweeps.set(sweepId, sweepJob);
        this.pollResults(sweepId);
        return sweepId;
    }
    async pollResults(sweepId) {
        const sweep = this.sweeps.get(sweepId);
        if (!sweep)
            return;
        const checkInterval = setInterval(async () => {
            let completed = 0;
            for (let i = 0; i < sweep.jobIds.length; i++) {
                if (!isNaN(sweep.result.results[i])) {
                    completed++;
                    continue;
                }
                try {
                    const status = this.scheduler.getJobStatus(sweep.jobIds[i]);
                    if (status && (status.status === 'completed' || status.status === 'failed')) {
                        if (status.status === 'completed') {
                            try {
                                const simResult = await this.scheduler.getJobResult(sweep.jobIds[i]);
                                sweep.result.results[i] = this.extractResult(simResult, sweep.config.resultExtractor);
                            }
                            catch {
                                sweep.result.results[i] = NaN;
                            }
                        }
                        else {
                            sweep.result.results[i] = NaN;
                            sweep.result.errors.push(`Job ${i} failed`);
                        }
                        completed++;
                    }
                }
                catch {
                    sweep.result.errors.push(`Job ${i}: status check failed`);
                    sweep.result.results[i] = NaN;
                    completed++;
                }
            }
            sweep.result.completedJobs = completed;
            sweep.result.progress = completed / sweep.result.totalJobs;
            if (completed >= sweep.result.totalJobs) {
                sweep.result.status = sweep.result.errors.length > 0 ? 'completed' : 'completed';
                clearInterval(checkInterval);
            }
        }, 2000);
    }
    applyParameter(config, path, value) {
        const modified = JSON.parse(JSON.stringify(config));
        const parts = path.split('.');
        let current = modified;
        for (let i = 0; i < parts.length - 1; i++) {
            if (current[parts[i]] === undefined)
                current[parts[i]] = {};
            current = current[parts[i]];
        }
        current[parts[parts.length - 1]] = value;
        return modified;
    }
    applySceneParameter(scene, path, value) {
        const modified = JSON.parse(JSON.stringify(scene));
        if (path.startsWith('scene.')) {
            const scenePath = path.substring(6);
            const parts = scenePath.split('.');
            let current = modified;
            for (let i = 0; i < parts.length - 1; i++) {
                if (current[parts[i]] === undefined)
                    current[parts[i]] = {};
                current = current[parts[i]];
            }
            current[parts[parts.length - 1]] = value;
        }
        return modified;
    }
    extractResult(simResult, extractor) {
        if (!simResult)
            return NaN;
        if (extractor === 'finalVelocity') {
            const frames = simResult.frames || [];
            if (frames.length === 0)
                return NaN;
            const lastFrame = frames[frames.length - 1];
            const objects = lastFrame.objects || [];
            if (objects.length === 0)
                return NaN;
            const vel = objects[0].velocity;
            return Math.sqrt(vel.x ** 2 + vel.y ** 2 + vel.z ** 2);
        }
        if (extractor === 'finalPosition') {
            const frames = simResult.frames || [];
            if (frames.length === 0)
                return NaN;
            const lastFrame = frames[frames.length - 1];
            const objects = lastFrame.objects || [];
            if (objects.length === 0)
                return NaN;
            return objects[0].position.y;
        }
        if (extractor === 'maxSensorValue') {
            const sensorData = simResult.sensorData || {};
            const keys = Object.keys(sensorData);
            if (keys.length === 0)
                return NaN;
            const records = sensorData[keys[0]] || [];
            const values = records.map((r) => typeof r.value === 'number' ? r.value : Math.sqrt(r.value.x ** 2 + r.value.y ** 2 + r.value.z ** 2));
            return Math.max(...values);
        }
        try {
            const fn = new Function('result', `return ${extractor}`);
            return fn(simResult);
        }
        catch {
            return NaN;
        }
    }
    getSweepStatus(sweepId) {
        const sweep = this.sweeps.get(sweepId);
        return sweep ? { ...sweep.result } : null;
    }
    cancelSweep(sweepId) {
        const sweep = this.sweeps.get(sweepId);
        if (!sweep)
            return false;
        for (const jobId of sweep.jobIds) {
            try {
                this.scheduler.cancelJob(jobId);
            }
            catch { }
        }
        sweep.result.status = 'failed';
        sweep.result.errors.push('Sweep cancelled by user');
        return true;
    }
}
//# sourceMappingURL=ParameterSweepService.js.map