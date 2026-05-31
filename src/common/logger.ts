import pino from 'pino';
import dayjs from 'dayjs';

export const logger = pino({
  level: process.env.LOG_LEVEL || 'info',
  base: {
    service: 'contract-audit-platform',
  },
  timestamp: () => `,"time":"${dayjs().toISOString()}"`,
  formatters: {
    level: (label) => ({ level: label }),
  },
});

export class LoggerContext {
  private context: Record<string, unknown>;

  constructor(context: Record<string, unknown>) {
    this.context = context;
  }

  private formatMessage(message: string, meta?: Record<string, unknown>): { msg: string; context: Record<string, unknown> } {
    return {
      msg: message,
      context: {
        ...this.context,
        ...meta,
      },
    };
  }

  info(message: string, meta?: Record<string, unknown>): void {
    logger.info(this.formatMessage(message, meta));
  }

  error(message: string, error?: Error, meta?: Record<string, unknown>): void {
    logger.error({
      ...this.formatMessage(message, meta),
      error: error?.message,
      stack: error?.stack,
    });
  }

  warn(message: string, errorOrMeta?: Error | Record<string, unknown>, meta?: Record<string, unknown>): void {
    let error: Error | undefined;
    let actualMeta: Record<string, unknown> | undefined;

    if (errorOrMeta instanceof Error) {
      error = errorOrMeta;
      actualMeta = meta;
    } else {
      actualMeta = errorOrMeta;
    }

    logger.warn({
      ...this.formatMessage(message, actualMeta),
      error: error?.message,
      stack: error?.stack,
    });
  }

  debug(message: string, meta?: Record<string, unknown>): void {
    logger.debug(this.formatMessage(message, meta));
  }

  child(additionalContext: Record<string, unknown>): LoggerContext {
    return new LoggerContext({
      ...this.context,
      ...additionalContext,
    });
  }
}
