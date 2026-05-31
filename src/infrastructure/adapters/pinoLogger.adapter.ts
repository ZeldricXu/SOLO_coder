import pino from 'pino';
import type { Logger, LogLevelType } from '@shared/logger';

export class PinoLogger implements Logger {
  private logger: pino.Logger;

  constructor(
    private readonly name: string = 'app',
    private readonly level: LogLevelType = 'info',
    private readonly prettyPrint: boolean = true
  ) {
    this.logger = pino({
      name,
      level,
      transport: prettyPrint
        ? {
            target: 'pino-pretty',
            options: {
              colorize: true,
              translateTime: 'SYS:standard',
              ignore: 'pid,hostname',
            },
          }
        : undefined,
    });
  }

  info(message: string, meta?: Record<string, unknown>): void {
    if (meta) {
      this.logger.info(meta, message);
    } else {
      this.logger.info(message);
    }
  }

  warn(message: string, meta?: Record<string, unknown>): void {
    if (meta) {
      this.logger.warn(meta, message);
    } else {
      this.logger.warn(message);
    }
  }

  error(message: string, meta?: Record<string, unknown>): void {
    if (meta) {
      this.logger.error(meta, message);
    } else {
      this.logger.error(message);
    }
  }

  debug(message: string, meta?: Record<string, unknown>): void {
    if (meta) {
      this.logger.debug(meta, message);
    } else {
      this.logger.debug(message);
    }
  }

  child(meta: Record<string, unknown>): Logger {
    const childLogger = this.logger.child(meta);
    return {
      info: (msg: string, m?: Record<string, unknown>) =>
        m ? childLogger.info(m, msg) : childLogger.info(msg),
      warn: (msg: string, m?: Record<string, unknown>) =>
        m ? childLogger.warn(m, msg) : childLogger.warn(msg),
      error: (msg: string, m?: Record<string, unknown>) =>
        m ? childLogger.error(m, msg) : childLogger.error(msg),
      debug: (msg: string, m?: Record<string, unknown>) =>
        m ? childLogger.debug(m, msg) : childLogger.debug(msg),
      child: (newMeta: Record<string, unknown>) => this.child({ ...meta, ...newMeta }),
    };
  }

  static create(
    name?: string,
    level?: LogLevelType,
    prettyPrint?: boolean
  ): PinoLogger {
    return new PinoLogger(name, level, prettyPrint);
  }
}
