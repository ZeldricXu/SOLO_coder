import { generateId, logger, ContextLogger, RequestContext, ValidationError } from '../../common';

export interface PromptVersion {
  id: string;
  promptId: string;
  version: number;
  content: string;
  variables: string[];
  description?: string;
  created_at: string;
  created_by?: string;
  isActive: boolean;
}

export interface ABExperimentConfig {
  id: string;
  name: string;
  description?: string;
  promptVersions: string[];
  trafficAllocation: Record<string, number>;
  status: 'draft' | 'running' | 'paused' | 'completed';
  start_time?: string;
  end_time?: string;
  created_at: string;
  metrics: string[];
}

export interface ExperimentResult {
  experimentId: string;
  promptVersionId: string;
  metricName: string;
  value: number;
  sampleSize: number;
  confidenceInterval?: [number, number];
  statisticalSignificance?: boolean;
}

export interface ExperimentEvaluation {
  experimentId: string;
  startTime: string;
  endTime: string;
  results: ExperimentResult[];
  winnerVersionId?: string;
  confidence: number;
}

export interface Prompt {
  id: string;
  name: string;
  description?: string;
  tags: string[];
  created_at: string;
  updated_at: string;
  currentVersion: number;
}

export interface TrialRecord {
  id: string;
  experimentId: string;
  promptVersionId: string;
  input: string;
  output: string;
  metrics: Record<string, number>;
  created_at: string;
}

export class PromptExperimentService {
  private prompts: Map<string, Prompt> = new Map();
  private versions: Map<string, PromptVersion[]> = new Map();
  private experiments: Map<string, ABExperimentConfig> = new Map();
  private trials: Map<string, TrialRecord[]> = new Map();

  async createPrompt(
    ctx: RequestContext,
    name: string,
    content: string,
    variables: string[] = [],
    description?: string,
    tags: string[] = []
  ): Promise<string> {
    const log = new ContextLogger(ctx);
    const promptId = generateId('entity');
    const now = new Date().toISOString();

    const prompt: Prompt = {
      id: promptId,
      name,
      description,
      tags,
      created_at: now,
      updated_at: now,
      currentVersion: 1
    };

    const version: PromptVersion = {
      id: `${promptId}_v1`,
      promptId,
      version: 1,
      content,
      variables,
      description,
      created_at: now,
      created_by: ctx.userId,
      isActive: true
    };

    this.prompts.set(promptId, prompt);
    this.versions.set(promptId, [version]);

    log.info('Prompt created', { promptId, name, version: 1 });
    return promptId;
  }

  async createVersion(
    ctx: RequestContext,
    promptId: string,
    content: string,
    variables: string[] = [],
    description?: string
  ): Promise<string> {
    const log = new ContextLogger(ctx);
    const prompt = this.prompts.get(promptId);

    if (!prompt) {
      throw new ValidationError(`Prompt not found: ${promptId}`);
    }

    const currentVersions = this.versions.get(promptId) || [];
    const newVersionNumber = prompt.currentVersion + 1;
    const now = new Date().toISOString();

    const version: PromptVersion = {
      id: `${promptId}_v${newVersionNumber}`,
      promptId,
      version: newVersionNumber,
      content,
      variables,
      description,
      created_at: now,
      created_by: ctx.userId,
      isActive: true
    };

    currentVersions.forEach(v => v.isActive = false);
    currentVersions.push(version);
    this.versions.set(promptId, currentVersions);

    prompt.currentVersion = newVersionNumber;
    prompt.updated_at = now;
    this.prompts.set(promptId, prompt);

    log.info('New prompt version created', { promptId, version: newVersionNumber });
    return version.id;
  }

  async getPrompt(promptId: string): Promise<Prompt | null> {
    return this.prompts.get(promptId) || null;
  }

  async getVersion(promptId: string, versionNumber?: number): Promise<PromptVersion | null> {
    const versions = this.versions.get(promptId);
    if (!versions || versions.length === 0) {
      return null;
    }

    if (versionNumber !== undefined) {
      return versions.find(v => v.version === versionNumber) || null;
    }

    return versions.find(v => v.isActive) || versions[versions.length - 1];
  }

