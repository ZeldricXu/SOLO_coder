import * as crypto from 'crypto';
import { IEnclaveManager } from './interfaces';
import { EnclaveConfig, EnclaveStatus, EnclaveStatusType } from '../core/types';

export class EnclaveManager implements IEnclaveManager {
  private readonly enclaves: Map<string, EnclaveConfig> = new Map();
  private readonly enclaveStatuses: Map<string, EnclaveStatus> = new Map();

  constructor() {}

  public createEnclave(name: string, attributes: string[] = []): EnclaveConfig {
    const enclaveId = crypto.randomUUID();
    const mrenclave = this.generateMeasurement(enclaveId, name);
    const mrsigner = this.generateSignerMeasurement();

    const config: EnclaveConfig = {
      enclaveId,
      name,
      mrenclave,
      mrsigner,
      isvProdId: Math.floor(Math.random() * 65535),
      isvSvn: 1,
      attributes
    };

    const status: EnclaveStatus = {
      enclaveId,
      status: 'initialized',
      memoryUsage: 0,
      cpuUsage: 0,
      uptime: 0,
      isHealthy: true
    };

    this.enclaves.set(enclaveId, config);
    this.enclaveStatuses.set(enclaveId, status);

    return config;
  }

  public initializeEnclave(enclaveId: string): boolean {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status || status.status !== 'initialized') return false;

    status.status = 'running';
    status.uptime = Date.now();
    this.enclaveStatuses.set(enclaveId, status);
    return true;
  }

  public suspendEnclave(enclaveId: string): boolean {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status || status.status !== 'running') return false;

    status.status = 'suspended';
    this.enclaveStatuses.set(enclaveId, status);
    return true;
  }

  public resumeEnclave(enclaveId: string): boolean {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status || status.status !== 'suspended') return false;

    status.status = 'running';
    this.enclaveStatuses.set(enclaveId, status);
    return true;
  }

  public terminateEnclave(enclaveId: string): boolean {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status) return false;

    status.status = 'terminated';
    status.isHealthy = false;
    this.enclaveStatuses.set(enclaveId, status);
    return true;
  }

  public getEnclaveConfig(enclaveId: string): EnclaveConfig | undefined {
    return this.enclaves.get(enclaveId);
  }

  public getEnclaveStatus(enclaveId: string): EnclaveStatus | undefined {
    return this.enclaveStatuses.get(enclaveId);
  }

  public getAllEnclaves(): EnclaveConfig[] {
    return Array.from(this.enclaves.values());
  }

  public updateEnclaveSvn(enclaveId: string): boolean {
    const config = this.enclaves.get(enclaveId);
    if (!config) return false;

    config.isvSvn++;
    this.enclaves.set(enclaveId, config);
    return true;
  }

  public verifyEnclaveIdentity(enclaveId: string, expectedMrenclave?: string, expectedMrsigner?: string): boolean {
    const config = this.enclaves.get(enclaveId);
    if (!config) return false;

    if (expectedMrenclave && config.mrenclave !== expectedMrenclave) return false;
    if (expectedMrsigner && config.mrsigner !== expectedMrsigner) return false;

    return true;
  }

  public isEnclaveRunning(enclaveId: string): boolean {
    const status = this.enclaveStatuses.get(enclaveId);
    return status?.status === 'running' || false;
  }

  public updateEnclaveResources(enclaveId: string, cpuDelta: number, memoryDelta: number): void {
    const status = this.enclaveStatuses.get(enclaveId);
    if (!status) return;

    status.cpuUsage = Math.min(100, Math.max(0, status.cpuUsage + cpuDelta));
    status.memoryUsage = Math.min(1000, Math.max(0, status.memoryUsage + memoryDelta));
    this.enclaveStatuses.set(enclaveId, status);
  }

  private generateMeasurement(enclaveId: string, name: string): string {
    return crypto
      .createHash('sha256')
      .update(enclaveId + name + Date.now())
      .digest('hex');
  }

  private generateSignerMeasurement(): string {
    return crypto
      .createHash('sha256')
      .update('signer-key' + crypto.randomBytes(32).toString('hex'))
      .digest('hex');
  }
}

export const createEnclaveManager = (): EnclaveManager => {
  return new EnclaveManager();
};
