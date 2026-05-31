import { Router } from 'express';
import { addressManagerRoutes } from '../modules/address-manager';
import { crossChainBridgeRoutes } from '../modules/cross-chain-bridge';
import { multisigCoordinatorRoutes } from '../modules/multisig-coordinator';
import { storageAdapterRoutes } from '../modules/storage-adapter';
import { transactionBuilderRoutes } from '../modules/transaction-builder';
import { chainAdapterRoutes } from '../modules/chain-adapter';
import { gasEstimatorRoutes } from '../modules/gas-estimator';
import { chainIndexerRoutes } from '../modules/chain-indexer';

const router = Router();

router.use('/addresses', addressManagerRoutes);
router.use('/bridge', crossChainBridgeRoutes);
router.use('/multisig', multisigCoordinatorRoutes);
router.use('/storage', storageAdapterRoutes);
router.use('/transactions', transactionBuilderRoutes);
router.use('/chain', chainAdapterRoutes);
router.use('/gas', gasEstimatorRoutes);
router.use('/indexer', chainIndexerRoutes);

export const apiRoutes = router;
export default apiRoutes;
