import { EventEmitter, generateId, parseDuration } from '../utils';
import logger from '../utils/logger';
import { TraceSpan, SamplingConfig } from '../types';
import cacheManager from '../data-access';
import { InMemoryCache } from '../data-access';
import metricsService from '../metrics';

interface TracingEvents {
  'span.received': TraceSpan;
  'span.sampled': { span: TraceSpan; sampling_rate: number; decision: boolean };
  'trace.completed': { trace_id: string; span_count: number; duration_ms: number };
  'sampling.config_updated': SamplingConfig;
}

export class TraceSampler {
  private config: SamplingConfig;

  constructor(config: Partial<SamplingConfig> = {}) {
    this.config = {
      default_sampling_rate: config.default_sampling_rate ?? 1.0,
      rules: config.rules ?? [],
      tail_sampling_enabled: config.tail_sampling_enabled ?? false,
      tail_sampling_wait_time: config.tail_sampling_wait_time ?? '1m',
    };
  }

  updateConfig(config: Partial<SamplingConfig>): void {
    this.config = {
      ...this.config,
      ...config,
    };
    logger.info('Updated sampling configuration');
  }

  getConfig(): SamplingConfig {
    return { ...this.config };
  }

  shouldSample(span: TraceSpan): boolean {
    for (const rule of this.config.rules) {
      if (rule.service_name && span.service_name !== rule.service_name) continue;
      if (rule.operation_name && span.name !== rule.operation_name) continue;
      if (rule.min_duration_ms && span.duration_ms < rule.min_duration_ms) continue;
      if (rule.error_only && span.status !== 'error') continue;

      return Math.random() < rule.sampling_rate;
    }

    return Math.random() < this.config.default_sampling_rate;
  }

  getSamplingRateForSpan(span: TraceSpan): number {
    for (const rule of this.config.rules) {
      if (rule.service_name && span.service_name !== rule.service_name) continue;
      if (rule.operation_name && span.name !== rule.operation_name) continue;
      if (rule.min_duration_ms && span.duration_ms < rule.min_duration_ms) continue;
      if (rule.error_only && span.status !== 'error') continue;

      return rule.sampling_rate;
    }

    return this.config.default_sampling_rate;
  }
}

export class TailSampler {
  private pendingTraces: Map<string, { spans: TraceSpan[]; received_at: number }>;
  private waitTimeMs: number;
  private cleanupInterval: NodeJS.Timeout | null = null;
  private maxTraces: number;

  constructor(waitTimeMs: number = 60000, maxTraces: number = 10000) {
    this.pendingTraces = new Map();
    this.waitTimeMs = waitTimeMs;
    this.maxTraces = maxTraces;
  }

  addSpan(span: TraceSpan): void {
    if (!this.pendingTraces.has(span.trace_id)) {
      if (this.pendingTraces.size >= this.maxTraces) {
        const oldestKey = this.pendingTraces.keys().next().value;
        this.pendingTraces.delete(oldestKey);
        logger.debug('Evicted oldest trace from tail sampling buffer');
      }
      this.pendingTraces.set(span.trace_id, {
        spans: [],
        received_at: Date.now(),
      });
    }
    const trace = this.pendingTraces.get(span.trace_id)!;
    trace.spans.push(span);
  }

  getReadyTraces(): TraceSpan[][] {
    const now = Date.now();
    const readyTraces: TraceSpan[][] = [];

    for (const [traceId, trace] of this.pendingTraces.entries()) {
      if (now - trace.received_at >= this.waitTimeMs) {
        readyTraces.push(trace.spans);
        this.pendingTraces.delete(traceId);
      }
    }

    return readyTraces;
  }

  getTrace(traceId: string): TraceSpan[] | undefined {
    return this.pendingTraces.get(traceId)?.spans;
  }

  getAllTraceIds(): string[] {
    return Array.from(this.pendingTraces.keys());
  }

  startCleanup(intervalMs: number = 10000): void {
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
    }
    this.cleanupInterval = setInterval(() => {
      this.getReadyTraces();
    }, intervalMs);
  }

  stopCleanup(): void {
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
      this.cleanupInterval = null;
    }
  }

  clear(): void {
    this.stopCleanup();
    this.pendingTraces.clear();
  }

  size(): number {
    return this.pendingTraces.size;
  }
}

export class TraceProcessor extends EventEmitter<TracingEvents> {
  private sampler: TraceSampler;
  private tailSampler: TailSampler;
  private sampledSpans: Map<string, TraceSpan[]>;
  private spanCache: InMemoryCache<TraceSpan>;
  private maxSampledSpans: number;

  constructor(samplingConfig?: Partial<SamplingConfig>, maxSampledSpans: number = 100000) {
    super();
    this.sampler = new TraceSampler(samplingConfig);
    this.tailSampler = new TailSampler(
      samplingConfig?.tail_sampling_wait_time ? parseDuration(samplingConfig.tail_sampling_wait_time) : 60000
    );
    this.sampledSpans = new Map();
    this.spanCache = cacheManager.createInMemoryCache<TraceSpan>('trace_spans', {
      default_ttl: 3600000,
      max_size: 100000,
    });
    this.maxSampledSpans = maxSampledSpans;
  }

  receiveSpan(span: TraceSpan): boolean {
    this.emit('span.received', span);

    metricsService.recordMetric('trace_spans_received_total', 1, {
      service: span.service_name,
      status: span.status,
    });

    const samplingRate = this.sampler.getSamplingRateForSpan(span);
    const shouldSample = this.sampler.shouldSample(span);

    this.emit('span.sampled', { span, sampling_rate: samplingRate, decision: shouldSample });

    if (!shouldSample) {
      return false;
    }

    this.cacheSpan(span);

    if (this.sampler.getConfig().tail_sampling_enabled) {
      this.tailSampler.addSpan(span);
    } else {
      this.storeSampledSpan(span);
    }

    return true;
  }

