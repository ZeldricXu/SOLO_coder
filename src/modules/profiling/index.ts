import { IProfiler } from '@ports/index';
import { ProfileSample, FlameGraphNode } from '@apptypes/index';
import { rootLogger } from '@modules/logging';
import { generateId, nowEpoch, sleep } from '@utils/index';
import { config } from '@config/index';

interface ActiveProfile {
  id: string;
  type: 'cpu' | 'memory';
  startTime: number;
  samples: ProfileSample[];
  intervalId: NodeJS.Timeout | null;
  autoStopTimeout: NodeJS.Timeout | null;
}

interface StackFrame {
  functionName: string;
  fileName: string;
  lineNumber: number;
}

export class Profiler implements IProfiler {
  private logger = rootLogger.child({ module: 'Profiler' });
  private activeProfiles: Map<string, ActiveProfile> = new Map();
  private cpuSamplingInterval: number;
  private memorySamplingInterval: number;
  private maxProfileDuration: number;

  constructor() {
    this.cpuSamplingInterval = config.profiling.cpuSamplingInterval;
    this.memorySamplingInterval = config.profiling.memorySamplingInterval;
    this.maxProfileDuration = config.profiling.maxProfileDuration;
  }

  private captureStackTrace(): string[] {
    const err = new Error();
    const stack = err.stack?.split('\n') || [];
    const frames: string[] = [];

    for (const line of stack.slice(3)) {
      const match = line.match(/at (.+?) \((.+?):(\d+):(\d+)\)/) || line.match(/at (.+?):(\d+):(\d+)/);
      if (match) {
        const functionName = match[1] || 'anonymous';
        const fileName = match[2] || 'unknown';
        const lineNumber = match[3] || '0';
        frames.push(`${functionName} (${fileName}:${lineNumber})`);
      }
    }

    return frames.length > 0 ? frames : ['<root>'];
  }

  private captureMemoryUsage(): NodeJS.MemoryUsage {
    return process.memoryUsage();
  }

  private captureCPUUsage(): { user: number; system: number } {
    const usage = process.cpuUsage();
    return {
      user: usage.user,
      system: usage.system,
    };
  }

  async startCPUProfiling(durationMs?: number): Promise<string> {
    const profileId = generateId('prof_cpu_');
    const maxDuration = durationMs || this.maxProfileDuration;

    const profile: ActiveProfile = {
      id: profileId,
      type: 'cpu',
      startTime: nowEpoch(),
      samples: [],
      intervalId: null,
      autoStopTimeout: null,
    };

    this.activeProfiles.set(profileId, profile);

    profile.intervalId = setInterval(() => {
      if (!this.activeProfiles.has(profileId)) return;

      const stackTrace = this.captureStackTrace();
      const cpuUsage = this.captureCPUUsage();

      profile.samples.push({
        timestamp: nowEpoch(),
        type: 'cpu',
        stack_trace: stackTrace,
        duration_ms: this.cpuSamplingInterval,
      });

      this.logger.debug('CPU sample collected', {
        profile_id: profileId,
        sample_count: profile.samples.length,
        cpu_user: cpuUsage.user,
        cpu_system: cpuUsage.system,
      });
    }, this.cpuSamplingInterval);

    profile.autoStopTimeout = setTimeout(async () => {
      this.logger.info('Auto-stopping CPU profile due to max duration', { profile_id: profileId });
      await this.stopCPUProfiling(profileId);
    }, maxDuration);

    this.logger.info('CPU profiling started', { profile_id: profileId, duration_ms: maxDuration });
    return profileId;
  }

  async stopCPUProfiling(profileId: string): Promise<ProfileSample[]> {
    const profile = this.activeProfiles.get(profileId);
    if (!profile || profile.type !== 'cpu') {
      throw new Error(`CPU profile not found: ${profileId}`);
    }

    if (profile.intervalId) {
      clearInterval(profile.intervalId);
      profile.intervalId = null;
    }
    if (profile.autoStopTimeout) {
      clearTimeout(profile.autoStopTimeout);
      profile.autoStopTimeout = null;
    }

    this.activeProfiles.delete(profileId);
    this.logger.info('CPU profiling stopped', {
      profile_id: profileId,
      sample_count: profile.samples.length,
      duration_ms: nowEpoch() - profile.startTime,
    });

    return profile.samples;
  }

  async startMemoryProfiling(durationMs?: number): Promise<string> {
    const profileId = generateId('prof_mem_');
    const maxDuration = durationMs || this.maxProfileDuration;

    const profile: ActiveProfile = {
      id: profileId,
      type: 'memory',
      startTime: nowEpoch(),
      samples: [],
      intervalId: null,
      autoStopTimeout: null,
    };

    this.activeProfiles.set(profileId, profile);

    profile.intervalId = setInterval(() => {
      if (!this.activeProfiles.has(profileId)) return;

      const memoryUsage = this.captureMemoryUsage();
      const stackTrace = this.captureStackTrace();

      profile.samples.push({
        timestamp: nowEpoch(),
        type: 'memory',
        stack_trace: stackTrace,
        duration_ms: this.memorySamplingInterval,
      });

      this.logger.debug('Memory sample collected', {
        profile_id: profileId,
        sample_count: profile.samples.length,
        heap_used: memoryUsage.heapUsed,
        heap_total: memoryUsage.heapTotal,
      });
    }, this.memorySamplingInterval);

    profile.autoStopTimeout = setTimeout(async () => {
      this.logger.info('Auto-stopping memory profile due to max duration', { profile_id: profileId });
      await this.stopMemoryProfiling(profileId);
    }, maxDuration);

    this.logger.info('Memory profiling started', { profile_id: profileId, duration_ms: maxDuration });
    return profileId;
  }

