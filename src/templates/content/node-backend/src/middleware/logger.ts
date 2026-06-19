import { Request, Response, NextFunction } from 'express';
import { logger } from './logger.js';

export function loggerMiddleware(req: Request, _res: Response, next: NextFunction): void {
  logger.info({
    method: req.method,
    url: req.url,
    ip: req.ip,
    userAgent: req.get('user-agent'),
  });
  next();
}
