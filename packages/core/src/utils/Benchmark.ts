export interface BenchmarkResult {
  name: string;
  iterations: number;
  totalMs: number;
  avgMs: number;
  minMs: number;
  maxMs: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
}

export function benchmark<T>(
  name: string,
  fn: () => T,
  iterations: number = 100
): BenchmarkResult {
  const times: number[] = [];
  let totalMs = 0;
  let minMs = Infinity;
  let maxMs = -Infinity;

  for (let i = 0; i < iterations; i++) {
    const start = performance.now();
    fn();
    const elapsed = performance.now() - start;
    times.push(elapsed);
    totalMs += elapsed;
    if (elapsed < minMs) minMs = elapsed;
    if (elapsed > maxMs) maxMs = elapsed;
  }

  times.sort((a, b) => a - b);

  const p50Index = Math.floor(times.length * 0.5);
  const p95Index = Math.floor(times.length * 0.95);
  const p99Index = Math.floor(times.length * 0.99);

  return {
    name,
    iterations,
    totalMs,
    avgMs: totalMs / iterations,
    minMs,
    maxMs,
    p50Ms: times[Math.min(p50Index, times.length - 1)],
    p95Ms: times[Math.min(p95Index, times.length - 1)],
    p99Ms: times[Math.min(p99Index, times.length - 1)],
  };
}

export function compareBenchmarks(results: BenchmarkResult[]): string {
  if (results.length === 0) return 'No benchmarks to compare.';

  const lines: string[] = [];
  lines.push('Benchmark Comparison');
  lines.push('='.repeat(80));

  const header = [
    'Name'.padEnd(25),
    'Iterations'.padStart(12),
    'Total(ms)'.padStart(12),
    'Avg(ms)'.padStart(12),
    'P50(ms)'.padStart(12),
    'P95(ms)'.padStart(12),
    'P99(ms)'.padStart(12),
  ].join(' | ');

  lines.push(header);
  lines.push('-'.repeat(80));

  const sorted = [...results].sort((a, b) => a.avgMs - b.avgMs);
  const bestAvg = sorted[0].avgMs;

  for (const r of sorted) {
    const speedup = bestAvg > 0 ? (bestAvg / r.avgMs).toFixed(2) + 'x' : 'N/A';
    const row = [
      r.name.padEnd(25),
      String(r.iterations).padStart(12),
      r.totalMs.toFixed(3).padStart(12),
      r.avgMs.toFixed(4).padStart(12),
      r.p50Ms.toFixed(4).padStart(12),
      r.p95Ms.toFixed(4).padStart(12),
      r.p99Ms.toFixed(4).padStart(12),
    ].join(' | ');
    lines.push(row + '  ' + speedup);
  }

  return lines.join('\n');
}
