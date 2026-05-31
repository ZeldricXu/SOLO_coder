import { Request, Response, NextFunction } from 'express';
import { randomUUID } from 'crypto';

export const tenantMiddleware = (req: Request, res: Response, next: NextFunction): void => {
  const traceId = req.headers['x-trace-id'] as string || randomUUID();
  const tenantId = req.headers['x-tenant-id'] as string;

  res.setHeader('x-trace-id', traceId);
  res.setHeader('x-tenant-id', tenantId || '');

  if (!tenantId && !req.path.startsWith('/health') && !req.path.startsWith('/docs')) {
    res.status(400).json({
      code: 400,
      error: 'BAD_REQUEST',
      message: 'Tenant ID is required in x-tenant-id header',
      traceId
    });
    return;
  }

  next();
};
