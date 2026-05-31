import { v4 as uuidv4 } from 'uuid';
import { ProfileSample, ProfileStack, FlameGraphNode } from '../types';
import { ProcessingPipeline } from '../core';

export type ProfileEventType = 'session_started' | 'session_completed' | 'session_failed' | 'flamegraph_ready';

export interface ProfileEvent {
  type: ProfileEventType;
  sessionId: string;
  timestamp: number;
  data?: Record<string, unknown>;
}

export type ProfileEventListener = (event: ProfileEvent) => void;

export type ProfileCallback = (error: Error | null, session?: AsyncProfilingSession) => void;

export interface ProfilingSession {
  id: string;
  type: 'cpu' | 'memory' | 'wall';
  duration: number;
  startTime: number;
  endTime?: number;
  samples: ProfileSample[];
  status: 'running' | 'completed' | 'failed';
}

export interface AsyncProfilingSession extends ProfilingSession {
  promise?: Promise<ProfileSample[]>;
}

export interface ProfileComparison {
  before: ProfileSample;
  after: ProfileSample;
  differences: {
    frame: string;
    beforeValue: number;
    afterValue: number;
    change: number;
    changePercent: number;
  }[];
}

export interface ProfilingModule {
  manager: AsyncProfilingManager;
  flameGraphGenerator: FlameGraphGenerator;
  comparator: ProfileComparator;
  eventBus: ProfileEventBus;
}

export class ProfileEventBus {
  private listeners: Map<ProfileEventType, Set<ProfileEventListener>> = new Map();
  private allListeners: Set<ProfileEventListener> = new Set();

  on(eventType: ProfileEventType | '*', listener: ProfileEventListener): () => void {
    if (eventType === '*') {
      this.allListeners.add(listener);
      return () => this.allListeners.delete(listener);
    }

    if (!this.listeners.has(eventType)) {
      this.listeners.set(eventType, new Set());
    }
    this.listeners.get(eventType)!.add(listener);
    return () => this.listeners.get(eventType)?.delete(listener);
  }

  emit(event: ProfileEvent): void {
    const typeListeners = this.listeners.get(event.type);
    if (typeListeners) {
      for (const listener of typeListeners) {
        try {
          listener(event);
        } catch (error) {
          console.error('[ProfileEventBus] Error in listener:', error);
        }
      }
    }

    for (const listener of this.allListeners) {
      try {
        listener(event);
      } catch (error) {
        console.error('[ProfileEventBus] Error in global listener:', error);
      }
    }
  }

  removeAllListeners(): void {
    this.listeners.clear();
    this.allListeners.clear();
  }

  getListenerCount(eventType?: ProfileEventType): number {
    if (eventType) {
      return this.listeners.get(eventType)?.size || 0;
    }
    let count = this.allListeners.size;
    for (const listeners of this.listeners.values()) {
      count += listeners.size;
    }
    return count;
  }
}

export class CPUSampler {
  private samplingInterval: number = 10;
  private isSampling: boolean = false;
  private samples: ProfileStack[] = [];

  start(durationMs: number): Promise<ProfileStack[]> {
    return new Promise((resolve) => {
      this.isSampling = true;
      this.samples = [];

      const interval = setInterval(() => {
        if (!this.isSampling) {
          clearInterval(interval);
          resolve(this.samples);
          return;
        }
        this.samples.push(this.captureStack());
      }, this.samplingInterval);

      setTimeout(() => {
        this.isSampling = false;
        clearInterval(interval);
        resolve(this.samples);
      }, durationMs);
    });
  }

  private captureStack(): ProfileStack {
    const error = new Error();
    const stackLines = error.stack?.split('\n').slice(2) || [];
    const frames = stackLines
      .map(line => line.trim())
      .filter(line => line.length > 0)
      .slice(0, 50);

    return {
      frames,
      count: 1,
      value: this.samplingInterval,
    };
  }

  setSamplingInterval(interval: number): void {
    this.samplingInterval = interval;
  }
}

export class MemorySampler {
  private isSampling: boolean = false;
  private samples: ProfileStack[] = [];
  private samplingInterval: number = 100;

  start(durationMs: number): Promise<ProfileStack[]> {
    return new Promise((resolve) => {
      this.isSampling = true;
      this.samples = [];

      const interval = setInterval(() => {
        if (!this.isSampling) {
          clearInterval(interval);
          resolve(this.samples);
          return;
        }
        this.samples.push(this.captureMemory());
      }, this.samplingInterval);

      setTimeout(() => {
        this.isSampling = false;
        clearInterval(interval);
        resolve(this.samples);
      }, durationMs);
    });
  }

