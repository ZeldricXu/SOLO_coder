import path from 'path';
import yaml from 'yaml';
import type { CiAdapter, CiPipeline, CiStage, CiService, ProjectConfig } from '../../types.js';

export class GitHubActionsAdapter implements CiAdapter {
  getFileName(): string {
    return 'ci.yml';
  }

  getFilePath(targetDir: string): string {
    return path.join(targetDir, '.github', 'workflows', 'ci.yml');
  }

  render(pipeline: CiPipeline, _config: ProjectConfig): string {
    const workflow: Record<string, unknown> = {
      name: pipeline.name,
      on: this.buildTrigger(pipeline),
      jobs: this.buildJobs(pipeline),
    };

    return yaml.stringify(workflow, { indent: 2, lineWidth: 120 });
  }

  private buildTrigger(pipeline: CiPipeline): Record<string, unknown> {
    const trigger: Record<string, unknown> = {};

    if (pipeline.trigger.push) {
      trigger['push'] = { branches: pipeline.trigger.push.branches };
    }
    if (pipeline.trigger.pullRequest) {
      trigger['pull_request'] = { branches: pipeline.trigger.pullRequest.branches };
    }

    return trigger;
  }

  private buildJobs(pipeline: CiPipeline): Record<string, unknown> {
    const jobs: Record<string, unknown> = {};

    for (const stage of pipeline.stages) {
      jobs[stage.name] = this.buildJob(stage);
    }

    return jobs;
  }

  private buildJob(stage: CiStage): Record<string, unknown> {
    const job: Record<string, unknown> = {
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

  private buildSteps(stage: CiStage): Array<Record<string, unknown>> {
    const steps: Array<Record<string, unknown>> = [];

    for (const step of stage.steps) {
      const stepObj: Record<string, unknown> = {};

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

  private buildServices(services: CiService[]): Record<string, unknown> {
    const result: Record<string, unknown> = {};

    for (const svc of services) {
      const serviceName = svc.alias ?? svc.name;
      const service: Record<string, unknown> = {
        image: svc.image,
      };

      if (svc.env && Object.keys(svc.env).length > 0) {
        service['env'] = svc.env;
      }

      if (svc.ports && svc.ports.length > 0) {
        service['ports'] = svc.ports;
      }

      if (svc.healthCheck) {
        const opts: string[] = [];
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
