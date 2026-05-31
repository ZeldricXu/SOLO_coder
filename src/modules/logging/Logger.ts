import { generateId, getCurrentTimestamp, generateUUID } from '../../common/utils';
import { EventEmitter } from 'events';

export type LogLevel = 'debug' | 'info' | 'warn' | 'error' | 'fatal';

export interface LogEntry {
  id: string;
  timestamp: string;
  level: LogLevel;
  message: string;
  module?: string;
  traceId?: string;
  tenantId?: string;
  userId?: string;
  metadata?: Record<string, unknown>;
  error?: {
    name: string;
    message: string;
    stack?: string;
  };
}

export interface LogTransport {
  (entry: LogEntry): void | Promise<void>;
}

export interface LoggerConfig {
  defaultLevel?: LogLevel;
  maxBufferSize?: number;
  enableConsole?: boolean;
  enableFile?: boolean;
  logFilePath?: string;
  jsonFormat?: boolean;
  includeTimestamp?: boolean;
  includeTraceId?: boolean;
  levelOverrides?: Record<string, LogLevel>;
}

const LOG_LEVEL_ORDER: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
  fatal: 4
};

export class Logger extends EventEmitter {
  private config: Required<Omit<LoggerConfig, 'levelOverrides'>> & { levelOverrides: Record<string, LogLevel> };
  private buffer: LogEntry[];
  private transports: Set<LogTransport>;
  private globalLevel: LogLevel;
  private moduleLevels: Map<string, LogLevel>;
  private isEnabled: boolean;

  constructor(config: LoggerConfig = {}) {
    super();
    this.config = {
      defaultLevel: config.defaultLevel ?? 'info',
      maxBufferSize: config.maxBufferSize ?? 1000,
      enableConsole: config.enableConsole ?? true,
      enableFile: config.enableFile ?? false,
      logFilePath: config.logFilePath ?? './app.log',
      jsonFormat: config.jsonFormat ?? true,
      includeTimestamp: config.includeTimestamp ?? true,
      includeTraceId: config.includeTraceId ?? true,
      levelOverrides: config.levelOverrides ?? {}
    };

    this.buffer = [];
    this.transports = new Set();
    this.globalLevel = this.config.defaultLevel;
    this.moduleLevels = new Map(Object.entries(this.config.levelOverrides));
    this.isEnabled = true;

    if (this.config.enableConsole) {
      this.addTransport(this.consoleTransport);
    }
  }

  setLevel(level: LogLevel, module?: string): void {
    if (module) {
      this.moduleLevels.set(module, level);
      this.emit('level:changed', { module, level });
    } else {
      this.globalLevel = level;
      this.emit('level:changed', { level });
    }
  }

  getLevel(module?: string): LogLevel {
    if (module) {
      return this.moduleLevels.get(module) ?? this.globalLevel;
    }
    return this.globalLevel;
  }

  shouldLog(level: LogLevel, module?: string): boolean {
    if (!this.isEnabled) return false;
    const currentLevel = this.getLevel(module);
    return LOG_LEVEL_ORDER[level] >= LOG_LEVEL_ORDER[currentLevel];
  }

  enable(): void {
    this.isEnabled = true;
    this.emit('logger:enabled');
  }

  disable(): void {
    this.isEnabled = false;
    this.emit('logger:disabled');
  }

  addTransport(transport: LogTransport): () => void {
    this.transports.add(transport);
    return () => this.transports.delete(transport);
  }

  removeTransport(transport: LogTransport): boolean {
    return this.transports.delete(transport);
  }

