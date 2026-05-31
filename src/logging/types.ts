export type LogLevel = 'debug' | 'info' | 'warn' | 'error' | 'fatal';

export interface LogEntry {
  timestamp: string;
  level: LogLevel;
  message: string;
  context: Record<string, unknown>;
  traceId?: string;
  service?: string;
  hostname?: string;
  pid?: number;
}

export interface LoggerConfig {
  level: LogLevel;
  enableConsole: boolean;
  enableFile: boolean;
  filePath?: string;
  maxFileSize?: number;
  maxFiles?: number;
  enableJsonFormat: boolean;
  includeContext: boolean;
  serviceName?: string;
}

export interface LogFilter {
  level?: LogLevel;
  message?: string;
  startTime?: string;
  endTime?: string;
  traceId?: string;
  context?: Record<string, unknown>;
}

export const LOG_LEVELS: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
  fatal: 4,
};