  async stopMemoryProfiling(profileId: string): Promise<ProfileSample[]> {
    const profile = this.activeProfiles.get(profileId);
    if (!profile || profile.type !== 'memory') {
      throw new Error(`Memory profile not found: ${profileId}`);
    }

    if (profile.intervalId) {
      clearInterval(profile.intervalId);
      profile.intervalId = null;
    }
    if (profile.autoStopTimeout) {
      clearTimeout(profile.autoStopTimeout);
      profile.autoStopTimeout = null;
    }

    this.activeProfiles.delete(profileId);
    this.logger.info('Memory profiling stopped', {
      profile_id: profileId,
      sample_count: profile.samples.length,
      duration_ms: nowEpoch() - profile.startTime,
    });

    return profile.samples;
  }

  generateFlameGraph(samples: ProfileSample[]): FlameGraphNode {
    const root: FlameGraphNode = {
      name: 'root',
      value: 0,
      children: [],
    };

    const nodeMap: Map<string, FlameGraphNode> = new Map();
    nodeMap.set('root', root);

    for (const sample of samples) {
      root.value++;

      const stack = sample.stack_trace.slice().reverse();
      let currentPath = 'root';
      let currentNode = root;

      for (const frame of stack) {
        currentPath += `/${frame}`;

        let childNode = nodeMap.get(currentPath);
        if (!childNode) {
          childNode = {
            name: frame,
            value: 0,
            children: [],
          };
          currentNode.children.push(childNode);
          nodeMap.set(currentPath, childNode);
        }

        childNode.value++;
        currentNode = childNode;
      }
    }

    this.logger.info('Flame graph generated', {
      sample_count: samples.length,
      node_count: nodeMap.size,
    });

    return root;
  }

  compareFlameGraphs(graph1: FlameGraphNode, graph2: FlameGraphNode): FlameGraphNode {
    const diffRoot: FlameGraphNode = {
      name: 'root (diff)',
      value: graph2.value - graph1.value,
      children: [],
    };

    const addDiffNodes = (
      diffNode: FlameGraphNode,
      node1: FlameGraphNode | undefined,
      node2: FlameGraphNode | undefined
    ) => {
      const value1 = node1?.value || 0;
      const value2 = node2?.value || 0;
      diffNode.value = value2 - value1;

      const childMap = new Map<string, [FlameGraphNode | undefined, FlameGraphNode | undefined]>();

      node1?.children.forEach((child) => {
        childMap.set(child.name, [child, undefined]);
      });

      node2?.children.forEach((child) => {
        const existing = childMap.get(child.name);
        if (existing) {
          existing[1] = child;
        } else {
          childMap.set(child.name, [undefined, child]);
        }
      });

      for (const [name, [n1, n2]] of childMap) {
        const diffChild: FlameGraphNode = {
          name,
          value: 0,
          children: [],
        };
        diffNode.children.push(diffChild);
        addDiffNodes(diffChild, n1, n2);
      }
    };

    addDiffNodes(diffRoot, graph1, graph2);
    this.logger.info('Flame graph comparison generated');
    return diffRoot;
  }

  getActiveProfiles(): { id: string; type: 'cpu' | 'memory'; duration_ms: number }[] {
    const now = nowEpoch();
    return Array.from(this.activeProfiles.values()).map((p) => ({
      id: p.id,
      type: p.type,
      duration_ms: now - p.startTime,
    }));
  }

  async profileAsync<T>(fn: () => Promise<T>, options?: { type?: 'cpu' | 'memory' }): Promise<{ result: T; samples: ProfileSample[] }> {
    const type = options?.type || 'cpu';
    const profileId = type === 'cpu' ? await this.startCPUProfiling() : await this.startMemoryProfiling();

    try {
      const result = await fn();
      const samples = type === 'cpu' ? await this.stopCPUProfiling(profileId) : await this.stopMemoryProfiling(profileId);
      return { result, samples };
    } catch (error) {
      if (type === 'cpu') {
        await this.stopCPUProfiling(profileId);
      } else {
        await this.stopMemoryProfiling(profileId);
      }
      throw error;
    }
  }

  stopAll(): void {
    for (const [profileId, profile] of this.activeProfiles) {
      if (profile.intervalId) {
        clearInterval(profile.intervalId);
      }
      if (profile.autoStopTimeout) {
        clearTimeout(profile.autoStopTimeout);
      }
      this.logger.info('Force stopped profile', { profile_id: profileId });
    }
    this.activeProfiles.clear();
  }
}

export const profiler = new Profiler();
