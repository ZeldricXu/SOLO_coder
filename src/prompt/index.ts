import { EventEmitter } from 'events';
import { generateId, nowISO, calculatePercentiles } from '../shared/utils';
import { logger } from '../logging';
import { monitoring } from '../monitoring';

interface PromptVersion {
  version_id: string;
  prompt_id: string;
  version: number;
  content: string;
  variables: string[];
  metadata: Record<string, unknown>;
  created_at: string;
  created_by: string;
  description?: string;
}

interface Prompt {
  prompt_id: string;
  name: string;
  description?: string;
  latest_version: number;
  versions: string[];
  created_at: string;
  updated_at: string;
  created_by: string;
  tags: string[];
  is_active: boolean;
}

interface ABExperiment {
  experiment_id: string;
  name: string;
  description?: string;
  prompt_id: string;
  variants: ABVariant[];
  status: 'draft' | 'running' | 'paused' | 'completed';
  traffic_percentage: number;
  started_at?: string;
  ended_at?: string;
  created_at: string;
  created_by: string;
  winner?: string;
}

interface ABVariant {
  variant_id: string;
  name: string;
  prompt_version_id: string;
  weight: number;
  is_control: boolean;
}

interface TrialResult {
  trial_id: string;
  experiment_id: string;
  variant_id: string;
  input: Record<string, unknown>;
  output: string;
  metrics: Record<string, number>;
  latency_ms: number;
  timestamp: string;
  success: boolean;
  error?: string;
}

interface ExperimentStats {
  total_trials: number;
  success_rate: number;
  avg_latency_ms: number;
  variants: Record<string, {
    trials: number;
    success_rate: number;
    avg_latency_ms: number;
    metrics: Record<string, number>;
  }>;
}

class PromptExperimentService extends EventEmitter {
  private prompts: Map<string, Prompt> = new Map();
  private promptVersions: Map<string, PromptVersion> = new Map();
  private experiments: Map<string, ABExperiment> = new Map();
  private trialResults: Map<string, TrialResult[]> = new Map();
  private maxTrialsPerExperiment = 10000;
  private maxVersionsPerPrompt = 50;

  createPrompt(
    name: string,
    content: string,
    variables: string[],
    createdBy: string,
    description?: string,
    tags: string[] = [],
    metadata?: Record<string, unknown>
  ): { prompt: Prompt; version: PromptVersion } {
    const promptId = generateId('pr');
    const versionId = generateId('pv');
    const now = nowISO();

    const version: PromptVersion = {
      version_id: versionId,
      prompt_id: promptId,
      version: 1,
      content,
      variables,
      metadata: metadata || {},
      created_at: now,
      created_by: createdBy,
      description,
    };

    const prompt: Prompt = {
      prompt_id: promptId,
      name,
      description,
      latest_version: 1,
      versions: [versionId],
      created_at: now,
      updated_at: now,
      created_by: createdBy,
      tags,
      is_active: true,
    };

    this.prompts.set(promptId, prompt);
    this.promptVersions.set(versionId, version);

    logger.info('Prompt created', { prompt_id: promptId, name, version: 1 }, createdBy);
    this.emit('prompt.created', prompt, version);

    return { prompt, version };
  }

  createVersion(
    promptId: string,
    content: string,
    variables: string[],
    createdBy: string,
    description?: string,
    metadata?: Record<string, unknown>
  ): PromptVersion | null {
    const prompt = this.prompts.get(promptId);
    if (!prompt) {
      logger.warn('Prompt not found for version creation', { prompt_id: promptId }, createdBy);
      return null;
    }

    const newVersion = prompt.latest_version + 1;
    const versionId = generateId('pv');
    const now = nowISO();

    const version: PromptVersion = {
      version_id: versionId,
      prompt_id: promptId,
      version: newVersion,
      content,
      variables,
      metadata: metadata || {},
      created_at: now,
      created_by: createdBy,
      description,
    };

    this.promptVersions.set(versionId, version);
    prompt.versions.push(versionId);
    prompt.latest_version = newVersion;
    prompt.updated_at = now;

    if (prompt.versions.length > this.maxVersionsPerPrompt) {
      const removed = prompt.versions.shift();
      if (removed) {
        this.promptVersions.delete(removed);
      }
    }

    logger.info('Prompt version created', { prompt_id: promptId, version: newVersion }, createdBy);
    this.emit('prompt.version_created', prompt, version);

    return version;
  }

  getPrompt(promptId: string): Prompt | null {
    return this.prompts.get(promptId) || null;
  }

  getPromptVersion(versionId: string): PromptVersion | null {
    return this.promptVersions.get(versionId) || null;
  }

  getPromptVersions(promptId: string): PromptVersion[] {
    const prompt = this.prompts.get(promptId);
    if (!prompt) return [];
    return prompt.versions
      .map((vid) => this.promptVersions.get(vid))
      .filter((v): v is PromptVersion => v !== undefined)
      .sort((a, b) => b.version - a.version);
  }

