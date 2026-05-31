import { Router } from 'express';
import { CrossChainBridgeController } from './crossChainBridge.controller';

const router = Router();
const controller = new CrossChainBridgeController();

router.post('/', controller.initiateTransfer);
router.get('/', controller.getTransfers);
router.get('/pending/:chainId', controller.getPendingTransfers);
router.get('/:id', controller.getTransfer);
router.post('/:id/lock', controller.confirmLock);
router.post('/:id/validate', controller.validateMessage);
router.post('/:id/mint', controller.executeMint);
router.post('/:id/confirm', controller.confirmTransfer);

export const crossChainBridgeRoutes = router;
export default crossChainBridgeRoutes;
