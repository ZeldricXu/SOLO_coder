import { Router } from 'express';
import { TransactionBuilderController } from './transactionBuilder.controller';

const router = Router();
const controller = new TransactionBuilderController();

router.post('/', controller.buildTransaction);
router.post('/multisig', controller.buildMultisigTransaction);
router.get('/', controller.getTransactions);
router.get('/hash/:hash', controller.getTransactionByHash);
router.get('/nonces/:address/:chainId', controller.getPendingNonces);
router.post('/optimize-gas', controller.optimizeGas);
router.get('/:id', controller.getTransaction);
router.post('/:id/sign', controller.signTransaction);
router.post('/:id/sign-multisig', controller.signMultisigTransaction);
router.patch('/:id/status', controller.updateTransactionStatus);

export const transactionBuilderRoutes = router;
export default transactionBuilderRoutes;
