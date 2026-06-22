"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.GitLabCIAdapter = void 0;
const path_1 = __importDefault(require("path"));
const yaml_1 = __importDefault(require("yaml"));
class GitLabCIAdapter {
    getFileName() {
        return '.gitlab-ci.yml';
    }
    getFilePath(targetDir) {
        return path_1.default.join(targetDir, '.gitlab-ci.yml');
    }
    render(pipeline, _config) {
        const ciConfig = {
            image: pipeline.defaultImage ?? `node:${this.getNodeVersion(pipeline)}`,
            stages: pipeline.stages.map(s => s.name),
        };
        if (pipeline.env && Object.keys(pipeline.env).length > 0) {
            ciConfig['variables'] = pipeline.env;
        }
        if (pipeline.services && pipeline.services.length > 0) {
            ciConfig['services'] = this.buildServices(pipeline.services);
            if (pipeline.env) {
                const vars = ciConfig['variables'];
                for (const svc of pipeline.services) {
                    if (svc.env) {
                        for (const [key, value] of Object.entries(svc.env)) {
                            vars[key] = value;
                        }
                    }
                }
            }
        }
        const cache = this.buildCache(_config);
        if (cache) {
            ciConfig['cache'] = cache;
        }
        for (const stage of pipeline.stages) {
            ciConfig[stage.name] = this.buildJob(stage, _config);
        }
        return yaml_1.default.stringify(ciConfig, { indent: 2, lineWidth: 120 });
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
    buildServices(services) {
        return services.map(svc => ({
            name: svc.image,
            alias: svc.alias ?? svc.name,
        }));
    }
    buildJob(stage, _config) {
        const job = {
            stage: stage.name,
            script: this.buildScript(stage),
        };
        if (stage.strategy?.matrix) {
            job['parallel'] = {
                matrix: Object.entries(stage.strategy.matrix).map(([key, values]) => ({
                    [key.toUpperCase()]: values,
                })),
            };
        }
        if (stage.artifacts) {
            job['artifacts'] = {
                paths: stage.artifacts.paths,
                expire_in: stage.artifacts.expireIn ?? '1 week',
            };
        }
        if (stage.env && Object.keys(stage.env).length > 0) {
            job['variables'] = stage.env;
        }
        if (stage.services && stage.services.length > 0) {
            job['services'] = this.buildServices(stage.services);
        }
        return job;
    }
    buildScript(stage) {
        const scripts = [];
        for (const step of stage.steps) {
            if (step.run) {
                if (step.name) {
                    scripts.push(`echo "=== ${step.name} ==="`);
                }
                scripts.push(step.run);
            }
        }
        return scripts;
    }
    buildCache(config) {
        const cachePaths = ['node_modules/'];
        if (config.packageManager === 'npm') {
            cachePaths.push('.npm/');
        }
        else if (config.packageManager === 'yarn') {
            cachePaths.push('.yarn/');
        }
        else if (config.packageManager === 'pnpm') {
            cachePaths.push('.pnpm/');
        }
        return {
            key: {
                files: ['package.json', 'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml'],
            },
            paths: cachePaths,
        };
    }
}
exports.GitLabCIAdapter = GitLabCIAdapter;
//# sourceMappingURL=gitlab-adapter.js.map