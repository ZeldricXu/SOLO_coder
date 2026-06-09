import pino from 'pino';
import { config } from '@config/index';

const baseLogger = pino({
  level: config.logLevel,
  formatters: {
    level: (label) => ({ level: label }),
    log: (object) => {
      if (object.tenantId) {
        return { ...object };
      }
      return object;
    },
  },
  timestamp: pino.stdTimeFunctions.isoTime,
  base: {
    service: 'cms-multitenant-api',
    environment: config.nodeEnv,
  },
  serializers: {
    req: (req) => ({
      id: req.id,
      method: req.method,
      url: req.url,
      headers: config.nodeEnv === 'production'
        ? { host: req.headers.host }
        : req.headers,
      remoteAddress: req.ip,
    }),
    res: (res) => ({
      statusCode: res.statusCode,
      responseTime: res.responseTime,
    }),
    err: pino.stdSerializers.err,
  },
});

export interface LoggerContext {
  tenantId?: string;
  requestId?: string;
  userId?: string;
  [key: string]: unknown;
}

export const logger = {
  ...baseLogger,
  withContext: (context: LoggerContext) => baseLogger.child(context),
};

export default logger;
