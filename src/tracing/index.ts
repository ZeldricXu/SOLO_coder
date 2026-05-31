import { v4 as uuidv4 } from 'uuid';
import { TraceSpan, SamplingStrategy, SamplingRule } from '../types';
import { ProcessingPipeline, DataTransformer } from '../core';

export type ScenarioType = 'production' | 'staging' | 'development' | 'custom';

export interface TracingConfig {
  bufferTimeout: number;
  maxBufferSize: number;
  defaultSampleRate: number;
  scenarios: Record<ScenarioType, ScenarioConfig>;
  currentScenario: ScenarioType;
}

export interface ScenarioConfig {
  name: string;
  bufferTimeout: number;
  maxBufferSize: number;
  sampleRate: number;
  enableTailSampling: boolean;
  strategies: string[];
}

export type ConfigChangeListener = (config: TracingConfig, version: number) => void;

export interface TraceBuffer {
  traceId: string;
  spans: TraceSpan[];
  createdAt: number;
  lastUpdated: number;
}

export interface TailSamplingDecision {
  shouldSample: boolean;
  reason: string;
  matchedStrategy?: string;
}

export interface TracingModule {
  collector: DynamicSpanCollector;
  strategyManager: DynamicSamplingStrategyManager;
  headSampler: HeadSampler;
  tailSampler: TailSampler;
  pipeline: TracePipeline;
  configManager: TracingConfigManager;
}

const DEFAULT_CONFIG: TracingConfig = {
  bufferTimeout: 30000,
  maxBufferSize: 1000,
  defaultSampleRate: 1.0,
  currentScenario: 'production',
  scenarios: {
    production: {
      name: 'Production',
      bufferTimeout: 30000,
      maxBufferSize: 1000,
      sampleRate: 0.1,
      enableTailSampling: true,
      strategies: [],
    },
    staging: {
      name: 'Staging',
      bufferTimeout: 60000,
      maxBufferSize: 2000,
      sampleRate: 0.5,
      enableTailSampling: true,
      strategies: [],
    },
    development: {
      name: 'Development',
      bufferTimeout: 120000,
      maxBufferSize: 5000,
      sampleRate: 1.0,
      enableTailSampling: false,
      strategies: [],
    },
    custom: {
      name: 'Custom',
      bufferTimeout: 30000,
      maxBufferSize: 1000,
      sampleRate: 1.0,
      enableTailSampling: true,
      strategies: [],
    },
  },
};

export class TracingConfigManager {
  private config: TracingConfig;
  private listeners: Set<ConfigChangeListener> = new Set();
  private version: number = 1;

  constructor(initialConfig?: Partial<TracingConfig>) {
    this.config = { ...DEFAULT_CONFIG, ...initialConfig };
  }

  getConfig(): TracingConfig {
    return { ...this.config };
  }

  getVersion(): number {
    return this.version;
  }

  updateConfig(updates: Partial<TracingConfig>): void {
    this.config = { ...this.config, ...updates };
    this.version++;
    this.notifyListeners();
  }

  updateScenario(scenarioType: ScenarioType, updates: Partial<ScenarioConfig>): void {
    const scenario = this.config.scenarios[scenarioType];
    if (!scenario) {
      throw new Error(`Unknown scenario: ${scenarioType}`);
    }
    this.config.scenarios[scenarioType] = { ...scenario, ...updates };
    this.version++;
    this.notifyListeners();
  }

  setCurrentScenario(scenarioType: ScenarioType): void {
    if (!this.config.scenarios[scenarioType]) {
      throw new Error(`Unknown scenario: ${scenarioType}`);
    }
    this.config.currentScenario = scenarioType;
    this.version++;
    this.notifyListeners();
  }

  getCurrentScenarioConfig(): ScenarioConfig {
    return { ...this.config.scenarios[this.config.currentScenario] };
  }

  addScenario(name: string, config: Omit<ScenarioConfig, 'name'>): ScenarioType {
    const key = `custom_${Date.now()}` as ScenarioType;
    this.config.scenarios[key] = { ...config, name };
    this.version++;
    this.notifyListeners();
    return key;
  }

  removeScenario(scenarioType: ScenarioType): boolean {
    if (scenarioType === 'production' || scenarioType === 'staging' || scenarioType === 'development') {
      return false;
    }
    if (this.config.currentScenario === scenarioType) {
      return false;
    }
    const deleted = delete this.config.scenarios[scenarioType];
    if (deleted) {
      this.version++;
      this.notifyListeners();
    }
    return deleted;
  }

