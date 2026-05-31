import { EventEmitter } from 'events';
import { Request, Response, NextFunction } from 'express';
import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { generateId, currentTimestamp } from '../../utils/helpers';

export interface VersionTarget {
  version: string;
  weight: number;
  url: string;
  status: 'healthy' | 'unhealthy';
  requestCount: number;
  errorCount: number;
}

export interface CanaryConfig {
  id: string;
  name: string;
  enabled: boolean;
  service: string;
  targets: VersionTarget[];
  matchRules?: Array<{
    header?: string;
    cookie?: string;
    query?: string;
    value: string;
  }>;
  createdAt: string;
  updatedAt: string;
}

export interface BlueGreenConfig {
  id: string;
  name: string;
  enabled: boolean;
  service: string;
  blueVersion: string;
  greenVersion: string;
  blueUrl: string;
  greenUrl: string;
  activeColor: 'blue' | 'green';
  createdAt: string;
}

export interface MirrorConfig {
  id: string;
  name: string;
  enabled: boolean;
  sourceService: string;
  targetService: string;
  targetUrl: string;
  sampleRate: number;
  excludePaths: string[];
  createdAt: string;
}

export interface CircuitBreakerConfig {
  id: string;
  name: string;
  enabled: boolean;
  service: string;
  failureThreshold: number;
  successThreshold: number;
  timeout: number;
  fallbackUrl?: string;
}

export interface CircuitBreakerState {
  status: 'closed' | 'open' | 'half_open';
  failureCount: number;
  successCount: number;
  lastFailureTime: number;
  lastStateChange: number;
}

export type StrategyType = 'canary' | 'bluegreen' | 'mirror' | 'circuitbreaker';

export class TrafficController extends EventEmitter {
  private canaryConfigs: Map<string, CanaryConfig> = new Map();
  private blueGreenConfigs: Map<string, BlueGreenConfig> = new Map();
  private mirrorConfigs: Map<string, MirrorConfig> = new Map();
  private circuitBreakers: Map<string, { config: CircuitBreakerConfig; state: CircuitBreakerState }> = new Map();
  private requestCounters: Map<string, number> = new Map();

  constructor() {
    super();
    logger.info('TrafficController initialized');
  }

  createCanary(config: Omit<CanaryConfig, 'id' | 'createdAt' | 'updatedAt'>): CanaryConfig {
    const id = generateId('canary_');
    const now = currentTimestamp();
    const canary: CanaryConfig = {
      ...config,
      id,
      createdAt: now,
      updatedAt: now,
    };
    this.canaryConfigs.set(id, canary);
    logger.info('Canary config created', { id, name: config.name });
    eventBus.emit('canary.created', canary);
    return canary;
  }

  updateCanary(id: string, updates: Partial<CanaryConfig>): CanaryConfig | undefined {
    const canary = this.canaryConfigs.get(id);
    if (!canary) return undefined;
    
    const updated = { ...canary, ...updates, updatedAt: currentTimestamp() };
    this.canaryConfigs.set(id, updated);
    logger.info('Canary config updated', { id });
    eventBus.emit('canary.updated', updated);
    return updated;
  }

  deleteCanary(id: string): boolean {
    const deleted = this.canaryConfigs.delete(id);
    if (deleted) {
      logger.info('Canary config deleted', { id });
      eventBus.emit('canary.deleted', { id });
    }
    return deleted;
  }

  getCanary(id: string): CanaryConfig | undefined {
    return this.canaryConfigs.get(id);
  }

  listCanaries(): CanaryConfig[] {
    return Array.from(this.canaryConfigs.values());
  }

  createBlueGreen(config: Omit<BlueGreenConfig, 'id' | 'createdAt'>): BlueGreenConfig {
    const id = generateId('bg_');
    const bg: BlueGreenConfig = {
      ...config,
      id,
      createdAt: currentTimestamp(),
    };
    this.blueGreenConfigs.set(id, bg);
    logger.info('BlueGreen config created', { id, name: config.name });
    eventBus.emit('bluegreen.created', bg);
    return bg;
  }

  switchBlueGreen(id: string): BlueGreenConfig | undefined {
    const bg = this.blueGreenConfigs.get(id);
    if (!bg) return undefined;
    
    bg.activeColor = bg.activeColor === 'blue' ? 'green' : 'blue';
    this.blueGreenConfigs.set(id, bg);
    logger.info('BlueGreen switched', { id, newActive: bg.activeColor });
    eventBus.emit('bluegreen.switched', bg);
    return bg;
  }

  getBlueGreen(id: string): BlueGreenConfig | undefined {
    return this.blueGreenConfigs.get(id);
  }

  listBlueGreens(): BlueGreenConfig[] {
    return Array.from(this.blueGreenConfigs.values());
  }

  createMirror(config: Omit<MirrorConfig, 'id' | 'createdAt'>): MirrorConfig {
    const id = generateId('mirror_');
    const mirror: MirrorConfig = {
      ...config,
      id,
      createdAt: currentTimestamp(),
    };
    this.mirrorConfigs.set(id, mirror);
    logger.info('Mirror config created', { id, name: config.name });
    eventBus.emit('mirror.created', mirror);
    return mirror;
  }

  getMirror(id: string): MirrorConfig | undefined {
    return this.mirrorConfigs.get(id);
  }

  listMirrors(): MirrorConfig[] {
    return Array.from(this.mirrorConfigs.values());
  }

