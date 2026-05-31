import { Router } from 'express';
import { ChainIndexerController } from './chainIndexer.controller';

const router = Router();
const controller = new ChainIndexerController();

router.post('/:chainId/blocks/:blockNumber', controller.indexBlock);
router.post('/:chainId/blocks', controller.indexBlocksRange);
router.get('/:chainId/blocks/latest', controller.getLatestIndexedBlock);
router.get('/:chainId/blocks/:blockNumber', controller.getIndexedBlock);
router.get('/:chainId/blocks', controller.getIndexedBlocks);
router.delete('/:chainId/blocks/:blockNumber', controller.deleteBlockIndex);

router.get('/:chainId/blocks-range', controller.getBlockRange);
router.get('/:chainId/transactions/search', controller.searchTransactions);
router.get('/:chainId/transactions/:hash', controller.getTransactionByHash);
router.get('/:chainId/contracts/:contractAddress/transactions', controller.getContractTransactions);
router.get('/:chainId/addresses/:address/transactions', controller.getAddressTransactions);

export const chainIndexerRoutes = router;
export default chainIndexerRoutes;