  subscribe(listener: ConfigChangeListener): () => void {
    this.listeners.add(listener);
    listener(this.config, this.version);
    return () => this.listeners.delete(listener);
  }

  private notifyListeners(): void {
    const config = this.getConfig();
    for (const listener of this.listeners) {
      try {
        listener(config, this.version);
      } catch (error) {
        console.error('[TracingConfig] Error in listener:', error);
      }
    }
  }

  getEffectiveBufferTimeout(): number {
    const scenario = this.getCurrentScenarioConfig();
    return scenario.bufferTimeout || this.config.bufferTimeout;
  }

  getEffectiveMaxBufferSize(): number {
    const scenario = this.getCurrentScenarioConfig();
    return scenario.maxBufferSize || this.config.maxBufferSize;
  }

  getEffectiveSampleRate(): number {
    const scenario = this.getCurrentScenarioConfig();
    return scenario.sampleRate ?? this.config.defaultSampleRate;
  }

  isTailSamplingEnabled(): boolean {
    const scenario = this.getCurrentScenarioConfig();
    return scenario.enableTailSampling ?? true;
  }

  getCurrentScenario(): ScenarioType {
    return this.config.currentScenario;
  }
}

export class DynamicSpanCollector {
  private spans: Map<string, TraceSpan[]> = new Map();
  private configManager: TracingConfigManager;
  private unsubscribe: () => void;

  constructor(configManager: TracingConfigManager) {
    this.configManager = configManager;
    this.unsubscribe = configManager.subscribe((config, version) => {
      this.cleanupOldBuffers();
    });
  }

  receiveSpan(span: TraceSpan): void {
    const normalized = this.normalizeSpan(span);
    if (!this.spans.has(span.traceId)) {
      this.spans.set(span.traceId, []);
    }
    const traceSpans = this.spans.get(span.traceId)!;
    traceSpans.push(normalized);
    this.cleanupOldBuffers();
  }

  private normalizeSpan(span: TraceSpan): TraceSpan {
    const schema: Record<string, string> = {
      traceId: 'string',
      spanId: 'string',
      name: 'string',
      serviceName: 'string',
      startTime: 'timestamp',
      status: 'string',
    };
    const normalized = DataTransformer.normalize(span as unknown as Record<string, unknown>, schema);
    return normalized as unknown as TraceSpan;
  }

  getTrace(traceId: string): TraceSpan[] {
    return this.spans.get(traceId) || [];
  }

  removeTrace(traceId: string): void {
    this.spans.delete(traceId);
  }

  listTraces(): TraceBuffer[] {
    const buffers: TraceBuffer[] = [];
    for (const [traceId, spans] of this.spans.entries()) {
      const timestamps = spans.map(s => new Date(s.startTime).getTime());
      buffers.push({
        traceId,
        spans,
        createdAt: Math.min(...timestamps),
        lastUpdated: Math.max(...timestamps),
      });
    }
    return buffers;
  }

  private cleanupOldBuffers(): void {
    const now = Date.now();
    const bufferTimeout = this.configManager.getEffectiveBufferTimeout();
    const maxBufferSize = this.configManager.getEffectiveMaxBufferSize();

    for (const [traceId, spans] of this.spans.entries()) {
      const lastUpdated = Math.max(...spans.map(s => new Date(s.startTime).getTime()));
      if (now - lastUpdated > bufferTimeout || spans.length > maxBufferSize) {
        this.spans.delete(traceId);
      }
    }
  }

  getStats(): { totalTraces: number; totalSpans: number; configVersion: number } {
    let totalSpans = 0;
    for (const spans of this.spans.values()) {
      totalSpans += spans.length;
    }
    return {
      totalTraces: this.spans.size,
      totalSpans,
      configVersion: this.configManager.getVersion(),
    };
  }

  destroy(): void {
    this.unsubscribe();
    this.spans.clear();
  }
}

export class DynamicSamplingStrategyManager {
  private strategies: Map<string, SamplingStrategy> = new Map();
  private configManager: TracingConfigManager;
  private unsubscribe: () => void;

  constructor(configManager: TracingConfigManager) {
    this.configManager = configManager;
    this.unsubscribe = configManager.subscribe((config, version) => {});
  }

  addStrategy(strategy: SamplingStrategy): void {
    this.strategies.set(strategy.id, strategy);
  }

  removeStrategy(id: string): boolean {
    return this.strategies.delete(id);
  }

