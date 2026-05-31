import { Router } from 'express';
import { GasController } from '../controllers/gas.controller';
import type { AppContainer } from '@application/container';

export function createGasRoutes(container: AppContainer): Router {
  const router = Router();
  const controller = new GasController(container);

  router.post('/estimate', controller.estimate.bind(controller));
  router.get('/price/:chainId', controller.getCurrentPrice.bind(controller));
  router.get('/history/:chainId', controller.getHistory.bind(controller));
  router.post('/optimize', controller.optimizeGasLimit.bind(controller));
  router.post('/cost', controller.calculateCost.bind(controller));

  return router;
}