  receiveSpans(spans: TraceSpan[]): number {
    let sampled = 0;
    for (const span of spans) {
      if (this.receiveSpan(span)) {
        sampled++;
      }
    }
    return sampled;
  }

  private cacheSpan(span: TraceSpan): void {
    this.spanCache.set(span.span_id, span);
  }

  private storeSampledSpan(span: TraceSpan): void {
    if (!this.sampledSpans.has(span.trace_id)) {
      this.sampledSpans.set(span.trace_id, []);
    }
    const traceSpans = this.sampledSpans.get(span.trace_id)!;
    traceSpans.push(span);

    let totalSpans = 0;
    for (const spans of this.sampledSpans.values()) {
      totalSpans += spans.length;
    }
    if (totalSpans > this.maxSampledSpans) {
      const oldestTrace = this.sampledSpans.keys().next().value;
      const removed = this.sampledSpans.get(oldestTrace)?.length || 0;
      this.sampledSpans.delete(oldestTrace);
      logger.debug(`Evicted trace ${oldestTrace} with ${removed} spans to maintain limit`);
    }

    const rootSpan = this.findRootSpan(span.trace_id);
    if (rootSpan && this.isTraceComplete(rootSpan)) {
      const duration = rootSpan.duration_ms;
      const spanCount = traceSpans.length;
      this.emit('trace.completed', { trace_id: span.trace_id, span_count: spanCount, duration_ms: duration });

      metricsService.recordMetric('trace_duration_ms', duration, {
        service: rootSpan.service_name,
        operation: rootSpan.name,
      });
      metricsService.recordMetric('trace_span_count', spanCount, {
        service: rootSpan.service_name,
      });
    }
  }

  private findRootSpan(traceId: string): TraceSpan | undefined {
    const spans = this.sampledSpans.get(traceId);
    if (!spans) return undefined;
    return spans.find((s) => !s.parent_span_id);
  }

  private isTraceComplete(rootSpan: TraceSpan): boolean {
    return rootSpan.end_time > 0;
  }

  getTrace(traceId: string): TraceSpan[] | undefined {
    return this.sampledSpans.get(traceId);
  }

  async getSpan(spanId: string): Promise<TraceSpan | undefined> {
    return this.spanCache.get(spanId);
  }

  getTracesByService(serviceName: string): TraceSpan[][] {
    const traces: TraceSpan[][] = [];
    for (const spans of this.sampledSpans.values()) {
      if (spans.some((s) => s.service_name === serviceName)) {
        traces.push(spans);
      }
    }
    return traces;
  }

  getTracesByOperation(operationName: string): TraceSpan[][] {
    const traces: TraceSpan[][] = [];
    for (const spans of this.sampledSpans.values()) {
      if (spans.some((s) => s.name === operationName)) {
        traces.push(spans);
      }
    }
    return traces;
  }

  getErrorTraces(): TraceSpan[][] {
    const traces: TraceSpan[][] = [];
    for (const spans of this.sampledSpans.values()) {
      if (spans.some((s) => s.status === 'error')) {
        traces.push(spans);
      }
    }
    return traces;
  }

  updateSamplingConfig(config: Partial<SamplingConfig>): void {
    this.sampler.updateConfig(config);
    this.emit('sampling.config_updated', this.sampler.getConfig());
  }

  getSamplingConfig(): SamplingConfig {
    return this.sampler.getConfig();
  }

  getStats(): {
    total_traces: number;
    total_spans: number;
    pending_tail_traces: number;
    sampling_rate: number;
  } {
    let totalSpans = 0;
    for (const spans of this.sampledSpans.values()) {
      totalSpans += spans.length;
    }

    return {
      total_traces: this.sampledSpans.size,
      total_spans: totalSpans,
      pending_tail_traces: this.tailSampler.size(),
      sampling_rate: this.sampler.getConfig().default_sampling_rate,
    };
  }

  start(): void {
    if (this.sampler.getConfig().tail_sampling_enabled) {
      this.tailSampler.startCleanup();
    }
    logger.info('Trace processor started');
  }

  stop(): void {
    this.tailSampler.stopCleanup();
    logger.info('Trace processor stopped');
  }

  clear(): void {
    this.stop();
    this.sampledSpans.clear();
    this.tailSampler.clear();
  }
}

export class TraceCollector {
  private processor: TraceProcessor;

  constructor(samplingConfig?: Partial<SamplingConfig>) {
    this.processor = new TraceProcessor(samplingConfig);
  }

  getProcessor(): TraceProcessor {
    return this.processor;
  }

  collect(span: TraceSpan): boolean {
    return this.processor.receiveSpan(span);
  }

  collectBatch(spans: TraceSpan[]): number {
    return this.processor.receiveSpans(spans);
  }

  async createSpan(
    traceId: string,
    parentSpanId: string | undefined,
    name: string,
    serviceName: string,
    startTime: number,
    endTime: number,
    status: 'ok' | 'error' | 'unknown' = 'ok',
    attributes?: Record<string, any>
  ): Promise<TraceSpan> {
    const span: TraceSpan = {
      trace_id: traceId,
      span_id: generateId('span'),
      parent_span_id: parentSpanId,
      name,
      service_name: serviceName,
      start_time: startTime,
      end_time: endTime,
      duration_ms: endTime - startTime,
      status,
      attributes,
    };

    this.processor.receiveSpan(span);
    return span;
  }

  start(): void {
    this.processor.start();
  }

  stop(): void {
    this.processor.stop();
  }
}

const traceCollector = new TraceCollector();

export default traceCollector;
