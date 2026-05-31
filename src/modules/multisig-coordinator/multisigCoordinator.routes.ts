import { Router } from 'express';
import { MultisigCoordinatorController } from './multisigCoordinator.controller';

const router = Router();
const controller = new MultisigCoordinatorController();

router.post('/', controller.createProposal);
router.get('/', controller.getProposals);
router.get('/pending/:walletId', controller.getPendingProposals);
router.get('/approved/:walletId', controller.getApprovedProposals);
router.get('/:id', controller.getProposal);
router.get('/:id/signatures', controller.getProposalSignatures);
router.get('/:id/can-execute', controller.canExecute);
router.post('/:id/sign', controller.signProposal);
router.post('/:id/execute', controller.executeProposal);
router.post('/:id/reject', controller.rejectProposal);

export const multisigCoordinatorRoutes = router;
export default multisigCoordinatorRoutes;