  deleteMirror(id: string): boolean {
    const deleted = this.mirrorConfigs.delete(id);
    if (deleted) {
      logger.info('Mirror config deleted', { id });
      eventBus.emit('mirror.deleted', { id });
    }
    return deleted;
  }

  createCircuitBreaker(config: CircuitBreakerConfig): CircuitBreakerConfig {
    const state: CircuitBreakerState = {
      status: 'closed',
      failureCount: 0,
      successCount: 0,
      lastFailureTime: 0,
      lastStateChange: Date.now(),
    };
    this.circuitBreakers.set(config.id, { config, state });
    logger.info('CircuitBreaker created', { id: config.id, name: config.name });
    return config;
  }

  recordSuccess(service: string): void {
    const cb = this.findCircuitBreaker(service);
    if (!cb) return;

    if (cb.state.status === 'half_open') {
      cb.state.successCount++;
      if (cb.state.successCount >= cb.config.successThreshold) {
        this.changeState(cb, 'closed');
      }
    } else if (cb.state.status === 'closed') {
      cb.state.failureCount = 0;
    }
  }

  recordFailure(service: string): void {
    const cb = this.findCircuitBreaker(service);
    if (!cb) return;

    cb.state.failureCount++;
    cb.state.lastFailureTime = Date.now();

    if (cb.state.status === 'closed' && cb.state.failureCount >= cb.config.failureThreshold) {
      this.changeState(cb, 'open');
    } else if (cb.state.status === 'half_open') {
      this.changeState(cb, 'open');
    }
  }

  isRequestAllowed(service: string): boolean {
    const cb = this.findCircuitBreaker(service);
    if (!cb) return true;

    if (cb.state.status === 'open') {
      if (Date.now() - cb.state.lastStateChange >= cb.config.timeout) {
        this.changeState(cb, 'half_open');
        return true;
      }
      return false;
    }

    return true;
  }

  private findCircuitBreaker(service: string): { config: CircuitBreakerConfig; state: CircuitBreakerState } | undefined {
    return Array.from(this.circuitBreakers.values()).find(
      cb => cb.config.service === service,
    );
  }

  private changeState(cb: { config: CircuitBreakerConfig; state: CircuitBreakerState }, newStatus: CircuitBreakerState['status']): void {
    cb.state.status = newStatus;
    cb.state.lastStateChange = Date.now();
    
    if (newStatus === 'closed') {
      cb.state.failureCount = 0;
      cb.state.successCount = 0;
    } else if (newStatus === 'half_open') {
      cb.state.successCount = 0;
    }

    logger.info('CircuitBreaker state changed', {
      service: cb.config.service,
      newStatus,
    });
    eventBus.emit('circuitbreaker.state_change', {
      service: cb.config.service,
      oldStatus: cb.state.status,
      newStatus,
    });
  }

  getCircuitBreakerState(service: string): CircuitBreakerState | undefined {
    return this.findCircuitBreaker(service)?.state;
  }

  listCircuitBreakers(): Array<{ config: CircuitBreakerConfig; state: CircuitBreakerState }> {
    return Array.from(this.circuitBreakers.values());
  }

  selectTarget(canary: CanaryConfig, req: Request): VersionTarget | undefined {
    if (!canary.enabled) return undefined;

    if (canary.matchRules && canary.matchRules.length > 0) {
      for (const rule of canary.matchRules) {
        if (rule.header && req.headers[rule.header.toLowerCase()] === rule.value) {
          return this.selectByWeight(canary.targets);
        }
        if (rule.cookie && req.cookies?.[rule.cookie] === rule.value) {
          return this.selectByWeight(canary.targets);
        }
        if (rule.query && req.query[rule.query] === rule.value) {
          return this.selectByWeight(canary.targets);
        }
      }
    }

    return this.selectByWeight(canary.targets);
  }

  private selectByWeight(targets: VersionTarget[]): VersionTarget {
    const totalWeight = targets.reduce((sum, t) => sum + t.weight, 0);
    let random = Math.random() * totalWeight;

    for (const target of targets) {
      random -= target.weight;
      if (random <= 0) {
        return target;
      }
    }

    return targets[targets.length - 1];
  }

  middleware(): (req: Request, res: Response, next: NextFunction) => void {
    return (req: Request, res: Response, next: NextFunction) => {
      const service = req.headers['x-service-name'] as string;
      
      if (service) {
        if (!this.isRequestAllowed(service)) {
          logger.warn('Request blocked by circuit breaker', { service, path: req.path });
          res.status(503).json({
            code: 503,
            message: 'Service temporarily unavailable',
          });
          return;
        }

        res.on('finish', () => {
          if (res.statusCode >= 500) {
            this.recordFailure(service);
          } else {
            this.recordSuccess(service);
          }
        });
      }

      next();
    };
  }

  shouldMirror(service: string, path: string): MirrorConfig | undefined {
    for (const mirror of this.mirrorConfigs.values()) {
      if (!mirror.enabled) continue;
      if (mirror.sourceService !== service) continue;
      if (mirror.excludePaths.some(p => path.startsWith(p))) continue;
      if (Math.random() > mirror.sampleRate) continue;
      
      return mirror;
    }
    return undefined;
  }

  getMetrics() {
    return {
      canaryCount: this.canaryConfigs.size,
      blueGreenCount: this.blueGreenConfigs.size,
      mirrorCount: this.mirrorConfigs.size,
      circuitBreakerCount: this.circuitBreakers.size,
      openCircuits: Array.from(this.circuitBreakers.values()).filter(
        cb => cb.state.status === 'open',
      ).length,
    };
  }
}

export const trafficController = new TrafficController();
