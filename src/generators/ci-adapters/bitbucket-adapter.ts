import path from 'path';
import yaml from 'yaml';
import type { CiAdapter, CiPipeline, CiStage, CiService, ProjectConfig } from '../../types.js';

export class BitbucketPipelinesAdapter implements CiAdapter {
  getFileName(): string {
    return 'bitbucket-pipelines.yml';
  }

  getFilePath(targetDir: string): string {
    return path.join(targetDir, 'bitbucket-pipelines.yml');
  }

  render(pipeline: CiPipeline, _config: ProjectConfig): string {
    const pipelines: Record<string, unknown> = {};

    if (pipeline.trigger.push?.branches) {
      const branches: Record<string, unknown> = {};
      for (const branch of pipeline.trigger.push.branches) {
        branches[branch] = this.buildPipelineSteps(pipeline);
      }
      pipelines['branches'] = branches;
    }

    if (pipeline.trigger.pullRequest?.branches) {
      const prBranches: Record<string, unknown> = {};
      for (const branch of pipeline.trigger.pullRequest.branches) {
        prBranches[branch] = this.buildPipelineSteps(pipeline);
      }
      pipelines['pull-requests'] = prBranches;
    }

    const bitbucketConfig: Record<string, unknown> = {
      image: pipeline.defaultImage ?? `node:${this.getNodeVersion(pipeline)}`,
      pipelines,
    };

    if (pipeline.services && pipeline.services.length > 0) {
      bitbucketConfig['definitions'] = {
        services: this.buildServices(pipeline.services),
      };
    }

    return yaml.stringify(bitbucketConfig, { indent: 2, lineWidth: 120 });
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

  private buildPipelineSteps(pipeline: CiPipeline): Array<Record<string, unknown>> {
    const steps: Array<Record<string, unknown>> = [];

    for (const stage of pipeline.stages) {
      const step = this.buildStep(stage);
      if (step) steps.push(step);
    }

    return steps;
  }

  private buildStep(stage: CiStage): Record<string, unknown> | null {
    const script = this.buildScript(stage);
    if (script.length === 0) return null;

    const step: Record<string, unknown> = {
      step: {
        name: stage.displayName,
        script,
      },
    };

    if (stage.services && stage.services.length > 0) {
      (step['step'] as Record<string, unknown>)['services'] = stage.services.map(s => s.alias ?? s.name);
    }

    if (stage.artifacts?.paths) {
      (step['step'] as Record<string, unknown>)['artifacts'] = stage.artifacts.paths;
    }

    if (stage.env && Object.keys(stage.env).length > 0) {
      (step['step'] as Record<string, unknown>)['variables'] = stage.env;
    }

    if (stage.strategy?.matrix) {
      const parallel: Array<Record<string, unknown>> = [];
      const matrixEntries = Object.entries(stage.strategy.matrix);
      const [matrixKey, matrixValues] = matrixEntries[0] ?? ['', []];

      for (const value of matrixValues) {
        const parallelStep = structuredClone(step);
        const stepInner = parallelStep['step'] as Record<string, unknown>;
        stepInner['name'] = `${stage.displayName} (${value})`;
        if (stepInner['variables']) {
          (stepInner['variables'] as Record<string, string>)[matrixKey.toUpperCase()] = value;
        } else {
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

  private buildScript(stage: CiStage): string[] {
    const scripts: string[] = [];

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

  private buildServices(services: CiService[]): Record<string, unknown> {
    const result: Record<string, unknown> = {};

    for (const svc of services) {
      const serviceName = svc.alias ?? svc.name;
      const service: Record<string, unknown> = {
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
