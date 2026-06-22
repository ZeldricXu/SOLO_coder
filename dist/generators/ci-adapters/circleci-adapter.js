"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.CircleCIAdapter = void 0;
const path_1 = __importDefault(require("path"));
const yaml_1 = __importDefault(require("yaml"));
class CircleCIAdapter {
    getFileName() {
        return '.circleci/config.yml';
    }
    getFilePath(targetDir) {
        return path_1.default.join(targetDir, '.circleci', 'config.yml');
    }
    render(pipeline, _config) {
        const orbs = {};
        const executors = {
            default: {
                docker: this.buildDockerExecutor(pipeline),
                working_directory: '~/repo',
            },
        };
        const jobs = {};
        for (const stage of pipeline.stages) {
            jobs[stage.name] = this.buildJob(stage);
        }
        const workflows = {
            ci: {
                jobs: this.buildWorkflowJobs(pipeline),
            },
        };
        const circleConfig = {
            version: '2.1',
            executors,
            jobs,
            workflows,
        };
        if (Object.keys(orbs).length > 0) {
            circleConfig['orbs'] = orbs;
        }
        return yaml_1.default.stringify(circleConfig, { indent: 2, lineWidth: 120 });
    }
    buildDockerExecutor(pipeline) {
        const docker = [
            {
                image: pipeline.defaultImage ?? `cimg/node:${this.getNodeVersion(pipeline)}`,
                auth: {
                    username: '$DOCKER_USERNAME',
                    password: '$DOCKER_PASSWORD',
                },
            },
        ];
        if (pipeline.services) {
            for (const svc of pipeline.services) {
                const service = {
                    image: svc.image,
                };
                if (svc.env && Object.keys(svc.env).length > 0) {
                    service['environment'] = svc.env;
                }
                docker.push(service);
            }
        }
        return docker;
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
    buildJob(stage) {
        const steps = [];
        steps.push('checkout');
        if (stage.services && stage.services.length > 0) {
            steps.push({
                'setup_remote_docker': {},
            });
        }
        for (const step of stage.steps) {
            if (step.run) {
                const runStep = {
                    run: {
                        name: step.name ?? 'Run',
                        command: step.run,
                    },
                };
                if (step.env && Object.keys(step.env).length > 0) {
                    runStep['run']['environment'] = step.env;
                }
                steps.push(runStep);
            }
            else if (step.uses) {
                steps.push(`orbs/${step.uses}`);
            }
        }
        if (stage.artifacts?.paths) {
            steps.push({
                'store_artifacts': {
                    path: stage.artifacts.paths[0] ?? 'dist',
                    destination: stage.artifacts.name ?? 'artifacts',
                },
            });
        }
        const job = {
            executor: 'default',
            steps,
        };
        if (stage.strategy?.matrix) {
            const matrixEntries = Object.entries(stage.strategy.matrix);
            const firstEntry = matrixEntries[0];
            if (firstEntry) {
                const [key, values] = firstEntry;
                job['parameters'] = {
                    [key]: {
                        type: 'string',
                        default: values[0] ?? '',
                    },
                };
            }
        }
        return job;
    }
    buildWorkflowJobs(pipeline) {
        const jobs = [];
        for (const stage of pipeline.stages) {
            const jobObj = {};
            if (stage.needs && stage.needs.length > 0) {
                jobObj[stage.name] = {
                    requires: stage.needs,
                };
                jobs.push(jobObj);
            }
            else {
                jobs.push(stage.name);
            }
        }
        return jobs;
    }
}
exports.CircleCIAdapter = CircleCIAdapter;
//# sourceMappingURL=circleci-adapter.js.map