  private consoleTransport: LogTransport = (entry: LogEntry) => {
    const levelColors: Record<LogLevel, string> = {
      debug: '\x1b[36m',
      info: '\x1b[32m',
      warn: '\x1b[33m',
      error: '\x1b[31m',
      fatal: '\x1b[35m'
    };

    const resetColor = '\x1b[0m';
    const color = levelColors[entry.level];

    if (this.config.jsonFormat) {
      const logLine = JSON.stringify({
        timestamp: entry.timestamp,
        level: entry.level,
        message: entry.message,
        ...(entry.module && { module: entry.module }),
        ...(entry.traceId && { traceId: entry.traceId }),
        ...(entry.metadata && Object.keys(entry.metadata).length > 0 && { metadata: entry.metadata }),
        ...(entry.error && { error: entry.error })
      });

      if (entry.level === 'error' || entry.level === 'fatal') {
        console.error(`${color}${logLine}${resetColor}`);
      } else {
        console.log(`${color}${logLine}${resetColor}`);
      }
    } else {
      const parts = [];
      if (this.config.includeTimestamp) parts.push(entry.timestamp);
      parts.push(`[${entry.level.toUpperCase()}]`);
      if (entry.module) parts.push(`[${entry.module}]`);
      if (this.config.includeTraceId && entry.traceId) parts.push(`[${entry.traceId}]`);
      parts.push(entry.message);

      const logLine = parts.join(' ');

      if (entry.level === 'error' || entry.level === 'fatal') {
        console.error(`${color}${logLine}${resetColor}`);
        if (entry.error?.stack) {
          console.error(entry.error.stack);
        }
      } else {
        console.log(`${color}${logLine}${resetColor}`);
      }
    }
  };

  private log(
    level: LogLevel,
    message: string,
    options: {
      module?: string;
      traceId?: string;
      tenantId?: string;
      userId?: string;
      metadata?: Record<string, unknown>;
      error?: unknown;
    } = {}
  ): LogEntry {
    if (!this.shouldLog(level, options.module)) {
      return {} as LogEntry;
    }

    const entry: LogEntry = {
      id: generateId('log'),
      timestamp: getCurrentTimestamp(),
      level,
      message,
      module: options.module,
      traceId: options.traceId,
      tenantId: options.tenantId,
      userId: options.userId,
      metadata: options.metadata
    };

    if (options.error instanceof Error) {
      entry.error = {
        name: options.error.name,
        message: options.error.message,
        stack: options.error.stack
      };
    } else if (options.error) {
      entry.error = {
        name: 'Error',
        message: String(options.error)
      };
    }

    this.addToBuffer(entry);
    this.emit('log', entry);
    this.sendToTransports(entry);

    return entry;
  }

  private addToBuffer(entry: LogEntry): void {
    this.buffer.push(entry);
    if (this.buffer.length > this.config.maxBufferSize) {
      this.buffer = this.buffer.slice(-this.config.maxBufferSize);
    }
  }

  private sendToTransports(entry: LogEntry): void {
    for (const transport of this.transports) {
      try {
        transport(entry);
      } catch (error) {
        console.error('日志传输失败:', error);
      }
    }
  }

  debug(
    message: string,
    options: {
      module?: string;
      traceId?: string;
      tenantId?: string;
      userId?: string;
      metadata?: Record<string, unknown>;
    } = {}
  ): LogEntry {
    return this.log('debug', message, options);
  }

  info(
    message: string,
    options: {
      module?: string;
      traceId?: string;
      tenantId?: string;
      userId?: string;
      metadata?: Record<string, unknown>;
    } = {}
  ): LogEntry {
    return this.log('info', message, options);
  }

  warn(
    message: string,
    options: {
      module?: string;
      traceId?: string;
      tenantId?: string;
      userId?: string;
      metadata?: Record<string, unknown>;
    } = {}
  ): LogEntry {
    return this.log('warn', message, options);
  }

  error(
    message: string,
    options: {
      module?: string;
      traceId?: string;
      tenantId?: string;
      userId?: string;
      metadata?: Record<string, unknown>;
      error?: unknown;
    } = {}
  ): LogEntry {
    return this.log('error', message, options);
  }

  fatal(
    message: string,
    options: {
      module?: string;
      traceId?: string;
      tenantId?: string;
      userId?: string;
      metadata?: Record<string, unknown>;
      error?: unknown;
    } = {}
  ): LogEntry {
    return this.log('fatal', message, options);
  }

