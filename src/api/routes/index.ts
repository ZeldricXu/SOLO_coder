import { Router } from 'express';
import type { AppContainer } from '@application/container';
import { createGasRoutes } from './gas.routes';
import { createTransactionRoutes } from './transaction.routes';

export function createApiRouter(container: AppContainer): Router {
  const router = Router();

  router.get('/health', (_req, res) => {
    res.json({
      code: 200,
      data: {
        status: 'healthy',
        timestamp: new Date().toISOString(),
      },
    });
  });

  router.use('/gas', createGasRoutes(container));
  router.use('/transactions', createTransactionRoutes(container));

  return router;
}