  async listVersions(promptId: string): Promise<PromptVersion[]> {
    return this.versions.get(promptId) || [];
  }

  async listPrompts(tags?: string[]): Promise<Prompt[]> {
    const allPrompts = Array.from(this.prompts.values());
    if (!tags || tags.length === 0) {
      return allPrompts;
    }
    return allPrompts.filter(p => tags.some(tag => p.tags.includes(tag)));
  }

  async createExperiment(
    ctx: RequestContext,
    config: Omit<ABExperimentConfig, 'id' | 'created_at' | 'status'>
  ): Promise<string> {
    const log = new ContextLogger(ctx);
    const experimentId = generateId('experiment');

    const totalAllocation = Object.values(config.trafficAllocation).reduce((sum, val) => sum + val, 0);
    if (Math.abs(totalAllocation - 100) > 0.01) {
      throw new ValidationError('Traffic allocation must sum to 100%');
    }

    for (const versionId of config.promptVersions) {
      const [promptId] = versionId.split('_v');
      const versions = this.versions.get(promptId);
      if (!versions || !versions.find(v => v.id === versionId)) {
        throw new ValidationError(`Prompt version not found: ${versionId}`);
      }
    }

    const experiment: ABExperimentConfig = {
      ...config,
      id: experimentId,
      status: 'draft',
      created_at: new Date().toISOString()
    };

    this.experiments.set(experimentId, experiment);
    this.trials.set(experimentId, []);

    log.info('Experiment created', { experimentId, name: config.name });
    return experimentId;
  }

  async startExperiment(ctx: RequestContext, experimentId: string): Promise<void> {
    const log = new ContextLogger(ctx);
    const experiment = this.experiments.get(experimentId);

    if (!experiment) {
      throw new ValidationError(`Experiment not found: ${experimentId}`);
    }

    if (experiment.status !== 'draft') {
      throw new ValidationError(`Cannot start experiment with status: ${experiment.status}`);
    }

    experiment.status = 'running';
    experiment.start_time = new Date().toISOString();
    this.experiments.set(experimentId, experiment);

    log.info('Experiment started', { experimentId });
  }

  async pauseExperiment(ctx: RequestContext, experimentId: string): Promise<void> {
    const log = new ContextLogger(ctx);
    const experiment = this.experiments.get(experimentId);

    if (!experiment) {
      throw new ValidationError(`Experiment not found: ${experimentId}`);
    }

    if (experiment.status !== 'running') {
      throw new ValidationError(`Cannot pause experiment with status: ${experiment.status}`);
    }

    experiment.status = 'paused';
    this.experiments.set(experimentId, experiment);

    log.info('Experiment paused', { experimentId });
  }

  async completeExperiment(ctx: RequestContext, experimentId: string): Promise<void> {
    const log = new ContextLogger(ctx);
    const experiment = this.experiments.get(experimentId);

    if (!experiment) {
      throw new ValidationError(`Experiment not found: ${experimentId}`);
    }

    experiment.status = 'completed';
    experiment.end_time = new Date().toISOString();
    this.experiments.set(experimentId, experiment);

    log.info('Experiment completed', { experimentId });
  }

  async getExperiment(experimentId: string): Promise<ABExperimentConfig | null> {
    return this.experiments.get(experimentId) || null;
  }

  async listExperiments(status?: ABExperimentConfig['status']): Promise<ABExperimentConfig[]> {
    const experiments = Array.from(this.experiments.values());
    if (status) {
      return experiments.filter(e => e.status === status);
    }
    return experiments;
  }

  async selectVersionForExperiment(experimentId: string, userId?: string): Promise<string | null> {
    const experiment = this.experiments.get(experimentId);
    if (!experiment || experiment.status !== 'running') {
      return null;
    }

    const randomValue = userId
      ? this.hashToPercent(userId + experimentId)
      : Math.random() * 100;

    let cumulative = 0;
    for (const [versionId, allocation] of Object.entries(experiment.trafficAllocation)) {
      cumulative += allocation;
      if (randomValue < cumulative) {
        return versionId;
      }
    }

    return experiment.promptVersions[0] || null;
  }