  getStrategies(): SamplingStrategy[] {
    const scenario = this.configManager.getCurrentScenarioConfig();
    const scenarioStrategyIds = scenario.strategies || [];

    const allStrategies = Array.from(this.strategies.values()).sort((a, b) => b.priority - a.priority);

    if (scenarioStrategyIds.length === 0) {
      return allStrategies;
    }

    return [
      ...allStrategies.filter(s => scenarioStrategyIds.includes(s.id)),
      ...allStrategies.filter(s => !scenarioStrategyIds.includes(s.id)),
    ];
  }

  getHeadSamplingStrategies(): SamplingStrategy[] {
    return this.getStrategies().filter(s => s.type === 'head' && s.enabled);
  }

  getTailSamplingStrategies(): SamplingStrategy[] {
    if (!this.configManager.isTailSamplingEnabled()) {
      return [];
    }
    return this.getStrategies().filter(s => s.type === 'tail' && s.enabled);
  }

  enableStrategy(id: string): boolean {
    const strategy = this.strategies.get(id);
    if (!strategy) return false;
    strategy.enabled = true;
    return true;
  }

  disableStrategy(id: string): boolean {
    const strategy = this.strategies.get(id);
    if (!strategy) return false;
    strategy.enabled = false;
    return true;
  }

  destroy(): void {
    this.unsubscribe();
    this.strategies.clear();
  }
}

export class HeadSampler {
  private strategyManager: DynamicSamplingStrategyManager;
  private configManager: TracingConfigManager;

  constructor(strategyManager: DynamicSamplingStrategyManager, configManager: TracingConfigManager) {
    this.strategyManager = strategyManager;
    this.configManager = configManager;
  }

  shouldSample(span: TraceSpan): boolean {
    const strategies = this.strategyManager.getHeadSamplingStrategies();
    for (const strategy of strategies) {
      if (this.matchRule(span, strategy.rule)) {
        return Math.random() < strategy.rule.sampleRate;
      }
    }
    return Math.random() < this.configManager.getEffectiveSampleRate();
  }

  private matchRule(span: TraceSpan, rule: SamplingRule): boolean {
    if (rule.serviceName && span.serviceName !== rule.serviceName) {
      return false;
    }
    if (rule.spanName && span.name !== rule.spanName) {
      return false;
    }
    if (rule.errorOnly && span.status !== 'ERROR') {
      return false;
    }
    if (rule.minDuration && span.duration && span.duration < rule.minDuration) {
      return false;
    }
    if (rule.attributes) {
      for (const [key, value] of Object.entries(rule.attributes)) {
        if (span.attributes[key] !== value) {
          return false;
        }
      }
    }
    return true;
  }
}

export class TailSampler {
  private strategyManager: DynamicSamplingStrategyManager;
  private configManager: TracingConfigManager;

  constructor(strategyManager: DynamicSamplingStrategyManager, configManager: TracingConfigManager) {
    this.strategyManager = strategyManager;
    this.configManager = configManager;
  }

  decide(traceSpans: TraceSpan[]): TailSamplingDecision {
    if (!this.configManager.isTailSamplingEnabled()) {
      return {
        shouldSample: true,
        reason: 'Tail sampling disabled, default to sample',
      };
    }

    const strategies = this.strategyManager.getTailSamplingStrategies();
    const traceStats = this.analyzeTrace(traceSpans);

    for (const strategy of strategies) {
      if (this.matchTraceRule(traceStats, strategy.rule)) {
        const shouldSample = Math.random() < strategy.rule.sampleRate;
        return {
          shouldSample,
          reason: `Matched strategy: ${strategy.name}`,
          matchedStrategy: strategy.id,
        };
      }
    }

    return {
      shouldSample: true,
      reason: 'No matching tail sampling strategy, default to sample',
    };
  }

  private analyzeTrace(spans: TraceSpan[]): {
    hasError: boolean;
    maxDuration: number;
    totalSpans: number;
    services: Set<string>;
  } {
    let hasError = false;
    let maxDuration = 0;
    const services = new Set<string>();

    for (const span of spans) {
      if (span.status === 'ERROR') {
        hasError = true;
      }
      if (span.duration && span.duration > maxDuration) {
        maxDuration = span.duration;
      }
      services.add(span.serviceName);
    }

    return {
      hasError,
      maxDuration,
      totalSpans: spans.length,
      services,
    };
  }

