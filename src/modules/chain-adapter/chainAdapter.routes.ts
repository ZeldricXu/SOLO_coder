import { Router } from 'express';
import { ChainAdapterController } from './chainAdapter.controller';

const router = Router();
const controller = new ChainAdapterController();

router.get('/chains', controller.listChainConfigs);
router.post('/chains', controller.addChainConfig);
router.get('/chains/:chainId', controller.getChainConfig);
router.put('/chains/:chainId', controller.updateChainConfig);

router.get('/:chainId/block-number', controller.getBlockNumber);
router.get('/:chainId/blocks/:block', controller.getBlock);
router.get('/:chainId/transactions/:hash', controller.getTransaction);
router.get('/:chainId/transactions/:hash/receipt', controller.getTransactionReceipt);
router.get('/:chainId/balances/:address', controller.getBalance);
router.get('/:chainId/nonces/:address', controller.getNonce);
router.post('/:chainId/call', controller.call);
router.post('/:chainId/estimate-gas', controller.estimateGas);
router.get('/:chainId/gas-price', controller.getGasPrice);
router.get('/:chainId/fee-data', controller.getFeeData);
router.post('/:chainId/broadcast', controller.broadcastTransaction);
router.post('/:chainId/wait/:hash', controller.waitForTransaction);

export const chainAdapterRoutes = router;
export default chainAdapterRoutes;
