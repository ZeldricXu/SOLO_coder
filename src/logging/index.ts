export type LogLevel = 'debug' | 'info' | 'warn' | 'error' | 'fatal';

export interface LogContext {
  traceId?: string;
  userId?: string;
  service?: string;
  module?: string;
  [key: string]: any;
}

export interface StructuredLogger {
  debug(message: string, context?: LogContext): void;
  info(message: string, context?: LogContext): void;
  warn(message: string, context?: LogContext): void;
  error(message: string, error?: Error, context?: LogContext): void;
  fatal(message: string, error?: Error, context?: LogContext): void;
  child(context: LogContext): StructuredLogger;
}

const LOG_LEVELS: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
  fatal: 4
};

const currentLogLevel: LogLevel = (process.env.LOG_LEVEL as LogLevel) || 'info';

class ConsoleLogger implements StructuredLogger {
  private baseContext: LogContext;

  constructor(context: LogContext = {}) {
    this.baseContext = context;
  }

  private shouldLog(level: LogLevel): boolean {
    return LOG_LEVELS[level] >= LOG_LEVELS[currentLogLevel];
  }

  private formatLog(level: string, message: string, context?: LogContext, error?: Error): string {
    const logEntry = {
      timestamp: new Date().toISOString(),
      level,
      message,
      ...this.baseContext,
      ...context
    };
    if (error) {
      (logEntry as any).error = { name: error.name, message: error.message, stack: error.stack };
    }
    return JSON.stringify(logEntry);
  }

  debug(message: string, context?: LogContext): void {
    if (this.shouldLog('debug')) {
      console.debug(this.formatLog('debug', message, context));
    }
  }

  info(message: string, context?: LogContext): void {
    if (this.shouldLog('info')) {
      console.info(this.formatLog('info', message, context));
    }
  }

  warn(message: string, context?: LogContext): void {
    if (this.shouldLog('warn')) {
      console.warn(this.formatLog('warn', message, context));
    }
  }

  error(message: string, error?: Error, context?: LogContext): void {
    if (this.shouldLog('error')) {
      console.error(this.formatLog('error', message, context, error));
    }
  }

  fatal(message: string, error?: Error, context?: LogContext): void {
    console.error(this.formatLog('fatal', message, context, error));
    process.exit(1);
  }

  child(context: LogContext): StructuredLogger {
    return new ConsoleLogger({ ...this.baseContext, ...context });
  }
}

export const logger: StructuredLogger = new ConsoleLogger({ service: 'enterprise-middleware' });

export const createLoggerWithContext = (context: LogContext): StructuredLogger => {
  return new ConsoleLogger(context);
};