  private captureMemory(): ProfileStack {
    const usage = process.memoryUsage();
    const frames = [
      `rss:${usage.rss}`,
      `heapTotal:${usage.heapTotal}`,
      `heapUsed:${usage.heapUsed}`,
      `external:${usage.external}`,
    ];

    return {
      frames,
      count: 1,
      value: usage.heapUsed,
    };
  }

  setSamplingInterval(interval: number): void {
    this.samplingInterval = interval;
  }
}

export class FlameGraphGenerator {
  generate(samples: ProfileStack[]): FlameGraphNode {
    const root: FlameGraphNode = {
      name: 'root',
      value: 0,
      children: [],
    };

    for (const stack of samples) {
      let current = root;
      current.value += stack.value;

      for (let i = stack.frames.length - 1; i >= 0; i--) {
        const frame = stack.frames[i];
        let child = current.children.find(c => c.name === frame);
        if (!child) {
          child = {
            name: frame,
            value: 0,
            children: [],
          };
          current.children.push(child);
        }
        child.value += stack.value;
        current = child;
      }
    }

    return root;
  }

  toJSON(root: FlameGraphNode): string {
    return JSON.stringify(root, null, 2);
  }

  toSVG(root: FlameGraphNode, width: number = 1200, height: number = 600): string {
    const rowHeight = 18;
    const maxDepth = this.getMaxDepth(root);
    const graphHeight = Math.min(maxDepth * rowHeight + 40, height);

    let svg = `<svg width="${width}" height="${graphHeight}" xmlns="http://www.w3.org/2000/svg">`;
    svg += `<style>
      .frame { cursor: pointer; }
      .frame:hover { opacity: 0.8; }
      text { font-family: monospace; font-size: 12px; }
    </style>`;

    const totalValue = root.value;
    const xScale = (width - 20) / totalValue;

    const drawNode = (
      node: FlameGraphNode,
      x: number,
      y: number,
      parentWidth: number
    ) => {
      const nodeWidth = (node.value / totalValue) * (width - 20);
      const color = this.getColor(node.name, node.value, totalValue);

      svg += `<g class="frame" transform="translate(${x + 10}, ${y})">`;
      svg += `<rect width="${nodeWidth}" height="${rowHeight - 2}" fill="${color}" rx="2" />`;
      if (nodeWidth > 30) {
        const displayName = node.name.length > Math.floor(nodeWidth / 7)
          ? node.name.substring(0, Math.floor(nodeWidth / 7) - 3) + '...'
          : node.name;
        svg += `<text x="4" y="${rowHeight - 6}" fill="#000">${displayName}</text>`;
      }
      svg += `<title>${node.name}: ${node.value.toFixed(0)}</title>`;
      svg += `</g>`;

      let childX = x;
      for (const child of node.children) {
        drawNode(child, childX, y - rowHeight, nodeWidth);
        childX += (child.value / totalValue) * (width - 20);
      }
    };

    drawNode(root, 0, graphHeight - rowHeight, width - 20);
    svg += `</svg>`;
    return svg;
  }

  private getMaxDepth(node: FlameGraphNode, depth: number = 0): number {
    if (node.children.length === 0) return depth;
    return Math.max(...node.children.map(c => this.getMaxDepth(c, depth + 1)));
  }

  private getColor(name: string, value: number, total: number): string {
    const hue = (name.charCodeAt(0) * 137.5) % 360;
    const saturation = 50 + (value / total) * 30;
    const lightness = 60 + (value / total) * 20;
    return `hsl(${hue}, ${saturation}%, ${lightness}%)`;
  }
}

export class ProfileComparator {
  compare(before: ProfileSample, after: ProfileSample): ProfileComparison {
    const beforeFrames = this.aggregateFrames(before.stacks);
    const afterFrames = this.aggregateFrames(after.stacks);

    const allFrames = new Set([...beforeFrames.keys(), ...afterFrames.keys()]);
    const differences: ProfileComparison['differences'] = [];

    for (const frame of allFrames) {
      const beforeValue = beforeFrames.get(frame) || 0;
      const afterValue = afterFrames.get(frame) || 0;
      const change = afterValue - beforeValue;
      const changePercent = beforeValue > 0 ? (change / beforeValue) * 100 : (afterValue > 0 ? 100 : 0);

      if (Math.abs(change) > 0.01 || Math.abs(changePercent) > 1) {
        differences.push({
          frame,
          beforeValue,
          afterValue,
          change,
          changePercent,
        });
      }
    }

    differences.sort((a, b) => Math.abs(b.change) - Math.abs(a.change));

    return {
      before,
      after,
      differences: differences.slice(0, 20),
    };
  }

