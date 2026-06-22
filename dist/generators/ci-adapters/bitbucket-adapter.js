"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.BitbucketPipelinesAdapter = void 0;
const path_1 = __importDefault(require("path"));
const yaml_1 = __importDefault(require("yaml"));
class BitbucketPipelinesAdapter {
    getFileName() {
        return 'bitbucket-pipelines.yml';
    }
    getFilePath(targetDir) {
        return path_1.default.join(targetDir, 'bitbucket-pipelines.yml');
    }
    render(pipeline, _config) {
        const pipelines = {};
        if (pipeline.trigger.push?.branches) {
            const branches = {};
            for (const branch of pipeline.trigger.push.branches) {
                branches[branch] = this.buildPipelineSteps(pipeline);
            }
            pipelines['branches'] = branches;
        }
        if (pipeline.trigger.pullRequest?.branches) {
            const prBranches = {};
            for (const branch of pipeline.trigger.pullRequest.branches) {
                prBranches[branch] = this.buildPipelineSteps(pipeline);
            }
            pipelines['pull-requests'] = prBranches;
        }
        const bitbucketConfig = {
            image: pipeline.defaultImage ?? `node:${this.getNodeVersion(pipeline)}`,
            pipelines,
        };
        if (pipeline.services && pipeline.services.length > 0) {
            bitbucketConfig['definitions'] = {
                services: this.buildServices(pipeline.services),
            };
        }
        return yaml_1.default.stringify(bitbucketConfig, { indent: 2, lineWidth: 120 });
    }
    getNodeVersion(pipeline) {
        for (const stage of pipeline.stages) {
            if (stage.image) {
                const match = stage.image.match(/node:([\d.]+)/);
                if (match && match[1])
                    return match[1];
            }
        }
        return '20';
    }
    buildPipelineSteps(pipeline) {
        const steps = [];
        for (const stage of pipeline.stages) {
            const step = this.buildStep(stage);
            if (step)
                steps.push(step);
        }
        return steps;
    }
    buildStep(stage) {
        const script = this.buildScript(stage);
        if (script.length === 0)
            return null;
        const step = {
            step: {
                name: stage.displayName,
                script,
            },
        };
        if (stage.services && stage.services.length > 0) {
            step['step']['services'] = stage.services.map(s => s.alias ?? s.name);
        }
        if (stage.artifacts?.paths) {
            step['step']['artifacts'] = stage.artifacts.paths;
        }
        if (stage.env && Object.keys(stage.env).length > 0) {
            step['step']['variables'] = stage.env;
        }
        if (stage.strategy?.matrix) {
            const parallel = [];
            const matrixEntries = Object.entries(stage.strategy.matrix);
            const [matrixKey, matrixValues] = matrixEntries[0] ?? ['', []];
            for (const value of matrixValues) {
                const parallelStep = structuredClone(step);
                const stepInner = parallelStep['step'];
                stepInner['name'] = `${stage.displayName} (${value})`;
                if (stepInner['variables']) {
                    stepInner['variables'][matrixKey.toUpperCase()] = value;
                }
                else {
                    stepInner['variables'] = { [matrixKey.toUpperCase()]: value };
                }
                parallel.push(parallelStep);
            }
            if (parallel.length > 0) {
                return { parallel };
            }
        }
        return step;
    }
    buildScript(stage) {
        const scripts = [];
        for (const step of stage.steps) {
            if (step.run) {
                if (step.name) {
                    scripts.push(`echo "${step.name}"`);
                }
                scripts.push(step.run);
            }
        }
        return scripts;
    }
    buildServices(services) {
        const result = {};
        for (const svc of services) {
            const serviceName = svc.alias ?? svc.name;
            const service = {
                image: svc.image,
            };
            if (svc.env && Object.keys(svc.env).length > 0) {
                service['variables'] = svc.env;
            }
            result[serviceName] = service;
        }
        return result;
    }
}
exports.BitbucketPipelinesAdapter = BitbucketPipelinesAdapter;
//# sourceMappingURL=bitbucket-adapter.js.map