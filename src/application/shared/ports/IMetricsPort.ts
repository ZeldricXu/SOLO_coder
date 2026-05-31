export interface IMetricsPort {
  increment(name: string, value?: number, labels?: Record<string, string>): void;
  decrement(name: string, value?: number, labels?: Record<string, string>): void;
  gauge(name: string, value: number, labels?: Record<string, string>): void;
  histogram(name: string, value: number, labels?: Record<string, string>): void;
  timing(name: string, valueMs: number, labels?: Record<string, string>): void;
  startTimer(name: string, labels?: Record<string, string>): () => void;
  getSnapshot(): {
    counters: Record<string, number>;
    gauges: Record<string, number>;
    requestCount: number;
    errorCount: number;
    uptime: number;
  };
  getSummary(): {
    requestCount: number;
    errorCount: number;
    avgResponseTime: number;
    throughput: number;
    errorRate: number;
  };
}

export const METRICS_PORT = Symbol('IMetricsPort');