  private aggregateFrames(stacks: ProfileStack[]): Map<string, number> {
    const frames = new Map<string, number>();
    for (const stack of stacks) {
      for (const frame of stack.frames) {
        frames.set(frame, (frames.get(frame) || 0) + stack.value);
      }
    }
    return frames;
  }

  generateDiffReport(comparison: ProfileComparison): string {
    let report = `Profile Comparison Report\n`;
    report += `=========================\n\n`;
    report += `Before: ${new Date(comparison.before.timestamp).toISOString()}\n`;
    report += `After: ${new Date(comparison.after.timestamp).toISOString()}\n\n`;
    report += `Top Changes:\n`;
    report += `-`.repeat(80) + `\n`;
    report += `Frame                          Before     After      Change     %\n`;
    report += `-`.repeat(80) + `\n`;

    for (const diff of comparison.differences) {
      const frame = diff.frame.length > 30 ? diff.frame.substring(0, 27) + '...' : diff.frame.padEnd(30);
      const before = diff.beforeValue.toFixed(0).padStart(10);
      const after = diff.afterValue.toFixed(0).padStart(10);
      const change = (diff.change >= 0 ? '+' : '') + diff.change.toFixed(0).padStart(9);
      const percent = (diff.changePercent >= 0 ? '+' : '') + diff.changePercent.toFixed(1) + '%';
      report += `${frame}${before}${after}${change}${percent.padStart(8)}\n`;
    }

    return report;
  }
}

export class AsyncProfilingManager {
  private sessions: Map<string, AsyncProfilingSession> = new Map();
  private cpuSampler: CPUSampler = new CPUSampler();
  private memorySampler: MemorySampler = new MemorySampler();
  private flameGraphGenerator: FlameGraphGenerator = new FlameGraphGenerator();
  private comparator: ProfileComparator = new ProfileComparator();
  private eventBus: ProfileEventBus = new ProfileEventBus();
  private pipeline: ProcessingPipeline<{ type: string; duration: number }, AsyncProfilingSession>;
  private maxConcurrentSessions: number = 10;
  private activeSessions: number = 0;

  constructor() {
    this.pipeline = this.buildPipeline();
  }

  private buildPipeline(): ProcessingPipeline<{ type: string; duration: number }, AsyncProfilingSession> {
    return new ProcessingPipeline<{ type: string; duration: number }, AsyncProfilingSession>()
      .addStage({
        name: 'validation',
        process: async (input) => {
          if (!['cpu', 'memory', 'wall'].includes(input.type)) {
            throw new Error(`Invalid profiling type: ${input.type}`);
          }
          if (input.duration <= 0 || input.duration > 60000) {
            throw new Error('Duration must be between 1ms and 60000ms');
          }
          if (this.activeSessions >= this.maxConcurrentSessions) {
            throw new Error('Maximum concurrent sessions exceeded');
          }
          return input;
        },
      })
      .addStage({
        name: 'session_creation',
        process: async (input) => this.createSession(input.type as 'cpu' | 'memory' | 'wall', input.duration),
      });
  }

  private createSession(type: 'cpu' | 'memory' | 'wall', duration: number): AsyncProfilingSession {
    const session: AsyncProfilingSession = {
      id: uuidv4(),
      type,
      duration,
      startTime: Date.now(),
      samples: [],
      status: 'running',
    };
    this.sessions.set(session.id, session);
    this.activeSessions++;

    this.eventBus.emit({
      type: 'session_started',
      sessionId: session.id,
      timestamp: Date.now(),
      data: { type, duration },
    });

    this.executeProfiling(session).catch((error) => {
      console.error('[Profiling] Session failed:', error);
    });

    return session;
  }

