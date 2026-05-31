import { logger } from '../logging';
import { v4 as uuidv4 } from 'uuid';

export type SidecarStatus = 'injected' | 'running' | 'stopped' | 'error';
export type InjectionStrategy = 'always' | 'conditional' | 'manual';
export type UpdateStrategy = 'rolling' | 'recreate' | 'blue-green';

export interface SidecarConfig {
  name: string;
  image: string;
  version: string;
  injectionStrategy: InjectionStrategy;
  resources: {
    cpuLimit?: string;
    memoryLimit?: string;
    cpuRequest?: string;
    memoryRequest?: string;
  };
  env: Record<string, string>;
  ports: number[];
  enabled: boolean;
  autoUpdate: boolean;
  updateStrategy: UpdateStrategy;
  healthCheck?: {
    httpGet?: { path: string; port: number };
    initialDelaySeconds?: number;
    periodSeconds?: number;
  };
}

export interface SidecarInstance {
  id: string;
  config: SidecarConfig;
  targetPod: string;
  status: SidecarStatus;
  injectedAt: string;
  startedAt?: string;
  stoppedAt?: string;
  lastConfigVersion: number;
  error?: string;
}

export interface HotUpdateResult {
  success: boolean;
  updated: boolean;
  previousVersion: string;
  newVersion: string;
  duration: number;
}

export class SidecarLifecycleManager {
  private sidecars: Map<string, SidecarInstance> = new Map();
  private configs: Map<string, SidecarConfig> = new Map();
  private configVersions: Map<string, number> = new Map();

  registerConfig(config: SidecarConfig): void {
    this.configs.set(config.name, config);
    this.configVersions.set(config.name, 1);
    logger.info('Sidecar config registered', { name: config.name, version: config.version });
  }

  updateConfig(config: SidecarConfig): void {
    const existing = this.configs.get(config.name);
    if (!existing) {
      throw new Error(`Sidecar config not found: ${config.name}');
    }
    
    const currentVersion = this.configVersions.get(config.name) || 1;
    this.configs.set(config.name, config);
    this.configVersions.set(config.name, currentVersion + 1);
    logger.info('Sidecar config updated', { name: config.name, version: currentVersion + 1 });
  }

  async injectSidecar(podId: string, sidecarName: string): Promise<SidecarInstance> {
    const config = this.configs.get(sidecarName);
    if (!config) {
      throw new Error(`Sidecar config not found: ${sidecarName}`);
    }

    const shouldInject = this.shouldInject(config, podId);
    if (!shouldInject) {
      throw new Error(`Injection not allowed for sidecar: ${sidecarName} in pod: ${podId}`);
    }

    const instanceId = `sidecar_${uuidv4()}`;
    const instance: SidecarInstance = {
      id: instanceId,
      config: { ...config },
      targetPod: podId,
      status: 'injected',
      injectedAt: new Date().toISOString(),
      lastConfigVersion: this.configVersions.get(sidecarName) || 1
    };

    this.sidecars.set(instanceId, instance);
    logger.info('Sidecar injected', { sidecarId: instanceId, podId, sidecarName });
    return instance;
  }

  private shouldInject(config: SidecarConfig, podId: string): boolean {
    switch (config.injectionStrategy) {
      case 'always': return true;
      case 'conditional': return Math.random() > 0.5;
      case 'manual': return false;
      default: return false;
    }
  }

  async startSidecar(sidecarId: string): Promise<void> {
    const sidecar = this.sidecars.get(sidecarId);
    if (!sidecar) {
      throw new Error(`Sidecar not found: ${sidecarId}`);
    }

    sidecar.status = 'running';
    sidecar.startedAt = new Date().toISOString();
    logger.info('Sidecar started', { sidecarId });
  }

  async stopSidecar(sidecarId: string): Promise<void> {
    const sidecar = this.sidecars.get(sidecarId);
    if (!sidecar) {
      throw new Error(`Sidecar not found: ${sidecarId}`);
    }

    sidecar.status = 'stopped';
    sidecar.stoppedAt = new Date().toISOString();
    logger.info('Sidecar stopped', { sidecarId });
  }

  async hotUpdate(sidecarId: string): Promise<HotUpdateResult> {
    const sidecar = this.sidecars.get(sidecarId);
    if (!sidecar) {
      throw new Error(`Sidecar not found: ${sidecarId}`);
    }

    const startTime = Date.now();
    const previousVersion = sidecar.config.version;
    
    const config = this.configs.get(sidecar.config.name);
    if (config) {
      sidecar.config = { ...config };
      sidecar.lastConfigVersion = this.configVersions.get(config.name) || 1;
    }

    return {
      success: true,
      updated: true,
      previousVersion,
      newVersion: sidecar.config.version,
      duration: Date.now() - startTime
    };
  }

  async removeSidecar(sidecarId: string): Promise<void> {
    const sidecar = this.sidecars.get(sidecarId);
    if (!sidecar) {
      throw new Error(`Sidecar not found: ${sidecarId}`);
    }

    if (sidecar.status === 'running') {
      await this.stopSidecar(sidecarId);
    }

    this.sidecars.delete(sidecarId);
    logger.info('Sidecar removed', { sidecarId });
  }

  getSidecar(sidecarId: string): SidecarInstance | undefined {
    return this.sidecars.get(sidecarId);
  }

  listSidecars(podId?: string): SidecarInstance[] {
    let sidecars = Array.from(this.sidecars.values());
    if (podId) {
      sidecars = sidecars.filter(s => s.targetPod === podId);
    }
    return sidecars;
  }

  getConfig(name: string): SidecarConfig | undefined {
    return this.configs.get(name);
  }

  listConfigs(): SidecarConfig[] {
    return Array.from(this.configs.values());
  }

  getStats(): { total: number; running: number; stopped: number; injected: number } {
    const sidecars = Array.from(this.sidecars.values());
    return {
      total: sidecars.length,
      running: sidecars.filter(s => s.status === 'running').length,
      stopped: sidecars.filter(s => s.status === 'stopped').length,
      injected: sidecars.filter(s => s.status === 'injected').length
    };
  }
}

export const createSidecarLifecycleManager = (): SidecarLifecycleManager => new SidecarLifecycleManager();
