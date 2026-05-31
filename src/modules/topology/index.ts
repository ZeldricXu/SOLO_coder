import { ITopologyBuilder } from '@ports/index';
import { TraceSpan, TopologyGraph, ServiceNode, ServiceEdge } from '@apptypes/index';
import { rootLogger } from '@modules/logging';
import { nowISO, calculateAvg } from '@utils/index';

interface SpanWindow {
  span: TraceSpan;
  timestamp: number;
}

interface EdgeMetrics {
  call_count: number;
  total_latency: number;
  error_count: number;
}

export class TopologyBuilder implements ITopologyBuilder {
  private logger = rootLogger.child({ module: 'TopologyBuilder' });
  private spans: Map<string, SpanWindow> = new Map();
  private services: Map<string, { type: string; instances: number; metadata: Record<string, unknown> }> = new Map();
  private edges: Map<string, EdgeMetrics> = new Map();
  private maxSpans: number = 100000;
  private retentionWindowMs: number = 60 * 60 * 1000;

  private getEdgeKey(source: string, target: string): string {
    return `${source}->${target}`;
  }

  private cleanupOldSpans(): void {
    const now = Date.now();
    const cutoff = now - this.retentionWindowMs;
    let removed = 0;

    for (const [traceId, window] of this.spans) {
      if (window.timestamp < cutoff) {
        this.spans.delete(traceId);
        removed++;
      }
    }

    if (removed > 0) {
      this.logger.debug('Cleaned up old spans', { removed });
    }
  }

  private limitSpans(): void {
    if (this.spans.size > this.maxSpans) {
      const entries = Array.from(this.spans.entries())
        .sort((a, b) => a[1].timestamp - b[1].timestamp)
        .slice(0, this.spans.size - this.maxSpans);

      entries.forEach(([key]) => this.spans.delete(key));
      this.logger.debug('Limited span count', { removed: entries.length });
    }
  }

  async ingestSpan(span: TraceSpan): Promise<void> {
    this.spans.set(span.trace_id, {
      span,
      timestamp: Date.now(),
    });

    this.services.set(span.service_name, {
      type: span.attributes['service.type'] as string || 'service',
      instances: 1,
      metadata: {},
    });

    if (span.parent_span_id) {
      const parentSpan = this.findParentSpan(span.parent_span_id);
      if (parentSpan && parentSpan.service_name !== span.service_name) {
        const edgeKey = this.getEdgeKey(parentSpan.service_name, span.service_name);
        const existing = this.edges.get(edgeKey) || {
          call_count: 0,
          total_latency: 0,
          error_count: 0,
        };

        const startTime = new Date(span.start_time).getTime();
        const endTime = new Date(span.end_time).getTime();
        const latency = endTime - startTime;

        existing.call_count++;
        existing.total_latency += latency;
        if (span.status === 'error') {
          existing.error_count++;
        }

        this.edges.set(edgeKey, existing);
      }
    }

    this.cleanupOldSpans();
    this.limitSpans();
  }

  private findParentSpan(parentSpanId: string): TraceSpan | null {
    for (const window of this.spans.values()) {
      if (window.span.span_id === parentSpanId) {
        return window.span;
      }
    }
    return null;
  }

  async ingestSpans(spans: TraceSpan[]): Promise<void> {
    for (const span of spans) {
      await this.ingestSpan(span);
    }
    this.logger.info('Batch spans ingested', { count: spans.length });
  }

  async getGraph(
    service?: string,
    timeWindow?: { start: number; end: number }
  ): Promise<TopologyGraph> {
    const now = Date.now();
    const startTime = timeWindow?.start || now - this.retentionWindowMs;
    const endTime = timeWindow?.end || now;

    const relevantEdges: Map<string, EdgeMetrics> = new Map();
    const relevantServices: Set<string> = new Set();

    for (const [key, metrics] of this.edges) {
      const [source, target] = key.split('->');

      if (service && source !== service && target !== service) {
        continue;
      }

      relevantServices.add(source);
      relevantServices.add(target);
      relevantEdges.set(key, metrics);
    }

    const nodes: ServiceNode[] = [];
    for (const serviceName of relevantServices) {
      const serviceInfo = this.services.get(serviceName) || {
        type: 'service',
        instances: 1,
        metadata: {},
      };
      nodes.push({
        service_name: serviceName,
        type: serviceInfo.type,
        instances: serviceInfo.instances,
        metadata: serviceInfo.metadata,
      });
    }

    const edges: ServiceEdge[] = [];
    for (const [key, metrics] of relevantEdges) {
      const [source, target] = key.split('->');
      edges.push({
        source,
        target,
        call_count: metrics.call_count,
        avg_latency_ms: metrics.call_count > 0 ? metrics.total_latency / metrics.call_count : 0,
        error_rate: metrics.call_count > 0 ? metrics.error_count / metrics.call_count : 0,
      });
    }

    return {
      nodes,
      edges,
      generated_at: nowISO(),
    };
  }

  async getDependencies(service: string): Promise<string[]> {
    const dependencies: Set<string> = new Set();

    for (const [key] of this.edges) {
      const [source, target] = key.split('->');
      if (source === service) {
        dependencies.add(target);
      }
    }

    return Array.from(dependencies);
  }

  async getDependents(service: string): Promise<string[]> {
    const dependents: Set<string> = new Set();

    for (const [key] of this.edges) {
      const [source, target] = key.split('->');
      if (target === service) {
        dependents.add(source);
      }
    }

    return Array.from(dependents);
  }

  getServiceStats(service: string): {
    incoming_calls: number;
    outgoing_calls: number;
    avg_incoming_latency: number;
    avg_outgoing_latency: number;
    error_rate: number;
  } | null {
    let incomingCalls = 0;
    let outgoingCalls = 0;
    const incomingLatencies: number[] = [];
    const outgoingLatencies: number[] = [];
    let totalErrors = 0;
    let totalCalls = 0;

    for (const [key, metrics] of this.edges) {
      const [source, target] = key.split('->');

      if (target === service) {
        incomingCalls += metrics.call_count;
        if (metrics.call_count > 0) {
          incomingLatencies.push(metrics.total_latency / metrics.call_count);
        }
        totalErrors += metrics.error_count;
        totalCalls += metrics.call_count;
      }

      if (source === service) {
        outgoingCalls += metrics.call_count;
        if (metrics.call_count > 0) {
          outgoingLatencies.push(metrics.total_latency / metrics.call_count);
        }
      }
    }

    if (totalCalls === 0) return null;

    return {
      incoming_calls: incomingCalls,
      outgoing_calls: outgoingCalls,
      avg_incoming_latency: calculateAvg(incomingLatencies),
      avg_outgoing_latency: calculateAvg(outgoingLatencies),
      error_rate: totalErrors / totalCalls,
    };
  }

  listServices(): string[] {
    return Array.from(this.services.keys());
  }

  clear(): void {
    this.spans.clear();
    this.services.clear();
    this.edges.clear();
    this.logger.info('Topology builder cleared');
  }

  setRetentionWindow(ms: number): void {
    this.retentionWindowMs = ms;
  }

  setMaxSpans(max: number): void {
    this.maxSpans = max;
  }
}

export const topologyBuilder = new TopologyBuilder();
