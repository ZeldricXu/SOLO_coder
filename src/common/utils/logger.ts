import winston from 'winston';
import { RequestContext } from '../types';

const { combine, timestamp, json, colorize, printf } = winston.format;

const logFormat = printf(({ level, message, timestamp, ...meta }) => {
  return `${timestamp} [${level}]: ${message} ${Object.keys(meta).length ? JSON.stringify(meta) : ''}`;
});

export const logger = winston.createLogger({
  level: process.env.LOG_LEVEL || 'info',
  format: combine(
    timestamp({ format: 'YYYY-MM-DDTHH:mm:ssZ' }),
    process.env.NODE_ENV === 'production' ? json() : combine(colorize(), logFormat)
  ),
  transports: [
    new winston.transports.Console(),
    new winston.transports.File({ filename: 'logs/error.log', level: 'error' }),
    new winston.transports.File({ filename: 'logs/combined.log' })
  ]
});

export class ContextLogger {
  constructor(private ctx: RequestContext) {}

  info(message: string, meta?: Record<string, unknown>): void {
    logger.info(message, { ...meta, traceId: this.ctx.traceId, namespace: this.ctx.namespace });
  }

  error(message: string, meta?: Record<string, unknown>): void {
    logger.error(message, { ...meta, traceId: this.ctx.traceId, namespace: this.ctx.namespace });
  }

  warn(message: string, meta?: Record<string, unknown>): void {
    logger.warn(message, { ...meta, traceId: this.ctx.traceId, namespace: this.ctx.namespace });
  }

  debug(message: string, meta?: Record<string, unknown>): void {
    logger.debug(message, { ...meta, traceId: this.ctx.traceId, namespace: this.ctx.namespace });
  }
}
