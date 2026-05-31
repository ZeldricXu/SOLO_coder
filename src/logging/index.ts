import EventEmitter from 'eventemitter3';
import fs from 'fs';
import path from 'path';
import { v4 as uuidv4 } from 'uuid';
import { LogLevel, LogEntry, LoggerConfig, LogFilter, LOG_LEVELS } from './types';

export class Logger extends EventEmitter {
  private config: LoggerConfig;
  private logBuffer: LogEntry[] = [];
  private fileWriteTimer?: NodeJS.Timeout;

  constructor(config: Partial<LoggerConfig> = {}) {
    super();
    this.config = {
      level: 'info',
      enableConsole: true,
      enableFile: false,
      enableJsonFormat: true,
      includeContext: true,
      serviceName: 'default',
      ...config,
    };

    if (this.config.enableFile) {
      this.startFileWriter();
    }
  }

  debug(message: string, context: Record<string, unknown> = {}, traceId?: string): void {
    this.log('debug', message, context, traceId);
  }

  info(message: string, context: Record<string, unknown> = {}, traceId?: string): void {
    this.log('info', message, context, traceId);
  }

  warn(message: string, context: Record<string, unknown> = {}, traceId?: string): void {
    this.log('warn', message, context, traceId);
  }

  error(message: string, context: Record<string, unknown> = {}, traceId?: string): void {
    this.log('error', message, context, traceId);
  }

  fatal(message: string, context: Record<string, unknown> = {}, traceId?: string): void {
    this.log('fatal', message, context, traceId);
  }

  private log(level: LogLevel, message: string, context: Record<string, unknown>, traceId?: string): void {
    if (!this.shouldLog(level)) return;

    const entry: LogEntry = {
      timestamp: new Date().toISOString(),
      level,
      message,
      context: this.config.includeContext ? context : {},
      traceId: traceId || uuidv4(),
      service: this.config.serviceName,
      hostname: require('os').hostname(),
      pid: process.pid,
    };

    this.logBuffer.push(entry);
    this.emit('log', entry);
    this.emit(`log:${level}`, entry);

    if (this.config.enableConsole) {
      this.writeToConsole(entry);
    }

    if (this.logBuffer.length > 1000) {
      this.flushBuffer();
    }
  }

  private shouldLog(level: LogLevel): boolean {
    return LOG_LEVELS[level] >= LOG_LEVELS[this.config.level];
  }

  private writeToConsole(entry: LogEntry): void {
    const levelColors: Record<LogLevel, string> = {
      debug: '\x1b[36m',
      info: '\x1b[32m',
      warn: '\x1b[33m',
      error: '\x1b[31m',
      fatal: '\x1b[35m',
    };
    const reset = '\x1b[0m';

    if (this.config.enableJsonFormat) {
      console.log(`${levelColors[entry.level]}${JSON.stringify(entry)}${reset}`);
    } else {
      const contextStr = Object.keys(entry.context).length > 0
        ? ` ${JSON.stringify(entry.context)}`
        : '';
      console.log(
        `${levelColors[entry.level]}[${entry.timestamp}] [${entry.level.toUpperCase()}] [${entry.traceId}] ${entry.message}${contextStr}${reset}`
      );
    }
  }

  private startFileWriter(): void {
    this.fileWriteTimer = setInterval(() => {
      if (this.logBuffer.length > 0) {
        this.flushBuffer();
      }
    }, 1000);
  }

  private flushBuffer(): void {
    if (!this.config.enableFile || !this.config.filePath) return;

    try {
      const dir = path.dirname(this.config.filePath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }

      const lines = this.logBuffer.map(entry => JSON.stringify(entry)).join('\n') + '\n';
      fs.appendFileSync(this.config.filePath, lines);
      this.logBuffer = [];
    } catch (error) {
      console.error('[Logger] Failed to write logs to file:', error);
    }
  }

  setLevel(level: LogLevel): void {
    const oldLevel = this.config.level;
    this.config.level = level;
    this.emit('level-changed', { oldLevel, newLevel: level });
  }

  getLevel(): LogLevel {
    return this.config.level;
  }

  enableConsole(): void {
    this.config.enableConsole = true;
  }

  disableConsole(): void {
    this.config.enableConsole = false;
  }

  enableFile(filePath: string): void {
    this.config.enableFile = true;
    this.config.filePath = filePath;
    this.startFileWriter();
  }

  disableFile(): void {
    this.config.enableFile = false;
    if (this.fileWriteTimer) {
      clearInterval(this.fileWriteTimer);
      this.fileWriteTimer = undefined;
    }
    this.flushBuffer();
  }

  queryLogs(filter: LogFilter): LogEntry[] {
    return this.logBuffer.filter(entry => {
      if (filter.level && entry.level !== filter.level) return false;
      if (filter.message && !entry.message.includes(filter.message)) return false;
      if (filter.startTime && entry.timestamp < filter.startTime) return false;
      if (filter.endTime && entry.timestamp > filter.endTime) return false;
      if (filter.traceId && entry.traceId !== filter.traceId) return false;
      if (filter.context) {
        for (const [key, value] of Object.entries(filter.context)) {
          if (entry.context[key] !== value) return false;
        }
      }
      return true;
    });
  }

  getBuffer(): LogEntry[] {
    return [...this.logBuffer];
  }

  clearBuffer(): void {
    this.logBuffer = [];
  }

  child(context: Record<string, unknown>): Logger {
    const childLogger = new Logger(this.config);
    const originalLog = childLogger['log'].bind(childLogger);

    childLogger['log'] = (level: LogLevel, message: string, ctx: Record<string, unknown>, traceId?: string) => {
      originalLog(level, message, { ...context, ...ctx }, traceId);
    };

    return childLogger;
  }

  destroy(): void {
    this.flushBuffer();
    if (this.fileWriteTimer) {
      clearInterval(this.fileWriteTimer);
    }
    this.removeAllListeners();
  }
}

let defaultLogger: Logger | null = null;

export function getLogger(config?: Partial<LoggerConfig>): Logger {
  if (!defaultLogger) {
    defaultLogger = new Logger(config);
  }
  return defaultLogger;
}

export * from './types';
