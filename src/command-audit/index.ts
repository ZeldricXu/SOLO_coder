import { Command, AuditLog, ComplianceLevel, CommandStatus } from '../types';
import { logger } from '../logging';
import { v4 as uuidv4 } from 'uuid';
import { MultiLevelCache, CacheConfig, CacheStats } from './multi-level-cache';

export interface ComplianceReport {
  report_id: string;
  generated_at: string;
  period: { start: string; end: string };
  summary: {
    total_commands: number;
    failed_commands: number;
    high_risk_operations: number;
    compliance_score: number;
  };
  details: AuditLog[];
}

export interface BatchGetCommandsResult {
  found: Map<string, Command>;
  missing: string[];
  cachedFrom: Map<string, 'L1' | 'L2'>;
}

export interface BatchGetAuditTrailResult {
  found: Map<string, { command: Command | null; logs: AuditLog[] }>;
  missing: string[];
}

export class CommandAuditModule {
  private commands: Map<string, Command> = new Map();
  private auditLogs: AuditLog[] = [];
  private commandCache: MultiLevelCache<Command>;
  private auditLogCache: MultiLevelCache<AuditLog[]>;
  private reportCache: MultiLevelCache<ComplianceReport>;
  private cacheEnabled: boolean = true;

  constructor(cacheConfig?: CacheConfig) {
    const defaultConfig: CacheConfig = {
      l1TTL: 300,
      l2TTL: 3600,
      l1MaxSize: 10000,
      preheatKeys: [],
      invalidationPatterns: ['command:', 'audit:', 'report:']
    };
    
    this.commandCache = new MultiLevelCache<Command>(cacheConfig || defaultConfig);
    this.auditLogCache = new MultiLevelCache<AuditLog[]>(cacheConfig || defaultConfig);
    this.reportCache = new MultiLevelCache<ComplianceReport>(cacheConfig || defaultConfig);
  }

  setCacheEnabled(enabled: boolean): void {
    this.cacheEnabled = enabled;
    logger.info(`Cache ${enabled ? 'enabled' : 'disabled'} for command audit module`);
  }

  async createCommand(type: string, payload: Record<string, any>, issuedBy: string): Promise<Command> {
    const command: Command = {
      command_id: `cmd_${uuidv4()}`,
      type,
      payload,
      issued_by: issuedBy,
      issued_at: new Date().toISOString(),
      status: 'pending'
    };
    this.commands.set(command.command_id, command);
    await this.logAudit(command.command_id, 'command.created', issuedBy, { type, payload });
    
    if (this.cacheEnabled) {
      await this.commandCache.set(`command:${command.command_id}`, command);
    }
    
    return command;
  }

  async executeCommand<T>(commandId: string, executor: (payload: Record<string, any>) => Promise<T>): Promise<T> {
    const command = this.commands.get(commandId);
    if (!command) throw new Error(`Command not found: ${commandId}`);
    command.status = 'processing';
    await this.logAudit(commandId, 'command.started', command.issued_by, {});
    try {
      const result = await executor(command.payload);
      command.status = 'completed';
      command.result = result;
      await this.logAudit(commandId, 'command.completed', command.issued_by, { result });
      
      if (this.cacheEnabled) {
        await this.commandCache.set(`command:${commandId}`, command);
        await this.auditLogCache.delete(`audit:${commandId}`);
      }
      
      return result;
    } catch (error) {
      command.status = 'failed';
      command.error = (error as Error).message;
      await this.logAudit(commandId, 'command.failed', command.issued_by, { error: (error as Error).message }, 'high');
      
      if (this.cacheEnabled) {
        await this.commandCache.set(`command:${commandId}`, command);
        await this.auditLogCache.delete(`audit:${commandId}`);
      }
      
      throw error;
    }
  }

  private async logAudit(commandId: string, action: string, actor: string, details: Record<string, any>, level: ComplianceLevel = 'medium'): Promise<void> {
    const log: AuditLog = {
      log_id: `log_${uuidv4()}`,
      command_id: commandId,
      action,
      actor,
      timestamp: new Date().toISOString(),
      details,
      compliance_level: level
    };
    this.auditLogs.push(log);
  }

  async generateComplianceReport(startTime: string, endTime: string, forceRefresh: boolean = false): Promise<ComplianceReport> {
    const cacheKey = `report:${startTime}:${endTime}`;
    
    if (this.cacheEnabled && !forceRefresh) {
      const cached = await this.reportCache.get(cacheKey);
      if (cached) {
        logger.debug('Compliance report fetched from cache', { period: `${startTime} to ${endTime}` });
        return cached.value;
      }
    }

    const logs = this.auditLogs.filter(l => l.timestamp >= startTime && l.timestamp <= endTime);
    const commands = Array.from(this.commands.values());
    const failedCommands = commands.filter(c => c.status === 'failed').length;
    const highRiskOps = logs.filter(l => l.compliance_level === 'high').length;
    const totalCommands = commands.length;
    const complianceScore = totalCommands > 0 ? Math.round((1 - (failedCommands / totalCommands)) * 100) : 100;
    
    const report: ComplianceReport = {
      report_id: `report_${uuidv4()}`,
      generated_at: new Date().toISOString(),
      period: { start: startTime, end: endTime },
      summary: { total_commands: totalCommands, failed_commands: failedCommands, high_risk_operations: highRiskOps, compliance_score: complianceScore },
      details: logs
    };

    if (this.cacheEnabled) {
      await this.reportCache.set(cacheKey, report, 'BOTH', { l1: 1800, l2: 3600 });
    }

    return report;
  }

