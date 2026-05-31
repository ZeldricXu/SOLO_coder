import { Router } from 'express';
import { TransactionController } from '../controllers/transaction.controller';
import type { AppContainer } from '@application/container';

export function createTransactionRoutes(container: AppContainer): Router {
  const router = Router();
  const controller = new TransactionController(container);

  router.post('/build', controller.build.bind(controller));
  router.post('/build/contract', controller.buildContractCall.bind(controller));
  router.post('/sign', controller.attachSignature.bind(controller));
  router.post('/submit', controller.submit.bind(controller));
  router.get('/status/:chainId/:hash', controller.getStatus.bind(controller));
  router.get('/wait/:chainId/:hash', controller.waitForConfirmation.bind(controller));

  return router;
}