  private hashToPercent(input: string): number {
    let hash = 0;
    for (let i = 0; i < input.length; i++) {
      const char = input.charCodeAt(i);
      hash = ((hash << 5) - hash) + char;
      hash = hash & hash;
    }
    return Math.abs(hash % 100);
  }

  async recordTrial(
    ctx: RequestContext,
    experimentId: string,
    promptVersionId: string,
    input: string,
    output: string,
    metrics: Record<string, number>
  ): Promise<string> {
    const experiment = this.experiments.get(experimentId);
    if (!experiment) {
      throw new ValidationError(`Experiment not found: ${experimentId}`);
    }

    const trialId = generateId('entity');
    const trial: TrialRecord = {
      id: trialId,
      experimentId,
      promptVersionId,
      input,
      output,
      metrics,
      created_at: new Date().toISOString()
    };

    const trials = this.trials.get(experimentId) || [];
    trials.push(trial);
    this.trials.set(experimentId, trials);

    return trialId;
  }

  async evaluateExperiment(experimentId: string): Promise<ExperimentEvaluation> {
    const experiment = this.experiments.get(experimentId);
    if (!experiment) {
      throw new ValidationError(`Experiment not found: ${experimentId}`);
    }

    const trials = this.trials.get(experimentId) || [];
    const results: ExperimentResult[] = [];

    const versionMetrics: Record<string, Record<string, number[]>> = {};
    for (const versionId of experiment.promptVersions) {
      versionMetrics[versionId] = {};
      for (const metric of experiment.metrics) {
        versionMetrics[versionId][metric] = [];
      }
    }

    for (const trial of trials) {
      for (const [metric, value] of Object.entries(trial.metrics)) {
        if (versionMetrics[trial.promptVersionId]?.[metric]) {
          versionMetrics[trial.promptVersionId][metric].push(value);
        }
      }
    }

    for (const versionId of experiment.promptVersions) {
      for (const metric of experiment.metrics) {
        const values = versionMetrics[versionId][metric] || [];
        if (values.length > 0) {
          const avg = values.reduce((sum, v) => sum + v, 0) / values.length;
          results.push({
            experimentId,
            promptVersionId: versionId,
            metricName: metric,
            value: avg,
            sampleSize: values.length
          });
        }
      }
    }

    const primaryMetric = experiment.metrics[0];
    let winnerVersionId: string | undefined;
    let maxValue = -Infinity;

    for (const result of results.filter(r => r.metricName === primaryMetric)) {
      if (result.value > maxValue) {
        maxValue = result.value;
        winnerVersionId = result.promptVersionId;
      }
    }

    return {
      experimentId,
      startTime: experiment.start_time || experiment.created_at,
      endTime: new Date().toISOString(),
      results,
      winnerVersionId,
      confidence: trials.length > 10 ? 0.95 : 0.5
    };
  }

  async getTrials(experimentId: string, limit: number = 100, offset: number = 0): Promise<TrialRecord[]> {
    const trials = this.trials.get(experimentId) || [];
    return trials.slice(offset, offset + limit);
  }

  async compareVersions(
    promptId: string,
    versionNumbers: number[],
    metrics: Record<string, (input: string, output: string) => number>,
    testInputs: string[]
  ): Promise<Record<string, Record<string, number>>> {
    const versions = await Promise.all(
      versionNumbers.map(v => this.getVersion(promptId, v))
    );

    const validVersions = versions.filter((v): v is PromptVersion => v !== null);
    const results: Record<string, Record<string, number>> = {};

    for (const version of validVersions) {
      results[version.id] = {};
      const outputs = testInputs.map(input => this.renderPrompt(version.content, input));
      for (const [metricName, metricFn] of Object.entries(metrics)) {
        const values = outputs.map(output => metricFn(version.content, output));
        results[version.id][metricName] = values.reduce((sum, v) => sum + v, 0) / values.length;
      }
    }

    return results;
  }

  private renderPrompt(template: string, input: string): string {
    return template.replace(/\$\{input\}/g, input);
  }
}
