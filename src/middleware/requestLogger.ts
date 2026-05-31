import { Request, Response, NextFunction } from 'express';
import logger from '../utils/logger';
import { v4 as uuidv4 } from 'uuid';

export const requestLogger = (req: Request, res: Response, next: NextFunction): void => {
  const traceId = uuidv4();
  (req as Record<string, unknown>).traceId = traceId;

  const startTime = Date.now();

  logger.info({
    traceId,
    method: req.method,
    path: req.path,
    query: req.query,
    ip: req.ip,
    userAgent: req.get('user-agent'),
  }, 'Incoming request');

  res.on('finish', () => {
    const duration = Date.now() - startTime;
    logger.info({
      traceId,
      method: req.method,
      path: req.path,
      statusCode: res.statusCode,
      durationMs: duration,
    }, 'Request completed');
  });

  next();
};

export const traceIdMiddleware = (req: Request, res: Response, next: NextFunction): void => {
  const existingTraceId = req.get('x-trace-id');
  const traceId = existingTraceId || uuidv4();
  (req as Record<string, unknown>).traceId = traceId;
  res.setHeader('x-trace-id', traceId);
  next();
};