  renderPrompt(versionId: string, variables: Record<string, string>): string | null {
    const version = this.promptVersions.get(versionId);
    if (!version) return null;

    let rendered = version.content;
    for (const [key, value] of Object.entries(variables)) {
      const regex = new RegExp(`\\{\\{\\s*${key}\\s*\\}\\}`, 'g');
      rendered = rendered.replace(regex, value);
    }
    return rendered;
  }

  createExperiment(
    name: string,
    promptId: string,
    variants: Array<{ name: string; prompt_version_id: string; weight: number; is_control?: boolean }>,
    createdBy: string,
    description?: string,
    trafficPercentage: number = 50
  ): ABExperiment | null {
    const prompt = this.prompts.get(promptId);
    if (!prompt) {
      logger.warn('Prompt not found for experiment', { prompt_id: promptId }, createdBy);
      return null;
    }

    const experimentId = generateId('exp');
    const now = nowISO();

    const experimentVariants: ABVariant[] = variants.map((v, idx) => ({
      variant_id: generateId('var'),
      name: v.name,
      prompt_version_id: v.prompt_version_id,
      weight: v.weight,
      is_control: v.is_control || (idx === 0),
    }));

    const totalWeight = experimentVariants.reduce((sum, v) => sum + v.weight, 0);
    if (totalWeight !== 100) {
      logger.warn('Variant weights do not sum to 100', { experiment_id: experimentId, total_weight: totalWeight }, createdBy);
    }

    const experiment: ABExperiment = {
      experiment_id: experimentId,
      name,
      description,
      prompt_id: promptId,
      variants: experimentVariants,
      status: 'draft',
      traffic_percentage: trafficPercentage,
      created_at: now,
      created_by: createdBy,
    };

    this.experiments.set(experimentId, experiment);
    this.trialResults.set(experimentId, []);

    logger.info('Experiment created', { experiment_id: experimentId, name, prompt_id: promptId }, createdBy);
    this.emit('experiment.created', experiment);

    return experiment;
  }

  startExperiment(experimentId: string): boolean {
    const experiment = this.experiments.get(experimentId);
    if (!experiment || experiment.status !== 'draft') {
      return false;
    }
    experiment.status = 'running';
    experiment.started_at = nowISO();
    logger.info('Experiment started', { experiment_id: experimentId, name: experiment.name });
    this.emit('experiment.started', experiment);
    return true;
  }

  pauseExperiment(experimentId: string): boolean {
    const experiment = this.experiments.get(experimentId);
    if (!experiment || experiment.status !== 'running') {
      return false;
    }
    experiment.status = 'paused';
    logger.info('Experiment paused', { experiment_id: experimentId, name: experiment.name });
    this.emit('experiment.paused', experiment);
    return true;
  }

  resumeExperiment(experimentId: string): boolean {
    const experiment = this.experiments.get(experimentId);
    if (!experiment || experiment.status !== 'paused') {
      return false;
    }
    experiment.status = 'running';
    logger.info('Experiment resumed', { experiment_id: experimentId, name: experiment.name });
    this.emit('experiment.resumed', experiment);
    return true;
  }

  endExperiment(experimentId: string, winner?: string): boolean {
    const experiment = this.experiments.get(experimentId);
    if (!experiment || (experiment.status !== 'running' && experiment.status !== 'paused')) {
      return false;
    }
    experiment.status = 'completed';
    experiment.ended_at = nowISO();
    experiment.winner = winner;
    logger.info('Experiment ended', { experiment_id: experimentId, name: experiment.name, winner });
    this.emit('experiment.ended', experiment);
    return true;
  }

  selectVariant(experimentId: string): ABVariant | null {
    const experiment = this.experiments.get(experimentId);
    if (!experiment || experiment.status !== 'running') {
      return null;
    }

    if (Math.random() * 100 > experiment.traffic_percentage) {
      return null;
    }

    const random = Math.random() * 100;
    let cumulative = 0;
    for (const variant of experiment.variants) {
      cumulative += variant.weight;
      if (random <= cumulative) {
        return variant;
      }
    }
    return experiment.variants[experiment.variants.length - 1];
  }

  recordTrial(
    experimentId: string,
    variantId: string,
    input: Record<string, unknown>,
    output: string,
    metrics: Record<string, number>,
    latencyMs: number,
    success: boolean = true,
    error?: string
  ): TrialResult | null {
    const experiment = this.experiments.get(experimentId);
    if (!experiment) return null;

    const trial: TrialResult = {
      trial_id: generateId('trl'),
      experiment_id: experimentId,
      variant_id: variantId,
      input,
      output,
      metrics,
      latency_ms: latencyMs,
      timestamp: nowISO(),
      success,
      error,
    };

    const trials = this.trialResults.get(experimentId);
    if (trials) {
      trials.push(trial);
      if (trials.length > this.maxTrialsPerExperiment) {
        trials.shift();
      }
    }

    monitoring.incrementCounter('experiment_trials', 1, { experiment_id: experimentId, variant_id: variantId });
    monitoring.recordLatency('trial_latency', latencyMs, { experiment_id: experimentId, variant_id: variantId });

    this.emit('trial.recorded', trial);
    return trial;
  }

