import { Router } from 'express';
import { GasEstimatorController } from './gasEstimator.controller';

const router = Router();
const controller = new GasEstimatorController();

router.post('/estimate', controller.estimateTransactionGas);
router.get('/:chainId', controller.getGasEstimate);
router.get('/:chainId/history', controller.getGasHistory);
router.get('/:chainId/statistics', controller.getGasStatistics);
router.post('/:chainId/record', controller.recordGasPrice);

export const gasEstimatorRoutes = router;
export default gasEstimatorRoutes;