  private async executeProfiling(session: AsyncProfilingSession): Promise<void> {
    try {
      let stacks: ProfileStack[];
      if (session.type === 'cpu' || session.type === 'wall') {
        stacks = await this.cpuSampler.start(session.duration);
      } else {
        stacks = await this.memorySampler.start(session.duration);
      }

      const sample: ProfileSample = {
        timestamp: new Date().toISOString(),
        type: session.type,
        duration: session.duration,
        stacks,
      };

      session.samples.push(sample);
      session.status = 'completed';
      session.endTime = Date.now();

      this.eventBus.emit({
        type: 'session_completed',
        sessionId: session.id,
        timestamp: Date.now(),
        data: { sampleCount: session.samples.length },
      });

      this.eventBus.emit({
        type: 'flamegraph_ready',
        sessionId: session.id,
        timestamp: Date.now(),
      });
    } catch (error) {
      session.status = 'failed';
      session.endTime = Date.now();

      this.eventBus.emit({
        type: 'session_failed',
        sessionId: session.id,
        timestamp: Date.now(),
        data: { error: (error as Error).message },
      });
    } finally {
      this.activeSessions = Math.max(0, this.activeSessions - 1);
    }
  }

  startSession(
    type: 'cpu' | 'memory' | 'wall',
    duration: number,
    callback?: ProfileCallback
  ): Promise<AsyncProfilingSession> {
    return new Promise(async (resolve, reject) => {
      try {
        const result = await this.pipeline.execute({ type, duration });
        if (!result.success || !result.data) {
          const error = new Error(result.error || 'Failed to start profiling session');
          if (callback) callback(error);
          reject(error);
          return;
        }

        const session = result.data;

        if (callback) {
          const off = this.eventBus.on('*', (event) => {
            if (event.sessionId === session.id) {
              if (event.type === 'session_completed') {
                off();
                callback(null, session);
              } else if (event.type === 'session_failed') {
                off();
                callback(new Error((event.data?.error as string) || 'Session failed'));
              }
            }
          });
        }

        resolve(session);
      } catch (error) {
        if (callback) callback(error as Error);
        reject(error);
      }
    });
  }

  startSessionAsync(
    type: 'cpu' | 'memory' | 'wall',
    duration: number
  ): Promise<AsyncProfilingSession> {
    return new Promise((resolve, reject) => {
      const off = this.eventBus.on('*', (event) => {
        const session = this.getSession(event.sessionId);
        if (!session) return;

        if (event.type === 'session_completed') {
          off();
          resolve(session);
        } else if (event.type === 'session_failed') {
          off();
          reject(new Error((event.data?.error as string) || 'Session failed'));
        }
      });

      this.startSession(type, duration).catch((error) => {
        off();
        reject(error);
      });
    });
  }

  getSession(id: string): AsyncProfilingSession | undefined {
    return this.sessions.get(id);
  }

  listSessions(): AsyncProfilingSession[] {
    return Array.from(this.sessions.values());
  }

  generateFlameGraph(sessionId: string): FlameGraphNode | null {
    const session = this.sessions.get(sessionId);
    if (!session || session.samples.length === 0) {
      return null;
    }
    const allStacks = session.samples.flatMap(s => s.stacks);
    return this.flameGraphGenerator.generate(allStacks);
  }

  generateFlameGraphSVG(sessionId: string, width?: number, height?: number): string | null {
    const flameGraph = this.generateFlameGraph(sessionId);
    if (!flameGraph) return null;
    return this.flameGraphGenerator.toSVG(flameGraph, width, height);
  }

  compareSessions(beforeId: string, afterId: string): ProfileComparison | null {
    const before = this.sessions.get(beforeId);
    const after = this.sessions.get(afterId);

    if (!before || !after || before.samples.length === 0 || after.samples.length === 0) {
      return null;
    }

    return this.comparator.compare(before.samples[0], after.samples[0]);
  }

  generateDiffReport(beforeId: string, afterId: string): string | null {
    const comparison = this.compareSessions(beforeId, afterId);
    if (!comparison) return null;
    return this.comparator.generateDiffReport(comparison);
  }

  getEventBus(): ProfileEventBus {
    return this.eventBus;
  }

  setMaxConcurrentSessions(max: number): void {
    this.maxConcurrentSessions = max;
  }

  getActiveSessionCount(): number {
    return this.activeSessions;
  }

  cancelSession(sessionId: string): boolean {
    const session = this.sessions.get(sessionId);
    if (session && session.status === 'running') {
      session.status = 'failed';
      session.endTime = Date.now();
      this.activeSessions = Math.max(0, this.activeSessions - 1);
      return true;
    }
    return false;
  }
}

export function createProfilingModule(): ProfilingModule {
  const manager = new AsyncProfilingManager();
  const flameGraphGenerator = new FlameGraphGenerator();
  const comparator = new ProfileComparator();
  const eventBus = manager.getEventBus();

  return {
    manager,
    flameGraphGenerator,
    comparator,
    eventBus,
  };
}