  getLogs(filters?: {
    level?: LogLevel;
    module?: string;
    traceId?: string;
    tenantId?: string;
    startTimestamp?: string;
    endTimestamp?: string;
    limit?: number;
  }): LogEntry[] {
    let logs = [...this.buffer];

    if (filters) {
      if (filters.level) {
        logs = logs.filter(l => LOG_LEVEL_ORDER[l.level] >= LOG_LEVEL_ORDER[filters.level!]);
      }
      if (filters.module) {
        logs = logs.filter(l => l.module === filters.module);
      }
      if (filters.traceId) {
        logs = logs.filter(l => l.traceId === filters.traceId);
      }
      if (filters.tenantId) {
        logs = logs.filter(l => l.tenantId === filters.tenantId);
      }
      if (filters.startTimestamp) {
        logs = logs.filter(l => l.timestamp >= filters.startTimestamp!);
      }
      if (filters.endTimestamp) {
        logs = logs.filter(l => l.timestamp <= filters.endTimestamp!);
      }
    }

    logs.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());

    return filters?.limit ? logs.slice(0, filters.limit) : logs;
  }

  clearBuffer(): void {
    this.buffer = [];
    this.emit('buffer:cleared');
  }

  getModuleLevels(): Record<string, LogLevel> {
    return Object.fromEntries(this.moduleLevels);
  }

  setModuleLevel(module: string, level: LogLevel): void {
    this.moduleLevels.set(module, level);
    this.emit('level:changed', { module, level });
  }

  removeModuleLevel(module: string): boolean {
    return this.moduleLevels.delete(module);
  }

  getStats() {
    const levelCounts = this.buffer.reduce((acc, entry) => {
      acc[entry.level] = (acc[entry.level] || 0) + 1;
      return acc;
    }, {} as Record<LogLevel, number>);

    return {
      totalLogs: this.buffer.length,
      levelCounts,
      globalLevel: this.globalLevel,
      moduleLevels: this.getModuleLevels(),
      bufferSize: this.buffer.length,
      maxBufferSize: this.config.maxBufferSize,
      isEnabled: this.isEnabled,
      transportCount: this.transports.size
    };
  }

  child(options: { module: string; defaultMetadata?: Record<string, unknown> }): ChildLogger {
    return new ChildLogger(this, options);
  }

  destroy(): void {
    this.buffer = [];
    this.transports.clear();
    this.moduleLevels.clear();
    this.removeAllListeners();
  }
}

export class ChildLogger {
  private parent: Logger;
  private module: string;
  private defaultMetadata?: Record<string, unknown>;

  constructor(parent: Logger, options: { module: string; defaultMetadata?: Record<string, unknown> }) {
    this.parent = parent;
    this.module = options.module;
    this.defaultMetadata = options.defaultMetadata;
  }

  debug(message: string, metadata?: Record<string, unknown>, traceId?: string): LogEntry {
    return this.parent.debug(message, {
      module: this.module,
      traceId,
      metadata: { ...this.defaultMetadata, ...metadata }
    });
  }

  info(message: string, metadata?: Record<string, unknown>, traceId?: string): LogEntry {
    return this.parent.info(message, {
      module: this.module,
      traceId,
      metadata: { ...this.defaultMetadata, ...metadata }
    });
  }

  warn(message: string, metadata?: Record<string, unknown>, traceId?: string): LogEntry {
    return this.parent.warn(message, {
      module: this.module,
      traceId,
      metadata: { ...this.defaultMetadata, ...metadata }
    });
  }

  error(message: string, error?: unknown, metadata?: Record<string, unknown>, traceId?: string): LogEntry {
    return this.parent.error(message, {
      module: this.module,
      traceId,
      error,
      metadata: { ...this.defaultMetadata, ...metadata }
    });
  }

  fatal(message: string, error?: unknown, metadata?: Record<string, unknown>, traceId?: string): LogEntry {
    return this.parent.fatal(message, {
      module: this.module,
      traceId,
      error,
      metadata: { ...this.defaultMetadata, ...metadata }
    });
  }

  setLevel(level: LogLevel): void {
    this.parent.setLevel(level, this.module);
  }

  getLevel(): LogLevel {
    return this.parent.getLevel(this.module);
  }
}

let defaultLogger: Logger | null = null;

export function getLogger(config?: LoggerConfig): Logger {
  if (!defaultLogger) {
    defaultLogger = new Logger(config);
  }
  return defaultLogger;
}

export function createLogger(config?: LoggerConfig): Logger {
  return new Logger(config);
}
