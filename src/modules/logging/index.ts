import pino from 'pino';
import { ILogger } from '@ports/index';
import { config } from '@config/index';
import { generateTraceId, nowISO } from '@utils/index';

export class Logger implements ILogger {
  private logger: pino.Logger;
  private context: Record<string, unknown>;

  constructor(context: Record<string, unknown> = {}) {
    this.context = context;
    this.logger = pino({
      level: config.logging.level,
      timestamp: () => `,"timestamp":"${nowISO()}"`,
      base: {
        service: 'metricplatform',
        ...this.context,
      },
      formatters: {
        level: (label) => ({ level: label }),
      },
      ...(config.logging.prettyPrint
        ? {
            transport: {
              target: 'pino-pretty',
              options: {
                colorize: true,
                translateTime: 'SYS:standard',
                ignore: 'pid,hostname',
              },
            },
          }
        : {}),
    });
  }

  private formatData(data?: Record<string, unknown>): Record<string, unknown> {
    return {
      trace_id: this.context.trace_id || generateTraceId(),
      ...data,
    };
  }

  debug(message: string, data?: Record<string, unknown>): void {
    this.logger.debug(this.formatData(data), message);
  }

  info(message: string, data?: Record<string, unknown>): void {
    this.logger.info(this.formatData(data), message);
  }

  warn(message: string, data?: Record<string, unknown>): void {
    this.logger.warn(this.formatData(data), message);
  }

  error(message: string, data?: Record<string, unknown>): void {
    this.logger.error(this.formatData(data), message);
  }

  fatal(message: string, data?: Record<string, unknown>): void {
    this.logger.fatal(this.formatData(data), message);
  }

  child(context: Record<string, unknown>): ILogger {
    return new Logger({
      ...this.context,
      ...context,
    });
  }
}

export const createLogger = (context: Record<string, unknown> = {}): ILogger => {
  return new Logger(context);
};

export const rootLogger = createLogger();
