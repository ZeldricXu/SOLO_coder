import path from 'path';
import yaml from 'yaml';
import type { CiAdapter, CiPipeline, CiStage, CiService, ProjectConfig } from '../../types.js';

export class GitLabCIAdapter implements CiAdapter {
  getFileName(): string {
    return '.gitlab-ci.yml';
  }

  getFilePath(targetDir: string): string {
    return path.join(targetDir, '.gitlab-ci.yml');
  }

  render(pipeline: CiPipeline, _config: ProjectConfig): string {
    const ciConfig: Record<string, unknown> = {
      image: pipeline.defaultImage ?? `node:${this.getNodeVersion(pipeline)}`,
      stages: pipeline.stages.map(s => s.name),
    };

    if (pipeline.env && Object.keys(pipeline.env).length > 0) {
      ciConfig['variables'] = pipeline.env;
    }

    if (pipeline.services && pipeline.services.length > 0) {
      ciConfig['services'] = this.buildServices(pipeline.services);
      if (pipeline.env) {
        const vars = ciConfig['variables'] as Record<string, string>;
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

    return yaml.stringify(ciConfig, { indent: 2, lineWidth: 120 });
  }

  private getNodeVersion(pipeline: CiPipeline): string {
    for (const stage of pipeline.stages) {
      if (stage.image) {
        const match = stage.image.match(/node:([\d.]+)/);
        if (match && match[1]) return match[1];
      }
    }
    return '20';
  }

  private buildServices(services: CiService[]): Array<Record<string, string>> {
    return services.map(svc => ({
      name: svc.image,
      alias: svc.alias ?? svc.name,
    }));
  }

  private buildJob(stage: CiStage, _config: ProjectConfig): Record<string, unknown> {
    const job: Record<string, unknown> = {
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

  private buildScript(stage: CiStage): string[] {
    const scripts: string[] = [];

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

  private buildCache(config: ProjectConfig): Record<string, unknown> | null {
    const cachePaths: string[] = ['node_modules/'];

    if (config.packageManager === 'npm') {
      cachePaths.push('.npm/');
    } else if (config.packageManager === 'yarn') {
      cachePaths.push('.yarn/');
    } else if (config.packageManager === 'pnpm') {
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
