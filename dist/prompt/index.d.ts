import { EventEmitter } from 'events';
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
declare class PromptExperimentService extends EventEmitter {
    private prompts;
    private promptVersions;
    private experiments;
    private trialResults;
    private maxTrialsPerExperiment;
    private maxVersionsPerPrompt;
    createPrompt(name: string, content: string, variables: string[], createdBy: string, description?: string, tags?: string[], metadata?: Record<string, unknown>): {
        prompt: Prompt;
        version: PromptVersion;
    };
    createVersion(promptId: string, content: string, variables: string[], createdBy: string, description?: string, metadata?: Record<string, unknown>): PromptVersion | null;
    getPrompt(promptId: string): Prompt | null;
    getPromptVersion(versionId: string): PromptVersion | null;
    getPromptVersions(promptId: string): PromptVersion[];
    renderPrompt(versionId: string, variables: Record<string, string>): string | null;
    createExperiment(name: string, promptId: string, variants: Array<{
        name: string;
        prompt_version_id: string;
        weight: number;
        is_control?: boolean;
    }>, createdBy: string, description?: string, trafficPercentage?: number): ABExperiment | null;
    startExperiment(experimentId: string): boolean;
    pauseExperiment(experimentId: string): boolean;
    resumeExperiment(experimentId: string): boolean;
    endExperiment(experimentId: string, winner?: string): boolean;
    selectVariant(experimentId: string): ABVariant | null;
    recordTrial(experimentId: string, variantId: string, input: Record<string, unknown>, output: string, metrics: Record<string, number>, latencyMs: number, success?: boolean, error?: string): TrialResult | null;
    getExperimentStats(experimentId: string): ExperimentStats | null;
    listPrompts(includeInactive?: boolean): Prompt[];
    listExperiments(status?: ABExperiment['status']): ABExperiment[];
    getExperiment(experimentId: string): ABExperiment | null;
    getTrials(experimentId: string, variantId?: string, limit?: number): TrialResult[];
    compareExperiments(experimentIdA: string, experimentIdB: string): Record<string, unknown> | null;
    archivePrompt(promptId: string): boolean;
    unarchivePrompt(promptId: string): boolean;
    searchPrompts(query: string, tags?: string[]): Prompt[];
}
export declare const promptExperimentService: PromptExperimentService;
export { PromptExperimentService, Prompt, PromptVersion, ABExperiment, ABVariant, TrialResult, ExperimentStats };
//# sourceMappingURL=index.d.ts.map