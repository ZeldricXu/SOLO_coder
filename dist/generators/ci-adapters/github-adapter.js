"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.GitHubActionsAdapter = void 0;
const path_1 = __importDefault(require("path"));
const yaml_1 = __importDefault(require("yaml"));
class GitHubActionsAdapter {
    getFileName() {
        return 'ci.yml';
    }
    getFilePath(targetDir) {
        return path_1.default.join(targetDir, '.github', 'workflows', 'ci.yml');
    }
    render(pipeline, _config) {
        const workflow = {
            name: pipeline.name,
            on: this.buildTrigger(pipeline),
            jobs: this.buildJobs(pipeline),
        };
        return yaml_1.default.stringify(workflow, { indent: 2, lineWidth: 120 });
    }
    buildTrigger(pipeline) {
        const trigger = {};
        if (pipeline.trigger.push) {
            trigger['push'] = { branches: pipeline.trigger.push.branches };
        }
        if (pipeline.trigger.pullRequest) {
            trigger['pull_request'] = { branches: pipeline.trigger.pullRequest.branches };
        }
        return trigger;
    }
    buildJobs(pipeline) {
        const jobs = {};
        for (const stage of pipeline.stages) {
            jobs[stage.name] = this.buildJob(stage);
        }
        return jobs;
    }
    buildJob(stage) {
        const job = {
            'runs-on': stage.runsOn ?? 'ubuntu-latest',
            steps: this.buildSteps(stage),
        };
        if (stage.needs && stage.needs.length > 0) {
            job['needs'] = stage.needs.length === 1 ? stage.needs[0] : stage.needs;
        }
        if (stage.services && stage.services.length > 0) {
            job['services'] = this.buildServices(stage.services);
        }
        if (stage.strategy?.matrix) {
            job['strategy'] = { matrix: stage.strategy.matrix };
        }
        if (stage.env) {
            job['env'] = stage.env;
        }
        return job;
    }
    buildSteps(stage) {
        const steps = [];
        for (const step of stage.steps) {
            const stepObj = {};
            if (step.uses) {
                stepObj['uses'] = step.uses;
                if (step.with) {
                    stepObj['with'] = step.with;
                }
            }
            if (step.name) {
                stepObj['name'] = step.name;
            }
            if (step.run) {
                stepObj['run'] = step.run;
            }
            if (step.env && Object.keys(step.env).length > 0) {
                stepObj['env'] = step.env;
            }
            if (step.if) {
                stepObj['if'] = step.if;
            }
            steps.push(stepObj);
        }
        return steps;
    }
    buildServices(services) {
        const result = {};
        for (const svc of services) {
            const serviceName = svc.alias ?? svc.name;
            const service = {
                image: svc.image,
            };
            if (svc.env && Object.keys(svc.env).length > 0) {
                service['env'] = svc.env;
            }
            if (svc.ports && svc.ports.length > 0) {
                service['ports'] = svc.ports;
            }
            if (svc.healthCheck) {
                const opts = [];
                opts.push(`--health-cmd ${svc.healthCheck.command}`);
                if (svc.healthCheck.interval) {
                    opts.push(`--health-interval ${svc.healthCheck.interval}`);
                }
                if (svc.healthCheck.timeout) {
                    opts.push(`--health-timeout ${svc.healthCheck.timeout}`);
                }
                if (svc.healthCheck.retries) {
                    opts.push(`--health-retries ${svc.healthCheck.retries}`);
                }
                service['options'] = opts.join(' ');
            }
            result[serviceName] = service;
        }
        return result;
    }
}
exports.GitHubActionsAdapter = GitHubActionsAdapter;
//# sourceMappingURL=github-adapter.js.map