  private matchTraceRule(
    traceStats: ReturnType<TailSampler['analyzeTrace']>,
    rule: SamplingRule
  ): boolean {
    if (rule.errorOnly && !traceStats.hasError) {
      return false;
    }
    if (rule.minDuration && traceStats.maxDuration < rule.minDuration) {
      return false;
    }
    if (rule.serviceName && !traceStats.services.has(rule.serviceName)) {
      return false;
    }
    return true;
  }
}

export class TracePipeline {
  private collector: DynamicSpanCollector;
  private headSampler: HeadSampler;
  private tailSampler: TailSampler;
  private pipeline: ProcessingPipeline<TraceSpan, TraceSpan>;
  private configManager: TracingConfigManager;

  constructor(
    collector: DynamicSpanCollector,
    headSampler: HeadSampler,
    tailSampler: TailSampler,
    configManager: TracingConfigManager
  ) {
    this.collector = collector;
    this.headSampler = headSampler;
    this.tailSampler = tailSampler;
    this.configManager = configManager;
    this.pipeline = this.buildPipeline();
  }

  private buildPipeline(): ProcessingPipeline<TraceSpan, TraceSpan> {
    return new ProcessingPipeline<TraceSpan, TraceSpan>()
      .addStage({
        name: 'validation',
        process: async (span) => this.validateSpan(span),
      })
      .addStage({
        name: 'head_sampling',
        process: async (span) => this.applyHeadSampling(span),
      })
      .addStage({
        name: 'enrichment',
        process: async (span) => this.enrichSpan(span),
      })
      .addStage({
        name: 'buffering',
        process: async (span) => {
          this.collector.receiveSpan(span);
          return span;
        },
      });
  }

  private validateSpan(span: TraceSpan): TraceSpan {
    if (!span.traceId || !span.spanId || !span.name) {
      throw new Error('Invalid span: missing required fields');
    }
    return span;
  }

  private applyHeadSampling(span: TraceSpan): TraceSpan {
    const sampled = this.headSampler.shouldSample(span);
    return { ...span, sampled };
  }

  private enrichSpan(span: TraceSpan): TraceSpan {
    const enriched = { ...span };
    if (!enriched.startTime) {
      enriched.startTime = new Date().toISOString();
    }
    if (enriched.endTime && !enriched.duration) {
      enriched.duration = new Date(enriched.endTime).getTime() - new Date(enriched.startTime).getTime();
    }
    enriched.attributes = {
      ...enriched.attributes,
      '_collected_at': new Date().toISOString(),
      '_scenario': this.configManager.getConfig().currentScenario,
    };
    return enriched;
  }

  async processSpan(span: TraceSpan): Promise<TraceSpan> {
    const result = await this.pipeline.execute(span, span.traceId);
    if (!result.success || !result.data) {
      throw new Error(result.error || 'Failed to process span');
    }
    return result.data;
  }

  async finalizeTrace(traceId: string): Promise<{ sampled: boolean; spans: TraceSpan[] }> {
    const spans = this.collector.getTrace(traceId);
    if (spans.length === 0) {
      return { sampled: false, spans: [] };
    }

    const decision = this.tailSampler.decide(spans);
    const sampledSpans = spans.map(s => ({ ...s, sampled: decision.shouldSample }));

    if (!decision.shouldSample) {
      this.collector.removeTrace(traceId);
    }

    return {
      sampled: decision.shouldSample,
      spans: sampledSpans,
    };
  }

  getConfigManager(): TracingConfigManager {
    return this.configManager;
  }
}

export function createTracingModule(): TracingModule {
  const configManager = new TracingConfigManager();
  const collector = new DynamicSpanCollector(configManager);
  const strategyManager = new DynamicSamplingStrategyManager(configManager);
  const headSampler = new HeadSampler(strategyManager, configManager);
  const tailSampler = new TailSampler(strategyManager, configManager);
  const pipeline = new TracePipeline(collector, headSampler, tailSampler, configManager);

  return {
    collector,
    strategyManager,
    headSampler,
    tailSampler,
    pipeline,
    configManager,
  };
}

export function createTracingModuleWithConfig(initialConfig?: Partial<TracingConfig>): TracingModule {
  const configManager = new TracingConfigManager(initialConfig);
  const collector = new DynamicSpanCollector(configManager);
  const strategyManager = new DynamicSamplingStrategyManager(configManager);
  const headSampler = new HeadSampler(strategyManager, configManager);
  const tailSampler = new TailSampler(strategyManager, configManager);
  const pipeline = new TracePipeline(collector, headSampler, tailSampler, configManager);

  return {
    collector,
    strategyManager,
    headSampler,
    tailSampler,
    pipeline,
    configManager,
  };
}