  async getCommand(commandId: string): Promise<Command | null> {
    if (this.cacheEnabled) {
      const cached = await this.commandCache.get(`command:${commandId}`);
      if (cached) {
        return cached.value;
      }
    }
    
    const command = this.commands.get(commandId) || null;
    
    if (this.cacheEnabled && command) {
      await this.commandCache.set(`command:${commandId}`, command);
    }
    
    return command;
  }

  async getAuditTrail(commandId: string): Promise<{ command: Command | null; logs: AuditLog[] }> {
    if (this.cacheEnabled) {
      const [cachedCommand, cachedLogs] = await Promise.all([
        this.commandCache.get(`command:${commandId}`),
        this.auditLogCache.get(`audit:${commandId}`)
      ]);
      
      if (cachedCommand && cachedLogs) {
        return { command: cachedCommand.value, logs: cachedLogs.value };
      }
    }

    const command = this.commands.get(commandId) || null;
    const logs = this.auditLogs.filter(l => l.command_id === commandId);
    
    if (this.cacheEnabled) {
      if (command) {
        await this.commandCache.set(`command:${commandId}`, command);
      }
      await this.auditLogCache.set(`audit:${commandId}`, logs);
    }
    
    return { command, logs };
  }

  async batchGetCommands(commandIds: string[]): Promise<BatchGetCommandsResult> {
    const found = new Map<string, Command>();
    const missing: string[] = [];
    const cachedFrom = new Map<string, 'L1' | 'L2'>();
    const toFetchFromStore: string[] = [];

    if (this.cacheEnabled) {
      for (const id of commandIds) {
        const cached = await this.commandCache.get(`command:${id}`);
        if (cached) {
          found.set(id, cached.value);
          cachedFrom.set(id, cached.source === 'L1' ? 'L1' : 'L2');
        } else {
          toFetchFromStore.push(id);
        }
      }
    } else {
      toFetchFromStore.push(...commandIds);
    }

    for (const id of toFetchFromStore) {
      const command = this.commands.get(id);
      if (command) {
        found.set(id, command);
        if (this.cacheEnabled) {
          await this.commandCache.set(`command:${id}`, command);
        }
      } else {
        missing.push(id);
      }
    }

    logger.debug('Batch get commands completed', { found: found.size, missing: missing.length });
    return { found, missing, cachedFrom };
  }

  async batchGetAuditTrails(commandIds: string[]): Promise<BatchGetAuditTrailResult> {
    const found = new Map<string, { command: Command | null; logs: AuditLog[] }>();
    const missing: string[] = [];

    const results = await Promise.all(
      commandIds.map(async (id) => {
        try {
          const trail = await this.getAuditTrail(id);
          return { id, trail, exists: this.commands.has(id) };
        } catch {
          return { id, trail: null, exists: false };
        }
      })
    );

    for (const result of results) {
      if (result.trail && result.exists) {
        found.set(result.id, result.trail);
      } else {
        missing.push(result.id);
      }
    }

    logger.debug('Batch get audit trails completed', { found: found.size, missing: missing.length });
    return { found, missing };
  }

  async invalidateCommandCache(commandId?: string): Promise<number> {
    if (commandId) {
      await this.commandCache.delete(`command:${commandId}`);
      await this.auditLogCache.delete(`audit:${commandId}`);
      return 2;
    } else {
      const deleted = await Promise.all([
        this.commandCache.clear(),
        this.auditLogCache.clear(),
        this.reportCache.clear()
      ]);
      return 3;
    }
  }

  async invalidateReportCache(): Promise<number> {
    const deleted = await this.reportCache.deletePattern('report:');
    logger.info('Report cache invalidated', { deletedCount: deleted });
    return deleted;
  }

  getCacheStats(): { command: CacheStats; auditLog: CacheStats; report: CacheStats } {
    return {
      command: this.commandCache.getStats(),
      auditLog: this.auditLogCache.getStats(),
      report: this.reportCache.getStats()
    };
  }

  async close(): Promise<void> {
    await Promise.all([
      this.commandCache.close(),
      this.auditLogCache.close(),
      this.reportCache.close()
    ]);
    logger.info('Command audit module closed');
  }
}

export const createCommandAuditModule = (cacheConfig?: CacheConfig): CommandAuditModule => {
  return new CommandAuditModule(cacheConfig);
};

export { MultiLevelCache, CacheConfig, CacheStats } from './multi-level-cache';
