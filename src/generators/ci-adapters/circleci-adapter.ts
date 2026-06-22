import path from 'path';
import yaml from 'yaml';
import type { CiAdapter, CiPipeline, CiStage, ProjectConfig } from '../../types.js';

export class CircleCIAdapter implements CiAdapter {
  getFileName(): string {
    return '.circleci/config.yml';
  }

  getFilePath(targetDir: string): string {
    return path.join(targetDir, '.circleci', 'config.yml');
  }

  render(pipeline: CiPipeline, _config: ProjectConfig): string {
    const orbs: Record<string, string> = {};

    const executors: Record<string, unknown> = {
      default: {
        docker: this.buildDockerExecutor(pipeline),
        working_directory: '~/repo',
      },
    };

    const jobs: Record<string, unknown> = {};
    for (const stage of pipeline.stages) {
      jobs[stage.name] = this.buildJob(stage);
    }

    const workflows: Record<string, unknown> = {
      ci: {
        jobs: this.buildWorkflowJobs(pipeline),
      },
    };

    const circleConfig: Record<string, unknown> = {
      version: '2.1',
      executors,
      jobs,
      workflows,
    };

    if (Object.keys(orbs).length > 0) {
      circleConfig['orbs'] = orbs;
    }

    return yaml.stringify(circleConfig, { indent: 2, lineWidth: 120 });
  }

  private buildDockerExecutor(pipeline: CiPipeline): Array<Record<string, unknown>> {
    const docker: Array<Record<string, unknown>> = [
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
        const service: Record<string, unknown> = {
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

  private getNodeVersion(pipeline: CiPipeline): string {
    for (const stage of pipeline.stages) {
      if (stage.image) {
        const match = stage.image.match(/node:([\d.]+)/);
        if (match && match[1]) return match[1];
      }
    }
    return '20';
  }

  private buildJob(stage: CiStage): Record<string, unknown> {
    const steps: Array<Record<string, unknown> | string> = [];

    steps.push('checkout');

    if (stage.services && stage.services.length > 0) {
      steps.push({
        'setup_remote_docker': {},
      });
    }

    for (const step of stage.steps) {
      if (step.run) {
        const runStep: Record<string, unknown> = {
          run: {
            name: step.name ?? 'Run',
            command: step.run,
          },
        };
        if (step.env && Object.keys(step.env).length > 0) {
          (runStep['run'] as Record<string, unknown>)['environment'] = step.env;
        }
        steps.push(runStep);
      } else if (step.uses) {
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

    const job: Record<string, unknown> = {
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

  private buildWorkflowJobs(pipeline: CiPipeline): Array<string | Record<string, unknown>> {
    const jobs: Array<string | Record<string, unknown>> = [];

    for (const stage of pipeline.stages) {
      const jobObj: Record<string, unknown> = {};

      if (stage.needs && stage.needs.length > 0) {
        jobObj[stage.name] = {
          requires: stage.needs,
        };
        jobs.push(jobObj);
      } else {
        jobs.push(stage.name);
      }
    }

    return jobs;
  }
}
