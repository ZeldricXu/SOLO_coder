import { EventEmitter } from 'events';
import { LogLevel, LogEntry } from '../types';
import { nowISO, generateId } from '../shared/utils';

const LOG_LEVELS: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
  fatal: 4,
};

interface LogTransport {
  name: string;
  minLevel: LogLevel;
  enabled: boolean;
  write: (entry: LogEntry) => void;
}

class ConsoleTransport implements LogTransport {
  name = 'console';
  minLevel: LogLevel = 'debug';
  enabled = true;

  private formatEntry(entry: LogEntry): string {
    const { timestamp, level, message, trace_id, context } = entry;
    const contextStr = Object.keys(context).length > 0 ? ` ${JSON.stringify(context)}` : '';
    return `[${timestamp}] [${level.toUpperCase()}] [${trace_id}] ${message}${contextStr}`;
  }

  write(entry: LogEntry): void {
    const formatted = this.formatEntry(entry);
    switch (entry.level) {
      case 'debug':
        console.debug(formatted);
        break;
      case 'info':
        console.info(formatted);
        break;
      case 'warn':
        console.warn(formatted);
        break;
      case 'error':
      case 'fatal':
        console.error(formatted);
        break;
    }
  }
}

class FileTransport implements LogTransport {
  name = 'file';
  minLevel: LogLevel = 'info';
  enabled = false;
  private logs: LogEntry[] = [];
  private maxSize = 10000;

  write(entry: LogEntry): void {
    this.logs.push(entry);
    if (this.logs.length > this.maxSize) {
      this.logs.shift();
    }
  }

  getLogs(level?: LogLevel, limit?: number): LogEntry[] {
    let filtered = this.logs;
    if (level) {
      const minLevel = LOG_LEVELS[level];
      filtered = filtered.filter((l) => LOG_LEVELS[l.level] >= minLevel);
    }
    if (limit) {
      filtered = filtered.slice(-limit);
    }
    return filtered;
  }

  clear(): void {
    this.logs = [];
  }
}

class LoggerService extends EventEmitter {
  private currentLevel: LogLevel = 'info';
  private transports: LogTransport[] = [];
  private defaultTraceId: string = 'system';
  private moduleLevels: Map<string, LogLevel> = new Map();

  constructor() {
    super();
    this.transports.push(new ConsoleTransport());
    this.transports.push(new FileTransport());
  }

  setLevel(level: LogLevel): void {
    this.currentLevel = level;
    this.emit('levelChanged', level);
  }

  getLevel(): LogLevel {
    return this.currentLevel;
  }

  setModuleLevel(module: string, level: LogLevel): void {
    this.moduleLevels.set(module, level);
    this.emit('moduleLevelChanged', module, level);
  }

  getModuleLevel(module: string): LogLevel | undefined {
    return this.moduleLevels.get(module);
  }

  setTransportLevel(transportName: string, level: LogLevel): void {
    const transport = this.transports.find((t) => t.name === transportName);
    if (transport) {
      transport.minLevel = level;
    }
  }

  enableTransport(transportName: string): void {
    const transport = this.transports.find((t) => t.name === transportName);
    if (transport) {
      transport.enabled = true;
    }
  }

  disableTransport(transportName: string): void {
    const transport = this.transports.find((t) => t.name === transportName);
    if (transport) {
      transport.enabled = false;
    }
  }

  getTransport<T extends LogTransport>(transportName: string): T | undefined {
    return this.transports.find((t) => t.name === transportName) as T | undefined;
  }

  private shouldLog(level: LogLevel): boolean {
    return LOG_LEVELS[level] >= LOG_LEVELS[this.currentLevel];
  }

  log(level: LogLevel, message: string, context: Record<string, unknown> = {}, traceId?: string): void {
    if (!this.shouldLog(level)) return;

    const entry: LogEntry = {
      timestamp: nowISO(),
      level,
      message,
      trace_id: traceId || this.defaultTraceId,
      context,
    };

    for (const transport of this.transports) {
      if (transport.enabled && LOG_LEVELS[level] >= LOG_LEVELS[transport.minLevel]) {
        try {
          transport.write(entry);
        } catch (error) {
          console.error(`Transport ${transport.name} error:`, error);
        }
      }
    }

    this.emit('log', entry);
  }

  debug(message: string, context?: Record<string, unknown>, traceId?: string): void {
    this.log('debug', message, context, traceId);
  }

  info(message: string, context?: Record<string, unknown>, traceId?: string): void {
    this.log('info', message, context, traceId);
  }

  warn(message: string, context?: Record<string, unknown>, traceId?: string): void {
    this.log('warn', message, context, traceId);
  }

  error(message: string, context?: Record<string, unknown>, traceId?: string): void {
    this.log('error', message, context, traceId);
  }

  fatal(message: string, context?: Record<string, unknown>, traceId?: string): void {
    this.log('fatal', message, context, traceId);
  }

  child(context: Record<string, unknown>): ChildLogger {
    return new ChildLogger(this, context);
  }
}

class ChildLogger {
  private parent: LoggerService;
  private context: Record<string, unknown>;

  constructor(parent: LoggerService, context: Record<string, unknown>) {
    this.parent = parent;
    this.context = context;
  }

  private mergeContext(additional?: Record<string, unknown>): Record<string, unknown> {
    return { ...this.context, ...additional };
  }

  debug(message: string, additional?: Record<string, unknown>, traceId?: string): void {
    this.parent.debug(message, this.mergeContext(additional), traceId);
  }

  info(message: string, additional?: Record<string, unknown>, traceId?: string): void {
    this.parent.info(message, this.mergeContext(additional), traceId);
  }

  warn(message: string, additional?: Record<string, unknown>, traceId?: string): void {
    this.parent.warn(message, this.mergeContext(additional), traceId);
  }

  error(message: string, additional?: Record<string, unknown>, traceId?: string): void {
    this.parent.error(message, this.mergeContext(additional), traceId);
  }

  fatal(message: string, additional?: Record<string, unknown>, traceId?: string): void {
    this.parent.fatal(message, this.mergeContext(additional), traceId);
  }
}

export const logger = new LoggerService();
export { LoggerService, ChildLogger, ConsoleTransport, FileTransport, LogTransport, LogLevel, LogEntry };