  getExperimentStats(experimentId: string): ExperimentStats | null {
    const experiment = this.experiments.get(experimentId);
    const trials = this.trialResults.get(experimentId);
    if (!experiment || !trials) return null;

    const successfulTrials = trials.filter((t) => t.success);
    const latencies = trials.map((t) => t.latency_ms);
    const percentiles = calculatePercentiles(latencies, [50, 95, 99]);

    const variantStats: ExperimentStats['variants'] = {};
    for (const variant of experiment.variants) {
      const variantTrials = trials.filter((t) => t.variant_id === variant.variant_id);
      const variantSuccessful = variantTrials.filter((t) => t.success);
      const variantLatencies = variantTrials.map((t) => t.latency_ms);

      const metrics: Record<string, number> = {};
      for (const trial of variantSuccessful) {
        for (const [key, value] of Object.entries(trial.metrics)) {
          metrics[key] = (metrics[key] || 0) + value;
        }
      }
      for (const key of Object.keys(metrics)) {
        metrics[key] = metrics[key] / variantSuccessful.length;
      }

      variantStats[variant.variant_id] = {
        trials: variantTrials.length,
        success_rate: variantTrials.length > 0 ? variantSuccessful.length / variantTrials.length : 0,
        avg_latency_ms: variantLatencies.length > 0 ? variantLatencies.reduce((a, b) => a + b, 0) / variantLatencies.length : 0,
        metrics,
      };
    }

    return {
      total_trials: trials.length,
      success_rate: trials.length > 0 ? successfulTrials.length / trials.length : 0,
      avg_latency_ms: latencies.length > 0 ? latencies.reduce((a, b) => a + b, 0) / latencies.length : 0,
      variants: variantStats,
    };
  }

  listPrompts(includeInactive: boolean = false): Prompt[] {
    return Array.from(this.prompts.values()).filter((p) => includeInactive || p.is_active);
  }

  listExperiments(status?: ABExperiment['status']): ABExperiment[] {
    let exps = Array.from(this.experiments.values());
    if (status) {
      exps = exps.filter((e) => e.status === status);
    }
    return exps.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());
  }

  getExperiment(experimentId: string): ABExperiment | null {
    return this.experiments.get(experimentId) || null;
  }

  getTrials(experimentId: string, variantId?: string, limit?: number): TrialResult[] {
    const trials = this.trialResults.get(experimentId);
    if (!trials) return [];

    let filtered = trials;
    if (variantId) {
      filtered = trials.filter((t) => t.variant_id === variantId);
    }

    const sorted = [...filtered].sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
    return limit ? sorted.slice(0, limit) : sorted;
  }

  compareExperiments(experimentIdA: string, experimentIdB: string): Record<string, unknown> | null {
    const statsA = this.getExperimentStats(experimentIdA);
    const statsB = this.getExperimentStats(experimentIdB);
    const expA = this.getExperiment(experimentIdA);
    const expB = this.getExperiment(experimentIdB);

    if (!statsA || !statsB || !expA || !expB) return null;

    return {
      experiment_a: { id: experimentIdA, name: expA.name, ...statsA },
      experiment_b: { id: experimentIdB, name: expB.name, ...statsB },
      comparison: {
        trials_delta: statsB.total_trials - statsA.total_trials,
        success_rate_delta: statsB.success_rate - statsA.success_rate,
        latency_delta: statsB.avg_latency_ms - statsA.avg_latency_ms,
      },
    };
  }

  archivePrompt(promptId: string): boolean {
    const prompt = this.prompts.get(promptId);
    if (!prompt) return false;
    prompt.is_active = false;
    prompt.updated_at = nowISO();
    logger.info('Prompt archived', { prompt_id: promptId });
    this.emit('prompt.archived', promptId);
    return true;
  }

  unarchivePrompt(promptId: string): boolean {
    const prompt = this.prompts.get(promptId);
    if (!prompt) return false;
    prompt.is_active = true;
    prompt.updated_at = nowISO();
    logger.info('Prompt unarchived', { prompt_id: promptId });
    this.emit('prompt.unarchived', promptId);
    return true;
  }

  searchPrompts(query: string, tags?: string[]): Prompt[] {
    const lowerQuery = query.toLowerCase();
    return this.listPrompts().filter((p) => {
      const matchesQuery = p.name.toLowerCase().includes(lowerQuery) ||
        (p.description?.toLowerCase().includes(lowerQuery));
      const matchesTags = !tags || tags.some((t) => p.tags.includes(t));
      return matchesQuery && matchesTags;
    });
  }
}

export const promptExperimentService = new PromptExperimentService();
export { PromptExperimentService, Prompt, PromptVersion, ABExperiment, ABVariant, TrialResult, ExperimentStats };
